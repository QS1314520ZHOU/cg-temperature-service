package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 总出量处理器
 *
 * 业务目的：处理总出量汇总（动态获取出量代码）
 * 输出：vitalsignName=总出量, vitalsignType=1010, unit=ml
 *
 * 动态获取出量代码逻辑：
 *   1. 根据patient Mongo _id查询bedsideConfig.pid
 *   2. groupName必须为"出入量"
 *   3. 找到groups.name="出量"
 *   4. 遍历groups.items
 *   5. 将item.code与configParam.code关联
 *   6. 只保留configParam.calculation="out"
 *   7. 加入有效管道动态代码
 */
@Component
public class TotalOutputHandler extends BaseVitalSignHandler {

    public TotalOutputHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        // 总出量由调度层计算后传入，此handler仅负责构建payload
        return null;
    }

    /**
     * 构建总出量payload
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
