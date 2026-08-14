package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 其他引流量处理器
 *
 * 业务目的：处理其他引流量汇总
 * 源数据：所有满足code包含"_tube_"的，排除param_tube_胃肠减压
 * 输出：vitalsignName=引流量(ml), vitalsignType=3126, unit=ml
 *
 * 必须只统计configParam.calculation="out"的有效数值
 */
@Component
public class OtherDrainageHandler extends BaseVitalSignHandler {
    @Autowired
    private MongoTemplate mongoTemplate;

    private static final String TUBE_SUFFIX = "_tube_";
    private static final String EXCLUDE_CODE = "param_tube_胃肠减压";

    public OtherDrainageHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        if (bedside == null) {
            return null;
        }

        String code = getValueFromDocByKey(bedside, "code", String.class);
        if (code == null || !code.contains(TUBE_SUFFIX) || EXCLUDE_CODE.equals(code)) {
            return null;
        }

        String strVal = getValueFromDocByKey(bedside, "strVal", String.class);
        Double value = parseDouble(strVal);

        if (value == null) {
            return null;
        }

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("引流量(ml)")
                .vitalsignType("3126")
                .vitalsignNVal1(formatDouble(value))
                .unit("ml")
                .build();

        fillCommonFields(payload, patient, bedside, mongoTemplate, traceId);
        return payload;
    }
}
