package com.example.order.service.impl;

import com.example.order.entity.EventRecord;
import com.example.order.entity.Order;
import com.example.order.entity.OrderItem;
import com.example.order.entity.Product;
import com.example.order.exception.BusinessException;
import com.example.order.mapper.CartMapper;
import com.example.order.mapper.EventRecordMapper;
import com.example.order.mapper.OrderItemMapper;
import com.example.order.mapper.OrderMapper;
import com.example.order.mapper.ProductMapper;
import com.example.order.service.OrderEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取消订单状态机测试：WAIT_PAY 取消不退钱、PAID 取消触发退款、幂等拒绝、
 * 非法状态拒绝、库存恢复。全部用 mock mapper，不碰真数据库。
 */
class OrderServiceImplTest {

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private ProductMapper productMapper;
    private EventRecordMapper eventRecordMapper;
    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        orderItemMapper = mock(OrderItemMapper.class);
        CartMapper cartMapper = mock(CartMapper.class);
        productMapper = mock(ProductMapper.class);
        OrderEventService orderEventService = mock(OrderEventService.class);
        eventRecordMapper = mock(EventRecordMapper.class);

        service = new OrderServiceImpl(orderMapper, redissonClient, orderItemMapper,
                cartMapper, productMapper, orderEventService, eventRecordMapper,
                new ObjectMapper());
    }

    private static Order order(Long id, String status) {
        Order o = new Order();
        o.setId(id);
        o.setOrderNo("NO" + id);
        o.setStatus(status);
        o.setTotalAmount(new BigDecimal("1999.00"));
        o.setUserId(5L);
        o.setReceiverPhone("13800138000");
        return o;
    }

    private static OrderItem item(Long productId, int qty) {
        OrderItem i = new OrderItem();
        i.setProductId(productId);
        i.setQuantity(qty);
        return i;
    }

    private static Product product(Long id, int stock) {
        Product p = new Product();
        p.setId(id);
        p.setStock(stock);
        return p;
    }

    @Test
    void 取消不存在的订单_抛404() {
        when(orderMapper.selectById(1L)).thenReturn(null);

        Throwable t = catchThrowable(() -> service.cancel(1L));

        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getCode()).isEqualTo(404);
    }

    @Test
    void 已取消订单_幂等拒绝() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, "CANCELLED"));

        Throwable t = catchThrowable(() -> service.cancel(1L));

        assertThat(((BusinessException) t).getMessage()).contains("无需重复操作");
        verify(eventRecordMapper, never()).insert(any(EventRecord.class));
    }

    @Test
    void 非法状态_不允许取消() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, "SHIPPED"));

        Throwable t = catchThrowable(() -> service.cancel(1L));

        assertThat(((BusinessException) t).getMessage()).contains("不允许取消");
    }

    @Test
    void 未支付订单取消_恢复库存_不触发退款() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, "WAIT_PAY"));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(10L, 2)));
        when(productMapper.selectById(10L)).thenReturn(product(10L, 100));

        service.cancel(1L);

        // 库存 100 + 2 = 102
        ArgumentCaptor<Product> productCap = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).updateById(productCap.capture());
        assertThat(productCap.getValue().getStock()).isEqualTo(102);
        // 订单改为 CANCELLED
        ArgumentCaptor<Order> orderCap = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(orderCap.capture());
        assertThat(orderCap.getValue().getStatus()).isEqualTo("CANCELLED");
        // 未支付 → 不插退款事件
        verify(eventRecordMapper, never()).insert(any(EventRecord.class));
    }

    @Test
    void 已支付订单取消_恢复库存_触发退款事件() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, "PAID"));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(10L, 1)));
        when(productMapper.selectById(10L)).thenReturn(product(10L, 50));

        service.cancel(1L);

        // 库存 50 + 1 = 51
        ArgumentCaptor<Product> productCap = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).updateById(productCap.capture());
        assertThat(productCap.getValue().getStock()).isEqualTo(51);
        // 插了一条 WAIT 的 REFUND 事件，数据带金额
        ArgumentCaptor<EventRecord> eventCap = ArgumentCaptor.forClass(EventRecord.class);
        verify(eventRecordMapper).insert(eventCap.capture());
        assertThat(eventCap.getValue().getEventType()).isEqualTo("REFUND");
        assertThat(eventCap.getValue().getStatus()).isEqualTo("WAIT");
        assertThat(eventCap.getValue().getEventData()).contains("1999.00");
    }

    @Test
    void 按订单号取消_已支付返回refundTriggered_true() {
        Order paid = order(1L, "PAID");
        when(orderMapper.selectOne(any())).thenReturn(paid);  // getOrderByNo
        when(orderMapper.selectById(1L)).thenReturn(paid);    // cancel(id)
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.cancelByOrderNo("NO1");

        assertThat(result.get("status")).isEqualTo("CANCELLED");
        assertThat(result.get("refundTriggered")).isEqualTo(true);
        verify(eventRecordMapper).insert(any(EventRecord.class));
    }
}
