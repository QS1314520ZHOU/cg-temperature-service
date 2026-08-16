package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.service.IntermediateService;
import com.digixmed.cloud.icu.service.PushService;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 推送任务
 *
 * 业务目的：扫描PENDING和RETRY状态的记录并推送
 * 输入：vitalsign_push_queue集合（新推送链路专用，不会领取旧回传链路的记录）
 * 输出：推送结果（SUCCESS/RETRY/DEAD）
 * 调度时间：每2分钟执行一次
 *
 * 关键流程：
 *   1. 使用原子操作领取记录（避免多实例重复发送）
 *   2. 从中间表恢复完整Payload
 *   3. 调用PushService执行推送
 *   4. 根据结果更新状态
 */
@Component
public class PushTask {

    private static final Logger log = LoggerFactory.getLogger(PushTask.class);

    private static final String COLLECTION_NAME = IntermediateService.PUSH_COLLECTION;

    /** 单轮最多处理的记录数，避免积压时 2 分钟只发 1 条 */
    private static final int MAX_BATCH_PER_ROUND = 50;

    /** SENDING 超时阈值：超过该时长仍未完成的记录视为卡死，恢复为 RETRY */
    private static final long SENDING_TIMEOUT_MS = 10 * 60 * 1000L;

    @org.springframework.beans.factory.annotation.Value("${vitalsign.auto-enabled:false}")
    private boolean autoEnabled;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private PushService pushService;

    @Autowired
    private IntermediateService intermediateService;

    /**
     * 执行推送
     */
    @Scheduled(cron = "0 0/2 * * * ?")
    public void execute() {
        if (!autoEnabled) {
            log.debug("VITALSIGN_AUTO_DISABLED 自动推送已关闭");
            return;
        }
        pushOnce(TraceIdGenerator.generate());
    }

    /** 单轮推送，供定时与手动共用 */
    public void pushOnce(String traceId) {
        log.info("STEP_10_PUSH_STARTED traceId={} 开始推送任务", traceId);

        try {
            // 恢复SENDING卡死记录（进程在发送期间重启会导致记录永久停留在SENDING）
            int recovered = intermediateService.recoverStaleSending(SENDING_TIMEOUT_MS);
            if (recovered > 0) {
                log.warn("STEP_10_PUSH_STARTED traceId={} 恢复SENDING超时记录 count={}", traceId, recovered);
            }

            int successCount = 0;
            int retryCount = 0;
            int deadCount = 0;
            int skippedCount = 0;
            int handled = 0;

            // 统一走 IntermediateService.claimNext（含 nextRetryTime 退避过滤与 createdAt 排序），并改为单轮批量处理
            for (int i = 0; i < MAX_BATCH_PER_ROUND; i++) {
                Document record = intermediateService.claimNext();
                if (record == null) {
                    break;
                }
                handled++;
                try {
                    // 内容变化时需要两步推送：先发旧值(isValid=0)作废，再发新值(isValid=1)
                    boolean needInvalidate = Boolean.TRUE.equals(record.get("invalidationNeeded"));
                    if (needInvalidate) {
                        Document invDoc = (Document) record.get("invalidationPayload");
                        if (invDoc != null) {
                            VitalSignPayload invPayload = convertDocToPayload(invDoc);
                            if (invPayload != null) {
                                log.info("INVALIDATION_PUSH traceId={} 先推送旧值 isValid=0 metric={} planTime={}",
                                        traceId, invPayload.getVitalsignType(), invPayload.getPlanTime());
                                pushService.pushInvalidation(invPayload, traceId);
                            }
                        }
                        // 清除 invalidation 标记，防止重试时重复推送作废
                        clearInvalidationFlag(record);
                    }

                    VitalSignPayload payload = convertToPayload(record);
                    if (payload == null) {
                        skippedCount++;
                        updateRecordStatus(record, "DEAD", "PAYLOAD_RESTORE_FAILED", "无法恢复Payload");
                    } else {
                        if (needInvalidate) {
                            payload.setIsValid(1);
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
                    }
                } catch (Exception e) {
                    log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 推送单条记录异常", traceId, e);
                    deadCount++;
                    updateRecordStatus(record, "DEAD", "PUSH_ERROR", e.getMessage());
                }
            }

            if (handled == 0) {
                log.info("STEP_10_PUSH_STARTED traceId={} 无待推送记录", traceId);
                return;
            }

            log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 推送完成: handled={} success={} retry={} dead={} skipped={}",
                    traceId, handled, successCount, retryCount, deadCount, skippedCount);

        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} 推送任务异常", traceId, e);
        }
    }

    /**
     * 从中间表Document恢复完整Payload
     */
    private VitalSignPayload convertToPayload(Document record) {
        try {
            java.time.LocalDateTime planTime = null;
            if (record.get("planTime") instanceof Date) {
                planTime = ((Date) record.get("planTime")).toInstant()
                        .atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime();
            }

            java.time.LocalDateTime recordTime = null;
            if (record.get("recordTime") instanceof Date) {
                recordTime = ((Date) record.get("recordTime")).toInstant()
                        .atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime();
            }

            return VitalSignPayload.builder()
                    .vitalsignName(getStringValue(record, "vitalsignName"))
                    .vitalsignType(getStringValue(record, "vitalsignType"))
                    .vitalsignNVal1(getStringValue(record, "vitalsignNVal1"))
                    .vitalsignNVal2(getStringValue(record, "vitalsignNVal2"))
                    .vitalsignNVal3(getStringValue(record, "vitalsignNVal3"))
                    .vitalsignSVal1(getStringValue(record, "vitalsignSVal1"))
                    .vitalsignSVal2(getStringValue(record, "vitalsignSVal2"))
                    .patientId(getStringValue(record, "patientId"))
                    .mrn(getStringValue(record, "mrn"))
                    .patientName(getStringValue(record, "patientName"))
                    .series(getStringValue(record, "series"))
                    .wardCode(getStringValue(record, "wardCode"))
                    .unit(getStringValue(record, "unit"))
                    .remark(getStringValue(record, "remark"))
                    .recordNurseId(getStringValue(record, "recordNurseId"))
                    .recordNurseName(getStringValue(record, "recordNurseName"))
                    .mongoPid(getStringValue(record, "mongoPid"))
                    .planTime(planTime)
                    .recordTime(recordTime != null ? recordTime : planTime)
                    .isValid(record.get("isValid") instanceof Number ? ((Number) record.get("isValid")).intValue() : 1)
                    .build();
        } catch (Exception e) {
            log.error("转换payload失败: {}", record, e);
            return null;
        }
    }

    /**
     * 安全获取字符串值
     */
    private String getStringValue(Document record, String key) {
        Object value = record.get(key);
        if (value == null) return null;
        return value.toString();
    }

    /**
     * 从 invalidationPayload 子文档构建 Payload（旧值，用于作废推送）
     */
    private VitalSignPayload convertDocToPayload(Document doc) {
        try {
            java.time.LocalDateTime planTime = null;
            if (doc.get("planTime") instanceof Date) {
                planTime = ((Date) doc.get("planTime")).toInstant()
                        .atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime();
            }
            java.time.LocalDateTime recordTime = null;
            if (doc.get("recordTime") instanceof Date) {
                recordTime = ((Date) doc.get("recordTime")).toInstant()
                        .atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime();
            }
            return VitalSignPayload.builder()
                    .vitalsignName(getStringValue(doc, "vitalsignName"))
                    .vitalsignType(getStringValue(doc, "vitalsignType"))
                    .vitalsignNVal1(getStringValue(doc, "vitalsignNVal1"))
                    .vitalsignNVal2(getStringValue(doc, "vitalsignNVal2"))
                    .vitalsignNVal3(getStringValue(doc, "vitalsignNVal3"))
                    .vitalsignSVal1(getStringValue(doc, "vitalsignSVal1"))
                    .vitalsignSVal2(getStringValue(doc, "vitalsignSVal2"))
                    .patientId(getStringValue(doc, "patientId"))
                    .mrn(getStringValue(doc, "mrn"))
                    .patientName(getStringValue(doc, "patientName"))
                    .series(getStringValue(doc, "series"))
                    .wardCode(getStringValue(doc, "wardCode"))
                    .unit(getStringValue(doc, "unit"))
                    .remark(getStringValue(doc, "remark"))
                    .recordNurseId(getStringValue(doc, "recordNurseId"))
                    .recordNurseName(getStringValue(doc, "recordNurseName"))
                    .mongoPid(getStringValue(doc, "mongoPid"))
                    .planTime(planTime)
                    .recordTime(recordTime != null ? recordTime : planTime)
                    .isValid(0)
                    .build();
        } catch (Exception e) {
            log.error("转换invalidationPayload失败: {}", doc, e);
            return null;
        }
    }

    /**
     * 清除 invalidation 标记，防止后续重试时重复推送作废
     */
    private void clearInvalidationFlag(Document record) {
        String idempotencyKey = (String) record.get("idempotencyKey");
        if (idempotencyKey == null) return;
        Update update = new Update()
                .set("invalidationNeeded", false)
                .unset("invalidationPayload")
                .set("updatedAt", new Date());
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("idempotencyKey").is(idempotencyKey)),
                update, COLLECTION_NAME);
    }

    /**
     * 更新记录状态
     */
    private void updateRecordStatus(Document record, String status, String errorCode, String errorMsg) {
        String idempotencyKey = (String) record.get("idempotencyKey");
        if (idempotencyKey == null) return;

        Update update = new Update();
        update.set("status", status);
        update.set("lastErrorCode", errorCode);
        update.set("lastErrorMessage", errorMsg);
        update.set("updatedAt", new Date());

        mongoTemplate.updateFirst(
                Query.query(Criteria.where("idempotencyKey").is(idempotencyKey)),
                update, COLLECTION_NAME);
    }
}
