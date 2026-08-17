package com.digixmed.cloud.icu.util;

import com.digixmed.cloud.icu.model.VitalSignPayload;

import java.time.LocalDateTime;

/**
 * 回传时间锚定工具
 *
 * 业务目的：把 payload 的 planTime 统一锚定到"标准时间点"，把真实记录时间放到 recordTime。
 *
 * 为什么必须这么做：
 *   幂等键 = patientId_series_vitalsignType_planTime。
 *   各 Handler 内部 fillCommonFields 会把 planTime 写成 bedside.time（护士真实填写的时刻），
 *   同一个 06:00 格子如果护士 09:12 补录、09:40 又改一次，就会产生两个不同的幂等键，
 *   变成"各推一次"，而不是"同一条记录比对后决定是否重传"。
 *
 * 锚定后：
 *   planTime   = 标准时间点（02/06/10/14/18/22，血压与汇总为 07:00）→ 幂等键稳定
 *   recordTime = bedside.time（真实记录时刻）→ 报文里仍然保留真实时间
 *
 * 例外 —— 入科首条体征：
 *   入科链路（VitalSignScanTask.processAdmissionVitalSign）**不调用**本工具。
 *   planTime 与 recordTime 均保留 bedside.time（真实入科记录时刻），
 *   业务方要求入科首条不得锚定到标准点。
 *
 * 注意：recordTime 已从 IntermediateService.computeSha256 中剔除，
 *      否则"值没变、只是记录时刻变了"会被误判为内容变化而重复回传。
 */
public final class PayloadTimeNormalizer {

    private PayloadTimeNormalizer() {
    }

    /**
     * 锚定 planTime
     *
     * @param payload         待锚定的载荷，可为 null
     * @param anchorPlanTime  标准时间点，可为 null（为 null 时不做任何处理）
     */
    public static void anchor(VitalSignPayload payload, LocalDateTime anchorPlanTime) {
        if (payload == null || anchorPlanTime == null) {
            return;
        }
        // Handler 内已把 recordTime 写为 bedside.time；虚拟记录（汇总类）为 null
        LocalDateTime realRecordTime = payload.getRecordTime();
        payload.setPlanTime(anchorPlanTime);
        payload.setRecordTime(realRecordTime != null ? realRecordTime : anchorPlanTime);
    }
}
