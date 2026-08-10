package com.digixmed.cloud.icu.service;

import com.digixmed.cloud.icu.model.VitalSignPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PushService单元测试
 * 测试SHA-256哈希计算逻辑
 */
class PushServiceTest {

    private PushService pushService;

    @BeforeEach
    void setUp() {
        pushService = new PushService();
    }

    @Test
    void testComputePayloadHash_相同内容相同哈希() throws Exception {
        VitalSignPayload payload1 = createPayload("36.5", "test");
        VitalSignPayload payload2 = createPayload("36.5", "test");

        String hash1 = invokeComputePayloadHash(payload1);
        String hash2 = invokeComputePayloadHash(payload2);

        assertEquals(hash1, hash2);
    }

    @Test
    void testComputePayloadHash_不同内容不同哈希() throws Exception {
        VitalSignPayload payload1 = createPayload("36.5", "test");
        VitalSignPayload payload2 = createPayload("37.5", "test");

        String hash1 = invokeComputePayloadHash(payload1);
        String hash2 = invokeComputePayloadHash(payload2);

        assertNotEquals(hash1, hash2);
    }

    @Test
    void testComputePayloadHash_空值处理() throws Exception {
        VitalSignPayload payload1 = createPayload(null, null);
        VitalSignPayload payload2 = createPayload(null, null);

        String hash1 = invokeComputePayloadHash(payload1);
        String hash2 = invokeComputePayloadHash(payload2);

        assertEquals(hash1, hash2);
    }

    @Test
    void testComputePayloadHash_哈希长度() throws Exception {
        VitalSignPayload payload = createPayload("36.5", "test");
        String hash = invokeComputePayloadHash(payload);

        // SHA-256 produces 64 character hex string
        assertEquals(64, hash.length());
    }

    @Test
    void testComputePayloadHash_哈希格式() throws Exception {
        VitalSignPayload payload = createPayload("36.5", "test");
        String hash = invokeComputePayloadHash(payload);

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

    private String invokeComputePayloadHash(VitalSignPayload payload) throws Exception {
        Method method = PushService.class.getDeclaredMethod("computePayloadHash", VitalSignPayload.class);
        method.setAccessible(true);
        return (String) method.invoke(pushService, payload);
    }
}
