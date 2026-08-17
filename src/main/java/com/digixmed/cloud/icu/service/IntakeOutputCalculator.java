package com.digixmed.cloud.icu.service;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 出入量合计计算器
 *
 * 集中管理总出量(1010)的合计逻辑，消除 DailySummaryTask 与 Controller 的重复实现。
 */
@Component
public class IntakeOutputCalculator {

    private static final Logger log = LoggerFactory.getLogger(IntakeOutputCalculator.class);

    /** 总出量(1010)固定七项代码 */
    public static final List<String> OUTPUT_FIXED_CODES = Arrays.asList(
            "param_niaoLiang",
            "param_daBianAmount",
            "param_outuwuliang",
            "param_造瘘口量",
            "param_咯血",
            "param_tanLiang",
            "param_tube_胃肠减压"
    );

    /**
     * 总出量合计：固定七项 + 所有 code 含 "_tube_" 的记录
     *
     * 两步走：
     * 1. 确定参与合计的 code 集合（固定七项 + _tube_ 通配，Set 天然去重）
     * 2. 对命中这些 code 的全部记录逐条累加
     *
     * 注意：Set 只用于 code 去重（避免胃肠减压同时命中固定项和通配），不限制每条 code 的记录数。
     */
    public BigDecimal sumTotalOutput(List<Document> records, String traceId, String pid) {
        // 第一步：确定 code 集合
        Set<String> outputCodes = new HashSet<>(OUTPUT_FIXED_CODES);
        for (Document doc : records) {
            String code = doc.getString("code");
            if (code != null && code.contains("_tube_")) {
                outputCodes.add(code);
            }
        }

        // 第二步：全量逐条累加
        BigDecimal total = BigDecimal.ZERO;
        for (Document doc : records) {
            String code = doc.getString("code");
            if (code == null || !outputCodes.contains(code)) {
                continue;
            }
            String val = doc.getString("strVal");
            if (val == null || val.trim().isEmpty()) {
                continue;
            }
            try {
                total = total.add(new BigDecimal(val.trim()));
            } catch (NumberFormatException e) {
                log.warn("STEP_05_VALUE_PARSED traceId={} pid={} code={} 无法解析出量值: {}",
                        traceId, pid, code, val);
            }
        }
        return total;
    }
}
