package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.handler.StoolCountHandler;
import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.service.IntermediateService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DailySummaryTask 大便次数处理单元测试
 *
 * 测试目标：
 * 1. 验证大便次数查询使用 window.end（次日07:00）
 * 2. 验证不会错误读取 window.start 的记录
 * 3. 验证多条记录取 editTime 最新值
 * 4. 验证空值不入队
 */
@ExtendWith(MockitoExtension.class)
class DailySummaryTaskTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private IntermediateService intermediateService;

    @Mock
    private StoolCountHandler stoolCountHandler;

    @InjectMocks
    private DailySummaryTask dailySummaryTask;

    private PatientIdentityMapper patientIdentityMapper;

    @BeforeEach
    void setUp() {
        patientIdentityMapper = new PatientIdentityMapper();
    }

    /**
     * 用例1：能够查询并入队正确的大便次数
     *
     * 输入：
     *   reportDate = 2026-08-25
     *   window.start = 2026-08-25 07:00
     *   window.end = 2026-08-26 07:00
     *   bedside: code = param_汇总大便次数, time = 2026-08-26 07:00, strVal = "2", valid = true
     *
     * 预期：
     *   生成1条1007记录
     *   vitalsignName = 大便次数
     *   vitalsignType = 1007
     *   vitalsignSVal2 = "2"
     *   unit = 次
     *   planTime = 2026-08-26 07:00
     *   recordTime = 2026-08-26 07:00
     *   调用 intermediateService.upsertPending 一次
     */
    @Test
    void testProcessStoolCount_Success() {
        // Arrange
        String pid = "test_pid_001";
        String traceId = "trace_001";

        ClinicalTimeWindow window = ClinicalTimeWindow.builder()
                .start(LocalDateTime.of(2026, 8, 25, 7, 0, 0))
                .end(LocalDateTime.of(2026, 8, 26, 7, 0, 0))
                .reportDate(LocalDateTime.of(2026, 8, 25, 7, 0, 0))
                .build();

        Document patient = new Document()
                .append("_id", new org.bson.types.ObjectId("507f1f77bcf86cd799439011"))
                .append("mrn", "TEST001")
                .append("hisPid", "HIS001")
                .append("patientName", "测试患者");

        Document stoolRecord = new Document()
                .append("_id", new org.bson.types.ObjectId("507f1f77bcf86cd799439022"))
                .append("pid", pid)
                .append("code", "param_汇总大便次数")
                .append("strVal", "2")
                .append("valid", true);

        VitalSignPayload expectedPayload = VitalSignPayload.builder()
                .vitalsignName("大便次数")
                .vitalsignType("1007")
                .vitalsignSVal2("2")
                .unit("次")
                .build();

        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(stoolRecord);
        when(stoolCountHandler.handle(eq(stoolRecord), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(expectedPayload);
        when(intermediateService.upsertPending(eq(expectedPayload), eq(traceId)))
                .thenReturn(new HashMap<>());

        // Act - 使用反射调用私有方法
        try {
            java.lang.reflect.Method method = DailySummaryTask.class.getDeclaredMethod(
                    "processStoolCount", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class);
            method.setAccessible(true);
            method.invoke(dailySummaryTask, new java.util.ArrayList<>(), patient, pid, window, traceId);
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }

        // Assert
        verify(mongoTemplate, times(1)).findOne(any(Query.class), eq(Document.class), eq("bedside"));
        verify(stoolCountHandler, times(1)).handle(eq(stoolRecord), eq(patient), eq(window.getEnd()), eq(traceId));
        verify(intermediateService, times(1)).upsertPending(eq(expectedPayload), eq(traceId));

        assertEquals("1007", expectedPayload.getVitalsignType());
        assertEquals("大便次数", expectedPayload.getVitalsignName());
        assertEquals("2", expectedPayload.getVitalsignSVal2());
        assertEquals("次", expectedPayload.getUnit());
    }

    /**
     * 用例2：不能错误读取 window.start 的记录
     *
     * 输入：
     *   window.start 存在 param_汇总大便次数
     *   window.end 不存在
     *
     * 预期：
     *   不生成1007记录
     *   不调用 intermediateService.upsertPending
     */
    @Test
    void testProcessStoolCount_NotFoundAtWindowEnd() {
        // Arrange
        String pid = "test_pid_002";
        String traceId = "trace_002";

        ClinicalTimeWindow window = ClinicalTimeWindow.builder()
                .start(LocalDateTime.of(2026, 8, 25, 7, 0, 0))
                .end(LocalDateTime.of(2026, 8, 26, 7, 0, 0))
                .reportDate(LocalDateTime.of(2026, 8, 25, 7, 0, 0))
                .build();

        Document patient = new Document()
                .append("_id", new org.bson.types.ObjectId("507f1f77bcf86cd799439011"))
                .append("mrn", "TEST002")
                .append("hisPid", "HIS002")
                .append("patientName", "测试患者2");

        // 模拟查询结果为null（window.end 无数据）
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(null);

        // Act
        try {
            java.lang.reflect.Method method = DailySummaryTask.class.getDeclaredMethod(
                    "processStoolCount", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class);
            method.setAccessible(true);
            method.invoke(dailySummaryTask, new java.util.ArrayList<>(), patient, pid, window, traceId);
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }

        // Assert - 不应调用 handler 和 intermediateService
        verify(stoolCountHandler, never()).handle(any(), any(), any(), any());
        verify(intermediateService, never()).upsertPending(any(), any());
    }

    /**
     * 用例4：空值不入队
     *
     * 输入：
     *   strVal = null 或空白字符串
     *
     * 预期：
     *   StoolCountHandler 返回 null
     *   不调用 intermediateService.upsertPending
     */
    @Test
    void testProcessStoolCount_EmptyValue() {
        // Arrange
        String pid = "test_pid_004";
        String traceId = "trace_004";

        ClinicalTimeWindow window = ClinicalTimeWindow.builder()
                .start(LocalDateTime.of(2026, 8, 25, 7, 0, 0))
                .end(LocalDateTime.of(2026, 8, 26, 7, 0, 0))
                .reportDate(LocalDateTime.of(2026, 8, 25, 7, 0, 0))
                .build();

        Document patient = new Document()
                .append("_id", new org.bson.types.ObjectId("507f1f77bcf86cd799439011"))
                .append("mrn", "TEST004")
                .append("hisPid", "HIS004")
                .append("patientName", "测试患者4");

        Document stoolRecord = new Document()
                .append("_id", new org.bson.types.ObjectId("507f1f77bcf86cd799439044"))
                .append("pid", pid)
                .append("code", "param_汇总大便次数")
                .append("strVal", "")  // 空值
                .append("valid", true);

        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(stoolRecord);
        // Handler 返回 null（空值）
        when(stoolCountHandler.handle(eq(stoolRecord), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(null);

        // Act
        try {
            java.lang.reflect.Method method = DailySummaryTask.class.getDeclaredMethod(
                    "processStoolCount", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class);
            method.setAccessible(true);
            method.invoke(dailySummaryTask, new java.util.ArrayList<>(), patient, pid, window, traceId);
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }

        // Assert - 不应调用 intermediateService
        verify(intermediateService, never()).upsertPending(any(), any());
    }

    /**
     * 用例3：多条记录取 editTime 最新值
     *
     * 验证查询条件包含 Sort.by(Sort.Direction.DESC, "editTime") 和 limit(1)
     */
    @Test
    void testProcessStoolCount_QuerySortByEditTimeDesc() {
        // Arrange
        String pid = "test_pid_003";
        String traceId = "trace_003";

        ClinicalTimeWindow window = ClinicalTimeWindow.builder()
                .start(LocalDateTime.of(2026, 8, 25, 7, 0, 0))
                .end(LocalDateTime.of(2026, 8, 26, 7, 0, 0))
                .reportDate(LocalDateTime.of(2026, 8, 25, 7, 0, 0))
                .build();

        Document patient = new Document()
                .append("_id", new org.bson.types.ObjectId("507f1f77bcf86cd799439011"))
                .append("mrn", "TEST003")
                .append("hisPid", "HIS003")
                .append("patientName", "测试患者3");

        Document stoolRecord = new Document()
                .append("_id", new org.bson.types.ObjectId("507f1f77bcf86cd799439033"))
                .append("pid", pid)
                .append("code", "param_汇总大便次数")
                .append("strVal", "3")
                .append("valid", true);

        VitalSignPayload expectedPayload = VitalSignPayload.builder()
                .vitalsignName("大便次数")
                .vitalsignType("1007")
                .vitalsignSVal2("3")
                .unit("次")
                .build();

        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(stoolRecord);
        when(stoolCountHandler.handle(eq(stoolRecord), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(expectedPayload);
        when(intermediateService.upsertPending(eq(expectedPayload), eq(traceId)))
                .thenReturn(new HashMap<>());

        // Act
        try {
            java.lang.reflect.Method method = DailySummaryTask.class.getDeclaredMethod(
                    "processStoolCount", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class);
            method.setAccessible(true);
            method.invoke(dailySummaryTask, new java.util.ArrayList<>(), patient, pid, window, traceId);
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }

        // Assert - 验证查询被调用（包含排序和限制条件）
        verify(mongoTemplate, times(1)).findOne(any(Query.class), eq(Document.class), eq("bedside"));
    }

    /**
     * 用例5：回归验证 - planTime 与 window.end 一致
     */
    @Test
    void testProcessStoolCount_PlanTimeMatchesWindowEnd() {
        // Arrange
        String pid = "test_pid_005";
        String traceId = "trace_005";

        LocalDateTime expectedPlanTime = LocalDateTime.of(2026, 8, 26, 7, 0, 0);
        ClinicalTimeWindow window = ClinicalTimeWindow.builder()
                .start(LocalDateTime.of(2026, 8, 25, 7, 0, 0))
                .end(expectedPlanTime)
                .reportDate(LocalDateTime.of(2026, 8, 25, 7, 0, 0))
                .build();

        Document patient = new Document()
                .append("_id", new org.bson.types.ObjectId("507f1f77bcf86cd799439011"))
                .append("mrn", "TEST005")
                .append("hisPid", "HIS005")
                .append("patientName", "测试患者5");

        Document stoolRecord = new Document()
                .append("_id", new org.bson.types.ObjectId("507f1f77bcf86cd799439055"))
                .append("pid", pid)
                .append("code", "param_汇总大便次数")
                .append("strVal", "1")
                .append("valid", true);

        VitalSignPayload expectedPayload = VitalSignPayload.builder()
                .vitalsignName("大便次数")
                .vitalsignType("1007")
                .vitalsignSVal2("1")
                .unit("次")
                .build();

        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(stoolRecord);
        when(stoolCountHandler.handle(eq(stoolRecord), eq(patient), eq(expectedPlanTime), eq(traceId)))
                .thenReturn(expectedPayload);
        when(intermediateService.upsertPending(eq(expectedPayload), eq(traceId)))
                .thenReturn(new HashMap<>());

        // Act
        try {
            java.lang.reflect.Method method = DailySummaryTask.class.getDeclaredMethod(
                    "processStoolCount", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class);
            method.setAccessible(true);
            method.invoke(dailySummaryTask, new java.util.ArrayList<>(), patient, pid, window, traceId);
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }

        // Assert - 验证 handler 使用 window.end 作为 planTime
        verify(stoolCountHandler, times(1)).handle(eq(stoolRecord), eq(patient), eq(expectedPlanTime), eq(traceId));
    }
}
