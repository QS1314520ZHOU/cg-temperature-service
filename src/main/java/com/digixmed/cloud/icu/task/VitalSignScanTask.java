package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.handler.*;
import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import com.digixmed.cloud.icu.service.HeightWeightNurseService;
import com.digixmed.cloud.icu.service.HeightWeightNurseService.NurseRef;
import com.digixmed.cloud.icu.service.IntermediateService;
import com.digixmed.cloud.icu.util.PayloadTimeNormalizer;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import org.bson.Document;
import org.bson.types.ObjectId;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 普通体征扫描任务
 *
 * 采集范围：
 *   - 生命体征（体温/脉搏/心率/呼吸/疼痛评分）：只取 02:00、06:00、10:00、14:00、18:00、22:00 六个标准时间点；
 *   - 血压：只取每天 07:00 槽位；
 *   - 入科第一条：入科时刻所属标准点的生命体征 + 身高体重。
 *
 * 补录处理：
 *   每轮扫描不只看"当前标准点"，而是回看 vitalsign.scan.lookback-hours 小时内的所有标准点
 *   （默认 26 小时，可跨天）。护士抢救结束后才补写 06:00 的体温时，bedside.time 仍是 06:00，
 *   本任务后续任意一轮都会重新扫到，并交给 IntermediateService.upsertPending 判定：
 *     - 从未回传过 → 新建 PENDING 回传；
 *     - 回传过且 payloadHash 一致且已 SUCCESS → 跳过，不重复回传；
 *     - 回传过但值发生变化 → 更新记录并重置 PENDING，重新回传。
 *
 * 时间锚定：
 *   payload.planTime 统一锚定为标准时间点（PayloadTimeNormalizer），
 *   bedside.time 落在 recordTime。这样同一格子无论被补录/修改几次，幂等键都不变。
 *
 * 职责分离：本任务只采集并写中间表 PENDING，不发起任何 HTTP/SOAP 请求，推送由 PushTask 负责。
 */
@Component
public class VitalSignScanTask {

    private static final Logger log = LoggerFactory.getLogger(VitalSignScanTask.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String BEDSIDE = "bedside";

    /** 生命体征来源 code */
    private static final String CODE_TEMPERATURE = "param_T";
    /** 脉搏来源 code：只认 param_脉搏，不再兼容 param_PR */
    private static final String CODE_PULSE = "param_脉搏";
    private static final String CODE_HEART_RATE = "param_HR";
    private static final String CODE_BREATH = "param_resp";
    private static final String CODE_PAIN = "param_tengTong_score";
    private static final String CODE_SYSTOLIC = "param_nibp_s";
    private static final String CODE_DIASTOLIC = "param_nibp_d";

    @Value("${vitalsign.patient.ward-code:125011}")
    private String wardCode;

    /** 标准时间点回看小时数，覆盖跨天补录 */
    @Value("${vitalsign.scan.lookback-hours:26}")
    private int scanLookbackHours;

    /** 血压 07:00 槽位回看天数 */
    @Value("${vitalsign.scan.bp-lookback-days:1}")
    private int bpLookbackDays;

    /** 入科首条回传回看天数 */
    @Value("${vitalsign.scan.admission-lookback-days:1}")
    private int admissionLookbackDays;

    @Value("${vitalsign.auto-enabled:false}")
    private boolean autoEnabled;

    @Autowired
    private ClinicalTimeWindowService timeWindowService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private PatientIdentityMapper patientIdentityMapper;

    @Autowired
    private IntermediateService intermediateService;

    @Autowired
    private HeightWeightNurseService heightWeightNurseService;

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
    @Autowired
    private HeightWeightHandler heightWeightHandler;
    @Autowired
    private PushTask pushTask;

    @Scheduled(cron = "${digixmed.cron}")
    public void execute() {
        if (!autoEnabled) {
            log.warn("SCAN_SKIPPED autoEnabled=false, 自动回传已关闭, 跳过本轮");
            return;
        }
        log.info("SCAN_TRIGGERED cron触发开始扫描");
        doScan();
    }

    /** 原 execute() 的完整逻辑，供定时与手动共用 */
    public void doScan() {
        String traceId = TraceIdGenerator.generate();
        LocalDateTime now = timeWindowService.now();
        log.info("STEP_01_PATIENT_SELECTED traceId={} 开始普通体征扫描 now={}", traceId, now);

        try {
            List<LocalDateTime> scanPoints = timeWindowService.getScanTimePoints(now, scanLookbackHours);
            log.info("STEP_02_WINDOW_CREATED traceId={} 回看{}小时，需扫描标准时间点数量={} 时间点={}",
                    traceId, scanLookbackHours, scanPoints.size(), scanPoints);

            // 批量收集所有时间点的候选pid（一次查询，而非每个时间点单独查）
            List<String> vitalCodes = Arrays.asList(
                    CODE_TEMPERATURE, CODE_PULSE, CODE_HEART_RATE, CODE_BREATH, CODE_PAIN);
            Set<String> allPids = findPidsAtPoints(scanPoints, vitalCodes);
            log.info("STEP_01_PATIENT_SELECTED traceId={} 本轮候选患者数量={}", traceId, allPids.size());

            if (!allPids.isEmpty()) {
                // 批量查询所有患者（一次 $in 查询，而非逐个 findOne）
                Map<String, Document> patientMap = findPatientsByPids(allPids);

                for (String pid : allPids) {
                    Document patient = patientMap.get(pid);
                    if (patient == null) {
                        log.info("STEP_01_PATIENT_SKIPPED traceId={} pid={} patient集合中不存在该_id，不回传", traceId, pid);
                        continue;
                    }

                    for (LocalDateTime point : scanPoints) {
                        ClinicalTimeWindow window = timeWindowService.buildVitalPointWindow(
                                point.toLocalDate(), point.getHour());
                        if (window != null) {
                            processPatientByPid(pid, patient, point, window, traceId);
                        }
                    }
                }
            }

            // 入科第一条：独立于 allPids，直接从 patient 集合按 icuAdmissionTime 取患者
            processAdmissionSlots(now, traceId);

            // 血压：只取 07:00 槽位，按回看天数逐日扫描
            processBloodPressureSlots(now, traceId);

            log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 普通体征扫描完成", traceId);

            // 扫描完成后立即推送（不等 PushTask 定时）
            try {
                pushTask.pushOnce(traceId);
            } catch (Exception pe) {
                log.error("SCAN_PUSH traceId={} 扫描后立即推送异常", traceId, pe);
            }
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 普通体征扫描异常", traceId, e);
        }
    }

    /**
     * 入科首条扫描：独立于 allPids，直接从 patient 集合按 icuAdmissionTime 取患者
     *
     * allPids 循环依赖 bedside 精确时刻匹配，入科患者（如 12:09）若无整点数据则不会被选中。
     * 此方法独立查询 patient 集合中 icuAdmissionTime 在回看范围内的患者，确保入科首条不遗漏。
     */
    private void processAdmissionSlots(LocalDateTime now, String traceId) {
        LocalDate earliest = now.toLocalDate().minusDays(Math.max(admissionLookbackDays, 0));
        Date start = Date.from(earliest.atStartOfDay(ZONE).toInstant());
        Date end = Date.from(now.atZone(ZONE).toInstant());

        Query query = new Query(Criteria.where("icuAdmissionTime").gte(start).lte(end));
        List<Document> patients = mongoTemplate.find(query, Document.class, "patient");
        log.info("ADMISSION_SCAN traceId={} 回看{}天 入科患者数量={}",
                traceId, admissionLookbackDays, patients.size());

        for (Document patient : patients) {
            Object id = patient.get("_id");
            if (id == null) {
                continue;
            }
            String pid = id.toString();
            processAdmissionVitalSigns(pid, patient, now, traceId);
        }
    }

    /**
     * 手动精准扫描：只处理指定患者的指定标准时刻
     *
     * @param mongoPid  Mongo patient._id
     * @param point     标准时刻（已校验为 02/06/10/14/18/22 之一）
     * @return 本次登记的记录数
     */
    public int scanOnePoint(String mongoPid, LocalDateTime point, String traceId) {
        Document patient = mongoTemplate.findOne(
                Query.query(Criteria.where("_id").is(new ObjectId(mongoPid))),
                Document.class, "patient");
        if (patient == null) {
            log.warn("MANUAL traceId={} pid={} 患者不存在", traceId, mongoPid);
            return 0;
        }

        int count = 0;
        if (processVitalSign(traceId, mongoPid, patient, point, CODE_TEMPERATURE, temperatureHandler, null)) count++;
        if (processPulse(traceId, mongoPid, patient, point, null)) count++;
        if (processVitalSign(traceId, mongoPid, patient, point, CODE_HEART_RATE, heartRateHandler, null)) count++;
        if (processVitalSign(traceId, mongoPid, patient, point, CODE_BREATH, breathHandler, null)) count++;
        if (processVitalSign(traceId, mongoPid, patient, point, CODE_PAIN, painScoreHandler, null)) count++;
        return count;
    }

    private void processPatientByPid(String pid, Document patient, LocalDateTime planTime,
                                     ClinicalTimeWindow window, String traceId) {
        String patientId = patientIdentityMapper.getPatientId(patient);
        String patientTraceId = TraceIdGenerator.generateWithPatient(pid);
        String patientIdMasked = maskPatientId(patientId);

        try {
            log.info("STEP_01_PATIENT_MATCHED traceId={} mongoPid={} patientId={} 时间点={}",
                    patientTraceId, pid, patientIdMasked, planTime);

            processVitalSign(patientTraceId, pid, patient, planTime, CODE_TEMPERATURE, temperatureHandler, window);
            processPulse(patientTraceId, pid, patient, planTime, window);
            processVitalSign(patientTraceId, pid, patient, planTime, CODE_HEART_RATE, heartRateHandler, window);
            processVitalSign(patientTraceId, pid, patient, planTime, CODE_BREATH, breathHandler, window);
            processVitalSign(patientTraceId, pid, patient, planTime, CODE_PAIN, painScoreHandler, window);
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} patientId={} 处理异常",
                    patientTraceId, patientIdMasked, e);
        }
    }

    /**
     * 入科第一条回传：入科时刻所属标准点的生命体征 + 身高体重
     *
     * 身高体重的记录者必须与本次体温的记录者一致，因此：
     *   1. 先处理体温并拿到 payload；
     *   2. 用体温 payload 的记录者调用 HeightWeightNurseService.pin 原子锁定；
     *   3. 用锁定结果构建身高体重。
     * 若本次没有体温记录，则不回传身高体重（等体温出现后的下一轮再一起回传），
     * 以保证"身高体重的记录者与对应体温一致"这一约束不被破坏。
     *
     * 入科标准点的取法：
     *   入科时刻本身就是标准点 → 取其自身；否则取上一个标准点。
     *   00:00-01:59 入科 → 前一天 22:00（窗口 [22:00, 次日02:00) 包含入科时刻）。
     *
     * 调用方：processAdmissionSlots 直接从 patient 集合按 icuAdmissionTime 取患者，
     *        不依赖 allPids（bedside 精确时刻匹配），确保入科首条不遗漏。
     *        此方法内的日期范围判断是二次校验，不是唯一过滤点。
     *
     * @return 入科体征登记的记录数（含生命体征+身高体重），-1表示患者不存在
     */
    public int scanAdmission(String mongoPid, String traceId) {
        Document patient = mongoTemplate.findOne(
                Query.query(Criteria.where("_id").is(new ObjectId(mongoPid))),
                Document.class, "patient");
        if (patient == null) {
            log.warn("MANUAL traceId={} pid={} 患者不存在", traceId, mongoPid);
            return -1;
        }

        // 查询执行前该患者的队列记录数
        long before = mongoTemplate.count(
                Query.query(Criteria.where("mongoPid").is(mongoPid)),
                IntermediateService.PUSH_COLLECTION);

        processAdmissionVitalSigns(mongoPid, patient, timeWindowService.now(), traceId);

        // 查询执行后的记录数，差值即为本次新增/更新的条数
        long after = mongoTemplate.count(
                Query.query(Criteria.where("mongoPid").is(mongoPid)),
                IntermediateService.PUSH_COLLECTION);

        return (int) (after - before);
    }

    private void processAdmissionVitalSigns(String pid, Document patient, LocalDateTime now, String traceId) {
        Date icuAdmissionTime = getValueFromDocByKey(patient, "icuAdmissionTime", Date.class);
        if (icuAdmissionTime == null) {
            log.debug("ADMISSION_VITALS traceId={} pid={} 无icuAdmissionTime，跳过入科扫描", traceId, pid);
            return;
        }

        LocalDateTime admissionDateTime = icuAdmissionTime.toInstant().atZone(ZONE).toLocalDateTime();
        LocalDate admissionDate = admissionDateTime.toLocalDate();
        LocalDate earliest = now.toLocalDate().minusDays(Math.max(admissionLookbackDays, 0));

        if (admissionDate.isBefore(earliest) || admissionDate.isAfter(now.toLocalDate())) {
            log.debug("ADMISSION_VITALS traceId={} pid={} 入科日期={} 不在回看范围内，跳过", traceId, pid, admissionDate);
            return;
        }

        // 入科时刻所属标准点（用于 handler 内部窗口构建）
        LocalDateTime admissionPlanTime = timeWindowService.getCurrentVitalPoint(admissionDateTime);
        ClinicalTimeWindow window = timeWindowService.buildVitalPointWindow(
                admissionPlanTime.toLocalDate(), admissionPlanTime.getHour());
        if (window == null) {
            return;
        }

        String patientId = patientIdentityMapper.getPatientId(patient);
        String patientTraceId = TraceIdGenerator.generateWithPatient(pid);
        String patientIdMasked = maskPatientId(patientId);

        log.info("ADMISSION_VITALS traceId={} mongoPid={} patientId={} 入科时间={} 标准点={} 窗口=[{}, {})",
                patientTraceId, pid, patientIdMasked, admissionDateTime, admissionPlanTime,
                window.getStart(), window.getEnd());

        // 1. 五项生命体征：窗口内取最早一条，不锚定（planTime = recordTime = bedside.time）
        VitalSignPayload tempPayload = processAdmissionVitalSign(
                patientTraceId, pid, patient, admissionDateTime, admissionPlanTime,
                CODE_TEMPERATURE, temperatureHandler, window);
        processAdmissionVitalSign(
                patientTraceId, pid, patient, admissionDateTime, admissionPlanTime,
                CODE_PULSE, pulseHandler, window);
        processAdmissionVitalSign(
                patientTraceId, pid, patient, admissionDateTime, admissionPlanTime,
                CODE_HEART_RATE, heartRateHandler, window);
        processAdmissionVitalSign(
                patientTraceId, pid, patient, admissionDateTime, admissionPlanTime,
                CODE_BREATH, breathHandler, window);
        processAdmissionVitalSign(
                patientTraceId, pid, patient, admissionDateTime, admissionPlanTime,
                CODE_PAIN, painScoreHandler, window);

        // 1.1 入科血压：查入科时间点的收缩压+舒张压，成对才回传
        processAdmissionBloodPressure(patientTraceId, pid, patient, admissionDateTime, window);

        // 2. 身高体重（与体温解耦：体温缺失不阻断身高体重）
        if (tempPayload == null) {
            log.info("ADMISSION_VITALS traceId={} pid={} 本次无体温记录，身高体重将使用默认记录者", patientTraceId, pid);
        }

        // 3. 锁定记录者并处理身高体重（R2: planTime=recordTime=icuAdmissionTime）
        String nurseId = tempPayload != null ? tempPayload.getRecordNurseId() : null;
        String nurseName = tempPayload != null ? tempPayload.getRecordNurseName() : null;
        NurseRef nurse = heightWeightNurseService.pin(pid, nurseId, nurseName, "ADMISSION");
        // R2: planTime=recordTime=admissionDateTime（不可变时间点），不锚定 07:00
        try {
            List<VitalSignPayload> hwPayloads = heightWeightHandler.buildAdmissionFirst(
                    patient, admissionDateTime, nurse, patientTraceId);
            for (VitalSignPayload hwPayload : hwPayloads) {
                intermediateService.upsertPending(hwPayload, patientTraceId);
                log.info("ADMISSION_VITALS traceId={} pid={} {}={} planTime={} 入科身高体重回传完成",
                        patientTraceId, pid, hwPayload.getVitalsignName(),
                        hwPayload.getVitalsignSVal1(), hwPayload.getPlanTime());
            }
        } catch (Exception e) {
            log.error("ADMISSION_HW traceId={} pid={} 身高体重处理异常", patientTraceId, pid, e);
        }

        log.info("ADMISSION_VITALS traceId={} pid={} 入科时间点扫描完成", patientTraceId, pid);
    }

    /**
     * 血压只取 07:00 槽位，按回看天数逐日扫描
     */
    private void processBloodPressureSlots(LocalDateTime now, String traceId) {
        LocalDate today = now.toLocalDate();
        int days = Math.max(bpLookbackDays, 0);
        for (int i = days; i >= 0; i--) {
            LocalDate slotDate = today.minusDays(i);
            LocalDateTime bpPoint = timeWindowService.buildSevenAmPoint(slotDate);
            if (bpPoint == null || bpPoint.isAfter(now)) {
                continue;
            }

            // 收集该日 07:00 精确时刻有收缩压数据的 pid
            Set<String> pids = findPidsAtPoint(bpPoint, Collections.singletonList(CODE_SYSTOLIC));

            log.info("STEP_BP traceId={} 日期={} 07:00槽位收缩压患者数量={}", traceId, slotDate, pids.size());

            // O8: 批量查询患者（一次 $in 替代 N 次单条查询）
            Map<String, Document> patientMap = findMongoPatientsByPids(pids);

            for (String pid : pids) {
                Document patient = patientMap.get(pid);
                if (patient == null) {
                    continue;
                }
                String patientTraceId = TraceIdGenerator.generateWithPatient(pid);
                VitalSignPayload payload = bloodPressureHandler.handle(null, patient, bpPoint, patientTraceId);
                if (payload != null) {
                    PayloadTimeNormalizer.anchor(payload, bpPoint);
                    intermediateService.upsertPending(payload, patientTraceId);
                    log.info("STEP_BP traceId={} pid={} 日期={} 血压处理完成", patientTraceId, pid, slotDate);
                }
            }
        }
    }

    /**
     * 采集指定标准时刻的脉搏
     *
     * 只查 param_脉搏，time 必须精确等于标准时刻，取 editTime 最新的一条。
     * 查不到即视为该时间点护士未记录脉搏，不回传。
     *
     * @return 是否登记了一条待推送记录
     */
    private boolean processPulse(String patientTraceId, String pid,
                                 Document patient, LocalDateTime planTime, ClinicalTimeWindow window) {
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(CODE_PULSE)
                .and("valid").ne(false)
                .andOperator(exactTimeCriteria(planTime)))
                .with(Sort.by(Sort.Direction.DESC, "editTime"))
                .limit(1);

        Document bedside = mongoTemplate.findOne(query, Document.class, BEDSIDE);
        if (bedside == null) {
            log.debug("STEP_04_SOURCE_RECORD_SELECTED traceId={} pid={} point={} 无脉搏数据", patientTraceId, pid, planTime);
            return false;
        }

        VitalSignPayload payload = pulseHandler.handle(bedside, patient, planTime, patientTraceId);
        if (payload == null) {
            return false;
        }

        PayloadTimeNormalizer.anchor(payload, planTime);
        intermediateService.upsertPending(payload, patientTraceId);
        return true;
    }

    private boolean processVitalSign(String patientTraceId, String pid, Document patient,
                                   LocalDateTime planTime, String sourceCode,
                                   BaseVitalSignHandler handler, ClinicalTimeWindow window) {
        try {
            log.info("STEP_03_QUERY traceId={} pid={} code={} 精确时刻={}",
                    patientTraceId, pid, sourceCode, planTime);

            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("code").is(sourceCode)
                    .and("valid").ne(false)
                    .andOperator(exactTimeCriteria(planTime)))
                    .with(Sort.by(Sort.Direction.DESC, "editTime"))
                    .limit(1);

            Document bedside = mongoTemplate.findOne(query, Document.class, BEDSIDE);
            if (bedside == null) {
                log.info("STEP_03_QUERY traceId={} pid={} code={} 未找到bedside记录", patientTraceId, pid, sourceCode);
                return false;
            }

            log.info("STEP_03_QUERY traceId={} pid={} code={} 找到bedside记录: time={}, strVal={}",
                    patientTraceId, pid, sourceCode, bedside.get("time"), bedside.get("strVal"));

            VitalSignPayload payload = handler.handle(bedside, patient, planTime, patientTraceId);
            if (payload != null) {
                PayloadTimeNormalizer.anchor(payload, planTime);
                intermediateService.upsertPending(payload, patientTraceId);
                log.info("STEP_07 traceId={} pid={} code={} 处理成功", patientTraceId, pid, sourceCode);
                return true;
            } else {
                log.info("STEP_07 traceId={} pid={} code={} handler返回null", patientTraceId, pid, sourceCode);
            }
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} pid={} code={} 处理异常", patientTraceId, pid, sourceCode, e);
        }
        return false;
    }

    /**
     * 入科专用：窗口内取最早一条体征，不锚定时间
     *
     * 查询语义：[入科时刻, 标准点窗口结束) 内的首条记录。
     * 起点用真实入科时刻（不是标准点），避免捞到入科前的历史数据。
     * 返回的 payload 的 planTime = recordTime = icuAdmissionTime（入科时刻），不做锚定。
     *
     * 注意：handler 内部用 planTime.getHour() 构建窗口（TemperatureHandler、BreathHandler），
     *       因此这里传标准点（10:00）给 handler，查到 bedside 后再用 bedside.time 覆盖 planTime。
     */
    private VitalSignPayload processAdmissionVitalSign(String patientTraceId, String pid,
                                                        Document patient, LocalDateTime admissionDateTime,
                                                        LocalDateTime standardPoint, String sourceCode,
                                                        BaseVitalSignHandler handler, ClinicalTimeWindow window) {
        try {
            Date startTime = Date.from(admissionDateTime.atZone(ZONE).toInstant());
            Date endTime = Date.from(window.getEnd().atZone(ZONE).toInstant());

            log.info("ADMISSION_VITALS traceId={} pid={} code={} 入科查询窗口=[{}, {})",
                    patientTraceId, pid, sourceCode, admissionDateTime, window.getEnd());

            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("code").is(sourceCode)
                    .and("valid").ne(false)
                    .and("time").gte(startTime).lt(endTime))
                    .with(Sort.by(Sort.Direction.ASC, "time")
                            .and(Sort.by(Sort.Direction.DESC, "editTime")))
                    .limit(1);

            Document bedside = mongoTemplate.findOne(query, Document.class, BEDSIDE);
            if (bedside == null) {
                log.info("ADMISSION_VITALS traceId={} pid={} code={} 窗口内未找到bedside记录", patientTraceId, pid, sourceCode);
                return null;
            }

            Date bedsideTime = getValueFromDocByKey(bedside, "time", Date.class);
            log.info("ADMISSION_VITALS traceId={} pid={} code={} 找到bedside记录: time={}, strVal={}",
                    patientTraceId, pid, sourceCode, bedsideTime, bedside.get("strVal"));

            // 传标准点给 handler（内部用 getHour() 构建窗口），查到 bedside 后再覆盖 planTime
            VitalSignPayload payload = handler.handle(bedside, patient, standardPoint, patientTraceId);
            if (payload == null) {
                log.warn("ADMISSION_VITALS traceId={} pid={} code={} handler返回null", patientTraceId, pid, sourceCode);
                return null;
            }

            // 入科首条不锚定：planTime = recordTime = icuAdmissionTime
            payload.setPlanTime(admissionDateTime);
            payload.setRecordTime(admissionDateTime);

            intermediateService.upsertPending(payload, patientTraceId);
            log.info("ADMISSION_VITALS traceId={} pid={} code={} planTime={} recordTime={} 处理成功",
                    patientTraceId, pid, sourceCode, payload.getPlanTime(), payload.getRecordTime());
            return payload;
        } catch (Exception e) {
            log.error("ADMISSION_VITALS traceId={} pid={} code={} 处理异常", patientTraceId, pid, sourceCode, e);
            return null;
        }
    }

    /**
     * 入科血压：查入科时间点的收缩压+舒张压，成对才回传
     * planTime = recordTime = admissionDateTime
     */
    private void processAdmissionBloodPressure(String patientTraceId, String pid, Document patient,
                                                LocalDateTime admissionDateTime, ClinicalTimeWindow window) {
        try {
            Date startTime = Date.from(admissionDateTime.atZone(ZONE).toInstant());
            Date endTime = Date.from(window.getEnd().atZone(ZONE).toInstant());

            // 查收缩压
            Query sysQuery = new Query(Criteria.where("pid").is(pid)
                    .and("code").is(CODE_SYSTOLIC)
                    .and("valid").ne(false)
                    .and("time").gte(startTime).lt(endTime))
                    .with(Sort.by(Sort.Direction.ASC, "time")
                            .and(Sort.by(Sort.Direction.DESC, "editTime")))
                    .limit(1);
            Document sysDoc = mongoTemplate.findOne(sysQuery, Document.class, BEDSIDE);

            // 查舒张压
            Query diaQuery = new Query(Criteria.where("pid").is(pid)
                    .and("code").is(CODE_DIASTOLIC)
                    .and("valid").ne(false)
                    .and("time").gte(startTime).lt(endTime))
                    .with(Sort.by(Sort.Direction.ASC, "time")
                            .and(Sort.by(Sort.Direction.DESC, "editTime")))
                    .limit(1);
            Document diaDoc = mongoTemplate.findOne(diaQuery, Document.class, BEDSIDE);

            if (sysDoc == null || diaDoc == null) {
                log.info("ADMISSION_BP traceId={} pid={} 入科血压不完整: systolic={} diastolic={}",
                        patientTraceId, pid, sysDoc != null ? "found" : "null", diaDoc != null ? "found" : "null");
                return;
            }

            String systolic = getValueFromDocByKey(sysDoc, "strVal", String.class);
            String diastolic = getValueFromDocByKey(diaDoc, "strVal", String.class);
            if (systolic == null || diastolic == null) {
                log.info("ADMISSION_BP traceId={} pid={} 血压值为空: systolic={} diastolic={}", patientTraceId, pid, systolic, diastolic);
                return;
            }

            VitalSignPayload payload = VitalSignPayload.builder()
                    .vitalsignName("血压")
                    .vitalsignType("1005")
                    .vitalsignNVal1(systolic)
                    .vitalsignNVal2(diastolic)
                    .unit("mmHg")
                    .build();

            // 设置患者身份和记录者
            payload.setPatientId(patientIdentityMapper.getPatientId(patient));
            payload.setMrn(patientIdentityMapper.getMrn(patient));
            payload.setPatientName(patientIdentityMapper.getPatientName(patient));
            payload.setSeries(patientIdentityMapper.getSeries());
            payload.setWardCode(patientIdentityMapper.getWardCode());
            payload.setRecordNurseId(patientIdentityMapper.getRecordNurseId());
            payload.setRecordNurseName("陈琳");
            payload.setIsValid(1);
            payload.setRemark("");
            payload.setTraceId(patientTraceId);
            payload.setMongoPid(getValueFromDocByKey(patient, "_id", Object.class) != null
                    ? getValueFromDocByKey(patient, "_id", Object.class).toString() : null);
            // 入科首条不锚定：planTime = recordTime = admissionDateTime
            payload.setPlanTime(admissionDateTime);
            payload.setRecordTime(admissionDateTime);

            intermediateService.upsertPending(payload, patientTraceId);
            log.info("ADMISSION_BP traceId={} pid={} 血压={}/{} planTime={} 入科血压回传完成",
                    patientTraceId, pid, systolic, diastolic, admissionDateTime);
        } catch (Exception e) {
            log.error("ADMISSION_BP traceId={} pid={} 入科血压处理异常", patientTraceId, pid, e);
        }
    }

    /**
     * 通过 pid 查询 MongoDB patient 文档
     */
    private Document findMongoPatientByPid(String pid) {
        try {
            Query query = new Query(Criteria.where("_id").is(new org.bson.types.ObjectId(pid)));
            return mongoTemplate.findOne(query, Document.class, "patient");
        } catch (Exception e) {
            log.warn("findMongoPatientByPid pid={} 查询异常: {}", pid, e.getMessage());
            return null;
        }
    }

    /**
     * O8: 批量查询 MongoDB patient 文档（一次 $in 替代 N 次单条查询）
     */
    private Map<String, Document> findMongoPatientsByPids(Set<String> pids) {
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
            log.warn("findMongoPatientsByPids 批量查询异常，降级为逐条查询: {}", e.getMessage());
            Map<String, Document> map = new java.util.LinkedHashMap<>();
            for (String pid : pids) {
                Document p = findMongoPatientByPid(pid);
                if (p != null) map.put(pid, p);
            }
            return map;
        }
    }

    private <T> T getValueFromDocByKey(Document doc, String key, Class<T> clazz) {
        if (doc == null) return null;
        Object value = doc.get(key);
        if (value == null) return null;
        if (clazz.isInstance(value)) return clazz.cast(value);
        if (clazz == String.class) return clazz.cast(value.toString());
        return null;
    }

    private String maskPatientId(String patientId) {
        if (patientId == null || patientId.length() <= 4) return "****";
        return patientId.substring(0, 2) + "****" + patientId.substring(patientId.length() - 2);
    }

    /**
     * 精确时刻匹配条件：time == point
     *
     * Mongo 存的是 Date（毫秒精度），用 gte(point) + lt(point+1ms) 等价于精确相等，
     * 比 is(point) 更稳妥——避免驱动层 Date 精度差异导致漏匹配。
     */
    private Criteria exactTimeCriteria(LocalDateTime point) {
        Date start = Date.from(point.atZone(ZONE).toInstant());
        Date end = new Date(start.getTime() + 1);
        return Criteria.where("time").gte(start).lt(end);
    }

    /**
     * 批量查询多个时间点的候选pid（一次查询）
     *
     * 原实现每个时间点单独查 bedside.findDistinct，6个时间点 = 6次查询。
     * 改为 $or 合并所有时间点条件，一次查询返回全部 pid。
     */
    private Set<String> findPidsAtPoints(List<LocalDateTime> points, List<String> codes) {
        if (points == null || points.isEmpty()) {
            return Collections.emptySet();
        }

        Criteria timeOr = new Criteria().orOperator(
                points.stream().map(this::exactTimeCriteria).toArray(Criteria[]::new));

        Query query = new Query(new Criteria().andOperator(
                Criteria.where("code").in(codes),
                Criteria.where("valid").ne(false),
                timeOr));

        return new LinkedHashSet<>(
                mongoTemplate.findDistinct(query, "pid", "bedside", String.class));
    }

    /**
     * 批量查询患者（一次 $in 查询）
     *
     * 原实现逐个 findMongoPatientByPid，N个患者 = N次查询。
     * 改为 $in 一次查回，以 _id 为 key 构建 Map。
     */
    private Map<String, Document> findPatientsByPids(Set<String> pids) {
        List<ObjectId> ids = new ArrayList<>();
        for (String pid : pids) {
            try {
                ids.add(new ObjectId(pid));
            } catch (IllegalArgumentException e) {
                log.warn("findPatientsByPids pid={} 不是合法ObjectId，跳过", pid);
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        Query query = new Query(Criteria.where("_id").in(ids));
        List<Document> patients = mongoTemplate.find(query, Document.class, "patient");

        Map<String, Document> map = new LinkedHashMap<>();
        for (Document p : patients) {
            Object id = p.get("_id");
            if (id != null) {
                map.put(id.toString(), p);
            }
        }
        return map;
    }

    /** 单个时间点的pid查询（血压等低频场景使用） */
    private Set<String> findPidsAtPoint(LocalDateTime point, List<String> codes) {
        Query query = new Query(Criteria.where("code").in(codes)
                .and("valid").ne(false)
                .andOperator(exactTimeCriteria(point)));
        return new LinkedHashSet<>(
                mongoTemplate.findDistinct(query, "pid", "bedside", String.class));
    }
}
