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

/**
 * 事件重试调度器
 *
 * 职责：
 *   定时扫描 t_event_record 中待处理的事件，交给 OrderEventService 执行
 *
 * 设计要点：
 *   ① fixedDelay=5000ms：上次执行完成后等 5 秒再扫下一轮
 *   ② 每次最多处理 50 条，防止单次扫描耗时过长
 *   ③ 失败后：WAIT 状态 + 递增 retry_count + 指数退避 next_retry_time
 *   ④ 超过 max_retry → FAIL，人工介入
 *
 * 为什么还需要定时任务？
 *   即使订单创建时 @Async 立即处理了，也可能处理失败。
 *   定时任务作为兜底，保证"至少处理一次"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventRetryScheduler {

    private final OrderEventService orderEventService;
    private final EventRecordMapper eventRecordMapper;

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
        List<EventRecord> pendingList = orderEventService.findPendingEvents();

        if (pendingList.isEmpty()) {
            return;  // 无事可做，安静跳过
        }

        log.info("[调度] 扫描到 {} 条待处理事件", pendingList.size());

        for (EventRecord record : pendingList) {
            try {
                orderEventService.process(record);
                // 成功 → process() 内部已更新 status=SUCCESS

            } catch (Exception e) {
                // 失败 → 判断是否需要重试
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
     */
    private void handleRetry(EventRecord record, Exception e) {
        int retryCount = record.getRetryCount() + 1;

        if (retryCount >= record.getMaxRetry()) {
            // 超过最大重试 → 进入死信
            record.setStatus("FAIL");
            record.setRetryCount(retryCount);
            record.setErrorMsg(e.getMessage());
            eventRecordMapper.updateById(record);
            log.error("[调度] 事件进入死信，id={}, type={}, orderNo={}, retryCount={}, error={}",
                    record.getId(), record.getEventType(), record.getOrderNo(),
                    retryCount, e.getMessage());
        } else {
            // 还未超过 → 重置为 WAIT，等 next_retry_time 到后再处理
            record.setStatus("WAIT");
            record.setRetryCount(retryCount);
            record.setNextRetryTime(calcNextRetryTime(retryCount));
            record.setErrorMsg(e.getMessage());
            eventRecordMapper.updateById(record);
            log.warn("[调度] 事件重试，id={}, type={}, orderNo={}, retryCount={}, nextRetry={}",
                    record.getId(), record.getEventType(), record.getOrderNo(),
                    retryCount, record.getNextRetryTime());
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
}
