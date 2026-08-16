package com.digixmed.cloud.icu.service;

import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 推送队列服务（vitalsign_push_queue）
 *
 * 状态机（简化版）：
 *   FAILED  → 推送中（原子领取） → SUCCESS
 *                                 → FAILED（失败回到待推送）
 *
 * 内容变化检测：
 *   SUCCESS 记录的 payloadHash 变化时 → 保存旧值到 invalidationPayload
 *   推送时先发 isValid=0（作废旧值），再发 isValid=1（新值生效）
 */
@Service
public class IntermediateService {

    private static final Logger log = LoggerFactory.getLogger(IntermediateService.class);

    public static final String PUSH_COLLECTION = "vitalsign_push_queue";
    private static final String COLLECTION = PUSH_COLLECTION;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private MongoTemplate mongoTemplate;

    // ==================== 核心方法 ====================

    /**
     * 入队：创建或更新待推送记录
     *
     * 逻辑：
     *   1. 无记录 → 插入 FAILED
     *   2. 有记录，内容未变 + SUCCESS → 跳过
     *   3. 有记录，内容未变 + FAILED → 更新字段（重试）
     *   4. 有记录，内容变了 → 保存旧值，设 invalidationNeeded=true，状态→FAILED
     */
    public Map<String, Object> upsertPending(VitalSignPayload payload, String traceId) {
        if (payload == null || payload.getPatientId() == null || payload.getPatientId().trim().isEmpty()) {
            Map<String, Object> skipped = new HashMap<>();
            skipped.put("action", "SKIP");
            skipped.put("status", "SKIPPED_NO_PATIENT_ID");
            return skipped;
        }

        String planTimeStr = payload.getPlanTime() != null ? payload.getPlanTime().format(FORMATTER) : "";
        String idempotencyKey = String.format("%s_%s_%s_%s",
                payload.getPatientId(), payload.getSeries(), payload.getVitalsignType(), planTimeStr);

        Query query = new Query(Criteria.where("idempotencyKey").is(idempotencyKey));
        Document existing = mongoTemplate.findOne(query, Document.class, COLLECTION);

        mergeRecheckState(payload, existing);
        String payloadHash = computeSha256(payload);

        Map<String, Object> result = new HashMap<>();
        result.put("idempotencyKey", idempotencyKey);
        result.put("payloadHash", payloadHash);
        Date now = new Date();

        // ===== 已存在记录 =====
        if (existing != null) {
            String existingHash = existing.getString("payloadHash");
            String existingStatus = existing.getString("status");

            // 内容一致 + 已成功 → 跳过
            if (payloadHash.equals(existingHash) && "SUCCESS".equals(existingStatus)) {
                result.put("action", "SKIP");
                result.put("status", "SUCCESS");
                result.put("id", existing.get("_id").toString());
                return result;
            }

            // 内容变化 → 保存旧值，标记需要作废
            boolean contentChanged = !payloadHash.equals(existingHash);
            Update update = buildPayloadUpdate(payload, payloadHash, traceId);
            update.set("status", "FAILED");
            update.set("retryCount", 0);
            update.set("lastErrorCode", null);
            update.set("lastErrorMessage", null);
            update.set("requestMsg", null);
            update.set("requestBodyMasked", null);
            update.set("responseMsg", null);
            update.set("responseBodyMasked", null);
            update.set("sentAt", null);
            update.set("claimedAt", null);
            update.set("updatedAt", now);

            if (contentChanged) {
                Document invPayload = buildInvalidationPayload(existing);
                update.set("invalidationNeeded", true);
                update.set("invalidationPayload", invPayload);
                log.info("INVALIDATION_QUEUED traceId={} key={} 内容变化，旧值已保存", traceId, idempotencyKey);
                result.put("action", "INVALIDATE_THEN_UPDATE");
            } else {
                // 内容未变但之前失败 → 直接重试，不需要作废
                result.put("action", "RETRY");
            }

            mongoTemplate.updateFirst(query, update, COLLECTION);
            result.put("status", "FAILED");
            result.put("id", existing.get("_id").toString());
            return result;
        }

        // ===== 新记录 =====
        Document doc = new Document();
        doc.append("idempotencyKey", idempotencyKey);
        doc.append("traceId", traceId);
        doc.append("payloadHash", payloadHash);
        doc.append("status", "FAILED");
        doc.append("retryCount", 0);
        doc.append("createdAt", now);
        doc.append("updatedAt", now);
        applyPayloadFields(doc, payload);
        mongoTemplate.insert(doc, COLLECTION);

        result.put("action", "INSERT");
        result.put("status", "FAILED");
        result.put("id", doc.get("_id"));
        return result;
    }

    // ==================== 领取与状态更新 ====================

    /**
     * 原子领取下一条 FAILED 记录（防并发重复推送）
     */
    public Document claimNext() {
        Date now = new Date();
        Query query = new Query(Criteria.where("status").is("FAILED"))
                .with(Sort.by(Sort.Direction.ASC, "createdAt"))
                .limit(1);
        Update update = new Update()
                .set("status", "CLAIMED")
                .set("claimedAt", now)
                .set("updatedAt", now);
        FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true).upsert(false);
        return mongoTemplate.findAndModify(query, update, options, Document.class, COLLECTION);
    }

    /** 标记成功 */
    public void markSuccess(String idempotencyKey) {
        Update update = new Update()
                .set("status", "SUCCESS")
                .set("sentAt", new Date())
                .set("claimedAt", null)
                .set("updatedAt", new Date());
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("idempotencyKey").is(idempotencyKey)),
                update, COLLECTION);
    }

    /** 标记失败（回到 FAILED，下次重试） */
    public void markFailed(String idempotencyKey, String errorCode, String errorMessage) {
        Update update = new Update()
                .set("status", "FAILED")
                .inc("retryCount", 1)
                .set("lastErrorCode", errorCode)
                .set("lastErrorMessage", errorMessage)
                .set("claimedAt", null)
                .set("updatedAt", new Date());
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("idempotencyKey").is(idempotencyKey)),
                update, COLLECTION);
    }

    /** 清除 invalidation 标记 */
    public void clearInvalidation(String idempotencyKey) {
        Update update = new Update()
                .set("invalidationNeeded", false)
                .unset("invalidationPayload")
                .set("updatedAt", new Date());
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("idempotencyKey").is(idempotencyKey)),
                update, COLLECTION);
    }

    /** 恢复卡死的 CLAIMED 记录 */
    public int recoverStaleClaimed(long timeoutMs) {
        Date threshold = new Date(System.currentTimeMillis() - timeoutMs);
        Query query = new Query(
                Criteria.where("status").is("CLAIMED").and("claimedAt").lte(threshold));
        Update update = new Update()
                .set("status", "FAILED")
                .set("lastErrorCode", "CLAIMED_TIMEOUT")
                .set("claimedAt", null)
                .set("updatedAt", new Date());
        return (int) mongoTemplate.updateMulti(query, update, COLLECTION).getModifiedCount();
    }

    // ==================== 复测方法 ====================

    public void updateRecheckValue(String id, String recheckValue, int attempts,
                                   boolean recheckRequired, boolean recheckCompleted) {
        Query query = new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(id)));
        Update update = new Update()
                .set("vitalsignNVal2", recheckValue)
                .set("recheckAttempts", attempts)
                .set("recheckRequired", recheckRequired)
                .set("recheckCompleted", recheckCompleted)
                .set("status", "FAILED")
                .set("requestMsg", null)
                .set("requestBodyMasked", null)
                .set("responseMsg", null)
                .set("responseBodyMasked", null)
                .set("sentAt", null)
                .set("updatedAt", new Date());
        mongoTemplate.updateFirst(query, update, COLLECTION);
    }

    public void updateRecheckProgress(String id, int attempts) {
        Query query = new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(id)));
        Update update = new Update()
                .set("recheckAttempts", attempts)
                .set("updatedAt", new Date());
        mongoTemplate.updateFirst(query, update, COLLECTION);
    }

    public void updateRecheckResult(String id, int attempts, boolean recheckRequired,
                                    boolean recheckCompleted) {
        Query query = new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(id)));
        Update update = new Update()
                .set("recheckAttempts", attempts)
                .set("recheckRequired", recheckRequired)
                .set("recheckCompleted", recheckCompleted)
                .set("updatedAt", new Date());
        mongoTemplate.updateFirst(query, update, COLLECTION);
    }

    // ==================== 内部方法 ====================

    private void mergeRecheckState(VitalSignPayload payload, Document existing) {
        if (existing == null || !"1001".equals(payload.getVitalsignType())) return;
        String existingVal1 = existing.getString("vitalsignNVal1");
        if (existingVal1 == null || !existingVal1.equals(payload.getVitalsignNVal1())) return;
        String existingVal2 = existing.getString("vitalsignNVal2");
        if (existingVal2 != null && !existingVal2.trim().isEmpty()) {
            payload.setVitalsignNVal2(existingVal2);
        }
        Boolean completed = readBoolean(existing, "recheckCompleted");
        Boolean required = readBoolean(existing, "recheckRequired");
        if (Boolean.TRUE.equals(completed)) {
            payload.setRecheckCompleted(true);
            payload.setRecheckRequired(Boolean.TRUE.equals(required));
        } else if (Boolean.TRUE.equals(required)) {
            payload.setRecheckRequired(true);
            payload.setRecheckCompleted(false);
        }
    }

    private Boolean readBoolean(Document doc, String key) {
        Object value = doc.get(key);
        return (value instanceof Boolean) ? (Boolean) value : null;
    }

    public static String computeSha256(VitalSignPayload payload) {
        String raw = String.format("%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s",
                nvl(payload.getPatientId()), nvl(payload.getMrn()), nvl(payload.getPatientName()),
                nvl(payload.getSeries()), nvl(payload.getWardCode()), nvl(payload.getVitalsignType()),
                nvl(payload.getVitalsignName()), nvl(payload.getUnit()), nvl(payload.getVitalsignNVal1()),
                nvl(payload.getVitalsignNVal2()), nvl(payload.getVitalsignNVal3()),
                nvl(payload.getVitalsignSVal1()), nvl(payload.getVitalsignSVal2()),
                nvl(payload.getRemark()), payload.getIsValid(),
                nvl(payload.getRecordNurseId()), nvl(payload.getRecordNurseName()),
                nvl(payload.getMongoPid()),
                payload.getPlanTime() != null ? payload.getPlanTime().format(FORMATTER) : "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private Update buildPayloadUpdate(VitalSignPayload payload, String payloadHash, String traceId) {
        Update update = new Update();
        update.set("traceId", traceId);
        update.set("payloadHash", payloadHash);
        update.set("patientId", payload.getPatientId());
        update.set("mrn", payload.getMrn());
        update.set("patientName", payload.getPatientName());
        update.set("series", payload.getSeries());
        update.set("wardCode", payload.getWardCode());
        update.set("vitalsignType", payload.getVitalsignType());
        update.set("vitalsignName", payload.getVitalsignName());
        update.set("unit", payload.getUnit());
        update.set("vitalsignNVal1", payload.getVitalsignNVal1());
        update.set("vitalsignNVal2", payload.getVitalsignNVal2());
        update.set("vitalsignNVal3", payload.getVitalsignNVal3());
        update.set("vitalsignSVal1", payload.getVitalsignSVal1());
        update.set("vitalsignSVal2", payload.getVitalsignSVal2());
        update.set("remark", payload.getRemark());
        update.set("isValid", payload.getIsValid());
        update.set("recordNurseId", payload.getRecordNurseId());
        update.set("recordNurseName", payload.getRecordNurseName());
        update.set("mongoPid", payload.getMongoPid());
        if (payload.getPlanTime() != null) {
            update.set("planTime", Date.from(payload.getPlanTime().atZone(ZONE).toInstant()));
        }
        if (payload.getRecordTime() != null) {
            update.set("recordTime", Date.from(payload.getRecordTime().atZone(ZONE).toInstant()));
        }
        update.set("recheckRequired", payload.isRecheckRequired());
        update.set("recheckCompleted", payload.isRecheckCompleted());
        return update;
    }

    private void applyPayloadFields(Document doc, VitalSignPayload payload) {
        doc.append("patientId", payload.getPatientId());
        doc.append("mrn", payload.getMrn());
        doc.append("patientName", payload.getPatientName());
        doc.append("series", payload.getSeries());
        doc.append("wardCode", payload.getWardCode());
        doc.append("vitalsignType", payload.getVitalsignType());
        doc.append("vitalsignName", payload.getVitalsignName());
        doc.append("unit", payload.getUnit());
        doc.append("vitalsignNVal1", payload.getVitalsignNVal1());
        doc.append("vitalsignNVal2", payload.getVitalsignNVal2());
        doc.append("vitalsignNVal3", payload.getVitalsignNVal3());
        doc.append("vitalsignSVal1", payload.getVitalsignSVal1());
        doc.append("vitalsignSVal2", payload.getVitalsignSVal2());
        doc.append("remark", payload.getRemark());
        doc.append("isValid", payload.getIsValid());
        doc.append("recordNurseId", payload.getRecordNurseId());
        doc.append("recordNurseName", payload.getRecordNurseName());
        doc.append("mongoPid", payload.getMongoPid());
        if (payload.getPlanTime() != null) {
            doc.append("planTime", Date.from(payload.getPlanTime().atZone(ZONE).toInstant()));
        }
        if (payload.getRecordTime() != null) {
            doc.append("recordTime", Date.from(payload.getRecordTime().atZone(ZONE).toInstant()));
        }
        doc.append("recheckRequired", payload.isRecheckRequired());
        doc.append("recheckCompleted", payload.isRecheckCompleted());
    }

    private Document buildInvalidationPayload(Document existing) {
        Document inv = new Document();
        inv.append("patientId", existing.get("patientId"));
        inv.append("mrn", existing.get("mrn"));
        inv.append("patientName", existing.get("patientName"));
        inv.append("series", existing.get("series"));
        inv.append("wardCode", existing.get("wardCode"));
        inv.append("vitalsignType", existing.get("vitalsignType"));
        inv.append("vitalsignName", existing.get("vitalsignName"));
        inv.append("unit", existing.get("unit"));
        inv.append("vitalsignNVal1", existing.get("vitalsignNVal1"));
        inv.append("vitalsignNVal2", existing.get("vitalsignNVal2"));
        inv.append("vitalsignNVal3", existing.get("vitalsignNVal3"));
        inv.append("vitalsignSVal1", existing.get("vitalsignSVal1"));
        inv.append("vitalsignSVal2", existing.get("vitalsignSVal2"));
        inv.append("remark", existing.get("remark"));
        inv.append("recordNurseId", existing.get("recordNurseId"));
        inv.append("recordNurseName", existing.get("recordNurseName"));
        inv.append("mongoPid", existing.get("mongoPid"));
        inv.append("planTime", existing.get("planTime"));
        inv.append("recordTime", existing.get("recordTime"));
        return inv;
    }
}
