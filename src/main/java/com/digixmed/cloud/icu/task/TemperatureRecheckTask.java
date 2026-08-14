package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.config.VitalSignRecheckProperties;
import com.digixmed.cloud.icu.dao.MongoDao;
import com.digixmed.cloud.icu.pojo.IntermediateTable;
import com.digixmed.cloud.icu.util.DateUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 体温复测巡检任务。
 *
 * 业务规则：
 *   1. 仅针对标准采集节点（2/6/10/14/18/22 整点）的高热记录（vitalsignNVal1 >= 38.5）做复测，
 *      在 1 小时内查找复测数据（bedside.time 属于 (原时间, 原时间+1小时]）；
 *      非节点记录（如 11:00）不查询复测数据，直接结束，vitalsignNVal2 保持为空；
 *   2. 因为复测数据是护士后补的，首次扫描往往查不到，所以每 10 分钟巡检一次，最多 6 次（共 1 小时）；
 *   3. 复测值 >= 38.5：写入 signValue2 并置 isUpload=0，由回传任务重新发送（带 vitalsignNVal2），巡检结束；
 *   4. 6 次均未命中或超出 1 小时窗口：vitalsignNVal2 保持为空，不再发送任何请求。
 *
 * 巡检回望窗口：默认 24 小时（vitalsign.recheck.lookback-hours，可用环境变量 VITALSIGN_RECHECK_LOOKBACK_HOURS 覆盖），
 * 保证当天已录入的历史高热记录也能被补充复测。
 *
 * 诊断日志：每次实际查询复测数据都会打印查询窗口和候选条数，
 * 便于判断是"真没数据"还是"被 valid 等条件过滤掉了"。
 *
 * 异常策略：单条记录异常不影响其他记录，记 WARN 日志。
 */
@Component
public class TemperatureRecheckTask {

    private static final Logger log = LoggerFactory.getLogger(TemperatureRecheckTask.class);

    /** 复测阀值 */
    private static final double RECHECK_THRESHOLD = 38.5D;

    /** 复测窗口：1 小时 */
    private static final long RECHECK_WINDOW_MS = 3600000L;

    /** 最大巡检次数：每 10 分钟 1 次，共 6 次 */
    private static final int MAX_RECHECK_ATTEMPTS = 6;

    /** 1 小时的毫秒数，用于把配置的 lookback 小时换算为毫秒 */
    private static final long HOUR_MS = 3600000L;

    /** 诊断日志时间格式 */
    private static final String LOG_TS_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Autowired
    private MongoDao mongoDao;

    @Autowired
    private VitalSignRecheckProperties recheckProperties;

    @Scheduled(cron = "0 0/10 * * * ?")
    public void scanRecheck() {
        Date now = new Date();
        // 回望窗口可配置：默认 24 小时（vitalsign.recheck.lookback-hours），覆盖当天已录入的历史高热记录
        long scanLookbackMs = ((long) recheckProperties.getLookbackHours()) * HOUR_MS;
        List<IntermediateTable> list;
        try {
            list = this.mongoDao.selectRecheckPendingList(new Date(now.getTime() - scanLookbackMs));
        } catch (Exception e) {
            log.error("STEP_06_RECHECK 查询待复测体温记录失败", e);
            return;
        }
        if (list == null || list.isEmpty()) {
            return;
        }
        int hit = 0;
        int finished = 0;
        int waiting = 0;
        for (IntermediateTable table : list) {
            try {
                String result = process(table, now);
                if ("HIT".equals(result)) {
                    hit++;
                } else if ("WAITING".equals(result)) {
                    waiting++;
                } else {
                    finished++;
                }
            } catch (Exception e) {
                log.warn("STEP_06_RECHECK 体温复测巡检异常: id={} pid={}",
                        new Object[]{table == null ? null : table.getId(), table == null ? null : table.getPid(), e});
            }
        }
        log.info("STEP_06_RECHECK 体温复测巡检完成: 待巡检={} 命中复测={} 结束巡检={} 继续等待={}",
                new Object[]{Integer.valueOf(list.size()), Integer.valueOf(hit),
                        Integer.valueOf(finished), Integer.valueOf(waiting)});
    }

    /**
     * 处理单条待复测记录。
     * @return HIT=命中复测值并触发重发；WAITING=本轮未命中但继续巡检；DONE=结束巡检
     */
    private String process(IntermediateTable table, Date now) {
        String id = table.getId();
        int attempts = (table.getRecheckAttempts() == null) ? 0 : table.getRecheckAttempts().intValue();

        // 非高热记录（含非数字）无需复测
        Double origin = parseValue(table.getSignValue());
        if (origin == null || origin.doubleValue() < RECHECK_THRESHOLD) {
            this.mongoDao.updateRecheckResult(id, null, attempts, true, false);
            return "DONE";
        }

        // 已经拿到合格复测值，无需再巡检
        Double exist = parseValue(table.getSignValue2());
        if (exist != null && exist.doubleValue() >= RECHECK_THRESHOLD) {
            this.mongoDao.updateRecheckResult(id, null, attempts, true, false);
            return "DONE";
        }

        Date base = table.getTimePoint();
        if (base == null) {
            this.mongoDao.updateRecheckResult(id, null, attempts, true, false);
            return "DONE";
        }

        // 只针对标准采集节点（2/6/10/14/18/22 整点）进行复测；
        // 非节点记录（如 11:00）直接结束，不再查 (timePoint, +1小时] 的复测数据，vitalsignNVal2 保持为空
        if (!DateUtils.isEqual(base, Boolean.FALSE)) {
            this.mongoDao.updateRecheckResult(id, null, attempts, true, false);
            log.info("STEP_06_RECHECK 非标准节点跳过复测: pid={} 原始值={} timePoint={}，仅 2/6/10/14/18/22 整点节点复测，vitalsignNVal2 保持为空",
                    new Object[]{table.getPid(), table.getSignValue(),
                            new SimpleDateFormat(LOG_TS_PATTERN).format(base)});
            return "DONE";
        }

        // 查询 (base, base+1小时] 内的 param_T 复测数据
        String recheckValue = null;
        List<Document> docs = this.mongoDao.selectRecheckTemperature(table.getPid(), base, table.getBedSideId());
        int candidateCount = (docs == null) ? 0 : docs.size();
        if (docs != null) {
            for (Document doc : docs) {
                Object strVal = doc.get("strVal");
                String text = (strVal == null) ? null : strVal.toString();
                Double value = parseValue(text);
                if (value != null && value.doubleValue() >= RECHECK_THRESHOLD) {
                    recheckValue = text.trim();
                    break;
                }
            }
        }

        // 诊断日志：打印查询窗口和候选条数，便于判断是真没数据还是被 valid 等条件过滤掉了
        String windowStart = new SimpleDateFormat(LOG_TS_PATTERN).format(base);
        log.info("STEP_06_RECHECK 复测查询: pid={} 窗口=({}, +1小时] 候选条数={} 命中值={}",
                new Object[]{table.getPid(), windowStart, Integer.valueOf(candidateCount),
                        (recheckValue == null) ? "-" : recheckValue});

        int newAttempts = attempts + 1;
        if (recheckValue != null) {
            this.mongoDao.updateRecheckResult(id, recheckValue, newAttempts, true, true);
            log.info("STEP_06_RECHECK 体温复测命中: pid={} 原始值={} 复测值={} 第{}次查询，已标记重新回传 vitalsignNVal2",
                    new Object[]{table.getPid(), table.getSignValue(), recheckValue, Integer.valueOf(newAttempts)});
            return "HIT";
        }

        if (candidateCount == 0) {
            List<Document> rawDocs = this.mongoDao.selectRecheckTemperatureDiagnostic(table.getPid(), base);
            if (rawDocs == null || rawDocs.isEmpty()) {
                log.info("STEP_06_RECHECK 复测诊断: pid={} 窗口=({}, +1小时] bedside 中确实没有 param_T 数据",
                        new Object[]{table.getPid(), windowStart});
            } else {
                for (Document raw : rawDocs) {
                    log.info("STEP_06_RECHECK 复测诊断: pid={} 窗口内存在 param_T 记录 time={} valid={} strVal={}，但未被采用",
                            new Object[]{table.getPid(), raw.get("time"), raw.get("valid"), raw.get("strVal")});
                }
            }
        }

        boolean windowClosed = now.getTime() > base.getTime() + RECHECK_WINDOW_MS;
        if (newAttempts >= MAX_RECHECK_ATTEMPTS || windowClosed) {
            this.mongoDao.updateRecheckResult(id, null, newAttempts, true, false);
            log.info("STEP_06_RECHECK 体温复测结束: pid={} 原始值={} 已查询{}次 窗口已到期={}，vitalsignNVal2 保持为空，不再发送请求",
                    new Object[]{table.getPid(), table.getSignValue(), Integer.valueOf(newAttempts),
                            Boolean.valueOf(windowClosed)});
            return "DONE";
        }

        this.mongoDao.updateRecheckResult(id, null, newAttempts, false, false);
        log.info("STEP_06_RECHECK 体温复测未命中: pid={} 原始值={} 第{}/{}次，10分钟后重试",
                new Object[]{table.getPid(), table.getSignValue(), Integer.valueOf(newAttempts),
                        Integer.valueOf(MAX_RECHECK_ATTEMPTS)});
        return "WAITING";
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
            return Double.valueOf(Double.parseDouble(trimmed));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
