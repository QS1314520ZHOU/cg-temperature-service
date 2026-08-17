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
 * 排出物量处理器
 *
 * 业务目的：处理排出物量汇总
 * 源数据：param_daBianAmount, param_造瘘口量, param_outuwuliang, param_咯血, param_tanLiang
 * 输出：vitalsignName=排出物量(ml), vitalsignType=3125, isCustomType=1
 */
@Component
public class DrainageOutputHandler extends BaseVitalSignHandler {
    @Autowired
    private MongoTemplate mongoTemplate;

    private static final List<String> DRAINAGE_CODES = Arrays.asList(
            "param_daBianAmount",
            "param_造瘘口量",
            "param_outuwuliang",
            "param_咯血",
            "param_tanLiang"
    );

    public DrainageOutputHandler(PatientIdentityMapper patientIdentityMapper) {
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
                .vitalsignName("排出物量(ml)")
                .vitalsignType("3125")
                .vitalsignNVal1(formatDouble(value))
                .isCustomType(1)
                .build();

        fillCommonFields(payload, patient, bedside, mongoTemplate, traceId);
        return payload;
    }

    public static List<String> getDrainageCodes() {
        return DRAINAGE_CODES;
    }
}
