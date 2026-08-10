package com.digixmed.cloud.icu.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * KingbaseES数据库配置
 *
 * 业务目的：配置KingbaseES数据库连接信息，用于查询在科患者
 * 输入：环境变量或配置文件
 * 输出：数据库连接参数
 * 异常策略：配置缺失时启动失败
 */
@Data
@Component
@ConfigurationProperties(prefix = "kingbase")
public class KingbaseProperties {

    /**
     * 数据库连接URL
     * 格式：jdbc:kingbase8://<host>:<port>/<database>
     */
    private String url;

    /**
     * 数据库用户名
     */
    private String username;

    /**
     * 数据库密码
     */
    private String password;

    /**
     * 数据库Schema
     * 默认：np_nis_cqchonggang
     */
    private String schema = "np_nis_cqchonggang";

    /**
     * 连接超时时间（毫秒）
     */
    private int connectionTimeout = 5000;

    /**
     * 读取超时时间（毫秒）
     */
    private int readTimeout = 10000;
}
