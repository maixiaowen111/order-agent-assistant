package com.example.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.order.dto.CreateOrderDTO;
import com.example.order.entity.Cart;
import com.example.order.entity.Order;
import com.example.order.entity.OrderItem;
import com.example.order.entity.Product;
import com.example.order.exception.BusinessException;
import com.example.order.entity.EventRecord;
import com.example.order.enums.EventType;
import com.example.order.mapper.CartMapper;
import com.example.order.context.UserContext;
import com.example.order.mapper.EventRecordMapper;
import com.example.order.mapper.OrderItemMapper;
import com.example.order.mapper.OrderMapper;
import com.example.order.mapper.ProductMapper;
import com.example.order.service.OrderEventService;
import com.example.order.service.OrderService;
import com.example.order.util.DesensitizeUtil;
import com.example.order.vo.OrderVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final CartMapper cartMapper;
    private final ProductMapper productMapper;
    private final OrderEventService orderEventService;
    private final EventRecordMapper eventRecordMapper;
    private final ObjectMapper objectMapper;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO create(CreateOrderDTO dto) {
        // 1. 查出要购买的购物车记录
        List<Cart> cartList = cartMapper.selectBatchIds(dto.getCartIds());
        if (CollectionUtils.isEmpty(cartList)) {
            throw new BusinessException(400, "购物车记录不存在");
        }

        // 2. 逐个校验商品 + 原子扣库存 + 计算总金额
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();

        for (Cart cart : cartList) {
            // 读一次用于校验商品状态 + 构建订单快照（价格/名称）
            Product product = productMapper.selectById(cart.getProductId());
            if (Objects.isNull(product) || product.getStatus() != 1) {
                throw new BusinessException(400,
                        "商品【" + (product != null ? product.getName() : "未知") + "】已下架或不存在");
            }

            // 快速失败：明显不够的直接拦，不用等原子扣减
            if (product.getStock() < cart.getQuantity()) {
                throw new BusinessException(400,
                        "商品【" + product.getName() + "】库存不足，剩余：" + product.getStock());
            }

            // 原子扣减：UPDATE ... SET stock = stock - ? WHERE stock >= ?
            // 两个并发请求同时买最后一件：只有一个人 affected=1，另一个 affected=0 被拦下。
            // 不再"先查再写"——查到的 stock 在并发下可能是旧的，等写的时候已经不够了。
            int affected = productMapper.deductStock(product.getId(), cart.getQuantity());
            if (affected == 0) {
                throw new BusinessException(400,
                        "商品【" + product.getName() + "】库存不足，请稍后重试");
            }

            // 计算小计（快照用下单时的价格）
            BigDecimal itemTotal = product.getPrice()
                    .multiply(BigDecimal.valueOf(cart.getQuantity()));
            totalAmount = totalAmount.add(itemTotal);

            // 构建订单详情（快照）
            OrderItem item = new OrderItem();
            item.setProductId(product.getId());
            item.setProductName(product.getName());
            item.setProductPrice(product.getPrice());
            item.setQuantity(cart.getQuantity());
            item.setTotalPrice(itemTotal);
            orderItems.add(item);
        }

        // 3. 生成订单号
        String orderNo = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().substring(0, 6);

        // 4. 保存订单
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setUserId(UserContext.getUserId());
        order.setTotalAmount(totalAmount);
        order.setStatus("WAIT_PAY");
        order.setReceiverName(dto.getReceiverName());
        order.setReceiverPhone(dto.getReceiverPhone());
        order.setReceiverAddress(dto.getReceiverAddress());
        orderMapper.insert(order);

        // 5. 保存订单详情
        for (OrderItem item : orderItems) {
            item.setOrderId(order.getId());
            item.setOrderNo(orderNo);
            orderItemMapper.insert(item);
        }

        // 6. 删除已下单的购物车记录
        cartMapper.deleteBatchIds(dto.getCartIds());

        log.info("订单创建成功，orderNo={}, totalAmount={}", orderNo, totalAmount);

        // 7. 构建 VO
        OrderVO orderVO = buildOrderVO(order, orderItems);

        // 8. 事务内插入事件记录（和订单同事务，保证不丢）
        insertEventRecords(orderVO);

        return orderVO;
    }


    @Override
    public OrderVO detail(Long id, Long userId) {
        Order order = requireOrder(id);
        checkOwner(order, userId);   // 非空时校验归属；空跳过（MCP 只读路径）

        return buildDetail(order);
    }

    @Override
    public List<OrderVO> myOrders() {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, UserContext.getUserId())
               .orderByDesc(Order::getCreateTime);
        List<Order> orders = orderMapper.selectList(wrapper);

        return orders.stream()
                .map(this::buildDetail)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pay(Long orderId, Long userId) {
        requireUser(userId);
        Order order = requireOrder(orderId);
        checkOwner(order, userId);   // 只能支付自己的订单

        if (!"WAIT_PAY".equals(order.getStatus())) {
            throw new BusinessException(400, "订单状态不允许支付，当前状态：" + order.getStatus());
        }

        // 模拟支付成功
        order.setStatus("PAID");
        orderMapper.updateById(order);

        log.info("订单支付成功（模拟），orderNo={}", order.getOrderNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long orderId, Long userId) {
        requireUser(userId);
        Order order = requireOrder(orderId);
        checkOwner(order, userId);   // 只能取消自己的订单

        String status = order.getStatus();
        // 已取消 → 幂等拒绝（友好提示）；其他非法状态 → 明确拒绝
        if ("CANCELLED".equals(status)) {
            throw new BusinessException(400, "订单已是取消状态，无需重复操作");
        }
        if (!"WAIT_PAY".equals(status) && !"PAID".equals(status)) {
            throw new BusinessException(400, "当前状态（" + status + "）不允许取消");
        }

        // 原子状态流转：只有还是 WAIT_PAY/PAID 才置 CANCELLED。
        // 两个并发取消同时读到 WAIT_PAY → 只有一个 affected=1，另一个 affected=0 被拦下，
        // 库存恢复天然只执行一次——幂等由「状态守卫」保证，而不是靠"再查一次状态"。
        int affected = orderMapper.markCancelled(orderId);
        if (affected == 0) {
            throw new BusinessException(400, "订单状态已变化，请刷新后重试");
        }

        // 恢复库存：原子加，不先查后写（并发下查到的 stock 可能是旧的）
        LambdaQueryWrapper<OrderItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderItem::getOrderId, orderId);
        List<OrderItem> items = orderItemMapper.selectList(wrapper);
        for (OrderItem item : items) {
            productMapper.restoreStock(item.getProductId(), item.getQuantity());
        }

        // 已支付订单取消 → 触发退款（同事务落 REFUND 事件，交给事件调度器兜底）
        if ("PAID".equals(status)) {
            insertRefundEvent(order);
        }

        log.info("订单已取消，orderNo={}", order.getOrderNo());
    }

    @Override
    public OrderVO getByOrderNo(String orderNo, Long userId) {
        Order order = getOrderByNo(orderNo);
        checkOwner(order, userId);   // 非空时校验归属；空跳过（MCP 只读路径）
        // 内部读接口：收货信息脱敏后再给 agent（最小权限），用户端 detail() 仍返回完整地址
        return DesensitizeUtil.mask(detail(order.getId(), userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelByOrderNo(String orderNo, Long userId) {
        requireUser(userId);
        Order order = getOrderByNo(orderNo);
        checkOwner(order, userId);   // 只能取消自己的订单
        boolean refundTriggered = "PAID".equals(order.getStatus());
        cancel(order.getId(), userId);
        return Map.of(
                "orderNo", order.getOrderNo(),
                "status", "CANCELLED",
                "refundTriggered", refundTriggered
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO updateAddress(String orderNo, String newAddress, Long userId) {
        requireUser(userId);
        if (newAddress == null || newAddress.isBlank()) {
            throw new BusinessException(400, "收货地址不能为空");
        }
        if (newAddress.length() > 500) {
            throw new BusinessException(400, "收货地址过长（最多 500 字）");
        }
        Order order = getOrderByNo(orderNo);
        checkOwner(order, userId);   // 只能改自己的订单
        String status = order.getStatus();
        if ("CANCELLED".equals(status)) {
            throw new BusinessException(400, "订单已取消，不能修改收货地址");
        }
        if (!"WAIT_PAY".equals(status) && !"PAID".equals(status)) {
            throw new BusinessException(400, "当前状态（" + status + "）不允许修改收货地址");
        }
        order.setReceiverAddress(newAddress.trim());
        orderMapper.updateById(order);
        log.info("收货地址已更新，orderNo={}", orderNo);
        // 回显同样脱敏：agent 写地址时自己带着完整地址来，响应无需再把它完整返回
        return DesensitizeUtil.mask(detail(order.getId(), userId));
    }

    private Order getOrderByNo(String orderNo) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getOrderNo, orderNo);
        Order order = orderMapper.selectOne(wrapper);
        if (Objects.isNull(order)) {
            throw new BusinessException(404, "订单不存在：" + orderNo);
        }
        return order;
    }

    /** 查订单，不存在抛 404 */
    private Order requireOrder(Long id) {
        Order order = orderMapper.selectById(id);
        if (Objects.isNull(order)) {
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }

    /**
     * 归属校验（读路径）：userId 为空跳过（MCP 只读开发路径没有用户身份，数据由内部接口脱敏兜底），
     * 非空则必须是自己名下。写路径调用前先 requireUser 保证 userId 非空。
     */
    private void checkOwner(Order order, Long userId) {
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BusinessException(403, "无权访问该订单");
        }
    }

    /** 写操作必须知道"是谁在操作"——没有用户身份直接拒绝 */
    private void requireUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "缺少用户身份");
        }
    }

    /** 查订单详情行 + 构建 VO（内部复用：myOrders / detail / 回显都走这里） */
    private OrderVO buildDetail(Order order) {
        LambdaQueryWrapper<OrderItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderItem::getOrderId, order.getId());
        List<OrderItem> items = orderItemMapper.selectList(wrapper);
        return buildOrderVO(order, items);
    }

    /**
     * 事务内插入事件记录（Transactional Outbox 模式核心）
     *
     * INSERT 和订单在同一个事务里：要么都成功，要么都回滚
     * 不会出现"订单落库了但事件记录没插进去"的情况
     *
     * 每条事件记录独立，互不影响——积分失败不影响短信
     */
    private void insertEventRecords(OrderVO orderVO) {
        // ① 积分事件
        insertEvent(orderVO, EventType.POINTS, Map.of(
                "userId", UserContext.getUserId(),
                "amount", orderVO.getTotalAmount()
        ));

        // ② 短信事件
        insertEvent(orderVO, EventType.SMS, Map.of(
                "phone", orderVO.getReceiverPhone(),
                "receiverName", orderVO.getReceiverName()
        ));

        // ③ 推送通知事件
        insertEvent(orderVO, EventType.NOTIFY, Map.of(
                "userId", UserContext.getUserId(),
                "orderNo", orderVO.getOrderNo()
        ));

        log.info("事件记录已入库，orderNo={}, 事件数=3", orderVO.getOrderNo());
    }

    private void insertEvent(OrderVO orderVO, EventType eventType, Map<String, Object> data) {
        EventRecord record = new EventRecord();
        record.setOrderNo(orderVO.getOrderNo());
        record.setEventType(eventType.name());  // Enum → "POINTS" 字符串存入 DB
        try {
            record.setEventData(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("事件数据序列化失败", e);
        }
        record.setStatus("WAIT");
        record.setRetryCount(0);
        record.setMaxRetry(3);
        record.setNextRetryTime(LocalDateTime.now());
        eventRecordMapper.insert(record);
    }

    /** 已支付订单取消 → 事务内插退款事件，交给事件调度器兜底处理 */
    private void insertRefundEvent(Order order) {
        EventRecord record = new EventRecord();
        record.setOrderNo(order.getOrderNo());
        record.setEventType(EventType.REFUND.name());
        try {
            record.setEventData(objectMapper.writeValueAsString(Map.of(
                    "userId", order.getUserId(),
                    "amount", order.getTotalAmount(),
                    "phone", order.getReceiverPhone())));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("事件数据序列化失败", e);
        }
        record.setStatus("WAIT");
        record.setRetryCount(0);
        record.setMaxRetry(3);
        record.setNextRetryTime(LocalDateTime.now());
        eventRecordMapper.insert(record);
    }

    private OrderVO buildOrderVO(Order order, List<OrderItem> items) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setStatus(order.getStatus());
        vo.setReceiverName(order.getReceiverName());
        vo.setReceiverPhone(order.getReceiverPhone());
        vo.setReceiverAddress(order.getReceiverAddress());
        vo.setCreateTime(order.getCreateTime());

        List<OrderVO.OrderItemVO> itemVOs = items.stream()
                .map(item -> {
                    OrderVO.OrderItemVO itemVO = new OrderVO.OrderItemVO();
                    itemVO.setProductId(item.getProductId());
                    itemVO.setProductName(item.getProductName());
                    itemVO.setProductPrice(item.getProductPrice());
                    itemVO.setQuantity(item.getQuantity());
                    itemVO.setTotalPrice(item.getTotalPrice());
                    return itemVO;
                })
                .collect(Collectors.toList());
        vo.setItems(itemVOs);

        return vo;
    }
}
