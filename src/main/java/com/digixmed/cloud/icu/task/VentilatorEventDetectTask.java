package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import com.digixmed.cloud.icu.service.IntermediateService;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bson.types.ObjectId;

/**
 * 呼吸机事件实时检测任务
 *
 * 业务目的：检测呼吸机启用/停止事件，立即推送，不等标准时间点
 *
 * 检测逻辑：
 *   1. 查询所有患者的最新 param_XiYangTuJing 记录
 *   2. 与内存中上次状态比对
 *   3. 状态变化时立即推送（planTime = recordTime = 记录实际时间）
 *
 * 检测范围：
 *   - USING（启用）：新记录的 strVal 为"无创"/"有创"/"高流量湿化氧疗"之一
 *   - STOPPED（停止）：上一次是 USING，当前最新记录的 strVal 不再是上述三种值
 *   - NOT_USING → USING：新记录出现呼吸机标识
 *   - USING → NOT_USING：由标准时间点扫描（BreathHandler）处理
 *
 * 注意：USING → NOT_USING 的检测依赖标准时间点扫描，
 *       因为 NOT_USING 是"没有记录"，无法通过查询新记录来检测。
 *       BreathHandler 在标准时间点检测到此转换时会立即推送。
 */
@Component
public class VentilatorEventDetectTask {

    private static final Logger log = LoggerFactory.getLogger(VentilatorEventDetectTask.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String BEDSIDE = "bedside";
    private static final String VENTILATOR_ROUTE_CODE = "param_XiYangTuJing";
    private static final Set<String> VENTILATOR_FLAGS = Set.of("无创", "有创", "高流量湿化氧疗");

    /** 呼吸机事件检测开关 */
    @Value("${vitalsign.ventilator-detect-enabled:false}")
    private boolean ventilatorDetectEnabled;

    /** 查询窗口：最近12小时有 param_XiYangTuJing 记录的患者才纳入检测 */
    private static final int LOOKBACK_HOURS = 12;

    /** 上次处理的各患者最新呼吸机记录时间（用于检测新记录） */
    private final ConcurrentHashMap<String, Date> lastProcessedTime = new ConcurrentHashMap<>();

    /** 上次推送的各患者呼吸机状态：true=使用中, false=未使用 */
    private final ConcurrentHashMap<String, Boolean> lastPushedState = new ConcurrentHashMap<>();

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private IntermediateService intermediateService;

    @Autowired
    private PatientIdentityMapper patientIdentityMapper;

    @Autowired
    private ClinicalTimeWindowService timeWindowService;

    @Autowired
    private PushTask pushTask;

    /**
     * 每2分钟检测一次呼吸机事件
     */
    @Scheduled(cron = "0 */2 * * * ?")
    public void detect() {
        if (!ventilatorDetectEnabled) {
            log.debug("VENTILATOR_DETECT ventilator-detect-enabled=false, skip");
            return;
        }
        String traceId = TraceIdGenerator.generate();
        LocalDateTime now = timeWindowService.now();
        Date nowDate = Date.from(now.atZone(ZONE).toInstant());

        log.info("VENTILATOR_DETECT traceId={} 开始呼吸机事件检测", traceId);

        try {
            // 查询最近12小时内有 param_XiYangTuJing 记录的患者（避免全表扫描）
            Date lookbackStart = Date.from(now.minusHours(LOOKBACK_HOURS).atZone(ZONE).toInstant());
            Query pidsQuery = new Query(Criteria.where("code").is(VENTILATOR_ROUTE_CODE)
                    .and("valid").ne(false).ne(0)
                    .and("time").gte(lookbackStart));
            Set<String> allPids = new java.util.LinkedHashSet<>(
                    mongoTemplate.findDistinct(pidsQuery, "pid", BEDSIDE, String.class));

            if (allPids.isEmpty()) {
                log.debug("VENTILATOR_DETECT traceId={} 无呼吸机记录患者", traceId);
                return;
            }

            // 批量查询患者
            List<ObjectId> objectIds = new java.util.ArrayList<>();
            for (String pid : allPids) {
                try {
                    objectIds.add(new ObjectId(pid));
                } catch (Exception e) {
                    // skip invalid
                }
            }
            if (objectIds.isEmpty()) {
                return;
            }
            Map<String, Document> patientMap = findPatientsByPids(objectIds);

            int eventCount = 0;
            for (String pid : allPids) {
                Document patient = patientMap.get(pid);
                if (patient == null) {
                    continue;
                }

                VentilatorEvent event = detectEvent(pid, nowDate, traceId);
                if (event != null) {
                    pushEvent(event, patient, traceId);
                    eventCount++;
                }
            }

            log.info("VENTILATOR_DETECT traceId={} 检测完成 患者数={} 事件数={}",
                    traceId, allPids.size(), eventCount);

            if (eventCount > 0) {
                try {
                    pushTask.pushOnce(traceId);
                } catch (Exception pe) {
                    log.error("VENTILATOR_DETECT traceId={} 推送异常", traceId, pe);
                }
            }
        } catch (Exception e) {
            log.error("VENTILATOR_DETECT traceId={} 检测异常", traceId, e);
        }
    }

    /**
     * 检测单个患者的呼吸机事件
     *
     * @return 检测到事件时返回 VentilatorEvent，否则返回 null
     */
    private VentilatorEvent detectEvent(String pid, Date nowDate, String traceId) {
        // 查询该患者最新的呼吸机记录
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(VENTILATOR_ROUTE_CODE)
                .and("valid").ne(false).ne(0))
                .with(Sort.by(Sort.Direction.DESC, "time"))
                .limit(1);

        Document record = mongoTemplate.findOne(query, Document.class, BEDSIDE);
        if (record == null) {
            return null;
        }
        // 二次过滤：排除 valid=false 的作废记录
        Object valid = record.get("valid");
        if (Boolean.FALSE.equals(valid) || Integer.valueOf(0).equals(valid)) {
            return null;
        }

        Date recordTime = record.getDate("time");
        if (recordTime == null) {
            return null;
        }

        String mode = record.getString("strVal");
        boolean isVentilatorActive = mode != null && VENTILATOR_FLAGS.contains(mode.trim());

        // 检查是否是新记录（比上次处理的时间晚）
        Date lastTime = lastProcessedTime.get(pid);
        boolean isNewRecord = lastTime == null || recordTime.after(lastTime);

        // 更新处理时间
        lastProcessedTime.put(pid, recordTime);

        Boolean previousState = lastPushedState.get(pid);

        // 记录当前呼吸机状态详情（便于日志分析）
        log.info("VENTILATOR_STATUS traceId={} pid={} currentMode={} isVentilatorActive={} isNewRecord={} lastTime={} recordTime={}",
                traceId, pid, mode, isVentilatorActive, isNewRecord,
                lastTime != null ? lastTime.getTime() : "null",
                recordTime.getTime());

        if (isNewRecord) {
            if (previousState == null) {
                // 首次检测：查询当前时间之前最近的一条呼吸机记录（不限时间范围）
                Query historyQuery = new Query(Criteria.where("pid").is(pid)
                        .and("code").is(VENTILATOR_ROUTE_CODE)
                        .and("time").lt(nowDate)
                        .and("valid").ne(false).ne(0))
                        .with(Sort.by(Sort.Direction.DESC, "time"))
                        .limit(1);

                Document historyRecord = mongoTemplate.findOne(historyQuery, Document.class, BEDSIDE);
                boolean previousWasActive = false;
                String historyMode = null;
                if (historyRecord != null) {
                    historyMode = historyRecord.getString("strVal");
                    previousWasActive = historyMode != null && VENTILATOR_FLAGS.contains(historyMode.trim());
                }

                // 初始化状态
                lastPushedState.put(pid, isVentilatorActive);

                log.info("VENTILATOR_DETECT traceId={} pid={} 首次检测 历史mode={} 历史isVentilatorActive={} 当前isVentilatorActive={}",
                        traceId, pid, historyMode, previousWasActive, isVentilatorActive);

                // 只有当历史状态不是使用呼吸机，且当前状态是使用呼吸机时才推送
                if (isVentilatorActive && !previousWasActive) {
                    LocalDateTime eventTime = recordTime.toInstant().atZone(ZONE).toLocalDateTime();
                    log.info("VENTILATOR_DETECT traceId={} pid={} 事件=USING(首次检测) 时间={} mode={} 历史状态={}",
                            traceId, pid, eventTime, mode, previousWasActive ? "使用中" : "未使用");
                    return new VentilatorEvent(pid, "USING", mode, eventTime);
                }
                log.debug("VENTILATOR_DETECT traceId={} pid={} 首次初始化 state={} 历史状态={}",
                        traceId, pid, isVentilatorActive, previousWasActive ? "使用中" : "未使用");
                return null;
            }

            if (isVentilatorActive && !previousState) {
                // NOT_USING → USING：新记录出现呼吸机标识
                lastPushedState.put(pid, true);
                LocalDateTime eventTime = recordTime.toInstant().atZone(ZONE).toLocalDateTime();
                log.info("VENTILATOR_DETECT traceId={} pid={} 事件=USING 时间={} mode={}",
                        traceId, pid, eventTime, mode);
                return new VentilatorEvent(pid, "USING", mode, eventTime);
            }

            if (!isVentilatorActive && previousState) {
                // USING → NOT_USING：新记录不再是呼吸机标识
                lastPushedState.put(pid, false);
                LocalDateTime eventTime = recordTime.toInstant().atZone(ZONE).toLocalDateTime();
                log.info("VENTILATOR_DETECT traceId={} pid={} 事件=STOPPED 时间={}", traceId, pid, eventTime);
                return new VentilatorEvent(pid, "STOPPED", null, eventTime);
            }
        }

        return null;
    }

    /**
     * 推送呼吸机事件
     */
    private void pushEvent(VentilatorEvent event, Document patient, String traceId) {
        String patientId = patientIdentityMapper.getPatientId(patient);
        String patientTraceId = TraceIdGenerator.generateWithPatient(event.pid);

        VitalSignPayload.VitalSignPayloadBuilder builder = VitalSignPayload.builder()
                .vitalsignName("呼吸")
                .vitalsignType("1004")
                .unit("")
                .planTime(event.eventTime)
                .recordTime(event.eventTime);

        if ("USING".equals(event.type)) {
            builder.vitalsignSVal1("使用呼吸机")
                    .vitalsignNVal1("");
        } else {
            builder.vitalsignSVal1("停止呼吸机")
                    .vitalsignNVal1("");
        }

        VitalSignPayload payload = builder.build();

        // 设置患者身份
        payload.setPatientId(patientId);
        payload.setMrn(patientIdentityMapper.getMrn(patient));
        payload.setPatientName(patientIdentityMapper.getPatientName(patient));
        payload.setSeries(patientIdentityMapper.getSeries(patient));
        payload.setWardCode(patientIdentityMapper.getWardCode());

        // 护士解析：param_Yishi → editUser → responsibleNurse → 默认
        resolveEventNurse(payload, event.pid, event.eventTime, patient, traceId);

        payload.setIsValid(1);
        payload.setRemark("");
        payload.setTraceId(patientTraceId);
        payload.setMongoPid(event.pid);

        intermediateService.upsertPending(payload, patientTraceId);
        log.info("VENTILATOR_DETECT traceId={} pid={} 事件={} planTime={} 已入队",
                patientTraceId, event.pid, event.type, event.eventTime);
    }

    /**
     * 呼吸机事件护士解析：param_Yishi → editUser → responsibleNurse → 默认
     */
    private void resolveEventNurse(VitalSignPayload payload, String pid, LocalDateTime eventTime,
                                    Document patient, String traceId) {
        Date eventDate = Date.from(eventTime.atZone(ZONE).toInstant());

        // 1. param_Yishi at eventTime
        try {
            Query query = new Query(Criteria.where("pid").is(pid)
                    .and("code").is("param_Yishi")
                    .and("valid").ne(false).ne(0)
                    .and("time").is(eventDate));
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
                            ObjectId accountId = new ObjectId(editUser);
                            Query accountQuery = new Query(Criteria.where("_id").is(accountId));
                            Document account = mongoTemplate.findOne(accountQuery, Document.class, "account");
                            if (account != null) {
                                String username = account.getString("username");
                                String trueName = account.getString("trueName");
                                if (trueName != null && !trueName.trim().isEmpty()) {
                                    payload.setRecordNurseId(username != null ? username.trim() : "");
                                    payload.setRecordNurseName(trueName.trim());
                                    log.info("VENTILATOR_NURSE traceId={} pid={} nurseId={} nurseName={} source=param_Yishi",
                                            traceId, pid, payload.getRecordNurseId(), payload.getRecordNurseName());
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            log.warn("VENTILATOR_NURSE traceId={} pid={} 查询account失败: {}", traceId, pid, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("VENTILATOR_NURSE traceId={} pid={} 查询param_Yishi异常: {}", traceId, pid, e.getMessage());
        }

        // 2. responsibleNurse
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
                            log.info("VENTILATOR_NURSE traceId={} pid={} nurseId={} nurseName={} source=responsibleNurse",
                                    traceId, pid, payload.getRecordNurseId(), payload.getRecordNurseName());
                            return;
                        }
                    }
                } catch (Exception e) {
                    log.warn("VENTILATOR_NURSE traceId={} pid={} responsibleNurse查询异常: {}", traceId, pid, e.getMessage());
                }
            }
        }

        // 3. 默认
        payload.setRecordNurseId(patientIdentityMapper.getRecordNurseId());
        payload.setRecordNurseName("陈琳");
        log.info("VENTILATOR_NURSE traceId={} pid={} 所有解析方式均失败，使用默认值", traceId, pid);
    }

    private Map<String, Document> findPatientsByPids(java.util.List<ObjectId> ids) {
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

    private <T> T getValueFromDocByKey(Document doc, String key, Class<T> clazz) {
        if (doc == null) return null;
        Object value = doc.get(key);
        if (value == null) return null;
        if (clazz.isInstance(value)) return clazz.cast(value);
        if (clazz == String.class) return clazz.cast(value.toString());
        return null;
    }

    /**
     * 呼吸机事件内部表示
     */
    private static class VentilatorEvent {
        final String pid;
        final String type;    // "USING" or "STOPPED"
        final String mode;    // 使用时的模式值
        final LocalDateTime eventTime;

        VentilatorEvent(String pid, String type, String mode, LocalDateTime eventTime) {
            this.pid = pid;
            this.type = type;
            this.mode = mode;
            this.eventTime = eventTime;
        }
    }
}
