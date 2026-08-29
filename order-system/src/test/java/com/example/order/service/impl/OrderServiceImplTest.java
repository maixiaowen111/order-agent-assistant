package com.example.order.service.impl;

import com.example.order.context.UserContext;
import com.example.order.dto.CreateOrderDTO;
import com.example.order.entity.Cart;
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
import com.example.order.vo.OrderVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

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
 * 订单服务测试：取消状态机（WAIT_PAY 取消不退钱、PAID 取消触发退款、幂等拒绝、非法状态拒绝）、
 * 库存原子扣减/恢复、订单归属校验（只能动自己的订单）。全部用 mock mapper，不碰真数据库。
 */
class OrderServiceImplTest {

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private ProductMapper productMapper;
    private CartMapper cartMapper;
    private EventRecordMapper eventRecordMapper;
    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        CartMapper cartMapper = mock(CartMapper.class);
        productMapper = mock(ProductMapper.class);
        OrderEventService orderEventService = mock(OrderEventService.class);
        eventRecordMapper = mock(EventRecordMapper.class);
        this.cartMapper = cartMapper;

        service = new OrderServiceImpl(orderMapper, orderItemMapper,
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

    /** 带完整收货信息的订单：测脱敏时用（order(...) 只设了 phone） */
    private static Order shippingOrder(Long id, String status) {
        Order o = order(id, status);
        o.setReceiverName("张小明");
        o.setReceiverAddress("上海市浦东新区张江高科技园区");
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

    // ---------- 取消状态机 ----------

    @Test
    void 取消不存在的订单_抛404() {
        when(orderMapper.selectById(1L)).thenReturn(null);

        Throwable t = catchThrowable(() -> service.cancel(1L, 5L));

        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getCode()).isEqualTo(404);
    }

    @Test
    void 没有用户身份_取消_401() {
        Throwable t = catchThrowable(() -> service.cancel(1L, null));

        assertThat(((BusinessException) t).getCode()).isEqualTo(401);
    }

    @Test
    void 已取消订单_幂等拒绝() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, "CANCELLED"));

        Throwable t = catchThrowable(() -> service.cancel(1L, 5L));

        assertThat(((BusinessException) t).getMessage()).contains("无需重复操作");
        verify(eventRecordMapper, never()).insert(any(EventRecord.class));
    }

    @Test
    void 非法状态_不允许取消() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, "SHIPPED"));

        Throwable t = catchThrowable(() -> service.cancel(1L, 5L));

        assertThat(((BusinessException) t).getMessage()).contains("不允许取消");
    }

    @Test
    void 未支付订单取消_恢复库存_不触发退款() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, "WAIT_PAY"));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(10L, 2)));
        when(orderMapper.markCancelled(1L)).thenReturn(1);

        service.cancel(1L, 5L);

        // 库存原子加：不再"先查再写"（并发下查到的 stock 可能是旧的）
        verify(productMapper).restoreStock(10L, 2);
        // 状态原子流转：只有还是 WAIT_PAY/PAID 才置 CANCELLED
        verify(orderMapper).markCancelled(1L);
        // 未支付 → 不插退款事件
        verify(eventRecordMapper, never()).insert(any(EventRecord.class));
    }

    @Test
    void 已支付订单取消_恢复库存_触发退款事件() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, "PAID"));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(10L, 1)));
        when(orderMapper.markCancelled(1L)).thenReturn(1);

        service.cancel(1L, 5L);

        verify(productMapper).restoreStock(10L, 1);
        // 插了一条 WAIT 的 REFUND 事件，数据带金额
        ArgumentCaptor<EventRecord> eventCap = ArgumentCaptor.forClass(EventRecord.class);
        verify(eventRecordMapper).insert(eventCap.capture());
        assertThat(eventCap.getValue().getEventType()).isEqualTo("REFUND");
        assertThat(eventCap.getValue().getStatus()).isEqualTo("WAIT");
        assertThat(eventCap.getValue().getEventData()).contains("1999.00");
    }

    @Test
    void 并发取消_状态守卫保证只恢复一次库存() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, "WAIT_PAY"));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(10L, 2)));
        // 另一个并发请求已抢先执行 markCancelled，导致本次影响行数=0
        when(orderMapper.markCancelled(1L)).thenReturn(0);

        Throwable t = catchThrowable(() -> service.cancel(1L, 5L));

        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).getMessage()).contains("状态已变化");
        verify(productMapper, never()).restoreStock(any(), any());  // 关键：库存绝不被重复恢复
    }

    @Test
    void 按订单号取消_已支付返回refundTriggered_true() {
        Order paid = order(1L, "PAID");
        when(orderMapper.selectOne(any())).thenReturn(paid);  // getOrderByNo
        when(orderMapper.selectById(1L)).thenReturn(paid);    // cancel(id) 内部再查一次
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.markCancelled(1L)).thenReturn(1);

        Map<String, Object> result = service.cancelByOrderNo("NO1", 5L);

        assertThat(result.get("status")).isEqualTo("CANCELLED");
        assertThat(result.get("refundTriggered")).isEqualTo(true);
        verify(eventRecordMapper).insert(any(EventRecord.class));
    }

    // ---------- 下单：原子扣库存 ----------

    @Test
    void 下单成功_原子扣库存_金额正确() {
        Cart cart = new Cart();
        cart.setId(1L);
        cart.setProductId(10L);
        cart.setQuantity(2);
        when(cartMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(cart));
        Product p = product(10L, 100);
        p.setName("iPhone 15 Ultra");
        p.setPrice(new BigDecimal("5000.00"));
        p.setStatus(1);
        when(productMapper.selectById(10L)).thenReturn(p);
        when(productMapper.deductStock(10L, 2)).thenReturn(1);

        UserContext.set(5L, "u", "USER");
        try {
            CreateOrderDTO dto = new CreateOrderDTO();
            dto.setCartIds(List.of(1L));
            dto.setReceiverName("张小明");
            dto.setReceiverPhone("13800138000");
            dto.setReceiverAddress("上海");

            OrderVO vo = service.create(dto);

            verify(productMapper).deductStock(10L, 2);   // 原子扣减命中 1 行
            assertThat(vo.getTotalAmount()).isEqualByComparingTo("10000.00");
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void 并发扣库存_原子扣减0行_下单失败且不落库() {
        Cart cart = new Cart();
        cart.setId(1L);
        cart.setProductId(10L);
        cart.setQuantity(5);
        when(cartMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(cart));
        Product p = product(10L, 10);  // 预读还够（快失败放行），但并发下实际已被别人扣光
        p.setName("iPhone 15 Ultra");
        p.setStatus(1);
        when(productMapper.selectById(10L)).thenReturn(p);
        when(productMapper.deductStock(10L, 5)).thenReturn(0);  // 原子 UPDATE 影响 0 行 → 拦下

        UserContext.set(5L, "u", "USER");
        try {
            CreateOrderDTO dto = new CreateOrderDTO();
            dto.setCartIds(List.of(1L));
            dto.setReceiverName("张小明");
            dto.setReceiverPhone("13800138000");
            dto.setReceiverAddress("上海");

            Throwable t = catchThrowable(() -> service.create(dto));

            assertThat(t).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) t).getCode()).isEqualTo(400);
            assertThat(((BusinessException) t).getMessage()).contains("库存不足");
            // 订单和订单明细一条都没落库
            verify(orderMapper, never()).insert(any(Order.class));
            verify(orderItemMapper, never()).insert(any(OrderItem.class));
        } finally {
            UserContext.clear();
        }
    }

    // ---------- 归属校验：只能动自己的订单 ----------

    @Test
    void 不是订单主人_查详情403() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, "PAID"));  // 订单属于用户 5

        Throwable t = catchThrowable(() -> service.detail(1L, 99L));

        assertThat(((BusinessException) t).getCode()).isEqualTo(403);
        verify(orderItemMapper, never()).selectList(any());
    }

    @Test
    void 不是订单主人_取消403_不碰状态不碰库存() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, "WAIT_PAY"));

        Throwable t = catchThrowable(() -> service.cancel(1L, 99L));

        assertThat(((BusinessException) t).getCode()).isEqualTo(403);
        verify(orderMapper, never()).markCancelled(any());
        verify(productMapper, never()).restoreStock(any(), any());
    }

    @Test
    void 不是订单主人_按单号查403() {
        when(orderMapper.selectOne(any())).thenReturn(order(1L, "PAID"));

        Throwable t = catchThrowable(() -> service.getByOrderNo("NO1", 99L));

        assertThat(((BusinessException) t).getCode()).isEqualTo(403);
    }

    @Test
    void 不是订单主人_按单号改地址403() {
        when(orderMapper.selectOne(any())).thenReturn(order(1L, "PAID"));

        Throwable t = catchThrowable(() -> service.updateAddress("NO1", "新地址", 99L));

        assertThat(((BusinessException) t).getCode()).isEqualTo(403);
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    void 订单主人_按单号取消_正常执行() {
        Order mine = order(1L, "WAIT_PAY");
        when(orderMapper.selectOne(any())).thenReturn(mine);  // getOrderByNo
        when(orderMapper.selectById(1L)).thenReturn(mine);    // cancel 内部再查一次
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.markCancelled(1L)).thenReturn(1);

        Map<String, Object> result = service.cancelByOrderNo("NO1", 5L);  // 自己是订单主人

        assertThat(result.get("status")).isEqualTo("CANCELLED");
        assertThat(result.get("refundTriggered")).isEqualTo(false);
        verify(orderMapper).markCancelled(1L);
    }

    // ---------- 内部读/写路径 ----------

    @Test
    void 内部查询_收货信息脱敏() {
        Order full = shippingOrder(1L, "PAID");
        when(orderMapper.selectOne(any())).thenReturn(full);  // getOrderByNo
        when(orderMapper.selectById(1L)).thenReturn(full);    // detail
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        OrderVO vo = service.getByOrderNo("NO1", 5L);

        assertThat(vo.getReceiverName()).isEqualTo("张*明");
        assertThat(vo.getReceiverPhone()).isEqualTo("138****8000");
        assertThat(vo.getReceiverAddress()).isEqualTo("上海市浦东新***");
    }

    @Test
    void 改地址成功_回显脱敏() {
        Order full = shippingOrder(1L, "PAID");
        when(orderMapper.selectOne(any())).thenReturn(full);  // getOrderByNo
        when(orderMapper.selectById(1L)).thenReturn(full);    // detail（回显）
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        OrderVO vo = service.updateAddress("NO1", "上海市浦东新区张江", 5L);

        // 写库发生了，但回显给 agent 的是打码后的地址
        verify(orderMapper).updateById(any(Order.class));
        assertThat(vo.getReceiverAddress()).isEqualTo("上海市浦东新***");
        assertThat(vo.getReceiverPhone()).isEqualTo("138****8000");
        assertThat(vo.getReceiverName()).isEqualTo("张*明");
    }

    @Test
    void 用户端详情_仍返回完整收货信息() {
        Order full = shippingOrder(1L, "PAID");
        when(orderMapper.selectById(1L)).thenReturn(full);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        OrderVO vo = service.detail(1L, 5L);

        // 反向守卫：detail() 是用户端路径，订单主人该看到自己的完整地址
        assertThat(vo.getReceiverName()).isEqualTo("张小明");
        assertThat(vo.getReceiverPhone()).isEqualTo("13800138000");
        assertThat(vo.getReceiverAddress()).isEqualTo("上海市浦东新区张江高科技园区");
    }

    // ---------- 幂等下单：clientRequestId 防网络重试重复下单 ----------

    @Test
    void 带幂等键重复下单_回放已有订单_不扣库存不删购物车() {
        Order existing = order(1L, "WAIT_PAY");   // orderNo="NO1"
        when(orderMapper.selectOne(any())).thenReturn(existing);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        UserContext.set(5L, "u", "USER");
        try {
            CreateOrderDTO dto = new CreateOrderDTO();
            dto.setCartIds(List.of(1L));
            dto.setClientRequestId("req-123");

            OrderVO vo = service.create(dto);

            assertThat(vo.getOrderNo()).isEqualTo("NO1");   // 回放已有订单，不是新单号
            // 关键：幂等命中绝不再碰库存、不删购物车、不再落库
            verify(productMapper, never()).deductStock(any(), any());
            verify(cartMapper, never()).deleteBatchIds(any());
            verify(orderMapper, never()).insert(any(Order.class));
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void 并发同键_第二个insert撞唯一键_回放赢家订单() {
        Order winner = order(9L, "WAIT_PAY");   // 赢家已抢先落库，orderNo="NO9"
        // 预检查没命中（赢家还没提交），insert 时撞唯一键 → catch 回查命中赢家
        when(orderMapper.selectOne(any())).thenReturn(null, winner);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        Cart cart = new Cart();
        cart.setId(1L);
        cart.setProductId(10L);
        cart.setQuantity(2);
        when(cartMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(cart));
        Product p = product(10L, 100);
        p.setName("iPhone 15 Ultra");
        p.setPrice(new BigDecimal("5000.00"));
        p.setStatus(1);
        when(productMapper.selectById(10L)).thenReturn(p);
        when(productMapper.deductStock(10L, 2)).thenReturn(1);
        when(orderMapper.insert(any(Order.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry 'req-123' for key 'uk_client_request_id'"));
        UserContext.set(5L, "u", "USER");
        try {
            CreateOrderDTO dto = new CreateOrderDTO();
            dto.setCartIds(List.of(1L));
            dto.setReceiverName("张小明");
            dto.setReceiverPhone("13800138000");
            dto.setReceiverAddress("上海");
            dto.setClientRequestId("req-123");

            OrderVO vo = service.create(dto);

            assertThat(vo.getOrderNo()).isEqualTo("NO9");   // 回放赢家订单，不建新单
            // 输家的 insert 确实尝试过一次（撞键），之后不再重复落库
            verify(orderMapper).insert(any(Order.class));
            // 撞键后整个事务回滚：购物车不删、订单明细不落库、事件不落库
            verify(cartMapper, never()).deleteBatchIds(any());
            verify(orderItemMapper, never()).insert(any(OrderItem.class));
            verify(eventRecordMapper, never()).insert(any(EventRecord.class));
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void 不带幂等键_跳过预检查_正常下单() {
        Cart cart = new Cart();
        cart.setId(1L);
        cart.setProductId(10L);
        cart.setQuantity(2);
        when(cartMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(cart));
        Product p = product(10L, 100);
        p.setName("iPhone 15 Ultra");
        p.setPrice(new BigDecimal("5000.00"));
        p.setStatus(1);
        when(productMapper.selectById(10L)).thenReturn(p);
        when(productMapper.deductStock(10L, 2)).thenReturn(1);
        UserContext.set(5L, "u", "USER");
        try {
            CreateOrderDTO dto = new CreateOrderDTO();
            dto.setCartIds(List.of(1L));
            dto.setReceiverName("张小明");
            dto.setReceiverPhone("13800138000");
            dto.setReceiverAddress("上海");

            service.create(dto);

            // 不带 key：不做幂等预检查（selectOne 一次都不该调）
            verify(orderMapper, never()).selectOne(any());
            ArgumentCaptor<Order> cap = ArgumentCaptor.forClass(Order.class);
            verify(orderMapper).insert(cap.capture());
            assertThat(cap.getValue().getClientRequestId()).isNull();
        } finally {
            UserContext.clear();
        }
    }
}
