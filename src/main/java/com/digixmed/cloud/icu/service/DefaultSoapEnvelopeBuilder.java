package com.digixmed.cloud.icu.service;

import org.springframework.stereotype.Component;

/**
 * SOAP信封构建器默认实现
 *
 * 生成单层SOAP Envelope，将业务XML放入CDATA中
 * 遵循Ewell接口规范：xmlns:ws="http://ws.nis.ewell/"
 */
@Component
public class DefaultSoapEnvelopeBuilder implements SoapEnvelopeBuilder {

    private static final String CDATA_START = "<![CDATA[";
    private static final String CDATA_END = "]]>";

    @Override
    public String build(String businessXml) {
        if (businessXml == null || businessXml.trim().isEmpty()) {
            throw new IllegalArgumentException("businessXml cannot be null or blank");
        }

        String safeBody = escapeCdata(businessXml);

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ws=\"http://ws.nis.ewell/\">\n"
                + "    <soapenv:Header/>\n"
                + "    <soapenv:Body>\n"
                + "        <ws:xmlReqEwellByGet>\n"
                + "            <inputXml>" + safeBody + "</inputXml>\n"
                + "        </ws:xmlReqEwellByGet>\n"
                + "    </soapenv:Body>\n"
                + "</soapenv:Envelope>";
    }

    /**
     * 安全处理CDATA中的内容
     * 如果业务XML包含]]>，则拆分为多个CDATA段
     *
     * 原理：将 content 中的每个]]> 替换为 ]]><![CDATA[
     * 这样第一个CDATA段正常结束，]]>作为字面文本，然后开始新的CDATA段
     */
    private String escapeCdata(String content) {
        if (content.contains(CDATA_END)) {
            // 拆分CDATA：将]]>替换为]]><![CDATA[ 使其跨多个CDATA段安全传输
            String[] parts = content.split("\\]\\]>", -1);
            StringBuilder sb = new StringBuilder(CDATA_START);
            for (int i = 0; i < parts.length; i++) {
                sb.append(parts[i]);
                if (i < parts.length - 1) {
                    sb.append(CDATA_END).append(CDATA_START);
                }
            }
            sb.append(CDATA_END);
            return sb.toString();
        }
        return CDATA_START + content + CDATA_END;
    }
}
