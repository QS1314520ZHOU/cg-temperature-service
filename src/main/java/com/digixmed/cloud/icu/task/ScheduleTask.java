package com.digixmed.cloud.icu.task;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.TimeInterval;
import com.digixmed.cloud.icu.service.common.DataService;
import com.digixmed.cloud.icu.service.common.MyConfig;
import com.digixmed.cloud.icu.service.common.ReturnService;
import com.digixmed.cloud.icu.util.DateUtils;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@RefreshScope
public class ScheduleTask {
    /*  23 */   private static final Logger log = LoggerFactory.getLogger(ScheduleTask.class);

    @Value("${digixmed.debug}")
    public boolean debug;

    @Value("${digixmed.backDay}")
    public Integer backDay;

    @Value("${digixmed.timePointasd}")
    public Integer timePointasd;

    @Autowired
    private DataService dataService;

    @Autowired
    private ReturnService returnService;

    @Value("${digixmed.deleteDay}")
    public Integer deleteDay;

    @Scheduled(cron = "${digixmed.rcron}")
    public void returnInfo() {
        /*  45 */
        log.info("开始回传记录！");
        /*  46 */
        this.returnService.uploadInfo();
    }

    @Scheduled(cron = "${digixmed.cron}")
    public void scheduleSelectFirstVitalSign() {
        /*  51 */
        log.info("开始统计一般记录！");
        /*  52 */
        this.dataService.selectPatientsInDepts();
    }

    @Scheduled(cron = "${digixmed.cron}")
    public void schedule4In() {
        /*  57 */
        Date now = new Date();
        /*  58 */
        TimeInterval timer = new TimeInterval();
        try {
            /*  60 */
            log.info(DateUtil.format(now, "yyyy-MM-dd HH:mm:ss") + "--开始执行入量的统计");
            /*  61 */
            this.dataService.selectCRLiangAfterLastTime_new(now, Boolean.valueOf(true), this.backDay, this.timePointasd);
            /*  62 */
        } catch (Exception e) {
            /*  63 */
            String s = DateUtil.format(now, "yyyy-MM-dd HH:mm:ss");
            /*  64 */
            log.error(s + "入量的统计的定时任务报错:" + s);
        } finally {
            /*  66 */
            log.info("{} --结束执行入量的统计，耗时：{}秒", DateUtil.format(now, "yyyy-MM-dd HH:mm:ss"), Long.valueOf(timer.intervalSecond()));
        }
    }

    @Scheduled(cron = "${digixmed.cron}")
    public void schedule4Out() {
        /*  72 */
        Date now = new Date();
        /*  73 */
        TimeInterval timer = new TimeInterval();
        try {
            /*  75 */
            log.info(DateUtil.format(now, "yyyy-MM-dd HH:mm:ss") + "--开始执行出量的统计");
            /*  76 */
            this.dataService.selectCRLiangAfterLastTime_new(now, Boolean.valueOf(false), this.backDay, this.timePointasd);
            /*  77 */
            this.dataService.selectChuLiangOtherAfterLastTime_new(now, this.backDay, this.timePointasd);
            /*  78 */
        } catch (Exception e) {
            /*  79 */
            String s = DateUtil.format(now, "yyyy-MM-dd HH:mm:ss");
            /*  80 */
            log.error(s + "出量的统计的定时任务报错:" + s);
        } finally {
            /*  82 */
            log.info("{} --结束执行出量的统计，耗时：{}秒", DateUtil.format(now, "yyyy-MM-dd HH:mm:ss"), Long.valueOf(timer.intervalSecond()));
            /*  83 */
            MyConfig.LASTEXECUTIONTIME_ChuLiang = now;
        }
    }

    @Scheduled(cron = "${digixmed.cron}")
    public void schedule4DX() {
        /*  89 */
        Date now = new Date();
        /*  90 */
        TimeInterval timer = new TimeInterval();
        try {
            /*  92 */
            log.info(DateUtil.format(now, "yyyy-MM-dd HH:mm:ss") + "--开始执行大小便的统计");
            /*  93 */
            this.dataService.selectDXLiangAfterLastTime(now, this.backDay, this.timePointasd);
            /*  94 */
        } catch (Exception e) {
            /*  95 */
            String s = DateUtil.format(now, "yyyy-MM-dd HH:mm:ss");
            /*  96 */
            log.error(s + "大小便的统计的定时任务报错:" + s);
        } finally {
            /*  98 */
            log.info("{} --结束大小便的统计，耗时：{}秒", DateUtil.format(now, "yyyy-MM-dd HH:mm:ss"), Long.valueOf(timer.intervalSecond()));
            /*  99 */
            MyConfig.LASTEXECUTIONTIME_DX = now;
        }
    }

    @Scheduled(cron = "${digixmed.cron}")
    public void scheduleVital() {
        /* 105 */
        Date now = new Date();
        /* 106 */
        Date lastTime = (MyConfig.LASTEXECUTIONTIME_VITAL != null) ? MyConfig.LASTEXECUTIONTIME_VITAL : DateUtils.getLastHour(now);
        /* 107 */
        TimeInterval timer = new TimeInterval();
        try {
            /* 109 */
            log.info(DateUtil.format(lastTime, "yyyy-MM-dd HH:mm:ss") + "--开始执行一般体征的记录");
            /* 110 */
            this.dataService.selectVitalSignsAfterLastTime(lastTime);
            /* 111 */
        } catch (Exception e) {
            /* 112 */
            String s = DateUtil.format(lastTime, "yyyy-MM-dd HH:mm:ss");
            /* 113 */
            log.error(s + "一般体征的记录的一般体征定时任务报错:" + s);
        } finally {
            /* 115 */
            log.info("{} --结束一般体征的统计，耗时：{}秒", DateUtil.format(now, "yyyy-MM-dd HH:mm:ss"), Long.valueOf(timer.intervalSecond()));
            /* 116 */
            MyConfig.LASTEXECUTIONTIME_VITAL = now;
        }
    }

    @Scheduled(cron = "0 0 0 * * ?")
    public void deleteMessageLog() {
        /* 122 */
        log.info("删除{}天前的回传日志.......", this.deleteDay);
        /* 123 */
        this.dataService.deleteMessage(this.deleteDay.intValue());
    }
}
