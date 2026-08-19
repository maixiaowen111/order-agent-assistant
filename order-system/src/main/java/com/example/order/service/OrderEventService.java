package com.example.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.order.entity.EventRecord;
import com.example.order.entity.Notification;
import com.example.order.enums.EventType;
import com.example.order.mapper.EventRecordMapper;
import com.example.order.mapper.NotificationMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 订单事件处理服务
 *
 * 职责：根据 event_type 执行对应的业务逻辑（积分/短信/通知）
 * 不负责"什么时候处理"——调度由 EventRetryScheduler 负责
 *
 * 设计要点：
 *   - 处理前检查状态，防止重复执行（幂等）
 *   - 处理失败抛异常，由外层（Scheduler）决定重试或标记 FAIL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEventService {

    private final EventRecordMapper eventRecordMapper;
    private final NotificationMapper notificationMapper;
    private final ObjectMapper objectMapper;

    /**
     * 处理单个事件记录
     *
     * @return true=处理成功，false=跳过（已处理或类型未知）
     */
    public boolean process(EventRecord record) {
        // ① 幂等保护：非 WAIT 状态跳过
        if (!"WAIT".equals(record.getStatus())) {
            log.info("[事件] 跳过，id={}, status={}", record.getId(), record.getStatus());
            return true;  // 已处理的视为成功，不再重试
        }

        // ② 标记为处理中
        record.setStatus("PROCESSING");
        eventRecordMapper.updateById(record);

        // ③ 根据类型执行
        try {
            Map<String, Object> data = parseEventData(record.getEventData());

            switch (EventType.valueOf(record.getEventType())) {
                case POINTS:
                    handlePoints(data, record.getOrderNo());
                    break;
                case SMS:
                    handleSms(data, record.getOrderNo());
                    break;
                case NOTIFY:
                    handleNotify(data, record.getOrderNo());
                    break;
                case REFUND:
                    handleRefund(data, record.getOrderNo());
                    break;
                default:
                    log.warn("[事件] 未知类型，id={}, type={}", record.getId(), record.getEventType());
                    break;
            }

            // ④ 标记成功
            record.setStatus("SUCCESS");
            eventRecordMapper.updateById(record);
            log.info("[事件] 处理成功，id={}, type={}, orderNo={}",
                    record.getId(), record.getEventType(), record.getOrderNo());
            return true;

        } catch (Exception e) {
            log.error("[事件] 处理失败，id={}, type={}, orderNo={}, error={}",
                    record.getId(), record.getEventType(), record.getOrderNo(), e.getMessage());
            throw e;  // 抛给外层 Scheduler 处理重试逻辑
        }
    }

    /**
     * 查询待处理的事件（WAIT 状态 + 已到重试时间）
     */
    public List<EventRecord> findPendingEvents() {
        LambdaQueryWrapper<EventRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EventRecord::getStatus, "WAIT")
               .le(EventRecord::getNextRetryTime, LocalDateTime.now())
               .last("LIMIT 50");
        return eventRecordMapper.selectList(wrapper);
    }

    // ==================== 各事件处理逻辑 ====================

    private void handlePoints(Map<String, Object> data, String orderNo) {
        Object amountObj = data.get("amount");
        Integer amount = amountObj instanceof Integer ? (Integer) amountObj
                : ((Number) amountObj).intValue();
        log.info("[积分] 赠送积分，orderNo={}, amount={}", orderNo, amount);
        // TODO 阶段五：调用积分服务 RPC 接口
    }

    private void handleSms(Map<String, Object> data, String orderNo) {
        String phone = (String) data.get("phone");
        String receiverName = (String) data.get("receiverName");
        log.info("[短信] 发送下单通知，orderNo={}, phone={}, name={}",
                orderNo, phone, receiverName);
        // TODO 阶段五：调用短信服务接口
    }

    private void handleNotify(Map<String, Object> data, String orderNo) {
        log.info("[通知] 推送下单通知，orderNo={}, userId={}",
                orderNo, data.get("userId"));
        // TODO 阶段五：调用推送服务接口
    }

    /**
     * 退款通知：由 agent 取消订单后插入的 REFUND 事件触发。
     * agent 只管"决策和触发"，真正可靠的执行（重试/幂等）交给这里的调度器链路兜底。
     */
    private void handleRefund(Map<String, Object> data, String orderNo) {
        Object userIdObj = data.get("userId");
        Object amount = data.get("amount");
        Long userId = userIdObj == null ? null : ((Number) userIdObj).longValue();
        log.info("[退款] 写入退款通知，orderNo={}, amount={}, userId={}", orderNo, amount, userId);

        // 真实落库：写进通知中心，用户在 /api/notification/my 可查
        Notification n = new Notification();
        n.setUserId(userId);
        n.setOrderNo(orderNo);
        n.setTitle("订单退款通知");
        n.setContent("您的订单 " + orderNo + " 已取消，退款金额 " + amount + " 元，预计 1-3 个工作日原路退回。");
        n.setIsRead(0);
        notificationMapper.insert(n);
        // TODO 阶段五：调用短信/推送服务
    }

    private Map<String, Object> parseEventData(String eventData) {
        try {
            return objectMapper.readValue(eventData, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("事件数据解析失败", e);
        }
    }
}
