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
import java.util.List;
import java.util.Set;

/**
 * 呼吸处理器
 *
 * 业务目的：处理呼吸体征，支持呼吸机状态判断
 * 源数据：param_resp.strVal（呼吸频率）, param_XiYangTuJing.strVal（给氧途径）
 * 输出：vitalsignName=呼吸, vitalsignType=1004, unit=次/分
 *
 * 呼吸机状态判断逻辑（基于前一个标准时间点与当前时间点的数据对比）：
 *
 * 标准时间点：2, 6, 10, 14, 18, 22
 *
 * 判断流程：
 *   1. 查询前一个标准时间点窗口内的 param_XiYangTuJing 数据（previousState）
 *   2. 查询当前标准时间点窗口内的 param_XiYangTuJing 数据（currentState）
 *   3. 根据状态组合决定回传内容：
 *
 *   A. NOT_USING → USING（启用呼吸机）：
 *      - 前一时间点无呼吸机标识，当前时间点有
 *      - vitalsignSVal1 = "使用呼吸机"
 *      - vitalsignNVal1 = 空
 *
 *   B. USING → NOT_USING（停止呼吸机）：
 *      - 前一时间点有呼吸机标识，当前时间点没有
 *      - vitalsignSVal1 = "停止呼吸机"
 *      - vitalsignNVal1 = 空
 *
 *   C. USING → USING（持续使用呼吸机）：
 *      - 前后时间点都有呼吸机标识（数据没变）
 *      - 发呼吸具体值
 *      - vitalsignNVal1 = 呼吸值
 *      - vitalsignSVal1 = 空
 *
 *   D. NOT_USING → NOT_USING（普通呼吸）：
 *      - 前后时间点都没有呼吸机标识
 *      - 发呼吸具体值
 *      - vitalsignNVal1 = 呼吸值
 *      - vitalsignSVal1 = 空
 */
@Component
public class BreathHandler extends BaseVitalSignHandler {

    private static final String BREATH_CODE = "param_resp";
    /** 呼吸机给氧途径代码 */
    private static final String VENTILATOR_ROUTE_CODE = "param_XiYangTuJing";
    /** 呼吸机标识判定值 */
    private static final Set<String> VENTILATOR_FLAGS = Set.of("无创", "有创");
    /** 标准时间点（按顺序，用于判断前一个时间点） */
    private static final List<Integer> STANDARD_HOURS = List.of(2, 6, 10, 14, 18, 22);

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
        // 检查前一个标准时间点到当前时间点之间是否有状态变化
        boolean hasChangeBetween = hasVentilatorChangeBetween(pid, planTime, traceId);

        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} pid={} ventilatorCurrent={} ventilatorPrevious={} hasChangeBetween={}",
                traceId, pid, currentState, previousState, hasChangeBetween);

        VitalSignPayload payload;

        // 如果中间有状态变化，根据当前状态回传
        if (hasChangeBetween) {
            if (currentState == VentilatorState.USING) {
                // 中间有变化且当前在使用 → 回传"使用呼吸机"
                payload = VitalSignPayload.builder()
                        .vitalsignName("呼吸")
                        .vitalsignType("1004")
                        .vitalsignNVal1("")
                        .vitalsignSVal1(VentilatorState.USING.getDisplayName())
                        .unit("")
                        .build();
                log.info("STEP_04 traceId={} pid={} 呼吸机状态变化后重新启用 hasChangeBetween=true", traceId, pid);
            } else {
                // 中间有变化且当前未使用 → 回传"停止呼吸机"
                payload = VitalSignPayload.builder()
                        .vitalsignName("呼吸")
                        .vitalsignType("1004")
                        .vitalsignNVal1("")
                        .vitalsignSVal1(VentilatorState.STOPPED.getDisplayName())
                        .unit("")
                        .build();
                log.info("STEP_04 traceId={} pid={} 呼吸机状态变化后停止 hasChangeBetween=true", traceId, pid);
            }
        } else if (previousState == VentilatorState.NOT_USING && currentState == VentilatorState.USING) {
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
     * 解析上一个标准时间点的呼吸机状态
     * 查询前一个标准时间点窗口内的 param_XiYangTuJing 记录
     * 用于判断呼吸机状态是否发生变化
     *
     * 逻辑：
     * - 如果前一个时间点没有数据 / 数据相同 → 只回传呼吸值
     * - 如果前一个时间点数据不同 / 状态变化 → 根据当前状态回传
     */
    private VentilatorState resolvePreviousVentilatorState(String pid, LocalDateTime planTime, String traceId) {
        // 找到前一个标准时间点
        int currentHour = planTime.getHour();
        int prevHour = getPreviousStandardHour(currentHour);
        if (prevHour < 0) {
            log.warn("STEP_04 traceId={} pid={} 无法确定前一个标准时间点 currentHour={}", traceId, pid, currentHour);
            return VentilatorState.NOT_USING;
        }

        // 构建前一个标准时间点的业务时间窗口
        LocalDateTime prevDate = planTime.minusDays(prevHour > currentHour ? 1 : 0);
        ClinicalTimeWindow prevWindow = timeWindowService.buildVitalPointWindow(prevDate.toLocalDate(), prevHour);
        if (prevWindow == null) {
            log.warn("STEP_04 traceId={} pid={} 无法构建前一时间窗口 prevHour={}", traceId, pid, prevHour);
            return VentilatorState.NOT_USING;
        }

        Date prevStart = Date.from(prevWindow.getStart().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Date prevEnd = Date.from(prevWindow.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());

        // 查询前一个时间窗口内的呼吸机记录
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(VENTILATOR_ROUTE_CODE)
                .and("time").gte(prevStart).lt(prevEnd)
                .and("valid").ne(false).ne(0));

        Document record = mongoTemplate.findOne(query, Document.class, "bedside");
        if (record == null || isInvalidRecord(record)) {
            log.info("STEP_04 traceId={} pid={} 前一时间窗口({}:00)无呼吸机记录", traceId, pid, prevHour);
            return VentilatorState.NOT_USING;
        }

        String strVal = getValueFromDocByKey(record, "strVal", String.class);
        log.info("STEP_04 traceId={} pid={} 前一时间窗口({}:00)呼吸机记录 time={} strVal={}",
                traceId, pid, prevHour, record.getDate("time"), strVal);
        if (strVal != null && VENTILATOR_FLAGS.contains(strVal.trim())) {
            return VentilatorState.USING;
        }
        return VentilatorState.NOT_USING;
    }

    /**
     * 检查前一个标准时间点到当前时间点之间是否有呼吸机状态变化
     * 用于检测中间的停用/启用事件
     *
     * @return true=中间有变化，需要根据当前状态回传；false=中间没变化
     */
    private boolean hasVentilatorChangeBetween(String pid, LocalDateTime planTime, String traceId) {
        int currentHour = planTime.getHour();
        int prevHour = getPreviousStandardHour(currentHour);
        if (prevHour < 0) {
            return false;
        }

        // 计算前一个标准时间点窗口的结束时间（即当前时间点窗口的开始时间）
        LocalDateTime prevDate = planTime.minusDays(prevHour > currentHour ? 1 : 0);
        ClinicalTimeWindow prevWindow = timeWindowService.buildVitalPointWindow(prevDate.toLocalDate(), prevHour);
        if (prevWindow == null) {
            return false;
        }

        // 查询范围：[前一窗口结束时间, 当前窗口开始时间)
        // 前一窗口结束时间 = 前一标准时间点 + 1小时
        Date rangeStart = Date.from(prevWindow.getEnd().atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Date rangeEnd = Date.from(planTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant());

        // 查询范围内的所有呼吸机记录
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(VENTILATOR_ROUTE_CODE)
                .and("time").gte(rangeStart).lt(rangeEnd)
                .and("valid").ne(false).ne(0))
                .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "time"));

        List<Document> records = mongoTemplate.find(query, Document.class, "bedside");
        if (records.isEmpty()) {
            log.info("STEP_04 traceId={} pid={} 前一时间窗口到当前时间之间无呼吸机记录", traceId, pid);
            return false;
        }

        // 检查是否有状态变化
        boolean hasChange = false;
        Boolean lastState = null;
        for (Document record : records) {
            String strVal = getValueFromDocByKey(record, "strVal", String.class);
            boolean isUsing = strVal != null && VENTILATOR_FLAGS.contains(strVal.trim());
            if (lastState != null && lastState != isUsing) {
                hasChange = true;
                log.info("STEP_04 traceId={} pid={} 检测到呼吸机状态变化 time={} isUsing={}",
                        traceId, pid, record.getDate("time"), isUsing);
                break;
            }
            lastState = isUsing;
        }

        return hasChange;
    }

    /**
     * 获取前一个标准时间点
     * 标准时间点顺序：2, 6, 10, 14, 18, 22
     * 例如：当前是2点，前一个是22点；当前是6点，前一个是2点
     */
    private int getPreviousStandardHour(int currentHour) {
        for (int i = 0; i < STANDARD_HOURS.size(); i++) {
            if (STANDARD_HOURS.get(i) == currentHour) {
                // 前一个时间点，如果是第一个则返回最后一个（跨天）
                return i > 0 ? STANDARD_HOURS.get(i - 1) : STANDARD_HOURS.get(STANDARD_HOURS.size() - 1);
            }
        }
        return -1; // 不在标准时间点
    }

    /**
     * 二次过滤：检查bedside记录的valid字段，排除作废记录
     */
    private boolean isInvalidRecord(Document doc) {
        Object valid = doc.get("valid");
        return Boolean.FALSE.equals(valid) || Integer.valueOf(0).equals(valid);
    }
}
