package com.digixmed.cloud.icu.util;

import java.util.UUID;

/**
 * 追踪ID生成器
 *
 * 业务目的：为每位患者每次处理生成唯一的traceId，用于日志关联和问题排查
 * 输入：无
 * 输出：唯一标识字符串
 * 异常策略：无
 */
public class TraceIdGenerator {

    private static final String PREFIX = "TS";

    private TraceIdGenerator() {
    }

    /**
     * 生成追踪ID
     * 格式：TS-年月日时分秒-随机4位
     * 例如：TS-20260810143052-A1B2
     *
     * @return 追踪ID
     */
    public static String generate() {
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String random = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        return PREFIX + "-" + timestamp + "-" + random;
    }

    /**
     * 生成带患者标识的追踪ID
     * 格式：TS-患者ID后4位-时间戳-随机4位
     *
     * @param patientId 患者ID
     * @return 追踪ID
     */
    public static String generateWithPatient(String patientId) {
        String patientSuffix = "";
        if (patientId != null && patientId.length() >= 4) {
            patientSuffix = patientId.substring(patientId.length() - 4);
        } else if (patientId != null) {
            patientSuffix = patientId;
        }
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String random = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        return PREFIX + "-" + patientSuffix + "-" + timestamp + "-" + random;
    }
}
