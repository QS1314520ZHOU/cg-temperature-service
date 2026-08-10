package com.digixmed.cloud.icu.handler;

import com.digixmed.cloud.icu.model.PatientIdentityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PainScoreHandler单元测试
 */
class PainScoreHandlerTest {

    private PainScoreHandler handler;

    @BeforeEach
    void setUp() {
        PatientIdentityMapper mapper = new PatientIdentityMapper();
        handler = new PainScoreHandler(mapper);
    }

    @Test
    void testExtractLastNumber() {
        // 测试提取最后一个数值
        assertEquals("3", handler.extractLastNumber("cpot-3"));
        assertEquals("4", handler.extractLastNumber("CPOT：4"));
        assertEquals("3", handler.extractLastNumber("3"));
        assertEquals("2", handler.extractLastNumber("评分:2"));
        assertEquals("5", handler.extractLastNumber("疼痛评分-5分"));
        assertEquals("10", handler.extractLastNumber("10"));
        assertNull(handler.extractLastNumber(null));
        assertNull(handler.extractLastNumber(""));
        assertNull(handler.extractLastNumber("无数据"));
        assertNull(handler.extractLastNumber("cpot-"));
    }

    @Test
    void testExtractLastNumberWithDecimal() {
        // 测试提取带小数的数值
        assertEquals("3.5", handler.extractLastNumber("疼痛-3.5"));
        assertEquals("2.0", handler.extractLastNumber("2.0"));
    }
}
