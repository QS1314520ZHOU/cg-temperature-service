package com.digixmed.cloud.icu.service;

import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
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
 * Intermediate table service (vitalsign_push_queue)
 * 新推送链路专用集合，与旧回传链路（thermometer_intermediate）完全隔离
 *
 * Decouples collection tasks from push tasks via a state-machine-backed queue.
 *
 * State flow:
 *   PENDING -> SENDING -> SUCCESS
 *                      -> RETRY -> SENDING -> ...
 *                      -> DEAD
 *
 * Idempotency key: patientId_series_vitalsignType_planTime
 */
@Service
public class IntermediateService {

    /**
     * 新推送链路（VitalSignScanTask -> PushTask -> PushService）专用集合。
     * 旧回传链路（HandleService/ReturnService/IntermediateTable）仍使用 thermometer_intermediate，
     * 两边文档结构不同，必须分开存储，否则会互相领取对方的记录并发出残缺报文。
     */
    public static final String PUSH_COLLECTION = "vitalsign_push_queue";

    private static final String COLLECTION = PUSH_COLLECTION;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 统一时区：统一使用本常量，不得使用 JVM 默认时区，否则 planTime 会随部署环境漂移并破坏幂等键 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private MongoTemplate mongoTemplate;

    /**
     * Create or update a PENDING record with the full payload.
     *
     * Logic:
     *  - Build idempotencyKey from patientId_series_vitalsignType_planTime
     *  - Compute SHA-256 payload hash
     *  - If exists, same hash, status SUCCESS -> skip, return existing
     *  - If exists, different hash -> update all fields, reset to PENDING
     *  - If not exists -> insert new with status PENDING
     *
     * @param payload the vital sign payload to persist
     * @param traceId correlation ID for logging
     * @return map with result info (idempotencyKey, status, action, id, etc.)
     */
    /**
     * Create or update a PENDING record with the full payload.
     *
     * 顺序调整说明：
     *   必须先按幂等键查出既有记录，再合并复测状态，最后才计算 payloadHash。
     *   否则 hash 用的是"未合并"的 payload，而落库的是"已合并"的值，
     *   两者永远不相等，每轮扫描都会判定内容变化并反复重置状态、重复回传。
     */
    public Map<String, Object> upsertPending(VitalSignPayload payload, String traceId) {
        // 准入原则：patientId（= patient.mrn）为空的记录不入队、不回传
        if (payload == null || payload.getPatientId() == null || payload.getPatientId().trim().isEmpty()) {
            Map<String, Object> skipped = new HashMap<>();
            skipped.put("action", "SKIP");
            skipped.put("status", "SKIPPED_NO_PATIENT_ID");
            return skipped;
        }

        String planTimeStr = payload.getPlanTime() != null
                ? payload.getPlanTime().format(FORMATTER) : "";
        String idempotencyKey = String.format("%s_%s_%s_%s",
                payload.getPatientId(),
                payload.getSeries(),
                payload.getVitalsignType(),
                planTimeStr);

        Query query = new Query(Criteria.where("idempotencyKey").is(idempotencyKey));
        Document existing = mongoTemplate.findOne(query, Document.class, COLLECTION);

        // 先合并复测状态，再算 hash
        mergeRecheckState(payload, existing);

        String payloadHash = computeSha256(payload);

        Map<String, Object> result = new HashMap<>();
        result.put("idempotencyKey", idempotencyKey);
        result.put("payloadHash", payloadHash);

        Date now = new Date();

        if (existing != null) {
            String existingHash = existing.getString("payloadHash");
            String existingStatus = existing.getString("status");

            if (payloadHash.equals(existingHash) && "SUCCESS".equals(existingStatus)) {
                // 已成功回传且内容完全一致 → 不再重复回传
                result.put("action", "SKIP");
                result.put("status", "SUCCESS");
                result.put("id", existing.get("_id").toString());
                return result;
            }

            // 内容变化（值不对、补录修正）或上次回传失败 → 更新并重置为 PENDING 重新回传
            Update update = buildPayloadUpdate(payload, payloadHash, traceId);
            update.set("status", "PENDING");
            update.set("retryCount", 0);
            update.set("nextRetryTime", null);
            update.set("lastErrorCode", null);
            update.set("lastErrorMessage", null);
            update.set("updatedAt", now);
            mongoTemplate.updateFirst(query, update, COLLECTION);

            result.put("action", "UPDATE");
            result.put("status", "PENDING");
            result.put("id", existing.get("_id").toString());
            return result;
        }

        Document doc = new Document();
        doc.append("idempotencyKey", idempotencyKey);
        doc.append("traceId", traceId);
        doc.append("payloadHash", payloadHash);
        doc.append("status", "PENDING");
        doc.append("retryCount", 0);
        doc.append("createdAt", now);
        doc.append("updatedAt", now);

        applyPayloadFields(doc, payload);

        mongoTemplate.insert(doc, COLLECTION);

        result.put("action", "INSERT");
        result.put("status", "PENDING");
        result.put("id", doc.get("_id"));
        return result;
    }

    /**
     * 合并已有的体温复测状态，防止复测结果被下一轮扫描抹掉。
     *
     * 背景：TemperatureRecheckTask 命中复测值后会写入 vitalsignNVal2 并置 recheckCompleted=true。
     *      而 TemperatureHandler 每次都无条件把 vitalsignNVal2 置为空串，
     *      扫描任务是分钟级重扫的，下一轮就会把复测值清回空、状态重置 PENDING，
     *      结果是复测值刚写进去就被抹掉，还会再推一次残缺报文。
     *
     * 合并条件：仅当原始体温值（vitalsignNVal1）未发生变化时才保留复测结果；
     *          若护士把 39.0 改成了 37.0，原复测值失效，必须丢弃并重新走复测流程。
     */
    private void mergeRecheckState(VitalSignPayload payload, Document existing) {
        if (existing == null || !"1001".equals(payload.getVitalsignType())) {
            return;
        }

        String existingVal1 = existing.getString("vitalsignNVal1");
        String currentVal1 = payload.getVitalsignNVal1();
        if (existingVal1 == null || !existingVal1.equals(currentVal1)) {
            // 原始体温值变了，复测结果作废
            return;
        }

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
            // 复测仍在进行中，保持巡检任务能继续查到这条记录
            payload.setRecheckRequired(true);
            payload.setRecheckCompleted(false);
        }
    }

    private Boolean readBoolean(Document doc, String key) {
        Object value = doc.get(key);
        return (value instanceof Boolean) ? (Boolean) value : null;
    }

    /**
     * Atomically claim the next PENDING/RETRY record for sending.
     *
     * Uses findAndModify to set status=SENDING and claimedAt=now in a single
     * atomic operation, preventing multiple consumers from picking the same record.
     *
     * @return the claimed document, or null if no eligible record exists
     */
    public Document claimNext() {
        Date now = new Date();

        Query query = new Query(
                Criteria.where("status").in("PENDING", "RETRY")
                        .orOperator(
                                Criteria.where("nextRetryTime").exists(false),
                                Criteria.where("nextRetryTime").lte(now)
                        )
        ).with(Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "createdAt")).limit(1);

        Update update = new Update()
                .set("status", "SENDING")
                .set("claimedAt", now)
                .set("updatedAt", now);

        FindAndModifyOptions options = FindAndModifyOptions.options()
                .returnNew(true)
                .upsert(false);

        return mongoTemplate.findAndModify(query, update, options, Document.class, COLLECTION);
    }

    /**
     * Mark a record as successfully sent.
     *
     * @param idempotencyKey the idempotency key of the record
     */
    public void markSuccess(String idempotencyKey) {
        Date now = new Date();

        Query query = new Query(Criteria.where("idempotencyKey").is(idempotencyKey));
        Update update = new Update()
                .set("status", "SUCCESS")
                .set("sentAt", now)
                .set("claimedAt", null)
                .set("claimedBy", null)
                .set("updatedAt", now);

        mongoTemplate.updateFirst(query, update, COLLECTION);
    }

    /**
     * Mark a record for retry with a delay.
     *
     * @param idempotencyKey the idempotency key of the record
     * @param errorCode      error code from the failed push
     * @param errorMessage   error message from the failed push
     * @param retryCount     current retry count (will be incremented)
     * @param delayMs        delay in milliseconds before the next retry
     */
    public void markRetry(String idempotencyKey, String errorCode, String errorMessage,
                          int retryCount, long delayMs) {
        Date now = new Date();
        Date nextRetryTime = new Date(now.getTime() + delayMs);

        Query query = new Query(Criteria.where("idempotencyKey").is(idempotencyKey));
        Update update = new Update()
                .set("status", "RETRY")
                .set("nextRetryTime", nextRetryTime)
                // 用 $inc 自增，避免“读-改-写”在多实例并发下丢失重试计数
                .inc("retryCount", 1)
                .set("lastErrorCode", errorCode)
                .set("lastErrorMessage", errorMessage)
                .set("claimedAt", null)
                .set("claimedBy", null)
                .set("updatedAt", now);

        mongoTemplate.updateFirst(query, update, COLLECTION);
    }

    /**
     * Mark a record as dead (exhausted all retries, will not be retried).
     *
     * @param idempotencyKey the idempotency key of the record
     * @param errorCode      final error code
     * @param errorMessage   final error message
     */
    public void markDead(String idempotencyKey, String errorCode, String errorMessage) {
        Date now = new Date();

        Query query = new Query(Criteria.where("idempotencyKey").is(idempotencyKey));
        Update update = new Update()
                .set("status", "DEAD")
                .set("lastErrorCode", errorCode)
                .set("lastErrorMessage", errorMessage)
                .set("claimedAt", null)
                .set("claimedBy", null)
                .set("updatedAt", now);

        mongoTemplate.updateFirst(query, update, COLLECTION);
    }

    /**
     * Recover SENDING records that have been stuck beyond the timeout threshold.
     *
     * These records were claimed but never completed (success or failure).
     * Resetting them to RETRY with immediate nextRetryTime allows re-processing.
     *
     * @param sendingTimeoutMs max time in milliseconds a record can stay in SENDING
     * @return count of recovered records
     */
    public int recoverStaleSending(long sendingTimeoutMs) {
        Date now = new Date();
        Date threshold = new Date(now.getTime() - sendingTimeoutMs);

        Query query = new Query(
                Criteria.where("status").is("SENDING")
                        .and("claimedAt").lte(threshold)
        );

        Update update = new Update()
                .set("status", "RETRY")
                .set("nextRetryTime", now)
                .set("lastErrorCode", "SENDING_TIMEOUT")
                .set("claimedBy", null)
                .set("claimedAt", null)
                .set("updatedAt", now);

        var result = mongoTemplate.updateMulti(query, update, COLLECTION);
        return (int) result.getModifiedCount();
    }

    // ==================== Recheck methods ====================

    /**
     * Update recheck value when a recheck temperature is found.
     * Sets vitalsignNVal2 to the recheck value and marks the record as completed.
     *
     * @param id                 the document _id (as string)
     * @param recheckValue       the recheck temperature value
     * @param attempts           current attempt count
     * @param recheckRequired    whether recheck was required
     * @param recheckCompleted   whether recheck is completed
     */
    public void updateRecheckValue(String id, String recheckValue, int attempts,
                                   boolean recheckRequired, boolean recheckCompleted) {
        Query query = new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(id)));
        Update update = new Update()
                .set("vitalsignNVal2", recheckValue)
                .set("recheckAttempts", attempts)
                .set("recheckRequired", recheckRequired)
                .set("recheckCompleted", recheckCompleted)
                .set("status", "PENDING")  // Reset to PENDING for re-push
                .set("updatedAt", new Date());
        mongoTemplate.updateFirst(query, update, COLLECTION);
    }

    /**
     * Update recheck progress (increment attempt count, no value found yet).
     *
     * @param id        the document _id (as string)
     * @param attempts  current attempt count
     */
    public void updateRecheckProgress(String id, int attempts) {
        Query query = new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(id)));
        Update update = new Update()
                .set("recheckAttempts", attempts)
                .set("updatedAt", new Date());
        mongoTemplate.updateFirst(query, update, COLLECTION);
    }

    /**
     * Update recheck final result (window closed or max attempts reached).
     *
     * @param id                 the document _id (as string)
     * @param attempts           final attempt count
     * @param recheckRequired    whether recheck was required
     * @param recheckCompleted   whether recheck found a value
     */
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

    // ==================== Internal helpers ====================

    /**
     * Compute SHA-256 hash of the payload content.
     *
     * Uses all payload fields to produce a 64-character hex string.
     * Throws RuntimeException if SHA-256 algorithm is unavailable (never falls back to hashCode).
     */
    /**
     * 计算 payloadHash。
     * 不得包含 traceId / className 等每次变化的字段，否则幂等比较永远判定”内容已变”。
     *
     * recordTime 已被排除：planTime 已锚定标准时间点作为幂等键，
     * recordTime 是护士真实填写时刻，同一格子被反复编辑时会变化。
     * 若纳入 hash，则”值完全一致、只是记录时刻不同”也会触发重复回传，
     * 与”数据一致就不再回传”的要求冲突。
     *
     * PushService 必须复用本方法，两处算法必须保持一致。
     */
    public static String computeSha256(VitalSignPayload payload) {
        String raw = String.format(“%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s”,
                nvl(payload.getPatientId()),
                nvl(payload.getMrn()),
                nvl(payload.getPatientName()),
                nvl(payload.getSeries()),
                nvl(payload.getWardCode()),
                nvl(payload.getVitalsignType()),
                nvl(payload.getVitalsignName()),
                nvl(payload.getUnit()),
                nvl(payload.getVitalsignNVal1()),
                nvl(payload.getVitalsignNVal2()),
                nvl(payload.getVitalsignNVal3()),
                nvl(payload.getVitalsignSVal1()),
                nvl(payload.getVitalsignSVal2()),
                nvl(payload.getRemark()),
                payload.getIsValid(),
                nvl(payload.getRecordNurseId()),
                nvl(payload.getRecordNurseName()),
                nvl(payload.getMongoPid()),
                payload.getPlanTime() != null ? payload.getPlanTime().format(FORMATTER) : “”
        );

        try {
            MessageDigest digest = MessageDigest.getInstance(“SHA-256”);
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(“SHA-256 algorithm unavailable”, e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /**
     * Build an Update that sets all payload fields onto the document.
     */
    private Update buildPayloadUpdate(VitalSignPayload payload, String payloadHash, String traceId) {
        Update update = new Update();
        applyPayloadUpdateFields(update, payload, payloadHash, traceId);
        return update;
    }

    /**
     * Apply all payload fields to an Update object.
     */
    private void applyPayloadUpdateFields(Update update, VitalSignPayload payload,
                                          String payloadHash, String traceId) {
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
            update.set("planTime", Date.from(
                    payload.getPlanTime().atZone(ZONE).toInstant()));
        }
        if (payload.getRecordTime() != null) {
            update.set("recordTime", Date.from(
                    payload.getRecordTime().atZone(ZONE).toInstant()));
        }
        update.set("recheckRequired", payload.isRecheckRequired());
        update.set("recheckCompleted", payload.isRecheckCompleted());
    }

    /**
     * Apply all payload fields to a new Document.
     */
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
            doc.append("planTime", Date.from(
                    payload.getPlanTime().atZone(ZONE).toInstant()));
        }
        if (payload.getRecordTime() != null) {
            doc.append("recordTime", Date.from(
                    payload.getRecordTime().atZone(ZONE).toInstant()));
        }
        doc.append("recheckRequired", payload.isRecheckRequired());
        doc.append("recheckCompleted", payload.isRecheckCompleted());
    }
}
