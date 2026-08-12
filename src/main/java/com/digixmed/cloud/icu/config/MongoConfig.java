package com.digixmed.cloud.icu.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import java.util.Arrays;

/**
 * MongoDB自定义配置
 *
 * 注册 Boolean → Integer 转换器，用于读取MongoDB中已有的Boolean类型数据。
 * IntermediateTable的字段已改为Integer类型，但历史数据仍是Boolean。
 */
@Configuration
public class MongoConfig {

    @Bean
    public MongoCustomConversions customConversions() {
        return new MongoCustomConversions(Arrays.asList(
                new BooleanToIntegerConverter()
        ));
    }

    /**
     * 将MongoDB中的Boolean值转换为Integer
     * true → 1, false → 0
     */
    static class BooleanToIntegerConverter implements Converter<Boolean, Integer> {
        @Override
        public Integer convert(Boolean source) {
            return source != null && source ? 1 : 0;
        }
    }
}
