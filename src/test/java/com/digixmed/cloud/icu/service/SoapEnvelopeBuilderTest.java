package com.digixmed.cloud.icu.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SoapEnvelopeBuilder单元测试
 */
class SoapEnvelopeBuilderTest {

    private DefaultSoapEnvelopeBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new DefaultSoapEnvelopeBuilder();
    }

    @Test
    void testBusinessXmlEntersInputXml() {
        String businessXml = "<root><data>test</data></root>";
        String result = builder.build(businessXml);

        assertTrue(result.contains("<inputXml>"), "结果应包含<inputXml>标签");
        assertTrue(result.contains(businessXml), "业务XML应出现在结果中");
        assertTrue(result.contains("</inputXml>"), "结果应包含</inputXml>标签");
    }

    @Test
    void testOnlyOneEnvelope() {
        String businessXml = "<root/>";

        String result = builder.build(businessXml);
        int envelopeCount = countOccurrences(result, "<soapenv:Envelope");
        assertEquals(1, envelopeCount, "应只有一个Envelope元素");

        int closingEnvelopeCount = countOccurrences(result, "</soapenv:Envelope>");
        assertEquals(1, closingEnvelopeCount, "应只有一个闭合Envelope元素");
    }

    @Test
    void testOnlyOneBody() {
        String businessXml = "<root/>";

        String result = builder.build(businessXml);
        int bodyStartCount = countOccurrences(result, "<soapenv:Body>");
        assertEquals(1, bodyStartCount, "应只有一个Body元素");

        int bodyEndCount = countOccurrences(result, "</soapenv:Body>");
        assertEquals(1, bodyEndCount, "应只有一个闭合Body元素");
    }

    @Test
    void testOnlyOneXmlReqEwellByGet() {
        String businessXml = "<root/>";

        String result = builder.build(businessXml);
        int reqCount = countOccurrences(result, "<ws:xmlReqEwellByGet>");
        assertEquals(1, reqCount, "应只有一个xmlReqEwellByGet元素");

        int reqEndCount = countOccurrences(result, "</ws:xmlReqEwellByGet>");
        assertEquals(1, reqEndCount, "应只有一个闭合xmlReqEwellByGet元素");
    }

    @Test
    void testRequestBodyTrimNotEmpty() {
        String businessXml = "<root>data</root>";

        String result = builder.build(businessXml);
        assertFalse(result.trim().isEmpty(), "请求体trim后不应为空");
        assertTrue(result.length() > 50, "请求体应有足够长度");
    }

    @Test
    void testChineseCharactersNotGarbled() {
        String businessXml = "<root><name>体温</name><value>36.5℃</value></root>";

        String result = builder.build(businessXml);
        assertTrue(result.contains("体温"), "中文体温应正确保留");
        assertTrue(result.contains("36.5℃"), "℃符号应正确保留");
        assertTrue(result.contains("UTF-8"), "编码声明应为UTF-8");
    }

    @Test
    void testEmptyBusinessXmlThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> builder.build(null),
                "null应抛出IllegalArgumentException");

        assertThrows(IllegalArgumentException.class, () -> builder.build(""),
                "空字符串应抛出IllegalArgumentException");

        assertThrows(IllegalArgumentException.class, () -> builder.build("   "),
                "纯空白字符串应抛出IllegalArgumentException");

        assertThrows(IllegalArgumentException.class, () -> builder.build("\t\n"),
                "制表符换行应抛出IllegalArgumentException");
    }

    @Test
    void testBusinessXmlWithCdataEndStillGeneratesValidXml() {
        String businessXml = "<root>data]]></root>";

        String result = builder.build(businessXml);

        // 应该仍然生成有效的XML结构
        assertTrue(result.contains("<inputXml>"), "应包含inputXml标签");
        assertTrue(result.contains("</inputXml>"), "应包含闭合inputXml标签");

        // 结构完整性：只有一个Envelope、Body、xmlReqEwellByGet
        assertEquals(1, countOccurrences(result, "<soapenv:Envelope"), "应只有一个Envelope");
        assertEquals(1, countOccurrences(result, "<soapenv:Body>"), "应只有一个Body");
        assertEquals(1, countOccurrences(result, "<ws:xmlReqEwellByGet>"), "应只有一个xmlReqEwellByGet");

        // 原始内容应保留在CDATA中
        assertTrue(result.contains("data]]"), "原始数据应保留");
    }

    @Test
    void testNoStringNullAppears() {
        String businessXml = "<root>value</root>";

        String result = builder.build(businessXml);
        assertFalse(result.contains("null"), "结果中不应出现字符串'null'");
    }

    @Test
    void testXmlDeclarationPresent() {
        String businessXml = "<root/>";

        String result = builder.build(businessXml);
        assertTrue(result.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"),
                "应以XML声明开头");
    }

    @Test
    void testCdataWrappingCorrect() {
        String businessXml = "<root>hello</root>";

        String result = builder.build(businessXml);

        // 验证CDATA正确包裹业务XML
        int cdataStart = result.indexOf("<![CDATA[");
        int cdataEnd = result.lastIndexOf("]]>");

        assertTrue(cdataStart >= 0, "应包含CDATA起始标记");
        assertTrue(cdataEnd > cdataStart, "CDATA结束标记应在起始标记之后");

        // 提取CDATA中的内容
        String cdataContent = result.substring(cdataStart + 9, cdataEnd);
        assertEquals(businessXml, cdataContent, "CDATA中的内容应与输入业务XML一致");
    }

    @Test
    void testMultipleCdataEndsAreHandled() {
        // 包含多个]]>的情况
        String businessXml = "<a>]]><b>]]><c>";

        String result = builder.build(businessXml);

        // 验证结构完整
        assertTrue(result.contains("<inputXml>"), "应包含inputXml");
        assertTrue(result.contains("</inputXml>"), "应包含闭合inputXml");

        // 验证原始内容可恢复
        assertTrue(result.contains("<a>"), "应包含<a>");
        assertTrue(result.contains("<b>"), "应包含<b>");
        assertTrue(result.contains("<c>"), "应包含<c>");
    }

    /**
     * 计算子串出现次数
     */
    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
