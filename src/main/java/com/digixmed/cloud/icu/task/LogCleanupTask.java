package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.service.IntermediateService;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import org.bson.Document;
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
import java.util.List;

/**
 * 历史日志清理任务
 *
 * 业务目的：清理超过指定天数的历史中间表记录
 * 输入：vitalsign_push_queue集合（新推送链路专用）
 * 输出：删除过期记录
 * 调度时间：每日00:00
 * 默认保留天数：180天
 *
 * 分批删除策略：每批1000条，避免一次性删除大量数据导致锁表
 */
@Component
public class LogCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(LogCleanupTask.class);

    private static final String COLLECTION_NAME = IntermediateService.PUSH_COLLECTION;

    /** 每批删除的最大条数 */
    private static final int BATCH_SIZE = 1000;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Value("${digixmed.deleteDay:180}")
    private int deleteDay;

    /**
     * 执行清理（分批删除）
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void execute() {
        String traceId = TraceIdGenerator.generate();
        log.info("STEP_01_PATIENT_SELECTED traceId={} 开始清理{}天前的历史记录", traceId, deleteDay);

        try {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -deleteDay);
            Date cutoffDate = cal.getTime();

            long totalCount = 0;
            int batchCount;

            do {
                Query query = new Query(Criteria.where("createdAt").lt(cutoffDate))
                        .limit(BATCH_SIZE);
                List<Document> batch = mongoTemplate.find(query, Document.class, COLLECTION_NAME);

                if (batch.isEmpty()) {
                    break;
                }

                // 用 find 出的 _id 做精确删除，避免重复扫描
                List<Object> ids = new java.util.ArrayList<>();
                for (Document doc : batch) {
                    Object id = doc.get("_id");
                    if (id != null) {
                        ids.add(id);
                    }
                }

                if (!ids.isEmpty()) {
                    Query deleteQuery = new Query(Criteria.where("_id").in(ids));
                    mongoTemplate.remove(deleteQuery, COLLECTION_NAME);
                    batchCount = ids.size();
                    totalCount += batchCount;
                } else {
                    break;
                }
            } while (batchCount == BATCH_SIZE);

            if (totalCount > 0) {
                log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 已分批清理{}条历史记录", traceId, totalCount);
            } else {
                log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 无需清理", traceId);
            }

        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 清理异常", traceId, e);
        }
    }
}
