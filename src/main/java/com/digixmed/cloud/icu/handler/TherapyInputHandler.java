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
 * 治疗输入量处理器
 *
 * 业务目的：处理治疗输入量汇总
 * 源数据：param_带入药量, param_YaoYeti_in_hour, param_YaoShuXue_in_hour
 * 输出：vitalsignName=输入量, vitalsignType=1045, unit=ml
 *
 * 注意：需求文字中 param_YaoYeti_in_hou 应视为拼写错误，兼容 param_YaoYeti_in_hour
 */
@Component
public class TherapyInputHandler extends BaseVitalSignHandler {
    @Autowired
    private MongoTemplate mongoTemplate;

    private static final List<String> THERAPY_INPUT_CODES = Arrays.asList(
            "param_带入药量",
            "param_YaoYeti_in_hour",
            "param_YaoYeti_in_hou",  // 兼容拼写错误
            "param_YaoShuXue_in_hour"
    );

    public TherapyInputHandler(PatientIdentityMapper patientIdentityMapper) {
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
            log.warn("STEP_05_VALUE_PARSED traceId={} 无法解析治疗输入量值: {}", traceId, strVal);
            return null;
        }

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("输入量")
                .vitalsignType("1045")
                .vitalsignNVal1(formatDouble(value))
                .unit("ml")
                .build();

        fillCommonFields(payload, patient, bedside, mongoTemplate, traceId);
        return payload;
    }

    public static List<String> getTherapyInputCodes() {
        return THERAPY_INPUT_CODES;
    }
}
