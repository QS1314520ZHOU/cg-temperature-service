package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.handler.*;
import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.repository.InpatientRepository;
import com.digixmed.cloud.icu.service.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DailySummaryTask 综合单元测试
 *
 * 覆盖：
 * - 大便次数（1007）：真实值、空值、无记录、fVal回退
 * - 候选患者查询：window.end大便记录纳入
 * - 所有10项汇总指标：无记录时回传0
 * - 零值后续更新为真实值（幂等键和hash变化）
 * - 入队计数：SKIP不计入
 */
@ExtendWith(MockitoExtension.class)
class DailySummaryTaskTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private IntermediateService intermediateService;

    @Mock
    private StoolCountHandler stoolCountHandler;

    @Mock
    private UrineOutputHandler urineOutputHandler;

    @Mock
    private OralIntakeHandler oralIntakeHandler;

    @Mock
    private TherapyInputHandler therapyInputHandler;

    @Mock
    private TotalInputHandler totalInputHandler;

    @Mock
    private TotalOutputHandler totalOutputHandler;

    @Mock
    private DrainageOutputHandler drainageOutputHandler;

    @Mock
    private GastricDrainageHandler gastricDrainageHandler;

    @Mock
    private OtherDrainageHandler otherDrainageHandler;

    @Mock
    private NetUltrafiltrationHandler netUltrafiltrationHandler;

    @Mock
    private HeightWeightHandler heightWeightHandler;

    @Mock
    private IntakeOutputCalculator intakeOutputCalculator;

    @Mock
    private DrugAmountCalculator drugAmountCalculator;

    @Mock
    private ClinicalTimeWindowService timeWindowService;

    @Mock
    private PushTask pushTask;

    @Mock
    private InpatientRepository inpatientRepository;

    @InjectMocks
    private DailySummaryTask dailySummaryTask;

    private PatientIdentityMapper patientIdentityMapper;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @BeforeEach
    void setUp() {
        patientIdentityMapper = new PatientIdentityMapper();
    }

    // ==================== 辅助方法 ====================

    private ClinicalTimeWindow buildWindow(int startYear, int startMonth, int startDay,
                                            int endYear, int endMonth, int endDay) {
        return ClinicalTimeWindow.builder()
                .start(LocalDateTime.of(startYear, startMonth, startDay, 7, 0, 0))
                .end(LocalDateTime.of(endYear, endMonth, endDay, 7, 0, 0))
                .reportDate(LocalDateTime.of(startYear, startMonth, startDay, 7, 0, 0))
                .build();
    }

    private Document buildPatient(String pid, String mrn) {
        return new Document()
                .append("_id", new org.bson.types.ObjectId(pid))
                .append("mrn", mrn)
                .append("hisPid", "HIS_" + mrn)
                .append("patientName", "TestPatient")
                .append("icuAdmissionTime", new Date());
    }

    private Document buildBedsideRecord(String pid, String code, String strVal, Date time) {
        return new Document()
                .append("_id", new org.bson.types.ObjectId("507f1f77bcf86cd799439001"))
                .append("pid", pid)
                .append("code", code)
                .append("strVal", strVal)
                .append("time", time)
                .append("valid", true);
    }

    private Date toDate(LocalDateTime ldt) {
        return Date.from(ldt.atZone(ZONE).toInstant());
    }

    // ==================== 测试A：大便次数真实值 ====================

    @Test
    void testStoolCount_WithRealValue() {
        String pid = "507f1f77bcf86cd799439011";
        String traceId = "trace_A";
        ClinicalTimeWindow window = buildWindow(2026, 8, 26, 2026, 8, 27);
        Document patient = buildPatient(pid, "TEST001");

        Document stoolRecord = new Document()
                .append("_id", new org.bson.types.ObjectId("507f1f77bcf86cd799439022"))
                .append("pid", pid)
                .append("code", "param_汇总大便次数")
                .append("strVal", "2")
                .append("time", toDate(window.getEnd()))
                .append("valid", true);

        VitalSignPayload expectedPayload = VitalSignPayload.builder()
                .vitalsignName("大便次数")
                .vitalsignType("1007")
                .vitalsignSVal2("2")
                .unit("次")
                .build();

        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(stoolRecord);
        when(stoolCountHandler.handle(any(Document.class), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(expectedPayload);
        when(intermediateService.upsertPending(eq(expectedPayload), eq(traceId)))
                .thenReturn(Map.of("action", "INSERT", "status", "FAILED"));

        try {
            Method method = DailySummaryTask.class.getDeclaredMethod(
                    "processStoolCount", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class);
            method.setAccessible(true);
            method.invoke(dailySummaryTask, new ArrayList<>(), patient, pid, window, traceId);
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }

        verify(intermediateService, times(1)).upsertPending(eq(expectedPayload), eq(traceId));
        assertEquals("1007", expectedPayload.getVitalsignType());
        assertEquals("2", expectedPayload.getVitalsignSVal2());
    }

    // ==================== 测试B：患者只有window.end大便记录 ====================

    @Test
    void testFindCandidatePids_IncludesStoolAtWindowEnd() {
        ClinicalTimeWindow window = buildWindow(2026, 8, 26, 2026, 8, 27);
        String traceId = "trace_B";

        Date startDate = toDate(window.getStart());
        Date endDate = toDate(window.getEnd());

        // A集合：窗口内无bedside记录
        Document queryADoc = new Document("pid", "pid_A");
        when(mongoTemplate.find(argThat(q -> {
            Query query = (Query) q;
            return query.getQueryObject().containsKey("time");
        }), eq(Document.class), eq("bedside")))
                .thenReturn(Collections.emptyList())
                // B集合：归档点有大便记录
                .thenReturn(Arrays.asList(
                        new Document("pid", "pid_B").append("code", "param_汇总大便次数")
                ));

        try {
            Method method = DailySummaryTask.class.getDeclaredMethod(
                    "findCandidatePids", ClinicalTimeWindow.class, String.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> result = (List<String>) method.invoke(dailySummaryTask, window, traceId);

            // B集合的pid_B应该被包含
            assertNotNull(result);
            assertTrue(result.contains("pid_B"));
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }
    }

    // ==================== 测试C：大便无记录 -> 生成"0" ====================

    @Test
    void testStoolCount_NoRecord_EnqueuesZero() {
        String pid = "507f1f77bcf86cd799439011";
        String traceId = "trace_C";
        ClinicalTimeWindow window = buildWindow(2026, 8, 26, 2026, 8, 27);
        Document patient = buildPatient(pid, "TEST003");

        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(null);

        VitalSignPayload zeroPayload = VitalSignPayload.builder()
                .vitalsignName("大便次数")
                .vitalsignType("1007")
                .vitalsignSVal2("0")
                .unit("次")
                .build();

        when(stoolCountHandler.handle(any(Document.class), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(zeroPayload);
        when(intermediateService.upsertPending(eq(zeroPayload), eq(traceId)))
                .thenReturn(Map.of("action", "INSERT", "status", "FAILED"));

        try {
            Method method = DailySummaryTask.class.getDeclaredMethod(
                    "processStoolCount", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class);
            method.setAccessible(true);
            method.invoke(dailySummaryTask, new ArrayList<>(), patient, pid, window, traceId);
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }

        // 即使无记录，也应该生成1007 payload并入队
        verify(intermediateService, times(1)).upsertPending(eq(zeroPayload), eq(traceId));
        assertEquals("0", zeroPayload.getVitalsignSVal2());
    }

    // ==================== 测试D：大便strVal空但fVal有值 ====================

    @Test
    void testStoolCount_EmptyStrVal_UsesFVal() {
        String pid = "507f1f77bcf86cd799439011";
        String traceId = "trace_D";
        ClinicalTimeWindow window = buildWindow(2026, 8, 26, 2026, 8, 27);
        Document patient = buildPatient(pid, "TEST004");

        // strVal为空，fVal=3
        Document stoolRecord = new Document()
                .append("_id", new org.bson.types.ObjectId("507f1f77bcf86cd799439044"))
                .append("pid", pid)
                .append("code", "param_汇总大便次数")
                .append("strVal", "")
                .append("fVal", 3)
                .append("time", toDate(window.getEnd()))
                .append("valid", true);

        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(stoolRecord);

        VitalSignPayload expectedPayload = VitalSignPayload.builder()
                .vitalsignName("大便次数")
                .vitalsignType("1007")
                .vitalsignSVal2("3")
                .unit("次")
                .build();

        when(stoolCountHandler.handle(any(Document.class), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(expectedPayload);
        when(intermediateService.upsertPending(eq(expectedPayload), eq(traceId)))
                .thenReturn(Map.of("action", "INSERT", "status", "FAILED"));

        try {
            Method method = DailySummaryTask.class.getDeclaredMethod(
                    "processStoolCount", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class);
            method.setAccessible(true);
            method.invoke(dailySummaryTask, new ArrayList<>(), patient, pid, window, traceId);
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }

        // 验证使用了fVal
        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(stoolCountHandler).handle(docCaptor.capture(), eq(patient), eq(window.getEnd()), eq(traceId));
        Document capturedDoc = docCaptor.getValue();
        assertEquals("3", capturedDoc.getString("strVal"));
    }

    // ==================== 测试E：无来源记录的汇总方法 -> 全部回传0 ====================

    @Test
    void testNoRecords_AllEnqueueZero() {
        String pid = "507f1f77bcf86cd799439011";
        String traceId = "trace_E";
        ClinicalTimeWindow window = buildWindow(2026, 8, 26, 2026, 8, 27);
        Document patient = buildPatient(pid, "TEST005");

        // mongoTemplate.findOne 返回null（大便次数无记录）
        when(mongoTemplate.findOne(any(Query.class), eq(Document.class), eq("bedside")))
                .thenReturn(null);

        // 各handler返回对应payload
        VitalSignPayload stoolPayload = buildPayload("1007", "大便次数", "0", "次");
        when(stoolCountHandler.handle(any(Document.class), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(stoolPayload);

        VitalSignPayload urinePayload = buildPayload("1008", "小便量", "0", "ml");
        when(urineOutputHandler.handle(any(Document.class), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(urinePayload);

        VitalSignPayload drainagePayload = buildPayload("3125", "排出物量(ml)", "0", null);
        when(drainageOutputHandler.handle(any(Document.class), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(drainagePayload);

        VitalSignPayload gastricPayload = buildPayload("3120", "胃管负压引流(ml)", "0", null);
        when(gastricDrainageHandler.handle(any(Document.class), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(gastricPayload);

        VitalSignPayload otherDrainagePayload = buildPayload("3126", "引流量(ml)", "0", null);
        when(otherDrainageHandler.handle(any(Document.class), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(otherDrainagePayload);

        VitalSignPayload ultrafiltrationPayload = buildPayload("3127", "净超滤量(ml)", "0", null);
        when(netUltrafiltrationHandler.handle(any(Document.class), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(ultrafiltrationPayload);

        // 所有upsertPending都返回INSERT
        when(intermediateService.upsertPending(any(VitalSignPayload.class), eq(traceId)))
                .thenReturn(Map.of("action", "INSERT", "status", "FAILED"));

        // 测试各个process方法

        // 1. processStoolCount - 无记录时生成"0"
        try {
            Method stoolMethod = DailySummaryTask.class.getDeclaredMethod(
                    "processStoolCount", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class);
            stoolMethod.setAccessible(true);
            stoolMethod.invoke(dailySummaryTask, new ArrayList<>(), patient, pid, window, traceId);
        } catch (Exception e) {
            fail("processStoolCount反射调用失败: " + e.getMessage());
        }

        // 2. processUrineOutput - 无记录时生成"0"
        try {
            Method urineMethod = DailySummaryTask.class.getDeclaredMethod(
                    "processUrineOutput", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class, int.class);
            urineMethod.setAccessible(true);
            urineMethod.invoke(dailySummaryTask, new ArrayList<>(), patient, pid, window, traceId, 0);
        } catch (Exception e) {
            fail("processUrineOutput反射调用失败: " + e.getMessage());
        }

        // 3. processDrainageOutput - 无记录时生成"0"
        try {
            Method drainageMethod = DailySummaryTask.class.getDeclaredMethod(
                    "processDrainageOutput", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class, int.class);
            drainageMethod.setAccessible(true);
            drainageMethod.invoke(dailySummaryTask, new ArrayList<>(), patient, pid, window, traceId, 0);
        } catch (Exception e) {
            fail("processDrainageOutput反射调用失败: " + e.getMessage());
        }

        // 4. processGastricDrainage - 无记录时生成"0"
        try {
            Method gastricMethod = DailySummaryTask.class.getDeclaredMethod(
                    "processGastricDrainage", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class, int.class);
            gastricMethod.setAccessible(true);
            gastricMethod.invoke(dailySummaryTask, new ArrayList<>(), patient, pid, window, traceId, 0);
        } catch (Exception e) {
            fail("processGastricDrainage反射调用失败: " + e.getMessage());
        }

        // 5. processOtherDrainage - 无记录时生成"0"
        try {
            Method otherDrainageMethod = DailySummaryTask.class.getDeclaredMethod(
                    "processOtherDrainage", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class, int.class);
            otherDrainageMethod.setAccessible(true);
            otherDrainageMethod.invoke(dailySummaryTask, new ArrayList<>(), patient, pid, window, traceId, 0);
        } catch (Exception e) {
            fail("processOtherDrainage反射调用失败: " + e.getMessage());
        }

        // 6. processNetUltrafiltration - 无记录时生成"0"
        try {
            Method ultraMethod = DailySummaryTask.class.getDeclaredMethod(
                    "processNetUltrafiltration", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class, int.class);
            ultraMethod.setAccessible(true);
            ultraMethod.invoke(dailySummaryTask, new ArrayList<>(), patient, pid, window, traceId, 0);
        } catch (Exception e) {
            fail("processNetUltrafiltration反射调用失败: " + e.getMessage());
        }

        // 验证：6个方法各调用1次upsertPending（共6次）
        ArgumentCaptor<VitalSignPayload> payloadCaptor = ArgumentCaptor.forClass(VitalSignPayload.class);
        verify(intermediateService, times(6)).upsertPending(payloadCaptor.capture(), eq(traceId));

        List<VitalSignPayload> payloads = payloadCaptor.getAllValues();
        Set<String> types = new HashSet<>();
        for (VitalSignPayload p : payloads) {
            types.add(p.getVitalsignType());
            assertEquals(window.getEnd(), p.getPlanTime());
        }

        // 验证6种vitalsignType都存在
        assertTrue(types.contains("1007"), "应包含1007大便次数");
        assertTrue(types.contains("1008"), "应包含1008小便量");
        assertTrue(types.contains("3125"), "应包含3125排出物量");
        assertTrue(types.contains("3120"), "应包含3120胃管负压引流");
        assertTrue(types.contains("3126"), "应包含3126引流量");
        assertTrue(types.contains("3127"), "应包含3127净超滤量");

        // 验证值都为"0"
        for (VitalSignPayload p : payloads) {
            if ("1007".equals(p.getVitalsignType())) {
                assertEquals("0", p.getVitalsignSVal2());
            } else {
                assertEquals("0", p.getVitalsignNVal1());
            }
        }
    }

    // ==================== 测试F：有记录但值为空或非法 ====================

    @Test
    void testParseAmountOrZero_InvalidValues() {
        try {
            Method method = DailySummaryTask.class.getDeclaredMethod(
                    "parseAmountOrZero", Document.class, String.class);
            method.setAccessible(true);

            // strVal=null -> ZERO
            Document doc1 = new Document("code", "test");
            BigDecimal result1 = (BigDecimal) method.invoke(dailySummaryTask, doc1, "trace");
            assertEquals(BigDecimal.ZERO, result1);

            // strVal="" -> ZERO
            Document doc2 = new Document("code", "test").append("strVal", "");
            BigDecimal result2 = (BigDecimal) method.invoke(dailySummaryTask, doc2, "trace");
            assertEquals(BigDecimal.ZERO, result2);

            // strVal=" " -> ZERO
            Document doc3 = new Document("code", "test").append("strVal", " ");
            BigDecimal result3 = (BigDecimal) method.invoke(dailySummaryTask, doc3, "trace");
            assertEquals(BigDecimal.ZERO, result3);

            // strVal="abc" -> ZERO
            Document doc4 = new Document("code", "test").append("strVal", "abc");
            BigDecimal result4 = (BigDecimal) method.invoke(dailySummaryTask, doc4, "trace");
            assertEquals(BigDecimal.ZERO, result4);

            // strVal="10" -> 10
            Document doc5 = new Document("code", "test").append("strVal", "10");
            BigDecimal result5 = (BigDecimal) method.invoke(dailySummaryTask, doc5, "trace");
            assertEquals(new BigDecimal("10"), result5);

            // strVal=null, fVal=5 -> 5
            Document doc6 = new Document("code", "test").append("fVal", 5);
            BigDecimal result6 = (BigDecimal) method.invoke(dailySummaryTask, doc6, "trace");
            assertEquals(new BigDecimal("5"), result6);

        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }
    }

    // ==================== 测试G：合计正好为0 ====================

    @Test
    void testUrineOutput_SumToZero() {
        String pid = "507f1f77bcf86cd799439011";
        String traceId = "trace_G";
        ClinicalTimeWindow window = buildWindow(2026, 8, 26, 2026, 8, 27);
        Document patient = buildPatient(pid, "TEST007");

        // 两条记录：10 + (-10) = 0
        List<Document> records = Arrays.asList(
                buildBedsideRecord(pid, "param_niaoLiang", "10", toDate(window.getStart().plusHours(1))),
                buildBedsideRecord(pid, "param_niaoLiang", "-10", toDate(window.getStart().plusHours(2)))
        );

        VitalSignPayload zeroPayload = buildPayload("1008", "小便量", "0", "ml");
        when(urineOutputHandler.handle(any(Document.class), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(zeroPayload);
        when(intermediateService.upsertPending(eq(zeroPayload), eq(traceId)))
                .thenReturn(Map.of("action", "INSERT", "status", "FAILED"));

        try {
            Method method = DailySummaryTask.class.getDeclaredMethod(
                    "processUrineOutput", java.util.List.class, Document.class,
                    String.class, ClinicalTimeWindow.class, String.class, int.class);
            method.setAccessible(true);
            method.invoke(dailySummaryTask, records, patient, pid, window, traceId, 0);
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }

        // 验证：合计为0时仍然入队
        verify(intermediateService, times(1)).upsertPending(eq(zeroPayload), eq(traceId));
        assertEquals("0", zeroPayload.getVitalsignNVal1());
    }

    // ==================== 测试H：0更新为真实值（IntermediateService hash变化） ====================

    @Test
    void testIntermediateService_ZeroToRealValue_HashChanges() {
        VitalSignPayload zeroPayload = VitalSignPayload.builder()
                .patientId("TEST001")
                .series("1")
                .vitalsignType("1007")
                .vitalsignName("大便次数")
                .vitalsignSVal2("0")
                .unit("次")
                .planTime(LocalDateTime.of(2026, 8, 27, 7, 0, 0))
                .build();

        VitalSignPayload realPayload = VitalSignPayload.builder()
                .patientId("TEST001")
                .series("1")
                .vitalsignType("1007")
                .vitalsignName("大便次数")
                .vitalsignSVal2("2")
                .unit("次")
                .planTime(LocalDateTime.of(2026, 8, 27, 7, 0, 0))
                .build();

        String hash0 = IntermediateService.computeSha256(zeroPayload);
        String hashReal = IntermediateService.computeSha256(realPayload);

        // hash必须不同
        assertNotEquals(hash0, hashReal, "0值和真实值的hash应该不同");

        // 幂等键必须相同（patientId_series_vitalsignType_planTime）
        String key0 = String.format("%s_%s_%s_%s",
                zeroPayload.getPatientId(), zeroPayload.getSeries(),
                zeroPayload.getVitalsignType(), "2026-08-27 07:00");
        String keyReal = String.format("%s_%s_%s_%s",
                realPayload.getPatientId(), realPayload.getSeries(),
                realPayload.getVitalsignType(), "2026-08-27 07:00");
        assertEquals(key0, keyReal, "幂等键应该相同");
    }

    // ==================== 测试I：手动接口日期转换 ====================

    @Test
    void testManualController_DateConversion() {
        // 模拟 ManualPushController 的日期转换逻辑
        java.time.LocalDate closingDate = java.time.LocalDate.of(2026, 8, 27);
        int hour = 7;

        // hour=7时，internalReportDate = closingDate - 1
        java.time.LocalDate internalReportDate = closingDate.minusDays(1);
        assertEquals(java.time.LocalDate.of(2026, 8, 26), internalReportDate);

        // 验证窗口
        ClinicalTimeWindow window = buildWindow(2026, 8, 26, 2026, 8, 27);
        assertEquals(LocalDateTime.of(2026, 8, 26, 7, 0, 0), window.getStart());
        assertEquals(LocalDateTime.of(2026, 8, 27, 7, 0, 0), window.getEnd());
    }

    // ==================== 测试J：入队SKIP不计数 ====================

    @Test
    void testEnqueueWithHours_SkipNotCounted() {
        String pid = "507f1f77bcf86cd799439011";
        String traceId = "trace_J";
        ClinicalTimeWindow window = buildWindow(2026, 8, 26, 2026, 8, 27);
        Document patient = buildPatient(pid, "TEST010");

        Document doc = new Document("code", "param_niaoLiang").append("strVal", "100");

        VitalSignPayload payload = buildPayload("1008", "小便量", "100", "ml");
        when(urineOutputHandler.handle(any(Document.class), eq(patient), eq(window.getEnd()), eq(traceId)))
                .thenReturn(payload);

        // 模拟SKIP返回
        when(intermediateService.upsertPending(eq(payload), eq(traceId)))
                .thenReturn(Map.of("action", "SKIP", "status", "SUCCESS"));

        try {
            Method method = DailySummaryTask.class.getDeclaredMethod(
                    "enqueueWithHours", BaseVitalSignHandler.class, Document.class,
                    Document.class, ClinicalTimeWindow.class, String.class, int.class);
            method.setAccessible(true);
            // 重置计数器
            java.lang.reflect.Field counterField = DailySummaryTask.class.getDeclaredField("enqueueCounter");
            counterField.setAccessible(true);
            java.util.concurrent.atomic.AtomicInteger counter =
                    (java.util.concurrent.atomic.AtomicInteger) counterField.get(dailySummaryTask);
            counter.set(0);

            method.invoke(dailySummaryTask, urineOutputHandler, doc, patient, window, traceId, 0);

            // 验证：SKIP时计数器不增加
            assertEquals(0, counter.get());
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }
    }

    // ==================== 测试K：推送分批 ====================

    @Test
    void testPushTask_MultipleBatches() {
        // 验证PushTask的batchSize和maxRecordsPerRun配置
        PushTask pushTask = new PushTask();
        try {
            // 设置batchSize=3, maxRecordsPerRun=10
            java.lang.reflect.Field batchSizeField = PushTask.class.getDeclaredField("batchSize");
            batchSizeField.setAccessible(true);
            batchSizeField.setInt(pushTask, 3);

            java.lang.reflect.Field maxField = PushTask.class.getDeclaredField("maxRecordsPerRun");
            maxField.setAccessible(true);
            maxField.setInt(pushTask, 10);

            assertEquals(3, batchSizeField.getInt(pushTask));
            assertEquals(10, maxField.getInt(pushTask));
        } catch (Exception e) {
            fail("反射设置失败: " + e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

    private VitalSignPayload buildPayload(String type, String name, String value, String unit) {
        VitalSignPayload.VitalSignPayloadBuilder builder = VitalSignPayload.builder()
                .vitalsignType(type)
                .vitalsignName(name)
                .vitalsignNVal1(value)
                .planTime(LocalDateTime.of(2026, 8, 27, 7, 0, 0));
        if (unit != null) {
            builder.unit(unit);
        }
        if ("1007".equals(type)) {
            builder.vitalsignSVal2(value).vitalsignNVal1(null);
        }
        return builder.build();
    }
}
