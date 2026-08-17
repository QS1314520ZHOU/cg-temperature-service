package com.digixmed.cloud.icu.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 临床时间窗口模型
 *
 * 业务目的：表示一个时间窗口，用于体征数据统计
 * 输入：窗口开始时间、结束时间、类型
 * 输出：时间窗口对象
 * 异常策略：参数校验失败时抛出IllegalArgumentException
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicalTimeWindow {

    /**
     * 窗口开始时间（包含）
     */
    private LocalDateTime start;

    /**
     * 窗口结束时间（不包含）
     */
    private LocalDateTime end;

    /**
     * 窗口类型
     */
    private WindowType type;

    /**
     * 报表日期（用于每日汇总）
     */
    private LocalDateTime reportDate;

    /**
     * 窗口类型枚举
     */
    public enum WindowType {
        /**
         * 普通体征时间点窗口
         * 例如：[02:00, 06:00)
         */
        VITAL_SIGN_POINT,

        /**
         * 每日汇总窗口
         * 例如：[前一天07:00, 当天07:00)
         */
        DAILY_SUMMARY,

        /**
         * 体温复测窗口
         * 例如：[标准时间点, 标准时间点+1小时)
         */
        TEMPERATURE_RECHECK,

        /**
         * 身高体重窗口（7天分页）
         */
        HEIGHT_WEIGHT_PAGE
    }

    /**
     * 检查指定时间是否在窗口内（左闭右开 [start, end)）
     *
     * @param time 要检查的时间
     * @return 是否在窗口内
     */
    public boolean contains(LocalDateTime time) {
        return !time.isBefore(start) && time.isBefore(end);
    }

    /**
     * 检查指定时间是否在窗口内（左开右闭 (start, end]）
     *
     * 护理日语义：07:00 归属上一护理日，次日 07:00 归属当前护理日。
     *
     * @param time 要检查的时间
     * @return 是否在窗口内
     */
    public boolean containsLeftOpenRightClosed(LocalDateTime time) {
        return time.isAfter(start) && !time.isAfter(end);
    }

    /**
     * 获取窗口持续时间（小时）
     *
     * @return 持续时间
     */
    public long getDurationHours() {
        return java.time.Duration.between(start, end).toHours();
    }

    @Override
    public String toString() {
        return String.format("[%s, %s)", start, end);
    }
}
