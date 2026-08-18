package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.model.VitalSignPayload;
import com.digixmed.cloud.icu.service.IntermediateService;
import com.digixmed.cloud.icu.service.PushService;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 推送任务（二态机）
 *
 * 状态：FAILED = 待推送（含推送失败）；SUCCESS = 已推送且内容未变
 *
 * 规则：
 *   1. 只捞 FAILED，不比对，直接推
 *   2. lastSuccessHash 存在且与 payloadHash 不一致 -> 先推 isValid=0 作废旧值，
 *      等响应成功后再推 isValid=1 新值；作废失败则整条留到下一轮
 *   3. 推送成功 -> 记录 lastSuccessHash / lastSuccessPayload，置 SUCCESS
 */
@Component
public class PushTask {

    private static final Logger log = LoggerFactory.getLogger(PushTask.class);

    /** 单线程调度下不要设太大：批量 * 读超时 = 最坏阻塞时长 */
    private static final int MAX_BATCH_PER_ROUND = 10;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 参与 payloadHash 的业务字段，快照时原样拷贝 */
    private static final List<String> SNAPSHOT_FIELDS = Arrays.asList(
            "vitalsignName", "vitalsignType", "unit",
            "vitalsignNVal1", "vitalsignNVal2", "vitalsignNVal3",
            "vitalsignSVal1", "vitalsignSVal2",
            "patientId", "mrn", "patientName", "series", "wardCode",
            "remark", "recordNurseId", "recordNurseName", "mongoPid",
            "planTime", "recordTime", "isCustomType");

    @Value("${vitalsign.auto-enabled:false}")
    private boolean autoEnabled;

    @Autowired
    private PushService pushService;

    @Autowired
    private IntermediateService intermediateService;

    @Scheduled(cron = "${vitalsign.push.cron:0 */10 * * * ?}")
    public void execute() {
        if (!autoEnabled) {
            log.warn("PUSH_SKIPPED autoEnabled=false, 自动推送已关闭, 跳过本轮");
            return;
        }
        log.info("PUSH_TRIGGERED cron触发开始推送");
        pushOnce(TraceIdGenerator.generate());
    }

    /** 单轮推送，供定时与手动共用 */
    public void pushOnce(String traceId) {
        log.info("PUSH_STARTED traceId={} 开始推送任务", traceId);

        int handled = 0, success = 0, failed = 0, skipped = 0;

        try {
            List<Document> pending = intermediateService.fetchPending(MAX_BATCH_PER_ROUND);
            log.info("PUSH_FETCHED traceId={} 待推送条数={}", traceId, pending.size());

            for (Document record : pending) {
                String key = record.getString("idempotencyKey");
                handled++;
                try {
                    switch (pushOne(record, key, traceId)) {
                        case SUCCESS: success++; break;
                        case SKIPPED: skipped++; break;
                        default:      failed++;  break;
                    }
                } catch (Exception e) {
                    log.error("PUSH_ERROR traceId={} key={} 异常", traceId, key, e);
                    intermediateService.markFailed(key, "PUSH_EXCEPTION", brief(e.getMessage()));
                    failed++;
                }
            }
        } catch (Exception e) {
            log.error("PUSH_ERROR traceId={} 推送任务异常", traceId, e);
        }

        log.info("PUSH_DONE traceId={} handled={} success={} failed={} skipped={}",
                traceId, handled, success, failed, skipped);
    }

    /** 单条推送，返回最终结果 */
    private PushService.PushResult pushOne(Document record, String key, String traceId) {
        String hash = record.getString("payloadHash");
        String lastSuccessHash = record.getString("lastSuccessHash");

        // ===== 第一步：内容变化时先作废 HIS 手里的旧值 =====
        if (lastSuccessHash != null && !lastSuccessHash.equals(hash)) {
            Document old = (Document) record.get("lastSuccessPayload");
            VitalSignPayload inv = buildPayload(old);
            if (inv != null) {
                inv.setIsValid(0);
                log.info("INVALIDATION_PUSH traceId={} key={} isValid=0 先作废旧值", traceId, key);

                PushService.PushResult r = pushService.pushInvalidation(inv, traceId);
                if (r != PushService.PushResult.SUCCESS) {
                    // 作废没成功就绝不推新值，否则 HIS 会同时持有两条有效记录
                    log.warn("INVALIDATION_FAILED traceId={} key={} result={} 本轮跳过新值",
                            traceId, key, r);
                    intermediateService.markFailed(key, "INVALIDATION_FAILED", "旧值作废未成功:" + r);
                    return PushService.PushResult.FAILED;
                }
            } else {
                log.warn("INVALIDATION_SKIP traceId={} key={} 旧值快照缺失，直接推新值", traceId, key);
            }
        }

        // ===== 第二步：推新值 =====
        VitalSignPayload payload = buildPayload(record);
        if (payload == null) {
            intermediateService.markFailed(key, "PAYLOAD_RESTORE_FAILED", "无法恢复Payload");
            return PushService.PushResult.FAILED;
        }
        payload.setIsValid(1);

        PushService.PushResult result = pushService.push(payload, traceId);

        if (result == PushService.PushResult.SUCCESS) {
            intermediateService.markSuccess(key, hash, snapshotOf(record));
            return result;
        }

        // 身份缺失单独打码，便于 group by lastErrorCode 区分脏数据与链路故障
        if (result == PushService.PushResult.SKIPPED) {
            intermediateService.markFailed(key, "PATIENT_IDENTITY_MISSING",
                    "patient文档缺失或mrn为空，mongoPid=" + record.getString("mongoPid"));
            return result;
        }

        intermediateService.markFailed(key, "PUSH_FAILED_" + result, "详见 responseMsg");
        return result;
    }

    // ==================== 工具方法 ====================

    /** 抽取参与 hash 的业务字段，作为"HIS 当前持有值"的快照 */
    private Document snapshotOf(Document record) {
        Document snap = new Document();
        for (String f : SNAPSHOT_FIELDS) {
            snap.put(f, record.get(f));
        }
        snap.put("isValid", 1);
        return snap;
    }

    private VitalSignPayload buildPayload(Document doc) {
        if (doc == null) {
            return null;
        }
        try {
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
                    .planTime(toLocal(doc.get("planTime")))
                    .recordTime(toLocal(doc.get("recordTime")) != null
                            ? toLocal(doc.get("recordTime"))
                            : toLocal(doc.get("planTime")))
                    .isValid(doc.get("isValid") instanceof Number
                            ? ((Number) doc.get("isValid")).intValue() : 1)
                    .isCustomType(doc.get("isCustomType") instanceof Number
                            ? ((Number) doc.get("isCustomType")).intValue() : null)
                    .build();
        } catch (Exception e) {
            log.error("PAYLOAD_CONVERT_ERROR key={}", doc.getString("idempotencyKey"), e);
            return null;
        }
    }

    private java.time.LocalDateTime toLocal(Object v) {
        return v instanceof Date
                ? ((Date) v).toInstant().atZone(ZONE).toLocalDateTime()
                : null;
    }

    private String str(Document doc, String key) {
        Object v = doc.get(key);
        return v == null ? null : v.toString();
    }

    private String brief(String msg) {
        if (msg == null) {
            return "null";
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
