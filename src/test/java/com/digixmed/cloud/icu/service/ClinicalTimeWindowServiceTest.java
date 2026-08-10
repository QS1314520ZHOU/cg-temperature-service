package com.digixmed.cloud.icu.service;

import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClinicalTimeWindowService单元测试
 */
class ClinicalTimeWindowServiceTest {

    private ClinicalTimeWindowService service;

    @BeforeEach
    void setUp() {
        service = new ClinicalTimeWindowService();
    }

    @Test
    void testBuildVitalPoint() {
        // 测试构建标准时间点
        LocalDateTime point = service.buildVitalPoint(LocalDate.of(2026, 8, 10), 2);
        assertNotNull(point);
        assertEquals(2, point.getHour());
        assertEquals(0, point.getMinute());

        LocalDateTime point6 = service.buildVitalPoint(LocalDate.of(2026, 8, 10), 6);
        assertEquals(6, point6.getHour());

        LocalDateTime point10 = service.buildVitalPoint(LocalDate.of(2026, 8, 10), 10);
        assertEquals(10, point10.getHour());

        LocalDateTime point14 = service.buildVitalPoint(LocalDate.of(2026, 8, 10), 14);
        assertEquals(14, point14.getHour());

        LocalDateTime point18 = service.buildVitalPoint(LocalDate.of(2026, 8, 10), 18);
        assertEquals(18, point18.getHour());

        LocalDateTime point22 = service.buildVitalPoint(LocalDate.of(2026, 8, 10), 22);
        assertEquals(22, point22.getHour());
    }

    @Test
    void testBuildVitalPointInvalidHour() {
        // 测试无效小时数
        LocalDateTime point = service.buildVitalPoint(LocalDate.of(2026, 8, 10), 3);
        assertNull(point);

        LocalDateTime point15 = service.buildVitalPoint(LocalDate.of(2026, 8, 10), 15);
        assertNull(point15);
    }

    @Test
    void testBuildDailyWindow() {
        // 测试构建每日汇总窗口
        // 2026-08-08 07:00 的窗口应该是 [2026-08-07 07:00, 2026-08-08 07:00)
        ClinicalTimeWindow window = service.buildDailyWindow(LocalDate.of(2026, 8, 8));

        assertNotNull(window);
        assertEquals(LocalDateTime.of(2026, 8, 7, 7, 0, 0), window.getStart());
        assertEquals(LocalDateTime.of(2026, 8, 8, 7, 0, 0), window.getEnd());
        assertEquals(ClinicalTimeWindow.WindowType.DAILY_SUMMARY, window.getType());
    }

    @Test
    void testBuildTemperatureRecheckWindow() {
        // 测试构建体温复测窗口
        LocalDateTime vitalPoint = LocalDateTime.of(2026, 8, 10, 2, 0, 0);
        ClinicalTimeWindow window = service.buildTemperatureRecheckWindow(vitalPoint);

        assertNotNull(window);
        assertEquals(vitalPoint, window.getStart());
        assertEquals(vitalPoint.plusHours(1), window.getEnd());
        assertEquals(ClinicalTimeWindow.WindowType.TEMPERATURE_RECHECK, window.getType());
    }

    @Test
    void testBuildHeightWeightWindow() {
        // 测试身高体重窗口
        // 入科日期2026-08-01，报表日期2026-08-01（入科当天）→ 应该发送
        ClinicalTimeWindow window1 = service.buildHeightWeightWindow(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1));
        assertNotNull(window1);

        // 入科日期2026-08-01，报表日期2026-08-08（第7天）→ 应该发送
        ClinicalTimeWindow window2 = service.buildHeightWeightWindow(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 8));
        assertNotNull(window2);

        // 入科日期2026-08-01，报表日期2026-08-05（第4天）→ 不应该发送
        ClinicalTimeWindow window3 = service.buildHeightWeightWindow(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 5));
        assertNull(window3);
    }

    @Test
    void testGetPreviousVitalPoint() {
        // 测试获取上一个时间点
        LocalDateTime current = LocalDateTime.of(2026, 8, 10, 10, 0, 0);
        LocalDateTime previous = service.getPreviousVitalPoint(current);
        assertEquals(6, previous.getHour());

        LocalDateTime current2 = LocalDateTime.of(2026, 8, 10, 2, 0, 0);
        LocalDateTime previous2 = service.getPreviousVitalPoint(current2);
        assertEquals(22, previous2.getHour());
        assertEquals(9, previous2.getDayOfMonth()); // 前一天22:00
    }

    @Test
    void testGetNextVitalPoint() {
        // 测试获取下一个时间点
        LocalDateTime current = LocalDateTime.of(2026, 8, 10, 10, 0, 0);
        LocalDateTime next = service.getNextVitalPoint(current);
        assertEquals(14, next.getHour());

        LocalDateTime current2 = LocalDateTime.of(2026, 8, 10, 22, 0, 0);
        LocalDateTime next2 = service.getNextVitalPoint(current2);
        assertEquals(2, next2.getHour());
        assertEquals(11, next2.getDayOfMonth()); // 次日02:00
    }

    @Test
    void testIsVitalPoint() {
        // 测试是否是标准时间点
        assertTrue(service.isVitalPoint(LocalDateTime.of(2026, 8, 10, 2, 0, 0)));
        assertTrue(service.isVitalPoint(LocalDateTime.of(2026, 8, 10, 6, 0, 0)));
        assertFalse(service.isVitalPoint(LocalDateTime.of(2026, 8, 10, 3, 0, 0)));
        assertFalse(service.isVitalPoint(LocalDateTime.of(2026, 8, 10, 2, 30, 0)));
    }

    @Test
    void testGetAllVitalPoints() {
        // 测试获取所有标准时间点
        var points = service.getAllVitalPoints(LocalDate.of(2026, 8, 10));
        assertEquals(6, points.size());
        assertEquals(2, points.get(0).getHour());
        assertEquals(6, points.get(1).getHour());
        assertEquals(10, points.get(2).getHour());
        assertEquals(14, points.get(3).getHour());
        assertEquals(18, points.get(4).getHour());
        assertEquals(22, points.get(5).getHour());
    }

    @Test
    void testWindowContains() {
        // 测试时间窗口包含判断
        ClinicalTimeWindow window = service.buildDailyWindow(LocalDate.of(2026, 8, 8));

        // 窗口内的时间
        assertTrue(window.contains(LocalDateTime.of(2026, 8, 7, 7, 0, 0))); // 开始时间（包含）
        assertTrue(window.contains(LocalDateTime.of(2026, 8, 7, 12, 0, 0))); // 中间时间
        assertFalse(window.contains(LocalDateTime.of(2026, 8, 8, 7, 0, 0))); // 结束时间（不包含）

        // 窗口外的时间
        assertFalse(window.contains(LocalDateTime.of(2026, 8, 7, 6, 59, 59))); // 之前
        assertFalse(window.contains(LocalDateTime.of(2026, 8, 8, 7, 0, 1))); // 之后
    }
}
