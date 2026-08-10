package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 小便量处理器
 *
 * 业务目的：处理小便量汇总
 * 源数据：param_niaoLiang
 * 输出：vitalsignName=小便量, vitalsignType=1008, unit=ml
 */
@Component
public class UrineOutputHandler extends BaseVitalSignHandler {

    private static final String SOURCE_CODE = "param_niaoLiang";

    public UrineOutputHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        if (bedside == null) {
            log.warn("STEP_04_SOURCE_RECORD_SELECTED traceId={} 小便量记录为空", traceId);
            return null;
        }

        String strVal = getValueFromDocByKey(bedside, "strVal", String.class);
        Double value = parseDouble(strVal);

        if (value == null) {
            log.warn("STEP_05_VALUE_PARSED traceId={} 无法解析小便量值: {}", traceId, strVal);
            return null;
        }

        String bedsideId = getValueFromDocByKey(bedside, "_id", Object.class) != null
                ? getValueFromDocByKey(bedside, "_id", Object.class).toString() : "unknown";
        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} bedsideId={} 小便量={}", traceId, bedsideId, value);

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("小便量")
                .vitalsignType("1008")
                .vitalsignNVal1(formatDouble(value))
                .unit("ml")
                .build();

        fillCommonFields(payload, patient, planTime, traceId);
        return payload;
    }
}
