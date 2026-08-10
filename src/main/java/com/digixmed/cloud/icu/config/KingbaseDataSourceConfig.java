package com.digixmed.cloud.icu.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * KingbaseES数据源配置
 *
 * 业务目的：配置KingbaseES JDBC数据源，用于只读查询在科患者
 * 输入：KingbaseProperties配置
 * 输出：DataSource和JdbcTemplate Bean
 * 异常策略：连接失败时记录ERROR日志，不阻塞主流程
 */
@Configuration
public class KingbaseDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(KingbaseDataSourceConfig.class);

    @Autowired
    private KingbaseProperties kingbaseProperties;

    /**
     * 创建KingbaseES数据源
     *
     * @return HikariDataSource
     */
    @Bean(name = "kingbaseDataSource")
    public DataSource kingbaseDataSource() {
        if (kingbaseProperties.getUrl() == null || kingbaseProperties.getUrl().isEmpty()) {
            log.warn("Kingbase数据库URL未配置，Kingbase查询功能将不可用");
            return null;
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(kingbaseProperties.getUrl());
        config.setUsername(kingbaseProperties.getUsername());
        config.setPassword(kingbaseProperties.getPassword());
        config.setConnectionTimeout(kingbaseProperties.getConnectionTimeout());
        config.setReadOnly(true);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setPoolName("KingbasePool");

        log.info("初始化KingbaseES数据源，URL: {}", maskUrl(kingbaseProperties.getUrl()));
        return new HikariDataSource(config);
    }

    /**
     * 创建KingbaseES JdbcTemplate
     *
     * @return JdbcTemplate
     */
    @Bean(name = "kingbaseJdbcTemplate")
    public JdbcTemplate kingbaseJdbcTemplate() {
        DataSource dataSource = kingbaseDataSource();
        if (dataSource == null) {
            return null;
        }
        return new JdbcTemplate(dataSource);
    }

    /**
     * 脱敏URL（隐藏密码）
     *
     * @param url 原始URL
     * @return 脱敏后的URL
     */
    private String maskUrl(String url) {
        if (url == null) return "null";
        return url.replaceAll(":[^@/]+@", ":****@");
    }
}
