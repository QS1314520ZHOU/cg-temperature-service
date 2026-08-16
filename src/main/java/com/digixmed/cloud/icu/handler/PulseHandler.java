package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 脉搏处理器
 *
 * 业务目的：处理脉搏体征
 * 源数据：param_脉搏.strVal → vitalsignNVal1
 * 输出：vitalsignName=脉搏, vitalsignType=1002, unit=次/分
 *
 * 取数口径：只识别 param_脉搏 这一个 code。
 * param_PR 是监护仪导入的脉率，与护理单手写脉搏不是同一来源，
 * 曾经作为别名兜底导致"护理单没填脉搏但回传了脉搏"，故不再纳入。
 */
@Component
public class PulseHandler extends BaseVitalSignHandler {
    @Autowired
    private MongoTemplate mongoTemplate;

    /** 脉搏唯一来源 code */
    public static final String SOURCE_CODE = "param_脉搏";

    public PulseHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        if (bedside == null) {
            log.warn("STEP_04_SOURCE_RECORD_SELECTED traceId={} 脉搏记录为空", traceId);
            return null;
        }

        // 双保险：即使调用方传错 code 的记录进来，也不构建 payload
        String code = getValueFromDocByKey(bedside, "code", String.class);
        if (!SOURCE_CODE.equals(code)) {
            log.warn("STEP_04_SOURCE_RECORD_SELECTED traceId={} 非脉搏来源 code={}，跳过", traceId, code);
            return null;
        }

        String strVal = getValueFromDocByKey(bedside, "strVal", String.class);
        Double pulseValue = parseDouble(strVal);

        if (pulseValue == null) {
            log.warn("STEP_05_VALUE_PARSED traceId={} 无法解析脉搏值: {}", traceId, strVal);
            return null;
        }

        String bedsideId = getValueFromDocByKey(bedside, "_id", Object.class) != null
                ? getValueFromDocByKey(bedside, "_id", Object.class).toString() : "unknown";
        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} bedsideId={} 脉搏值={}", traceId, bedsideId, pulseValue);

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("脉搏")
                .vitalsignType("1002")
                .vitalsignNVal1(formatDouble(pulseValue))
                .unit("次/分")
                .build();

        fillCommonFields(payload, patient, bedside, mongoTemplate, traceId);
        return payload;
    }
}
