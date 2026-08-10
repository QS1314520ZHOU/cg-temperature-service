package com.digixmed.cloud.icu.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TraceIdGenerator单元测试
 */
class TraceIdGeneratorTest {

    @Test
    void testGenerate_格式正确() {
        String traceId = TraceIdGenerator.generate();

        assertNotNull(traceId);
        // 格式: TS-年月日时分秒-随机4位
        assertTrue(traceId.startsWith("TS-"));
        assertTrue(traceId.contains("-"));
        assertTrue(traceId.length() > 15);
    }

    @Test
    void testGenerate_唯一性() {
        String traceId1 = TraceIdGenerator.generate();
        String traceId2 = TraceIdGenerator.generate();

        assertNotEquals(traceId1, traceId2);
    }

    @Test
    void testGenerateWithPatient_格式正确() {
        String patientId = "test-patient-123";
        String traceId = TraceIdGenerator.generateWithPatient(patientId);

        assertNotNull(traceId);
        assertTrue(traceId.startsWith("TS-"));
        assertTrue(traceId.contains("-"));
        assertTrue(traceId.contains("123"));
    }

    @Test
    void testGenerateWithPatient_唯一性() {
        String patientId = "test-patient-123";
        String traceId1 = TraceIdGenerator.generateWithPatient(patientId);
        String traceId2 = TraceIdGenerator.generateWithPatient(patientId);

        assertNotEquals(traceId1, traceId2);
    }

    @Test
    void testGenerateWithPatient_不同患者不同ID() {
        String traceId1 = TraceIdGenerator.generateWithPatient("patient-1");
        String traceId2 = TraceIdGenerator.generateWithPatient("patient-2");

        assertNotEquals(traceId1, traceId2);
    }
}
