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

    /** 汇总扫描频率 */
    @Value("${vitalsign.summary.cron:0 0 7 * * ?}")
    private String summaryCron;

    /** 汇总回看天数（报表日） */
    @Value("${vitalsign.summary.lookback-days:1}")
    private int summaryLookbackDays;

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

    /**
     * 执行每日汇总
     */
    @Scheduled(cron = "${vitalsign.summary.cron:0 0 7 * * ?}")
    public void execute() {
        String traceId = TraceIdGenerator.generate();
        LocalDateTime now = timeWindowService.now();
        log.info("STEP_01_PATIENT_SELECTED traceId={} 开始每日汇总 now={}", traceId, now);

        try {
            // 获取需要处理的报表日列表（含回看）
            List<LocalDate> reportDates = timeWindowService.getSummaryReportDates(now, summaryLookbackDays);
            log.info("STEP_02_WINDOW_CREATED traceId={} 回看{}天，需处理报表日数量={} 日期={}",
                    traceId, summaryLookbackDays, reportDates.size(), reportDates);

            for (LocalDate reportDate : reportDates) {
                ClinicalTimeWindow window = timeWindowService.buildDailyWindow(reportDate);
                log.info("STEP_02_WINDOW_CREATED traceId={} 报表日={} 统计窗口=[{}, {})",
                        traceId, reportDate, window.getStart(), window.getEnd());

                // 准入原则：不再查金仓在科患者列表，只看 bedside 是否有数据 + patient 集合中是否存在该 _id
                List<String> pids = findCandidatePids(window, traceId);
                log.info("STEP_01_PATIENT_SELECTED traceId={} 报表日={} 候选患者数量={}", traceId, reportDate, pids.size());

                for (String pid : pids) {
                    Document patient = findMongoPatientByPid(pid);
                    if (patient == null) {
                        log.info("STEP_01_PATIENT_SKIPPED traceId={} pid={} patient集合中不存在该_id，不回传", traceId, pid);
                        continue;
                    }
                    processPatientSummary(pid, patient, window, reportDate, traceId);
                }
            }

            log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 每日汇总完成", traceId);
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 每日汇总异常", traceId, e);
        }
    }

    private void processPatientSummary(String pid, Document patient, ClinicalTimeWindow window,
                                        LocalDate reportDate, String parentTraceId) {
        String hisPatientId = getValueFromDocByKey(patient, "mrn", String.class);
        String patientTraceId = TraceIdGenerator.generateWithPatient(pid);
        String patientIdMasked = maskPatientId(hisPatientId);

        try {
            Date startDate = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Date endDate = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

            // 查询窗口内所有bedside记录
            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("valid").ne(false)
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
            if (heightWeightHandler.shouldSendHeightWeight(hisPatientId, reportDate, patient)) {
                LocalDateTime sendTime = window.getReportDate();
                NurseRef nurse = heightWeightNurseService.resolve(pid);
                VitalSignPayload heightPayload = heightWeightHandler.buildHeightPayload(
                        patient, sendTime, nurse, patientTraceId);
                if (heightPayload != null) {
                    PayloadTimeNormalizer.anchor(heightPayload, sendTime);
                    intermediateService.upsertPending(heightPayload, patientTraceId);
                }
                VitalSignPayload weightPayload = heightWeightHandler.buildWeightPayload(
                        patient, sendTime, nurse, patientTraceId);
                if (weightPayload != null) {
                    PayloadTimeNormalizer.anchor(weightPayload, sendTime);
                    intermediateService.upsertPending(weightPayload, patientTraceId);
                }
            }

        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 汇总异常", patientTraceId, e);
        }
    }

    /**
     * 处理大便次数
     * 只使用 param_汇总大便次数，只获取07:00时间点的数据
     */
    private void processStoolCount(List<Document> records, Document patient, String pid,
                                    ClinicalTimeWindow window, String traceId) {
        // 大便次数窗口 [07:00, 08:00)
        ClinicalTimeWindow stoolWindow = timeWindowService.buildSevenAmWindow(window.getReportDate());
        Date startDate = Date.from(stoolWindow.getStart().atZone(ZONE).toInstant());
        Date endDate = Date.from(stoolWindow.getEnd().atZone(ZONE).toInstant());

        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is("param_汇总大便次数")
                .and("valid").ne(false)
                .and("time").gte(startDate).lt(endDate))
                .with(Sort.by(Sort.Direction.DESC, "editTime"))
                .limit(1);
        Document stoolRecord = mongoTemplate.findOne(query, Document.class, "bedside");

        if (stoolRecord != null) {
            VitalSignPayload payload = stoolCountHandler.handle(stoolRecord, patient,
                    window.getReportDate(), traceId);
            if (payload != null) {
                PayloadTimeNormalizer.anchor(payload, window.getReportDate());
                intermediateService.upsertPending(payload, traceId);
            }
        }
    }

    /**
     * 构造虚拟 Document 并设置 time 字段，保证 handler 取到 planTime
     */
    private Document virtualDoc(String strVal, String code, ClinicalTimeWindow window) {
        Document doc = new Document();
        doc.append("strVal", strVal);
        doc.append("code", code);
        Date time = Date.from(window.getReportDate().atZone(ZONE).toInstant());
        doc.append("time", time);
        return doc;
    }

    /**
     * 调用 handler 构建 payload 并写入队列
     */
    private void enqueue(DocHandler handler, Document doc, Document patient,
                         ClinicalTimeWindow window, String traceId) {
        VitalSignPayload payload = handler.handle(doc, patient, window.getReportDate(), traceId);
        if (payload != null) {
            PayloadTimeNormalizer.anchor(payload, window.getReportDate());
            intermediateService.upsertPending(payload, traceId);
        }
    }

    /**
     * 安全累加
     */
    private BigDecimal sum(BigDecimal a, BigDecimal b) {
        return a.add(b);
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
            Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_niaoLiang", window);
            enqueue(urineOutputHandler, vDoc, patient, window, traceId);
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
            Document vDoc = virtualDoc(oralTotal.stripTrailingZeros().toPlainString(), "param_kouFu", window);
            enqueue(oralIntakeHandler, vDoc, patient, window, traceId);
        }

        // 治疗输入量
        if (therapyTotal.compareTo(BigDecimal.ZERO) > 0) {
            Document vDoc = virtualDoc(therapyTotal.stripTrailingZeros().toPlainString(), "param_YaoYeti_in_hour", window);
            enqueue(therapyInputHandler, vDoc, patient, window, traceId);
        }

        // 总输入量
        BigDecimal totalInput = sum(oralTotal, therapyTotal);
        if (totalInput.compareTo(BigDecimal.ZERO) > 0) {
            Document vDoc = virtualDoc(totalInput.stripTrailingZeros().toPlainString(), "param_zongRuliang", window);
            enqueue(totalInputHandler, vDoc, patient, window, traceId);
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
            Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_zongChuLiang", window);
            enqueue(totalOutputHandler, vDoc, patient, window, traceId);
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
            Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_daBianAmount", window);
            enqueue(drainageOutputHandler, vDoc, patient, window, traceId);
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
            Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_tube_胃肠减压", window);
            enqueue(gastricDrainageHandler, vDoc, patient, window, traceId);
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
            Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_tube_other", window);
            enqueue(otherDrainageHandler, vDoc, patient, window, traceId);
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
            Document vDoc = virtualDoc(total.stripTrailingZeros().toPlainString(), "param_chaoLvLiang", window);
            enqueue(netUltrafiltrationHandler, vDoc, patient, window, traceId);
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

    /**
     * 本轮候选患者：统计窗口内有 bedside 数据的 pid（去重）。
     * 不依赖金仓在科列表，是否回传只看 patient 集合中是否存在该 _id。
     */
    private List<String> findCandidatePids(ClinicalTimeWindow window, String traceId) {
        try {
            Date startDate = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Date endDate = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

            Query query = new Query(Criteria.where("time").gte(startDate).lt(endDate));
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
