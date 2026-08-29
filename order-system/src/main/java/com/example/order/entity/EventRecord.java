package com.example.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地事件记录表 — Transactional Outbox 模式
 *
 * 设计目的：
 *   订单创建后，非核心事件（积分/短信/通知）先入库，
 *   保证事件和订单在同一事务中落库，永不丢失。
 *   后续由异步线程 + 定时任务兜底扫描处理。
 *
 * 状态流转：
 *   WAIT → SENDING → SUCCESS
 *   WAIT → SENDING → WAIT（失败，等待重试）
 *   WAIT → SENDING → WAIT → ... → FAIL（超过 max_retry）
 */
@Data
@TableName("t_event_record")
public class EventRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联订单号 */
    private String orderNo;

    /** 事件类型：POINTS-积分 SMS-短信 NOTIFY-推送 */
    private String eventType;

    /** 事件数据（JSON），不同事件存放不同参数 */
    private String eventData;

    /** 状态：WAIT / SENDING / SUCCESS / FAIL */
    private String status;

    /** 领取处理权的实例标识（多实例部署时标记"谁在处理"，崩溃回收依据之一） */
    private String claimOwner;

    /** 领取时间：SENDING 僵尸判定用它（在途事件被误回收的修复点） */
    private LocalDateTime claimedAt;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大重试次数 */
    private Integer maxRetry;

    /** 下次重试时间（失败后延迟重试） */
    private LocalDateTime nextRetryTime;

    /** 最后一次失败原因 */
    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
