package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.BloodPressurePair;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 血压处理器
 *
 * 业务目的：处理血压体征，显式成对处理收缩压和舒张压
 * 源数据：param_nibp_s.strVal（收缩压）, param_nibp_d.strVal（舒张压）
 * 输出：vitalsignName=血压, vitalsignType=1005, unit=mmHg
 *
 * 规则：
 *   - 同一患者、同一标准时间点必须同时存在收缩压和舒张压
 *   - 如果只存在一个值：不发送不完整血压，记录WARN
 *   - reasonCode=INCOMPLETE_BLOOD_PRESSURE
 */
@Component
public class BloodPressureHandler extends BaseVitalSignHandler {

    private static final String SYSTOLIC_CODE = "param_nibp_s";
    private static final String DIASTOLIC_CODE = "param_nibp_d";

    @Autowired
    private MongoTemplate mongoTemplate;

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

        // 查询收缩压
        Document systolicDoc = findRecordByCode(pid, planTime, SYSTOLIC_CODE);
        // 查询舒张压
        Document diastolicDoc = findRecordByCode(pid, planTime, DIASTOLIC_CODE);

        String systolic = systolicDoc != null ? getValueFromDocByKey(systolicDoc, "strVal", String.class) : null;
        String diastolic = diastolicDoc != null ? getValueFromDocByKey(diastolicDoc, "strVal", String.class) : null;

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

        fillCommonFields(payload, patient, planTime, traceId);
        return payload;
    }

    /**
     * 按code查询指定时间点的记录
     */
    private Document findRecordByCode(String pid, LocalDateTime planTime, String code) {
        Date time = Date.from(planTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(code)
                .and("time").is(time)
                .and("valid").is(true));

        return mongoTemplate.findOne(query, Document.class, "bedside");
    }

    private String maskPid(String pid) {
        if (pid == null || pid.length() <= 4) return "****";
        return pid.substring(0, 2) + "****" + pid.substring(pid.length() - 2);
    }
}
