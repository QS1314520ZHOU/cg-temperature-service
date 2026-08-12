package com.digixmed.cloud.icu.config;

import org.springframework.context.annotation.Configuration;

/**
 * MongoDB自定义配置
 *
 * 注意：不要注册全局的 Integer<->Boolean 转换器，
 * 因为 Spring Data MongoDB 内部也使用 Integer 类型（如排序方向），
 * 全局转换器会干扰这些内部操作导致查询失败。
 *
 * MongoDB 中 Boolean 字段存储为 Integer(0/1) 的问题，
 * 通过修改 POJO 字段类型为 Integer 来适配。
 */
@Configuration
public class MongoConfig {
    // 无全局转换器，避免干扰 Spring Data MongoDB 内部操作
}
