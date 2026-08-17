package com.digixmed.cloud.icu.service;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DrugAmountCalculator 单元测试
 *
 * 测试 calcContinuousDrugAmount 的各种场景：
 * - 持续用药跨区间
 * - pause + recovery
 * - quickAdd
 * - 封顶（liquidAmount）
 * - speedUnit ml/min 转换
 * - 无动作数据回退
 */
class DrugAmountCalculatorTest {

    private DrugAmountCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new DrugAmountCalculator();
    }

    // ==================== 基础积分 ====================

    @Test
    void testContinuousDrug_simpleFlow() {
        // 简单持续用药：开始后匀速输注，无结束
        // 区间 08:00-10:00，速度 100 ml/h → 应注入 200 ml
        long rangeStart = hours(8);
        long rangeEnd = hours(10);

        Document execution = buildExecution(hours(7), null, 500.0,
                List.of(
                        buildAction(hours(7), "start", 100.0, "ml/h")
                ));

        DrugAmountCalculator.DrugActualAmount result =
                calculator.calcContinuousDrugAmount(execution, rangeStart, rangeEnd, true);

        assertEquals(200.0, result.inRange.doubleValue(), 0.1);
        assertFalse(result.fallback);
    }

    @Test
    void testContinuousDrug_partialOverlap() {
        // 持续用药跨区间：06:00开始，区间 08:00-10:00 → 只计入重叠部分 200ml
        long rangeStart = hours(8);
        long rangeEnd = hours(10);

        Document execution = buildExecution(hours(6), null, 500.0,
                List.of(
                        buildAction(hours(6), "start", 100.0, "ml/h")
                ));

        DrugAmountCalculator.DrugActualAmount result =
                calculator.calcContinuousDrugAmount(execution, rangeStart, rangeEnd, true);

        assertEquals(200.0, result.inRange.doubleValue(), 0.1);
    }

    @Test
    void testContinuousDrug_crossMidnight() {
        // 跨 07:00 的持续用药：前一天 20:00 开始，当天 08:00 结束
        // 区间 (08-10 07:00, 08-10 08:00] → 只计入 1 小时 = 60ml
        long rangeStart = hours(7);   // 08-10 07:00
        long rangeEnd = hours(8);     // 08-10 08:00
        // 前一天 20:00 = 08-09 20:00
        long prevDay20 = hours(0) - 4 * 3600_000L;

        // liquidAmount=1000 确保封顶不会在区间开始前发生（12h * 60ml/h = 720 < 1000）
        Document execution = buildExecution(prevDay20, rangeEnd, 1000.0,
                List.of(
                        buildAction(prevDay20, "start", 60.0, "ml/h")
                ));

        DrugAmountCalculator.DrugActualAmount result =
                calculator.calcContinuousDrugAmount(execution, rangeStart, rangeEnd, true);
        assertEquals(60.0, result.inRange.doubleValue(), 0.01);
    }

    // ==================== pause + recovery ====================

    @Test
    void testContinuousDrug_pauseAndRecovery() {
        // 08:00开始 100ml/h，09:00暂停，09:30恢复，区间 08:00-10:00
        // 08:00-09:00 = 100ml，09:00-09:30 = 0（暂停），09:30-10:00 = 50ml → 共150ml
        long rangeStart = hours(8);
        long rangeEnd = hours(10);

        Document execution = buildExecution(hours(8), null, 500.0,
                List.of(
                        buildAction(hours(8), "start", 100.0, "ml/h"),
                        buildAction(hours(9), "pause", 0, null),
                        buildAction(hours(9, 30), "recovery", 100.0, "ml/h")
                ));

        DrugAmountCalculator.DrugActualAmount result =
                calculator.calcContinuousDrugAmount(execution, rangeStart, rangeEnd, true);

        assertEquals(150.0, result.inRange.doubleValue(), 0.1);
    }

    // ==================== quickAdd ====================

    @Test
    void testContinuousDrug_quickAdd() {
        // 08:00开始 100ml/h，08:30快推 50ml，区间 08:00-10:00
        // 流速积分：100 * 2 = 200ml，快推：50ml → 共250ml
        long rangeStart = hours(8);
        long rangeEnd = hours(10);

        Document execution = buildExecution(hours(8), null, 500.0,
                List.of(
                        buildAction(hours(8), "start", 100.0, "ml/h"),
                        buildAction(hours(8, 30), "quickAdd", 0, null, 50.0)
                ));

        DrugAmountCalculator.DrugActualAmount result =
                calculator.calcContinuousDrugAmount(execution, rangeStart, rangeEnd, true);

        assertEquals(250.0, result.inRange.doubleValue(), 0.1);
    }

    // ==================== 封顶 ====================

    @Test
    void testContinuousDrug_capReached() {
        // liquidAmount=150ml，速度 100ml/h → 1.5小时后封顶
        // 区间 08:00-10:00 → 实际只跑了 08:00-09:30 = 150ml
        long rangeStart = hours(8);
        long rangeEnd = hours(10);

        Document execution = buildExecution(hours(8), null, 150.0,
                List.of(
                        buildAction(hours(8), "start", 100.0, "ml/h")
                ));

        DrugAmountCalculator.DrugActualAmount result =
                calculator.calcContinuousDrugAmount(execution, rangeStart, rangeEnd, true);

        assertEquals(150.0, result.total.doubleValue(), 0.1);
        // inRange 应该也受封顶影响
        assertTrue(result.inRange.doubleValue() <= 150.0);
    }

    @Test
    void testContinuousDrug_capWithQuickAdd() {
        // liquidAmount=200ml，速度 100ml/h，快推 80ml
        // 快推先占额：80ml，剩余 120ml → 速度积分 120ml / 100ml/h = 1.2h
        // 区间 08:00-10:00 → 08:00-09:12 速度积分 120ml + 快推 80ml = 200ml
        long rangeStart = hours(8);
        long rangeEnd = hours(10);

        Document execution = buildExecution(hours(8), null, 200.0,
                List.of(
                        buildAction(hours(8), "start", 100.0, "ml/h"),
                        buildAction(hours(8, 30), "quickAdd", 0, null, 80.0)
                ));

        DrugAmountCalculator.DrugActualAmount result =
                calculator.calcContinuousDrugAmount(execution, rangeStart, rangeEnd, true);

        assertEquals(200.0, result.total.doubleValue(), 0.1);
    }

    // ==================== speedUnit 转换 ====================

    @Test
    void testContinuousDrug_mlPerMinConversion() {
        // speedUnit='ml/min'，speed=1 → 60 ml/h
        // 区间 08:00-10:00 → 60 * 2 = 120ml
        long rangeStart = hours(8);
        long rangeEnd = hours(10);

        Document execution = buildExecution(hours(8), null, 500.0,
                List.of(
                        buildAction(hours(8), "start", 1.0, "ml/min")
                ));

        DrugAmountCalculator.DrugActualAmount result =
                calculator.calcContinuousDrugAmount(execution, rangeStart, rangeEnd, true);

        assertEquals(120.0, result.inRange.doubleValue(), 0.1);
    }

    // ==================== 无动作数据回退 ====================

    @Test
    void testContinuousDrug_noActions_fallback() {
        // 无动作数据 → 回退为开始时点全额计入 liquidAmount
        long rangeStart = hours(8);
        long rangeEnd = hours(10);

        Document execution = buildExecution(hours(9), null, 200.0, List.of());

        DrugAmountCalculator.DrugActualAmount result =
                calculator.calcContinuousDrugAmount(execution, rangeStart, rangeEnd, true);

        assertTrue(result.fallback);
        assertEquals(200.0, result.inRange.doubleValue(), 0.1);
    }

    @Test
    void testContinuousDrug_noActions_outsideRange() {
        // 无动作数据，开始时间在区间之后（15:00），endTime=16:00
        // 区间 08:00-10:00 → 药物完全在区间之后，cutoff=10:00 < startMs=15:00 → 提前返回
        long rangeStart = hours(8);
        long rangeEnd = hours(10);

        Document execution = buildExecution(hours(15), hours(16), 200.0, List.of());

        DrugAmountCalculator.DrugActualAmount result =
                calculator.calcContinuousDrugAmount(execution, rangeStart, rangeEnd, false);

        // cutoff < startMs → (0, 0, false)
        assertFalse(result.fallback);
        assertEquals(0, result.inRange.doubleValue(), 0.1);
        assertEquals(0, result.total.doubleValue(), 0.1);
    }

    // ==================== findDrugMethod ====================

    @Test
    void testFindDrugMethod_normalMatch() {
        Document config = new Document("code", "ABC123").append("valid", true).append("name", "test");
        List<Document> configs = List.of(config);

        Document result = calculator.findDrugMethod("ABC123", configs);
        assertNotNull(result);
        assertEquals("ABC123", result.getString("code"));
    }

    @Test
    void testFindDrugMethod_multiValueCode() {
        // code 字段是 `、` 分隔的多值
        Document config = new Document("code", "ABC123、DEF456").append("valid", true).append("name", "test");
        List<Document> configs = List.of(config);

        Document result = calculator.findDrugMethod("DEF456", configs);
        assertNotNull(result);
    }

    @Test
    void testFindDrugMethod_invalidConfig() {
        Document config = new Document("code", "ABC123").append("valid", false).append("name", "test");
        List<Document> configs = List.of(config);

        Document result = calculator.findDrugMethod("ABC123", configs);
        assertNull(result);
    }

    @Test
    void testFindDrugMethod_noMatch() {
        Document config = new Document("code", "ABC123").append("valid", true).append("name", "test");
        List<Document> configs = List.of(config);

        Document result = calculator.findDrugMethod("XYZ", configs);
        assertNull(result);
    }

    // ==================== stop ====================

    @Test
    void testContinuousDrug_stop() {
        // 08:00开始 100ml/h，09:00停止，区间 08:00-10:00
        // 只有 08:00-09:00 = 100ml
        long rangeStart = hours(8);
        long rangeEnd = hours(10);

        Document execution = buildExecution(hours(8), null, 500.0,
                List.of(
                        buildAction(hours(8), "start", 100.0, "ml/h"),
                        buildAction(hours(9), "stop", 0, null)
                ));

        DrugAmountCalculator.DrugActualAmount result =
                calculator.calcContinuousDrugAmount(execution, rangeStart, rangeEnd, true);

        assertEquals(100.0, result.inRange.doubleValue(), 0.1);
    }

    // ==================== 工具方法 ====================

    private long hours(int h) {
        return hours(h, 0);
    }

    private long hours(int h, int m) {
        // 使用固定基准：2026-08-10 00:00:00 +08:00
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        cal.set(2026, Calendar.AUGUST, 10, h, m, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private Document buildExecution(long startTimeMs, Long endTimeMs, double liquidAmount,
                                     List<Document> actionList) {
        Document doc = new Document();
        doc.append("startTime", new Date(startTimeMs));
        if (endTimeMs != null) {
            doc.append("endTime", new Date(endTimeMs));
        }
        doc.append("liquidAmount", liquidAmount);
        doc.append("status", "active");
        doc.append("drugActionList", actionList);
        return doc;
    }

    private Document buildAction(long timeMs, String action, double speed, String speedUnit) {
        return buildAction(timeMs, action, speed, speedUnit, 0);
    }

    private Document buildAction(long timeMs, String action, double speed, String speedUnit,
                                  double quickAddAmount) {
        Document doc = new Document();
        doc.append("time", new Date(timeMs));
        doc.append("action", action);
        if (speed > 0) doc.append("speed", speed);
        if (speedUnit != null) doc.append("speedUnit", speedUnit);
        if (quickAddAmount > 0) doc.append("quickAddAmount", quickAddAmount);
        return doc;
    }
}
