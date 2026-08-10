package com.digixmed.cloud.icu.service;

/**
 * SOAP信封构建器接口
 *
 * 负责将业务XML包装为符合Ewell接口规范的SOAP Envelope格式
 */
public interface SoapEnvelopeBuilder {

    /**
     * 将业务XML构建为完整的SOAP Envelope
     *
     * @param businessXml 业务XML内容
     * @return 包含业务XML的SOAP Envelope字符串
     * @throws IllegalArgumentException 当businessXml为null或空白时
     */
    String build(String businessXml);
}
