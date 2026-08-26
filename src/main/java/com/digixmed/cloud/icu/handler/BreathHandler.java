package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VentilatorState;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.service.ClinicalTimeWindowService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Set;

/**
 * 呼吸处理器
 *
 * 业务目的：处理呼吸体征，支持呼吸机状态判断
 * 源数据：param_resp.strVal（呼吸频率）, param_XiYangTuJing.strVal（给氧途径）
 * 输出：vitalsignName=呼吸, vitalsignType=1004, unit=次/分
 *
 * 呼吸机状态判断（基于param_XiYangTuJing.strVal）：
 *   A. 正在使用呼吸机（USING）：
 *      - 当前业务时间窗口内param_XiYangTuJing的strVal为"无创"/"有创"/"高流量湿化氧疗"之一
 *      - vitalsignSVal1 = "使用呼吸机"
 *      - vitalsignNVal1 = 空
 *
 *   B. 普通呼吸（NOT_USING）：
 *      - 当前没有有效呼吸机标识
 *      - 上一个已知状态也不是使用呼吸机
 *      - param_resp.strVal → vitalsignNVal1
 *      - vitalsignSVal1 = 空
 *
 *   C. 停止呼吸机（STOPPED）：
 *      - 上一个标准时间点或上一个有效状态为使用呼吸机
 *      - 当前标准时间点param_XiYangTuJing的strVal不再是"无创"/"有创"/"高流量湿化氧疗"
 *      - vitalsignSVal1 = "停止呼吸机"
 *      - vitalsignNVal1 = 空
 *      - 同一个停止事件只生成一次
 */
@Component
public class BreathHandler extends BaseVitalSignHandler {

    private static final String BREATH_CODE = "param_resp";
    /** 呼吸机给氧途径代码 */
    private static final String VENTILATOR_ROUTE_CODE = "param_XiYangTuJing";
    /** 呼吸机标识判定值 */
    private static final Set<String> VENTILATOR_FLAGS = Set.of("无创", "有创", "高流量湿化氧疗");

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ClinicalTimeWindowService timeWindowService;

    public BreathHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        String pid = getValueFromDocByKey(patient, "_id", Object.class) != null
                ? getValueFromDocByKey(patient, "_id", Object.class).toString() : null;
        if (pid == null) {
            log.warn("STEP_04 traceId={} 患者pid为空", traceId);
            return null;
        }

        // 使用业务时间窗口查询
        ClinicalTimeWindow window = timeWindowService.buildVitalPointWindow(planTime.toLocalDate(), planTime.getHour());
        if (window == null) {
            log.warn("STEP_04 traceId={} pid={} 无法构建时间窗口 planTime={}", traceId, pid, planTime);
            return null;
        }
        Date startTime = Date.from(window.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Date endTime = Date.from(window.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

        // 查询当前时间窗口的呼吸机模式
        VentilatorState currentState = resolveVentilatorState(pid, startTime, endTime, traceId);
        // 查询上一个时间点的呼吸机状态
        VentilatorState previousState = resolvePreviousVentilatorState(pid, planTime, traceId);

        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} pid={} ventilatorCurrent={} ventilatorPrevious={}",
                traceId, pid, currentState, previousState);

        VitalSignPayload payload;

        if (previousState == VentilatorState.NOT_USING && currentState == VentilatorState.USING) {
            // 转换点：从未使用 -> 使用呼吸机（只在开始使用时发一次）
            payload = VitalSignPayload.builder()
                    .vitalsignName("呼吸")
                    .vitalsignType("1004")
                    .vitalsignNVal1("")
                    .vitalsignSVal1(VentilatorState.USING.getDisplayName())
                    .unit("")
                    .build();
            log.info("STEP_04 traceId={} pid={} 呼吸机启用事件 NOT_USING->USING", traceId, pid);
        } else if (previousState == VentilatorState.USING && currentState == VentilatorState.NOT_USING) {
            // 转换点：从使用 -> 停止呼吸机（只发一次）
            payload = VitalSignPayload.builder()
                    .vitalsignName("呼吸")
                    .vitalsignType("1004")
                    .vitalsignNVal1("")
                    .vitalsignSVal1(VentilatorState.STOPPED.getDisplayName())
                    .unit("")
                    .build();
            log.info("STEP_04 traceId={} pid={} 呼吸机停止事件 USING->NOT_USING", traceId, pid);
        } else if (previousState == VentilatorState.USING && currentState == VentilatorState.USING) {
            // 持续使用呼吸机：发呼吸具体值，不发"使用呼吸机"
            String breathStr = null;
            if (bedside != null) {
                breathStr = getValueFromDocByKey(bedside, "strVal", String.class);
            }
            Double breathValue = parseDouble(breathStr);

            payload = VitalSignPayload.builder()
                    .vitalsignName("呼吸")
                    .vitalsignType("1004")
                    .vitalsignNVal1(breathValue != null ? formatDouble(breathValue) : "")
                    .vitalsignSVal1("")
                    .unit("次/分")
                    .build();
            log.info("STEP_04 traceId={} pid={} 持续使用呼吸机 发呼吸值", traceId, pid);
        } else {
            // 普通呼吸（从未使用呼吸机）
            String breathStr = null;
            if (bedside != null) {
                breathStr = getValueFromDocByKey(bedside, "strVal", String.class);
            }
            Double breathValue = parseDouble(breathStr);

            payload = VitalSignPayload.builder()
                    .vitalsignName("呼吸")
                    .vitalsignType("1004")
                    .vitalsignNVal1(breathValue != null ? formatDouble(breathValue) : "")
                    .vitalsignSVal1("")
                    .unit("次/分")
                    .build();
        }

        fillCommonFields(payload, patient, bedside, mongoTemplate, traceId);
        return payload;
    }

    /**
     * 解析当前时间窗口的呼吸机状态
     * 使用业务时间窗口而非精确时间匹配
     */
    private VentilatorState resolveVentilatorState(String pid, Date startTime, Date endTime, String traceId) {
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(VENTILATOR_ROUTE_CODE)
                .and("time").gte(startTime).lt(endTime)
                .and("valid").ne(false).ne(0));

        Document record = mongoTemplate.findOne(query, Document.class, "bedside");
        if (record == null || isInvalidRecord(record)) {
            return VentilatorState.NOT_USING;
        }

        String strVal = getValueFromDocByKey(record, "strVal", String.class);
        if (strVal != null && VENTILATOR_FLAGS.contains(strVal.trim())) {
            return VentilatorState.USING;
        }
        return VentilatorState.NOT_USING;
    }

    /**
     * 解析上一个时间点的呼吸机状态
     * 查询当前时间点前一个小时的数据来判断
     */
    private VentilatorState resolvePreviousVentilatorState(String pid, LocalDateTime planTime, String traceId) {
        // 查询当前时间点前一个小时内的呼吸机记录
        Date currentTime = Date.from(planTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Date oneHourBefore = Date.from(planTime.minusHours(1).atZone(ZoneId.of("Asia/Shanghai")).toInstant());

        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(VENTILATOR_ROUTE_CODE)
                .and("time").gte(oneHourBefore).lt(currentTime)
                .and("valid").ne(false).ne(0)
        ).with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "time"))
                .limit(1);

        Document record = mongoTemplate.findOne(query, Document.class, "bedside");
        if (record == null || isInvalidRecord(record)) {
            // 如果前一个小时没有记录，再查询更早的记录（但不超过24小时）
            Date twentyFourHoursBefore = Date.from(planTime.minusHours(24).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
            Query extendedQuery = new Query(Criteria.where("pid").is(pid)
                    .and("code").is(VENTILATOR_ROUTE_CODE)
                    .and("time").gte(twentyFourHoursBefore).lt(currentTime)
                    .and("valid").ne(false).ne(0)
            ).with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "time"))
                    .limit(1);

            record = mongoTemplate.findOne(extendedQuery, Document.class, "bedside");
            if (record == null || isInvalidRecord(record)) {
                return VentilatorState.NOT_USING;
            }
        }

        String strVal = getValueFromDocByKey(record, "strVal", String.class);
        if (strVal != null && VENTILATOR_FLAGS.contains(strVal.trim())) {
            return VentilatorState.USING;
        }
        return VentilatorState.NOT_USING;
    }

    /**
     * 二次过滤：检查bedside记录的valid字段，排除作废记录
     */
    private boolean isInvalidRecord(Document doc) {
        Object valid = doc.get("valid");
        return Boolean.FALSE.equals(valid) || Integer.valueOf(0).equals(valid);
    }
}
