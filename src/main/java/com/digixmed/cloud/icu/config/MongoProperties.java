package com.digixmed.cloud.icu.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MongoDB配置
 *
 * 业务目的：配置MongoDB连接信息，用于存储体征数据和中间表
 * 输入：环境变量或配置文件
 * 输出：数据库连接参数
 * 异常策略：配置缺失时启动失败
 */
@Data
@Component
@ConfigurationProperties(prefix = "spring.data.mongodb")
public class MongoProperties {

    /**
     * MongoDB连接URI
     * 格式：mongodb://<username>:<password>@<host>:<port>/<database>
     */
    private String uri;

    /**
     * 数据库名称
     */
    private String database;
}
