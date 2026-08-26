package com.digixmed.cloud.icu.service;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 出入量合计计算器
 *
 * 对齐 icu-stats-form-jar 护理记录单（hljld-form.utils.ts buildSummary）的权威算法。
 *
 * 总入量(1009) = 药物治疗 + 胃肠摄入
 *   药物治疗 = 带入药量(bedside) + 静脉入量(drugExe: 输血 + 各静脉途径)
 *   胃肠摄入 = 鼻饲量(bedside手工 + drugExe肠内营养泵入) + 胃肠入量(bedside口服 + drugExe po等)
 *
 * 总出量(1010) = 尿量 + 净超滤量 + 排出物 + 引流液
 *   引流判定：code含"引流" OR code==="param_tube_胃肠减压"
 */
@Component
public class IntakeOutputCalculator {

    private static final Logger log = LoggerFactory.getLogger(IntakeOutputCalculator.class);

    /**
     * 历史引流编码白名单。
     * 数据库中仍存在这些code，但配置名称已修改。
     */
    private static final Set<String> LEGACY_DRAIN_CODES = Set.of("param_tube_胃肠减压");

    /** 排出物五项（不含尿量、净超滤量） */
    private static final List<String> EXCRETION_CODES = Arrays.asList(
            "param_daBianAmount",
            "param_造瘘口量",
            "param_outuwuliang",
            "param_咯血",
            "param_tanLiang"
    );

    /**
     * 判断 bedside code 是否属于引流量。
     *
     * 兼容：
     * 1. 历史编码 param_tube_胃肠减压；
     * 2. code 中包含"引流"的项目。
     */
    public static boolean isDrainCode(String code) {
        if (code == null || code.trim().isEmpty()) return false;
        return LEGACY_DRAIN_CODES.contains(code) || code.contains("引流");
    }

    /**
     * 舍入到 1 位小数（HALF_UP），与护理记录单 round1 对齐。
     */
    public static BigDecimal round1(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO;
        return value.setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * 计算总入量(1009)
     * 新口径：总入量 = 饮入量 + 输入量
     *
     * @param records           bedside 记录（窗口内）
     * @param drugChannelTotals drugExe 按通道聚合的入量（保留参数兼容，实际不再使用）
     * @param traceId           追踪ID
     * @param pid               患者ID
     * @return 总入量（已 round1）
     */
    public BigDecimal sumTotalInput(List<Document> records,
                                     DrugAmountCalculator.DrugChannelTotals drugChannelTotals,
                                     String traceId, String pid) {
        // [DEBUG] 逐项分项日志
        BigDecimal kouFuSum = sumBedsideByCode(records, "param_kouFu");
        BigDecimal biSiSum = sumBedsideByCode(records, "param_biSi");
        BigDecimal yaoStomachSum = sumBedsideByCode(records, "param_YaoStomach_in_hour");
        BigDecimal daiRuYaoLiangSum = sumBedsideByCode(records, "param_带入药量");
        BigDecimal yaoYetiSum = sumBedsideByCode(records, "param_YaoYeti_in_hour");
        BigDecimal yaoShuXueSum = sumBedsideByCode(records, "param_YaoShuXue_in_hour");

        log.info("IO_CALC traceId={} pid={} kouFu={} biSi={} yaoStomach_in_hour={}",
                traceId, pid,
                kouFuSum.stripTrailingZeros().toPlainString(),
                biSiSum.stripTrailingZeros().toPlainString(),
                yaoStomachSum.stripTrailingZeros().toPlainString());
        log.info("IO_CALC traceId={} pid={} daiRuYaoLiang={} yaoYeti_in_hour={} yaoShuXue_in_hour={}",
                traceId, pid,
                daiRuYaoLiangSum.stripTrailingZeros().toPlainString(),
                yaoYetiSum.stripTrailingZeros().toPlainString(),
                yaoShuXueSum.stripTrailingZeros().toPlainString());

        // 饮入量 = param_kouFu + param_biSi + param_YaoStomach_in_hour
        BigDecimal oralIntake = kouFuSum.add(biSiSum).add(yaoStomachSum);

        // 输入量 = param_带入药量 + param_YaoYeti_in_hour + param_YaoShuXue_in_hour
        BigDecimal therapyInput = daiRuYaoLiangSum.add(yaoYetiSum).add(yaoShuXueSum);

        // 总入量 = 饮入量 + 输入量
        BigDecimal totalInput = round1(oralIntake.add(therapyInput));

        log.info("IO_CALC traceId={} pid={} oralIntake={} therapyInput={} totalInput={}",
                traceId, pid, round1(oralIntake), round1(therapyInput), totalInput);

        return totalInput;
    }

    /**
     * 计算总出量(1010)
     *
     * 总出量 = 尿量 + 净超滤量 + 排出物 + 引流液
     * 排出物：param_daBianAmount, param_造瘘口量, param_outuwuliang, param_咯血, param_tanLiang
     * 引流液：code 含"引流" OR code === "param_tube_胃肠减压"
     *
     * @param records bedside 记录（窗口内）
     * @param traceId 追踪ID
     * @param pid     患者ID
     * @return 总出量（已 round1）
     */
    public BigDecimal sumTotalOutput(List<Document> records, String traceId, String pid) {
        BigDecimal urineTotal = BigDecimal.ZERO;
        BigDecimal ultrafiltrationTotal = BigDecimal.ZERO;
        BigDecimal excretionTotal = BigDecimal.ZERO;
        BigDecimal drainTotal = BigDecimal.ZERO;

        for (Document doc : records) {
            String code = doc.getString("code");
            if (code == null) continue;

            String val = doc.getString("strVal");
            if (val == null || val.trim().isEmpty()) continue;

            BigDecimal amount;
            try {
                amount = new BigDecimal(val.trim());
            } catch (NumberFormatException e) {
                log.warn("IO_CALC traceId={} pid={} code={} 无法解析值: {}", traceId, pid, code, val);
                continue;
            }

            if ("param_niaoLiang".equals(code)) {
                urineTotal = urineTotal.add(amount);
            } else if ("param_chaoLvLiang".equals(code)) {
                ultrafiltrationTotal = ultrafiltrationTotal.add(amount);
            } else if (EXCRETION_CODES.contains(code)) {
                excretionTotal = excretionTotal.add(amount);
            } else if (isDrainCode(code)) {
                drainTotal = drainTotal.add(amount);
            }
        }

        BigDecimal totalOutput = round1(
                round1(urineTotal)
                        .add(round1(ultrafiltrationTotal))
                        .add(round1(excretionTotal))
                        .add(round1(drainTotal)));

        log.info("IO_CALC traceId={} pid={} urine={} ultrafiltration={} excretion={} drain={} totalOutput={}",
                traceId, pid,
                round1(urineTotal), round1(ultrafiltrationTotal),
                round1(excretionTotal), round1(drainTotal), totalOutput);

        return totalOutput;
    }

    /**
     * 按单个 code 从 bedside 记录求和
     */
    private BigDecimal sumBedsideByCode(List<Document> records, String code) {
        BigDecimal total = BigDecimal.ZERO;
        for (Document doc : records) {
            if (!code.equals(doc.getString("code"))) continue;
            String val = doc.getString("strVal");
            if (val == null || val.trim().isEmpty()) continue;
            try {
                total = total.add(new BigDecimal(val.trim()));
            } catch (NumberFormatException e) {
                // skip
            }
        }
        return total;
    }
}
