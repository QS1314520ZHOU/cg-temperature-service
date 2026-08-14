package com.digixmed.cloud.icu.service;

import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.ClinicalTimeWindow.WindowType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 临床时间窗口服务
 *
 * 业务目的：统一管理所有时间窗口的计算逻辑
 * 输入：日期、小时数、窗口类型
 * 输出：ClinicalTimeWindow对象
 * 时间边界：
 *   - 普通体征时间点：02:00, 06:00, 10:00, 14:00, 18:00, 22:00
 *   - 每日汇总窗口：[前一天07:00, 当天07:00)
 *   - 体温复测窗口：[标准时间点, 标准时间点+1小时)
 * 异常策略：参数非法时记录ERROR日志并返回null
 *
 * 关键规则：
 *   1. 所有窗口必须采用左闭右开 [start, end)
 *   2. 禁止使用 23:59:59 拼接方式
 *   3. 所有数据库查询统一使用 time >= start AND time < end
 *   4. 时区固定为 Asia/Shanghai
 */
@Service
public class ClinicalTimeWindowService {

    private static final Logger log = LoggerFactory.getLogger(ClinicalTimeWindowService.class);

    /**
     * 普通体征标准时间点（小时）
     */
    private static final List<Integer> VITAL_SIGN_HOURS = Arrays.asList(2, 6, 10, 14, 18, 22);

    /**
     * 每日汇总归档时间（小时）
     */
    private static final int DAILY_SUMMARY_HOUR = 7;

    /**
     * 体温复测窗口长度（小时）
     */
    private static final long TEMPERATURE_RECHECK_HOURS = 1;

    /**
     * 身高体重分页天数
     */
    private static final int HEIGHT_WEIGHT_PAGE_DAYS = 7;

    /**
     * 时区
     */
    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    /**
     * 时钟（可注入，用于测试）
     */
    private Clock clock;

    public ClinicalTimeWindowService() {
        this.clock = Clock.system(ZONE_SHANGHAI);
    }

    /**
     * 构建指定日期和小时的体征时间点
     * 业务目的：确定某个标准时间点的 LocalDateTime
     *
     * @param date 日期
     * @param hour 小时（2,6,10,14,18,22）
     * @return 标准时间点
     */
    public LocalDateTime buildVitalPoint(LocalDate date, int hour) {
        if (!VITAL_SIGN_HOURS.contains(hour)) {
            log.error("STEP_01_INVALID_HOUR hour={} 无效，有效值为{}", hour, VITAL_SIGN_HOURS);
            return null;
        }
        return LocalDateTime.of(date, java.time.LocalTime.of(hour, 0, 0));
    }

    /**
     * 构建每日汇总统计窗口
     * 业务目的：确定每日汇总的时间范围 [前一天07:00, 当天07:00)
     *
     * 例如：2026-08-08 07:00 生成的数据，其统计窗口为：
     *   start = 2026-08-07 07:00:00
     *   end   = 2026-08-08 07:00:00
     *
     * @param reportDate 报表日期（当天）
     * @return 每日汇总时间窗口
     */
    public ClinicalTimeWindow buildDailyWindow(LocalDate reportDate) {
        LocalDateTime reportTime = LocalDateTime.of(reportDate, java.time.LocalTime.of(DAILY_SUMMARY_HOUR, 0, 0));
        LocalDateTime windowStart = reportTime.minusDays(1);
        return ClinicalTimeWindow.builder()
                .start(windowStart)
                .end(reportTime)
                .type(WindowType.DAILY_SUMMARY)
                .reportDate(reportTime)
                .build();
    }

    /**
     * 构建指定时间点的体征时间窗口
     * 业务目的：确定某个标准时间点的数据归属窗口
     *
     * 窗口逻辑：
     *   当前标准时间点N → [N, N+下一个时间点)
     *   例如 02:00 → [02:00, 06:00)
     *
     * @param date 日期
     * @param hour 小时（2,6,10,14,18,22）
     * @return 体征时间窗口
     */
    public ClinicalTimeWindow buildVitalPointWindow(LocalDate date, int hour) {
        LocalDateTime point = buildVitalPoint(date, hour);
        if (point == null) {
            return null;
        }
        // getNextVitalPoint 已处理跨天（22:00 -> 次日02:00），不会返回 null
        LocalDateTime nextPoint = getNextVitalPoint(point);
        return ClinicalTimeWindow.builder()
                .start(point)
                .end(nextPoint)
                .type(WindowType.VITAL_SIGN_POINT)
                .build();
    }

    /**
     * 构建体温复测窗口
     * 业务目的：用于查找体温>=38.5℃后的复测记录
     *
     * 窗口：[标准时间点, 标准时间点+1小时)
     * 例如 02:00 → [02:00, 03:00)
     *
     * @param vitalPoint 标准时间点
     * @return 体温复测窗口
     */
    public ClinicalTimeWindow buildTemperatureRecheckWindow(LocalDateTime vitalPoint) {
        LocalDateTime windowEnd = vitalPoint.plusHours(TEMPERATURE_RECHECK_HOURS);
        return ClinicalTimeWindow.builder()
                .start(vitalPoint)
                .end(windowEnd)
                .type(WindowType.TEMPERATURE_RECHECK)
                .build();
    }

    /**
     * 构建身高体重发送窗口（7天分页）
     * 业务目的：判断某个日期是否需要发送身高体重
     *
     * 发送条件：
     *   pageDayIndex >= 0 AND pageDayIndex % 7 == 0
     *   pageDayIndex = DAYS.between(admissionWardDate, reportDate)
     *
     * @param admissionWardDate 入科日期
     * @param reportDate 报表日期
     * @return 身高体重窗口（如需发送），否则返回null
     */
    public ClinicalTimeWindow buildHeightWeightWindow(LocalDate admissionWardDate, LocalDate reportDate) {
        long pageDayIndex = ChronoUnit.DAYS.between(admissionWardDate, reportDate);
        if (pageDayIndex >= 0 && pageDayIndex % HEIGHT_WEIGHT_PAGE_DAYS == 0) {
            LocalDateTime sendTime = LocalDateTime.of(reportDate, java.time.LocalTime.of(DAILY_SUMMARY_HOUR, 0, 0));
            return ClinicalTimeWindow.builder()
                    .start(sendTime)
                    .end(sendTime)
                    .type(WindowType.HEIGHT_WEIGHT_PAGE)
                    .reportDate(sendTime)
                    .build();
        }
        return null;
    }

    /**
     * 获取上一个标准时间点
     *
     * @param current 当前时间点
     * @return 上一个标准时间点
     */
    public LocalDateTime getPreviousVitalPoint(LocalDateTime current) {
        for (int i = VITAL_SIGN_HOURS.size() - 1; i >= 0; i--) {
            // 按完整时间比较，避免只比小时导致 10:00 整点落错窗口
            LocalDateTime candidate = LocalDateTime.of(current.toLocalDate(), java.time.LocalTime.of(VITAL_SIGN_HOURS.get(i), 0, 0));
            if (candidate.isBefore(current)) {
                return candidate;
            }
        }
        // 当前时间在02:00之前，上一个时间点是前一天22:00
        return LocalDateTime.of(current.toLocalDate().minusDays(1), java.time.LocalTime.of(22, 0, 0));
    }

    /**
     * 获取下一个标准时间点
     *
     * @param current 当前时间点
     * @return 下一个标准时间点
     */
    public LocalDateTime getNextVitalPoint(LocalDateTime current) {
        for (int h : VITAL_SIGN_HOURS) {
            LocalDateTime candidate = LocalDateTime.of(current.toLocalDate(), java.time.LocalTime.of(h, 0, 0));
            if (candidate.isAfter(current)) {
                return candidate;
            }
        }
        // 当前时间在22:00或之后，下一个时间点是次日02:00
        return LocalDateTime.of(current.toLocalDate().plusDays(1), java.time.LocalTime.of(2, 0, 0));
    }

    /**
     * 获取当前最近的标准时间点
     *
     * @return 当前最近的标准时间点
     */
    public LocalDateTime getCurrentVitalPoint() {
        LocalDateTime now = LocalDateTime.now(clock);
        int currentHour = now.getHour();
        // 找到当前时间所属的标准时间点
        for (int i = VITAL_SIGN_HOURS.size() - 1; i >= 0; i--) {
            if (currentHour >= VITAL_SIGN_HOURS.get(i)) {
                return LocalDateTime.of(now.toLocalDate(), java.time.LocalTime.of(VITAL_SIGN_HOURS.get(i), 0, 0));
            }
        }
        // 当前时间在02:00之前，标准时间点是前一天22:00
        return LocalDateTime.of(now.toLocalDate().minusDays(1), java.time.LocalTime.of(22, 0, 0));
    }

    /**
     * 获取所有标准时间点（当天）
     *
     * @param date 日期
     * @return 标准时间点列表
     */
    public List<LocalDateTime> getAllVitalPoints(LocalDate date) {
        List<LocalDateTime> points = new ArrayList<>();
        for (int hour : VITAL_SIGN_HOURS) {
            points.add(buildVitalPoint(date, hour));
        }
        return points;
    }

    /**
     * 检查某个时间是否是标准时间点
     *
     * @param time 时间
     * @return 是否是标准时间点
     */
    public boolean isVitalPoint(LocalDateTime time) {
        return VITAL_SIGN_HOURS.contains(time.getHour()) && time.getMinute() == 0 && time.getSecond() == 0;
    }

    /**
     * 获取有效的时间点列表
     *
     * @return 时间点列表
     */
    public List<Integer> getVitalSignHours() {
        return new ArrayList<>(VITAL_SIGN_HOURS);
    }

    /**
     * 检查是否应该发送身高体重
     *
     * @param admissionWardDate 入科日期
     * @param reportDate 报表日期
     * @return 是否应该发送
     */
    public boolean shouldSendHeightWeight(LocalDate admissionWardDate, LocalDate reportDate) {
        return buildHeightWeightWindow(admissionWardDate, reportDate) != null;
    }
}
