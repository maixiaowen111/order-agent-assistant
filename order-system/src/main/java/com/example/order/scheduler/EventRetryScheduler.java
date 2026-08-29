package com.example.order.scheduler;

import com.example.order.entity.EventRecord;
import com.example.order.mapper.EventRecordMapper;
import com.example.order.service.OrderEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 事件重试调度器
 *
 * 职责：
 *   定时扫描 t_event_record 中待处理的事件，交给 OrderEventService 执行
 *
 * 设计要点：
 *   ① fixedDelay=5000ms：上次执行完成后等 5 秒再扫下一轮
 *   ② 每次最多处理 50 条，防止单次扫描耗时过长
 *   ③ 先 claim 再处理：条件更新 WAIT→SENDING（影响行数当闸门），多实例下谁先 claim 谁处理，
 *      绝不重复处理同一条事件
 *   ④ 失败后：WAIT 状态 + 递增 retry_count + 指数退避 next_retry_time
 *   ⑤ 超过 max_retry → FAIL，人工介入（requeueFailedEvent 可复位重试）
 *   ⑥ 每次扫描先 reclaimStuck 回收崩溃实例遗留的 SENDING 僵尸（claimed_at 超阈值）
 *
 * 为什么还需要定时任务？
 *   即使订单创建时 @Async 立即处理了，也可能处理失败。
 *   定时任务作为兜底，保证"至少处理一次"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventRetryScheduler {

    /** SENDING 超过该时长视为实例崩溃留下的僵尸，收回 WAIT */
    private static final int STUCK_MINUTES = 10;

    private final OrderEventService orderEventService;
    private final EventRecordMapper eventRecordMapper;

    /**
     * 本实例的处理者标识：claim 事件时写进 claim_owner，崩溃后靠它排查/回收。
     * 刻意用非 final 内联字段——它不参与 @RequiredArgsConstructor 的两参构造，
     * 每实例一个，重启换新，保证多实例 claim 互不冒充。
     */
    private String claimOwner = "order-" + UUID.randomUUID().toString().substring(0, 8);

    /**
     * 每 5 秒扫描一次
     *
     * 为什么是 5 秒？
     *   - 太短（1s）：数据库频繁查询，浪费连接
     *   - 太长（1min）：事件延迟大，用户体验差
     *   - 5 秒是轻量级场景的合理折中
     */
    @Scheduled(fixedDelay = 5000)
    public void scanAndProcess() {
        // 0. 回收崩溃实例遗留的 SENDING 僵尸，重新进入待处理池
        eventRecordMapper.reclaimStuck(LocalDateTime.now().minusMinutes(STUCK_MINUTES));

        List<EventRecord> pendingList = orderEventService.findPendingEvents();
        if (pendingList.isEmpty()) {
            return;  // 无事可做，安静跳过
        }

        log.info("[调度] 扫描到 {} 条待处理事件", pendingList.size());

        for (EventRecord record : pendingList) {
            // 1. 条件抢占：只有仍 WAIT 的才轮到本实例处理（多实例下谁先 claim 谁处理）
            int claimed = eventRecordMapper.claimForSend(record.getId(), claimOwner);
            if (claimed == 0) {
                continue;   // 已被其他实例抢走，跳过
            }

            try {
                // 2. 业务副作用（积分/短信/通知/退款落库）
                orderEventService.process(record);
                // 3. 条件标记成功：只有还是 SENDING 且归属本实例才置 SUCCESS
                eventRecordMapper.markSendSuccess(record.getId(), claimOwner);
            } catch (Exception e) {
                // 4. 失败 → 条件更新状态：退避重试或进死信
                handleRetry(record, e);
            }
        }
    }

    /**
     * 失败重试策略
     *
     * 指数退避：
     *   第1次重试：1 分钟后
     *   第2次重试：3 分钟后
     *   第3次重试：5 分钟后
     *   超过 maxRetry → 标记 FAIL，不再重试
     *
     * 状态统一走 markSendResult 条件更新（WHERE status='SENDING' AND claim_owner=本实例），
     * 不再用 updateById——防止多实例下把别的实例正在处理的记录覆盖掉。
     */
    private void handleRetry(EventRecord record, Exception e) {
        int retryCount = record.getRetryCount() + 1;

        if (retryCount >= record.getMaxRetry()) {
            // 超过最大重试 → 进入死信 FAIL（人工介入），清掉下次重试时间
            eventRecordMapper.markSendResult(record.getId(), claimOwner, "FAIL",
                    retryCount, null, e.getMessage());
            log.error("[调度] 事件进入死信，id={}, type={}, orderNo={}, retryCount={}, error={}",
                    record.getId(), record.getEventType(), record.getOrderNo(),
                    retryCount, e.getMessage());
        } else {
            // 还未超过 → 重置为 WAIT，等 next_retry_time 到后再处理
            LocalDateTime nextRetryTime = calcNextRetryTime(retryCount);
            eventRecordMapper.markSendResult(record.getId(), claimOwner, "WAIT",
                    retryCount, nextRetryTime, e.getMessage());
            log.warn("[调度] 事件重试，id={}, type={}, orderNo={}, retryCount={}, nextRetry={}",
                    record.getId(), record.getEventType(), record.getOrderNo(),
                    retryCount, nextRetryTime);
        }
    }

    /**
     * 指数退避时间
     */
    private LocalDateTime calcNextRetryTime(int retryCount) {
        int minutes;
        switch (retryCount) {
            case 1:  minutes = 1;  break;
            case 2:  minutes = 3;  break;
            default: minutes = 5;  break;
        }
        return LocalDateTime.now().plusMinutes(minutes);
    }

    /**
     * 人工重试入口：把死信（FAIL）复位回 WAIT，清掉重试计数和错误信息，立即重新排队。
     * 只对 FAIL 状态生效（requeueFailed 的条件 WHERE status='FAIL'），不会误复位正常事件。
     */
    public void requeueFailedEvent(Long id) {
        int affected = eventRecordMapper.requeueFailed(id);
        if (affected == 0) {
            log.warn("[调度] 复位失败：事件不存在或非 FAIL 状态，id={}", id);
        } else {
            log.info("[调度] 死信已复位回 WAIT，等待重新处理，id={}", id);
        }
    }
}
