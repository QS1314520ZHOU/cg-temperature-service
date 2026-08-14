package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 脉搏处理器
 *
 * 业务目的：处理脉搏体征
 * 源数据：param_脉搏.strVal 或 param_PR.strVal → vitalsignNVal1
 * 输出：vitalsignName=脉搏, vitalsignType=1002, unit=次/分
 *
 * 兼容别名：
 *   pulse-codes = param_脉搏, param_PR
 *   按顺序优先选择 param_脉搏
 */
@Component
public class PulseHandler extends BaseVitalSignHandler {
    @Autowired
    private MongoTemplate mongoTemplate;

    /**
     * 脉搏代码别名，按优先级排列
     */
    private static final List<String> PULSE_CODES = Arrays.asList("param_脉搏", "param_PR");

    public PulseHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        if (bedside == null) {
            log.warn("STEP_04_SOURCE_RECORD_SELECTED traceId={} 脉搏记录为空", traceId);
            return null;
        }

        String strVal = getValueFromDocByKey(bedside, "strVal", String.class);
        Double pulseValue = parseDouble(strVal);

        if (pulseValue == null) {
            log.warn("STEP_05_VALUE_PARSED traceId={} 无法解析脉搏值: {}", traceId, strVal);
            return null;
        }

        String bedsideId = getValueFromDocByKey(bedside, "_id", Object.class) != null
                ? getValueFromDocByKey(bedside, "_id", Object.class).toString() : "unknown";
        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} bedsideId={} 脉搏值={}", traceId, bedsideId, pulseValue);

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("脉搏")
                .vitalsignType("1002")
                .vitalsignNVal1(formatDouble(pulseValue))
                .unit("次/分")
                .build();

        fillCommonFields(payload, patient, bedside, mongoTemplate, traceId);
        return payload;
    }

    /**
     * 获取脉搏代码别名列表
     *
     * @return 脉搏代码列表
     */
    public static List<String> getPulseCodes() {
        return PULSE_CODES;
    }
}
