package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 总输入量处理器
 *
 * 业务目的：处理总输入量汇总（饮入量 + 治疗输入量）
 * 源数据：饮入量 + 治疗输入量
 * 输出：vitalsignName=总输入量, vitalsignType=1009, unit=ml
 *
 * 注意：需求原文把 1045 和 1009 都写成"输入量"，代码中命名为 TOTAL_INPUT
 */
@Component
public class TotalInputHandler extends BaseVitalSignHandler {

    public TotalInputHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        // 总输入量由调度层计算后传入，此handler仅负责构建payload
        // 实际计算在DailySummaryTask中完成
        return null;
    }

    /**
     * 构建总输入量payload
     *
     * @param totalValue 总输入量值
     * @param patient 患者文档
     * @param planTime 计划时间
     * @param traceId 追踪ID
     * @return VitalSignPayload
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
