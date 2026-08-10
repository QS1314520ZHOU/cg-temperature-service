package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.service.PushService;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 推送任务
 *
 * 业务目的：扫描PENDING和RETRY状态的记录并推送
 * 输入：thermometer_intermediate集合
 * 输出：推送结果（SUCCESS/RETRY/DEAD）
 * 调度时间：每2分钟执行一次
 */
@Component
public class PushTask {

    private static final Logger log = LoggerFactory.getLogger(PushTask.class);

    private static final String COLLECTION_NAME = "thermometer_intermediate";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private PushService pushService;

    /**
     * 执行推送
     */
    @Scheduled(cron = "0 0/2 * * * ?")
    public void execute() {
        String traceId = TraceIdGenerator.generate();
        log.info("STEP_10_PUSH_STARTED traceId={} 开始推送任务", traceId);

        try {
            // 查询PENDING和RETRY状态的记录
            Query query = new Query(Criteria.where("status").in("PENDING", "RETRY"))
                    .limit(100); // 每次最多处理100条

            List<Map<String, Object>> records = mongoTemplate.find(query, Map.class, COLLECTION_NAME);

            if (records.isEmpty()) {
                log.info("STEP_10_PUSH_STARTED traceId={} 无待推送记录", traceId);
                return;
            }

            log.info("STEP_10_PUSH_STARTED traceId={} 待推送记录数量={}", traceId, records.size());

            int successCount = 0;
            int retryCount = 0;
            int deadCount = 0;
            int skippedCount = 0;

            for (Map<String, Object> record : records) {
                try {
                    VitalSignPayload payload = convertToPayload(record);
                    if (payload == null) {
                        skippedCount++;
                        continue;
                    }

                    PushService.PushResult result = pushService.push(payload, traceId);
                    switch (result) {
                        case SUCCESS:
                            successCount++;
                            break;
                        case RETRY:
                            retryCount++;
                            break;
                        case DEAD:
                            deadCount++;
                            break;
                        case SKIPPED:
                            skippedCount++;
                            break;
                    }
                } catch (Exception e) {
                    log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 推送单条记录异常", traceId, e);
                    deadCount++;
                }
            }

            log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 推送完成: success={} retry={} dead={} skipped={}",
                    traceId, successCount, retryCount, deadCount, skippedCount);

        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 推送任务异常", traceId, e);
        }
    }

    private VitalSignPayload convertToPayload(Map<String, Object> record) {
        try {
            String planTimeStr = record.get("planTime") != null ? record.get("planTime").toString() : null;
            java.time.LocalDateTime planTime = null;
            if (record.get("planTime") instanceof Date) {
                planTime = ((Date) record.get("planTime")).toInstant()
                        .atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime();
            }

            return VitalSignPayload.builder()
                    .vitalsignName((String) record.get("vitalsignName"))
                    .vitalsignType((String) record.get("vitalsignType"))
                    .patientId((String) record.get("patientId"))
                    .mrn((String) record.get("mrn"))
                    .patientName((String) record.get("patientName"))
                    .series((String) record.get("series"))
                    .wardCode((String) record.get("wardCode"))
                    .planTime(planTime)
                    .recordTime(planTime)
                    .recordNurseId("dba")
                    .build();
        } catch (Exception e) {
            log.error("转换payload失败: {}", record, e);
            return null;
        }
    }
}
