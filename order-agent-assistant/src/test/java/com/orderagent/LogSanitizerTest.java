package com.orderagent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志脱敏测试：敏感字段打码、手机号打码、超长文本截断，
 * 保证日志里不落完整地址 / 完整手机号。
 */
class LogSanitizerTest {

    @Test
    void 工具参数里的地址被打码_订单号保留原样() {
        Map<String, Object> sanitized = LogSanitizer.sanitizeArgs(Map.of(
                "orderNo", "A123",
                "address", "上海市浦东新区张江高科技园区"));

        assertThat(sanitized.get("orderNo")).isEqualTo("A123");
        assertThat(String.valueOf(sanitized.get("address")))
                .contains("***")
                .doesNotContain("张江高科技园区");
    }

    @Test
    void 手机号字段也按敏感字段打码() {
        Map<String, Object> sanitized = LogSanitizer.sanitizeArgs(Map.of(
                "receiverPhone", "13812345678"));

        assertThat(String.valueOf(sanitized.get("receiverPhone")))
                .doesNotContain("13812345678")
                .contains("***");
    }

    @Test
    void 自由文本里的手机号中间四位被打码() {
        assertThat(LogSanitizer.maskText("收货电话：13812345678"))
                .isEqualTo("收货电话：138****5678");
    }

    @Test
    void 超长文本被截断() {
        String longText = "很长的内容".repeat(20); // 100 字
        assertThat(LogSanitizer.maskText(longText)).hasSize(51); // 前 50 字 + 省略号
    }

    @Test
    void null文本返回空串_不炸() {
        assertThat(LogSanitizer.maskText(null)).isEmpty();
    }
}
