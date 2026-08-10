package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 净超滤量处理器
 *
 * 业务目的：处理净超滤量汇总
 * 源数据：param_chaoLvLiang
 * 输出：vitalsignName=净超滤量(ml), vitalsignType=3127, unit=ml
 */
@Component
public class NetUltrafiltrationHandler extends BaseVitalSignHandler {

    private static final String SOURCE_CODE = "param_chaoLvLiang";

    public NetUltrafiltrationHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        if (bedside == null) {
            return null;
        }

        String strVal = getValueFromDocByKey(bedside, "strVal", String.class);
        Double value = parseDouble(strVal);

        if (value == null) {
            return null;
        }

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("净超滤量(ml)")
                .vitalsignType("3127")
                .vitalsignNVal1(formatDouble(value))
                .unit("ml")
                .build();

        fillCommonFields(payload, patient, planTime, traceId);
        return payload;
    }
}
