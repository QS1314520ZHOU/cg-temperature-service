package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 疼痛评分处理器
 *
 * 业务目的：处理疼痛评分体征
 * 源数据：param_tengTong_score.strVal
 * 输出：vitalsignName=疼痛评分, vitalsignType=1012, unit=空
 *
 * 解析规则：
 *   - 提取字符串最后一个合法数值
 *   - 例如：cpot-3 → 3, CPOT：4 → 4, 3 → 3
 *   - 不要简单 split("-")，使用明确正则并校验
 *   - 无法提取分数则不推送，记录 reasonCode=INVALID_PAIN_SCORE
 *
 * 输出字段：
 *   - vitalsignNVal1 = 分数
 *   - vitalsignNVal3 = 1（标识）
 */
@Component
public class PainScoreHandler extends BaseVitalSignHandler {

    private static final String SOURCE_CODE = "param_tengTong_score";

    /**
     * 匹配最后一个数字的正则
     * 支持：cpot-3, CPOT：4, 3, 评分:2, 疼痛评分-5分 等格式
     */
    private static final Pattern LAST_NUMBER_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*分?\\s*$");

    public PainScoreHandler(PatientIdentityMapper patientIdentityMapper) {
        super(patientIdentityMapper);
    }

    @Override
    public VitalSignPayload handle(Document bedside, Document patient, LocalDateTime planTime, String traceId) {
        if (bedside == null) {
            log.warn("STEP_04_SOURCE_RECORD_SELECTED traceId={} 疼痛评分记录为空", traceId);
            return null;
        }

        String strVal = getValueFromDocByKey(bedside, "strVal", String.class);
        if (strVal == null || strVal.trim().isEmpty()) {
            log.warn("STEP_05_VALUE_PARSED traceId={} 疼痛评分值为空 reasonCode=INVALID_PAIN_SCORE", traceId);
            return null;
        }

        // 提取最后一个数值
        String scoreStr = extractLastNumber(strVal);
        if (scoreStr == null) {
            log.warn("STEP_05_VALUE_PARSED traceId={} 无法从'{}'中提取分数 reasonCode=INVALID_PAIN_SCORE", traceId, strVal);
            return null;
        }

        Double score = parseDouble(scoreStr);
        if (score == null) {
            log.warn("STEP_05_VALUE_PARSED traceId={} 分数解析失败: {} reasonCode=INVALID_PAIN_SCORE", traceId, scoreStr);
            return null;
        }

        String bedsideId = getValueFromDocByKey(bedside, "_id", Object.class) != null
                ? getValueFromDocByKey(bedside, "_id", Object.class).toString() : "unknown";
        log.info("STEP_04_SOURCE_RECORD_SELECTED traceId={} bedsideId={} 疼痛评分={} raw={}", traceId, bedsideId, score, strVal);

        VitalSignPayload payload = VitalSignPayload.builder()
                .vitalsignName("疼痛评分")
                .vitalsignType("1012")
                .vitalsignNVal1(formatDouble(score))
                .vitalsignNVal3("1")
                .unit("")
                .build();

        fillCommonFields(payload, patient, planTime, traceId);
        return payload;
    }

    /**
     * 提取字符串中最后一个数值
     *
     * @param input 输入字符串
     * @return 数值字符串，无法提取返回null
     */
    String extractLastNumber(String input) {
        if (input == null) {
            return null;
        }
        Matcher matcher = LAST_NUMBER_PATTERN.matcher(input);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
