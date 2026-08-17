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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 身高体重处理器
 *
 * 源数据：Mongo dFormData 中的 fieldDataList 数组
 * 输出：
 *   - 身高：vitalsignType=1013, unit=cm, 值放 vitalsignNVal1
 *   - 体重：vitalsignType=1014, unit=ml, 值放 vitalsignSVal1
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
 * 字段优先级：身高 sg, fg；体重 zt
 */
@Component
public class HeightWeightHandler extends BaseVitalSignHandler {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 身高体重差异项：除这三个参数外，取数、时间、记录者、公共字段完全共用 */
    private enum Metric {
        HEIGHT("身高", "1013", Collections.singletonList("sg"),
                (p, v) -> p.setVitalsignNVal1(v)),
        WEIGHT("体重", "1014", Collections.singletonList("tz"),
                (p, v) -> p.setVitalsignSVal1(v));

        private final String name;
        private final String type;
        private final List<String> sourceFields;
        private final BiConsumer<VitalSignPayload, String> valueSetter;

        Metric(String name, String type, List<String> sourceFields,
               BiConsumer<VitalSignPayload, String> valueSetter) {
            this.name = name;
            this.type = type;
            this.sourceFields = sourceFields;
            this.valueSetter = valueSetter;
        }
    }

    private static final List<String> FORM_CODES = Arrays.asList(
            "ruyuanhulipinggudan",
            "zhuanruhulipinggudan"
    );

    /** 身高单位 */
    @Value("${vitalsign.height-weight.height-unit:cm}")
    private String heightUnit;

    /** 体重单位：院方口径为 ml，联调确认后可改 kg */
    @Value("${vitalsign.height-weight.weight-unit:ml}")
    private String weightUnit;

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

    public VitalSignPayload buildHeightPayload(Document patient, LocalDateTime planTime,
                                               NurseRef nurse, String traceId) {
        return build(patient, planTime, nurse, traceId, Metric.HEIGHT, heightUnit);
    }

    public VitalSignPayload buildWeightPayload(Document patient, LocalDateTime planTime,
                                               NurseRef nurse, String traceId) {
        return build(patient, planTime, nurse, traceId, Metric.WEIGHT, weightUnit);
    }

    /**
     * 身高体重统一构建
     *
     * 取数：patient._id == dFormData.pid
     *      且 formCode ∈ (ruyuanhulipinggudan, zhuanruhulipinggudan)
     *      取 fieldDataList 中 field == 约定字段 的 value
     *
     * 差异仅三处：源字段、单位、值落到 NVal1 还是 SVal1。
     * 其余（planTime/recordTime = 当天07:00、series=1、wardCode=125011、
     * patientId=mrn、mrn=hisPid、remark 空、isValid=1、记录者锁定）两者完全一致。
     */
    private VitalSignPayload build(Document patient, LocalDateTime planTime, NurseRef nurse,
                                   String traceId, Metric metric, String unit) {
        String pid = readPid(patient);
        if (pid == null) {
            return null;
        }

        Document formData = findFormData(pid, traceId);
        if (formData == null) {
            log.warn("STEP_04 traceId={} pid={} 未找到入院/转入护理评估单，{}不回传",
                    traceId, pid, metric.name);
            return null;
        }

        String value = extractFieldFromFormData(formData, metric.sourceFields);
        if (value == null) {
            log.warn("STEP_05_VALUE_PARSED traceId={} pid={} 评估单无{}字段或值为空 formCode={}",
                    traceId, pid, metric.sourceFields, formData.getString("formCode"));
            return null;
        }

        LocalDateTime sendTime = sevenAmOf(planTime);

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName(metric.name)
                .vitalsignType(metric.type)
                .unit(unit)
                .build();
        metric.valueSetter.accept(payload, value);

        fillCommonFields(payload, patient, sendTime, traceId);
        payload.setRecordTime(sendTime);
        payload.setSeries("1");
        payload.setWardCode("125011");
        payload.setRemark("");
        payload.setIsValid(1);
        applyNurse(payload, nurse, pid, traceId);

        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} pid={} {}={} unit={} planTime={}",
                traceId, pid, metric.name, value, unit, sendTime);
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

    private Document findFormData(String pid, String traceId) {
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("formCode").in(FORM_CODES))
                .with(Sort.by(Sort.Direction.DESC, "createTime"))
                .limit(1);
        return mongoTemplate.findOne(query, Document.class, "dFormData");
    }

    /**
     * 取 planTime 当天 07:00 作为身高体重的发送时间
     */
    private LocalDateTime sevenAmOf(LocalDateTime planTime) {
        return LocalDateTime.of(planTime.toLocalDate(), LocalTime.of(7, 0));
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
