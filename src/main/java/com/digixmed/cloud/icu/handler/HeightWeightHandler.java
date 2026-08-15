package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.InpatientDTO;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.repository.InpatientRepository;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import com.digixmed.cloud.icu.service.HeightWeightNurseService;
import com.digixmed.cloud.icu.service.HeightWeightNurseService.NurseRef;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/**
 * 身高体重处理器
 *
 * 源数据：Mongo dFormData 中的 fieldDataList 数组
 * 输出：
 *   - 身高：vitalsignType=1013, unit=cm, 值放 vitalsignNVal1
 *   - 体重：vitalsignType=1014, unit=kg, 值放 vitalsignSVal1（按院方要求，NVal1 送空串占位）
 *
 * 回传时机：
 *   - 入科当天（pageDayIndex=0）：由 VitalSignScanTask 的入科扫描负责，
 *     与入科第一条生命体征一起回传，记录者与同批体温一致；
 *   - 之后：pageDayIndex > 0 且 % 7 == 0，由 DailySummaryTask 在 07:00 槽位回传。
 *
 * 记录者：一律取 HeightWeightNurseService 锁定的记录者，
 *        即"入科第一条回传时对应体温的记录者"，之后永不变化。
 *
 * 入科日期解析：
 *   1. reportDate 与 patient.icuAdmissionTime 同一天 → 直接用 icuAdmissionTime；
 *   2. 不同一天 → 查 KingBase：
 *      select * from np_nis_cqchonggang.inpatients where patient_id = ?（patient_id = patient.mrn），
 *      取 admission_ward_time；
 *   3. 都拿不到 → 不回传，日志记录未获取到 admission_ward_time。
 *
 * 字段优先级：身高 sg, fg；体重 tz, zt
 */
@Component
public class HeightWeightHandler extends BaseVitalSignHandler {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

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

    @Autowired
    private HeightWeightNurseService heightWeightNurseService;

    public HeightWeightHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        // 身高体重不走 bedside，统一由 buildHeightPayload / buildWeightPayload 构建
        return null;
    }

    /**
     * 构建身高payload
     *
     * @param nurse 已锁定的记录者，不允许为 null
     */
    public VitalSignPayload buildHeightPayload(Document patient, LocalDateTime planTime,
                                               NurseRef nurse, String traceId) {
        String pid = readPid(patient);
        if (pid == null) {
            return null;
        }

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
        applyNurse(payload, nurse, pid, traceId);
        return payload;
    }

    /**
     * 构建体重payload
     *
     * 注意：按院方要求，体重值回传在 vitalsignSVal1，
     *      vitalsignNVal1 送空串而不是 null —— JAXB 遇到 null 会整节点省略，
     *      对端解析时可能因缺节点报错。
     */
    public VitalSignPayload buildWeightPayload(Document patient, LocalDateTime planTime,
                                               NurseRef nurse, String traceId) {
        String pid = readPid(patient);
        if (pid == null) {
            return null;
        }

        Document formData = findValidFormData(pid);
        if (formData == null) {
            log.warn("STEP_04 traceId={} 未找到有效表单数据 pid={}", traceId, pid);
            return null;
        }

        String weightValue = extractFieldFromFormData(formData, WEIGHT_FIELDS);
        if (weightValue == null) {
            log.warn("STEP_05_VALUE_PARSED traceId={} 无法获取体重值 pid={}", traceId, pid);
            return null;
        }

        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} pid={} 体重={}（回传于vitalsignSVal1）",
                traceId, pid, weightValue);

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("体重")
                .vitalsignType("1014")
                .vitalsignNVal1("")
                .vitalsignSVal1(weightValue)
                .unit("kg")
                .build();

        fillCommonFields(payload, patient, planTime, traceId);
        applyNurse(payload, nurse, pid, traceId);
        return payload;
    }

    /**
     * 写入锁定的记录者；nurse 为空时退化为按 pid 解析
     */
    private void applyNurse(VitalSignPayload payload, NurseRef nurse, String pid, String traceId) {
        NurseRef effective = (nurse != null) ? nurse : heightWeightNurseService.resolve(pid);
        payload.setRecordNurseId(effective.getId());
        payload.setRecordNurseName(effective.getName());
        log.info("STEP_07_NURSE traceId={} pid={} 身高体重记录者={} pinned={}",
                traceId, pid, effective.getName(), effective.isPinned());
    }

    /**
     * 检查是否应该发送身高体重（7天分页，入科当天由入科扫描负责）
     *
     * @param patientId  住院号（patient.mrn），用于查 KingBase
     * @param reportDate 报表日期
     * @param patient    MongoDB patient文档
     */
    public boolean shouldSendHeightWeight(String patientId, LocalDate reportDate, Document patient) {
        LocalDate admissionDate = resolveAdmissionDate(patientId, patient, reportDate);
        if (admissionDate == null) {
            return false;
        }
        return timeWindowService.shouldSendHeightWeight(admissionDate, reportDate);
    }

    /**
     * 入科日期解析
     *
     * 修复点：原实现用 LocalDate.now() 且未指定时区来判断"是否同一天"，
     *        既忽略了传入的 reportDate（补跑/回溯历史日期时必然算错），
     *        也用了 JVM 默认时区（本项目其他位置一律禁止）。现改为用 reportDate + Asia/Shanghai。
     *
     * @param patientId  住院号（patient.mrn）→ KingBase inpatients.patient_id
     * @param patient    MongoDB patient文档
     * @param reportDate 报表日期
     * @return 入科日期，查不到返回 null（表示不回传）
     */
    private LocalDate resolveAdmissionDate(String patientId, Document patient, LocalDate reportDate) {
        // 1. 先取 patient.icuAdmissionTime
        java.util.Date icuAdmissionTime = getValueFromDocByKey(patient, "icuAdmissionTime", java.util.Date.class);
        LocalDate icuDate = icuAdmissionTime != null
                ? icuAdmissionTime.toInstant().atZone(ZONE).toLocalDate()
                : null;

        // 2. reportDate 与 icuAdmissionTime 同一天 → 直接用
        if (icuDate != null && icuDate.equals(reportDate)) {
            log.info("报表日期与入科日期同一天，直接使用icuAdmissionTime patientId={} 入科日期={}",
                    maskPatientId(patientId), icuDate);
            return icuDate;
        }

        // 3. 不同一天 → 查 KingBase admission_ward_time
        if (patientId == null || patientId.trim().isEmpty()) {
            log.warn("未获取到admission_ward_time：patient.mrn 为空，跳过身高体重回传");
            return null;
        }

        try {
            InpatientDTO inpatient = inpatientRepository.findByPatientId(patientId);
            if (inpatient != null && inpatient.getAdmissionWardTime() != null) {
                LocalDate wardDate = inpatient.getAdmissionWardTime().toLocalDate();
                log.info("从KingBase获取admission_ward_time成功 patientId={} 入科日期={}",
                        maskPatientId(patientId), wardDate);
                return wardDate;
            }
        } catch (Exception e) {
            log.warn("查询金仓入科时间失败 patientId={}: {}", maskPatientId(patientId), e.getMessage());
        }

        // 4. 查不到 → 不回传并记录日志
        log.warn("未获取到admission_ward_time，跳过身高体重回传 patientId={} reportDate={}",
                maskPatientId(patientId), reportDate);
        return null;
    }

    private String readPid(Document patient) {
        Object id = getValueFromDocByKey(patient, "_id", Object.class);
        return id != null ? id.toString() : null;
    }

    private Document findValidFormData(String pid) {
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("status").is("valid")
                .and("formCode").in(FORM_CODES))
                .with(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createTime"))
                .limit(1);

        return mongoTemplate.findOne(query, Document.class, "dFormData");
    }

    @SuppressWarnings("unchecked")
    private String extractFieldFromFormData(Document formData, List<String> fields) {
        if (formData == null) {
            return null;
        }

        Object fieldDataListObj = formData.get("fieldDataList");
        if (!(fieldDataListObj instanceof List)) {
            log.warn("fieldDataList不是数组类型: {}",
                    fieldDataListObj != null ? fieldDataListObj.getClass().getName() : "null");
            return null;
        }

        List<Document> fieldDataList = (List<Document>) fieldDataListObj;

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

    private String maskPatientId(String patientId) {
        if (patientId == null || patientId.length() <= 4) {
            return "****";
        }
        return patientId.substring(0, 2) + "****" + patientId.substring(patientId.length() - 2);
    }
}
