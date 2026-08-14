package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.BloodPressurePair;
import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 血压处理器
 *
 * 业务目的：处理血压体征，显式成对处理收缩压和舒张压
 * 源数据：param_nibp_s.strVal（收缩压）, param_nibp_d.strVal（舒张压）
 * 输出：vitalsignName=血压, vitalsignType=1005, unit=mmHg
 *
 * 规则：
 *   - 同一患者、同一业务时间窗口必须同时存在收缩压和舒张压
 *   - 如果只存在一个值：不发送不完整血压，记录WARN
 *   - reasonCode=INCOMPLETE_BLOOD_PRESSURE
 */
@Component
public class BloodPressureHandler extends BaseVitalSignHandler {

    private static final String SYSTOLIC_CODE = "param_nibp_s";
    private static final String DIASTOLIC_CODE = "param_nibp_d";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ClinicalTimeWindowService timeWindowService;

    public BloodPressureHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        String pid = getValueFromDocByKey(patient, "_id", Object.class) != null
                ? getValueFromDocByKey(patient, "_id", Object.class).toString() : null;
        if (pid == null) {
            log.warn("STEP_04 traceId={} 患者pid为空", traceId);
            return null;
        }

        // 血压只传每天7点的数据
        // 构建7点的时间窗口: [07:00, 08:00)
        LocalDate today = planTime.toLocalDate();
        Date startTime = Date.from(today.atTime(7, 0, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Date endTime = Date.from(today.atTime(8, 0, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());

        // 如果当前时间早于7点，则查询前一天7点的数据
        if (planTime.getHour() < 7) {
            LocalDate yesterday = today.minusDays(1);
            startTime = Date.from(yesterday.atTime(7, 0, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            endTime = Date.from(yesterday.atTime(8, 0, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        }

        // 查询收缩压
        Document systolicDoc = findRecordByCode(pid, startTime, endTime, SYSTOLIC_CODE);
        // 查询舒张压
        Document diastolicDoc = findRecordByCode(pid, startTime, endTime, DIASTOLIC_CODE);

        log.info("STEP_04_BP_QUERY traceId={} pid={} 查询血压: startTime={}, endTime={}, systolicDoc={}, diastolicDoc={}",
                traceId, maskPid(pid), startTime, endTime, systolicDoc != null ? "found" : "null", diastolicDoc != null ? "found" : "null");

        String systolic = systolicDoc != null ? getValueFromDocByKey(systolicDoc, "strVal", String.class) : null;
        String diastolic = diastolicDoc != null ? getValueFromDocByKey(diastolicDoc, "strVal", String.class) : null;

        log.info("STEP_04_BP_VALUES traceId={} pid={} 收缩压={}, 舒张压={}", traceId, maskPid(pid), systolic, diastolic);

        BloodPressurePair pair = BloodPressurePair.builder()
                .systolic(systolic)
                .diastolic(diastolic)
                .systolicRecord(systolicDoc)
                .diastolicRecord(diastolicDoc)
                .build();

        // 检查完整性
        if (!pair.isComplete()) {
            log.warn("STEP_05_VALUE_PARSED traceId={} pid={} 不完整血压: systolic={}, diastolic={} reasonCode=INCOMPLETE_BLOOD_PRESSURE",
                    traceId, maskPid(pid), systolic, diastolic);
            return null;
        }

        // 检查有效性
        if (!pair.isValid()) {
            log.warn("STEP_05_VALUE_PARSED traceId={} pid={} 无效血压值: {}/{} reasonCode=INVALID_BLOOD_PRESSURE",
                    traceId, maskPid(pid), systolic, diastolic);
            return null;
        }

        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} pid={} 血压={}/{}", traceId, maskPid(pid), systolic, diastolic);

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("血压")
                .vitalsignType("1005")
                .vitalsignNVal1(pair.getSystolic())
                .vitalsignNVal2(pair.getDiastolic())
                .unit("mmHg")
                .build();

        // 使用收缩压记录的 bedside.time
        fillCommonFields(payload, patient, systolicDoc, mongoTemplate, traceId);
        return payload;
    }

    /**
     * 按code查询指定时间窗口内的记录
     * 使用业务时间窗口而非精确时间匹配
     */
    private Document findRecordByCode(String pid, Date startTime, Date endTime, String code) {
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(code)
                .and("time").gte(startTime).lt(endTime)
        ).with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "editTime"))
                .limit(1);

        return mongoTemplate.findOne(query, Document.class, "bedside");
    }

    private String maskPid(String pid) {
        if (pid == null || pid.length() <= 4) return "****";
        return pid.substring(0, 2) + "****" + pid.substring(pid.length() - 2);
    }
}
