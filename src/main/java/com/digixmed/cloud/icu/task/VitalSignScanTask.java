package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.handler.*;
import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.repository.InpatientRepository;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import com.digixmed.cloud.icu.service.PushService;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import com.digixmed.cloud.icu.model.InpatientDTO;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

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

    private static final String WARD_CODE = "125011";

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
    private PushService pushService;

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
     */
    @Scheduled(cron = "${digixmed.cron}")
    public void execute() {
        String traceId = TraceIdGenerator.generate();
        log.info("STEP_01_PATIENT_SELECTED traceId={} 开始普通体征扫描", traceId);

        try {
            // 获取当前标准时间点
            LocalDateTime currentVitalPoint = timeWindowService.getCurrentVitalPoint();
            log.info("STEP_02_WINDOW_CREATED traceId={} 当前标准时间点={}", traceId, currentVitalPoint);

            // 查询在科患者
            List<InpatientDTO> inpatients = inpatientRepository.findInpatients(WARD_CODE);
            log.info("STEP_01_PATIENT_SELECTED traceId={} 在科患者数量={}", traceId, inpatients.size());

            for (InpatientDTO inpatient : inpatients) {
                processPatient(inpatient, currentVitalPoint, traceId);
            }

            log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 普通体征扫描完成", traceId);
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 普通体征扫描异常", traceId, e);
        }
    }

    private void processPatient(InpatientDTO inpatient, LocalDateTime planTime, String traceId) {
        String patientTraceId = TraceIdGenerator.generateWithPatient(inpatient.getPatientId());
        String patientIdMasked = maskPatientId(inpatient.getPatientId());

        try {
            // 查询MongoDB patient文档
            Document patient = findMongoPatient(inpatient.getPatientId());
            if (patient == null) {
                log.warn("STEP_01_PATIENT_SELECTED traceId={} patientId={} 未找到MongoDB患者记录", patientTraceId, patientIdMasked);
                return;
            }

            String pid = getMongoPid(patient);
            if (pid == null) {
                log.warn("STEP_01_PATIENT_SELECTED traceId={} patientId={} 无法获取MongoDB pid", patientTraceId, patientIdMasked);
                return;
            }

            ClinicalTimeWindow window = timeWindowService.buildVitalPointWindow(planTime.toLocalDate(), planTime.getHour());

            // 处理体温
            processVitalSign(traceId, patientTraceId, pid, patient, planTime,
                    "param_T", temperatureHandler, window);

            // 处理脉搏（统一查询，避免重复）
            processVitalSign(traceId, patientTraceId, pid, patient, planTime,
                    "param_脉搏", pulseHandler, window);
            // 如果没有param_脉搏，尝试param_PR
            processPulseWithFallback(traceId, patientTraceId, pid, patient, planTime, window);

            // 处理心率
            processVitalSign(traceId, patientTraceId, pid, patient, planTime,
                    "param_HR", heartRateHandler, window);

            // 处理呼吸
            processVitalSign(traceId, patientTraceId, pid, patient, planTime,
                    "param_resp", breathHandler, window);

            // 处理血压（血压Handler内部会查询收缩压和舒张压）
            processBloodPressure(traceId, patientTraceId, pid, patient, planTime, window);

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
                    pushService.push(payload, patientTraceId);
                }
            }
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} pid={} 脉搏处理异常",
                    patientTraceId, pid, e);
        }
    }

    /**
     * 处理血压（血压Handler内部会查询收缩压和舒张压）
     */
    private void processBloodPressure(String parentTraceId, String patientTraceId, String pid,
                                       Document patient, LocalDateTime planTime, ClinicalTimeWindow window) {
        try {
            // 血压Handler会自己查询收缩压和舒张压，传入null即可
            VitalSignPayload payload = bloodPressureHandler.handle(null, patient, planTime, patientTraceId);
            if (payload != null) {
                pushService.push(payload, patientTraceId);
            }
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} pid={} 血压处理异常",
                    patientTraceId, pid, e);
        }
    }

    private void processVitalSign(String parentTraceId, String patientTraceId, String pid,
                                   Document patient, LocalDateTime planTime,
                                   String sourceCode, BaseVitalSignHandler handler,
                                   ClinicalTimeWindow window) {
        try {
            Date startTime = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Date endTime = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("code").is(sourceCode)
                    .and("time").gte(startTime).lt(endTime)
                    .and("valid").is(true));

            Document bedside = mongoTemplate.findOne(query, Document.class, "bedside");
            if (bedside == null) {
                return;
            }

            VitalSignPayload payload = handler.handle(bedside, patient, planTime, patientTraceId);
            if (payload != null) {
                pushService.push(payload, patientTraceId);
            }
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} pid={} code={} 处理异常",
                    patientTraceId, pid, sourceCode, e);
        }
    }

    private Document findMongoPatient(String patientId) {
        // 尝试通过mrn或hisPid查询
        Query query = new Query(Criteria.where("mrn").is(patientId)
                .orOperator(Criteria.where("hisPid").is(patientId)));
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
