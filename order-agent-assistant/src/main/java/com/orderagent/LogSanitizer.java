package com.orderagent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 日志脱敏：日志里不许出现完整地址、完整手机号（更别说 API Key / 密码 / JWT，那类根本不该进日志）。
 * 两个手段：
 *   1) 结构化参数按字段名打码：address / phone 这类字段的值只留前 6 个字 + ***；
 *   2) 自由文本（工具结果是一整句话，没法按名打码）→ 手机号打码 + 超长文本截断。
 * 注意局限：自由文本里"较短的完整地址"靠截断挡不住，彻底方案是业务层就返回脱敏数据。
 */
public final class LogSanitizer {

    private static final Set<String> SENSITIVE_KEYS =
            Set.of("address", "receiverAddress", "phone", "receiverPhone");
    private static final int MAX_TEXT_LEN = 50;
    private static final int MAX_ARG_KEEP = 6;

    private LogSanitizer() {
    }

    /** 工具参数：敏感字段的值打码，其余原样返回（新 map，不改原参数） */
    public static Map<String, Object> sanitizeArgs(Map<String, Object> args) {
        Map<String, Object> copy = new HashMap<>(args);
        for (String key : SENSITIVE_KEYS) {
            if (copy.containsKey(key)) {
                copy.put(key, maskValue(String.valueOf(copy.get(key))));
            }
        }
        return copy;
    }

    /** 自由文本：手机号打码 + 超长截断。手机号 = 1 开头 11 位数字，中间 4 位打星号 */
    public static String maskText(String text) {
        if (text == null) {
            return "";
        }
        String masked = text.replaceAll("(1[3-9]\\d)\\d{4}(\\d{4})", "$1****$2");
        return masked.length() <= MAX_TEXT_LEN ? masked : masked.substring(0, MAX_TEXT_LEN) + "…";
    }

    /** 敏感字段值：只留前 6 个字，后面打星号 */
    private static String maskValue(String value) {
        int keep = Math.min(MAX_ARG_KEEP, value.length());
        return value.substring(0, keep) + "***";
    }
}
