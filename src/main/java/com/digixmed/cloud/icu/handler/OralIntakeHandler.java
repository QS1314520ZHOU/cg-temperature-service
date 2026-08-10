package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 饮入量处理器
 *
 * 业务目的：处理饮入量汇总
 * 源数据：param_kouFu, param_biSi, param_YaoStomach_in_hour
 * 输出：vitalsignName=饮入量, vitalsignType=1044, unit=ml
 */
@Component
public class OralIntakeHandler extends BaseVitalSignHandler {

    private static final List<String> ORAL_INTAKE_CODES = Arrays.asList(
            "param_kouFu",
            "param_biSi",
            "param_YaoStomach_in_hour"
    );

    public OralIntakeHandler(PatientIdentityMapper patientIdentityMapper) {
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
            log.warn("STEP_05_VALUE_PARSED traceId={} 无法解析饮入量值: {}", traceId, strVal);
            return null;
        }

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("饮入量")
                .vitalsignType("1044")
                .vitalsignNVal1(formatDouble(value))
                .unit("ml")
                .build();

        fillCommonFields(payload, patient, planTime, traceId);
        return payload;
    }

    public static List<String> getOralIntakeCodes() {
        return ORAL_INTAKE_CODES;
    }
}
