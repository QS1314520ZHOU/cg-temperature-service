package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 总出量处理器
 *
 * 业务目的：处理总出量汇总（固定七项 + 引流通配，去重）
 * 源数据：调度层合计后以虚拟 Document 传入（strVal 为合计值）
 * 输出：vitalsignName=总出量, vitalsignType=1010, unit=ml
 */
@Component
public class TotalOutputHandler extends BaseVitalSignHandler {
    @Autowired
    private MongoTemplate mongoTemplate;

    public TotalOutputHandler(PatientIdentityMapper patientIdentityMapper) {
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
            log.warn("STEP_05_VALUE_PARSED traceId={} 无法解析总出量值: {}", traceId, strVal);
            return null;
        }

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("总出量")
                .vitalsignType("1010")
                .vitalsignNVal1(formatDouble(value))
                .unit("ml")
                .build();

        fillCommonFields(payload, patient, bedside, mongoTemplate, traceId);
        return payload;
    }

    /**
     * 构建总出量payload（供 Controller 手动扫描端点使用）
     */
    public VitalSignPayload buildPayload(Double totalValue, Document patient, LocalDateTime planTime, String traceId) {
        if (totalValue == null || totalValue == 0) {
            return null;
        }

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("总出量")
                .vitalsignType("1010")
                .vitalsignNVal1(formatDouble(totalValue))
                .unit("ml")
                .build();

        fillCommonFields(payload, patient, planTime, traceId);
        return payload;
    }
}
