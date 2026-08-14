package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.config.VitalSignRecheckProperties;
import com.digixmed.cloud.icu.service.IntermediateService;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 体温复测巡检任务（新链路）
 *
 * 业务规则：
 *   1. 仅针对标准采集节点（2/6/10/14/18/22 整点）的高热记录（vitalsignNVal1 >= 38.5）做复测，
 *      在 1 小时内查找复测数据（bedside.time 属于 (原时间, 原时间+1小时]）；
 *   2. 每 10 分钟巡检一次，最多 6 次（共 1 小时）；
 *   3. 复测值 >= 38.5：更新 vitalsignNVal2，标记重新发送；
 *   4. 6 次均未命中或超出 1 小时窗口：vitalsignNVal2 保持为空，不再发送。
 *
 * 操作集合：vitalsign_push_queue（新队列）
 */
@Component
public class TemperatureRecheckTask {

    private static final Logger log = LoggerFactory.getLogger(TemperatureRecheckTask.class);

    private static final String QUEUE_COLLECTION = "vitalsign_push_queue";
    private static final String BEDSIDE_COLLECTION = "bedside";

    /** 复测阈值 */
    private static final double RECHECK_THRESHOLD = 38.5D;

    /** 复测窗口：1 小时 */
    private static final long RECHECK_WINDOW_MS = 3600000L;

    /** 最大巡检次数：每 10 分钟 1 次，共 6 次 */
    private static final int MAX_RECHECK_ATTEMPTS = 6;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private IntermediateService intermediateService;

    @Autowired
    private VitalSignRecheckProperties recheckProperties;

    @Scheduled(cron = "${vitalsign.recheck.cron:0 0/10 * * * ?}")
    public void scanRecheck() {
        Date now = new Date();
        long scanLookbackMs = ((long) recheckProperties.getLookbackHours()) * 3600000L;
        Date lookbackTime = new Date(now.getTime() - scanLookbackMs);

        // 查询需要复测的记录：recheckRequired=true, recheckCompleted=false, 体温类型1001
        Query query = new Query(Criteria.where("recheckRequired").is(true)
                .and("recheckCompleted").is(false)
                .and("vitalsignType").is("1001")
                .and("createdAt").gte(lookbackTime));

        List<Document> records = mongoTemplate.find(query, Document.class, QUEUE_COLLECTION);

        if (records == null || records.isEmpty()) {
            return;
        }

        int hit = 0;
        int finished = 0;
        int waiting = 0;

        for (Document record : records) {
            try {
                String result = processRecord(record, now);
                if ("HIT".equals(result)) {
                    hit++;
                } else if ("WAITING".equals(result)) {
                    waiting++;
                } else {
                    finished++;
                }
            } catch (Exception e) {
                log.warn("STEP_06_RECHECK 体温复测巡检异常: id={}",
                        record.get("_id"), e);
            }
        }

        log.info("STEP_06_RECHECK 体温复测巡检完成: 待巡检={} 命中复测={} 结束巡检={} 继续等待={}",
                records.size(), hit, finished, waiting);
    }

    /**
     * 处理单条待复测记录
     * @return HIT=命中复测值并触发重发；WAITING=本轮未命中但继续巡检；DONE=结束巡检
     */
    private String processRecord(Document record, Date now) {
        String id = record.get("_id").toString();
        String pid = record.getString("mongoPid");
        int attempts = record.get("recheckAttempts") instanceof Number
                ? ((Number) record.get("recheckAttempts")).intValue() : 0;

        // 获取原始体温值
        Double origin = parseValue(record.getString("vitalsignNVal1"));
        if (origin == null || origin < RECHECK_THRESHOLD) {
            markDone(id, attempts, false);
            return "DONE";
        }

        // 已经拿到合格复测值，无需再巡检
        Double exist = parseValue(record.getString("vitalsignNVal2"));
        if (exist != null && exist >= RECHECK_THRESHOLD) {
            markDone(id, attempts, true);
            return "DONE";
        }

        // 获取原始记录时间
        Date originalTime = null;
        Object planTimeObj = record.get("planTime");
        if (planTimeObj instanceof Date) {
            originalTime = (Date) planTimeObj;
        }

        if (originalTime == null) {
            markDone(id, attempts, false);
            return "DONE";
        }

        // 查询 (originalTime, originalTime+1小时] 内的 param_T 复测数据
        Date recheckWindowEnd = new Date(originalTime.getTime() + RECHECK_WINDOW_MS);

        Query recheckQuery = new Query(Criteria.where("pid").is(pid)
                .and("code").is("param_T")
                .and("time").gt(originalTime).lte(recheckWindowEnd)
                .and("valid").is(true));

        List<Document> recheckDocs = mongoTemplate.find(recheckQuery, Document.class, BEDSIDE_COLLECTION);
        int candidateCount = recheckDocs == null ? 0 : recheckDocs.size();

        String recheckValue = null;
        if (recheckDocs != null) {
            for (Document doc : recheckDocs) {
                Object strVal = doc.get("strVal");
                String text = strVal == null ? null : strVal.toString();
                Double value = parseValue(text);
                if (value != null && value >= RECHECK_THRESHOLD) {
                    recheckValue = text.trim();
                    break;
                }
            }
        }

        log.info("STEP_06_RECHECK 复测查询: pid={} 窗口=({}, {}] 候选条数={} 命中值={}",
                pid, originalTime, recheckWindowEnd, candidateCount,
                recheckValue == null ? "-" : recheckValue);

        int newAttempts = attempts + 1;
        if (recheckValue != null) {
            // 命中复测值，更新队列记录
            intermediateService.updateRecheckValue(id, recheckValue, newAttempts, true, true);
            log.info("STEP_06_RECHECK 体温复测命中: pid={} 原始值={} 复测值={} 第{}次查询，已标记重新回传",
                    pid, record.getString("vitalsignNVal1"), recheckValue, newAttempts);
            return "HIT";
        }

        boolean windowClosed = now.getTime() > recheckWindowEnd.getTime();
        if (newAttempts >= MAX_RECHECK_ATTEMPTS || windowClosed) {
            markDone(id, newAttempts, false);
            log.info("STEP_06_RECHECK 体温复测结束: pid={} 原始值={} 已查询{}次 窗口已到期={}，vitalsignNVal2 保持为空",
                    pid, record.getString("vitalsignNVal1"), newAttempts, windowClosed);
            return "DONE";
        }

        // 继续等待
        intermediateService.updateRecheckProgress(id, newAttempts);
        log.info("STEP_06_RECHECK 体温复测未命中: pid={} 原始值={} 第{}/{}次，10分钟后重试",
                pid, record.getString("vitalsignNVal1"), newAttempts, MAX_RECHECK_ATTEMPTS);
        return "WAITING";
    }

    private void markDone(String id, int attempts, boolean hasRecheckValue) {
        intermediateService.updateRecheckResult(id, attempts, true, hasRecheckValue);
    }

    private Double parseValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
