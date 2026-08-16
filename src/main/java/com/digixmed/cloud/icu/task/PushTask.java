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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 推送任务
 *
 * 状态：FAILED → CLAIMED → SUCCESS / FAILED
 * 调度：每2分钟执行一次
 */
@Component
public class PushTask {

    private static final Logger log = LoggerFactory.getLogger(PushTask.class);
    private static final int MAX_BATCH_PER_ROUND = 50;
    private static final long CLAIMED_TIMEOUT_MS = 10 * 60 * 1000L;

    @org.springframework.beans.factory.annotation.Value("${vitalsign.auto-enabled:false}")
    private boolean autoEnabled;

    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private PushService pushService;
    @Autowired private IntermediateService intermediateService;

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
        log.info("PUSH_STARTED traceId={} 开始推送任务", traceId);
        try {
            // 恢复卡死的 CLAIMED 记录
            int recovered = intermediateService.recoverStaleClaimed(CLAIMED_TIMEOUT_MS);
            if (recovered > 0) {
                log.warn("PUSH_STARTED traceId={} 恢复CLAIMED超时记录 count={}", traceId, recovered);
            }

            int successCount = 0;
            int failedCount = 0;
            int skippedCount = 0;
            int handled = 0;

            for (int i = 0; i < MAX_BATCH_PER_ROUND; i++) {
                Document record = intermediateService.claimNext();
                if (record == null) break;
                handled++;

                String key = record.getString("idempotencyKey");
                try {
                    // 内容变化时两步推送：先 isValid=0 作旧值，再 isValid=1 推新值
                    boolean needInvalidate = Boolean.TRUE.equals(record.get("invalidationNeeded"));
                    if (needInvalidate) {
                        Document invDoc = (Document) record.get("invalidationPayload");
                        if (invDoc != null) {
                            VitalSignPayload invPayload = convertDocToPayload(invDoc);
                            if (invPayload != null) {
                                log.info("INVALIDATION_PUSH traceId={} key={} isValid=0 先推旧值",
                                        traceId, key);
                                pushService.pushInvalidation(invPayload, traceId);
                            }
                        }
                        intermediateService.clearInvalidation(key);
                    }

                    VitalSignPayload payload = convertToPayload(record);
                    if (payload == null) {
                        failedCount++;
                        intermediateService.markFailed(key, "PAYLOAD_RESTORE_FAILED", "无法恢复Payload");
                        continue;
                    }

                    if (needInvalidate) payload.setIsValid(1);

                    PushService.PushResult result = pushService.push(payload, traceId);
                    switch (result) {
                        case SUCCESS:
                            successCount++;
                            break;
                        case SKIPPED:
                            skippedCount++;
                            break;
                        default: // RETRY / DEAD
                            failedCount++;
                            break;
                    }
                } catch (Exception e) {
                    log.error("PUSH_ERROR traceId={} key={} 异常", traceId, key, e);
                    failedCount++;
                    intermediateService.markFailed(key, "PUSH_EXCEPTION", e.getMessage());
                }
            }

            log.info("PUSH_DONE traceId={} handled={} success={} failed={} skipped={}",
                    traceId, handled, successCount, failedCount, skippedCount);
        } catch (Exception e) {
            log.error("PUSH_ERROR traceId={} 推送任务异常", traceId, e);
        }
    }

    // ==================== Payload 转换 ====================

    private VitalSignPayload convertToPayload(Document record) {
        return buildPayload(record);
    }

    private VitalSignPayload convertDocToPayload(Document doc) {
        return buildPayload(doc);
    }

    private VitalSignPayload buildPayload(Document doc) {
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
                    .vitalsignName(str(doc, "vitalsignName"))
                    .vitalsignType(str(doc, "vitalsignType"))
                    .vitalsignNVal1(str(doc, "vitalsignNVal1"))
                    .vitalsignNVal2(str(doc, "vitalsignNVal2"))
                    .vitalsignNVal3(str(doc, "vitalsignNVal3"))
                    .vitalsignSVal1(str(doc, "vitalsignSVal1"))
                    .vitalsignSVal2(str(doc, "vitalsignSVal2"))
                    .patientId(str(doc, "patientId"))
                    .mrn(str(doc, "mrn"))
                    .patientName(str(doc, "patientName"))
                    .series(str(doc, "series"))
                    .wardCode(str(doc, "wardCode"))
                    .unit(str(doc, "unit"))
                    .remark(str(doc, "remark"))
                    .recordNurseId(str(doc, "recordNurseId"))
                    .recordNurseName(str(doc, "recordNurseName"))
                    .mongoPid(str(doc, "mongoPid"))
                    .planTime(planTime)
                    .recordTime(recordTime != null ? recordTime : planTime)
                    .isValid(doc.get("isValid") instanceof Number ? ((Number) doc.get("isValid")).intValue() : 1)
                    .build();
        } catch (Exception e) {
            log.error("PAYLOAD_CONVERT_ERROR doc={}", doc, e);
            return null;
        }
    }

    private String str(Document doc, String key) {
        Object v = doc.get(key);
        return v == null ? null : v.toString();
    }
}
