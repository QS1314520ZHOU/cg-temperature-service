package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 总输入量处理器
 *
 * 业务目的：处理总输入量汇总（饮入量 + 治疗输入量，去重）
 * 源数据：调度层合计后以虚拟 Document 传入（strVal 为合计值）
 * 输出：vitalsignName=总输入量, vitalsignType=1009, unit=ml
 */
@Component
public class TotalInputHandler extends BaseVitalSignHandler {
    @Autowired
    private MongoTemplate mongoTemplate;

    public TotalInputHandler(PatientIdentityMapper patientIdentityMapper) {
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
            log.warn("STEP_05_VALUE_PARSED traceId={} 无法解析总输入量值: {}", traceId, strVal);
            return null;
        }

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("总输入量")
                .vitalsignType("1009")
                .vitalsignNVal1(formatDouble(value))
                .unit("ml")
                .build();

        fillCommonFields(payload, patient, bedside, mongoTemplate, traceId);
        return payload;
    }

    /**
     * 构建总输入量payload（供 Controller 手动扫描端点使用）
     */
    public VitalSignPayload buildPayload(Double totalValue, Document patient, LocalDateTime planTime, String traceId) {
        if (totalValue == null || totalValue == 0) {
            return null;
        }

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("总输入量")
                .vitalsignType("1009")
                .vitalsignNVal1(formatDouble(totalValue))
                .unit("ml")
                .build();

        fillCommonFields(payload, patient, planTime, traceId);
        return payload;
    }
}
