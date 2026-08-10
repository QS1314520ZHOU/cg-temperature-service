package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 心率处理器
 *
 * 业务目的：处理心率体征
 * 源数据：param_HR.strVal → vitalsignNVal1
 * 输出：vitalsignName=心率, vitalsignType=1003, unit=次/分
 *
 * 注意：不能继续保留"没有 param_HR 才把 param_PR 当作心率"的隐式逻辑
 */
@Component
public class HeartRateHandler extends BaseVitalSignHandler {

    private static final String SOURCE_CODE = "param_HR";

    public HeartRateHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        if (bedside == null) {
            log.warn("STEP_04_SOURCE_RECORD_SELECTED traceId={} 心率记录为空", traceId);
            return null;
        }

        String strVal = getValueFromDocByKey(bedside, "strVal", String.class);
        Double hrValue = parseDouble(strVal);

        if (hrValue == null) {
            log.warn("STEP_05_VALUE_PARSED traceId={} 无法解析心率值: {}", traceId, strVal);
            return null;
        }

        String bedsideId = getValueFromDocByKey(bedside, "_id", Object.class) != null
                ? getValueFromDocByKey(bedside, "_id", Object.class).toString() : "unknown";
        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} bedsideId={} 心率值={}", traceId, bedsideId, hrValue);

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("心率")
                .vitalsignType("1003")
                .vitalsignNVal1(formatDouble(hrValue))
                .unit("次/分")
                .build();

        fillCommonFields(payload, patient, planTime, traceId);
        return payload;
    }
}
