package com.example.order.service;

import com.example.order.entity.Notification;

import java.util.List;

/**
 * 通知中心服务接口
 */
public interface NotificationService {

    /**
     * 当前用户的通知列表（新的在前）
     */
    List<Notification> listMy(Long userId);

    /**
     * 单条标记已读
     * 越权保护：只能操作自己的通知，别人的抛异常
     */
    void markRead(Long userId, Long id);

    /**
     * 当前用户所有未读通知全部标记已读
     */
    void markAllRead(Long userId);

    /**
     * 当前用户未读通知数量（前端红点/角标用）
     */
    Long countUnread(Long userId);
}
