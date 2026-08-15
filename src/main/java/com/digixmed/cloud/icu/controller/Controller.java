package com.digixmed.cloud.icu.controller;

import com.digixmed.cloud.icu.handler.*;
import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import com.digixmed.cloud.icu.service.IntermediateService;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 体温单接口控制器（新链路）
 */
@RestController
@Api(value = "体温单接口", tags = {"体温单接口"})
public class Controller {

    private static final Logger log = LoggerFactory.getLogger(Controller.class);

    /** 普通体征标准时间点（小时） */
    private static final List<Integer> VITAL_SIGN_HOURS = Arrays.asList(2, 6, 10, 14, 18, 22);

    @Autowired
    private IntermediateService intermediateService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ClinicalTimeWindowService timeWindowService;

    @Autowired
    private PatientIdentityMapper patientIdentityMapper;

    // 普通体征Handlers
    @Autowired
    private TemperatureHandler temperatureHandler;
    @Autowired
    private PulseHandler pulseHandler;
    @Autowired
    private HeartRateHandler heartRateHandler;
    @Autowired
    private BreathHandler breathHandler;
    @Autowired
    private BloodPressureHandler bloodPressureHandler;
    @Autowired
    private PainScoreHandler painScoreHandler;

    // 每日汇总Handlers
    @Autowired
    private StoolCountHandler stoolCountHandler;
    @Autowired
    private UrineOutputHandler urineOutputHandler;
    @Autowired
    private OralIntakeHandler oralIntakeHandler;
    @Autowired
    private TherapyInputHandler therapyInputHandler;
    @Autowired
    private TotalInputHandler totalInputHandler;
    @Autowired
    private TotalOutputHandler totalOutputHandler;
    @Autowired
    private DrainageOutputHandler drainageOutputHandler;
    @Autowired
    private GastricDrainageHandler gastricDrainageHandler;
    @Autowired
    private OtherDrainageHandler otherDrainageHandler;
    @Autowired
    private NetUltrafiltrationHandler netUltrafiltrationHandler;
    @Autowired
    private HeightWeightHandler heightWeightHandler;

    @GetMapping("/health")
    @ApiOperation(value = "健康检查", notes = "服务健康检查接口")
    public String health() {
        return "OK";
    }

    @GetMapping("/queue/stats")
    @ApiOperation(value = "队列统计", notes = "查看推送队列状态统计")
    public Map<String, Object> queueStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        String[] statuses = {"PENDING", "SENDING", "SUCCESS", "RETRY", "DEAD"};
        long total = 0;
        for (String status : statuses) {
            Query query = new Query(Criteria.where("status").is(status));
            long count = mongoTemplate.count(query, "vitalsign_push_queue");
            stats.put(status, count);
            total += count;
        }
        stats.put("total", total);
        return stats;
    }

    /**
     * 手动触发指定患者的体征数据扫描和回传（含出入量）
     *
     * @param mrn       住院号（patient.mrn）
     * @param startDate 开始日期（包含）
     * @param endDate   结束日期（包含）
     * @return 扫描结果
     */
    @PostMapping("/scan/patient")
    @ApiOperation(value = "扫描指定患者全部数据", notes = "根据住院号和日期范围扫描并回传体征+出入量数据")
    public Map<String, Object> scanPatient(
            @ApiParam(value = "住院号", required = true, example = "0126070178")
            @RequestParam String mrn,
            @ApiParam(value = "开始日期", required = true, example = "2026-08-01")
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @ApiParam(value = "结束日期", required = true, example = "2026-08-14")
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate) {

        String traceId = TraceIdGenerator.generate();
        log.info("MANUAL_SCAN traceId={} mrn={} startDate={} endDate={}", traceId, mrn, startDate, endDate);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("mrn", mrn);
        result.put("startDate", startDate.toString());
        result.put("endDate", endDate.toString());

        // 1. 查找患者
        Query patientQuery = new Query(new Criteria().orOperator(
                Criteria.where("mrn").is(mrn),
                Criteria.where("hisPid").is(mrn)
        ));
        Document patient = mongoTemplate.findOne(patientQuery, Document.class, "patient");

        if (patient == null) {
            result.put("success", false);
            result.put("message", "未找到患者，mrn=" + mrn);
            return result;
        }

        String pid = patient.get("_id").toString();
        String patientName = patient.getString("name");
        result.put("pid", pid);
        result.put("patientName", patientName);

        List<Map<String, Object>> details = new ArrayList<>();
        int totalInserted = 0;
        int totalSkipped = 0;
        int totalFailed = 0;

        // ========== 2. 普通体征（体温、脉搏、心率、呼吸、疼痛评分）==========
        LocalDate currentDate = startDate;
        while (!currentDate.isAfter(endDate)) {
            for (int hour : VITAL_SIGN_HOURS) {
                LocalDateTime planTime = LocalDateTime.of(currentDate, LocalTime.of(hour, 0, 0));
                ClinicalTimeWindow window = timeWindowService.buildVitalPointWindow(currentDate, hour);

                Map<String, Object> pointResult = processTimePoint(pid, patient, planTime, window, traceId);
                pointResult.put("date", currentDate.toString());
                pointResult.put("hour", hour);
                pointResult.put("type", "普通体征");
                details.add(pointResult);

                totalInserted += (int) pointResult.getOrDefault("inserted", 0);
                totalSkipped += (int) pointResult.getOrDefault("skipped", 0);
                totalFailed += (int) pointResult.getOrDefault("failed", 0);
            }
            currentDate = currentDate.plusDays(1);
        }

        // ========== 2.1 入科时间点扫描（入科当天，生命体征+身高体重）==========
        Date icuAdmissionTime = getValueFromDocByKey(patient, "icuAdmissionTime", Date.class);
        if (icuAdmissionTime != null) {
            LocalDateTime admissionDateTime = icuAdmissionTime.toInstant()
                    .atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
            LocalDate admissionDate = admissionDateTime.toLocalDate();

            // 只在日期范围内且为入科当天时扫描
            if (!admissionDate.isBefore(startDate) && !admissionDate.isAfter(endDate)) {
                int admissionHour = admissionDateTime.getHour();
                // 找到入科时间所在的标准时间点
                int vitalHour = VITAL_SIGN_HOURS.stream()
                        .filter(h -> h <= admissionHour)
                        .max(Integer::compareTo)
                        .orElse(2);

                LocalDateTime admissionPlanTime = LocalDateTime.of(admissionDate, LocalTime.of(vitalHour, 0, 0));
                ClinicalTimeWindow admissionWindow = timeWindowService.buildVitalPointWindow(admissionDate, vitalHour);

                // 生命体征
                Map<String, Object> admissionResult = processTimePoint(pid, patient, admissionPlanTime, admissionWindow, traceId);
                admissionResult.put("date", admissionDate.toString());
                admissionResult.put("hour", vitalHour);
                admissionResult.put("type", "入科体征");
                admissionResult.put("icuAdmissionTime", admissionDateTime.toString());
                details.add(admissionResult);

                totalInserted += (int) admissionResult.getOrDefault("inserted", 0);
                totalSkipped += (int) admissionResult.getOrDefault("skipped", 0);
                totalFailed += (int) admissionResult.getOrDefault("failed", 0);

                // 身高体重
                try {
                    VitalSignPayload heightPayload = heightWeightHandler.buildHeightPayload(patient, admissionPlanTime, traceId);
                    if (heightPayload != null) {
                        String action = (String) intermediateService.upsertPending(heightPayload, traceId).get("action");
                        if ("INSERT".equals(action)) totalInserted++;
                        else if ("SKIP".equals(action)) totalSkipped++;
                        log.info("MANUAL_SCAN traceId={} 入科身高处理完成 action={}", traceId, action);
                    }
                    VitalSignPayload weightPayload = heightWeightHandler.buildWeightPayload(patient, admissionPlanTime, traceId);
                    if (weightPayload != null) {
                        String action = (String) intermediateService.upsertPending(weightPayload, traceId).get("action");
                        if ("INSERT".equals(action)) totalInserted++;
                        else if ("SKIP".equals(action)) totalSkipped++;
                        log.info("MANUAL_SCAN traceId={} 入科体重处理完成 action={}", traceId, action);
                    }
                } catch (Exception e) {
                    totalFailed++;
                    log.error("MANUAL_SCAN traceId={} 入科身高体重处理异常", traceId, e);
                }

                log.info("MANUAL_SCAN traceId={} 入科时间点扫描完成 admissionDate={} vitalHour={}", traceId, admissionDate, vitalHour);
            }
        }

        // ========== 3. 血压（07:00）==========
        LocalDate bpDate = startDate;
        while (!bpDate.isAfter(endDate)) {
            Map<String, Object> bpResult = processBloodPressure(pid, patient, bpDate, traceId);
            if (bpResult != null) {
                bpResult.put("date", bpDate.toString());
                bpResult.put("type", "血压");
                details.add(bpResult);
                totalInserted += (int) bpResult.getOrDefault("inserted", 0);
                totalSkipped += (int) bpResult.getOrDefault("skipped", 0);
            }
            bpDate = bpDate.plusDays(1);
        }

        // ========== 4. 每日汇总（出入量）==========
        LocalDate summaryDate = startDate;
        while (!summaryDate.isAfter(endDate)) {
            ClinicalTimeWindow dailyWindow = timeWindowService.buildDailyWindow(summaryDate);
            Map<String, Object> summaryResult = processDailySummary(pid, patient, dailyWindow, traceId);
            summaryResult.put("date", summaryDate.toString());
            summaryResult.put("type", "每日汇总");
            details.add(summaryResult);

            totalInserted += (int) summaryResult.getOrDefault("inserted", 0);
            totalSkipped += (int) summaryResult.getOrDefault("skipped", 0);
            totalFailed += (int) summaryResult.getOrDefault("failed", 0);

            summaryDate = summaryDate.plusDays(1);
        }

        result.put("totalInserted", totalInserted);
        result.put("totalSkipped", totalSkipped);
        result.put("totalFailed", totalFailed);
        result.put("details", details);

        log.info("MANUAL_SCAN traceId={} 完成 inserted={} skipped={} failed={}",
                traceId, totalInserted, totalSkipped, totalFailed);

        return result;
    }

    /**
     * 处理单个时间点的所有普通体征
     */
    private Map<String, Object> processTimePoint(String pid, Document patient,
                                                  LocalDateTime planTime,
                                                  ClinicalTimeWindow window,
                                                  String traceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        int inserted = 0;
        int skipped = 0;
        int failed = 0;

        Date startTime = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Date endTime = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

        // 体温
        try {
            Document tRecord = findBedside(pid, "param_T", startTime, endTime);
            if (tRecord != null) {
                VitalSignPayload payload = temperatureHandler.handle(tRecord, patient, planTime, traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 体温处理异常", traceId, e);
        }

        // 脉搏
        try {
            Document pulseRecord = findBedside(pid, "param_脉搏", startTime, endTime);
            if (pulseRecord == null) pulseRecord = findBedside(pid, "param_PR", startTime, endTime);
            if (pulseRecord != null) {
                VitalSignPayload payload = pulseHandler.handle(pulseRecord, patient, planTime, traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 脉搏处理异常", traceId, e);
        }

        // 心率
        try {
            Document hrRecord = findBedside(pid, "param_HR", startTime, endTime);
            if (hrRecord != null) {
                VitalSignPayload payload = heartRateHandler.handle(hrRecord, patient, planTime, traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 心率处理异常", traceId, e);
        }

        // 呼吸
        try {
            Document respRecord = findBedside(pid, "param_resp", startTime, endTime);
            if (respRecord != null) {
                VitalSignPayload payload = breathHandler.handle(respRecord, patient, planTime, traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 呼吸处理异常", traceId, e);
        }

        // 疼痛评分
        try {
            Document painRecord = findBedside(pid, "param_tengTong_score", startTime, endTime);
            if (painRecord != null) {
                VitalSignPayload payload = painScoreHandler.handle(painRecord, patient, planTime, traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 疼痛评分处理异常", traceId, e);
        }

        result.put("inserted", inserted);
        result.put("skipped", skipped);
        result.put("failed", failed);
        return result;
    }

    /**
     * 处理血压（07:00）
     */
    private Map<String, Object> processBloodPressure(String pid, Document patient,
                                                      LocalDate date, String traceId) {
        LocalDateTime planTime = LocalDateTime.of(date, LocalTime.of(7, 0, 0));
        ClinicalTimeWindow window = timeWindowService.buildVitalPointWindow(date, 7);

        Date startTime = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Date endTime = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

        Query bpQuery = new Query(Criteria.where("pid").is(pid)
                .and("code").is("param_nibp_s")
                .and("time").gte(startTime).lt(endTime));
        Document bpRecord = mongoTemplate.findOne(bpQuery, Document.class, "bedside");

        Map<String, Object> result = new LinkedHashMap<>();
        if (bpRecord != null) {
            try {
                VitalSignPayload payload = bloodPressureHandler.handle(bpRecord, patient, planTime, traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    result.put("action", action);
                    result.put("inserted", "INSERT".equals(action) ? 1 : 0);
                    result.put("skipped", "SKIP".equals(action) ? 1 : 0);
                    return result;
                }
            } catch (Exception e) {
                log.error("MANUAL_SCAN traceId={} 血压处理异常", traceId, e);
            }
        }
        result.put("action", "NO_DATA");
        result.put("inserted", 0);
        result.put("skipped", 0);
        return result;
    }

    /**
     * 处理每日汇总（出入量）
     * 窗口：[前一天07:00, 当天07:00)
     */
    private Map<String, Object> processDailySummary(String pid, Document patient,
                                                     ClinicalTimeWindow window,
                                                     String traceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        int inserted = 0;
        int skipped = 0;
        int failed = 0;
        List<String> items = new ArrayList<>();

        Date startDate = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Date endDate = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

        // 查询窗口内所有bedside记录
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("time").gte(startDate).lt(endDate));
        List<Document> records = mongoTemplate.find(query, Document.class, "bedside");

        // 大便次数（只查07:00）
        try {
            Date stoolStart = Date.from(window.getEnd().minusHours(1).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Date stoolEnd = endDate;
            Query stoolQuery = new Query(Criteria.where("pid").is(pid)
                    .and("code").is("param_汇总大便次数")
                    .and("time").gte(stoolStart).lt(stoolEnd));
            Document stoolRecord = mongoTemplate.findOne(stoolQuery, Document.class, "bedside");
            if (stoolRecord != null) {
                VitalSignPayload payload = stoolCountHandler.handle(stoolRecord, patient, window.getReportDate(), traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    items.add("大便次数:" + action);
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 大便次数处理异常", traceId, e);
        }

        // 小便量
        try {
            BigDecimal urineTotal = sumByCode(records, "param_niaoLiang");
            if (urineTotal.compareTo(BigDecimal.ZERO) > 0) {
                Document doc = new Document("strVal", urineTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_niaoLiang");
                VitalSignPayload payload = urineOutputHandler.handle(doc, patient, window.getReportDate(), traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    items.add("小便量:" + action);
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 小便量处理异常", traceId, e);
        }

        // 饮入量
        try {
            List<String> oralCodes = OralIntakeHandler.getOralIntakeCodes();
            BigDecimal oralTotal = sumByCodes(records, oralCodes);
            if (oralTotal.compareTo(BigDecimal.ZERO) > 0) {
                Document doc = new Document("strVal", oralTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_kouFu");
                VitalSignPayload payload = oralIntakeHandler.handle(doc, patient, window.getReportDate(), traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    items.add("饮入量:" + action);
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 饮入量处理异常", traceId, e);
        }

        // 治疗输入量
        try {
            List<String> therapyCodes = TherapyInputHandler.getTherapyInputCodes();
            BigDecimal therapyTotal = sumByCodes(records, therapyCodes);
            if (therapyTotal.compareTo(BigDecimal.ZERO) > 0) {
                Document doc = new Document("strVal", therapyTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_YaoYeti_in_hour");
                VitalSignPayload payload = therapyInputHandler.handle(doc, patient, window.getReportDate(), traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    items.add("治疗输入量:" + action);
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 治疗输入量处理异常", traceId, e);
        }

        // 总输入量
        try {
            List<String> oralCodes = OralIntakeHandler.getOralIntakeCodes();
            List<String> therapyCodes = TherapyInputHandler.getTherapyInputCodes();
            BigDecimal oralTotal = sumByCodes(records, oralCodes);
            BigDecimal therapyTotal = sumByCodes(records, therapyCodes);
            BigDecimal totalInput = oralTotal.add(therapyTotal);
            if (totalInput.compareTo(BigDecimal.ZERO) > 0) {
                VitalSignPayload payload = totalInputHandler.buildPayload(
                        totalInput.doubleValue(), patient, window.getReportDate(), traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    items.add("总输入量:" + action);
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 总输入量处理异常", traceId, e);
        }

        // 总出量
        try {
            List<String> outputCodes = getDynamicOutputCodes(pid, traceId);
            if (!outputCodes.isEmpty()) {
                BigDecimal outputTotal = sumByCodes(records, outputCodes);
                if (outputTotal.compareTo(BigDecimal.ZERO) > 0) {
                    VitalSignPayload payload = totalOutputHandler.buildPayload(
                            outputTotal.doubleValue(), patient, window.getReportDate(), traceId);
                    if (payload != null) {
                        String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                        if ("INSERT".equals(action)) inserted++;
                        else if ("SKIP".equals(action)) skipped++;
                        items.add("总出量:" + action);
                    }
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 总出量处理异常", traceId, e);
        }

        // 排出物量
        try {
            List<String> drainageCodes = DrainageOutputHandler.getDrainageCodes();
            BigDecimal drainageTotal = sumByCodes(records, drainageCodes);
            if (drainageTotal.compareTo(BigDecimal.ZERO) > 0) {
                Document doc = new Document("strVal", drainageTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_daBianAmount");
                VitalSignPayload payload = drainageOutputHandler.handle(doc, patient, window.getReportDate(), traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    items.add("排出物量:" + action);
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 排出物量处理异常", traceId, e);
        }

        // 胃管负压引流
        try {
            BigDecimal gastricTotal = sumByCode(records, "param_tube_胃肠减压");
            if (gastricTotal.compareTo(BigDecimal.ZERO) > 0) {
                Document doc = new Document("strVal", gastricTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_tube_胃肠减压");
                VitalSignPayload payload = gastricDrainageHandler.handle(doc, patient, window.getReportDate(), traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    items.add("胃管负压引流:" + action);
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 胃管负压引流处理异常", traceId, e);
        }

        // 其他引流量
        try {
            BigDecimal otherTotal = records.stream()
                    .filter(doc -> {
                        String code = doc.getString("code");
                        return code != null && code.contains("_tube_") && !"param_tube_胃肠减压".equals(code);
                    })
                    .map(doc -> parseBigDecimal(doc.getString("strVal")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (otherTotal.compareTo(BigDecimal.ZERO) > 0) {
                Document doc = new Document("strVal", otherTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_tube_other");
                VitalSignPayload payload = otherDrainageHandler.handle(doc, patient, window.getReportDate(), traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    items.add("其他引流量:" + action);
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 其他引流量处理异常", traceId, e);
        }

        // 净超滤量
        try {
            BigDecimal ultraTotal = sumByCode(records, "param_chaoLvLiang");
            if (ultraTotal.compareTo(BigDecimal.ZERO) > 0) {
                Document doc = new Document("strVal", ultraTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_chaoLvLiang");
                VitalSignPayload payload = netUltrafiltrationHandler.handle(doc, patient, window.getReportDate(), traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    items.add("净超滤量:" + action);
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 净超滤量处理异常", traceId, e);
        }

        // 身高体重
        try {
            String hisPatientId = patient.getString("mrn");
            if (heightWeightHandler.shouldSendHeightWeight(hisPatientId, window.getReportDate().toLocalDate(), patient)) {
                VitalSignPayload heightPayload = heightWeightHandler.buildHeightPayload(patient, window.getReportDate(), traceId);
                if (heightPayload != null) {
                    String action = (String) intermediateService.upsertPending(heightPayload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    items.add("身高:" + action);
                }
                VitalSignPayload weightPayload = heightWeightHandler.buildWeightPayload(patient, window.getReportDate(), traceId);
                if (weightPayload != null) {
                    String action = (String) intermediateService.upsertPending(weightPayload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    items.add("体重:" + action);
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 身高体重处理异常", traceId, e);
        }

        result.put("inserted", inserted);
        result.put("skipped", skipped);
        result.put("failed", failed);
        result.put("items", items);
        return result;
    }

    /**
     * 查询 bedside 记录
     */
    private Document findBedside(String pid, String code, Date startTime, Date endTime) {
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(code)
                .and("time").gte(startTime).lt(endTime)
                .and("valid").is(true));
        return mongoTemplate.findOne(query, Document.class, "bedside");
    }

    /**
     * 按单个code求和
     */
    private BigDecimal sumByCode(List<Document> records, String code) {
        return records.stream()
                .filter(doc -> code.equals(doc.getString("code")))
                .map(doc -> parseBigDecimal(doc.getString("strVal")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 按多个code求和
     */
    private BigDecimal sumByCodes(List<Document> records, List<String> codes) {
        return records.stream()
                .filter(doc -> {
                    String code = doc.getString("code");
                    return code != null && codes.contains(code);
                })
                .map(doc -> parseBigDecimal(doc.getString("strVal")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 安全解析BigDecimal
     */
    private BigDecimal parseBigDecimal(String val) {
        if (val == null || val.trim().isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(val.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 从Document中安全获取指定key的值
     */
    private <T> T getValueFromDocByKey(Document doc, String key, Class<T> clazz) {
        if (doc == null) return null;
        Object value = doc.get(key);
        if (value == null) return null;
        return clazz.cast(value);
    }

    /**
     * 动态获取出量代码
     */
    private List<String> getDynamicOutputCodes(String pid, String traceId) {
        try {
            Query configQuery = new Query(Criteria.where("pid").is(pid)
                    .and("groupName").is("出入量"));
            Document config = mongoTemplate.findOne(configQuery, Document.class, "bedsideConfig");
            if (config == null) return Collections.emptyList();

            @SuppressWarnings("unchecked")
            List<Document> groups = (List<Document>) config.get("groups");
            if (groups == null) return Collections.emptyList();

            for (Document group : groups) {
                if ("出量".equals(group.getString("name"))) {
                    @SuppressWarnings("unchecked")
                    List<Document> items = (List<Document>) group.get("items");
                    if (items == null) continue;

                    List<String> codes = new ArrayList<>();
                    for (Document item : items) {
                        String code = item.getString("code");
                        if (code != null) {
                            Query paramQuery = new Query(Criteria.where("code").is(code));
                            Document param = mongoTemplate.findOne(paramQuery, Document.class, "configParam");
                            if (param != null && "out".equals(param.getString("calculation"))) {
                                codes.add(code);
                            }
                        }
                    }
                    return codes;
                }
            }
        } catch (Exception e) {
            log.error("MANUAL_SCAN traceId={} 获取动态出量配置失败 pid={}", traceId, pid, e);
        }
        return Collections.emptyList();
    }
}
