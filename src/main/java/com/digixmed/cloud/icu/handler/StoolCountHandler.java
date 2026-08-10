package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 大便次数处理器
 *
 * 业务目的：处理大便次数汇总
 * 源数据：优先 param_汇总大便次数，兼容 param_daBianCiShu, param_daBianAmount
 * 输出：vitalsignName=大便次数, vitalsignType=1007, unit=次
 *
 * 必须记录：windowStart, windowEnd, sourceCode, sourceBedsideId, finalValue
 */
@Component
public class StoolCountHandler extends BaseVitalSignHandler {

    /**
     * 大便次数代码别名，按优先级排列
     */
    private static final List<String> STOOL_CODES = Arrays.asList(
            "param_汇总大便次数",
            "param_daBianCiShu",
            "param_daBianAmount"
    );

    public StoolCountHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        if (bedside == null) {
            log.warn("STEP_04_SOURCE_RECORD_SELECTED traceId={} 大便次数记录为空", traceId);
            return null;
        }

        String strVal = getValueFromDocByKey(bedside, "strVal", String.class);
        if (strVal == null || strVal.trim().isEmpty()) {
            log.warn("STEP_05_VALUE_PARSED traceId={} 大便次数值为空", traceId);
            return null;
        }

        String bedsideId = getValueFromDocByKey(bedside, "_id", Object.class) != null
                ? getValueFromDocByKey(bedside, "_id", Object.class).toString() : "unknown";
        String code = getValueFromDocByKey(bedside, "code", String.class);

        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} bedsideId={} code={} value={}", traceId, bedsideId, code, strVal);

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("大便次数")
                .vitalsignType("1007")
                .vitalsignSVal1(strVal)
                .unit("次")
                .build();

        fillCommonFields(payload, patient, planTime, traceId);
        return payload;
    }

    /**
     * 获取大便次数代码列表
     */
    public static List<String> getStoolCodes() {
        return STOOL_CODES;
    }
}
