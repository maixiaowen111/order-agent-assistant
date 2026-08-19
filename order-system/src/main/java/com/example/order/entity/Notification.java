package com.example.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用内通知（通知中心）
 * 由事件调度器处理 REFUND 事件时写入，用户在 /api/notification/my 可查。
 */
@Data
@TableName("t_notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String orderNo;

    private String title;

    private String content;

    private Integer isRead;

    private LocalDateTime createTime;
}
