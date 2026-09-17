package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.handler.*;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import com.digixmed.cloud.icu.service.IntermediateService;
import com.digixmed.cloud.icu.util.PayloadTimeNormalizer;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 出科患者回传任务
 *
 * 业务目的：患者出科时，以出科时间点回传该时间点的生命体征数据
 * 触发方式：定时任务，每5分钟执行一次
 * 数据范围：只处理最近24小时内出科的患者
 *
 * 处理逻辑：
 *   1. 查询最近24小时内出科的患者
 *   2. 对每个出科患者，获取出科时间
 *   3. 查询出科时间点附近（出科时间前1小时到出科时间）的生命体征数据
 *   4. 以出科时间作为planTime回传
 */
@Component
public class DischargePatientTask {

    private static final Logger log = LoggerFactory.getLogger(DischargePatientTask.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Value("${vitalsign.patient.ward-code:125011}")
    private String wardCode;

    @Value("${vitalsign.auto-enabled:false}")
    private boolean autoEnabled;

    /** 出科回传回看小时数 */
    @Value("${vitalsign.discharge.lookback-hours:24}")
    private int dischargeLookbackHours;

    /** 生命体征来源code */
    private static final String CODE_TEMPERATURE = "param_T";
    private static final String CODE_PULSE = "param_脉搏";
    private static final String CODE_HEART_RATE = "param_HR";
    private static final String CODE_BREATH = "param_resp";
    private static final String CODE_PAIN = "param_tengTong_score";

    @Autowired
    private ClinicalTimeWindowService timeWindowService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private IntermediateService intermediateService;

    @Autowired
    private TemperatureHandler temperatureHandler;
    @Autowired
    private PulseHandler pulseHandler;
    @Autowired
    private HeartRateHandler heartRateHandler;
    @Autowired
    private BreathHandler breathHandler;
    @Autowired
    private PainScoreHandler painScoreHandler;

    @Autowired
    private PushTask pushTask;

    /**
     * 定时执行出科患者回传
     * 每5分钟执行一次
     */
    @Scheduled(cron = "${vitalsign.discharge.cron:0 */5 * * * ?}")
    public void execute() {
        if (!autoEnabled) {
            log.debug("DISCHARGE_SKIPPED autoEnabled=false, skip");
            return;
        }

        String traceId = TraceIdGenerator.generate();
        LocalDateTime now = timeWindowService.now();
        log.info("DISCHARGE_TRIGGERED traceId={} 开始出科患者回传 now={}", traceId, now);

        try {
            // 查询最近24小时内出科的患者（使用 MongoDB patient 的 status、icuDischargeTime、dischargedType）
            // 只处理转出患者，其他出科类型（如死亡）不需要回传生命体征
            LocalDateTime since = now.minusHours(dischargeLookbackHours);
            Date sinceDate = Date.from(since.atZone(ZONE).toInstant());
            Query query = new Query(Criteria.where("status").is("discharged")
                    .and("deptCode").is(wardCode)
                    .and("dischargedType").is("转出")
                    .and("icuDischargeTime").gte(sinceDate));
            List<Document> dischargedPatients = mongoTemplate.find(query, Document.class, "patient");
            log.info("DISCHARGE_PATIENTS traceId={} 出科患者数量={} since={}", traceId, dischargedPatients.size(), since);

            if (dischargedPatients.isEmpty()) {
                log.info("DISCHARGE_COMPLETE traceId={} 无出科患者，跳过", traceId);
                return;
            }

            int totalInserted = 0;
            int totalSkipped = 0;

            for (Document patientDoc : dischargedPatients) {
                try {
                    Map<String, Object> result = processDischargedPatient(patientDoc, traceId);
                    totalInserted += (int) result.getOrDefault("inserted", 0);
                    totalSkipped += (int) result.getOrDefault("skipped", 0);
                } catch (Exception e) {
                    String pid = patientDoc != null ? String.valueOf(patientDoc.get("_id")) : "unknown";
                    log.error("DISCHARGE_PATIENT_ERROR traceId={} pid={} 处理异常", traceId, pid, e);
                }
            }

            log.info("DISCHARGE_COMPLETE traceId={} 出科回传完成 inserted={} skipped={}",
                    traceId, totalInserted, totalSkipped);

            // 回传完成后立即推送
            try {
                pushTask.pushOnce(traceId);
            } catch (Exception pe) {
                log.error("DISCHARGE_PUSH traceId={} 出科回传后推送异常", traceId, pe);
            }
        } catch (Exception e) {
            log.error("DISCHARGE_ERROR traceId={} 出科患者回传异常", traceId, e);
        }
    }

    /**
     * 处理单个出科患者
     *
     * @param patientDoc MongoDB patient 文档
     * @param traceId    追踪ID
     * @return 处理结果（inserted, skipped）
     */
    private Map<String, Object> processDischargedPatient(Document patientDoc, String traceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        int inserted = 0;
        int skipped = 0;

        String pid = patientDoc.get("_id").toString();
        Date icuDischargeDate = patientDoc.get("icuDischargeTime") instanceof Date
                ? (Date) patientDoc.get("icuDischargeTime") : null;

        if (icuDischargeDate == null) {
            log.warn("DISCHARGE_NO_TIME traceId={} pid={} 无出科时间，跳过", traceId, pid);
            result.put("inserted", 0);
            result.put("skipped", 0);
            return result;
        }

        LocalDateTime dischargeTime = icuDischargeDate.toInstant()
                .atZone(ZONE).toLocalDateTime();

        // 确定出科时间对应的标准时间点
        // 标准时间点：02:00, 06:00, 10:00, 14:00, 18:00, 22:00
        int dischargeHour = dischargeTime.getHour();
        int standardHour = findStandardHour(dischargeHour);
        LocalDateTime standardTime = dischargeTime.withHour(standardHour).withMinute(0).withSecond(0).withNano(0);

        // 查询窗口：[标准时间点, 出科时间)
        Date startDate = Date.from(standardTime.atZone(ZONE).toInstant());
        Date endDate = Date.from(dischargeTime.atZone(ZONE).toInstant());

        log.info("DISCHARGE_PROCESS traceId={} pid={} dischargeTime={} standardHour={} window=[{}, {})",
                traceId, pid, dischargeTime, standardHour, standardTime, dischargeTime);

        // 查询窗口内的生命体征数据
        List<String> vitalCodes = Arrays.asList(
                CODE_TEMPERATURE, CODE_PULSE, CODE_HEART_RATE, CODE_BREATH);

        for (String code : vitalCodes) {
            try {
                Query query = new Query(Criteria.where("pid").is(pid)
                        .and("code").is(code)
                        .and("valid").ne(false).ne(0)
                        .and("time").gte(startDate).lt(endDate))
                        .with(Sort.by(Sort.Direction.DESC, "time"))
                        .limit(1);
                Document record = mongoTemplate.findOne(query, Document.class, "bedside");

                if (record != null) {
                    VitalSignPayload payload = buildPayload(code, record, patientDoc, dischargeTime, traceId);
                    if (payload != null) {
                        PayloadTimeNormalizer.anchor(payload, dischargeTime);
                        Map<String, Object> upsertResult = intermediateService.upsertPending(payload, traceId);
                        String action = upsertResult != null ? String.valueOf(upsertResult.get("action")) : "UNKNOWN";
                        if ("INSERT".equals(action)) {
                            inserted++;
                        } else if ("SKIP".equals(action)) {
                            skipped++;
                        }
                        log.info("DISCHARGE_RECORD traceId={} pid={} code={} dischargeTime={} standardHour={} value={} action={}",
                                traceId, pid, code, dischargeTime, standardHour,
                                payload.getVitalsignNVal1() != null ? payload.getVitalsignNVal1() : payload.getVitalsignSVal1(),
                                action);
                    }
                } else {
                    log.info("DISCHARGE_NO_RECORD traceId={} pid={} code={} 窗口[{}, {})内无数据",
                            traceId, pid, code, standardTime, dischargeTime);
                }
            } catch (Exception e) {
                log.error("DISCHARGE_RECORD_ERROR traceId={} pid={} code={} 处理异常", traceId, pid, code, e);
            }
        }

        // 疼痛评分：查 (前一标准时间点, 出科时间] 范围内最新一条
        try {
            LocalDateTime prevHour = previousStandardHour(standardTime);
            Date painStartDate = Date.from(prevHour.atZone(ZONE).toInstant());
            Query painQuery = new Query(Criteria.where("pid").is(pid)
                    .and("code").is(CODE_PAIN)
                    .and("valid").ne(false).ne(0)
                    .and("time").gt(painStartDate).lte(endDate))
                    .with(Sort.by(Sort.Direction.DESC, "time"))
                    .limit(1);
            Document painRecord = mongoTemplate.findOne(painQuery, Document.class, "bedside");
            if (painRecord != null) {
                VitalSignPayload payload = painScoreHandler.handle(painRecord, patientDoc, dischargeTime, traceId);
                if (payload != null) {
                    PayloadTimeNormalizer.anchor(payload, dischargeTime);
                    intermediateService.upsertPending(payload, traceId);
                }
            }
        } catch (Exception e) {
            log.error("DISCHARGE_PAIN_ERROR traceId={} pid={} 处理异常", traceId, pid, e);
        }

        result.put("inserted", inserted);
        result.put("skipped", skipped);
        return result;
    }

    /**
     * 根据出科时间的小时数，找到对应的标准时间点
     *
     * 标准时间点：02:00, 06:00, 10:00, 14:00, 18:00, 22:00
     * 规则：找到出科时间之前最近的标准时间点
     *
     * 示例：
     *   出科时间 01:30 → 标准时间点 00:00（前一天22:00）
     *   出科时间 03:00 → 标准时间点 02:00
     *   出科时间 18:30 → 标准时间点 18:00
     *   出科时间 23:00 → 标准时间点 22:00
     */
    private int findStandardHour(int dischargeHour) {
        // 标准时间点列表（升序）
        int[] standardHours = {2, 6, 10, 14, 18, 22};

        // 找到出科时间之前最近的标准时间点
        for (int i = standardHours.length - 1; i >= 0; i--) {
            if (dischargeHour >= standardHours[i]) {
                return standardHours[i];
            }
        }

        // 出科时间在02:00之前，使用前一天的22:00
        return 22;
    }

    /**
     * 获取前一个标准时间点
     *
     * 02→前一天22, 06→02, 10→06, 14→10, 18→14, 22→18
     */
    private LocalDateTime previousStandardHour(LocalDateTime point) {
        int hour = point.getHour();
        switch (hour) {
            case 2:  return point.minusDays(1).withHour(22).withMinute(0).withSecond(0).withNano(0);
            case 6:  return point.withHour(2).withMinute(0).withSecond(0).withNano(0);
            case 10: return point.withHour(6).withMinute(0).withSecond(0).withNano(0);
            case 14: return point.withHour(10).withMinute(0).withSecond(0).withNano(0);
            case 18: return point.withHour(14).withMinute(0).withSecond(0).withNano(0);
            case 22: return point.withHour(18).withMinute(0).withSecond(0).withNano(0);
            default: return point.withMinute(0).withSecond(0).withNano(0).minusHours(4);
        }
    }

    /**
     * 根据code构建VitalSignPayload
     */
    private VitalSignPayload buildPayload(String code, Document record, Document patient,
                                           LocalDateTime planTime, String traceId) {
        switch (code) {
            case CODE_TEMPERATURE:
                return temperatureHandler.handle(record, patient, planTime, traceId);
            case CODE_PULSE:
                return pulseHandler.handle(record, patient, planTime, traceId);
            case CODE_HEART_RATE:
                return heartRateHandler.handle(record, patient, planTime, traceId);
            case CODE_BREATH:
                return breathHandler.handle(record, patient, planTime, traceId);
            case CODE_PAIN:
                return painScoreHandler.handle(record, patient, planTime, traceId);
            default:
                return null;
        }
    }
}
