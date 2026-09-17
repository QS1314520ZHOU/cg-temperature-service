package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.handler.*;
import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.repository.InpatientRepository;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import com.digixmed.cloud.icu.service.IntermediateService;
import com.digixmed.cloud.icu.service.IntakeOutputCalculator;
import com.digixmed.cloud.icu.service.DrugAmountCalculator;
import com.digixmed.cloud.icu.util.PayloadTimeNormalizer;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import com.digixmed.cloud.icu.model.InpatientDTO;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 每日07:00汇总任务
 *
 * 业务目的：每日07:00汇总前一天的出入量、大便次数等数据
 * 输入：KingbaseES在科患者列表、MongoDB bedside记录
 * 输出：IntermediateTable记录（status=PENDING）
 * 调度时间：每日07:00
 *
 * 统计窗口：[前一天07:00, 当天07:00)
 *
 * 所有数值：
 *   - 去除null、空白
 *   - 用BigDecimal解析
 *   - 非法值记录WARN后跳过
 *   - 最终统一去除无意义的末尾0
 *   - 禁止使用double累加
 *   - 禁止Math.round导致精度丢失
 *
 * 职责分离：
 *   - 本任务只负责采集和计算数据
 *   - 只写入中间表PENDING状态
 *   - 不进行HTTP/SOAP请求
 *   - 推送由PushTask负责
 */
@Component
public class DailySummaryTask {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryTask.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 病区编码，与 VitalSignScanTask 统一使用同一配置项 */
    @Value("${vitalsign.patient.ward-code:125011}")
    private String wardCode;

    /** 汇总扫描频率：每天 08:00 执行一次 */
    @Value("${vitalsign.summary.cron:0 0 8 * * ?}")
    private String summaryCron;

    /** 汇总回看天数（报表日） */
    @Value("${vitalsign.summary.lookback-days:1}")
    private int summaryLookbackDays;

    /** 变化检测回看天数：检测最近N天的数据变化，默认2天（昨天+前天） */
    @Value("${vitalsign.summary.check-lookback-days:2}")
    private int checkLookbackDays;

    @Value("${vitalsign.auto-enabled:false}")
    private boolean autoEnabled;

    @Autowired
    private ClinicalTimeWindowService timeWindowService;

    @Autowired
    private InpatientRepository inpatientRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private PatientIdentityMapper patientIdentityMapper;

    @Autowired
    private IntermediateService intermediateService;

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
    private IntakeOutputCalculator intakeOutputCalculator;

    @Autowired
    private DrugAmountCalculator drugAmountCalculator;

    @Autowired
    private PushTask pushTask;

    /**
     * 执行每日汇总
     */
    @Scheduled(cron = "${vitalsign.summary.cron:0 0 7 * * ?}")
    public void execute() {
        if (!autoEnabled) {
            log.warn("SUMMARY_SKIPPED autoEnabled=false, 自动汇总已关闭, 跳过本轮");
            return;
        }
        log.info("SUMMARY_TRIGGERED cron触发开始每日汇总");
        doSummary();
    }

    /** 原 execute() 的完整逻辑，供定时与手动共用 */
    public void doSummary() {
        String traceId = TraceIdGenerator.generate();
        LocalDateTime now = timeWindowService.now();
        LocalDate today = now.toLocalDate();
        log.info("STEP_01_PATIENT_SELECTED traceId={} 开始每日汇总 now={}", traceId, now);

        try {
            // 只处理昨天及以前的报表日，不处理今天（今天的窗口明天才关闭）
            List<LocalDate> reportDates = timeWindowService.getSummaryReportDates(now, summaryLookbackDays);
            // 过滤掉 today：今天的出入量窗口 [today 07:00, today+1 07:00) 尚未关闭
            reportDates.removeIf(d -> !d.isBefore(today));
            log.info("STEP_02_WINDOW_CREATED traceId={} 回看{}天，需处理报表日数量={} 日期={}",
                    traceId, summaryLookbackDays, reportDates.size(), reportDates);

            for (LocalDate reportDate : reportDates) {
                ClinicalTimeWindow window = timeWindowService.buildDailyWindow(reportDate);
                log.info("STEP_02_WINDOW_CREATED traceId={} 报表日={} 统计窗口=[{}, {})",
                        traceId, reportDate, window.getStart(), window.getEnd());

                // 准入原则：不再查金仓在科患者列表，只看 bedside 是否有数据 + patient 集合中是否存在该 _id
                List<String> pids = findCandidatePids(window, traceId);
                log.info("STEP_01_PATIENT_SELECTED traceId={} 报表日={} 候选患者数量={}", traceId, reportDate, pids.size());

                // O3: 批量查询患者（一次 $in 替代 N 次单条查询）
                Map<String, Document> patientMap = findMongoPatientsByPids(pids);

                for (String pid : pids) {
                    Document patient = patientMap.get(pid);
                    if (patient == null) {
                        log.info("STEP_01_PATIENT_SKIPPED traceId={} pid={} patient集合中不存在该_id，不回传", traceId, pid);
                        continue;
                    }
                    processPatientSummary(pid, patient, window, reportDate, traceId);
                }
            }

            log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 每日汇总完成", traceId);

            // 汇总完成后立即推送
            try {
                pushTask.pushOnce(traceId);
            } catch (Exception pe) {
                log.error("SUMMARY_PUSH traceId={} 汇总后立即推送异常", traceId, pe);
            }
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 每日汇总异常", traceId, e);
        }
    }

    // ======================== 变化检测（每小时） ========================

    /**
     * 每小时变化检测：重新计算昨天的出入量，如有变化则通过 upsertPending 触发撤销重发
     *
     * 执行时间：09:00 ~ 次日 06:00 每小时一次
     * 不在 07:00~08:59 执行，因为这段时间由 doSummary 主流程负责
     *
     * 原理：复用 processPatientSummary，upsertPending 内部比对 payloadHash：
     *   - hash 相同 + SUCCESS → SKIP（不重复推送）
     *   - hash 不同 → 设 FAILED → PushTask 自动走两步流程（isValid=0 旧值 → isValid=1 新值）
     */
    @Scheduled(cron = "${vitalsign.summary.check-cron:0 0 9-23,0-6 * * ?}")
    public void checkAndResendScheduled() {
        if (!autoEnabled) {
            return;
        }
        String traceId = TraceIdGenerator.generate();
        log.info("CHECK_RESEND traceId={} 开始变化检测", traceId);

        try {
            LocalDate today = timeWindowService.today();

            // 按配置的回看天数生成检测日期列表
            List<LocalDate> checkDates = new java.util.ArrayList<>();
            for (int i = 1; i <= checkLookbackDays; i++) {
                checkDates.add(today.minusDays(i));
            }
            log.info("CHECK_RESEND traceId={} 回看天数={} 检测日期={}", traceId, checkLookbackDays, checkDates);

            for (LocalDate checkDate : checkDates) {
                ClinicalTimeWindow window = timeWindowService.buildDailyWindow(checkDate);

                List<String> pids = findCandidatePids(window, traceId);
                log.info("CHECK_RESEND traceId={} 日期={} 候选患者数量={}", traceId, checkDate, pids.size());

                if (pids.isEmpty()) {
                    continue;
                }

                Map<String, Document> patientMap = findMongoPatientsByPids(pids);

                for (String pid : pids) {
                    Document patient = patientMap.get(pid);
                    if (patient == null) continue;
                    processPatientSummary(pid, patient, window, checkDate, traceId);
                }

                log.info("CHECK_RESEND traceId={} 日期={} 变化检测完成", traceId, checkDate);
            }

            // 变化检测后立即推送（如有变化的记录）
            try {
                pushTask.pushOnce(traceId);
            } catch (Exception pe) {
                log.error("CHECK_RESEND_PUSH traceId={} 变化检测后推送异常", traceId, pe);
            }
        } catch (Exception e) {
            log.error("CHECK_RESEND traceId={} 变化检测异常", traceId, e);
        }
    }

    /**
     * 手动触发单患者汇总
     *
     * @param mongoPid   Mongo patient._id
     * @param reportDate 报表日期
     * @param traceId    追踪ID
     * @return 本次登记的记录数
     */
    public int summarizeOnePatient(String mongoPid, LocalDate reportDate, String traceId) {
        Document patient = mongoTemplate.findOne(
                Query.query(Criteria.where("_id").is(new org.bson.types.ObjectId(mongoPid))),
                Document.class, "patient");
        if (patient == null) {
            log.warn("MANUAL traceId={} pid={} 患者不存在", traceId, mongoPid);
            return 0;
        }

        ClinicalTimeWindow window = timeWindowService.buildDailyWindow(reportDate);
        log.info("MANUAL traceId={} pid={} reportDate={} 统计窗口=[{}, {})",
                traceId, mongoPid, reportDate, window.getStart(), window.getEnd());

        enqueueCounter.set(0);
        processPatientSummary(mongoPid, patient, window, reportDate, traceId);
        return enqueueCounter.get();
    }

    /** 记录入队条数的计数器 */
    private final java.util.concurrent.atomic.AtomicInteger enqueueCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    private void processPatientSummary(String pid, Document patient, ClinicalTimeWindow window,
                                        LocalDate reportDate, String parentTraceId) {
        String hisPatientId = getValueFromDocByKey(patient, "mrn", String.class);
        String patientTraceId = TraceIdGenerator.generateWithPatient(pid);
        String patientIdMasked = maskPatientId(hisPatientId);

        try {
            Date startDate = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Date endDate = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

            // 入科第一天：计算入科时间到第二天07:00的小时数（取 patient.icuAdmissionTime）
            int admissionHoursToSeven = 0;
            Date icuAdmissionDate = patient.get("icuAdmissionTime") instanceof Date
                    ? (Date) patient.get("icuAdmissionTime") : null;
            if (icuAdmissionDate != null) {
                LocalDateTime admissionTime = icuAdmissionDate.toInstant()
                        .atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
                LocalDate admissionDate = admissionTime.toLocalDate();
                if (reportDate.equals(admissionDate)) {
                    long minutes = java.time.Duration.between(admissionTime, window.getEnd()).toMinutes();
                    // 1小时29分算1小时、1小时30分算2小时（满30分钟进1小时）
                    admissionHoursToSeven = (int) ((minutes + 30) / 60);
                    log.info("ADMISSION_HOURS traceId={} pid={} icuAdmissionTime={} hoursToSeven={}",
                            patientTraceId, pid, admissionTime, admissionHoursToSeven);
                    // 入科当天：bedside查询起点取入科时间所在的整点
                    // 因为系统按整点记录数据（如09:00-10:00的数据记录在09点），
                    // 入科时间09:32 → startDate调整为09:00，确保入科时间所在整点的数据能被计算
                    LocalDateTime admissionLocal = admissionTime;
                    LocalDateTime roundedTime = admissionLocal.withMinute(0).withSecond(0).withNano(0);
                    Date roundedDate = Date.from(roundedTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant());
                    if (roundedDate.after(startDate)) {
                        startDate = roundedDate;
                        log.info("ADMISSION_START_ADJUSTED traceId={} pid={} 入科时间={} startDate调整为整点={}",
                                patientTraceId, pid, admissionTime, roundedTime);
                    }
                }
            }

            // 查询窗口内所有bedside记录（左闭右开：time >= start AND time < end）
            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("valid").ne(false)
                    .and("time").gte(startDate).lt(endDate));
            List<Document> records = mongoTemplate.find(query, Document.class, "bedside");

            log.info("STEP_03_SOURCE_RECORDS_QUERIED traceId={} pid={} recordCount={}",
                    patientTraceId, pid, records.size());

            // 大便次数（不传小时数）
            processStoolCount(records, patient, pid, window, patientTraceId);

            // 小便量
            processUrineOutput(records, patient, pid, window, patientTraceId, admissionHoursToSeven);

            // 饮入量、治疗输入量、总输入量
            processIntakeAndOutput(records, patient, pid, window, patientTraceId, admissionHoursToSeven);

            // 总出量
            processTotalOutput(records, patient, pid, window, patientTraceId, admissionHoursToSeven);

            // 排出物量
            processDrainageOutput(records, patient, pid, window, patientTraceId, admissionHoursToSeven);

            // 胃管负压引流
            processGastricDrainage(records, patient, pid, window, patientTraceId, admissionHoursToSeven);

            // 其他引流量
            processOtherDrainage(records, patient, pid, window, patientTraceId, admissionHoursToSeven);

            // 净超滤量
            processNetUltrafiltration(records, patient, pid, window, patientTraceId, admissionHoursToSeven);

            // 身高体重（R3: 以 KingBase admission_ward_time 为基准，7天周期）
            com.digixmed.cloud.icu.model.InpatientDTO inpatient = null;
            try {
                inpatient = inpatientRepository.findByPatientId(hisPatientId);
            } catch (Exception e) {
                log.warn("STEP_12_HW traceId={} patientId={} 查入科时间失败: {}", patientTraceId, maskPatientId(hisPatientId), e.getMessage());
            }
            if (inpatient != null && inpatient.getAdmissionWardTime() != null) {
                LocalDateTime admissionWardTime = inpatient.getAdmissionWardTime();
                java.util.Optional<LocalDateTime> sendTimeOpt = heightWeightHandler.planFor(
                        admissionWardTime, reportDate, timeWindowService.now());
                if (sendTimeOpt.isPresent()) {
                    LocalDateTime sendTime = sendTimeOpt.get();
                    List<VitalSignPayload> hwPayloads = heightWeightHandler.buildPeriodic(
                            pid, admissionWardTime, sendTime, patientTraceId);
                    for (VitalSignPayload hwPayload : hwPayloads) {
                        intermediateService.upsertPending(hwPayload, patientTraceId);
                        enqueueCounter.incrementAndGet();
                    }
                }
            }

        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 汇总异常", patientTraceId, e);
        }
    }

    /**
     * 处理大便次数
     * 只使用 param_汇总大便次数，只获取 window.end 对应的07:00精确时刻的数据
     *
     * 业务说明：
     *   - 统计窗口 [window.start, window.end) 中，大便次数数据位于 window.end 的07:00时刻
     *   - 查询时间：window.end（次日07:00）
     *   - planTime：window.end
     *   - 幂等键：使用 window.end 作为 planTime
     *   - 无记录时不回传（不再回传0值）
     */
    private void processStoolCount(List<Document> records, Document patient, String pid,
                                    ClinicalTimeWindow window, String traceId) {
        LocalDateTime point = window.getEnd();
        Date pointDate = Date.from(point.atZone(ZONE).toInstant());

        log.info("STOOL_QUERY traceId={} pid={} reportDate={} window=[{}, {}) point={}",
                traceId,
                pid,
                window.getReportDate(),
                window.getStart(),
                window.getEnd(),
                point);

        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is("param_汇总大便次数")
                .and("valid").ne(false)
                .and("time").is(pointDate))
                .with(Sort.by(Sort.Direction.DESC, "editTime"))
                .limit(1);
        Document stoolRecord = mongoTemplate.findOne(query, Document.class, "bedside");

        // 无记录时不回传
        if (stoolRecord == null) {
            log.info("STOOL_SKIP traceId={} pid={} point={} 无大便次数记录，跳过回传",
                    traceId, pid, point);
            return;
        }

        Object bedsideId = stoolRecord.get("_id");
        Object strVal = stoolRecord.get("strVal");
        log.info("STOOL_FOUND traceId={} pid={} point={} bedsideId={} strVal={}",
                traceId, pid, point, bedsideId, strVal);

        String stoolValue = "0";

        // 优先使用 strVal，strVal 为空时尝试 fVal
        if (strVal != null && !strVal.toString().trim().isEmpty()) {
            stoolValue = strVal.toString().trim();
        } else {
            Object fValObj = stoolRecord.get("fVal");
            if (fValObj instanceof Number) {
                stoolValue = new BigDecimal(((Number) fValObj).toString())
                        .stripTrailingZeros().toPlainString();
                log.info("STOOL_USE_FVAL traceId={} pid={} fVal={} stoolValue={}",
                        traceId, pid, fValObj, stoolValue);
            } else {
                log.info("STOOL_EMPTY_VAL traceId={} pid={} strVal={} fVal={} 跳过回传",
                        traceId, pid, strVal, fValObj);
                return;
            }
        }

        // 有记录时使用记录时间
        Date recordTime = pointDate;
        Object editTimeObj = stoolRecord.get("editTime");
        if (editTimeObj instanceof Date) {
            recordTime = (Date) editTimeObj;
        } else {
            Object timeObj = stoolRecord.get("time");
            if (timeObj instanceof Date) {
                recordTime = (Date) timeObj;
            }
        }

        // 构造虚拟 Document，确保 handler 能处理
        Document vDoc = new Document();
        vDoc.append("strVal", stoolValue);
        vDoc.append("code", "param_汇总大便次数");
        vDoc.append("time", pointDate);
        vDoc.append("valid", true);
        if (bedsideId != null) {
            vDoc.append("_id", bedsideId);
        }

        VitalSignPayload payload = stoolCountHandler.handle(vDoc, patient, point, traceId);
        if (payload == null) {
            log.warn("STOOL_PAYLOAD_NULL traceId={} pid={} point={} bedsideId={}",
                    traceId, pid, point, bedsideId);
            return;
        }

        PayloadTimeNormalizer.anchor(payload, point);
        Map<String, Object> result = intermediateService.upsertPending(payload, traceId);
        String action = result != null ? String.valueOf(result.get("action")) : "UNKNOWN";
        if (!"SKIP".equals(action)) {
            enqueueCounter.incrementAndGet();
        }
        log.info("STOOL_ENQUEUED traceId={} pid={} point={} bedsideId={} value={} action={} result={}",
                traceId, pid, point, bedsideId, stoolValue, action, result);
    }

    /**
     * 构造虚拟 Document 并设置 time 字段，保证 handler 取到 planTime
     */
    private Document virtualDoc(String strVal, String code, ClinicalTimeWindow window) {
        Document doc = new Document();
        doc.append("strVal", strVal);
        doc.append("code", code);
        Date time = Date.from(window.getEnd().atZone(ZONE).toInstant());
        doc.append("time", time);
        return doc;
    }

    /**
     * 调用 handler 构建 payload 并写入队列
     */
    private void enqueue(BaseVitalSignHandler handler, Document doc, Document patient,
                         ClinicalTimeWindow window, String traceId) {
        enqueueWithHours(handler, doc, patient, window, traceId, 0);
    }

    /**
     * 调用 handler 构建 payload 并写入队列，入科第一天设置 vitalsignSVal1 = 小时数
     * 入队计数只统计实际入队的情况（INSERT, CONTENT_CHANGED, RETRY），跳过 SKIP
     */
    private void enqueueWithHours(BaseVitalSignHandler handler, Document doc, Document patient,
                                  ClinicalTimeWindow window, String traceId, int admissionHoursToSeven) {
        VitalSignPayload payload = handler.handle(doc, patient, window.getEnd(), traceId);
        if (payload == null) {
            String code = getValueFromDocByKey(doc, "code", String.class);
            String pid = getValueFromDocByKey(patient, "_id", String.class);
            log.warn("ENQUEUE_NULL traceId={} handler={} code={} pid={} 返回null，未入队",
                    traceId, handler.getClass().getSimpleName(), code, pid);
            return;
        }

        // 出入量护士解析：从 param_Yishi 往前搜8小时，兜底 responsibleNurse
        resolveNurseForPayload(payload, patient, window, traceId);

        PayloadTimeNormalizer.anchor(payload, window.getEnd());
        if (admissionHoursToSeven > 0) {
            payload.setVitalsignSVal1(String.valueOf(admissionHoursToSeven));
        }
        Map<String, Object> result = intermediateService.upsertPending(payload, traceId);
        String action = result != null ? String.valueOf(result.get("action")) : "UNKNOWN";
        if (!"SKIP".equals(action)) {
            enqueueCounter.incrementAndGet();
        }
        log.info("ENQUEUE_DONE traceId={} handler={} code={} pid={} planTime={} value={} action={} status={}",
                traceId, handler.getClass().getSimpleName(),
                payload.getVitalsignType(),
                getValueFromDocByKey(patient, "_id", String.class),
                payload.getPlanTime(),
                payload.getVitalsignNVal1() != null ? payload.getVitalsignNVal1() : payload.getVitalsignSVal2(),
                action,
                result != null ? result.get("status") : "UNKNOWN");
    }

    /**
     * 出入量护士解析：从 param_Yishi 往前搜8小时，兜底 responsibleNurse
     */
    private void resolveNurseForPayload(VitalSignPayload payload, Document patient,
                                         ClinicalTimeWindow window, String traceId) {
        String pid = getValueFromDocByKey(patient, "_id", String.class);
        if (pid == null) {
            return;
        }

        Date searchBefore = Date.from(window.getEnd().atZone(ZONE).toInstant());
        Date searchStart = Date.from(searchBefore.toInstant().minus(java.time.Duration.ofHours(8)));

        // 1. 从 param_Yishi 往前搜8小时
        try {
            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("code").is("param_Yishi")
                    .and("valid").ne(false).ne(0)
                    .and("time").gte(searchStart).lt(searchBefore))
                    .with(Sort.by(Sort.Direction.DESC, "time"))
                    .limit(1);
            Document yishiDoc = mongoTemplate.findOne(query, Document.class, "bedside");
            if (yishiDoc != null) {
                Object valid = yishiDoc.get("valid");
                if (Boolean.FALSE.equals(valid) || Integer.valueOf(0).equals(valid)) {
                    yishiDoc = null;
                }
            }
            if (yishiDoc != null) {
                Object editUserObj = yishiDoc.get("editUser");
                if (editUserObj != null) {
                    String editUser = editUserObj.toString().trim();
                    if (!editUser.isEmpty()) {
                        try {
                            org.bson.types.ObjectId accountId = new org.bson.types.ObjectId(editUser);
                            Query accountQuery = new Query(Criteria.where("_id").is(accountId));
                            Document account = mongoTemplate.findOne(accountQuery, Document.class, "account");
                            if (account != null) {
                                String username = account.getString("username");
                                String trueName = account.getString("trueName");
                                if (trueName != null && !trueName.trim().isEmpty()) {
                                    payload.setRecordNurseId(username != null ? username.trim() : "");
                                    payload.setRecordNurseName(trueName.trim());
                                    log.info("INTAKE_NURSE traceId={} pid={} nurseId={} nurseName={} source=param_Yishi",
                                            traceId, pid, payload.getRecordNurseId(), payload.getRecordNurseName());
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            log.warn("INTAKE_NURSE traceId={} pid={} 查询account失败: {}", traceId, pid, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("INTAKE_NURSE traceId={} pid={} 搜索param_Yishi异常: {}", traceId, pid, e.getMessage());
        }

        // 2. 兜底：patient.responsibleNurse
        Object responsibleObj = patient.get("responsibleNurse");
        if (responsibleObj != null) {
            String responsibleName = responsibleObj.toString().trim();
            if (!responsibleName.isEmpty()) {
                try {
                    Query query = new Query(Criteria.where("trueName").is(responsibleName)).limit(1);
                    Document account = mongoTemplate.findOne(query, Document.class, "account");
                    if (account != null) {
                        String username = account.getString("username");
                        if (username != null && !username.trim().isEmpty()) {
                            payload.setRecordNurseId(username.trim());
                            payload.setRecordNurseName(responsibleName);
                            log.info("INTAKE_NURSE traceId={} pid={} nurseId={} nurseName={} source=responsibleNurse",
                                    traceId, pid, payload.getRecordNurseId(), payload.getRecordNurseName());
                            return;
                        }
                    }
                } catch (Exception e) {
                    log.warn("INTAKE_NURSE traceId={} pid={} responsibleNurse查询异常: {}", traceId, pid, e.getMessage());
                }
            }
        }

        log.info("INTAKE_NURSE traceId={} pid={} 所有解析方式均失败，使用默认值", traceId, pid);
    }

    /**
     * 按多个code求和
     */
    private BigDecimal sumByCodes(List<Document> records, List<String> codes) {
        return records.stream()
                .filter(doc -> {
                    String code = getValueFromDocByKey(doc, "code", String.class);
                    return code != null && codes.contains(code);
                })
                .map(doc -> {
                    String val = getValueFromDocByKey(doc, "strVal", String.class);
                    try {
                        return val != null ? new BigDecimal(val.trim()) : BigDecimal.ZERO;
                    } catch (NumberFormatException e) {
                        return BigDecimal.ZERO;
                    }
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 检查是否有任何匹配指定code的记录 */
    private boolean hasAnyRecord(List<Document> records, List<String> codes) {
        return records.stream().anyMatch(doc -> {
            String code = getValueFromDocByKey(doc, "code", String.class);
            return code != null && codes.contains(code);
        });
    }

    /**
     * 从 Document 中解析金额/数量值，优先 strVal，其次 fVal，都不合法则返回 ZERO
     *
     * 优先级：
     *   1. strVal 非空且可解析为 BigDecimal → 返回该值
     *   2. fVal 非空且为 Number → 转 BigDecimal 返回
     *   3. 都不可用 → 返回 BigDecimal.ZERO
     */
    private BigDecimal parseAmountOrZero(Document doc, String traceId) {
        String strVal = getValueFromDocByKey(doc, "strVal", String.class);
        if (strVal != null && !strVal.trim().isEmpty()) {
            try {
                return new BigDecimal(strVal.trim());
            } catch (NumberFormatException e) {
                log.warn("PARSE_AMOUNT traceId={} strVal={} 无法解析为数字", traceId, strVal);
            }
        }
        Object fValObj = doc.get("fVal");
        if (fValObj instanceof Number) {
            try {
                return new BigDecimal(((Number) fValObj).toString());
            } catch (NumberFormatException e) {
                log.warn("PARSE_AMOUNT traceId={} fVal={} 无法解析为数字", traceId, fValObj);
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * 处理小便量
     * 无记录时回传 0
     */
    private void processUrineOutput(List<Document> records, Document patient, String pid,
                                     ClinicalTimeWindow window, String traceId, int hours) {
        List<Document> matched = records.stream()
                .filter(doc -> "param_niaoLiang".equals(getValueFromDocByKey(doc, "code", String.class)))
                .collect(Collectors.toList());

        BigDecimal total = BigDecimal.ZERO;
        for (Document doc : matched) {
            total = total.add(parseAmountOrZero(doc, traceId));
        }

        Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_niaoLiang", window);
        enqueueWithHours(urineOutputHandler, vDoc, patient, window, traceId, hours);
    }

    /**
     * 处理饮入量、治疗输入量、总输入量
     *
     * 对齐护理记录单口径：
     * 饮入量(1044) = param_kouFu + param_biSi + param_YaoStomach_in_hour（保持原口径）
     * 输入量(1045) = param_带入药量 + param_YaoYeti_in_hour + param_YaoShuXue_in_hour（保持原口径）
     * 总入量(1009) = 药物治疗 + 胃肠摄入（新口径，对齐护理记录单）
     *   药物治疗 = 带入药量(bedside) + 静脉入量(drugExe: 输血 + 各静脉途径)
     *   胃肠摄入 = 鼻饲量(bedside手工 + drugExe肠内营养泵入) + 胃肠入量(bedside口服 + drugExe po等)
     *
     * 无来源记录或最终结果为0时，回传0
     */
    private void processIntakeAndOutput(List<Document> records, Document patient, String pid,
                                         ClinicalTimeWindow window, String traceId, int hours) {
        // 1044/1045 保持原口径
        List<String> oralCodes = OralIntakeHandler.getOralIntakeCodes();
        List<String> therapyCodes = TherapyInputHandler.getTherapyInputCodes();

        // 饮入量 1044（无记录时回传0）
        BigDecimal oralTotal = sumByCodes(records, oralCodes);
        Document oralDoc = virtualDoc(oralTotal.stripTrailingZeros().toPlainString(), "param_biSi", window);
        enqueueWithHours(oralIntakeHandler, oralDoc, patient, window, traceId, hours);

        // 输入量 1045（无记录时回传0）
        BigDecimal therapyTotal = sumByCodes(records, therapyCodes);
        Document therapyDoc = virtualDoc(therapyTotal.stripTrailingZeros().toPlainString(), "param_YaoYeti_in_hour", window);
        enqueueWithHours(therapyInputHandler, therapyDoc, patient, window, traceId, hours);

        // 总入量 1009（无记录时回传0）
        Date startDate = Date.from(window.getStart().atZone(ZONE).toInstant());
        Date endDate = Date.from(window.getEnd().atZone(ZONE).toInstant());

        List<Document> drugExecutions = drugAmountCalculator.queryDrugExe(pid, startDate, endDate);
        List<Document> drugMethods = drugAmountCalculator.queryDrugMethods();
        DrugAmountCalculator.DrugChannelTotals drugChannelTotals =
                drugAmountCalculator.sumDrugAmountsByChannel(
                        drugExecutions, drugMethods,
                        startDate.getTime(), endDate.getTime(), true);

        BigDecimal totalInput = intakeOutputCalculator.sumTotalInput(
                records, drugChannelTotals, traceId, pid);

        Document inputDoc = virtualDoc(totalInput.stripTrailingZeros().toPlainString(), "param_zongRuliang", window);
        enqueueWithHours(totalInputHandler, inputDoc, patient, window, traceId, hours);
    }

    /**
     * 处理总出量（对齐护理记录单口径）
     * 总出量(1010) = 尿量 + 净超滤量 + 排出物 + 引流液
     *   排出物：param_daBianAmount, param_造瘘口量, param_outuwuliang, param_咯血, param_tanLiang
     *   引流液：code含"引流" OR code==="param_tube_胃肠减压"
     * 无来源记录时结果为0并回传
     */
    private void processTotalOutput(List<Document> records, Document patient, String pid,
                                     ClinicalTimeWindow window, String traceId, int hours) {
        BigDecimal total = intakeOutputCalculator.sumTotalOutput(records, traceId, pid);
        Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_zongChuLiang", window);
        enqueueWithHours(totalOutputHandler, vDoc, patient, window, traceId, hours);
    }

    /**
     * 处理排出物量
     * 无记录时不回传
     */
    private void processDrainageOutput(List<Document> records, Document patient, String pid,
                                        ClinicalTimeWindow window, String traceId, int hours) {
        List<String> drainageCodes = DrainageOutputHandler.getDrainageCodes();
        BigDecimal total = BigDecimal.ZERO;
        for (Document doc : records) {
            String code = getValueFromDocByKey(doc, "code", String.class);
            if (code != null && drainageCodes.contains(code)) {
                total = total.add(parseAmountOrZero(doc, traceId));
            }
        }

        if (total.compareTo(BigDecimal.ZERO) == 0) {
            log.info("DRAINAGE_OUTPUT_SKIP traceId={} pid={} 无记录，跳过回传", traceId, pid);
            return;
        }
        Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_daBianAmount", window);
        enqueueWithHours(drainageOutputHandler, vDoc, patient, window, traceId, hours);
    }

    /**
     * 处理胃管负压引流
     * 无记录时不回传
     */
    private void processGastricDrainage(List<Document> records, Document patient, String pid,
                                         ClinicalTimeWindow window, String traceId, int hours) {
        BigDecimal total = BigDecimal.ZERO;
        for (Document doc : records) {
            if ("param_tube_胃肠减压".equals(getValueFromDocByKey(doc, "code", String.class))) {
                total = total.add(parseAmountOrZero(doc, traceId));
            }
        }

        if (total.compareTo(BigDecimal.ZERO) == 0) {
            log.info("GASTRIC_DRAINAGE_SKIP traceId={} pid={} 无记录，跳过回传", traceId, pid);
            return;
        }
        Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_tube_胃肠减压", window);
        enqueueWithHours(gastricDrainageHandler, vDoc, patient, window, traceId, hours);
    }

    /**
     * 处理其他引流量
     * 无记录时不回传
     */
    private void processOtherDrainage(List<Document> records, Document patient, String pid,
                                       ClinicalTimeWindow window, String traceId, int hours) {
        BigDecimal total = BigDecimal.ZERO;
        for (Document doc : records) {
            String code = getValueFromDocByKey(doc, "code", String.class);
            if (code != null && code.contains("_tube_") && !"param_tube_胃肠减压".equals(code)) {
                total = total.add(parseAmountOrZero(doc, traceId));
            }
        }

        if (total.compareTo(BigDecimal.ZERO) == 0) {
            log.info("OTHER_DRAINAGE_SKIP traceId={} pid={} 无记录，跳过回传", traceId, pid);
            return;
        }
        Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_tube_other", window);
        enqueueWithHours(otherDrainageHandler, vDoc, patient, window, traceId, hours);
    }

    /**
     * 处理净超滤量
     * 无记录时不回传
     */
    private void processNetUltrafiltration(List<Document> records, Document patient, String pid,
                                            ClinicalTimeWindow window, String traceId, int hours) {
        BigDecimal total = BigDecimal.ZERO;
        for (Document doc : records) {
            if ("param_chaoLvLiang".equals(getValueFromDocByKey(doc, "code", String.class))) {
                total = total.add(parseAmountOrZero(doc, traceId));
            }
        }

        if (total.compareTo(BigDecimal.ZERO) == 0) {
            log.info("NET_ULTRAFILTRATION_SKIP traceId={} pid={} 无记录，跳过回传", traceId, pid);
            return;
        }
        Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_chaoLvLiang", window);
        enqueueWithHours(netUltrafiltrationHandler, vDoc, patient, window, traceId, hours);
    }

    /**
     * 本轮候选患者：统计窗口内有 bedside 数据的 pid + 归档点有大便次数记录的 pid（去重）。
     * 不依赖金仓在科列表，是否回传只看 patient 集合中是否存在该 _id。
     *
     * 两个集合的并集：
     *   A. 统计窗口内 bedside 记录：time >= window.start AND time < window.end
     *   B. 归档点的大便次数记录：code = param_汇总大便次数 AND time = window.end AND valid != false AND valid != 0
     */
    private List<String> findCandidatePids(ClinicalTimeWindow window, String traceId) {
        try {
            Date startDate = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Date endDate = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

            // A. 统计窗口内 bedside 记录
            Query queryA = new Query(Criteria.where("time").gte(startDate).lt(endDate));
            queryA.fields().include("pid");
            List<Document> docsA = mongoTemplate.find(queryA, Document.class, "bedside");

            Set<String> pidSet = new LinkedHashSet<>();
            for (Document doc : docsA) {
                String pid = getValueFromDocByKey(doc, "pid", String.class);
                if (pid != null && !pid.isEmpty()) {
                    pidSet.add(pid);
                }
            }

            log.info("STEP_01_PATIENT_SELECTED traceId={} 窗口=[{}, {}) A集合bedside命中pid数量={}",
                    traceId, window.getStart(), window.getEnd(), pidSet.size());

            // B. 归档点的大便次数记录（time = window.end）
            try {
                Criteria stoolCriteria = Criteria.where("code").is("param_汇总大便次数")
                        .and("time").is(endDate)
                        .and("valid").ne(false);
                Query queryB = new Query(stoolCriteria);
                queryB.fields().include("pid");
                List<Document> docsB = mongoTemplate.find(queryB, Document.class, "bedside");

                int bBefore = pidSet.size();
                for (Document doc : docsB) {
                    String pid = getValueFromDocByKey(doc, "pid", String.class);
                    if (pid != null && !pid.isEmpty()) {
                        pidSet.add(pid);
                    }
                }
                log.info("STEP_01_PATIENT_SELECTED traceId={} B集合大便次数命中pid数量={} 新增={}",
                        traceId, docsB.size(), pidSet.size() - bBefore);
            } catch (Exception e) {
                log.warn("STEP_01_PATIENT_SELECTED traceId={} B集合大便次数查询异常: {}", traceId, e.getMessage());
            }

            List<String> pids = new ArrayList<>(pidSet);
            log.info("STEP_01_PATIENT_SELECTED traceId={} 窗口=[{}, {}) 合并后候选pid数量={}",
                    traceId, window.getStart(), window.getEnd(), pids.size());

            // 过滤掉入科时间在窗口结束之后的患者
            // 过滤掉已出科的患者（使用 MongoDB patient 的 status 和 icuDischargeTime）
            if (!pids.isEmpty()) {
                Map<String, Document> patientMap = findMongoPatientsByPids(pids);

                int beforeFilter = pids.size();
                pids.removeIf(pid -> {
                    Document patient = patientMap.get(pid);
                    if (patient == null) {
                        return true; // patient不存在，过滤掉
                    }
                    Date icuAdmissionTime = patient.get("icuAdmissionTime") instanceof Date
                            ? (Date) patient.get("icuAdmissionTime") : null;
                    if (icuAdmissionTime == null) {
                        return false; // 无入科时间，保留
                    }
                    LocalDateTime admissionDateTime = icuAdmissionTime.toInstant()
                            .atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime();

                    // 入科时间在窗口结束之后（即入科时间 >= windowEnd），过滤掉
                    if (!admissionDateTime.isBefore(window.getEnd())) {
                        log.info("STEP_01_PATIENT_FILTERED traceId={} pid={} 入科时间={} 在窗口结束={}之后，跳过汇总",
                                traceId, pid, admissionDateTime, window.getEnd());
                        return true;
                    }

                    // 出科判断：使用 MongoDB patient 的 status 和 icuDischargeTime
                    String patientStatus = getValueFromDocByKey(patient, "status", String.class);
                    if ("discharged".equals(patientStatus)) {
                        Date icuDischargeDate = patient.get("icuDischargeTime") instanceof Date
                                ? (Date) patient.get("icuDischargeTime") : null;
                        if (icuDischargeDate != null) {
                            LocalDateTime dischargeTime = icuDischargeDate.toInstant()
                                    .atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
                            if (dischargeTime.isBefore(window.getEnd())) {
                                log.info("STEP_01_PATIENT_FILTERED traceId={} pid={} 出科时间={} status={} 在窗口结束={}之前，跳过汇总",
                                        traceId, pid, dischargeTime, patientStatus, window.getEnd());
                                return true;
                            }
                        }
                    }

                    return false;
                });
                log.info("STEP_01_PATIENT_SELECTED traceId={} 过滤入科出科时间后候选pid数量={} 过滤了{}个",
                        traceId, pids.size(), beforeFilter - pids.size());
            }

            return pids;
        } catch (Exception e) {
            log.error("STEP_01_PATIENT_SELECTED traceId={} 查询候选患者异常", traceId, e);
            return Collections.emptyList();
        }
    }

    /** 通过 pid 查询 MongoDB patient 文档；pid 非法或不存在返回 null */
    private Document findMongoPatientByPid(String pid) {
        try {
            Query query = new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(pid)));
            return mongoTemplate.findOne(query, Document.class, "patient");
        } catch (Exception e) {
            log.warn("findMongoPatientByPid pid={} 查询异常: {}", pid, e.getMessage());
            return null;
        }
    }

    /** O3: 批量查询 MongoDB patient 文档（一次 $in 替代 N 次单条查询） */
    private Map<String, Document> findMongoPatientsByPids(List<String> pids) {
        if (pids == null || pids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<org.bson.types.ObjectId> objectIds = new java.util.ArrayList<>();
        for (String pid : pids) {
            try {
                objectIds.add(new org.bson.types.ObjectId(pid));
            } catch (Exception e) {
                log.warn("findMongoPatientsByPids pid={} ObjectId转换失败，跳过", pid);
            }
        }
        if (objectIds.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Query query = new Query(Criteria.where("_id").in(objectIds));
            List<Document> patients = mongoTemplate.find(query, Document.class, "patient");
            Map<String, Document> map = new java.util.LinkedHashMap<>();
            for (Document p : patients) {
                Object id = p.get("_id");
                if (id != null) {
                    map.put(id.toString(), p);
                }
            }
            log.info("批量查询患者完成，请求{}条，返回{}条", objectIds.size(), map.size());
            return map;
        } catch (Exception e) {
            log.warn("findMongoPatientsByPids 批量查询异常: {}", e.getMessage());
            // 降级为逐条查询
            Map<String, Document> map = new java.util.LinkedHashMap<>();
            for (String pid : pids) {
                Document p = findMongoPatientByPid(pid);
                if (p != null) map.put(pid, p);
            }
            return map;
        }
    }

    private Document findMongoPatient(String patientId) {
        // 通过mrn或hisPid查询（OR逻辑）
        Query query = new Query(new Criteria().orOperator(
                Criteria.where("mrn").is(patientId),
                Criteria.where("hisPid").is(patientId)
        ));
        return mongoTemplate.findOne(query, Document.class, "patient");
    }

    private String getMongoPid(Document patient) {
        Object id = patient.get("_id");
        return id != null ? id.toString() : null;
    }

    private <T> T getValueFromDocByKey(Document doc, String key, Class<T> clazz) {
        if (doc == null) return null;
        Object value = doc.get(key);
        if (value == null) return null;
        if (clazz.isInstance(value)) return clazz.cast(value);
        if (clazz == String.class) return (T) value.toString();
        return null;
    }

    private String maskPatientId(String patientId) {
        if (patientId == null || patientId.length() <= 4) return "****";
        return patientId.substring(0, 2) + "****" + patientId.substring(patientId.length() - 2);
    }
}
