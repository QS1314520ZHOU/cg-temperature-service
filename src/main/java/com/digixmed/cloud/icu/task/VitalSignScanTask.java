package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.handler.*;
import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.repository.InpatientRepository;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import com.digixmed.cloud.icu.service.IntermediateService;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import com.digixmed.cloud.icu.model.InpatientDTO;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.stream.Collectors;

/**
 * 普通体征扫描任务
 *
 * 业务目的：定时扫描在科患者的普通体征数据（体温、脉搏、心率、呼吸、血压、疼痛评分）
 * 输入：KingbaseES在科患者列表、MongoDB bedside记录
 * 输出：IntermediateTable记录（status=PENDING）
 * 调度时间：每小时执行一次
 *
 * 关键流程：
 *   1. 获取当前标准时间点
 *   2. 从Kingbase查询在科患者
 *   3. 对每个患者，查询对应的bedside记录
 *   4. 调用对应Handler处理
 *   5. 保存到中间表（不直接调用HTTP推送）
 *
 * 职责分离：
 *   - 本任务只负责采集和计算数据
 *   - 只写入中间表PENDING状态
 *   - 不进行HTTP/SOAP请求
 *   - 推送由PushTask负责
 */
@Component
public class VitalSignScanTask {

    private static final Logger log = LoggerFactory.getLogger(VitalSignScanTask.class);

    /** 病区编码，改为配置项，避免与其他模块硬编码不一致 */
    @Value("${vitalsign.patient.ward-code:125011}")
    private String wardCode;

    /**
     * 脉搏代码优先级（param_脉搏优先，避免重复处理）
     */
    private static final List<String> PULSE_CODES = Arrays.asList("param_脉搏", "param_PR");

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

    /**
     * 执行普通体征扫描
     *
     * 扫描逻辑：根据当前时间，扫描当前时间点及之前可能补录的时间点
     * 例如当前10:00，扫描 02:00、06:00、10:00 三个时间点的窗口
     * 通过 upsertPending 的幂等机制处理：相同数据跳过，不同数据重新回传
     */
    @Scheduled(cron = "${digixmed.cron}")
    public void execute() {
        String traceId = TraceIdGenerator.generate();
        log.info("STEP_01_PATIENT_SELECTED traceId={} 开始普通体征扫描", traceId);

        try {
            // 获取当前标准时间点
            LocalDateTime currentVitalPoint = timeWindowService.getCurrentVitalPoint();
            log.info("STEP_02_WINDOW_CREATED traceId={} 当前标准时间点={}", traceId, currentVitalPoint);

            // 获取需要扫描的时间点列表（当前时间点 + 之前可能补录的时间点）
            List<LocalDateTime> scanPoints = getScanTimePoints(currentVitalPoint);
            log.info("STEP_02_WINDOW_CREATED traceId={} 需要扫描的时间点数量={} 时间点={}",
                    traceId, scanPoints.size(), scanPoints);

            // 收集所有时间点窗口内的候选患者（去重）
            java.util.Set<String> allPids = new java.util.LinkedHashSet<>();
            for (LocalDateTime point : scanPoints) {
                ClinicalTimeWindow window = timeWindowService.buildVitalPointWindow(
                        point.toLocalDate(), point.getHour());
                List<String> pids = findCandidatePids(window, traceId);
                allPids.addAll(pids);
            }

            log.info("STEP_01_PATIENT_SELECTED traceId={} 本轮候选患者数量={}", traceId, allPids.size());

            // 对每个患者，处理所有时间点
            for (String pid : allPids) {
                Document patient = findMongoPatientByPid(pid);
                if (patient == null) {
                    log.info("STEP_01_PATIENT_SKIPPED traceId={} pid={} patient集合中不存在该_id，不回传", traceId, pid);
                    continue;
                }
                for (LocalDateTime point : scanPoints) {
                    ClinicalTimeWindow window = timeWindowService.buildVitalPointWindow(
                            point.toLocalDate(), point.getHour());
                    processPatientByPid(pid, patient, point, window, traceId);
                }
            }

            // 血压：直接从 bedside 表查询有8点数据的患者（不依赖在科列表）
            // 窗口[07:00,08:00)在 8 点后才完整，因此从 10 点标准时间点起执行（幂等键相同，重复执行不会重复推送）
            if (currentVitalPoint.getHour() >= 10) {
                processBloodPressureFromBedside(traceId, currentVitalPoint);
            }

            log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 普通体征扫描完成", traceId);
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 普通体征扫描异常", traceId, e);
        }
    }

    /**
     * 获取需要扫描的时间点列表
     * 根据当前时间点，返回当前及之前的所有标准时间点（用于补录数据扫描）
     *
     * 例如：
     * - 当前10:00 → [02:00, 06:00, 10:00]
     * - 当前14:00 → [02:00, 06:00, 10:00, 14:00]
     * - 当前02:00 → [02:00]（只扫当天02:00）
     *
     * @param currentVitalPoint 当前标准时间点
     * @return 需要扫描的时间点列表（按时间顺序）
     */
    private List<LocalDateTime> getScanTimePoints(LocalDateTime currentVitalPoint) {
        List<LocalDateTime> points = new ArrayList<>();
        LocalDate date = currentVitalPoint.toLocalDate();
        int currentHour = currentVitalPoint.getHour();

        // 标准时间点：02, 06, 10, 14, 18, 22
        List<Integer> vitalHours = timeWindowService.getVitalSignHours();

        for (int hour : vitalHours) {
            if (hour <= currentHour) {
                points.add(LocalDateTime.of(date, java.time.LocalTime.of(hour, 0, 0)));
            }
        }

        // 如果当前时间点是列表中的第一个（02:00），只返回它自己
        // 不需要扫描前一天的时间点（那些已经在前一天扫描过了）
        return points;
    }

    private void processPatientByPid(String pid, Document patient, LocalDateTime planTime,
                                      ClinicalTimeWindow window, String traceId) {
        String patientId = patientIdentityMapper.getPatientId(patient);
        String patientTraceId = TraceIdGenerator.generateWithPatient(pid);
        String patientIdMasked = maskPatientId(patientId);

        try {
            log.info("STEP_01_PATIENT_MATCHED traceId={} mongoPid={} patientId={}", patientTraceId, pid, patientIdMasked);

            // 处理体温
            processVitalSign(traceId, patientTraceId, pid, patient, planTime,
                    "param_T", temperatureHandler, window);

            // 处理脉搏：param_脉搏 优先，缺失时回退 param_PR（只处理一次，原实现会重复 upsert）
            processPulseWithFallback(traceId, patientTraceId, pid, patient, planTime, window);

            // 处理心率
            processVitalSign(traceId, patientTraceId, pid, patient, planTime,
                    "param_HR", heartRateHandler, window);

            // 处理呼吸
            processVitalSign(traceId, patientTraceId, pid, patient, planTime,
                    "param_resp", breathHandler, window);

            // 处理疼痛评分
            processVitalSign(traceId, patientTraceId, pid, patient, planTime,
                    "param_tengTong_score", painScoreHandler, window);

        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} patientId={} 处理异常", patientTraceId, patientIdMasked, e);
        }
    }

    /**
     * 处理脉搏，支持param_脉搏和param_PR fallback
     */
    private void processPulseWithFallback(String parentTraceId, String patientTraceId, String pid,
                                           Document patient, LocalDateTime planTime, ClinicalTimeWindow window) {
        try {
            Date startTime = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Date endTime = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

            // 先查询param_脉搏
            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("code").is("param_脉搏")
                    .and("time").gte(startTime).lt(endTime));

            Document bedside = mongoTemplate.findOne(query, Document.class, "bedside");

            // 如果没有param_脉搏，尝试param_PR
            if (bedside == null) {
                query = new Query(Criteria.where("pid").is(pid)
                        .and("code").is("param_PR")
                        .and("time").gte(startTime).lt(endTime));
                bedside = mongoTemplate.findOne(query, Document.class, "bedside");
            }

            if (bedside != null) {
                VitalSignPayload payload = pulseHandler.handle(bedside, patient, planTime, patientTraceId);
                if (payload != null) {
                    intermediateService.upsertPending(payload, patientTraceId);
                }
            }
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} pid={} 脉搏处理异常",
                    patientTraceId, pid, e);
        }
    }

    /**
     * 直接从 bedside 表查询有8点血压数据的患者并处理
     * 不依赖 KingbaseES 在科患者列表
     */
    private void processBloodPressureFromBedside(String traceId, LocalDateTime planTime) {
        log.info("STEP_BP_DIRECT traceId={} 开始直接从bedside查询血压数据", traceId);

        try {
            // 构建7点的时间窗口
            LocalDate today = planTime.toLocalDate();
            Date startTime = Date.from(today.atTime(7, 0, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Date endTime = Date.from(today.atTime(8, 0, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());

            // 如果当前时间早于7点，则查询前一天7点的数据
            if (planTime.getHour() < 7) {
                LocalDate yesterday = today.minusDays(1);
                startTime = Date.from(yesterday.atTime(7, 0, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
                endTime = Date.from(yesterday.atTime(8, 0, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            }

            // 查询8点有收缩压数据的所有 pid
            Query query = new Query(Criteria.where("code").is("param_nibp_s")
                    .and("time").gte(startTime).lt(endTime));
            List<Document> systolicRecords = mongoTemplate.find(query, Document.class, "bedside");

            // 去重获取所有 pid
            List<String> pids = systolicRecords.stream()
                    .map(doc -> getValueFromDocByKey(doc, "pid", String.class))
                    .filter(pid -> pid != null)
                    .distinct()
                    .collect(Collectors.toList());

            log.info("STEP_BP_DIRECT traceId={} 查询到{}个患者有8点收缩压数据", traceId, pids.size());

            for (String pid : pids) {
                try {
                    // 查询 patient 文档
                    Document patient = findMongoPatientByPid(pid);
                    if (patient == null) {
                        log.warn("STEP_BP_DIRECT traceId={} pid={} 未找到patient文档", traceId, pid);
                        continue;
                    }

                    String patientTraceId = TraceIdGenerator.generateWithPatient(pid);

                    // 调用 BloodPressureHandler
                    VitalSignPayload payload = bloodPressureHandler.handle(null, patient, planTime, patientTraceId);
                    if (payload != null) {
                        intermediateService.upsertPending(payload, patientTraceId);
                        log.info("STEP_BP_DIRECT traceId={} pid={} 血压处理成功", traceId, pid);
                    }
                } catch (Exception e) {
                    log.error("STEP_BP_DIRECT traceId={} pid={} 血压处理异常", traceId, pid, e);
                }
            }
        } catch (Exception e) {
            log.error("STEP_BP_DIRECT traceId={} 直接查询血压异常", traceId, e);
        }
    }

    /**
     * 本轮候选患者：当前标准时间点窗口内有普通体征数据的 pid（去重）。
     * 不依赖金仓在科列表，是否回传只看 patient 集合中是否存在该 _id。
     */
    private List<String> findCandidatePids(ClinicalTimeWindow window, String traceId) {
        try {
            Date startTime = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Date endTime = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

            List<String> codes = new ArrayList<>(PULSE_CODES);
            codes.add("param_T");
            codes.add("param_HR");
            codes.add("param_resp");
            codes.add("param_tengTong_score");

            Query query = new Query(Criteria.where("code").in(codes)
                    .and("time").gte(startTime).lt(endTime));
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
            // pid 可能不是 ObjectId 格式，尝试其他方式
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

    private void processVitalSign(String parentTraceId, String patientTraceId, String pid,
                                   Document patient, LocalDateTime planTime,
                                   String sourceCode, BaseVitalSignHandler handler,
                                   ClinicalTimeWindow window) {
        try {
            LocalDateTime startLdt = window.getStart();
            LocalDateTime endLdt = window.getEnd();
            Date startTime = Date.from(startLdt.atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Date endTime = Date.from(endLdt.atZone(ZoneId.of("Asia/Shanghai")).toInstant());

            log.info("STEP_03_QUERY traceId={} pid={} code={} 查询bedside: startLdt={}, endLdt={}, startTime={}, endTime={}",
                    patientTraceId, pid, sourceCode, startLdt, endLdt, startTime, endTime);

            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("code").is(sourceCode)
                    .and("time").gte(startTime).lt(endTime)
                    .and("valid").is(true));

            log.info("STEP_03_QUERY traceId={} pid={} code={} 查询条件: {}", patientTraceId, pid, sourceCode, query);

            Document bedside = mongoTemplate.findOne(query, Document.class, "bedside");
            if (bedside == null) {
                log.info("STEP_03_QUERY traceId={} pid={} code={} 未找到bedside记录", patientTraceId, pid, sourceCode);
                return;
            }

            log.info("STEP_03_QUERY traceId={} pid={} code={} 找到bedside记录: time={}, strVal={}", patientTraceId, pid, sourceCode, bedside.get("time"), bedside.get("strVal"));

            VitalSignPayload payload = handler.handle(bedside, patient, planTime, patientTraceId);
            if (payload != null) {
                intermediateService.upsertPending(payload, patientTraceId);
                log.info("STEP_07 traceId={} pid={} code={} 处理成功", patientTraceId, pid, sourceCode);
            } else {
                log.info("STEP_07 traceId={} pid={} code={} handler返回null", patientTraceId, pid, sourceCode);
            }
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} pid={} code={} 处理异常",
                    patientTraceId, pid, sourceCode, e);
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

    private String maskPatientId(String patientId) {
        if (patientId == null || patientId.length() <= 4) return "****";
        return patientId.substring(0, 2) + "****" + patientId.substring(patientId.length() - 2);
    }
}
