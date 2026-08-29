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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
        Long userId = UserContext.getUserId();

        // 0. 幂等键（可选）：同一 clientRequestId 只允许一个订单。
        //    客户端网络重试/重复提交时，先校验请求内容与已有订单一致再回放——同键不同内容直接报参数冲突。
        //    并发竞态：两个同键请求同时越过预检查 → 见下方 try——订单先落库，唯一键
        //    uk_client_request_id 在扣任何库存之前就生效，输家撞键时库存一毫未动，干净回放赢家订单。
        String clientRequestId = dto.getClientRequestId();
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            Order existing = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                    .eq(Order::getClientRequestId, clientRequestId));
            if (existing != null) {
                checkOwner(existing, userId);   // 幂等键也是资源：不能拿别人的键回放别人的订单
                assertSameRequestContent(dto, existing);   // 同键不同内容 → 400 参数冲突
                log.info("幂等命中（下单）：clientRequestId={} 已下单，回放订单 orderNo={}",
                        clientRequestId, existing.getOrderNo());
                return buildDetail(existing);
            }
        }

        // 1. 查出要购买的购物车记录——必须全部属于当前用户。
        //    cartId 是客户端传入的，不能只 selectBatchIds 查出来就用：那会拿到别人的购物车
        //    记录来下单、并删掉对方的购物车（越权）。按 userId 过滤后数量对不上
        //    （有 cartId 不存在或不属于自己）→ 直接 403，绝不拿部分记录继续下单。
        List<Long> requestedCartIds = dto.getCartIds();
        long distinctCartCount = requestedCartIds.stream().distinct().count();
        List<Cart> cartList = cartMapper.selectList(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId)
                .in(Cart::getId, requestedCartIds));
        if (cartList.size() != distinctCartCount) {
            throw new BusinessException(403, "部分购物车记录不存在或不属于当前用户");
        }

        // 1.1 数量二次校验：不能只依赖加购时的校验（数据可能被绕过/是历史脏数据）。
        //     尤其防负数进入库存公式 stock - quantity——quantity=-5 会让库存反向 +5。
        for (Cart cart : cartList) {
            if (cart.getQuantity() == null || cart.getQuantity() < 1 || cart.getQuantity() > 999) {
                throw new BusinessException(400, "购物车商品数量非法，请调整后重新下单");
            }
        }

        // 2. 逐个校验商品 + 构建订单快照 + 计算总金额。
        //    这一遍只读不写：商品下架/库存不足在这里快速失败，此刻一个副作用都没有。
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

        // 2. 生成订单号
        String orderNo = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().substring(0, 6);

        try {
            // 3. 先落订单——唯一键 uk_client_request_id 在此生效，且发生在扣任何库存之前。
            //    并发同键的输家在这里就撞键抛 DuplicateKeyException，此时库存一根毛都没动。
            Order order = new Order();
            order.setOrderNo(orderNo);
            order.setUserId(userId);
            order.setTotalAmount(totalAmount);
            order.setStatus("WAIT_PAY");
            order.setReceiverName(dto.getReceiverName());
            order.setReceiverPhone(dto.getReceiverPhone());
            order.setReceiverAddress(dto.getReceiverAddress());
            order.setClientRequestId(clientRequestId);   // 可为 null（不带幂等键的旧客户端）
            // 请求指纹：收货信息 + 商品明细落成不可变快照，幂等回放直接比它（不依赖购物车是否还在）
            order.setRequestFingerprint(buildRequestFingerprint(dto, cartList));
            orderMapper.insert(order);

            // 4. 逐个原子扣库存 + 落订单明细。扣减放在订单之后：若原子扣减失败（超卖竞争
            //    affected=0），BusinessException 上抛 → 整个事务回滚，订单不落库，
            //    绝不会有"库存没扣到却生成了订单"的中间态。
            for (OrderItem item : orderItems) {
                item.setOrderId(order.getId());
                item.setOrderNo(orderNo);
                // 原子扣减：UPDATE ... SET stock = stock - ? WHERE stock >= ?
                // 两个并发请求同时买最后一件：只有一个人 affected=1，另一个 affected=0 被拦下。
                // 不再"先查再写"——查到的 stock 在并发下可能是旧的，等写的时候已经不够了。
                int affected = productMapper.deductStock(item.getProductId(), item.getQuantity());
                if (affected == 0) {
                    throw new BusinessException(400,
                            "商品【" + item.getProductName() + "】库存不足，请稍后重试");
                }
                orderItemMapper.insert(item);
            }

            // 5. 删除已下单的购物车记录
            cartMapper.deleteBatchIds(dto.getCartIds());

            log.info("订单创建成功，orderNo={}, totalAmount={}", orderNo, totalAmount);

            // 6. 构建 VO + 事务内插入事件记录（和订单同事务，保证不丢）
            OrderVO orderVO = buildOrderVO(order, orderItems);
            insertEventRecords(orderVO);

            return orderVO;
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一键在扣库存之前就被执行，输家撞键时没扣过任何库存，没有副作用可补偿。
            // 在事务方法内 catch（异常不穿事务边界 → 不会 UnexpectedRollbackException），
            // catch 住后事务正常提交——但本事务此刻只有只读操作，提交的就是个空事务。
            // 回查回放赢家已落库的订单。
            if (clientRequestId != null && !clientRequestId.isBlank()) {
                Order existing = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                        .eq(Order::getClientRequestId, clientRequestId));
                if (existing != null) {
                    checkOwner(existing, userId);
                    assertSameRequestContent(dto, existing);   // 同键不同内容 → 400 参数冲突
                    log.info("并发撞唯一键，回放赢家订单 orderNo={}, clientRequestId={}",
                            existing.getOrderNo(), clientRequestId);
                    return buildDetail(existing);
                }
            }
            throw e;
        }
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
     * 幂等回放前的参数冲突校验：同 clientRequestId 的下单请求内容必须与已有订单一致，
     * 否则报 400 参数冲突——客户端不能拿同一个幂等键去下内容不同的单，否则回放会
     * 返回一份"和他这次请求对不上"的订单，掩盖掉真实意图。
     *
     * 收货信息逐字段比对：有一方没填就跳过（无法判断就不拦；正常请求收货信息 @NotBlank 必填，
     * 缺失只出现在测试/畸形请求，不拦不算放水）。
     * 商品明细比对新请求购物车：合法重试的购物车已被下单事务删掉、查不到 → 跳过比对，
     * 收货信息对得上就放行；新请求换了商品 → 购物车还在 → 商品指纹不一致 → 报冲突。
     */
    private void assertSameRequestContent(CreateOrderDTO dto, Order existing) {
        compareReceiverField("收货人", dto.getReceiverName(), existing.getReceiverName());
        compareReceiverField("收货电话", dto.getReceiverPhone(), existing.getReceiverPhone());
        compareReceiverField("收货地址", dto.getReceiverAddress(), existing.getReceiverAddress());

        // 商品内容比对：优先比"创建时保存的请求指纹"——它是不可变快照，不依赖购物车是否还在。
        List<Cart> cartList = cartMapper.selectBatchIds(dto.getCartIds());
        if (CollectionUtils.isEmpty(cartList)) {
            return;   // 合法重试：购物车已删，无法比对商品，收货信息对得上就放行
        }
        String storedFingerprint = existing.getRequestFingerprint();
        if (storedFingerprint != null) {
            // 有指纹：直接比对新请求算出的指纹。同 key 不同商品/数量/收货信息 → 400 参数冲突。
            if (!buildRequestFingerprint(dto, cartList).equals(storedFingerprint)) {
                throw new BusinessException(400,
                        "clientRequestId 已用于不同的下单内容，请更换幂等键或核对参数");
            }
            return;
        }
        // 老订单（升级前创建）没有指纹：退回按订单明细比对，兼容历史数据。
        LambdaQueryWrapper<OrderItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderItem::getOrderId, existing.getId());
        List<OrderItem> existingItems = orderItemMapper.selectList(wrapper);
        if (CollectionUtils.isEmpty(existingItems)) {
            return;   // 老订单无明细，无从比对
        }
        if (!itemFingerprint(cartList).equals(orderItemsFingerprint(existingItems))) {
            throw new BusinessException(400,
                    "clientRequestId 已用于不同的下单内容（商品不一致），请更换幂等键或核对参数");
        }
    }

    /**
     * 下单请求指纹：收货信息 + 商品明细（productId:quantity 排序拼接）合并成一份 JSON 快照。
     * 创建订单时落库，幂等回放时直接比对新请求算出的指纹——这份快照在创建时固定，
     * 不依赖购物车是否还在（下单成功后购物车已被删）。
     */
    private String buildRequestFingerprint(CreateOrderDTO dto, List<Cart> cartList) {
        try {
            Map<String, Object> fp = new LinkedHashMap<>();
            fp.put("receiverName", dto.getReceiverName());
            fp.put("receiverPhone", dto.getReceiverPhone());
            fp.put("receiverAddress", dto.getReceiverAddress());
            fp.put("items", itemFingerprint(cartList));
            return objectMapper.writeValueAsString(fp);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("下单请求指纹序列化失败", e);
        }
    }

    private void compareReceiverField(String label, String dtoValue, String existingValue) {
        if (dtoValue != null && existingValue != null && !dtoValue.equals(existingValue)) {
            throw new BusinessException(400,
                    "clientRequestId 已用于不同的下单内容（" + label + "不一致），请更换幂等键或核对参数");
        }
    }

    /** 商品内容指纹：productId:quantity 排序后拼接，顺序无关地比对"买了什么、各多少" */
    private String itemFingerprint(List<Cart> carts) {
        return carts.stream()
                .sorted(Comparator.comparing(Cart::getProductId))
                .map(c -> c.getProductId() + ":" + c.getQuantity())
                .collect(Collectors.joining(","));
    }

    private String orderItemsFingerprint(List<OrderItem> items) {
        return items.stream()
                .sorted(Comparator.comparing(OrderItem::getProductId))
                .map(i -> i.getProductId() + ":" + i.getQuantity())
                .collect(Collectors.joining(","));
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
