package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.handler.*;
import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.repository.InpatientRepository;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import com.digixmed.cloud.icu.service.HeightWeightNurseService;
import com.digixmed.cloud.icu.service.HeightWeightNurseService.NurseRef;
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
    private HeightWeightNurseService heightWeightNurseService;

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
            LocalDate yesterday = today.minusDays(1);

            // 每小时检测两天的数据：昨天 + 今天
            // 昨天：[yesterday 07:00, today 07:00) — 已有完整数据
            // 今天：[today 07:00, tomorrow 07:00) — 捕获07:00后新写入的记录
            List<LocalDate> checkDates = Arrays.asList(yesterday, today);

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

            // 查询窗口内所有bedside记录（左开右闭：time > start AND time <= end）
            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("valid").ne(false)
                    .and("time").gt(startDate).lte(endDate));
            List<Document> records = mongoTemplate.find(query, Document.class, "bedside");

            log.info("STEP_03_SOURCE_RECORDS_QUERIED traceId={} pid={} recordCount={}",
                    patientTraceId, pid, records.size());

            // 大便次数
            processStoolCount(records, patient, pid, window, patientTraceId);

            // 小便量
            processUrineOutput(records, patient, pid, window, patientTraceId);

            // 饮入量、治疗输入量、总输入量
            processIntakeAndOutput(records, patient, pid, window, patientTraceId);

            // 总出量
            processTotalOutput(records, patient, pid, window, patientTraceId);

            // 排出物量
            processDrainageOutput(records, patient, pid, window, patientTraceId);

            // 胃管负压引流
            processGastricDrainage(records, patient, pid, window, patientTraceId);

            // 其他引流量
            processOtherDrainage(records, patient, pid, window, patientTraceId);

            // 净超滤量
            processNetUltrafiltration(records, patient, pid, window, patientTraceId);

            // 身高体重（R3: 以 admission_ward_time 为基准，7天周期）
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
     * 只使用 param_汇总大便次数，只获取07:00精确时刻的数据
     */
    private void processStoolCount(List<Document> records, Document patient, String pid,
                                    ClinicalTimeWindow window, String traceId) {
        LocalDateTime point = timeWindowService.buildSevenAmPoint(window.getReportDate().toLocalDate());
        Date start = Date.from(point.atZone(ZONE).toInstant());
        Date end = new Date(start.getTime() + 1);

        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is("param_汇总大便次数")
                .and("valid").ne(false)
                .and("time").gte(start).lt(end))
                .with(Sort.by(Sort.Direction.DESC, "editTime"))
                .limit(1);
        Document stoolRecord = mongoTemplate.findOne(query, Document.class, "bedside");

        if (stoolRecord == null) {
            log.info("STEP_03 traceId={} pid={} 07:00时刻无汇总大便次数记录", traceId, pid);
            return;
        }
        VitalSignPayload payload = stoolCountHandler.handle(stoolRecord, patient,
                window.getEnd(), traceId);
        if (payload != null) {
            PayloadTimeNormalizer.anchor(payload, window.getEnd());
            intermediateService.upsertPending(payload, traceId);
            enqueueCounter.incrementAndGet();
        }
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
        VitalSignPayload payload = handler.handle(doc, patient, window.getEnd(), traceId);
        if (payload == null) {
            String code = getValueFromDocByKey(doc, "code", String.class);
            String pid = getValueFromDocByKey(patient, "_id", String.class);
            log.warn("ENQUEUE_NULL traceId={} handler={} code={} pid={} 返回null，未入队",
                    traceId, handler.getClass().getSimpleName(), code, pid);
            return;
        }
        PayloadTimeNormalizer.anchor(payload, window.getEnd());
        intermediateService.upsertPending(payload, traceId);
        enqueueCounter.incrementAndGet();
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
     * 处理小便量
     */
    private void processUrineOutput(List<Document> records, Document patient, String pid,
                                     ClinicalTimeWindow window, String traceId) {
        // 只处理有 param_niaoLiang 记录的患者（用户填写了才回传）
        List<Document> matched = records.stream()
                .filter(doc -> "param_niaoLiang".equals(getValueFromDocByKey(doc, "code", String.class)))
                .collect(Collectors.toList());
        if (matched.isEmpty()) {
            return;
        }
        BigDecimal total = matched.stream()
                .map(doc -> {
                    String val = getValueFromDocByKey(doc, "strVal", String.class);
                    try {
                        return val != null ? new BigDecimal(val.trim()) : BigDecimal.ZERO;
                    } catch (NumberFormatException e) {
                        log.warn("STEP_05_VALUE_PARSED traceId={} 无法解析尿量值: {}", traceId, val);
                        return BigDecimal.ZERO;
                    }
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_niaoLiang", window);
        enqueue(urineOutputHandler, vDoc, patient, window, traceId);
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
     */
    private void processIntakeAndOutput(List<Document> records, Document patient, String pid,
                                         ClinicalTimeWindow window, String traceId) {
        // 1044/1045 保持原口径
        List<String> oralCodes = OralIntakeHandler.getOralIntakeCodes();
        List<String> therapyCodes = TherapyInputHandler.getTherapyInputCodes();

        // 饮入量 1044（有记录才回传）
        if (hasAnyRecord(records, oralCodes)) {
            BigDecimal oralTotal = sumByCodes(records, oralCodes);
            Document vDoc = virtualDoc(oralTotal.stripTrailingZeros().toPlainString(), "param_biSi", window);
            enqueue(oralIntakeHandler, vDoc, patient, window, traceId);
        }

        // 输入量 1045（有记录才回传）
        if (hasAnyRecord(records, therapyCodes)) {
            BigDecimal therapyTotal = sumByCodes(records, therapyCodes);
            Document vDoc = virtualDoc(therapyTotal.stripTrailingZeros().toPlainString(), "param_YaoYeti_in_hour", window);
            enqueue(therapyInputHandler, vDoc, patient, window, traceId);
        }

        // 总入量 1009（新口径：对齐护理记录单）
        Date startDate = Date.from(window.getStart().atZone(ZONE).toInstant());
        Date endDate = Date.from(window.getEnd().atZone(ZONE).toInstant());

        // 检查是否有任何入量相关记录（bedside 或 drugExecution）
        List<String> allInputCodes = new java.util.ArrayList<>();
        allInputCodes.addAll(OralIntakeHandler.getOralIntakeCodes());
        allInputCodes.addAll(TherapyInputHandler.getTherapyInputCodes());
        boolean hasInputRecords = hasAnyRecord(records, allInputCodes);
        List<Document> drugExecutions = drugAmountCalculator.queryDrugExe(pid, startDate, endDate);
        if (!hasInputRecords && drugExecutions.isEmpty()) {
            return;
        }

        List<Document> drugMethods = drugAmountCalculator.queryDrugMethods();
        DrugAmountCalculator.DrugChannelTotals drugChannelTotals =
                drugAmountCalculator.sumDrugAmountsByChannel(
                        drugExecutions, drugMethods,
                        startDate.getTime(), endDate.getTime(), true);

        BigDecimal totalInput = intakeOutputCalculator.sumTotalInput(
                records, drugChannelTotals, traceId, pid);

        Document vDoc = virtualDoc(totalInput.stripTrailingZeros().toPlainString(), "param_zongRuliang", window);
        enqueue(totalInputHandler, vDoc, patient, window, traceId);
    }

    /**
     * 处理总出量（对齐护理记录单口径）
     * 总出量(1010) = 尿量 + 净超滤量 + 排出物 + 引流液
     *   排出物：param_daBianAmount, param_造瘘口量, param_outuwuliang, param_咯血, param_tanLiang
     *   引流液：code含"引流" OR code==="param_tube_胃肠减压"
     */
    private void processTotalOutput(List<Document> records, Document patient, String pid,
                                     ClinicalTimeWindow window, String traceId) {
        // 总出量组成：尿量、净超滤量、排出物、引流液
        List<String> allOutputCodes = new java.util.ArrayList<>();
        allOutputCodes.add("param_niaoLiang");
        allOutputCodes.add("param_chaoLvLiang");
        allOutputCodes.addAll(DrainageOutputHandler.getDrainageCodes());
        // 加上 _tube_ 通配
        boolean hasOutputRecords = records.stream().anyMatch(doc -> {
            String code = getValueFromDocByKey(doc, "code", String.class);
            return code != null && (allOutputCodes.contains(code) || code.contains("_tube_"));
        });
        if (!hasOutputRecords) {
            return;
        }

        BigDecimal total = intakeOutputCalculator.sumTotalOutput(records, traceId, pid);
        Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_zongChuLiang", window);
        enqueue(totalOutputHandler, vDoc, patient, window, traceId);
    }

    /**
     * 处理排出物量
     */
    private void processDrainageOutput(List<Document> records, Document patient, String pid,
                                        ClinicalTimeWindow window, String traceId) {
        List<String> drainageCodes = DrainageOutputHandler.getDrainageCodes();
        if (!hasAnyRecord(records, drainageCodes)) {
            return;
        }
        BigDecimal total = records.stream()
                .filter(doc -> {
                    String code = getValueFromDocByKey(doc, "code", String.class);
                    return code != null && drainageCodes.contains(code);
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

        Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_daBianAmount", window);
        enqueue(drainageOutputHandler, vDoc, patient, window, traceId);
    }

    /**
     * 处理胃管负压引流
     */
    private void processGastricDrainage(List<Document> records, Document patient, String pid,
                                         ClinicalTimeWindow window, String traceId) {
        boolean hasRecords = records.stream()
                .anyMatch(doc -> "param_tube_胃肠减压".equals(getValueFromDocByKey(doc, "code", String.class)));
        if (!hasRecords) {
            return;
        }
        BigDecimal total = records.stream()
                .filter(doc -> "param_tube_胃肠减压".equals(getValueFromDocByKey(doc, "code", String.class)))
                .map(doc -> {
                    String val = getValueFromDocByKey(doc, "strVal", String.class);
                    try {
                        return val != null ? new BigDecimal(val.trim()) : BigDecimal.ZERO;
                    } catch (NumberFormatException e) {
                        return BigDecimal.ZERO;
                    }
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_tube_胃肠减压", window);
        enqueue(gastricDrainageHandler, vDoc, patient, window, traceId);
    }

    /**
     * 处理其他引流量
     */
    private void processOtherDrainage(List<Document> records, Document patient, String pid,
                                       ClinicalTimeWindow window, String traceId) {
        boolean hasRecords = records.stream().anyMatch(doc -> {
            String code = getValueFromDocByKey(doc, "code", String.class);
            return code != null && code.contains("_tube_") && !"param_tube_胃肠减压".equals(code);
        });
        if (!hasRecords) {
            return;
        }
        BigDecimal total = records.stream()
                .filter(doc -> {
                    String code = getValueFromDocByKey(doc, "code", String.class);
                    return code != null && code.contains("_tube_") && !"param_tube_胃肠减压".equals(code);
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

        Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_tube_other", window);
        enqueue(otherDrainageHandler, vDoc, patient, window, traceId);
    }

    /**
     * 处理净超滤量
     */
    private void processNetUltrafiltration(List<Document> records, Document patient, String pid,
                                            ClinicalTimeWindow window, String traceId) {
        boolean hasRecords = records.stream()
                .anyMatch(doc -> "param_chaoLvLiang".equals(getValueFromDocByKey(doc, "code", String.class)));
        if (!hasRecords) {
            return;
        }
        BigDecimal total = records.stream()
                .filter(doc -> "param_chaoLvLiang".equals(getValueFromDocByKey(doc, "code", String.class)))
                .map(doc -> {
                    String val = getValueFromDocByKey(doc, "strVal", String.class);
                    try {
                        return val != null ? new BigDecimal(val.trim()) : BigDecimal.ZERO;
                    } catch (NumberFormatException e) {
                        return BigDecimal.ZERO;
                    }
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_chaoLvLiang", window);
        enqueue(netUltrafiltrationHandler, vDoc, patient, window, traceId);
    }

    /**
     * 本轮候选患者：统计窗口内有 bedside 数据的 pid（去重）。
     * 不依赖金仓在科列表，是否回传只看 patient 集合中是否存在该 _id。
     */
    private List<String> findCandidatePids(ClinicalTimeWindow window, String traceId) {
        try {
            Date startDate = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Date endDate = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

            Query query = new Query(Criteria.where("time").gt(startDate).lte(endDate));
            query.fields().include("pid");
            List<Document> docs = mongoTemplate.find(query, Document.class, "bedside");

            List<String> pids = docs.stream()
                    .map(doc -> getValueFromDocByKey(doc, "pid", String.class))
                    .filter(one -> one != null && !one.isEmpty())
                    .distinct()
                    .collect(Collectors.toList());

            log.info("STEP_01_PATIENT_SELECTED traceId={} 窗口=[{}, {}) bedside命中pid数量={}",
                    traceId, window.getStart(), window.getEnd(), pids.size());
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
