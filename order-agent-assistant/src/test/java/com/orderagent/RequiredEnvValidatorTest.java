package com.orderagent;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产密钥护栏测试：dev 放行；非 dev 环境缺密钥 / 用开发默认值 → 启动失败。
 * 环境变量来源用注入的 Map，测试确定，不碰真实 System.getenv。
 */
class RequiredEnvValidatorTest {

    private final Map<String, String> env = new HashMap<>();

    private RequiredEnvValidator validator(String profile) {
        return new RequiredEnvValidator(profile, env::get);
    }

    @Test
    void dev环境_不校验_允许开发默认值() {
        assertThatCode(() -> validator("dev").run(null)).doesNotThrowAnyException();
    }

    @Test
    void 生产环境_缺失JWT_SECRET_启动失败() {
        assertThatThrownBy(() -> validator("prod").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void 生产环境_用了开发默认值_启动失败() {
        env.put("JWT_SECRET", "MyOrderSystemSecretKeyForJwtToken2024!!!");
        env.put("INTERNAL_API_KEY", "prod-internal-key");

        assertThatThrownBy(() -> validator("prod").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("开发默认值");
    }

    @Test
    void 生产环境_全部设置为非默认值_启动通过() {
        env.put("JWT_SECRET", "prod-jwt-secret");
        env.put("INTERNAL_API_KEY", "prod-internal-key");

        assertThatCode(() -> validator("prod").run(null)).doesNotThrowAnyException();
    }
}
