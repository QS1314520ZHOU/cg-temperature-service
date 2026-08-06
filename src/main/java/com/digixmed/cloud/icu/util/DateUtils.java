package com.digixmed.cloud.icu.util;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import com.digixmed.cloud.icu.service.common.MyConfig;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;


public class DateUtils {
    public static List<Date> getDatesFormNow(Date start) throws ParseException {
        /*  22 */
        String startTime = DateUtil.format(start, "yyyy-MM-dd");
        /*  23 */
        List<String> betweenDate = getBetweenDate(startTime, getToday());
        /*  24 */
        List<Date> list = new ArrayList<>();
        /*  25 */
        for (String date : betweenDate) {
            /*  26 */
            Date dateTime = getDateTime(date);
            /*  27 */
            list.add(dateTime);
        }
        /*  29 */
        list.add(start);
        /*  30 */
        return list;
    }

    public static Date getDateTime(String time) throws ParseException {
        /*  34 */
        String format = "yyyy-MM-dd";
        /*  35 */
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        /*  36 */
        return sdf.parse(time);
    }

    public static Date getDateTime1(String time) throws ParseException {
        /*  39 */
        String format = "yyyy-MM-dd HH:mm:ss";
        /*  40 */
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        /*  41 */
        return sdf.parse(time);
    }

    public static String getToday() {
        /*  45 */
        String format = "yyyy-MM-dd";
        /*  46 */
        SimpleDateFormat sdf = new SimpleDateFormat(format);
        /*  47 */
        return sdf.format(new Date());
    }

    public static List<String> getBetweenDate(String start, String end) {
        /*  51 */
        List<String> list = new ArrayList<>();
        /*  52 */
        LocalDate startDate = LocalDate.parse(start);
        /*  53 */
        LocalDate endDate = LocalDate.parse(end);
        /*  54 */
        long distance = ChronoUnit.DAYS.between(startDate, endDate);
        /*  55 */
        if (distance < 1L) {
            /*  56 */
            return list;
        }
        /*  58 */
        Stream.<LocalDate>iterate(startDate, d -> d.plusDays(1L)).limit(distance + 1L).forEach(f -> list.add(f.toString()));
        /*  59 */
        return list;
    }


    public static Date getFirstTime(Date time) {
        /*  69 */
        if (time == null) {
            /*  70 */
            return time;
        }
        /*  72 */
        Calendar cal = Calendar.getInstance();
        /*  73 */
        cal.setTime(time);
        /*  74 */
        int hour = cal.get(11);
        /*  75 */
        if (hour >= 3 && hour < 7) {
            /*  76 */
            cal.set(11, 3);
            /*  77 */
        } else if (hour >= 7 && hour < 11) {
            /*  78 */
            cal.set(11, 7);
            /*  79 */
        } else if (hour >= 11 && hour < 15) {
            /*  80 */
            cal.set(11, 11);
            /*  81 */
        } else if (hour >= 15 && hour < 19) {
            /*  82 */
            cal.set(11, 15);
            /*  83 */
        } else if (hour >= 19 && hour < 23) {
            /*  84 */
            cal.set(11, 19);
            /*  85 */
        } else if (hour >= 23 && hour <= 24) {
            /*  86 */
            cal.set(11, 23);
            /*  87 */
        } else if (hour >= 0 && hour < 3) {
            /*  88 */
            cal.set(5, cal.get(5) - 1);
            /*  89 */
            cal.set(11, 23);
        }
        /*  91 */
        cal.set(12, 0);
        /*  92 */
        cal.set(13, 0);
        /*  93 */
        cal.set(14, 0);
        /*  94 */
        return cal.getTime();
    }

    public static Boolean isEqual(Date time, Boolean isIBP) {
        /*  98 */
        if (time == null) {
            /*  99 */
            return Boolean.valueOf(false);
        }
        /* 101 */
        Calendar cal = Calendar.getInstance();
        /* 102 */
        cal.setTime(time);
        /* 103 */
        if (cal.get(12) != 0 || cal.get(13) != 0 || cal.get(14) != 0) {
            /* 104 */
            return Boolean.valueOf(false);
        }
        /* 106 */
        if (isIBP.booleanValue()) {
            /* 107 */
            if (MyConfig.XYTIMEPOINTS.contains(Integer.valueOf(cal.get(11)))) {
                /* 108 */
                return Boolean.valueOf(true);
            }
        }
        /* 111 */
        else if (MyConfig.TIMEPOINTS.contains(Integer.valueOf(cal.get(11)))) {
            /* 112 */
            return Boolean.valueOf(true);
        }


        /* 116 */
        return Boolean.valueOf(false);
    }

    public static Date getTimeByOclock(Date today, Integer oclock) {
        /* 120 */
        Calendar calendar = Calendar.getInstance();
        /* 121 */
        calendar.setTime(today);
        /* 122 */
        calendar.set(11, oclock.intValue());
        /* 123 */
        calendar.set(12, 0);
        /* 124 */
        calendar.set(13, 0);
        /* 125 */
        calendar.set(14, 0);
        /* 126 */
        return calendar.getTime();
    }

    public static Date getTimeSecond(Date today) {
        /* 129 */
        Calendar calendar = Calendar.getInstance();
        /* 130 */
        calendar.setTime(today);
        /* 131 */
        calendar.set(12, 1);
        /* 132 */
        return calendar.getTime();
    }


    public static Date formatTimePoint(Date time, int timepoint) {
        DateTime dateTime=new DateTime();
        /* 140 */
        if (time == null) {
            /* 141 */
            return null;
        }
        /* 143 */
        Date timePoint = time;
        /* 144 */
        Calendar cal = Calendar.getInstance();
        /* 145 */
        cal.setTime(timePoint);
        /* 146 */
        int hour = cal.get(11);
        /* 147 */
        int minute = cal.get(12);
        /* 148 */
        int secono = cal.get(13);


        /* 151 */
        if (hour >= 0 && hour < timepoint) {
            /* 152 */
            dateTime = DateUtil.offsetDay(timePoint, -1);
        }

        /* 155 */
        if (hour == timepoint && minute == 0 && secono == 0) {
            /* 156 */
            dateTime = DateUtil.offsetDay((Date) dateTime, -1);
        }
        /* 158 */
        return getTimeByOclock((Date) dateTime, Integer.valueOf(timepoint));
    }


    public static Boolean formatCKTimePoint(Date time, Date ckTime) {
        /* 167 */
        if (time == null && ckTime == null) {
            /* 168 */
            return null;
        }

        /* 171 */
        Date timePoint = time;
        /* 172 */
        Calendar cal = Calendar.getInstance();
        /* 173 */
        cal.setTime(timePoint);


        /* 176 */
        Date ckTimePoint = ckTime;
        /* 177 */
        Calendar ckCal = Calendar.getInstance();
        /* 178 */
        ckCal.setTime(ckTimePoint);

        /* 180 */
        return Boolean.valueOf(true);
    }

    public static Date getTomorrow(Date today) {
        /* 183 */
        Calendar calendar = Calendar.getInstance();
        /* 184 */
        calendar.setTime(today);

        /* 186 */
        calendar.add(5, 1);
        /* 187 */
        return calendar.getTime();
    }

    public static Date getYesterDay(Date today) {
        /* 191 */
        Date time = getTimeByOclock(today, Integer.valueOf(8));
        /* 192 */
        Calendar calendar = Calendar.getInstance();
        /* 193 */
        calendar.setTime(time);

        /* 195 */
        calendar.add(5, -1);
        /* 196 */
        return calendar.getTime();
    }

    public static Date getYesterDay4now(Date today) {
        /* 200 */
        Calendar calendar = Calendar.getInstance();
        /* 201 */
        calendar.setTime(today);

        /* 203 */
        calendar.add(5, -1);
        /* 204 */
        return calendar.getTime();
    }

    public static Date getYesterDayJian(Date today, Integer backDay) {
        /* 207 */
        Calendar calendar = Calendar.getInstance();
        /* 208 */
        calendar.setTime(today);

        /* 210 */
        calendar.add(5, -backDay.intValue());
        /* 211 */
        return calendar.getTime();
    }

    public static Date getLastHour(Date today) {
        /* 214 */
        Calendar calendar = Calendar.getInstance();
        /* 215 */
        calendar.setTime(today);

        /* 217 */
        calendar.add(11, -1);
        /* 218 */
        return calendar.getTime();
    }


    public static boolean isEffectiveDate(Date time, Date startTime, Date endTime) {
        /* 230 */
        if (time.getTime() == startTime.getTime() || time.getTime() == endTime.getTime()) {
            /* 231 */
            return true;
        }
        /* 233 */
        Calendar date = Calendar.getInstance();
        /* 234 */
        date.setTime(time);
        /* 235 */
        Calendar begin = Calendar.getInstance();
        /* 236 */
        begin.setTime(startTime);
        /* 237 */
        Calendar end = Calendar.getInstance();
        /* 238 */
        end.setTime(endTime);
        /* 239 */
        if (date.after(begin) && date.before(end)) {
            /* 240 */
            return true;
        }
        /* 242 */
        return false;
    }


    public static String parseTime(Date timePoint) {
        /* 247 */
        return DateUtil.format(timePoint, "yyyy-MM-dd HH:mm:ss");
    }


    public static boolean isAm(Date timePoint) {
        /* 257 */
        Calendar calendar = Calendar.getInstance();
        /* 258 */
        calendar.setTime(timePoint);
        /* 259 */
        int hour = calendar.get(11);
        /* 260 */
        return (hour <= 12);
    }
}

