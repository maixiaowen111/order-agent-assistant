package com.example.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.order.entity.Notification;
import com.example.order.exception.BusinessException;
import com.example.order.mapper.NotificationMapper;
import com.example.order.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知中心服务实现
 *
 * 设计要点：
 *   - markRead 先按 id 查出再校验归属，防止越权改别人通知（改 is_read 本身无危险，
 *     但"能改别人的资源"这个口子必须堵，不然以后这里换成其他资源会照抄出漏洞）
 *   - markAllRead 用 LambdaUpdateWrapper 直接在数据库 UPDATE，不把行拉回 JVM 再改——
 *     未读数多时一次 UPDATE 语句就完成，不会 N 行查 + N 行改
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;

    @Override
    public List<Notification> listMy(Long userId) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
               .orderByDesc(Notification::getCreateTime);
        return notificationMapper.selectList(wrapper);
    }

    @Override
    public void markRead(Long userId, Long id) {
        Notification n = notificationMapper.selectById(id);
        if (n == null) {
            throw new BusinessException(404, "通知不存在");
        }
        // 越权保护：只能操作自己的通知
        if (!n.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权操作该通知");
        }
        n.setIsRead(1);
        notificationMapper.updateById(n);
        log.info("[通知] 标记已读，id={}, userId={}", id, userId);
    }

    @Override
    public void markAllRead(Long userId) {
        LambdaUpdateWrapper<Notification> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Notification::getUserId, userId)   // 只动自己的
               .eq(Notification::getIsRead, 0)        // 只动未读的，已读的不用再写
               .set(Notification::getIsRead, 1);
        notificationMapper.update(null, wrapper);
        log.info("[通知] 全部标记已读，userId={}", userId);
    }

    @Override
    public Long countUnread(Long userId) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
               .eq(Notification::getIsRead, 0);
        return notificationMapper.selectCount(wrapper);
    }
}
