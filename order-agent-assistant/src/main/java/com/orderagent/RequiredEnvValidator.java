package com.orderagent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 生产环境密钥护栏：非 dev 环境启动时，强制校验敏感环境变量已设置且没用开发默认值。
 *
 * 为什么：本地开发图省事，application.yml 里给了开发默认值（JWT_SECRET / INTERNAL_API_KEY），
 * 但生产环境绝不能带着公开的默认密钥上线。这里在非 dev 环境把"缺失 / 用默认值"变成启动失败，
 * 让问题卡在部署环节。和 order-system 侧的 RequiredEnvValidator 是同一套约定。
 *
 * 判定：spring.profiles.active == dev 放行（本地开发不设环境变量也能跑通）；其余环境一律校验。
 * DEEPSEEK_API_KEY 不需要在这里列：它在配置里就是 ${DEEPSEEK_API_KEY}、没有默认值，
 * Spring 解析不到占位符会直接启动失败，天然强制。
 */
@Component
public class RequiredEnvValidator implements ApplicationRunner {

    /** 生产必须设置的环境变量 → 开发默认值（生产禁止使用）。LinkedHashMap 保序：报错优先提示第一项。 */
    private static final Map<String, String> REQUIRED_IN_NON_DEV = new LinkedHashMap<>();

    static {
        REQUIRED_IN_NON_DEV.put("JWT_SECRET", "MyOrderSystemSecretKeyForJwtToken2024!!!");
        REQUIRED_IN_NON_DEV.put("INTERNAL_API_KEY", "order-agent-internal-key-2026");
    }

    private final String activeProfile;
    private final Function<String, String> env;

    /** 双构造器必须 @Autowired 指明注入用哪个，否则 Spring 退回无参构造 → 启动崩。 */
    @Autowired
    public RequiredEnvValidator(@Value("${spring.profiles.active:dev}") String activeProfile) {
        this(activeProfile, System::getenv);
    }

    /** 测试用：环境变量来源可注入（System.getenv 在单测里无法替换）。 */
    RequiredEnvValidator(String activeProfile, Function<String, String> env) {
        this.activeProfile = activeProfile;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        if ("dev".equals(activeProfile)) {
            return; // 开发环境允许开发默认值，本地开发不卡
        }
        for (Map.Entry<String, String> entry : REQUIRED_IN_NON_DEV.entrySet()) {
            String value = env.apply(entry.getKey());
            if (value == null || value.isBlank() || entry.getValue().equals(value)) {
                throw new IllegalStateException(
                        "生产环境必须设置环境变量 " + entry.getKey()
                                + "（且不能使用开发默认值 " + entry.getValue() + "）。请检查部署环境。");
            }
        }
    }
}
