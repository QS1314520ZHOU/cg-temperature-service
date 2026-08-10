package com.digixmed.cloud.icu.service;

import com.digixmed.cloud.icu.config.VitalSignPushProperties;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.util.DataUtils;
import com.digixmed.cloud.icu.util.HttpUtils;
import com.digixmed.cloud.icu.util.XMLUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 推送服务
 *
 * 业务目的：处理体征数据的SOAP/XML推送，支持幂等性、重试、状态管理
 * 输入：VitalSignPayload
 * 输出：推送结果（SUCCESS/RETRY/DEAD）
 * 异常策略：
 *   - 网络异常、超时、5xx → 允许自动重试
 *   - 4xx、XML校验错误、字段错误 → 进入DEAD
 *   - 重试使用指数退避
 *
 * 幂等键：patientId + series + vitalsignType + planTime
 * 同一幂等键：
 *   1. payloadHash未变化 → 跳过
 *   2. payloadHash变化 → 更新记录并重新进入PENDING
 *   3. 已SUCCESS且内容未变化 → 绝不重复发送
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private static final String COLLECTION_NAME = "thermometer_intermediate";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private VitalSignPushProperties pushProperties;

    /**
     * 推送单条体征数据
     *
     * @param payload 体征载荷
     * @param traceId 追踪ID
     * @return 推送结果
     */
    public PushResult push(VitalSignPayload payload, String traceId) {
        String patientIdMasked = maskPatientId(payload.getPatientId());
        log.info("STEP_08_IDEMPOTENCY_CHECKED traceId={} patient={} metric={} planTime={}",
                traceId, patientIdMasked, payload.getVitalsignType(), payload.getPlanTime());

        // 幂等性检查
        String idempotencyKey = buildIdempotencyKey(payload);
        String payloadHash = computePayloadHash(payload);

        Map<String, Object> existing = findExistingRecord(idempotencyKey);
        if (existing != null) {
            String existingHash = (String) existing.get("payloadHash");
            String existingStatus = (String) existing.get("status");

            if (payloadHash.equals(existingHash)) {
                if ("SUCCESS".equals(existingStatus)) {
                    log.info("STEP_08_IDEMPOTENCY_CHECKED traceId={} 幂等键已存在且状态为SUCCESS，跳过", traceId);
                    return PushResult.SKIPPED;
                }
            } else {
                log.info("STEP_08_IDEMPOTENCY_CHECKED traceId={} 幂等键已存在但payload变化，更新记录", traceId);
                updateRecordForResend(idempotencyKey, payload, payloadHash);
            }
        } else {
            saveNewRecord(payload, idempotencyKey, payloadHash, traceId);
        }

        // 原子更新状态为SENDING
        if (!atomicUpdateToSending(idempotencyKey)) {
            log.warn("STEP_10_PUSH_STARTED traceId={} 原子更新状态失败，可能已被其他线程处理", traceId);
            return PushResult.SKIPPED;
        }

        log.info("STEP_10_PUSH_STARTED traceId={} 开始推送 patient={} metric={}", traceId, patientIdMasked, payload.getVitalsignType());

        // 生成SOAP XML
        String dataXml = buildDataXml(payload);
        String requestXml = DataUtils.getRequestStr(dataXml);

        // 发送请求
        long startTime = System.currentTimeMillis();
        Map<String, String> response;
        try {
            response = HttpUtils.doPost(pushProperties.getUrl(), requestXml);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("STEP_11_PUSH_RESPONDED traceId={} 请求异常 durationMs={}", traceId, duration, e);
            updateRecordAfterPush(idempotencyKey, false, "REQUEST_ERROR", e.getMessage(), requestXml, null);
            return PushResult.RETRY;
        }

        long duration = System.currentTimeMillis() - startTime;
        String responseMsg = response.get("msg");
        String responseCode = response.get("code");

        log.info("STEP_11_PUSH_RESPONDED traceId={} httpCode={} durationMs={}", traceId, responseCode, duration);

        // 判断结果
        if ("200".equals(responseCode)) {
            if (responseMsg != null && responseMsg.contains("成功")) {
                updateRecordAfterPush(idempotencyKey, true, null, null, requestXml, responseMsg);
                log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 推送成功", traceId);
                return PushResult.SUCCESS;
            } else {
                String errorMsg = extractErrorMsg(responseMsg);
                updateRecordAfterPush(idempotencyKey, false, "BUSINESS_ERROR", errorMsg, requestXml, responseMsg);
                log.warn("STEP_12_PUSH_STATUS_UPDATED traceId={} 业务错误: {}", traceId, errorMsg);
                return PushResult.DEAD;
            }
        } else {
            updateRecordAfterPush(idempotencyKey, false, "HTTP_ERROR", responseMsg, requestXml, responseMsg);
            log.warn("STEP_12_PUSH_STATUS_UPDATED traceId={} HTTP错误: code={}", traceId, responseCode);
            return "4".equals(responseCode) ? PushResult.DEAD : PushResult.RETRY;
        }
    }

    private String buildIdempotencyKey(VitalSignPayload payload) {
        return String.format("%s_%s_%s_%s",
                payload.getPatientId(),
                payload.getSeries(),
                payload.getVitalsignType(),
                payload.getPlanTime());
    }

    private String computePayloadHash(VitalSignPayload payload) {
        String content = String.format("%s_%s_%s_%s_%s_%s_%s",
                payload.getVitalsignNVal1(),
                payload.getVitalsignNVal2(),
                payload.getVitalsignNVal3(),
                payload.getVitalsignSVal1(),
                payload.getVitalsignSVal2(),
                payload.getRecordNurseName(),
                payload.getRecordTime());
        return String.valueOf(content.hashCode());
    }

    private Map<String, Object> findExistingRecord(String idempotencyKey) {
        Query query = new Query(Criteria.where("idempotencyKey").is(idempotencyKey));
        return mongoTemplate.findOne(query, Map.class, COLLECTION_NAME);
    }

    private void saveNewRecord(VitalSignPayload payload, String idempotencyKey, String payloadHash, String traceId) {
        Map<String, Object> record = new HashMap<>();
        record.put("idempotencyKey", idempotencyKey);
        record.put("patientId", payload.getPatientId());
        record.put("mrn", payload.getMrn());
        record.put("patientName", payload.getPatientName());
        record.put("series", payload.getSeries());
        record.put("vitalsignType", payload.getVitalsignType());
        record.put("vitalsignName", payload.getVitalsignName());
        record.put("planTime", Date.from(payload.getPlanTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
        record.put("payloadHash", payloadHash);
        record.put("status", "PENDING");
        record.put("retryCount", 0);
        record.put("traceId", traceId);
        record.put("createdAt", new Date());
        record.put("updatedAt", new Date());
        mongoTemplate.save(record, COLLECTION_NAME);
    }

    private void updateRecordForResend(String idempotencyKey, VitalSignPayload payload, String payloadHash) {
        Update update = new Update();
        update.set("payloadHash", payloadHash);
        update.set("status", "PENDING");
        update.set("retryCount", 0);
        update.set("updatedAt", new Date());
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("idempotencyKey").is(idempotencyKey)),
                update, COLLECTION_NAME);
    }

    private boolean atomicUpdateToSending(String idempotencyKey) {
        Update update = new Update();
        update.set("status", "SENDING");
        update.set("updatedAt", new Date());
        var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("idempotencyKey").is(idempotencyKey)
                        .and("status").in("PENDING", "RETRY")),
                update, COLLECTION_NAME);
        return result.getModifiedCount() > 0;
    }

    private void updateRecordAfterPush(String idempotencyKey, boolean success, String errorCode, String errorMsg, String requestXml, String responseMsg) {
        Update update = new Update();
        update.set("status", success ? "SUCCESS" : "DEAD");
        update.set("updatedAt", new Date());
        if (success) {
            update.set("sentAt", new Date());
        }
        if (errorCode != null) {
            update.set("lastErrorCode", errorCode);
        }
        if (errorMsg != null) {
            update.set("lastErrorMessage", errorMsg);
        }
        if (requestXml != null) {
            update.set("requestBodyMasked", truncate(requestXml, pushProperties.getMaxRequestBodyLength()));
        }
        if (responseMsg != null) {
            update.set("responseBodyMasked", truncate(responseMsg, pushProperties.getMaxResponseBodyLength()));
        }
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("idempotencyKey").is(idempotencyKey)),
                update, COLLECTION_NAME);
    }

    private String buildDataXml(VitalSignPayload payload) {
        // 使用DataValue构建XML
        com.digixmed.cloud.icu.pojo.DataValue dataValue = new com.digixmed.cloud.icu.pojo.DataValue();
        dataValue.setIsValid(payload.getIsValid());
        dataValue.setMrn(payload.getMrn());
        dataValue.setPatientId(payload.getPatientId());
        dataValue.setPatientName(payload.getPatientName());
        dataValue.setPlanTime(Date.from(payload.getPlanTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
        dataValue.setRecordTime(Date.from(payload.getRecordTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
        dataValue.setRecordNurseId(payload.getRecordNurseId());
        dataValue.setRecordNurseName(payload.getRecordNurseName());
        dataValue.setSeries(payload.getSeries());
        dataValue.setUnit(payload.getUnit());
        dataValue.setWardCode(payload.getWardCode());
        dataValue.setRemark(payload.getRemark());
        dataValue.setVitalsignName(payload.getVitalsignName());
        dataValue.setVitalsignType(payload.getVitalsignType());
        dataValue.setVitalsignNVal1(payload.getVitalsignNVal1());
        dataValue.setVitalsignNVal2(payload.getVitalsignNVal2());
        dataValue.setVitalsignSVal1(payload.getVitalsignSVal1());
        dataValue.setVitalsignSVal2(payload.getVitalsignSVal2());

        com.digixmed.cloud.icu.pojo.Data data = new com.digixmed.cloud.icu.pojo.Data();
        java.util.List<com.digixmed.cloud.icu.pojo.DataValue> list = new java.util.ArrayList<>();
        list.add(dataValue);
        data.setData(list);

        return XMLUtils.convertToXml(data);
    }

    private String extractErrorMsg(String responseMsg) {
        if (responseMsg == null) return "未知错误";
        int start = responseMsg.indexOf("<msg>");
        int end = responseMsg.indexOf("</msg>");
        if (start >= 0 && end > start) {
            return responseMsg.substring(start + 5, end);
        }
        return responseMsg;
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
    }

    private String maskPatientId(String patientId) {
        if (patientId == null || patientId.length() <= 4) return "****";
        return patientId.substring(0, 2) + "****" + patientId.substring(patientId.length() - 2);
    }

    public enum PushResult {
        SUCCESS,
        RETRY,
        DEAD,
        SKIPPED
    }
}
