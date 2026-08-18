package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.InpatientDTO;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.repository.InpatientRepository;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import com.digixmed.cloud.icu.service.HeightWeightNurseService;
import com.digixmed.cloud.icu.service.HeightWeightNurseService.NurseRef;
import com.digixmed.cloud.icu.util.PayloadTimeNormalizer;
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
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * 身高体重处理器
 *
 * 源数据：Mongo dFormData 中的 fieldDataList 数组
 * 输出：
 *   - 身高：vitalsignType=1013, unit=cm, 值放 vitalsignSVal1
 *   - 体重：vitalsignType=1014, unit=kg, 值放 vitalsignSVal1
 *
 * 回传时机：
 *   - 入科当天（pageDayIndex=0）：由 VitalSignScanTask 的入科扫描负责，
 *     planTime=recordTime=icuAdmissionTime，与入科第一条生命体征一起回传；
 *   - 之后：pageDayIndex > 0 且 % 7 == 0，由 DailySummaryTask 在 reportDate 07:00 回传。
 *
 * 记录者：一律取 HeightWeightNurseService 锁定的记录者，
 *        即"入科第一条回传时对应体温的记录者"，之后永不变化。
 *
 * 入科日期基准：以 KingBase inpatients.admission_ward_time 为唯一基准，
 *   不再以 icuAdmissionTime 同天判断替代。
 *
 * 字段优先级：身高 sg；体重 tz
 */
@Component
public class HeightWeightHandler extends BaseVitalSignHandler {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 身高体重差异项
     *
     * R1: 身高(1013) 和 体重(1014) 值均放 vitalsignSVal1
     */
    private enum Metric {
        HEIGHT("身高", "1013", "cm", Collections.singletonList("sg"),
                VitalSignPayload::setVitalsignSVal1),
        WEIGHT("体重", "1014", "kg", Collections.singletonList("tz"),
                VitalSignPayload::setVitalsignSVal1);

        private final String name;
        private final String type;
        private final String unit;
        private final List<String> sourceFields;
        private final BiConsumer<VitalSignPayload, String> valueSetter;

        Metric(String name, String type, String unit, List<String> sourceFields,
               BiConsumer<VitalSignPayload, String> valueSetter) {
            this.name = name;
            this.type = type;
            this.unit = unit;
            this.sourceFields = sourceFields;
            this.valueSetter = valueSetter;
        }
    }

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
        // 身高体重不走 bedside，统一由 buildAdmissionFirst / buildPeriodic 构建
        return null;
    }

    // ======================== 新公开 API ========================

    /**
     * 入科首次构建（planTime=recordTime=icuAdmissionTime + 1小时）
     *
     * @param patient           MongoDB patient 文档
     * @param admissionDateTime icuAdmissionTime 原始值（不可变时间点）
     * @param nurse             锁定的记录者（由调用方传入）
     * @param traceId           追踪ID
     * @return 身高和体重 payload 列表（可能只有一条或零条）
     */
    public List<VitalSignPayload> buildAdmissionFirst(Document patient, LocalDateTime admissionDateTime,
                                                      NurseRef nurse, String traceId) {
        String pid = readPid(patient);
        if (pid == null) {
            return Collections.emptyList();
        }

        Document formData = findFormData(pid, traceId);
        if (formData == null) {
            log.warn("ADMISSION_HW traceId={} pid={} 未找到入院/转入护理评估单，身高体重不回传", traceId, pid);
            return Collections.emptyList();
        }

        // 入科第一条：planTime=recordTime=icuAdmissionTime + 1小时
        LocalDateTime sendTime = admissionDateTime.plusHours(1);
        return buildPair(patient, pid, formData, nurse, traceId, timeNormalizer -> {
            return sendTime;
        }, sendTime);
    }

    /**
     * 周期性构建（7天周期，sendTime 由 planFor 计算）
     *
     * @param pid               MongoDB patient _id
     * @param admissionWardTime admission_ward_time（LocalDateTime）
     * @param sendTime          planFor 计划的发送时间（reportDate 07:00）
     * @param traceId           追踪ID
     * @return 身高和体重 payload 列表
     */
    public List<VitalSignPayload> buildPeriodic(String pid, LocalDateTime admissionWardTime,
                                                LocalDateTime sendTime, String traceId) {
        Document patient = findPatientById(pid, traceId);
        if (patient == null) {
            return Collections.emptyList();
        }

        Document formData = findFormData(pid, traceId);
        if (formData == null) {
            log.warn("PERIODIC_HW traceId={} pid={} 未找到入院/转入护理评估单，身高体重不回传", traceId, pid);
            return Collections.emptyList();
        }

        NurseRef nurse = heightWeightNurseService.resolve(pid);

        return buildPair(patient, pid, formData, nurse, traceId, timeNormalizer -> {
            // 周期模式：用 PayloadTimeNormalizer 归一化到 sendTime
            return sendTime;
        }, sendTime);
    }

    /**
     * 计划发送时间（委托 ClinicalTimeWindowService）
     *
     * @param admissionWardTime admission_ward_time
     * @param reportDate        报表日期
     * @param now               当前时间
     * @return 发送时间（仅在应该发送时返回）
     */
    public Optional<LocalDateTime> planFor(LocalDateTime admissionWardTime, LocalDate reportDate, LocalDateTime now) {
        return timeWindowService.resolveHeightWeightSendTime(admissionWardTime, reportDate, now);
    }

    /**
     * 是否应该发送（兼容旧签名，委托 planFor）
     */
    public boolean shouldSendHeightWeight(String patientId, LocalDate reportDate, Document patient) {
        // 取 admission_ward_time
        LocalDateTime admissionWardTime = resolveAdmissionWardTime(patientId, patient);
        if (admissionWardTime == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(ZONE);
        return planFor(admissionWardTime, reportDate, now).isPresent();
    }

    /**
     * 获取入科日期（供外部调用，如 DailySummaryTask 需要 admissionWardTime）
     *
     * @param patientId 住院号（patient.mrn）
     * @param patient   MongoDB patient 文档
     * @return admission_ward_time（LocalDateTime），查不到返回 null
     */
    public LocalDateTime getAdmissionWardTime(String patientId, Document patient) {
        return resolveAdmissionWardTime(patientId, patient);
    }

    // ======================== 内部方法 ========================

    /**
     * 共享构建逻辑：查 dFormData 一次，构建身高和体重两条 payload
     */
    private List<VitalSignPayload> buildPair(Document patient, String pid, Document formData,
                                              NurseRef nurse, String traceId,
                                              java.util.function.Function<Void, LocalDateTime> timeResolver,
                                              LocalDateTime anchorTime) {
        List<VitalSignPayload> result = new java.util.ArrayList<>();

        for (Metric metric : Metric.values()) {
            String value = extractFieldFromFormData(formData, metric.sourceFields);
            if (value == null) {
                log.warn("HW_VALUE traceId={} pid={} 评估单无{}字段或值为空 formCode={}",
                        traceId, pid, metric.sourceFields, formData.getString("formCode"));
                continue;
            }

            LocalDateTime sendTime = timeResolver.apply(null);

            VitalSignPayload payload = VitalSignPayload.builder()
                    .vitalsignName(metric.name)
                    .vitalsignType(metric.type)
                    .unit(metric.unit)
                    .build();
            metric.valueSetter.accept(payload, value);

            fillCommonFields(payload, patient, sendTime, traceId);
            payload.setRecordTime(sendTime);
            payload.setSeries("1");
            payload.setWardCode("125011");
            payload.setRemark("");
            payload.setIsValid(1);
            applyNurse(payload, nurse, pid, traceId);

            log.info("HW_BUILT traceId={} pid={} {}={} unit={} planTime={}",
                    traceId, pid, metric.name, value, metric.unit, sendTime);
            result.add(payload);
        }

        return result;
    }

    /**
     * 写入锁定的记录者；nurse 为空时退化为按 pid 解析
     */
    private void applyNurse(VitalSignPayload payload, NurseRef nurse, String pid, String traceId) {
        NurseRef effective = (nurse != null) ? nurse : heightWeightNurseService.resolve(pid);
        payload.setRecordNurseId(effective.getId());
        payload.setRecordNurseName(effective.getName());
        log.info("HW_NURSE traceId={} pid={} 记录者={} pinned={}",
                traceId, pid, effective.getName(), effective.isPinned());
    }

    /**
     * 入科日期基准解析（R3: 以 admission_ward_time 为唯一基准）
     *
     * 不再有 icuAdmissionTime 同天快捷分支。
     * 先查 KingBase admission_ward_time；查不到返回 null。
     */
    private LocalDateTime resolveAdmissionWardTime(String patientId, Document patient) {
        if (patientId == null || patientId.trim().isEmpty()) {
            log.warn("未获取到admission_ward_time：patient.mrn 为空，跳过身高体重回传");
            return null;
        }

        try {
            InpatientDTO inpatient = inpatientRepository.findByPatientId(patientId);
            if (inpatient != null && inpatient.getAdmissionWardTime() != null) {
                log.info("从KingBase获取admission_ward_time成功 patientId={} admissionWardTime={}",
                        maskPatientId(patientId), inpatient.getAdmissionWardTime());
                return inpatient.getAdmissionWardTime();
            }
        } catch (Exception e) {
            log.warn("查询金仓入科时间失败 patientId={}: {}", maskPatientId(patientId), e.getMessage());
        }

        log.warn("未获取到admission_ward_time，跳过身高体重回传 patientId={}", maskPatientId(patientId));
        return null;
    }

    private Document findPatientById(String pid, String traceId) {
        try {
            Query query = new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(pid)));
            return mongoTemplate.findOne(query, Document.class, "patient");
        } catch (Exception e) {
            log.warn("HW_FIND_PATIENT traceId={} pid={} 查询失败: {}", traceId, pid, e.getMessage());
            return null;
        }
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
