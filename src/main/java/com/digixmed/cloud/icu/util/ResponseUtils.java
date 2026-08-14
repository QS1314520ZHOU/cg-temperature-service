package com.digixmed.cloud.icu.util;

/**
 * 接口响应解析工具。
 *
 * <p>历史实现使用 {@code responseMsg.contains("成功")} 判定业务结果，
 * 但对方返回的失败报文形如 {@code <msg>不成功：xxx</msg>} / {@code <msg>未成功</msg>}，
 * 同样包含“成功”二字，会把失败误判为成功并把记录置为 SUCCESS，造成数据永久丢失。
 * 统一在此处做严格判定，回传链路与推送链路共用同一规则。
 */
public final class ResponseUtils {

    private ResponseUtils() {
    }

    /**
     * 取出响应报文中 &lt;msg&gt; 节点的文本，取不到返回 null。
     */
    public static String extractMsgNode(String responseMsg) {
        if (responseMsg == null) {
            return null;
        }
        int start = responseMsg.indexOf("<msg>");
        int end = responseMsg.indexOf("</msg>");
        if (start >= 0 && end > start) {
            return responseMsg.substring(start + "<msg>".length(), end);
        }
        return null;
    }

    /**
     * 严格判定业务是否成功：先取 &lt;msg&gt; 节点，命中否定词一律视为失败。
     */
    public static boolean isBusinessSuccess(String responseMsg) {
        if (responseMsg == null) {
            return false;
        }
        String msg = extractMsgNode(responseMsg);
        if (msg == null) {
            msg = responseMsg;
        }
        msg = msg.trim();
        if (msg.isEmpty()) {
            return false;
        }
        if (msg.contains("不成功") || msg.contains("未成功") || msg.contains("失败")
                || msg.contains("错误") || msg.contains("异常")) {
            return false;
        }
        return "成功".equals(msg) || msg.startsWith("成功");
    }

    /**
     * 报文脱敏：屏蔽患者标识类节点，用于 *_Masked 字段与日志输出。
     */
    public static String maskXml(String xml) {
        if (xml == null) {
            return null;
        }
        String masked = xml;
        masked = masked.replaceAll("(?s)(<patientId>)(.*?)(</patientId>)", "$1***$3");
        masked = masked.replaceAll("(?s)(<mrn>)(.*?)(</mrn>)", "$1***$3");
        masked = masked.replaceAll("(?s)(<patientName>)(.*?)(</patientName>)", "$1***$3");
        return masked;
    }
}
