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
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** 脉搏代码优先级（param_脉搏优先，避免重复处理） */
    private static final List<String> PULSE_CODES = Arrays.asList("param_脉搏", "param_PR");

    /** 生命体征来源 code */
    private static final String CODE_TEMPERATURE = "param_T";
    private static final String CODE_HEART_RATE = "param_HR";
    private static final String CODE_BREATH = "param_resp";
    private static final String CODE_PAIN = "param_tengTong_score";
    private static final String CODE_SYSTOLIC = "param_nibp_s";

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

    @Scheduled(cron = "${digixmed.cron}")
    public void execute() {
        String traceId = TraceIdGenerator.generate();
        LocalDateTime now = timeWindowService.now();
        log.info("STEP_01_PATIENT_SELECTED traceId={} 开始普通体征扫描 now={}", traceId, now);

        try {
            List<LocalDateTime> scanPoints = timeWindowService.getScanTimePoints(now, scanLookbackHours);
            log.info("STEP_02_WINDOW_CREATED traceId={} 回看{}小时，需扫描标准时间点数量={} 时间点={}",
                    traceId, scanLookbackHours, scanPoints.size(), scanPoints);

            // 收集所有时间点窗口内的候选患者（去重）
            Set<String> allPids = new LinkedHashSet<>();
            for (LocalDateTime point : scanPoints) {
                ClinicalTimeWindow window = timeWindowService.buildVitalPointWindow(
                        point.toLocalDate(), point.getHour());
                if (window == null) {
                    continue;
                }
                allPids.addAll(findCandidatePids(window, traceId));
            }
            log.info("STEP_01_PATIENT_SELECTED traceId={} 本轮候选患者数量={}", traceId, allPids.size());

            for (String pid : allPids) {
                Document patient = findMongoPatientByPid(pid);
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

                // 入科第一条：生命体征 + 身高体重
                processAdmissionVitalSigns(pid, patient, now, traceId);
            }

            // 血压：只取 07:00 槽位，按回看天数逐日扫描
            processBloodPressureSlots(now, traceId);

            log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 普通体征扫描完成", traceId);
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 普通体征扫描异常", traceId, e);
        }
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
            processPulseWithFallback(patientTraceId, pid, patient, planTime, window);
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
     *   原实现用 "hours.filter(h <= admissionHour).max().orElse(2)"，
     *   在 00:00-01:59 入科时会错误落到当天 02:00，而该窗口 [02:00,06:00) 并不包含入科时刻。
     */
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

        // 入科时刻所属标准点
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

        // 1. 先处理体温，拿到记录者
        VitalSignPayload tempPayload = processVitalSignAndGetPayload(
                patientTraceId, pid, patient, admissionPlanTime, CODE_TEMPERATURE, temperatureHandler, window);

        // 2. 锁定记录者
        String nurseId = tempPayload != null ? tempPayload.getRecordNurseId() : null;
        String nurseName = tempPayload != null ? tempPayload.getRecordNurseName() : null;
        NurseRef nurse = heightWeightNurseService.pin(pid, nurseId, nurseName, "ADMISSION");

        // 3. 处理其他生命体征
        processPulseWithFallback(patientTraceId, pid, patient, admissionPlanTime, window);
        processVitalSign(patientTraceId, pid, patient, admissionPlanTime, CODE_HEART_RATE, heartRateHandler, window);
        processVitalSign(patientTraceId, pid, patient, admissionPlanTime, CODE_BREATH, breathHandler, window);
        processVitalSign(patientTraceId, pid, patient, admissionPlanTime, CODE_PAIN, painScoreHandler, window);

        // 4. 身高体重（用锁定的记录者）
        try {
            VitalSignPayload heightPayload = heightWeightHandler.buildHeightPayload(
                    patient, admissionPlanTime, nurse, patientTraceId);
            if (heightPayload != null) {
                PayloadTimeNormalizer.anchor(heightPayload, admissionPlanTime);
                intermediateService.upsertPending(heightPayload, patientTraceId);
                log.info("ADMISSION_VITALS traceId={} pid={} 身高处理完成 nurse={}", patientTraceId, pid, nurse.getName());
            }
            VitalSignPayload weightPayload = heightWeightHandler.buildWeightPayload(
                    patient, admissionPlanTime, nurse, patientTraceId);
            if (weightPayload != null) {
                PayloadTimeNormalizer.anchor(weightPayload, admissionPlanTime);
                intermediateService.upsertPending(weightPayload, patientTraceId);
                log.info("ADMISSION_VITALS traceId={} pid={} 体重处理完成 nurse={}", patientTraceId, pid, nurse.getName());
            }
        } catch (Exception e) {
            log.error("ADMISSION_VITALS traceId={} pid={} 身高体重处理异常", patientTraceId, pid, e);
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
            LocalDateTime slotTime = LocalDateTime.of(slotDate, java.time.LocalTime.of(7, 0, 0));
            if (now.isBefore(slotTime)) {
                continue;
            }

            // 收集该日 07:00 槽位有收缩压数据的 pid
            ClinicalTimeWindow window = timeWindowService.buildSevenAmWindow(slotDate);
            Date startTime = Date.from(window.getStart().atZone(ZONE).toInstant());
            Date endTime = Date.from(window.getEnd().atZone(ZONE).toInstant());

            Query query = new Query(Criteria.where("code").is(CODE_SYSTOLIC)
                    .and("valid").ne(false)
                    .and("time").gte(startTime).lt(endTime));
            List<Document> systolicRecords = mongoTemplate.find(query, Document.class, BEDSIDE);

            Set<String> pids = systolicRecords.stream()
                    .map(doc -> getValueFromDocByKey(doc, "pid", String.class))
                    .filter(p -> p != null && !p.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            log.info("STEP_BP traceId={} 日期={} 07:00槽位收缩压患者数量={}", traceId, slotDate, pids.size());

            for (String pid : pids) {
                Document patient = findMongoPatientByPid(pid);
                if (patient == null) {
                    continue;
                }
                String patientTraceId = TraceIdGenerator.generateWithPatient(pid);
                VitalSignPayload payload = bloodPressureHandler.handle(null, patient, slotTime, patientTraceId);
                if (payload != null) {
                    PayloadTimeNormalizer.anchor(payload, slotTime);
                    intermediateService.upsertPending(payload, patientTraceId);
                    log.info("STEP_BP traceId={} pid={} 日期={} 血压处理完成", patientTraceId, pid, slotDate);
                }
            }
        }
    }

    private void processPulseWithFallback(String patientTraceId, String pid,
                                           Document patient, LocalDateTime planTime, ClinicalTimeWindow window) {
        try {
            Date startTime = Date.from(window.getStart().atZone(ZONE).toInstant());
            Date endTime = Date.from(window.getEnd().atZone(ZONE).toInstant());

            Document bedside = null;
            for (String code : PULSE_CODES) {
                Query query = new Query(Criteria.where("pid").is(pid)
                        .and("code").is(code)
                        .and("valid").ne(false)
                        .and("time").gte(startTime).lt(endTime));
                bedside = mongoTemplate.findOne(query, Document.class, BEDSIDE);
                if (bedside != null) {
                    break;
                }
            }

            if (bedside != null) {
                VitalSignPayload payload = pulseHandler.handle(bedside, patient, planTime, patientTraceId);
                if (payload != null) {
                    PayloadTimeNormalizer.anchor(payload, planTime);
                    intermediateService.upsertPending(payload, patientTraceId);
                }
            }
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} pid={} 脉搏处理异常", patientTraceId, pid, e);
        }
    }

    private void processVitalSign(String patientTraceId, String pid, Document patient,
                                   LocalDateTime planTime, String sourceCode,
                                   BaseVitalSignHandler handler, ClinicalTimeWindow window) {
        try {
            Date startTime = Date.from(window.getStart().atZone(ZONE).toInstant());
            Date endTime = Date.from(window.getEnd().atZone(ZONE).toInstant());

            log.info("STEP_03_QUERY traceId={} pid={} code={} 窗口=[{}, {})",
                    patientTraceId, pid, sourceCode, window.getStart(), window.getEnd());

            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("code").is(sourceCode)
                    .and("valid").ne(false)
                    .and("time").gte(startTime).lt(endTime));

            Document bedside = mongoTemplate.findOne(query, Document.class, BEDSIDE);
            if (bedside == null) {
                log.info("STEP_03_QUERY traceId={} pid={} code={} 未找到bedside记录", patientTraceId, pid, sourceCode);
                return;
            }

            log.info("STEP_03_QUERY traceId={} pid={} code={} 找到bedside记录: time={}, strVal={}",
                    patientTraceId, pid, sourceCode, bedside.get("time"), bedside.get("strVal"));

            VitalSignPayload payload = handler.handle(bedside, patient, planTime, patientTraceId);
            if (payload != null) {
                PayloadTimeNormalizer.anchor(payload, planTime);
                intermediateService.upsertPending(payload, patientTraceId);
                log.info("STEP_07 traceId={} pid={} code={} 处理成功", patientTraceId, pid, sourceCode);
            } else {
                log.info("STEP_07 traceId={} pid={} code={} handler返回null", patientTraceId, pid, sourceCode);
            }
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} pid={} code={} 处理异常", patientTraceId, pid, sourceCode, e);
        }
    }

    /**
     * 处理生命体征并返回 payload（用于入科扫描获取体温记录者）
     */
    private VitalSignPayload processVitalSignAndGetPayload(String patientTraceId, String pid, Document patient,
                                                            LocalDateTime planTime, String sourceCode,
                                                            BaseVitalSignHandler handler, ClinicalTimeWindow window) {
        try {
            Date startTime = Date.from(window.getStart().atZone(ZONE).toInstant());
            Date endTime = Date.from(window.getEnd().atZone(ZONE).toInstant());

            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("code").is(sourceCode)
                    .and("valid").ne(false)
                    .and("time").gte(startTime).lt(endTime));

            Document bedside = mongoTemplate.findOne(query, Document.class, BEDSIDE);
            if (bedside == null) {
                log.info("ADMISSION_VITALS traceId={} pid={} code={} 未找到bedside记录", patientTraceId, pid, sourceCode);
                return null;
            }

            VitalSignPayload payload = handler.handle(bedside, patient, planTime, patientTraceId);
            if (payload != null) {
                PayloadTimeNormalizer.anchor(payload, planTime);
                intermediateService.upsertPending(payload, patientTraceId);
                log.info("ADMISSION_VITALS traceId={} pid={} code={} 处理成功", patientTraceId, pid, sourceCode);
            }
            return payload;
        } catch (Exception e) {
            log.error("ADMISSION_VITALS traceId={} pid={} code={} 处理异常", patientTraceId, pid, sourceCode, e);
            return null;
        }
    }

    /**
     * 本轮候选患者：当前标准时间点窗口内有普通体征数据的 pid（去重）。
     * 不依赖金仓在科列表，是否回传只看 patient 集合中是否存在该 _id。
     */
    private List<String> findCandidatePids(ClinicalTimeWindow window, String traceId) {
        try {
            Date startTime = Date.from(window.getStart().atZone(ZONE).toInstant());
            Date endTime = Date.from(window.getEnd().atZone(ZONE).toInstant());

            List<String> codes = new ArrayList<>(PULSE_CODES);
            codes.add(CODE_TEMPERATURE);
            codes.add(CODE_HEART_RATE);
            codes.add(CODE_BREATH);
            codes.add(CODE_PAIN);

            Query query = new Query(Criteria.where("code").in(codes)
                    .and("valid").ne(false)
                    .and("time").gte(startTime).lt(endTime));
            query.fields().include("pid");
            List<Document> docs = mongoTemplate.find(query, Document.class, BEDSIDE);

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
            return new ArrayList<>();
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

    private <T> T getValueFromDocByKey(Document doc, String key, Class<T> clazz) {
        if (doc == null) return null;
        Object value = doc.get(key);
        if (value == null) return null;
        return clazz.cast(value);
    }

    private String maskPatientId(String patientId) {
        if (patientId == null || patientId.length() <= 4) return "****";
        return patientId.substring(0, 2) + "****" + patientId.substring(patientId.length() - 2);
    }
}
