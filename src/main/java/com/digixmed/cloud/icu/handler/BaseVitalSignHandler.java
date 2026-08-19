package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

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
 *   - recordNurseId = "041660"
 *   - planTime = bedside.time
 *   - recordTime = bedside.time
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
     * @param bedside MongoDB bedside记录（用于获取time字段）
     * @param mongoTemplate MongoDB模板（用于查询account）
     * @param traceId 追踪ID
     */
    protected void fillCommonFields(VitalSignPayload payload, Document patient, Document bedside,
                                     MongoTemplate mongoTemplate, String traceId) {
        payload.setPatientId(patientIdentityMapper.getPatientId(patient));
        payload.setMrn(patientIdentityMapper.getMrn(patient));
        payload.setPatientName(patientIdentityMapper.getPatientName(patient));
        payload.setSeries(patientIdentityMapper.getSeries());
        payload.setWardCode(patientIdentityMapper.getWardCode());

        // 从 account 动态获取记录者工号和姓名
        String pid = getValueFromDocByKey(patient, "_id", String.class);
        Document account = resolveRecordAccount(pid, bedside, mongoTemplate, traceId);
        if (account != null) {
            String username = getValueFromDocByKey(account, "username", String.class);
            String trueName = getValueFromDocByKey(account, "trueName", String.class);
            payload.setRecordNurseId(username != null ? username : "041660");
            payload.setRecordNurseName(trueName != null ? trueName : "陈琳");
            log.info("STEP_07_NURSE traceId={} account resolved username={} trueName={}", traceId, username, trueName);
        } else {
            payload.setRecordNurseId("041660");
            payload.setRecordNurseName("陈琳");
            log.warn("STEP_07_NURSE traceId={} 未找到account记录，使用默认值", traceId);
        }

        // planTime 和 recordTime 都使用 bedside.time
        LocalDateTime bedsideTime = getBedsideTime(bedside);
        payload.setPlanTime(bedsideTime);
        payload.setRecordTime(bedsideTime);

        payload.setRemark("");
        payload.setIsValid(1);
        payload.setTraceId(traceId);
        payload.setMongoPid(pid);
    }

    /**
     * 填充公共字段（使用指定的 planTime）
     * 用于没有 bedside 记录的场景（如身高体重）
     *
     * @param payload 推送载荷
     * @param patient MongoDB patient文档
     * @param planTime 指定的时间
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
     * 从 bedside 文档获取 time 字段并转换为 LocalDateTime
     *
     * @param bedside MongoDB bedside记录
     * @return bedside.time 的 LocalDateTime 表示
     */
    protected LocalDateTime getBedsideTime(Document bedside) {
        Date time = getValueFromDocByKey(bedside, "time", Date.class);
        if (time != null) {
            return time.toInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        }
        return null;
    }

    /**
     * 获取记录护士姓名
     * 优先从 param_Yishi 的 editUser 获取，如果没有则从当前记录的 editUser 获取
     *
     * @param pid 患者MongoDB ID
     * @param bedside 当前bedside记录
     * @param mongoTemplate MongoDB模板
     * @param traceId 追踪ID
     * @return 护士真实姓名
     */
    /** 读取 editUser（兼容 String 与 ObjectId 两种存储形式） */
    protected String readEditUserValue(Document doc) {
        if (doc == null) {
            return null;
        }
        Object editUser = doc.get("editUser");
        if (editUser == null) {
            return null;
        }
        String value = editUser.toString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 解析记录者的 account 文档
     *
     * 查找顺序：
     *   1. bedside 中 code=param_Yishi 同时刻的 editUser
     *   2. 当前 bedside 记录的 editUser
     *   3. editUser (ObjectId) → 查 account 集合
     *
     * @return account 文档，未找到返回 null
     */
    protected Document resolveRecordAccount(String pid, Document bedside, MongoTemplate mongoTemplate, String traceId) {
        String editUser = null;

        // 1. 优先查询 param_Yishi 的 editUser
        Date bedsideTime = getValueFromDocByKey(bedside, "time", Date.class);
        if (bedsideTime != null && pid != null) {
            Query yishiQuery = new Query(Criteria.where("pid").is(pid)
                    .and("code").is("param_Yishi")
                    .and("valid").ne(false)
                    .and("time").is(bedsideTime));
            Document yishiDoc = mongoTemplate.findOne(yishiQuery, Document.class, "bedside");
            if (yishiDoc != null) {
                editUser = readEditUserValue(yishiDoc);
                log.info("STEP_07_NURSE traceId={} 从param_Yishi获取editUser={}", traceId, editUser);
            }
        }

        // 2. 如果没有 param_Yishi，从当前记录获取 editUser
        if (editUser == null || editUser.isEmpty()) {
            editUser = readEditUserValue(bedside);
            log.info("STEP_07_NURSE traceId={} 从当前记录获取editUser={}", traceId, editUser);
        }

        // 3. 根据 editUser 查询 account
        if (editUser != null && !editUser.isEmpty()) {
            try {
                ObjectId accountId = new ObjectId(editUser);
                Query accountQuery = new Query(Criteria.where("_id").is(accountId));
                Document account = mongoTemplate.findOne(accountQuery, Document.class, "account");
                if (account != null) {
                    return account;
                }
            } catch (Exception e) {
                log.warn("STEP_07_NURSE traceId={} 查询account失败 editUser={}: {}", traceId, editUser, e.getMessage());
            }
        }

        return null;
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
