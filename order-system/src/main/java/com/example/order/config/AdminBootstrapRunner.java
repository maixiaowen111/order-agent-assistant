package com.example.order.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.order.entity.User;
import com.example.order.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时引导初始化管理员账号（幂等）。
 *
 * 项目原本没有产生 ADMIN 账号的入口：注册永远建 USER，也没有提权接口。
 * 演示/开发要有一个可用的管理员，所以启动时自动建一个 admin 账号。
 * 密码走配置 admin.init-password（默认 admin123），日志会打印账号提示。
 *
 * 幂等设计：先查 username=admin 是否存在，存在就跳过——重启不会重复建号。
 * 生产安全：真实系统应移除本类（管理员由运维手动建号），或至少把密码改成
 * 环境变量注入的随机值，别用默认密码。
 */
@Slf4j
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final String ADMIN_USERNAME = "admin";

    private final UserMapper userMapper;
    private final String initPassword;

    public AdminBootstrapRunner(UserMapper userMapper,
                                @Value("${admin.init-password:admin123}") String initPassword) {
        this.userMapper = userMapper;
        this.initPassword = initPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, ADMIN_USERNAME)
                .eq(User::getDeleted, 0);
        if (userMapper.selectCount(wrapper) > 0) {
            log.info("管理员账号已存在，跳过初始化（username={}）", ADMIN_USERNAME);
            return;
        }

        User admin = new User();
        admin.setUsername(ADMIN_USERNAME);
        admin.setPassword(new BCryptPasswordEncoder().encode(initPassword));
        admin.setRole("ADMIN");
        admin.setStatus(1);
        userMapper.insert(admin);
        log.info("管理员账号已初始化：username={}（密码为配置 admin.init-password，默认 admin123）",
                ADMIN_USERNAME);
    }
}
