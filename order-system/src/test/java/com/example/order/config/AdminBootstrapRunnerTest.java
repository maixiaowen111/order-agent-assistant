package com.example.order.config;

import com.example.order.entity.User;
import com.example.order.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员引导初始化测试：幂等（存在跳过）、缺省时插入 ADMIN 账号且密码已 BCrypt 加密。
 * 纯 Mockito，不碰真数据库。
 */
class AdminBootstrapRunnerTest {

    @Test
    void 管理员已存在_跳过初始化() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        AdminBootstrapRunner runner = new AdminBootstrapRunner(mapper, "admin123");

        runner.run(null);

        verify(mapper, never()).insert(any(User.class));
    }

    @Test
    void 不存在_插入管理员账号_密码已加密() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        AdminBootstrapRunner runner = new AdminBootstrapRunner(mapper, "admin123");

        runner.run(null);

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(mapper).insert(cap.capture());
        User admin = cap.getValue();
        assertThat(admin.getUsername()).isEqualTo("admin");
        assertThat(admin.getRole()).isEqualTo("ADMIN");
        assertThat(admin.getStatus()).isEqualTo(1);
        // 密码是 BCrypt 哈希，不可能等于明文，且以 $2a$ 开头
        assertThat(admin.getPassword()).isNotEqualTo("admin123");
        assertThat(admin.getPassword()).startsWith("$2a$");
    }
}
