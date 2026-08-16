package com.digixmed.cloud.icu.service;

import com.digixmed.cloud.icu.model.ClinicalTimeWindow;
import com.digixmed.cloud.icu.model.ClinicalTimeWindow.WindowType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 临床时间窗口服务
 *
 * 业务目的：统一管理所有时间窗口的计算逻辑
 * 时间边界：
 *   - 普通体征时间点：02:00, 06:00, 10:00, 14:00, 18:00, 22:00
 *   - 血压 / 大便次数：只取 07:00 槽位，窗口 [07:00, 08:00)
 *   - 每日汇总窗口：[前一天07:00, 当天07:00)
 *   - 体温复测窗口：[标准时间点, 标准时间点+1小时)
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

    /** 普通体征标准时间点（小时） */
    private static final List<Integer> VITAL_SIGN_HOURS = Arrays.asList(2, 6, 10, 14, 18, 22);

    /** 每日汇总归档时间（小时）；血压与大便次数同样只取该槽位 */
    private static final int DAILY_SUMMARY_HOUR = 7;

    /** 体温复测窗口长度（小时） */
    private static final long TEMPERATURE_RECHECK_HOURS = 1;

    /** 身高体重分页天数 */
    private static final int HEIGHT_WEIGHT_PAGE_DAYS = 7;

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");

    private Clock clock;

    public ClinicalTimeWindowService() {
        this.clock = Clock.system(ZONE_SHANGHAI);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * 构建指定日期和小时的体征时间点（精确时刻，用于 time 精确相等匹配）
     *
     * 护理单格子时间是整点写入，采集必须 time == 标准时刻，
     * 不能用区间，否则非整点的临时测量会被误匹配。
     */
    public LocalDateTime buildVitalPoint(LocalDate date, int hour) {
        if (date == null || !VITAL_SIGN_HOURS.contains(hour)) {
            return null;
        }
        return date.atTime(hour, 0, 0);
    }

    /** 07:00 精确时刻（血压 / 大便次数 / 汇总） */
    public LocalDateTime buildSevenAmPoint(LocalDate date) {
        if (date == null) {
            return null;
        }
        return date.atTime(DAILY_SUMMARY_HOUR, 0, 0);
    }

    /**
     * 构建每日汇总统计窗口 [前一天07:00, 当天07:00)
     */
    public ClinicalTimeWindow buildDailyWindow(LocalDate reportDate) {
        LocalDateTime reportTime = LocalDateTime.of(reportDate, LocalTime.of(DAILY_SUMMARY_HOUR, 0, 0));
        LocalDateTime windowStart = reportTime.minusDays(1);
        return ClinicalTimeWindow.builder()
                .start(windowStart)
                .end(reportTime)
                .type(WindowType.DAILY_SUMMARY)
                .reportDate(reportTime)
                .build();
    }

    /**
     * 构建 07:00 槽位窗口 [07:00, 08:00)
     *
     * 业务用途：血压、大便次数都只取 07:00 这一格的数据。
     * 之所以是 [07:00, 08:00) 而不是 [06:00, 07:00)：
     *   bedside.time 存的是护理记录单的"格子时间"，07:00 那一格的记录 time 就等于 07:00，
     *   左闭右开取 [06:00,07:00) 会把 07:00 整点这条正好排除掉，导致一条都查不到。
     */
    public ClinicalTimeWindow buildSevenAmWindow(LocalDate date) {
        LocalDateTime start = LocalDateTime.of(date, LocalTime.of(DAILY_SUMMARY_HOUR, 0, 0));
        return ClinicalTimeWindow.builder()
                .start(start)
                .end(start.plusHours(1))
                .type(WindowType.DAILY_SUMMARY)
                .reportDate(start)
                .build();
    }

    /**
     * 构建指定时间点的体征时间窗口，例如 02:00 → [02:00, 06:00)
     */
    public ClinicalTimeWindow buildVitalPointWindow(LocalDate date, int hour) {
        LocalDateTime point = buildVitalPoint(date, hour);
        if (point == null) {
            return null;
        }
        LocalDateTime nextPoint = getNextVitalPoint(point);
        return ClinicalTimeWindow.builder()
                .start(point)
                .end(nextPoint)
                .type(WindowType.VITAL_SIGN_POINT)
                .build();
    }

    /**
     * 构建体温复测窗口 [标准时间点, 标准时间点+1小时)
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
     *
     * 发送条件：pageDayIndex > 0 AND pageDayIndex % 7 == 0
     *
     * 注意 pageDayIndex == 0（入科当天）被排除：
     *   入科当天的身高体重由 VitalSignScanTask.processAdmissionVitalSigns 负责，
     *   随"入科第一条生命体征"一起回传，记录者与体温一致。
     *   若这里也放行，会因为 planTime 不同（入科标准点 vs 07:00）产生两个幂等键，重复回传两次。
     */
    public ClinicalTimeWindow buildHeightWeightWindow(LocalDate admissionWardDate, LocalDate reportDate) {
        long pageDayIndex = ChronoUnit.DAYS.between(admissionWardDate, reportDate);
        if (pageDayIndex > 0 && pageDayIndex % HEIGHT_WEIGHT_PAGE_DAYS == 0) {
            LocalDateTime sendTime = LocalDateTime.of(reportDate, LocalTime.of(DAILY_SUMMARY_HOUR, 0, 0));
            return ClinicalTimeWindow.builder()
                    .start(sendTime)
                    .end(sendTime)
                    .type(WindowType.HEIGHT_WEIGHT_PAGE)
                    .reportDate(sendTime)
                    .build();
        }
        return null;
    }

    public LocalDateTime getPreviousVitalPoint(LocalDateTime current) {
        for (int i = VITAL_SIGN_HOURS.size() - 1; i >= 0; i--) {
            LocalDateTime candidate = LocalDateTime.of(current.toLocalDate(),
                    LocalTime.of(VITAL_SIGN_HOURS.get(i), 0, 0));
            if (candidate.isBefore(current)) {
                return candidate;
            }
        }
        return LocalDateTime.of(current.toLocalDate().minusDays(1), LocalTime.of(22, 0, 0));
    }

    public LocalDateTime getNextVitalPoint(LocalDateTime current) {
        for (int h : VITAL_SIGN_HOURS) {
            LocalDateTime candidate = LocalDateTime.of(current.toLocalDate(), LocalTime.of(h, 0, 0));
            if (candidate.isAfter(current)) {
                return candidate;
            }
        }
        return LocalDateTime.of(current.toLocalDate().plusDays(1), LocalTime.of(2, 0, 0));
    }

    /**
     * 获取指定时刻所属的标准时间点
     */
    public LocalDateTime getCurrentVitalPoint(LocalDateTime moment) {
        int currentHour = moment.getHour();
        for (int i = VITAL_SIGN_HOURS.size() - 1; i >= 0; i--) {
            if (currentHour >= VITAL_SIGN_HOURS.get(i)) {
                return LocalDateTime.of(moment.toLocalDate(), LocalTime.of(VITAL_SIGN_HOURS.get(i), 0, 0));
            }
        }
        return LocalDateTime.of(moment.toLocalDate().minusDays(1), LocalTime.of(22, 0, 0));
    }

    public LocalDateTime getCurrentVitalPoint() {
        return getCurrentVitalPoint(now());
    }

    /**
     * 回看窗口内需要扫描的标准时刻列表（倒序，最近的在前）
     *
     * @param now           当前时间
     * @param lookbackHours 回看小时数，覆盖护士事后补录
     */
    public List<LocalDateTime> getScanTimePoints(LocalDateTime now, int lookbackHours) {
        if (now == null || lookbackHours < 0) {
            return Collections.emptyList();
        }
        LocalDateTime earliest = now.minusHours(lookbackHours);
        List<LocalDateTime> points = new ArrayList<>();

        LocalDate cursor = earliest.toLocalDate();
        LocalDate last = now.toLocalDate();
        while (!cursor.isAfter(last)) {
            for (Integer hour : VITAL_SIGN_HOURS) {
                LocalDateTime point = cursor.atTime(hour, 0, 0);
                if (!point.isBefore(earliest) && !point.isAfter(now)) {
                    points.add(point);
                }
            }
            cursor = cursor.plusDays(1);
        }
        points.sort(Collections.reverseOrder());
        return points;
    }

    /**
     * 获取本轮需要处理的汇总报表日列表
     *
     * 规则：报表日 D 的归档时刻是 D 的 07:00，只有 now >= D 07:00 才允许处理；
     *      同时向前回看 lookbackDays 天，覆盖 07:00 之后才补录的出入量/大便次数。
     *
     * @param moment       当前时刻
     * @param lookbackDays 回看天数
     * @return 按日期升序排列的报表日
     */
    public List<LocalDate> getSummaryReportDates(LocalDateTime moment, int lookbackDays) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate today = moment.toLocalDate();
        int days = Math.max(lookbackDays, 0);
        for (int i = days; i >= 0; i--) {
            LocalDate candidate = today.minusDays(i);
            LocalDateTime archiveTime = LocalDateTime.of(candidate, LocalTime.of(DAILY_SUMMARY_HOUR, 0, 0));
            if (!moment.isBefore(archiveTime)) {
                dates.add(candidate);
            }
        }
        return dates;
    }

    public List<LocalDateTime> getAllVitalPoints(LocalDate date) {
        List<LocalDateTime> points = new ArrayList<>();
        for (int hour : VITAL_SIGN_HOURS) {
            points.add(buildVitalPoint(date, hour));
        }
        return points;
    }

    public boolean isVitalPoint(LocalDateTime time) {
        return VITAL_SIGN_HOURS.contains(time.getHour()) && time.getMinute() == 0 && time.getSecond() == 0;
    }

    public List<Integer> getVitalSignHours() {
        return new ArrayList<>(VITAL_SIGN_HOURS);
    }

    public int getDailySummaryHour() {
        return DAILY_SUMMARY_HOUR;
    }

    public boolean shouldSendHeightWeight(LocalDate admissionWardDate, LocalDate reportDate) {
        return buildHeightWeightWindow(admissionWardDate, reportDate) != null;
    }
}
