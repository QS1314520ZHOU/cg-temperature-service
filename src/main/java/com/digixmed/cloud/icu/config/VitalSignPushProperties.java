package com.digixmed.cloud.icu.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 体征推送配置
 *
 * 业务目的：配置SOAP/HTTP推送参数，用于向HIS系统推送体温单数据
 * 输入：环境变量或配置文件
 * 输出：推送配置参数
 * 异常策略：配置缺失时启动失败
 */
@Data
@Component
@ConfigurationProperties(prefix = "vitalsign.push")
public class VitalSignPushProperties {

    /**
     * 推送接口地址
     * 格式：http://<host>:<port>/<path>
     */
    private String url;

    /**
     * 连接超时时间（毫秒）
     */
    private int connectionTimeout = 10000;

    /**
     * 读取超时时间（毫秒）
     */
    private int readTimeout = 30000;

    /**
     * 是否启用推送
     */
    private boolean enabled = true;

    /**
     * 最大重试次数
     */
    private int maxRetryCount = 3;

    /**
     * 重试间隔基础值（毫秒）
     */
    private long retryBaseInterval = 1000;

    /**
     * 请求体最大保存长度（字符）
     */
    private int maxRequestBodyLength = 4000;

    /**
     * 响应体最大保存长度（字符）
     */
    private int maxResponseBodyLength = 2000;
}
