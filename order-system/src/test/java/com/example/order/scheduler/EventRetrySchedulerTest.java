package com.example.order.scheduler;

import com.example.order.entity.EventRecord;
import com.example.order.mapper.EventRecordMapper;
import com.example.order.service.OrderEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox 调度器（EventRetryScheduler）的并发加固验证（mock mapper/service，不碰真库）：
 *   · 先 claim 再处理：claimForSend 影响行数当闸门，没抢到就跳过，事件绝不重复处理
 *   · 成功走 markSendSuccess（带 claim_owner 条件），旧实例补刀命中 0 行不覆盖新实例
 *   · 失败走 markSendResult 条件更新：退避 WAIT 或死信 FAIL
 *   · 每次扫描先 reclaimStuck 回收崩溃实例遗留的 SENDING 僵尸
 *   · requeueFailedEvent 把死信 FAIL 复位回 WAIT（人工重试入口）
 */
class EventRetrySchedulerTest {

    private EventRecordMapper eventRecordMapper;
    private OrderEventService orderEventService;
    private EventRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        eventRecordMapper = mock(EventRecordMapper.class);
        orderEventService = mock(OrderEventService.class);
        scheduler = new EventRetryScheduler(orderEventService, eventRecordMapper);
    }

    private static EventRecord record(Long id) {
        EventRecord r = new EventRecord();
        r.setId(id);
        r.setOrderNo("NO" + id);
        r.setEventType("POINTS");
        r.setEventData("{\"amount\":100}");
        r.setStatus("WAIT");
        r.setRetryCount(0);
        r.setMaxRetry(3);
        r.setNextRetryTime(LocalDateTime.now());
        return r;
    }

    @Test
    void claimForSend返回0_跳过不处理() {
        EventRecord mine = record(1L);
        when(orderEventService.findPendingEvents()).thenReturn(List.of(mine));
        when(eventRecordMapper.claimForSend(eq(1L), anyString())).thenReturn(0);  // 已被其他实例抢走

        scheduler.scanAndProcess();

        verify(orderEventService, never()).process(any(EventRecord.class));
        verify(eventRecordMapper, never()).markSendSuccess(any(), anyString());
    }

    @Test
    void 成功_标记SUCCESS_带本实例claimOwner() {
        EventRecord mine = record(1L);
        when(orderEventService.findPendingEvents()).thenReturn(List.of(mine));
        when(eventRecordMapper.claimForSend(eq(1L), anyString())).thenReturn(1);

        scheduler.scanAndProcess();

        verify(orderEventService).process(mine);
        verify(eventRecordMapper).markSendSuccess(eq(1L), argThat(owner ->
                owner != null && owner.startsWith("order-")));
    }

    @Test
    void 处理失败_退回WAIT_退避重试() {
        EventRecord mine = record(1L);
        when(orderEventService.findPendingEvents()).thenReturn(List.of(mine));
        when(eventRecordMapper.claimForSend(eq(1L), anyString())).thenReturn(1);
        doThrow(new RuntimeException("boom")).when(orderEventService).process(mine);

        scheduler.scanAndProcess();

        // 退回 WAIT + retry_count=1 + 带退避时间，绝不误标 SUCCESS
        verify(eventRecordMapper).markSendResult(eq(1L), anyString(), eq("WAIT"), eq(1),
                argThat(next -> next != null), eq("boom"));
        verify(eventRecordMapper, never()).markSendSuccess(any(), anyString());
    }

    @Test
    void 超过最大重试_进入死信FAIL() {
        EventRecord mine = record(1L);
        mine.setRetryCount(2);   // 再失败一次就 = maxRetry(3)
        when(orderEventService.findPendingEvents()).thenReturn(List.of(mine));
        when(eventRecordMapper.claimForSend(eq(1L), anyString())).thenReturn(1);
        doThrow(new RuntimeException("boom")).when(orderEventService).process(mine);

        scheduler.scanAndProcess();

        verify(eventRecordMapper).markSendResult(eq(1L), anyString(), eq("FAIL"), eq(3),
                any(), eq("boom"));
    }

    @Test
    void 标记成功与抢占用同一claimOwner_旧实例补刀无法覆盖新实例() {
        EventRecord mine = record(1L);
        when(orderEventService.findPendingEvents()).thenReturn(List.of(mine));
        when(eventRecordMapper.claimForSend(eq(1L), anyString())).thenReturn(1);

        scheduler.scanAndProcess();

        // 关键：claim 和 mark 用同一个本实例 claimOwner。
        // markSendSuccess 的 SQL 带 claim_owner=#{owner} 条件——旧实例（已崩溃被回收、记录又被
        // 新实例抢占）补刀时 owner 对不上，命中 0 行，不会把新实例正在处理的记录错误改掉。
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(eventRecordMapper).claimForSend(eq(1L), cap.capture());
        verify(eventRecordMapper).markSendSuccess(eq(1L), eq(cap.getValue()));
    }

    @Test
    void requeueFailed_把死信复位回WAIT() {
        when(eventRecordMapper.requeueFailed(1L)).thenReturn(1);

        scheduler.requeueFailedEvent(1L);

        verify(eventRecordMapper).requeueFailed(1L);
    }

    @Test
    void 每次扫描先回收崩溃实例遗留的SENDING僵尸() {
        when(orderEventService.findPendingEvents()).thenReturn(List.of());

        scheduler.scanAndProcess();

        verify(eventRecordMapper).reclaimStuck(any(LocalDateTime.class));
    }
}
