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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 血压处理器
 *
 * 业务目的：血压只回传每天 07:00 槽位的数据，收缩压与舒张压必须成对
 * 源数据：param_nibp_s.strVal（收缩压）, param_nibp_d.strVal（舒张压）
 * 输出：vitalsignName=血压, vitalsignType=1005, unit=mmHg
 *
 * 时间窗口：由调用方传入的 planTime 直接决定，窗口 = [planTime, planTime+1小时)，
 *          planTime 固定为某一天的 07:00。
 *          原实现在 handler 内部用 "planTime.getHour() < 7 就查前一天" 自行推断日期，
 *          与 VitalSignScanTask 里的同款逻辑重复且容易不一致，现统一由任务层决定槽位日期。
 *
 * 补录支持：护士忙完后补写 07:00 那一格，bedside.time 仍是 07:00，
 *          由任务层周期性重扫该槽位即可命中，幂等键不变。
 *
 * 规则：
 *   - 只存在收缩压或只存在舒张压 → 不回传，记录WARN，reasonCode=INCOMPLETE_BLOOD_PRESSURE
 *   - 等另一半补齐后重扫会自动回传
 */
@Component
public class BloodPressureHandler extends BaseVitalSignHandler {

    private static final String SYSTOLIC_CODE = "param_nibp_s";
    private static final String DIASTOLIC_CODE = "param_nibp_d";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

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
        if (planTime == null) {
            log.warn("STEP_04 traceId={} pid={} 血压planTime为空", traceId, maskPid(pid));
            return null;
        }

        // 窗口固定为 [planTime, planTime+1h)，planTime 由任务层给定为某天的 07:00
        ClinicalTimeWindow window = timeWindowService.buildSevenAmWindow(planTime.toLocalDate());
        Date startTime = Date.from(window.getStart().atZone(ZONE).toInstant());
        Date endTime = Date.from(window.getEnd().atZone(ZONE).toInstant());

        Document systolicDoc = findRecordByCode(pid, startTime, endTime, SYSTOLIC_CODE);
        Document diastolicDoc = findRecordByCode(pid, startTime, endTime, DIASTOLIC_CODE);

        log.info("STEP_04_BP_QUERY traceId={} pid={} 查询血压窗口=[{}, {}) systolic={} diastolic={}",
                traceId, maskPid(pid), window.getStart(), window.getEnd(),
                systolicDoc != null ? "found" : "null", diastolicDoc != null ? "found" : "null");

        String systolic = systolicDoc != null ? getValueFromDocByKey(systolicDoc, "strVal", String.class) : null;
        String diastolic = diastolicDoc != null ? getValueFromDocByKey(diastolicDoc, "strVal", String.class) : null;

        BloodPressurePair pair = BloodPressurePair.builder()
                .systolic(systolic)
                .diastolic(diastolic)
                .systolicRecord(systolicDoc)
                .diastolicRecord(diastolicDoc)
                .build();

        if (!pair.isComplete()) {
            log.warn("STEP_05_VALUE_PARSED traceId={} pid={} 不完整血压: systolic={}, diastolic={} reasonCode=INCOMPLETE_BLOOD_PRESSURE",
                    traceId, maskPid(pid), systolic, diastolic);
            return null;
        }

        if (!pair.isValid()) {
            log.warn("STEP_05_VALUE_PARSED traceId={} pid={} 无效血压值: {}/{} reasonCode=INVALID_BLOOD_PRESSURE",
                    traceId, maskPid(pid), systolic, diastolic);
            return null;
        }

        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} pid={} 血压={}/{}",
                traceId, maskPid(pid), systolic, diastolic);

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("血压")
                .vitalsignType("1005")
                .vitalsignNVal1(pair.getSystolic())
                .vitalsignNVal2(pair.getDiastolic())
                .unit("mmHg")
                .build();

        // recordTime 取收缩压记录的 bedside.time；planTime 由任务层用 PayloadTimeNormalizer 锚定为 07:00
        fillCommonFields(payload, patient, systolicDoc, mongoTemplate, traceId);
        return payload;
    }

    /**
     * 按code查询指定时间窗口内的记录
     * valid ne false：兼容历史数据缺失 valid 字段的情况，同时排除已作废记录
     */
    private Document findRecordByCode(String pid, Date startTime, Date endTime, String code) {
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(code)
                .and("valid").ne(false)
                .and("time").gte(startTime).lt(endTime)
        ).with(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "editTime"))
                .limit(1);

        return mongoTemplate.findOne(query, Document.class, "bedside");
    }

    private String maskPid(String pid) {
        if (pid == null || pid.length() <= 4) return "****";
        return pid.substring(0, 2) + "****" + pid.substring(pid.length() - 2);
    }
}
