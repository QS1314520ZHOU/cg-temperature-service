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

        String payloadHash = computeSha256(payload);

        // Check existing record
        Query query = new Query(Criteria.where("idempotencyKey").is(idempotencyKey));
        Document existing = mongoTemplate.findOne(query, Document.class, COLLECTION);

        Map<String, Object> result = new HashMap<>();
        result.put("idempotencyKey", idempotencyKey);
        result.put("payloadHash", payloadHash);

        Date now = new Date();

        if (existing != null) {
            String existingHash = existing.getString("payloadHash");
            String existingStatus = existing.getString("status");

            if (payloadHash.equals(existingHash) && "SUCCESS".equals(existingStatus)) {
                // Identical payload already pushed successfully -> skip
                result.put("action", "SKIP");
                result.put("status", "SUCCESS");
                result.put("id", existing.get("_id").toString());
                return result;
            }

            // Payload changed or previous push failed -> update and reset to PENDING
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

        // New record
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

    // ==================== Internal helpers ====================

    /**
     * Compute SHA-256 hash of the payload content.
     *
     * Uses all payload fields to produce a 64-character hex string.
     * Throws RuntimeException if SHA-256 algorithm is unavailable (never falls back to hashCode).
     */
    /**
     * 计算 payloadHash。
     * 不得包含 traceId / className 等每次变化的字段，否则幂等比较永远判定“内容已变”。
     * PushService 必须复用本方法，两处算法必须保持一致。
     */
    public static String computeSha256(VitalSignPayload payload) {
        String raw = String.format("%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s",
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
                payload.getPlanTime() != null ? payload.getPlanTime().format(FORMATTER) : "",
                payload.getRecordTime() != null ? payload.getRecordTime().format(FORMATTER) : ""
        );

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
    }
}
