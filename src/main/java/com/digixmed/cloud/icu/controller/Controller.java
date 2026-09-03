package com.digixmed.cloud.icu.controller;

import com.digixmed.cloud.icu.handler.*;
import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.InpatientDTO;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.repository.InpatientRepository;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import com.digixmed.cloud.icu.service.IntermediateService;
import com.digixmed.cloud.icu.service.IntakeOutputCalculator;
import com.digixmed.cloud.icu.service.DrugAmountCalculator;
import com.digixmed.cloud.icu.task.PushTask;
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
import java.util.stream.Collectors;

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
    private IntakeOutputCalculator intakeOutputCalculator;

    @Autowired
    private DrugAmountCalculator drugAmountCalculator;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ClinicalTimeWindowService timeWindowService;

    @Autowired
    private PatientIdentityMapper patientIdentityMapper;

    @Autowired
    private InpatientRepository inpatientRepository;

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
    @Autowired
    private PushTask pushTask;

    @GetMapping("/health")
    @ApiOperation(value = "健康检查", notes = "服务健康检查接口")
    public String health() {
        return "OK";
    }

    @GetMapping("/queue/stats")
    @ApiOperation(value = "队列统计", notes = "查看推送队列状态统计")
    public Map<String, Object> queueStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        String[] statuses = {"FAILED", "SUCCESS"};
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

                // 身高体重（R2: planTime=recordTime=icuAdmissionTime）
                try {
                    List<VitalSignPayload> hwPayloads = heightWeightHandler.buildAdmissionFirst(
                            patient, admissionDateTime, null, traceId);
                    for (VitalSignPayload hwPayload : hwPayloads) {
                        String action = (String) intermediateService.upsertPending(hwPayload, traceId).get("action");
                        if ("INSERT".equals(action)) totalInserted++;
                        else if ("SKIP".equals(action)) totalSkipped++;
                        log.info("MANUAL_SCAN traceId={} 入科{}处理完成 action={}", traceId, hwPayload.getVitalsignName(), action);
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
        // 注意：用户传入的 startDate 是"报表日"，对应窗口 [startDate-1 07:00, startDate 07:00)
        // 例如 startDate=2026-08-23 对应窗口 [2026-08-22 07:00, 2026-08-23 07:00)
        LocalDate summaryDate = startDate;
        while (!summaryDate.isAfter(endDate)) {
            // 使用 summaryDate 的前一天构建窗口，因为汇总的是前一天的数据
            ClinicalTimeWindow dailyWindow = timeWindowService.buildDailyWindow(summaryDate.minusDays(1));
            log.info("MANUAL_SCAN traceId={} 每日汇总: reportDate={} window=[{}, {})",
                    traceId, summaryDate, dailyWindow.getStart(), dailyWindow.getEnd());
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
     * 扫描在科患者指定日期的生命体征数据（用于调试分析）
     *
     * 功能：
     *   1. 查询当前在科患者
     *   2. 对每个患者扫描指定日期的6个标准时间点（02/06/10/14/18/22）
     *   3. 输出详细的日志便于分析问题
     *
     * @param targetDate 目标日期（如2026-08-22），回传该日期的6个时间点数据
     * @return 扫描结果统计
     */
    @PostMapping("/debug/scanByDate")
    @ApiOperation(value = "调试扫描在科患者指定日期体征", notes = "查询在科患者并扫描指定日期的6个时间点生命体征，输出详细日志")
    public Map<String, Object> debugScanByDate(
            @ApiParam(value = "目标日期", required = true, example = "2026-08-22")
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate targetDate) {

        String traceId = TraceIdGenerator.generate();
        log.info("========== DEBUG_SCAN_BY_DATE traceId={} targetDate={} ==========", traceId, targetDate);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("targetDate", targetDate.toString());

        // 1. 查询在科患者
        String wardCode = "125011"; // 配置项
        List<InpatientDTO> inpatients = inpatientRepository.findInpatients(wardCode);
        log.info("DEBUG_SCAN traceId={} 在科患者数量={}", traceId, inpatients.size());

        if (inpatients.isEmpty()) {
            result.put("message", "无在科患者");
            result.put("totalPatients", 0);
            return result;
        }

        List<Map<String, Object>> patientResults = new ArrayList<>();
        int totalInserted = 0;
        int totalSkipped = 0;
        int totalFailed = 0;

        // 2. 遍历每个患者
        for (InpatientDTO inpatient : inpatients) {
            String patientId = inpatient.getPatientId();
            String patientName = inpatient.getName();
            String mrn = inpatient.getMrn();

            log.info("DEBUG_SCAN traceId={} ---------- 患者开始 patientId={} name={} ----------",
                    traceId, maskPatientId(patientId), patientName);

            // 2.1 在MongoDB中查找患者
            Query patientQuery = new Query(new Criteria().orOperator(
                    Criteria.where("mrn").is(mrn),
                    Criteria.where("hisPid").is(mrn)
            ));
            Document patient = mongoTemplate.findOne(patientQuery, Document.class, "patient");

            if (patient == null) {
                log.warn("DEBUG_SCAN traceId={} patientId={} MongoDB中未找到患者记录", traceId, maskPatientId(patientId));
                continue;
            }

            String pid = patient.get("_id").toString();
            log.info("DEBUG_SCAN traceId={} patientId={} mongoPid={}", traceId, maskPatientId(patientId), pid);

            // 2.2 扫描6个标准时间点
            Map<String, Object> patientResult = new LinkedHashMap<>();
            patientResult.put("patientId", maskPatientId(patientId));
            patientResult.put("patientName", patientName);
            patientResult.put("mrn", mrn);
            patientResult.put("mongoPid", pid);

            List<Map<String, Object>> timePointResults = new ArrayList<>();

            for (int hour : VITAL_SIGN_HOURS) {
                LocalDateTime planTime = LocalDateTime.of(targetDate, LocalTime.of(hour, 0, 0));
                ClinicalTimeWindow window = timeWindowService.buildVitalPointWindow(targetDate, hour);

                log.info("DEBUG_SCAN traceId={} 时间点={} 窗口=[{}, {})",
                        traceId, hour, window.getStart(), window.getEnd());

                Map<String, Object> pointResult = processTimePointDebug(pid, patient, planTime, window, traceId);
                pointResult.put("hour", hour);
                timePointResults.add(pointResult);

                totalInserted += (int) pointResult.getOrDefault("inserted", 0);
                totalSkipped += (int) pointResult.getOrDefault("skipped", 0);
                totalFailed += (int) pointResult.getOrDefault("failed", 0);
            }

            patientResult.put("timePoints", timePointResults);
            patientResults.add(patientResult);

            log.info("DEBUG_SCAN traceId={} ---------- 患者结束 patientId={} ----------",
                    traceId, maskPatientId(patientId));
        }

        result.put("totalPatients", inpatients.size());
        result.put("totalInserted", totalInserted);
        result.put("totalSkipped", totalSkipped);
        result.put("totalFailed", totalFailed);
        result.put("patients", patientResults);

        log.info("========== DEBUG_SCAN_BY_DATE traceId={} 完成 totalInserted={} totalSkipped={} totalFailed={} ==========",
                traceId, totalInserted, totalSkipped, totalFailed);

        return result;
    }

    /**
     * 批量重新扫描在科患者指定日期和时间点，并立即推送
     *
     * 用于数据修正场景：修改业务逻辑后，重新扫描并推送历史数据
     *
     * @param targetDate 目标日期
     * @param hours      可选，要扫描的时间点（小时），逗号分隔。为空则扫描全部6个时间点(2,6,10,14,18,22)
     * @return 扫描和推送结果
     */
    @PostMapping("/manual/scanAndPush")
    @ApiOperation(value = "批量扫描并立即推送", notes = "重新扫描在科患者指定日期和时间点的数据，然后立即推送到HIS")
    public Map<String, Object> scanAndPush(
            @ApiParam(value = "目标日期", required = true, example = "2026-08-23")
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate targetDate,
            @ApiParam(value = "时间点(小时)，逗号分隔，为空则全部6个时间点", example = "2,6,10,14")
            @RequestParam(required = false) String hours) {

        String traceId = TraceIdGenerator.generate();
        log.info("========== SCAN_AND_PUSH traceId={} targetDate={} hours={} ==========",
                traceId, targetDate, hours);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("targetDate", targetDate.toString());

        // 解析时间点
        List<Integer> targetHours;
        if (hours != null && !hours.trim().isEmpty()) {
            targetHours = Arrays.stream(hours.split(","))
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());
        } else {
            targetHours = VITAL_SIGN_HOURS;
        }
        result.put("targetHours", targetHours);

        // 1. 查询在科患者
        String wardCode = "125011";
        List<InpatientDTO> inpatients = inpatientRepository.findInpatients(wardCode);
        log.info("SCAN_AND_PUSH traceId={} 在科患者数量={} 目标时间点={}", traceId, inpatients.size(), targetHours);

        if (inpatients.isEmpty()) {
            result.put("message", "无在科患者");
            result.put("totalPatients", 0);
            return result;
        }

        int totalInserted = 0;
        int totalSkipped = 0;
        int totalFailed = 0;

        // 2. 遍历每个患者
        for (InpatientDTO inpatient : inpatients) {
            String patientId = inpatient.getPatientId();
            String mrn = inpatient.getMrn();

            // 2.1 在MongoDB中查找患者
            Query patientQuery = new Query(new Criteria().orOperator(
                    Criteria.where("mrn").is(mrn),
                    Criteria.where("hisPid").is(mrn)
            ));
            Document patient = mongoTemplate.findOne(patientQuery, Document.class, "patient");

            if (patient == null) {
                log.warn("SCAN_AND_PUSH traceId={} patientId={} MongoDB中未找到患者记录", traceId, maskPatientId(patientId));
                continue;
            }

            String pid = patient.get("_id").toString();

            // 2.2 扫描指定时间点（普通体征）
            for (int hour : targetHours) {
                LocalDateTime planTime = LocalDateTime.of(targetDate, LocalTime.of(hour, 0, 0));
                ClinicalTimeWindow window = timeWindowService.buildVitalPointWindow(targetDate, hour);

                if (window == null) {
                    continue;
                }

                Map<String, Object> pointResult = processTimePointDebug(pid, patient, planTime, window, traceId);
                totalInserted += (int) pointResult.getOrDefault("inserted", 0);
                totalSkipped += (int) pointResult.getOrDefault("skipped", 0);
                totalFailed += (int) pointResult.getOrDefault("failed", 0);
            }

            // 2.3 出入量汇总（使用前一天的窗口）
            ClinicalTimeWindow dailyWindow = timeWindowService.buildDailyWindow(targetDate.minusDays(1));
            log.info("SCAN_AND_PUSH traceId={} pid={} 出入量汇总: window=[{}, {})",
                    traceId, pid, dailyWindow.getStart(), dailyWindow.getEnd());
            Map<String, Object> summaryResult = processDailySummary(pid, patient, dailyWindow, traceId);
            totalInserted += (int) summaryResult.getOrDefault("inserted", 0);
            totalSkipped += (int) summaryResult.getOrDefault("skipped", 0);
            totalFailed += (int) summaryResult.getOrDefault("failed", 0);
        }

        result.put("totalPatients", inpatients.size());
        result.put("totalInserted", totalInserted);
        result.put("totalSkipped", totalSkipped);
        result.put("totalFailed", totalFailed);

        log.info("SCAN_AND_PUSH traceId={} 扫描完成 inserted={} skipped={} failed={}",
                traceId, totalInserted, totalSkipped, totalFailed);

        // 3. 立即推送
        log.info("SCAN_AND_PUSH traceId={} 开始立即推送...", traceId);
        try {
            pushTask.pushOnce(traceId);
            log.info("SCAN_AND_PUSH traceId={} 推送完成", traceId);
            result.put("pushStatus", "completed");
        } catch (Exception e) {
            log.error("SCAN_AND_PUSH traceId={} 推送异常", traceId, e);
            result.put("pushStatus", "error: " + e.getMessage());
        }

        log.info("========== SCAN_AND_PUSH traceId={} 全部完成 ==========", traceId);
        return result;
    }

    /**
     * 调试版：处理单个时间点的所有普通体征（带详细日志）
     */
    private Map<String, Object> processTimePointDebug(String pid, Document patient,
                                                       LocalDateTime planTime,
                                                       ClinicalTimeWindow window,
                                                       String traceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        int inserted = 0;
        int skipped = 0;
        int failed = 0;
        List<String> details = new ArrayList<>();

        Date startTime = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Date endTime = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

        // 体温
        try {
            Document tRecord = findBedside(pid, "param_T", startTime, endTime);
            if (tRecord != null) {
                log.info("DEBUG_SCAN traceId={} pid={} 18:00体温原始数据: time={}, strVal={}, valid={}",
                        traceId, pid, tRecord.get("time"), tRecord.get("strVal"), tRecord.get("valid"));
                VitalSignPayload payload = temperatureHandler.handle(tRecord, patient, planTime, traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    details.add("体温:" + action + "=" + payload.getVitalsignNVal1());
                } else {
                    details.add("体温:handler返回null");
                }
            } else {
                details.add("体温:无数据");
            }
        } catch (Exception e) {
            failed++;
            details.add("体温:异常=" + e.getMessage());
            log.error("DEBUG_SCAN traceId={} pid={} 体温处理异常", traceId, pid, e);
        }

        // 脉搏
        try {
            Document pulseRecord = findBedside(pid, "param_脉搏", startTime, endTime);
            if (pulseRecord == null) pulseRecord = findBedside(pid, "param_PR", startTime, endTime);
            if (pulseRecord != null) {
                log.info("DEBUG_SCAN traceId={} pid={} 脉搏原始数据: code={}, time={}, strVal={}",
                        traceId, pid, pulseRecord.get("code"), pulseRecord.get("time"), pulseRecord.get("strVal"));
                VitalSignPayload payload = pulseHandler.handle(pulseRecord, patient, planTime, traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    details.add("脉搏:" + action + "=" + payload.getVitalsignNVal1());
                } else {
                    details.add("脉搏:handler返回null");
                }
            } else {
                details.add("脉搏:无数据");
            }
        } catch (Exception e) {
            failed++;
            details.add("脉搏:异常=" + e.getMessage());
            log.error("DEBUG_SCAN traceId={} pid={} 脉搏处理异常", traceId, pid, e);
        }

        // 心率
        try {
            Document hrRecord = findBedside(pid, "param_HR", startTime, endTime);
            if (hrRecord != null) {
                log.info("DEBUG_SCAN traceId={} pid={} 心率原始数据: time={}, strVal={}",
                        traceId, pid, hrRecord.get("time"), hrRecord.get("strVal"));
                VitalSignPayload payload = heartRateHandler.handle(hrRecord, patient, planTime, traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    details.add("心率:" + action + "=" + payload.getVitalsignNVal1());
                } else {
                    details.add("心率:handler返回null");
                }
            } else {
                details.add("心率:无数据");
            }
        } catch (Exception e) {
            failed++;
            details.add("心率:异常=" + e.getMessage());
            log.error("DEBUG_SCAN traceId={} pid={} 心率处理异常", traceId, pid, e);
        }

        // 呼吸
        try {
            Document respRecord = findBedside(pid, "param_resp", startTime, endTime);
            if (respRecord != null) {
                log.info("DEBUG_SCAN traceId={} pid={} 呼吸原始数据: time={}, strVal={}",
                        traceId, pid, respRecord.get("time"), respRecord.get("strVal"));
                VitalSignPayload payload = breathHandler.handle(respRecord, patient, planTime, traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    details.add("呼吸:" + action + "=" + payload.getVitalsignNVal1());
                } else {
                    details.add("呼吸:handler返回null");
                }
            } else {
                details.add("呼吸:无数据");
            }
        } catch (Exception e) {
            failed++;
            details.add("呼吸:异常=" + e.getMessage());
            log.error("DEBUG_SCAN traceId={} pid={} 呼吸处理异常", traceId, pid, e);
        }

        // 疼痛评分
        try {
            Document painRecord = findBedside(pid, "param_tengTong_score", startTime, endTime);
            if (painRecord != null) {
                log.info("DEBUG_SCAN traceId={} pid={} 疼痛评分原始数据: time={}, strVal={}",
                        traceId, pid, painRecord.get("time"), painRecord.get("strVal"));
                VitalSignPayload payload = painScoreHandler.handle(painRecord, patient, planTime, traceId);
                if (payload != null) {
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    details.add("疼痛评分:" + action + "=" + payload.getVitalsignNVal1());
                } else {
                    details.add("疼痛评分:handler返回null");
                }
            } else {
                details.add("疼痛评分:无数据");
            }
        } catch (Exception e) {
            failed++;
            details.add("疼痛评分:异常=" + e.getMessage());
            log.error("DEBUG_SCAN traceId={} pid={} 疼痛评分处理异常", traceId, pid, e);
        }

        result.put("inserted", inserted);
        result.put("skipped", skipped);
        result.put("failed", failed);
        result.put("details", details);
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
        // 07:00 不是标准体征时间点，手动构建窗口
        LocalDateTime bpStart = LocalDateTime.of(date, LocalTime.of(6, 0, 0));
        LocalDateTime bpEnd = LocalDateTime.of(date, LocalTime.of(7, 0, 0));

        Date startTime = Date.from(bpStart.atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Date endTime = Date.from(bpEnd.atZone(ZoneId.of("Asia/Shanghai")).toInstant());

        Query bpQuery = new Query(Criteria.where("pid").is(pid)
                .and("code").is("param_nibp_s")
                .and("valid").ne(false).ne(0)
                .and("time").gte(startTime).lt(endTime));
        Document bpRecord = mongoTemplate.findOne(bpQuery, Document.class, "bedside");
        if (bpRecord != null) {
            Object valid = bpRecord.get("valid");
            if (Boolean.FALSE.equals(valid) || Integer.valueOf(0).equals(valid)) {
                bpRecord = null;
            }
        }

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
        LocalDateTime planTimeDate = window.getEnd();

        // 入科第一天：计算入科时间到第二天07:00的小时数（取 patient.icuAdmissionTime）
        int admissionHoursToSeven = 0;
        Date icuAdmissionDate = patient.get("icuAdmissionTime") instanceof Date
                ? (Date) patient.get("icuAdmissionTime") : null;
        if (icuAdmissionDate != null) {
            LocalDateTime admissionTime = icuAdmissionDate.toInstant()
                    .atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
            LocalDate admissionDate = admissionTime.toLocalDate();
            LocalDate reportDate = window.getStart().toLocalDate();
            if (reportDate.equals(admissionDate)) {
                long minutes = java.time.Duration.between(admissionTime, window.getEnd()).toMinutes();
                // 1小时29分算1小时、1小时30分算2小时（满30分钟进1小时）
                admissionHoursToSeven = (int) ((minutes + 30) / 60);
                log.info("MANUAL_SCAN_ADMISSION_HOURS traceId={} pid={} icuAdmissionTime={} hoursToSeven={}",
                        traceId, pid, admissionTime, admissionHoursToSeven);
                // 入科当天：bedside查询起点取 max(window.start, admissionTime)
                Date admissionDateInstant = Date.from(admissionTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant());
                if (admissionDateInstant.after(startDate)) {
                    startDate = admissionDateInstant;
                    log.info("MANUAL_SCAN_ADMISSION_START_ADJUSTED traceId={} pid={} startDate调整为入科时间={}",
                            traceId, pid, admissionTime);
                }
            }
        }

        // 查询窗口内出入量相关bedside记录（左闭右开：time >= start AND time < end）
        List<String> intakeOutputCodes = Arrays.asList(
                "param_kouFu", "param_biSi", "param_YaoStomach_in_hour",
                "param_带入药量", "param_YaoYeti_in_hour", "param_YaoShuXue_in_hour",
                "param_niaoLiang", "param_汇总大便次数",
                "param_out_day",
                "param_daBianAmount", "param_造瘘口量", "param_outuwuliang", "param_咯血", "param_tanLiang", "param_渗液",
                "param_chaoLvLiang", "param_tube_胃肠减压",
                "param_in_day"
        );
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("valid").ne(false).ne(0)
                .and("time").gte(startDate).lt(endDate)
                .andOperator(
                        new Criteria().orOperator(
                                Criteria.where("code").in(intakeOutputCodes),
                                Criteria.where("code").regex("_tube_")
                        )
                ))
                .limit(1000);
        List<Document> records = mongoTemplate.find(query, Document.class, "bedside");

        // 二次过滤：排除 valid=false 的作废记录
        int beforeFilter = records.size();
        records.removeIf(doc -> {
            Object valid = doc.get("valid");
            return Boolean.FALSE.equals(valid) || Integer.valueOf(0).equals(valid);
        });
        if (records.size() != beforeFilter) {
            log.warn("IO_VALID_FILTER traceId={} pid={} removed={} remaining={}",
                    traceId, pid, beforeFilter - records.size(), records.size());
        }

        // 大便次数（只查07:00）
        try {
            Date stoolStart = Date.from(window.getEnd().minusHours(1).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Date stoolEnd = endDate;
            Query stoolQuery = new Query(Criteria.where("pid").is(pid)
                    .and("code").is("param_汇总大便次数")
                    .and("valid").ne(false).ne(0)
                    .and("time").gte(stoolStart).lt(stoolEnd));
            Document stoolRecord = mongoTemplate.findOne(stoolQuery, Document.class, "bedside");
            if (stoolRecord != null) {
                Object valid = stoolRecord.get("valid");
                if (Boolean.FALSE.equals(valid) || Integer.valueOf(0).equals(valid)) {
                    stoolRecord = null;
                }
            }
            if (stoolRecord != null) {
                VitalSignPayload payload = stoolCountHandler.handle(stoolRecord, patient, planTimeDate, traceId);
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
            if (urineTotal.compareTo(BigDecimal.ZERO) >= 0) {
                Document doc = new Document("strVal", urineTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_niaoLiang");
                VitalSignPayload payload = urineOutputHandler.handle(doc, patient, planTimeDate, traceId);
                if (payload != null) {
                    if (admissionHoursToSeven > 0) {
                        payload.setVitalsignSVal1(String.valueOf(admissionHoursToSeven));
                    }
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
            // [DEBUG] 饮入量详细日志：逐条打印匹配记录
            log.info("IO_DEBUG traceId={} pid={} oralCodes={}", traceId, pid, oralCodes);
            for (String code : oralCodes) {
                List<Document> matched = records.stream()
                        .filter(doc -> code.equals(doc.getString("code")))
                        .collect(Collectors.toList());
                for (Document doc : matched) {
                    String val = doc.getString("strVal");
                    Object time = doc.get("time");
                    log.info("IO_DEBUG traceId={} pid={} oralMatched code={} strVal={} time={}",
                            traceId, pid, code, val, time);
                }
            }
            BigDecimal oralTotal = sumByCodes(records, oralCodes);
            // [DEBUG] 饮入量分项明细
            BigDecimal kouFuSum = sumByCode(records, "param_kouFu");
            BigDecimal biSiSum = sumByCode(records, "param_biSi");
            BigDecimal yaoStomachSum = sumByCode(records, "param_YaoStomach_in_hour");
            log.info("IO_DEBUG traceId={} pid={} oralDetail: kouFu={} biSi={} yaoStomach={} oralTotal={}",
                    traceId, pid, kouFuSum.stripTrailingZeros().toPlainString(),
                    biSiSum.stripTrailingZeros().toPlainString(),
                    yaoStomachSum.stripTrailingZeros().toPlainString(),
                    oralTotal.stripTrailingZeros().toPlainString());
            if (oralTotal.compareTo(BigDecimal.ZERO) >= 0) {
                Document doc = new Document("strVal", oralTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_kouFu");
                VitalSignPayload payload = oralIntakeHandler.handle(doc, patient, planTimeDate, traceId);
                if (payload != null) {
                    if (admissionHoursToSeven > 0) {
                        payload.setVitalsignSVal1(String.valueOf(admissionHoursToSeven));
                    }
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
            // [DEBUG] 治疗输入量详细日志：逐条打印匹配记录
            log.info("IO_DEBUG traceId={} pid={} therapyCodes={}", traceId, pid, therapyCodes);
            for (String code : therapyCodes) {
                List<Document> matched = records.stream()
                        .filter(doc -> code.equals(doc.getString("code")))
                        .collect(Collectors.toList());
                for (Document doc : matched) {
                    String val = doc.getString("strVal");
                    Object time = doc.get("time");
                    log.info("IO_DEBUG traceId={} pid={} therapyMatched code={} strVal={} time={}",
                            traceId, pid, code, val, time);
                }
            }
            BigDecimal therapyTotal = sumByCodes(records, therapyCodes);
            // [DEBUG] 治疗输入量分项明细
            BigDecimal daiRuYaoLiangSum = sumByCode(records, "param_带入药量");
            BigDecimal yaoYetiSum = sumByCode(records, "param_YaoYeti_in_hour");
            BigDecimal yaoShuXueSum = sumByCode(records, "param_YaoShuXue_in_hour");
            log.info("IO_DEBUG traceId={} pid={} therapyDetail: daiRuYaoLiang={} yaoYeti={} yaoShuXue={} therapyTotal={}",
                    traceId, pid, daiRuYaoLiangSum.stripTrailingZeros().toPlainString(),
                    yaoYetiSum.stripTrailingZeros().toPlainString(),
                    yaoShuXueSum.stripTrailingZeros().toPlainString(),
                    therapyTotal.stripTrailingZeros().toPlainString());
            if (therapyTotal.compareTo(BigDecimal.ZERO) >= 0) {
                Document doc = new Document("strVal", therapyTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_YaoYeti_in_hour");
                VitalSignPayload payload = therapyInputHandler.handle(doc, patient, planTimeDate, traceId);
                if (payload != null) {
                    if (admissionHoursToSeven > 0) {
                        payload.setVitalsignSVal1(String.valueOf(admissionHoursToSeven));
                    }
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

        // 总入量 1009：优先读 param_in_day，有值直接用，没有再逐项求和（对齐 DailySummaryTask）
        try {
            BigDecimal totalInput = null;
            for (Document doc : records) {
                if ("param_in_day".equals(doc.getString("code"))) {
                    String val = doc.getString("strVal");
                    if (val != null && !val.trim().isEmpty()) {
                        try {
                            totalInput = new BigDecimal(val.trim()).setScale(1, java.math.RoundingMode.HALF_UP);
                            log.info("MANUAL_SCAN_IO traceId={} pid={} source=param_in_day value={}",
                                    traceId, pid, totalInput.toPlainString());
                        } catch (NumberFormatException e) {
                            log.warn("MANUAL_SCAN_IO traceId={} pid={} param_in_day parse failed: {}",
                                    traceId, pid, val);
                        }
                    }
                    break;
                }
            }
            if (totalInput == null) {
                // 没有 param_in_day，走逐项求和
                List<Document> drugExecutions = drugAmountCalculator.queryDrugExe(pid, startDate, endDate);
                List<Document> drugMethods = drugAmountCalculator.queryDrugMethods();
                DrugAmountCalculator.DrugChannelTotals drugChannelTotals =
                        drugAmountCalculator.sumDrugAmountsByChannel(
                                drugExecutions, drugMethods,
                                startDate.getTime(), endDate.getTime(), true);
                // [DEBUG] 药物执行记录详情
                log.info("IO_DEBUG traceId={} pid={} drugExecutions.size={} drugMethods.size={}",
                        traceId, pid, drugExecutions.size(), drugMethods.size());
                log.info("IO_DEBUG traceId={} pid={} drugChannelTotals: vein={} gastro={} transfusion={} enteral={}",
                        traceId, pid,
                        drugChannelTotals.vein,
                        drugChannelTotals.gastro,
                        drugChannelTotals.transfusion.stripTrailingZeros().toPlainString(),
                        drugChannelTotals.enteral.stripTrailingZeros().toPlainString());
                totalInput = intakeOutputCalculator.sumTotalInput(
                        records, drugChannelTotals, traceId, pid).setScale(1, java.math.RoundingMode.HALF_UP);
                log.info("MANUAL_SCAN_IO traceId={} pid={} source=sumComponents value={}",
                        traceId, pid, totalInput.toPlainString());
            }
            if (totalInput.compareTo(BigDecimal.ZERO) >= 0) {
                VitalSignPayload payload = totalInputHandler.buildPayload(
                        totalInput.doubleValue(), patient, planTimeDate, traceId);
                if (payload != null) {
                    if (admissionHoursToSeven > 0) {
                        payload.setVitalsignSVal1(String.valueOf(admissionHoursToSeven));
                    }
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    items.add("总入量:" + action);
                }
            }
        } catch (Exception e) {
            failed++;
            log.error("MANUAL_SCAN traceId={} 总入量处理异常", traceId, e);
        }

        // 总出量 1010：优先读 param_out_day，有值直接用，没有再逐项求和（对齐 DailySummaryTask）
        try {
            BigDecimal total = null;
            for (Document doc : records) {
                if ("param_out_day".equals(doc.getString("code"))) {
                    String val = doc.getString("strVal");
                    if (val != null && !val.trim().isEmpty()) {
                        try {
                            total = new BigDecimal(val.trim()).setScale(1, java.math.RoundingMode.HALF_UP);
                            log.info("MANUAL_IO_DEBUG_TOTAL_OUTPUT traceId={} pid={} source=param_out_day value={}",
                                    traceId, pid, total.toPlainString());
                        } catch (NumberFormatException e) {
                            log.warn("MANUAL_IO_DEBUG_TOTAL_OUTPUT traceId={} pid={} param_out_day parse failed: {}",
                                    traceId, pid, val);
                        }
                    }
                    break;
                }
            }
            if (total == null) {
                // 没有 param_out_day，走逐项求和
                total = intakeOutputCalculator.sumTotalOutput(records, traceId, pid).setScale(1, java.math.RoundingMode.HALF_UP);
                log.info("MANUAL_IO_DEBUG_TOTAL_OUTPUT traceId={} pid={} source=sumComponents value={}",
                        traceId, pid, total.toPlainString());
            }
            if (total.compareTo(BigDecimal.ZERO) >= 0) {
                VitalSignPayload payload = totalOutputHandler.buildPayload(
                        total.doubleValue(), patient, planTimeDate, traceId);
                if (payload != null) {
                    if (admissionHoursToSeven > 0) {
                        payload.setVitalsignSVal1(String.valueOf(admissionHoursToSeven));
                    }
                    String action = (String) intermediateService.upsertPending(payload, traceId).get("action");
                    if ("INSERT".equals(action)) inserted++;
                    else if ("SKIP".equals(action)) skipped++;
                    items.add("总出量:" + action);
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
            if (drainageTotal.compareTo(BigDecimal.ZERO) >= 0) {
                Document doc = new Document("strVal", drainageTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_daBianAmount");
                VitalSignPayload payload = drainageOutputHandler.handle(doc, patient, planTimeDate, traceId);
                if (payload != null) {
                    if (admissionHoursToSeven > 0) {
                        payload.setVitalsignSVal1(String.valueOf(admissionHoursToSeven));
                    }
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
            if (gastricTotal.compareTo(BigDecimal.ZERO) >= 0) {
                Document doc = new Document("strVal", gastricTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_tube_胃肠减压");
                VitalSignPayload payload = gastricDrainageHandler.handle(doc, patient, planTimeDate, traceId);
                if (payload != null) {
                    if (admissionHoursToSeven > 0) {
                        payload.setVitalsignSVal1(String.valueOf(admissionHoursToSeven));
                    }
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
            if (otherTotal.compareTo(BigDecimal.ZERO) >= 0) {
                Document doc = new Document("strVal", otherTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_tube_other");
                VitalSignPayload payload = otherDrainageHandler.handle(doc, patient, planTimeDate, traceId);
                if (payload != null) {
                    if (admissionHoursToSeven > 0) {
                        payload.setVitalsignSVal1(String.valueOf(admissionHoursToSeven));
                    }
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
            if (ultraTotal.compareTo(BigDecimal.ZERO) >= 0) {
                Document doc = new Document("strVal", ultraTotal.stripTrailingZeros().toPlainString())
                        .append("code", "param_chaoLvLiang");
                VitalSignPayload payload = netUltrafiltrationHandler.handle(doc, patient, planTimeDate, traceId);
                if (payload != null) {
                    if (admissionHoursToSeven > 0) {
                        payload.setVitalsignSVal1(String.valueOf(admissionHoursToSeven));
                    }
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

        // 身高体重（R3: 以 admission_ward_time 为基准，7天周期）
        try {
            String hisPatientId = patient.getString("mrn");
            com.digixmed.cloud.icu.model.InpatientDTO inpatient = null;
            try {
                inpatient = inpatientRepository.findByPatientId(hisPatientId);
            } catch (Exception ex) {
                log.warn("MANUAL_SCAN traceId={} patientId={} 查入科时间失败: {}", traceId, hisPatientId, ex.getMessage());
            }
            if (inpatient != null && inpatient.getAdmissionWardTime() != null) {
                LocalDateTime admissionWardTime = inpatient.getAdmissionWardTime();
                java.util.Optional<LocalDateTime> sendTimeOpt = heightWeightHandler.planFor(
                        admissionWardTime, window.getStart().toLocalDate(), timeWindowService.now());
                if (sendTimeOpt.isPresent()) {
                    LocalDateTime hwSendTime = sendTimeOpt.get();
                    List<VitalSignPayload> hwPayloads = heightWeightHandler.buildPeriodic(
                            pid, admissionWardTime, hwSendTime, traceId);
                    for (VitalSignPayload hwPayload : hwPayloads) {
                        String action = (String) intermediateService.upsertPending(hwPayload, traceId).get("action");
                        if ("INSERT".equals(action)) inserted++;
                        else if ("SKIP".equals(action)) skipped++;
                        items.add(hwPayload.getVitalsignName() + ":" + action);
                    }
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

        // [DEBUG] 汇总完成日志
        log.info("IO_DEBUG traceId={} pid={} processDailySummary完成: inserted={} skipped={} failed={} items={}",
                traceId, pid, inserted, skipped, failed, items);

        return result;
    }

    /**
     * 查询 bedside 记录
     */
    private Document findBedside(String pid, String code, Date startTime, Date endTime) {
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(code)
                .and("time").gte(startTime).lt(endTime)
                .and("valid").ne(false).ne(0));
        Document doc = mongoTemplate.findOne(query, Document.class, "bedside");
        if (doc != null) {
            Object valid = doc.get("valid");
            if (Boolean.FALSE.equals(valid) || Integer.valueOf(0).equals(valid)) {
                return null;
            }
        }
        return doc;
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
     * 脱敏住院号（日志输出用）
     */
    private String maskPatientId(String patientId) {
        if (patientId == null || patientId.length() <= 4) {
            return "****";
        }
        return patientId.substring(0, 2) + "****" + patientId.substring(patientId.length() - 2);
    }

}
