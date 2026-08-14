package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.InpatientDTO;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.repository.InpatientRepository;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 身高体重处理器
 *
 * 业务目的：处理身高和体重的发送（7天分页）
 * 源数据：Mongo dFormData中的fieldDataList数组
 * 输出：
 *   - 身高：vitalsignType=1013, unit=cm
 *   - 体重：vitalsignType=1014, unit=kg
 *
 * 发送条件：
 *   pageDayIndex = DAYS.between(admissionWardDate, reportDate)
 *   pageDayIndex >= 0 AND pageDayIndex % 7 == 0
 *
 * 字段优先级：
 *   身高：sg, fg
 *   体重：tz, zt
 *
 * 实际附件结构：
 * {
 *   "fieldDataList": [
 *     {"field": "tz", "value": "160"},
 *     {"field": "sg", "value": "161"}
 *   ]
 * }
 */
@Component
public class HeightWeightHandler extends BaseVitalSignHandler {

    private static final List<String> HEIGHT_FIELDS = Arrays.asList("sg", "fg");
    private static final List<String> WEIGHT_FIELDS = Arrays.asList("tz", "zt");
    private static final List<String> FORM_CODES = Arrays.asList(
            "ruyuanhulipinggudan",
            "zhuanruhulipinggudan"
    );

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private InpatientRepository inpatientRepository;

    @Autowired
    private ClinicalTimeWindowService timeWindowService;

    public HeightWeightHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        // 身高体重不通过bedside处理，而是通过dFormData和Kingbase
        return null;
    }

    /**
     * 构建身高payload
     */
    public VitalSignPayload buildHeightPayload(Document patient, LocalDateTime planTime, String traceId) {
        String pid = getValueFromDocByKey(patient, "_id", Object.class) != null
                ? getValueFromDocByKey(patient, "_id", Object.class).toString() : null;
        if (pid == null) {
            return null;
        }

        // 查询dFormData获取身高
        Document formData = findValidFormData(pid);
        if (formData == null) {
            log.warn("STEP_04 traceId={} 未找到有效表单数据 pid={}", traceId, pid);
            return null;
        }

        String heightValue = extractFieldFromFormData(formData, HEIGHT_FIELDS);
        if (heightValue == null) {
            log.warn("STEP_05_VALUE_PARSED traceId={} 无法获取身高值 pid={}", traceId, pid);
            return null;
        }

        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} pid={} 身高={}", traceId, pid, heightValue);

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("身高")
                .vitalsignType("1013")
                .vitalsignNVal1(heightValue)
                .unit("cm")
                .build();

        fillCommonFields(payload, patient, planTime, traceId);
        return payload;
    }

    /**
     * 构建体重payload
     */
    public VitalSignPayload buildWeightPayload(Document patient, LocalDateTime planTime, String traceId) {
        String pid = getValueFromDocByKey(patient, "_id", Object.class) != null
                ? getValueFromDocByKey(patient, "_id", Object.class).toString() : null;
        if (pid == null) {
            return null;
        }

        Document formData = findValidFormData(pid);
        if (formData == null) {
            return null;
        }

        String weightValue = extractFieldFromFormData(formData, WEIGHT_FIELDS);
        if (weightValue == null) {
            log.warn("STEP_05_VALUE_PARSED traceId={} 无法获取体重值 pid={}", traceId, pid);
            return null;
        }

        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} pid={} 体重={}", traceId, pid, weightValue);

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("体重")
                .vitalsignType("1014")
                .vitalsignNVal1(weightValue)
                .unit("kg")
                .build();

        fillCommonFields(payload, patient, planTime, traceId);
        return payload;
    }

    /**
     * 检查是否应该发送身高体重
     */
    public boolean shouldSendHeightWeight(String patientId, LocalDate reportDate) {
        LocalDate admissionDate = resolveAdmissionDate(patientId);
        if (admissionDate == null) {
            return false;
        }
        return timeWindowService.shouldSendHeightWeight(admissionDate, reportDate);
    }

    /**
     * 入科日期：优先金仓 admission_ward_time，查不到时回退 Mongo patient.icuAdmissionTime，
     * 不再因为金仓无在科记录而直接不发送。
     */
    private LocalDate resolveAdmissionDate(String patientId) {
        try {
            InpatientDTO inpatient = inpatientRepository.findByPatientId(patientId);
            if (inpatient != null && inpatient.getAdmissionWardTime() != null) {
                return inpatient.getAdmissionWardTime().toLocalDate();
            }
        } catch (Exception e) {
            log.warn("查询金仓入科时间失败 patientId={}: {}", patientId, e.getMessage());
        }

        if (patientId == null || patientId.trim().isEmpty()) {
            return null;
        }

        Query query = new Query(new Criteria().orOperator(
                Criteria.where("mrn").is(patientId),
                Criteria.where("hisPid").is(patientId)
        ));
        Document patient = mongoTemplate.findOne(query, Document.class, "patient");
        java.util.Date icuAdmissionTime = getValueFromDocByKey(patient, "icuAdmissionTime", java.util.Date.class);
        if (icuAdmissionTime == null) {
            log.info("未获取到入科时间，跳过身高体重 patientId={}", patientId);
            return null;
        }
        return icuAdmissionTime.toInstant().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
    }

    /**
     * 查找有效的表单数据
     */
    private Document findValidFormData(String pid) {
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("status").is("valid")
                .and("formCode").in(FORM_CODES))
                .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createTime"))
                .limit(1);

        return mongoTemplate.findOne(query, Document.class, "dFormData");
    }

    /**
     * 从表单数据的fieldDataList中提取字段值
     *
     * 实际结构：
     * {
     *   "fieldDataList": [
     *     {"field": "tz", "value": "160"},
     *     {"field": "sg", "value": "161"}
     *   ]
     * }
     *
     * @param formData MongoDB dFormData文档
     * @param fields 字段优先级列表
     * @return 字段值，未找到返回null
     */
    @SuppressWarnings("unchecked")
    private String extractFieldFromFormData(Document formData, List<String> fields) {
        if (formData == null) {
            return null;
        }

        // 获取fieldDataList数组
        Object fieldDataListObj = formData.get("fieldDataList");
        if (!(fieldDataListObj instanceof List)) {
            log.warn("fieldDataList不是数组类型: {}", fieldDataListObj != null ? fieldDataListObj.getClass().getName() : "null");
            return null;
        }

        List<Document> fieldDataList = (List<Document>) fieldDataListObj;

        // 按优先级查找字段
        for (String field : fields) {
            for (Document fieldData : fieldDataList) {
                String fieldName = fieldData.getString("field");
                if (field.equals(fieldName)) {
                    String value = fieldData.getString("value");
                    if (value != null && !value.trim().isEmpty()) {
                        log.info("从fieldDataList提取字段 field={} value={}", field, value);
                        return value.trim();
                    }
                }
            }
        }

        return null;
    }
}
