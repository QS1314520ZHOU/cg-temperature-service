package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VentilatorState;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 呼吸处理器
 *
 * 业务目的：处理呼吸体征，支持呼吸机状态判断
 * 源数据：param_resp.strVal（呼吸频率）, param_HuXiMoShi.strVal（呼吸机模式）
 * 输出：vitalsignName=呼吸, vitalsignType=1004, unit=次/分
 *
 * 呼吸机状态判断：
 *   A. 正在使用呼吸机（USING）：
 *      - 当前标准时间点 param_HuXiMoShi 的 strVal 非 null、非空白
 *      - vitalsignSVal1 = "使用呼吸机"
 *      - vitalsignNVal1 = 空
 *
 *   B. 普通呼吸（NOT_USING）：
 *      - 当前没有有效呼吸机模式
 *      - 上一个已知状态也不是使用呼吸机
 *      - param_resp.strVal → vitalsignNVal1
 *      - vitalsignSVal1 = 空
 *
 *   C. 停止呼吸机（STOPPED）：
 *      - 上一个标准时间点或上一个有效状态为使用呼吸机
 *      - 当前标准时间点已经没有有效 param_HuXiMoShi
 *      - vitalsignSVal1 = "停止呼吸机"
 *      - vitalsignNVal1 = 空
 *      - 同一个停止事件只生成一次
 */
@Component
public class BreathHandler extends BaseVitalSignHandler {

    private static final String BREATH_CODE = "param_resp";
    private static final String VENTILATOR_CODE = "param_HuXiMoShi";

    @Autowired
    private MongoTemplate mongoTemplate;

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

        // 查询当前时间点的呼吸机模式
        VentilatorState currentState = resolveVentilatorState(pid, planTime, traceId);
        // 查询上一个时间点的呼吸机状态
        VentilatorState previousState = resolvePreviousVentilatorState(pid, planTime, traceId);

        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} pid={} ventilatorCurrent={} ventilatorPrevious={}",
                traceId, pid, currentState, previousState);

        VitalSignPayload payload;

        if (currentState == VentilatorState.USING) {
            // 正在使用呼吸机
            payload = VitalSignPayload.builder()
                    .vitalsignName("呼吸")
                    .vitalsignType("1004")
                    .vitalsignNVal1("")
                    .vitalsignSVal1(VentilatorState.USING.getDisplayName())
                    .unit("次/分")
                    .build();
        } else if (previousState == VentilatorState.USING && currentState == VentilatorState.NOT_USING) {
            // 停止呼吸机（只发一次）
            payload = VitalSignPayload.builder()
                    .vitalsignName("呼吸")
                    .vitalsignType("1004")
                    .vitalsignNVal1("")
                    .vitalsignSVal1(VentilatorState.STOPPED.getDisplayName())
                    .unit("次/分")
                    .build();
        } else {
            // 普通呼吸
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

        fillCommonFields(payload, patient, planTime, traceId);
        return payload;
    }

    /**
     * 解析当前时间点的呼吸机状态
     */
    private VentilatorState resolveVentilatorState(String pid, LocalDateTime planTime, String traceId) {
        Date time = Date.from(planTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(VENTILATOR_CODE)
                .and("time").is(time));

        Document record = mongoTemplate.findOne(query, Document.class, "bedside");
        if (record == null) {
            return VentilatorState.NOT_USING;
        }

        String strVal = getValueFromDocByKey(record, "strVal", String.class);
        Boolean valid = getValueFromDocByKey(record, "valid", Boolean.class);

        if (strVal != null && !strVal.trim().isEmpty() && Boolean.TRUE.equals(valid)) {
            return VentilatorState.USING;
        }
        return VentilatorState.NOT_USING;
    }

    /**
     * 解析上一个时间点的呼吸机状态
     */
    private VentilatorState resolvePreviousVentilatorState(String pid, LocalDateTime planTime, String traceId) {
        // 查找上一个有param_HuXiMoShi记录的时间点
        Date currentTime = Date.from(planTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        Query query = new Query(Criteria.where("pid").is(pid)
                .and("code").is(VENTILATOR_CODE)
                .and("time").lt(currentTime)
        ).with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "time"))
                .limit(1);

        Document record = mongoTemplate.findOne(query, Document.class, "bedside");
        if (record == null) {
            return VentilatorState.NOT_USING;
        }

        String strVal = getValueFromDocByKey(record, "strVal", String.class);
        Boolean valid = getValueFromDocByKey(record, "valid", Boolean.class);

        if (strVal != null && !strVal.trim().isEmpty() && Boolean.TRUE.equals(valid)) {
            return VentilatorState.USING;
        }
        return VentilatorState.NOT_USING;
    }
}
