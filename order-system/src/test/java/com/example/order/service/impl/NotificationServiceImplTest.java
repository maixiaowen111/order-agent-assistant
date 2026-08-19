package com.example.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.order.entity.Notification;
import com.example.order.exception.BusinessException;
import com.example.order.mapper.NotificationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知中心服务测试：核心是"越权保护"——标记已读只允许操作自己的通知。
 */
class NotificationServiceImplTest {

    private final NotificationMapper mapper = mock(NotificationMapper.class);
    private final NotificationServiceImpl service = new NotificationServiceImpl(mapper);

    /**
     * 纯单元测试没有 MyBatis-Spring 上下文，TableInfo 不会自动初始化，
     * 而 LambdaUpdateWrapper 构建时要解析列名（@TableName/@TableField 元数据），
     * 手动初始化一次即可。这是 MyBatis-Plus 单测的固定套路。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Notification.class);
    }

    private static Notification notification(Long id, Long userId) {
        Notification n = new Notification();
        n.setId(id);
        n.setUserId(userId);
        n.setIsRead(0);
        return n;
    }

    @Test
    void 列表返回当前用户的通知() {
        when(mapper.selectList(any())).thenReturn(List.of(notification(1L, 5L)));

        assertThat(service.listMy(5L)).hasSize(1);
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    void 标记不存在的通知_抛404() {
        when(mapper.selectById(99L)).thenReturn(null);

        Throwable t = catchThrowable(() -> service.markRead(1L, 99L));

        assertThat(t).isInstanceOf(BusinessException.class).hasMessageContaining("通知不存在");
        assertThat(((BusinessException) t).getCode()).isEqualTo(404);
    }

    @Test
    void 标记别人的通知_抛403_越权保护() {
        when(mapper.selectById(1L)).thenReturn(notification(1L, 2L)); // 通知属于用户2

        Throwable t = catchThrowable(() -> service.markRead(1L, 1L));  // 用户1来标

        assertThat(t).isInstanceOf(BusinessException.class).hasMessageContaining("无权操作");
        assertThat(((BusinessException) t).getCode()).isEqualTo(403);
    }

    @Test
    void 标记自己的通知_置为已读() {
        Notification n = notification(1L, 1L);
        when(mapper.selectById(1L)).thenReturn(n);

        service.markRead(1L, 1L);

        assertThat(n.getIsRead()).isEqualTo(1);
        verify(mapper, times(1)).updateById(n);
    }

    @Test
    void 全部已读_走数据库update() {
        service.markAllRead(5L);

        verify(mapper, times(1)).update(isNull(), any());
    }

    @Test
    void 未读数量返回计数() {
        when(mapper.selectCount(any())).thenReturn(3L);

        assertThat(service.countUnread(5L)).isEqualTo(3L);
        verify(mapper, times(1)).selectCount(any());
    }
}
