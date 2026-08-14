package com.digixmed.cloud.icu.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 体温复测巡检配置
 *
 * 业务目的：配置体温复测巡检的回望窗口（lookback），控制扫描历史待复测高热记录的时间范围
 * 输入：环境变量 VITALSIGN_RECHECK_LOOKBACK_HOURS 或配置文件 vitalsign.recheck.lookback-hours
 * 输出：回望窗口时长（小时）
 * 异常策略：配置缺失时使用默认值 24 小时
 */
@Data
@Component
@ConfigurationProperties(prefix = "vitalsign.recheck")
public class VitalSignRecheckProperties {

    /**
     * 复测巡检回望窗口（小时），默认 24 小时。
     * 只巡检 timePoint 在最近 N 小时内的待复测记录，
     * 保证当天已录入的历史高热记录也能被补充复测。
     * 可用环境变量 VITALSIGN_RECHECK_LOOKBACK_HOURS 覆盖。
     */
    private int lookbackHours = 24;
}
