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
    /**
     * 推送单条体征数据（简化版：不做幂等检查，由 PushTask 管理状态）
     *
     * @return SUCCESS 或 FAILED
     */
    public PushResult push(VitalSignPayload payload, String traceId) {
        if (!ensurePatientIdentity(payload, traceId)) {
            return PushResult.SKIPPED;
        }

        String patientIdMasked = maskPatientId(payload.getPatientId());
        String idempotencyKey = buildIdempotencyKey(payload);
        log.info("PUSH traceId={} patient={} metric={} planTime={}",
                traceId, patientIdMasked, payload.getVitalsignType(), payload.getPlanTime());

        // 生成 SOAP XML
        String dataXml = buildDataXml(payload);
        String requestXml = DataUtils.getRequestStr(dataXml);
        if (requestXml == null || requestXml.trim().isEmpty()) {
            log.error("PUSH traceId={} 请求体为空", traceId);
            saveMessages(idempotencyKey, null, null);
            return PushResult.FAILED;
        }

        // 发送请求
        long startTime = System.currentTimeMillis();
        Map<String, String> response;
        try {
            response = HttpUtils.doPost(pushProperties.getUrl(), requestXml);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("PUSH traceId={} 请求异常 durationMs={}", traceId, duration, e);
            saveMessages(idempotencyKey, requestXml, null);
            return PushResult.FAILED;
        }

        long duration = System.currentTimeMillis() - startTime;
        String responseMsg = response.get("msg");
        String responseCode = response.get("code");

        log.info("PUSH traceId={} httpCode={} durationMs={}", traceId, responseCode, duration);
        log.info("PUSH traceId={} 请求报文:{}", traceId, requestXml);
        log.info("PUSH traceId={} 响应报文:{}", traceId, responseMsg);

        // 保存报文到队列记录
        saveMessages(idempotencyKey, requestXml, responseMsg);

        // 判断结果
        if ("200".equals(responseCode) && ResponseUtils.isBusinessSuccess(responseMsg)) {
            log.info("PUSH traceId={} 推送成功 patient={} metric={}",
                    traceId, patientIdMasked, payload.getVitalsignType());
            return PushResult.SUCCESS;
        } else {
            String errorMsg = (responseCode == null ? "无响应码" : "HTTP_" + responseCode) + " " + responseMsg;
            log.warn("PUSH traceId={} 推送失败: {}", traceId, errorMsg);
            return PushResult.FAILED;
        }
    }

    /** 保存请求/响应报文到队列记录 */
    private void saveMessages(String idempotencyKey, String requestXml, String responseMsg) {
        Update update = new Update().set("updatedAt", new Date());
        if (requestXml != null) {
            update.set("requestMsg", truncate(requestXml, pushProperties.getMaxRequestBodyLength()));
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

    private String buildIdempotencyKey(VitalSignPayload payload) {
        String planTimeStr = payload.getPlanTime() != null
                ? payload.getPlanTime().format(PLAN_TIME_FORMATTER) : "";
        return String.format("%s_%s_%s_%s",
                payload.getPatientId(), payload.getSeries(),
                payload.getVitalsignType(), planTimeStr);
    }

    /**
     * 推送前校准病人标识：
     *   1. 按 mongoPid 回查 patient 文档，不存在直接跳过（不判断金仓状态）
     *   2. patientId = patient.mrn，mrn = patient.hisPid，patientName = patient.name
     *   3. patientId 仍为空时不推送，避免 HIS 回"没有相关入参病人信息"导致 DEAD 堆积
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
    /**
     * 直接推送作废报文（isValid=0），不做幂等检查
     * 用于内容变化时先发送旧值作废，再由正常 push 流程发送新值
     */
    public PushResult pushInvalidation(VitalSignPayload payload, String traceId) {
        String patientIdMasked = maskPatientId(payload.getPatientId());
        log.info("INVALIDATION_PUSH traceId={} patient={} metric={} planTime={} isValid=0 开始推送作废报文",
                traceId, patientIdMasked, payload.getVitalsignType(), payload.getPlanTime());

        String dataXml = buildDataXml(payload);
        String requestXml = DataUtils.getRequestStr(dataXml);
        if (requestXml == null || requestXml.trim().isEmpty()) {
            log.warn("INVALIDATION_PUSH traceId={} 作废报文为空，跳过", traceId);
            return PushResult.FAILED;
        }

        try {
            Map<String, String> response = HttpUtils.doPost(pushProperties.getUrl(), requestXml);
            String responseCode = response.get("code");
            String responseMsg = response.get("msg");
            log.info("INVALIDATION_PUSH traceId={} httpCode={} response={}", traceId, responseCode, responseMsg);

            if ("200".equals(responseCode) && ResponseUtils.isBusinessSuccess(responseMsg)) {
                return PushResult.SUCCESS;
            }
            return PushResult.FAILED;
        } catch (Exception e) {
            log.error("INVALIDATION_PUSH traceId={} 作废报文推送异常", traceId, e);
            return PushResult.FAILED;
        }
    }

    public enum PushResult {
        SUCCESS,
        FAILED,
        SKIPPED
    }
}
