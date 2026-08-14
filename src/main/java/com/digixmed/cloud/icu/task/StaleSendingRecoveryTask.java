package com.digixmed.cloud.icu.task;

import com.digixmed.cloud.icu.service.IntermediateService;
import com.digixmed.cloud.icu.util.TraceIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SENDING超时恢复任务
 *
 * 业务目的：恢复卡死在SENDING状态的记录
 * 输入：vitalsign_push_queue集合中status=SENDING且超时的记录
 * 输出：恢复为RETRY状态，nextRetryTime=now
 * 调度时间：每5分钟执行一次
 *
 * 规则：
 *   - status=SENDING
 *   - claimedAt < now - sendingTimeout
 *   - 恢复为RETRY，nextRetryTime=now
 *   - 多实例安全（原子操作）
 *   - SUCCESS和DEAD不恢复
 */
@Component
public class StaleSendingRecoveryTask {

    private static final Logger log = LoggerFactory.getLogger(StaleSendingRecoveryTask.class);

    @Autowired
    private IntermediateService intermediateService;

    @Value("${vitalsign.push.sending-timeout-ms:300000}")
    private long sendingTimeoutMs;

    @Scheduled(cron = "0 */5 * * * ?")
    public void execute() {
        String traceId = TraceIdGenerator.generate();
        log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 开始SENDING超时恢复", traceId);

        try {
            int recovered = intermediateService.recoverStaleSending(sendingTimeoutMs);
            if (recovered > 0) {
                log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 恢复了{}条SENDING记录", traceId, recovered);
            } else {
                log.info("STEP_12_PUSH_STATUS_UPDATED traceId={} 无需恢复", traceId);
            }
        } catch (Exception e) {
            log.error("STEP_12_PUSH_STATUS_UPDATED traceId={} SENDING恢复异常", traceId, e);
        }
    }
}
