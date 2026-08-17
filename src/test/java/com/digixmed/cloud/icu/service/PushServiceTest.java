package com.digixmed.cloud.icu.service;

import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.service.IntermediateService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SHA-256 哈希计算逻辑测试
 * 哈希计算已迁移到 IntermediateService.computeSha256
 */
class PushServiceTest {

    @Test
    void testComputeSha256_相同内容相同哈希() {
        VitalSignPayload payload1 = createPayload("36.5", "test");
        VitalSignPayload payload2 = createPayload("36.5", "test");

        String hash1 = IntermediateService.computeSha256(payload1);
        String hash2 = IntermediateService.computeSha256(payload2);

        assertEquals(hash1, hash2);
    }

    @Test
    void testComputeSha256_不同内容不同哈希() {
        VitalSignPayload payload1 = createPayload("36.5", "test");
        VitalSignPayload payload2 = createPayload("37.5", "test");

        String hash1 = IntermediateService.computeSha256(payload1);
        String hash2 = IntermediateService.computeSha256(payload2);

        assertNotEquals(hash1, hash2);
    }

    @Test
    void testComputeSha256_空值处理() {
        VitalSignPayload payload1 = createPayload(null, null);
        VitalSignPayload payload2 = createPayload(null, null);

        String hash1 = IntermediateService.computeSha256(payload1);
        String hash2 = IntermediateService.computeSha256(payload2);

        assertEquals(hash1, hash2);
    }

    @Test
    void testComputeSha256_哈希长度() {
        VitalSignPayload payload = createPayload("36.5", "test");
        String hash = IntermediateService.computeSha256(payload);

        // SHA-256 produces 64 character hex string
        assertEquals(64, hash.length());
    }

    @Test
    void testComputeSha256_哈希格式() {
        VitalSignPayload payload = createPayload("36.5", "test");
        String hash = IntermediateService.computeSha256(payload);

        // Should only contain hex characters
        assertTrue(hash.matches("[0-9a-f]+"));
    }

    private VitalSignPayload createPayload(String nVal1, String nVal2) {
        return VitalSignPayload.builder()
                .vitalsignNVal1(nVal1)
                .vitalsignNVal2(nVal2)
                .vitalsignNVal3("nVal3")
                .vitalsignSVal1("sVal1")
                .vitalsignSVal2("sVal2")
                .recordNurseId("nurse1")
                .recordNurseName("Test Nurse")
                .vitalsignName("Temperature")
                .vitalsignType("1001")
                .unit("℃")
                .build();
    }
}
