package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * 体征处理器抽象基类
 *
 * 业务目的：统一所有体征处理器的公共逻辑
 * 输入：MongoDB bedside记录、患者信息
 * 输出：VitalSignPayload
 * 异常策略：单条记录处理失败时记录ERROR日志并返回null，不中断批次
 *
 * 公共默认值：
 *   - series = "1"
 *   - remark = ""
 *   - isValid = 1
 *   - wardCode = "125011"
 *   - recordNurseId = "dba"
 */
public abstract class BaseVitalSignHandler {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final PatientIdentityMapper patientIdentityMapper;

    protected BaseVitalSignHandler(PatientIdentityMapper patientIdentityMapper) {
        this.patientIdentityMapper = patientIdentityMapper;
    }

    /**
     * 处理体征记录
     *
     * @param bedside MongoDB bedside记录
     * @param patient MongoDB patient文档
     * @param planTime 标准时间点
     * @param traceId 追踪ID
     * @return VitalSignPayload，处理失败返回null
     */
    public abstract VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId);

    /**
     * 填充公共字段
     *
     * @param payload 推送载荷
     * @param patient MongoDB patient文档
     * @param planTime 标准时间点
     * @param traceId 追踪ID
     */
    protected void fillCommonFields(VitalSignPayload payload, Document patient, LocalDateTime planTime, String traceId) {
        payload.setPatientId(patientIdentityMapper.getPatientId(patient));
        payload.setMrn(patientIdentityMapper.getMrn(patient));
        payload.setPatientName(patientIdentityMapper.getPatientName(patient));
        payload.setSeries(patientIdentityMapper.getSeries());
        payload.setWardCode(patientIdentityMapper.getWardCode());
        payload.setRecordNurseId(patientIdentityMapper.getRecordNurseId());
        payload.setPlanTime(planTime);
        payload.setRecordTime(planTime);
        payload.setRemark("");
        payload.setIsValid(1);
        payload.setTraceId(traceId);
        payload.setMongoPid(getValueFromDocByKey(patient, "_id", String.class));
    }

    /**
     * 从Document获取值
     *
     * @param doc Document
     * @param key 键
     * @param clazz 值类型
     * @return 值，不存在返回null
     */
    protected <T> T getValueFromDocByKey(Document doc, String key, Class<T> clazz) {
        if (doc == null) {
            return null;
        }
        try {
            Object value = doc.get(key);
            if (value == null) {
                return null;
            }
            if (clazz.isInstance(value)) {
                return clazz.cast(value);
            }
            // 尝试类型转换
            if (clazz == String.class) {
                return (T) value.toString();
            }
            if (clazz == Integer.class && value instanceof Number) {
                return (T) Integer.valueOf(((Number) value).intValue());
            }
            if (clazz == Double.class && value instanceof Number) {
                return (T) Double.valueOf(((Number) value).doubleValue());
            }
            if (clazz == Long.class && value instanceof Number) {
                return (T) Long.valueOf(((Number) value).longValue());
            }
            if (clazz == Boolean.class && value instanceof Boolean) {
                return (T) value;
            }
        } catch (Exception e) {
            log.error("getValueFromDocByKey error, key={}", key, e);
        }
        return null;
    }

    /**
     * 解析Double值，失败返回null
     *
     * @param value 字符串值
     * @return Double值，解析失败返回null
     */
    protected Double parseDouble(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 格式化Double值，去除无意义末尾0
     *
     * @param value Double值
     * @return 格式化后的字符串
     */
    protected String formatDouble(Double value) {
        if (value == null) {
            return null;
        }
        // 使用BigDecimal去除末尾0
        java.math.BigDecimal bd = new java.math.BigDecimal(value.toString());
        bd = bd.stripTrailingZeros();
        return bd.toPlainString();
    }
}
