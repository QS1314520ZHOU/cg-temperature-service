package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 胃管负压引流处理器
 *
 * 业务目的：处理胃管负压引流量汇总
 * 源数据：param_tube_胃肠减压
 * 输出：vitalsignName=胃管负压引流(ml), vitalsignType=3120, unit=ml
 */
@Component
public class GastricDrainageHandler extends BaseVitalSignHandler {

    private static final String SOURCE_CODE = "param_tube_胃肠减压";

    public GastricDrainageHandler(PatientIdentityMapper patientIdentityMapper) {
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
                .vitalsignName("胃管负压引流(ml)")
                .vitalsignType("3120")
                .vitalsignNVal1(formatDouble(value))
                .unit("ml")
                .build();

        fillCommonFields(payload, patient, planTime, traceId);
        return payload;
    }
}
