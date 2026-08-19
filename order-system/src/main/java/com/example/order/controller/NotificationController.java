package com.example.order.controller;

import com.example.order.common.Result;
import com.example.order.context.UserContext;
import com.example.order.entity.Notification;
import com.example.order.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知中心：登录用户查看/操作自己的通知（退款通知会出现在这里）
 *
 * 路由设计：
 *   GET  /api/notification/my           我的通知列表（新的在前）
 *   POST /api/notification/{id}/read    单条标记已读
 *   POST /api/notification/read-all     全部标记已读
 *   GET  /api/notification/unread-count 未读数量（红点/角标）
 */
@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/my")
    public Result<List<Notification>> my() {
        return Result.success(notificationService.listMy(UserContext.getUserId()));
    }

    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        notificationService.markRead(UserContext.getUserId(), id);
        return Result.success();
    }

    @PostMapping("/read-all")
    public Result<Void> readAll() {
        notificationService.markAllRead(UserContext.getUserId());
        return Result.success();
    }

    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(notificationService.countUnread(UserContext.getUserId()));
    }
}
