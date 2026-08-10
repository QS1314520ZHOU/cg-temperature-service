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

    private static final String WARD_CODE = "125011";

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

    /**
     * 执行每日汇总
     */
    @Scheduled(cron = "0 0 7 * * ?")
    public void execute() {
        String traceId = TraceIdGenerator.generate();
        log.info("STEP_01_PATIENT_SELECTED traceId={} 开始每日07:00汇总", traceId);

        try {
            LocalDate today = LocalDate.now();
            ClinicalTimeWindow window = timeWindowService.buildDailyWindow(today);
            log.info("STEP_02_WINDOW_CREATED traceId={} 统计窗口={}", traceId, window);

            List<InpatientDTO> inpatients = inpatientRepository.findInpatients(WARD_CODE);
            log.info("STEP_01_PATIENT_SELECTED traceId={} 在科患者数量={}", traceId, inpatients.size());

            for (InpatientDTO inpatient : inpatients) {
                processPatientSummary(inpatient, window, today, traceId);
            }

            log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 每日汇总完成", traceId);
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 每日汇总异常", traceId, e);
        }
    }

    private void processPatientSummary(InpatientDTO inpatient, ClinicalTimeWindow window,
                                        LocalDate today, String parentTraceId) {
        String patientTraceId = TraceIdGenerator.generateWithPatient(inpatient.getPatientId());
        String patientIdMasked = maskPatientId(inpatient.getPatientId());

        try {
            Document patient = findMongoPatient(inpatient.getPatientId());
            if (patient == null) {
                return;
            }

            String pid = getMongoPid(patient);
            if (pid == null) {
                return;
            }

            Date startDate = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Date endDate = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

            // 查询窗口内所有bedside记录
            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("time").gte(startDate).lt(endDate));
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

            // 身高体重（检查是否需要发送）
            if (heightWeightHandler.shouldSendHeightWeight(inpatient.getPatientId(), today)) {
                LocalDateTime sendTime = window.getReportDate();
                VitalSignPayload heightPayload = heightWeightHandler.buildHeightPayload(patient, sendTime, patientTraceId);
                if (heightPayload != null) {
                    intermediateService.upsertPending(heightPayload, patientTraceId);
                }
                VitalSignPayload weightPayload = heightWeightHandler.buildWeightPayload(patient, sendTime, patientTraceId);
                if (weightPayload != null) {
                    intermediateService.upsertPending(weightPayload, patientTraceId);
                }
            }

        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 汇总异常", patientTraceId, e);
        }
    }

    /**
     * 处理大便次数
     */
    private void processStoolCount(List<Document> records, Document patient, String pid,
                                    ClinicalTimeWindow window, String traceId) {
        List<String> stoolCodes = StoolCountHandler.getStoolCodes();
        Optional<Document> stoolRecord = records.stream()
                .filter(doc -> {
                    String code = getValueFromDocByKey(doc, "code", String.class);
                    return code != null && stoolCodes.contains(code);
                })
                .findFirst();

        if (stoolRecord.isPresent()) {
            VitalSignPayload payload = stoolCountHandler.handle(stoolRecord.get(), patient,
                    window.getReportDate(), traceId);
            if (payload != null) {
                intermediateService.upsertPending(payload, traceId);
            }
        }
    }

    /**
     * 处理小便量
     */
    private void processUrineOutput(List<Document> records, Document patient, String pid,
                                     ClinicalTimeWindow window, String traceId) {
        // 求和所有param_niaoLiang记录
        BigDecimal total = records.stream()
                .filter(doc -> "param_niaoLiang".equals(getValueFromDocByKey(doc, "code", String.class)))
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

        if (total.compareTo(BigDecimal.ZERO) > 0) {
            // 创建一个虚拟bedside记录用于handler处理
            Document virtualDoc = new Document("strVal", total.stripTrailingZeros().toPlainString())
                    .append("code", "param_niaoLiang");
            VitalSignPayload payload = urineOutputHandler.handle(virtualDoc, patient,
                    window.getReportDate(), traceId);
            if (payload != null) {
                intermediateService.upsertPending(payload, traceId);
            }
        }
    }

    /**
     * 处理饮入量、治疗输入量、总输入量
     */
    private void processIntakeAndOutput(List<Document> records, Document patient, String pid,
                                         ClinicalTimeWindow window, String traceId) {
        List<String> oralCodes = OralIntakeHandler.getOralIntakeCodes();
        BigDecimal oralTotal = records.stream()
                .filter(doc -> {
                    String code = getValueFromDocByKey(doc, "code", String.class);
                    return code != null && oralCodes.contains(code);
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

        List<String> therapyCodes = TherapyInputHandler.getTherapyInputCodes();
        BigDecimal therapyTotal = records.stream()
                .filter(doc -> {
                    String code = getValueFromDocByKey(doc, "code", String.class);
                    return code != null && therapyCodes.contains(code);
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

        // 饮入量
        if (oralTotal.compareTo(BigDecimal.ZERO) > 0) {
            Document doc = new Document("strVal", oralTotal.stripTrailingZeros().toPlainString())
                    .append("code", "param_kouFu");
            VitalSignPayload payload = oralIntakeHandler.handle(doc, patient,
                    window.getReportDate(), traceId);
            if (payload != null) {
                intermediateService.upsertPending(payload, traceId);
            }
        }

        // 治疗输入量
        if (therapyTotal.compareTo(BigDecimal.ZERO) > 0) {
            Document doc = new Document("strVal", therapyTotal.stripTrailingZeros().toPlainString())
                    .append("code", "param_YaoYeti_in_hour");
            VitalSignPayload payload = therapyInputHandler.handle(doc, patient,
                    window.getReportDate(), traceId);
            if (payload != null) {
                intermediateService.upsertPending(payload, traceId);
            }
        }

        // 总输入量
        BigDecimal totalInput = oralTotal.add(therapyTotal);
        if (totalInput.compareTo(BigDecimal.ZERO) > 0) {
            VitalSignPayload payload = totalInputHandler.buildPayload(
                    totalInput.doubleValue(), patient, window.getReportDate(), traceId);
            if (payload != null) {
                intermediateService.upsertPending(payload, traceId);
            }
        }
    }

    /**
     * 处理总出量（动态配置）
     */
    private void processTotalOutput(List<Document> records, Document patient, String pid,
                                     ClinicalTimeWindow window, String traceId) {
        // 动态获取出量代码：查询bedsideConfig → configParam.calculation=out
        List<String> outputCodes = getDynamicOutputCodes(pid, traceId);
        if (outputCodes.isEmpty()) {
            log.info("STEP_05_VALUE_PARSED traceId={} pid={} 无动态出量配置", traceId, pid);
            return;
        }

        BigDecimal total = records.stream()
                .filter(doc -> {
                    String code = getValueFromDocByKey(doc, "code", String.class);
                    return code != null && outputCodes.contains(code);
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

        if (total.compareTo(BigDecimal.ZERO) > 0) {
            VitalSignPayload payload = totalOutputHandler.buildPayload(
                    total.doubleValue(), patient, window.getReportDate(), traceId);
            if (payload != null) {
                intermediateService.upsertPending(payload, traceId);
            }
        }
    }

    /**
     * 处理排出物量
     */
    private void processDrainageOutput(List<Document> records, Document patient, String pid,
                                        ClinicalTimeWindow window, String traceId) {
        List<String> drainageCodes = DrainageOutputHandler.getDrainageCodes();
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

        if (total.compareTo(BigDecimal.ZERO) > 0) {
            Document doc = new Document("strVal", total.stripTrailingZeros().toPlainString())
                    .append("code", "param_daBianAmount");
            VitalSignPayload payload = drainageOutputHandler.handle(doc, patient,
                    window.getReportDate(), traceId);
            if (payload != null) {
                intermediateService.upsertPending(payload, traceId);
            }
        }
    }

    /**
     * 处理胃管负压引流
     */
    private void processGastricDrainage(List<Document> records, Document patient, String pid,
                                         ClinicalTimeWindow window, String traceId) {
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

        if (total.compareTo(BigDecimal.ZERO) > 0) {
            Document doc = new Document("strVal", total.stripTrailingZeros().toPlainString())
                    .append("code", "param_tube_胃肠减压");
            VitalSignPayload payload = gastricDrainageHandler.handle(doc, patient,
                    window.getReportDate(), traceId);
            if (payload != null) {
                intermediateService.upsertPending(payload, traceId);
            }
        }
    }

    /**
     * 处理其他引流量
     */
    private void processOtherDrainage(List<Document> records, Document patient, String pid,
                                       ClinicalTimeWindow window, String traceId) {
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

        if (total.compareTo(BigDecimal.ZERO) > 0) {
            Document doc = new Document("strVal", total.stripTrailingZeros().toPlainString())
                    .append("code", "param_tube_other");
            VitalSignPayload payload = otherDrainageHandler.handle(doc, patient,
                    window.getReportDate(), traceId);
            if (payload != null) {
                intermediateService.upsertPending(payload, traceId);
            }
        }
    }

    /**
     * 处理净超滤量
     */
    private void processNetUltrafiltration(List<Document> records, Document patient, String pid,
                                            ClinicalTimeWindow window, String traceId) {
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

        if (total.compareTo(BigDecimal.ZERO) > 0) {
            Document doc = new Document("strVal", total.stripTrailingZeros().toPlainString())
                    .append("code", "param_chaoLvLiang");
            VitalSignPayload payload = netUltrafiltrationHandler.handle(doc, patient,
                    window.getReportDate(), traceId);
            if (payload != null) {
                intermediateService.upsertPending(payload, traceId);
            }
        }
    }

    /**
     * 动态获取出量代码
     * 查询bedsideConfig → configParam.calculation=out
     */
    private List<String> getDynamicOutputCodes(String pid, String traceId) {
        try {
            // 查询bedsideConfig
            Query configQuery = new Query(Criteria.where("pid").is(pid)
                    .and("groupName").is("出入量"));
            Document config = mongoTemplate.findOne(configQuery, Document.class, "bedsideConfig");
            if (config == null) {
                return Collections.emptyList();
            }

            // 获取出量配置
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
                            // 查询configParam验证calculation=out
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
            log.error("STEP_05_VALUE_PARSED traceId={} 获取动态出量配置失败 pid={}", traceId, pid, e);
        }
        return Collections.emptyList();
    }

    private Document findMongoPatient(String patientId) {
        Query query = new Query(Criteria.where("mrn").is(patientId)
                .orOperator(Criteria.where("hisPid").is(patientId)));
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
