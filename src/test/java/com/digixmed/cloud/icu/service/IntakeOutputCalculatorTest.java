package com.digixmed.cloud.icu.service;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IntakeOutputCalculator 单元测试
 *
 * 覆盖场景：
 * - isDrainCode: "引流" 匹配、param_tube_胃肠减压、非引流
 * - round1: HALF_UP 舍入
 * - sumTotalOutput: 尿量、净超滤量、排出物、引流液
 * - sumTotalInput: bedside 三项 + DrugChannelTotals
 */
class IntakeOutputCalculatorTest {

    private IntakeOutputCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new IntakeOutputCalculator();
    }

    // ==================== isDrainCode ====================

    @Test
    void testIsDrainCode_with引流() {
        assertTrue(IntakeOutputCalculator.isDrainCode("param_tube_胸腔引流"));
        assertTrue(IntakeOutputCalculator.isDrainCode("param_tube_腹腔引流"));
        assertTrue(IntakeOutputCalculator.isDrainCode("引流液"));
    }

    @Test
    void testIsDrainCode_胃肠减压() {
        assertTrue(IntakeOutputCalculator.isDrainCode("param_tube_胃肠减压"));
    }

    @Test
    void testIsDrainCode_nonDrain() {
        assertFalse(IntakeOutputCalculator.isDrainCode("param_niaoLiang"));
        assertFalse(IntakeOutputCalculator.isDrainCode("param_daBianAmount"));
        assertFalse(IntakeOutputCalculator.isDrainCode("param_chaoLvLiang"));
        assertFalse(IntakeOutputCalculator.isDrainCode(null));
        assertFalse(IntakeOutputCalculator.isDrainCode(""));
    }

    // ==================== round1 ====================

    @Test
    void testRound1_halfUp() {
        assertEquals(new BigDecimal("1.2"), IntakeOutputCalculator.round1(new BigDecimal("1.15")));
        assertEquals(new BigDecimal("1.3"), IntakeOutputCalculator.round1(new BigDecimal("1.25")));
        assertEquals(new BigDecimal("1.1"), IntakeOutputCalculator.round1(new BigDecimal("1.14")));
        assertEquals(BigDecimal.ZERO, IntakeOutputCalculator.round1(null));
    }

    // ==================== sumTotalOutput ====================

    @Test
    void testSumTotalOutput_basicComponents() {
        // 尿量 500 + 净超滤 200 + 大便 50 + 胸腔引流 100 = 850
        List<Document> records = Arrays.asList(
                bedsideRecord("param_niaoLiang", "500"),
                bedsideRecord("param_chaoLvLiang", "200"),
                bedsideRecord("param_daBianAmount", "50"),
                bedsideRecord("param_tube_胸腔引流", "100")
        );

        BigDecimal result = calculator.sumTotalOutput(records, "test", "pid1");
        assertEquals(new BigDecimal("850.0"), result);
    }

    @Test
    void testSumTotalOutput_ultrafiltrationIncluded() {
        // 净超滤量(param_chaoLvLiang) 必须计入 1010
        List<Document> records = Arrays.asList(
                bedsideRecord("param_niaoLiang", "300"),
                bedsideRecord("param_chaoLvLiang", "150")
        );

        BigDecimal result = calculator.sumTotalOutput(records, "test", "pid1");
        assertEquals(new BigDecimal("450.0"), result);
    }

    @Test
    void testSumTotalOutput_drainCodeMatching() {
        // 引流：code 含 "引流" 或 == param_tube_胃肠减压
        List<Document> records = Arrays.asList(
                bedsideRecord("param_tube_胃肠减压", "80"),
                bedsideRecord("param_tube_腹腔引流", "60"),
                bedsideRecord("param_niaoLiang", "400")
        );

        BigDecimal result = calculator.sumTotalOutput(records, "test", "pid1");
        // 400 + 80 + 60 = 540
        assertEquals(new BigDecimal("540.0"), result);
    }

    @Test
    void testSumTotalOutput_excretionFiveItems() {
        // 排出物五项全部计入
        List<Document> records = Arrays.asList(
                bedsideRecord("param_daBianAmount", "10"),
                bedsideRecord("param_造瘘口量", "20"),
                bedsideRecord("param_outuwuliang", "30"),
                bedsideRecord("param_咯血", "5"),
                bedsideRecord("param_tanLiang", "15")
        );

        BigDecimal result = calculator.sumTotalOutput(records, "test", "pid1");
        // 10+20+30+5+15 = 80
        assertEquals(new BigDecimal("80.0"), result);
    }

    @Test
    void testSumTotalOutput_nonOutputCodeIgnored() {
        // param_kouFu (入量) 不应计入出量
        List<Document> records = Arrays.asList(
                bedsideRecord("param_niaoLiang", "300"),
                bedsideRecord("param_kouFu", "500"),
                bedsideRecord("param_T", "36.5")
        );

        BigDecimal result = calculator.sumTotalOutput(records, "test", "pid1");
        assertEquals(new BigDecimal("300.0"), result);
    }

    @Test
    void testSumTotalOutput_emptyRecords() {
        BigDecimal result = calculator.sumTotalOutput(Collections.emptyList(), "test", "pid1");
        assertEquals(new BigDecimal("0.0"), result);
    }

    // ==================== sumTotalInput ====================

    @Test
    void testSumTotalInput_bedsidePlusDrugExe() {
        // bedside: 带入药量 100, 口服 50, 鼻饲 30
        List<Document> records = Arrays.asList(
                bedsideRecord("param_带入药量", "100"),
                bedsideRecord("param_kouFu", "50"),
                bedsideRecord("param_biSi", "30")
        );

        // drugExe: 静脉 200, 输血 150, 肠内营养 80, 胃肠 po 40
        DrugAmountCalculator.DrugChannelTotals drugTotals = new DrugAmountCalculator.DrugChannelTotals();
        drugTotals.transfusion = new BigDecimal("150");
        drugTotals.enteral = new BigDecimal("80");
        drugTotals.vein.put("静滴", new BigDecimal("200"));
        drugTotals.gastro.put("po", new BigDecimal("40"));

        BigDecimal result = calculator.sumTotalInput(records, drugTotals, "test", "pid1");

        // 药物治疗 = 带入药量(100) + 输血(150) + 静脉(200) = 450
        // 胃肠摄入 = (鼻饲30 + 肠内80) + (口服50 + po40) = 110 + 90 = 200
        // 总入量 = 450 + 200 = 650
        assertEquals(new BigDecimal("650.0"), result);
    }

    @Test
    void testSumTotalInput_zeroDrugTotals() {
        List<Document> records = Arrays.asList(
                bedsideRecord("param_带入药量", "100"),
                bedsideRecord("param_kouFu", "50"),
                bedsideRecord("param_biSi", "30")
        );

        DrugAmountCalculator.DrugChannelTotals drugTotals = new DrugAmountCalculator.DrugChannelTotals();

        BigDecimal result = calculator.sumTotalInput(records, drugTotals, "test", "pid1");

        // 药物治疗 = 100 + 0 = 100
        // 胃肠摄入 = (30 + 0) + (50 + 0) = 80
        // 总入量 = 100 + 80 = 180
        assertEquals(new BigDecimal("180.0"), result);
    }

    // ==================== 工具方法 ====================

    private Document bedsideRecord(String code, String strVal) {
        Document doc = new Document();
        doc.append("code", code);
        doc.append("strVal", strVal);
        return doc;
    }
}
