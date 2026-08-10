package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.util.TraceIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Calendar;
import java.util.Date;

/**
 * 历史日志清理任务
 *
 * 业务目的：清理超过指定天数的历史中间表记录
 * 输入：thermometer_intermediate集合
 * 输出：删除过期记录
 * 调度时间：每日00:00
 * 默认保留天数：180天
 */
@Component
public class LogCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(LogCleanupTask.class);

    private static final String COLLECTION_NAME = "thermometer_intermediate";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Value("${digixmed.deleteDay:180}")
    private int deleteDay;

    /**
     * 执行清理
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void execute() {
        String traceId = TraceIdGenerator.generate();
        log.info("STEP_01_PATIENT_SELECTED traceId={} 开始清理{}天前的历史记录", traceId, deleteDay);

        try {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -deleteDay);
            Date cutoffDate = cal.getTime();

            Query query = new Query(Criteria.where("createdAt").lt(cutoffDate));
            long count = mongoTemplate.count(query, COLLECTION_NAME);

            if (count > 0) {
                mongoTemplate.remove(query, COLLECTION_NAME);
                log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 已清理{}条历史记录", traceId, count);
            } else {
                log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 无需清理", traceId);
            }

        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 清理异常", traceId, e);
        }
    }
}
