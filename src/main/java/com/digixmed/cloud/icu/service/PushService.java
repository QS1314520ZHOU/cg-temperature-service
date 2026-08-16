package com.digixmed.cloud.icu.service;

import com.digixmed.cloud.icu.config.VitalSignPushProperties;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.util.DataUtils;
import com.digixmed.cloud.icu.util.HttpUtils;
import com.digixmed.cloud.icu.util.ResponseUtils;
import com.digixmed.cloud.icu.util.XMLUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    /** 新推送链路专用集合（与旧回传链路隔离） */
    private static final String COLLECTION_NAME = IntermediateService.PUSH_COLLECTION;

    private static final DateTimeFormatter PLAN_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
        // 准入原则：patient 集合中存在该 _id 才推送；并用 patient 文档校准
        // patientId = patient.mrn，mrn = patient.hisPid，patientName = patient.name
        if (!ensurePatientIdentity(payload, traceId)) {
            return PushResult.SKIPPED;
        }

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

        if (requestXml == null || requestXml.trim().isEmpty()) {
            log.error("STEP_11_PUSH_RESPONDED traceId={} 请求体为空", traceId);
            updateRecordAfterPush(idempotencyKey, false, "EMPTY_REQUEST", "请求体为空", null, null);
            return PushResult.RETRY;
        }

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

        // 详细日志：请求和响应内容
        log.info("STEP_11_PUSH_RESPONDED traceId={} httpCode={} durationMs={}", traceId, responseCode, duration);
        log.info("STEP_11_PUSH_REQUEST_DETAIL traceId={} patient={} metric={} requestXml长度={}",
                traceId, patientIdMasked, payload.getVitalsignType(), requestXml != null ? requestXml.length() : 0);
        log.info("STEP_11_PUSH_REQUEST_XML traceId={} 请求报文:{}", traceId, requestXml);
        log.info("STEP_11_PUSH_RESPONSE_DETAIL traceId={} responseCode={} responseMsg={}",
                traceId, responseCode, responseMsg != null ? responseMsg : "null");
        log.info("STEP_11_PUSH_RESPONSE_XML traceId={} 响应报文:{}", traceId, responseMsg);

        // 判断结果
        if (responseCode == null) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 响应缺少状态码，按可重试处理", traceId);
            updateRecordAfterPush(idempotencyKey, false, "REQUEST_ERROR", "响应缺少状态码", requestXml, responseMsg);
            return PushResult.RETRY;
        }
        if ("200".equals(responseCode)) {
            // 不能用 contains("成功")：“不成功/未成功”同样包含“成功”，会把失败误判为 SUCCESS 而丢数据
            if (ResponseUtils.isBusinessSuccess(responseMsg)) {
                updateRecordAfterPush(idempotencyKey, true, null, null, requestXml, responseMsg);
                log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 推送成功 patient={} metric={}",
                        traceId, patientIdMasked, payload.getVitalsignType());
                return PushResult.SUCCESS;
            } else {
                String errorMsg = extractErrorMsg(responseMsg);
                updateRecordAfterPush(idempotencyKey, false, "BUSINESS_ERROR", errorMsg, requestXml, responseMsg);
                log.warn("STEP_12_PUSH_STATUS_UPDATED traceId={} 业务错误: {} patient={} metric={} responseMsg={}",
                        traceId, errorMsg, patientIdMasked, payload.getVitalsignType(), responseMsg);
                return PushResult.DEAD;
            }
        } else if (responseCode.startsWith("4")) {
            // 4xx错误
            updateRecordAfterPush(idempotencyKey, false, "HTTP_" + responseCode, responseMsg, requestXml, responseMsg);
            log.warn("STEP_12_PUSH_STATUS_UPDATED traceId={} HTTP客户端错误: code={} patient={} metric={}",
                    traceId, responseCode, patientIdMasked, payload.getVitalsignType());
            return PushResult.DEAD;
        } else {
            // 5xx或其他错误，允许重试
            updateRecordAfterPush(idempotencyKey, false, "HTTP_" + responseCode, responseMsg, requestXml, responseMsg);
            log.warn("STEP_12_PUSH_STATUS_UPDATED traceId={} HTTP服务端错误，可重试: code={} patient={} metric={}",
                    traceId, responseCode, patientIdMasked, payload.getVitalsignType());
            return PushResult.RETRY;
        }
    }

    /**
     * 更新记录（带重试逻辑）
     */
    private void updateRecordAfterPush(String idempotencyKey, boolean success, String errorCode,
                                        String errorMsg, String requestXml, String responseMsg) {
        Update update = new Update();
        update.set("updatedAt", new Date());

        if (success) {
            update.set("status", "SUCCESS");
            update.set("sentAt", new Date());
            update.set("nextRetryTime", null);
            update.set("claimedAt", null);
            update.set("lastErrorCode", null);
            update.set("lastErrorMessage", null);
        } else {
            // 判断是否应该重试
            boolean shouldRetry = shouldRetry(errorCode);
            int retryCount = getRetryCount(idempotencyKey);

            if (shouldRetry && retryCount < pushProperties.getMaxRetryCount()) {
                update.set("status", "RETRY");
                // 用 $inc 自增，避免“读-改-写”在多实例下丢失计数
                update.inc("retryCount", 1);
                // 指数退避
                long delay = pushProperties.getRetryBaseInterval() * (1L << retryCount);
                update.set("nextRetryTime", new Date(System.currentTimeMillis() + delay));
                log.info("进入重试状态 retryCount={} nextDelayMs={}", retryCount + 1, delay);
            } else {
                update.set("status", "DEAD");
                update.inc("retryCount", 1);
                update.set("nextRetryTime", null);
                log.info("进入DEAD状态 retryCount={}", retryCount + 1);
            }
        }

        if (errorCode != null) {
            update.set("lastErrorCode", errorCode);
        }
        if (errorMsg != null) {
            update.set("lastErrorMessage", truncate(errorMsg, pushProperties.getMaxResponseBodyLength()));
        }
        if (requestXml != null) {
            update.set("requestMsg", truncate(requestXml, pushProperties.getMaxRequestBodyLength()));
            // *_Masked 字段存脱敏后的报文，避免患者标识明文落库
            update.set("requestBodyMasked", truncate(ResponseUtils.maskXml(requestXml), pushProperties.getMaxRequestBodyLength()));
        }
        if (responseMsg != null) {
            update.set("responseMsg", truncate(responseMsg, pushProperties.getMaxResponseBodyLength()));
            update.set("responseBodyMasked", truncate(ResponseUtils.maskXml(responseMsg), pushProperties.getMaxResponseBodyLength()));
        }

        mongoTemplate.updateFirst(
                Query.query(Criteria.where("idempotencyKey").is(idempotencyKey)),
                update, COLLECTION_NAME);
    }

    /**
     * 判断是否应该重试
     */
    private boolean shouldRetry(String errorCode) {
        if (errorCode == null) return false;
        // 网络错误、超时、5xx可以重试
        // 仅 5xx 与网络异常可自愈；4xx / 业务错误 / 空报文重发也不会成功，直接进入 DEAD（与类注释一致）
        return errorCode.startsWith("HTTP_5") || "REQUEST_ERROR".equals(errorCode);
    }

    /**
     * 获取当前重试次数
     */
    private int getRetryCount(String idempotencyKey) {
        Query query = new Query(Criteria.where("idempotencyKey").is(idempotencyKey));
        Map<String, Object> record = mongoTemplate.findOne(query, Map.class, COLLECTION_NAME);
        if (record != null && record.get("retryCount") instanceof Number) {
            return ((Number) record.get("retryCount")).intValue();
        }
        return 0;
    }

    /**
     * 构建幂等键
     * 格式：patientId_series_vitalsignType_planTime
     */
    private String buildIdempotencyKey(VitalSignPayload payload) {
        String planTimeStr = payload.getPlanTime() != null
                ? payload.getPlanTime().format(PLAN_TIME_FORMATTER) : "";
        return String.format("%s_%s_%s_%s",
                payload.getPatientId(),
                payload.getSeries(),
                payload.getVitalsignType(),
                planTimeStr);
    }

    /**
     * 计算Payload哈希（使用SHA-256）
     */
    private String computePayloadHash(VitalSignPayload payload) {
        // 必须与 IntermediateService.upsertPending 写入的 payloadHash 使用同一算法，
        // 否则同一条记录的 hash 永不相等，幂等判定恒为“内容已变”，引发状态反复重置与重复发送。
        return IntermediateService.computeSha256(payload);
    }

    private String nullToEmpty(String str) {
        return str == null ? "" : str;
    }

    private Map<String, Object> findExistingRecord(String idempotencyKey) {
        Query query = new Query(Criteria.where("idempotencyKey").is(idempotencyKey));
        return mongoTemplate.findOne(query, Map.class, COLLECTION_NAME);
    }

    /**
     * 保存完整Payload到中间表
     */
    private void saveNewRecord(VitalSignPayload payload, String idempotencyKey, String payloadHash, String traceId) {
        Map<String, Object> record = new HashMap<>();
        record.put("idempotencyKey", idempotencyKey);
        record.put("traceId", traceId);
        record.put("patientId", payload.getPatientId());
        record.put("mrn", payload.getMrn());
        record.put("patientName", payload.getPatientName());
        record.put("series", payload.getSeries());
        record.put("wardCode", payload.getWardCode());
        record.put("vitalsignType", payload.getVitalsignType());
        record.put("vitalsignName", payload.getVitalsignName());
        record.put("vitalsignNVal1", payload.getVitalsignNVal1());
        record.put("vitalsignNVal2", payload.getVitalsignNVal2());
        record.put("vitalsignNVal3", payload.getVitalsignNVal3());
        record.put("vitalsignSVal1", payload.getVitalsignSVal1());
        record.put("vitalsignSVal2", payload.getVitalsignSVal2());
        record.put("unit", payload.getUnit());
        record.put("remark", payload.getRemark());
        record.put("isValid", payload.getIsValid());
        record.put("recordNurseId", payload.getRecordNurseId());
        record.put("recordNurseName", payload.getRecordNurseName());
        record.put("mongoPid", payload.getMongoPid());
        if (payload.getPlanTime() != null) {
            record.put("planTime", Date.from(payload.getPlanTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
        }
        if (payload.getRecordTime() != null) {
            record.put("recordTime", Date.from(payload.getRecordTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
        }
        record.put("payloadHash", payloadHash);
        record.put("status", "PENDING");
        record.put("retryCount", 0);
        record.put("createdAt", new Date());
        record.put("updatedAt", new Date());
        mongoTemplate.save(record, COLLECTION_NAME);
    }

    /**
     * 更新记录用于重发
     */
    private void updateRecordForResend(String idempotencyKey, VitalSignPayload payload, String payloadHash) {
        Update update = new Update();
        update.set("payloadHash", payloadHash);
        update.set("status", "PENDING");
        update.set("retryCount", 0);
        update.set("vitalsignNVal1", payload.getVitalsignNVal1());
        update.set("vitalsignNVal2", payload.getVitalsignNVal2());
        update.set("vitalsignNVal3", payload.getVitalsignNVal3());
        update.set("vitalsignSVal1", payload.getVitalsignSVal1());
        update.set("vitalsignSVal2", payload.getVitalsignSVal2());
        update.set("recordNurseName", payload.getRecordNurseName());
        update.set("updatedAt", new Date());
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("idempotencyKey").is(idempotencyKey)),
                update, COLLECTION_NAME);
    }

    /**
     * 原子更新状态为SENDING
     */
    private boolean atomicUpdateToSending(String idempotencyKey) {
        Update update = new Update();
        update.set("status", "SENDING");
        update.set("claimedAt", new Date());
        update.set("updatedAt", new Date());
        var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("idempotencyKey").is(idempotencyKey)
                        // PushTask 领取时已置为 SENDING，若不放行 SENDING 则此处永远更新 0 条，推送被无声跳过
                        .and("status").in("PENDING", "RETRY", "SENDING")),
                update, COLLECTION_NAME);
        return result.getModifiedCount() > 0;
    }

    /**
     * 推送前校准病人标识：
     *   1. 按 mongoPid 回查 patient 文档，不存在直接跳过（不判断金仓状态）
     *   2. patientId = patient.mrn，mrn = patient.hisPid，patientName = patient.name
     *   3. patientId 仍为空时不推送，避免 HIS 回“没有相关入参病人信息”导致 DEAD 堆积
     */
    private boolean ensurePatientIdentity(VitalSignPayload payload, String traceId) {
        if (payload == null) {
            return false;
        }

        String pid = payload.getMongoPid();
        org.bson.Document patient = findPatientByPid(pid);
        if (patient != null) {
            String patientId = readPatientString(patient, "mrn");
            String mrn = readPatientString(patient, "hisPid");
            String patientName = readPatientString(patient, "name");
            if (patientId != null) {
                payload.setPatientId(patientId);
            }
            if (mrn != null) {
                payload.setMrn(mrn);
            }
            if (patientName != null) {
                payload.setPatientName(patientName);
            }
        } else if (pid != null && !pid.trim().isEmpty()) {
            log.info("STEP_09_PATIENT_CHECK traceId={} pid={} patient集合中不存在该_id，不推送", traceId, pid);
            return false;
        }

        if (payload.getPatientId() == null || payload.getPatientId().trim().isEmpty()) {
            log.warn("STEP_09_PATIENT_CHECK traceId={} pid={} patientId(patient.mrn)为空，不推送", traceId, pid);
            return false;
        }
        if (payload.getMrn() == null || payload.getMrn().trim().isEmpty()) {
            log.warn("STEP_09_PATIENT_CHECK traceId={} pid={} mrn(patient.hisPid)为空", traceId, pid);
        }
        return true;
    }

    /** 按 Mongo pid 查 patient 文档；pid 为空/非法或异常时返回 null */
    private org.bson.Document findPatientByPid(String pid) {
        if (pid == null || pid.trim().isEmpty()) {
            return null;
        }
        try {
            Query query = Query.query(Criteria.where("_id").is(new org.bson.types.ObjectId(pid.trim())));
            return mongoTemplate.findOne(query, org.bson.Document.class, "patient");
        } catch (Exception e) {
            log.warn("STEP_09_PATIENT_CHECK pid={} 查询patient异常: {}", pid, e.getMessage());
            return null;
        }
    }

    /** 读取 patient 字符串字段（空串视为 null） */
    private String readPatientString(org.bson.Document patient, String key) {
        Object value = patient.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 构建数据XML（使用JAXB）
     */
    private String buildDataXml(VitalSignPayload payload) {
        com.digixmed.cloud.icu.pojo.DataValue dataValue = new com.digixmed.cloud.icu.pojo.DataValue();
        dataValue.setIsValid(payload.getIsValid());
        // mrn = patient.hisPid，patientId = patient.mrn，patientName = patient.name；
        // null 会使 JAXB 省略整个节点，因此空值统一输出空串
        dataValue.setMrn(payload.getMrn() != null ? payload.getMrn() : "");
        dataValue.setPatientId(payload.getPatientId() != null ? payload.getPatientId() : "");
        dataValue.setPatientName(payload.getPatientName() != null ? payload.getPatientName() : "");
        if (payload.getPlanTime() != null) {
            dataValue.setPlanTime(Date.from(payload.getPlanTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
        }
        if (payload.getRecordTime() != null) {
            dataValue.setRecordTime(Date.from(payload.getRecordTime().atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
        }
        // recordNurseId 为空时默认 "dba"
        String nurseId = payload.getRecordNurseId();
        dataValue.setRecordNurseId((nurseId != null && !nurseId.isEmpty()) ? nurseId : "dba");
        // recordNurseName 为空时默认 "系统管理员"
        String nurseName = payload.getRecordNurseName();
        dataValue.setRecordNurseName((nurseName != null && !nurseName.isEmpty()) ? nurseName : "系统管理员");
        dataValue.setSeries(payload.getSeries());
        dataValue.setUnit(payload.getUnit());
        dataValue.setWardCode(payload.getWardCode());
        dataValue.setRemark(payload.getRemark());
        dataValue.setVitalsignName(payload.getVitalsignName());
        dataValue.setVitalsignType(payload.getVitalsignType());
        dataValue.setVitalsignNVal1(payload.getVitalsignNVal1());
        dataValue.setVitalsignNVal2(payload.getVitalsignNVal2());
        dataValue.setVitalsignNVal3(payload.getVitalsignNVal3());
        dataValue.setVitalsignSVal1(payload.getVitalsignSVal1());
        dataValue.setVitalsignSVal2(payload.getVitalsignSVal2());

        com.digixmed.cloud.icu.pojo.Data data = new com.digixmed.cloud.icu.pojo.Data();
        java.util.List<com.digixmed.cloud.icu.pojo.DataValue> list = new java.util.ArrayList<>();
        list.add(dataValue);
        data.setData(list);

        return XMLUtils.convertToXml(data);
    }

    /**
     * 提取错误信息
     */
    private String extractErrorMsg(String responseMsg) {
        if (responseMsg == null) return "未知错误";
        String msg = ResponseUtils.extractMsgNode(responseMsg);
        return msg != null ? msg : responseMsg;
    }

    /**
     * 截断字符串
     */
    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() > maxLength ? str.substring(0, maxLength) + "..." : str;
    }

    /**
     * 脱敏患者ID
     */
    private String maskPatientId(String patientId) {
        if (patientId == null || patientId.length() <= 4) return "****";
        return patientId.substring(0, 2) + "****" + patientId.substring(patientId.length() - 2);
    }

    /**
     * 直接推送作废报文（isValid=0），跳过幂等检查和状态管理
     * 用于内容变化时先发送旧值作废，再由正常 push 流程发送新值
     */
    public void pushInvalidation(VitalSignPayload payload, String traceId) {
        String patientIdMasked = maskPatientId(payload.getPatientId());
        log.info("INVALIDATION_PUSH traceId={} patient={} metric={} planTime={} isValid=0 开始推送作废报文",
                traceId, patientIdMasked, payload.getVitalsignType(), payload.getPlanTime());

        String dataXml = buildDataXml(payload);
        String requestXml = DataUtils.getRequestStr(dataXml);
        if (requestXml == null || requestXml.trim().isEmpty()) {
            log.warn("INVALIDATION_PUSH traceId={} 作废报文为空，跳过", traceId);
            return;
        }

        try {
            Map<String, String> response = HttpUtils.doPost(pushProperties.getUrl(), requestXml);
            String responseCode = response.get("code");
            String responseMsg = response.get("msg");
            log.info("INVALIDATION_PUSH traceId={} httpCode={} response={}", traceId, responseCode, responseMsg);

            // 作废报文发送后更新队列记录的 requestMsg（记录作废报文内容）
            String idempotencyKey = buildIdempotencyKey(payload);
            Update update = new Update()
                    .set("invalidationRequestMsg", requestXml)
                    .set("invalidationResponseMsg", responseMsg)
                    .set("invalidationSentAt", new Date());
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("idempotencyKey").is(idempotencyKey)),
                    update, COLLECTION_NAME);
        } catch (Exception e) {
            log.error("INVALIDATION_PUSH traceId={} 作废报文推送异常", traceId, e);
        }
    }

    public enum PushResult {
        SUCCESS,
        RETRY,
        DEAD,
        SKIPPED
    }
}
