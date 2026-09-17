package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.NurseInfo;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
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
        fillCommonFields(payload, patient, bedside, mongoTemplate, traceId, null);
    }

    /**
     * 填充公共字段（支持出入量往前搜索 param_Yishi）
     *
     * @param searchBefore 出入量场景传入 window.getEnd()，普通体征传 null
     */
    protected void fillCommonFields(VitalSignPayload payload, Document patient, Document bedside,
                                     MongoTemplate mongoTemplate, String traceId, Date searchBefore) {
        payload.setPatientId(patientIdentityMapper.getPatientId(patient));
        payload.setMrn(patientIdentityMapper.getMrn(patient));
        payload.setPatientName(patientIdentityMapper.getPatientName(patient));
        payload.setSeries(patientIdentityMapper.getSeries(patient));
        payload.setWardCode(patientIdentityMapper.getWardCode());

        // 解析护士：param_Yishi / editUser / responsibleNurse
        String pid = getValueFromDocByKey(patient, "_id", String.class);
        NurseInfo nurseInfo = resolveNurseFromBedside(pid, bedside, mongoTemplate, traceId);

        // 出入量场景：param_Yishi 精确查询失败时，往前搜8小时
        if (nurseInfo == null && searchBefore != null) {
            nurseInfo = resolveNurseForIntakeOutput(pid, searchBefore, mongoTemplate, traceId);
        }

        // 兜底：patient.responsibleNurse
        if (nurseInfo == null) {
            nurseInfo = resolveNurseFromResponsible(patient, mongoTemplate, traceId);
        }

        // 最终兜底
        if (nurseInfo != null) {
            payload.setRecordNurseId(nurseInfo.getNurseId());
            payload.setRecordNurseName(nurseInfo.getNurseName());
            log.info("STEP_07_NURSE traceId={} nurseId={} nurseName={} source={}",
                    traceId, nurseInfo.getNurseId(), nurseInfo.getNurseName(), nurseInfo.getSource());
        } else {
            payload.setRecordNurseId(patientIdentityMapper.getRecordNurseId());
            payload.setRecordNurseName("陈琳");
            log.warn("STEP_07_NURSE traceId={} 所有解析方式均失败，使用默认值", traceId);
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
        payload.setSeries(patientIdentityMapper.getSeries(patient));
        payload.setWardCode(patientIdentityMapper.getWardCode());

        // 解析护士：patient.responsibleNurse 兜底
        NurseInfo nurseInfo = resolveNurseFromResponsible(patient, null, traceId);
        if (nurseInfo != null) {
            payload.setRecordNurseId(nurseInfo.getNurseId());
            payload.setRecordNurseName(nurseInfo.getNurseName());
            log.info("STEP_07_NURSE traceId={} nurseId={} nurseName={} source=responsibleNurse",
                    traceId, nurseInfo.getNurseId(), nurseInfo.getNurseName());
        } else {
            payload.setRecordNurseId(patientIdentityMapper.getRecordNurseId());
            payload.setRecordNurseName("陈琳");
            log.warn("STEP_07_NURSE traceId={} responsibleNurse解析失败，使用默认值", traceId);
        }

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
     * 从 bedside 记录解析护士
     *
     * 优先级：
     *   1. param_Yishi 同时间的 editUser → 查 account
     *   2. 当前 bedside 记录的 editUser → 查 account
     *
     * @return NurseInfo，未找到返回 null
     */
    protected NurseInfo resolveNurseFromBedside(String pid, Document bedside, MongoTemplate mongoTemplate, String traceId) {
        Date bedsideTime = getValueFromDocByKey(bedside, "time", Date.class);

        // 1. 优先查询 param_Yishi 的 editUser
        if (bedsideTime != null && pid != null) {
            Query yishiQuery = new Query(Criteria.where("pid").is(pid)
                    .and("code").is("param_Yishi")
                    .and("valid").ne(false).ne(0)
                    .and("time").is(bedsideTime));
            Document yishiDoc = mongoTemplate.findOne(yishiQuery, Document.class, "bedside");
            if (yishiDoc != null) {
                Object valid = yishiDoc.get("valid");
                if (Boolean.FALSE.equals(valid) || Integer.valueOf(0).equals(valid)) {
                    yishiDoc = null;
                }
            }
            if (yishiDoc != null) {
                String editUser = readEditUserValue(yishiDoc);
                if (editUser != null && !editUser.isEmpty()) {
                    NurseInfo info = lookupNurseFromEditUser(editUser, mongoTemplate, "param_Yishi", traceId);
                    if (info != null) {
                        return info;
                    }
                }
            }
        }

        // 2. 从当前 bedside 记录的 editUser
        String editUser = readEditUserValue(bedside);
        if (editUser != null && !editUser.isEmpty()) {
            NurseInfo info = lookupNurseFromEditUser(editUser, mongoTemplate, "source_record", traceId);
            if (info != null) {
                return info;
            }
        }

        return null;
    }

    /**
     * 出入量场景：从 param_Yishi 往前搜索护士（最多8小时）
     *
     * @param pid          患者 MongoDB _id
     * @param searchBefore 搜索起点（通常为 window.getEnd()）
     * @return NurseInfo，未找到返回 null
     */
    protected NurseInfo resolveNurseForIntakeOutput(String pid, Date searchBefore, MongoTemplate mongoTemplate, String traceId) {
        if (pid == null || searchBefore == null) {
            return null;
        }

        Date searchStart = Date.from(searchBefore.toInstant().minus(Duration.ofHours(8)));
        log.info("STEP_07_NURSE traceId={} 出入量往前搜索 param_Yishi pid={} range=[{}, {}]",
                traceId, pid, searchStart, searchBefore);

        try {
            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("code").is("param_Yishi")
                    .and("valid").ne(false).ne(0)
                    .and("time").gte(searchStart).lt(searchBefore))
                    .with(Sort.by(Sort.Direction.DESC, "time"))
                    .limit(1);
            Document yishiDoc = mongoTemplate.findOne(query, Document.class, "bedside");
            if (yishiDoc != null) {
                Object valid = yishiDoc.get("valid");
                if (Boolean.FALSE.equals(valid) || Integer.valueOf(0).equals(valid)) {
                    yishiDoc = null;
                }
            }
            if (yishiDoc != null) {
                String editUser = readEditUserValue(yishiDoc);
                if (editUser != null && !editUser.isEmpty()) {
                    NurseInfo info = lookupNurseFromEditUser(editUser, mongoTemplate, "intake_output_yishi", traceId);
                    if (info != null) {
                        return info;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("STEP_07_NURSE traceId={} 出入量搜索 param_Yishi 异常: {}", traceId, e.getMessage());
        }

        log.info("STEP_07_NURSE traceId={} 出入量往前搜索 param_Yishi 未找到", traceId);
        return null;
    }

    /**
     * 从 patient.responsibleNurse 解析护士
     *
     * @return NurseInfo，未找到返回 null
     */
    protected NurseInfo resolveNurseFromResponsible(Document patient, MongoTemplate mongoTemplate, String traceId) {
        Object responsibleObj = patient != null ? patient.get("responsibleNurse") : null;
        if (responsibleObj == null) {
            return null;
        }
        String responsibleName = responsibleObj.toString().trim();
        if (responsibleName.isEmpty()) {
            return null;
        }

        log.info("STEP_07_NURSE traceId={} 尝试从 patient.responsibleNurse={} 解析", traceId, responsibleName);

        // 按 trueName 查 account 集合
        if (mongoTemplate != null) {
            try {
                Query query = new Query(Criteria.where("trueName").is(responsibleName)).limit(1);
                Document account = mongoTemplate.findOne(query, Document.class, "account");
                if (account != null) {
                    String username = account.getString("username");
                    if (username != null && !username.trim().isEmpty()) {
                        NurseInfo info = NurseInfo.builder()
                                .nurseId(username.trim())
                                .nurseName(responsibleName)
                                .source("responsibleNurse")
                                .reasonCode(null)
                                .build();
                        log.info("STEP_07_NURSE traceId={} responsibleNurse解析成功 nurseId={} nurseName={}",
                                traceId, info.getNurseId(), info.getNurseName());
                        return info;
                    }
                }
            } catch (Exception e) {
                log.warn("STEP_07_NURSE traceId={} responsibleNurse查询account异常: {}", traceId, e.getMessage());
            }
        }

        log.info("STEP_07_NURSE traceId={} responsibleNurse解析失败 name={}", traceId, responsibleName);
        return null;
    }

    /**
     * 根据 editUser (ObjectId) 查 account 集合，返回 NurseInfo
     */
    private NurseInfo lookupNurseFromEditUser(String editUser, MongoTemplate mongoTemplate, String source, String traceId) {
        try {
            ObjectId accountId = new ObjectId(editUser);
            Query accountQuery = new Query(Criteria.where("_id").is(accountId));
            Document account = mongoTemplate.findOne(accountQuery, Document.class, "account");
            if (account != null) {
                String username = account.getString("username");
                String trueName = account.getString("trueName");
                if (trueName != null && !trueName.trim().isEmpty()) {
                    return NurseInfo.builder()
                            .nurseId(username != null ? username.trim() : "")
                            .nurseName(trueName.trim())
                            .source(source)
                            .reasonCode(null)
                            .build();
                }
            }
        } catch (Exception e) {
            log.warn("STEP_07_NURSE traceId={} 查询account失败 editUser={}: {}", traceId, editUser, e.getMessage());
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
