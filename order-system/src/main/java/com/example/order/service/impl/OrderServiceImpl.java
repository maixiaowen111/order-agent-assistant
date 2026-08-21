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
import com.example.order.vo.OrderVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
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
      private final RedissonClient redissonClient;
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

          // 2. 逐个校验商品 + 计算总金额
          BigDecimal totalAmount = BigDecimal.ZERO;
          List<OrderItem> orderItems = new ArrayList<>();

          for (Cart cart : cartList) {
              // 锁前读一次（用于校验商品状态 + 快速失败）
              Product product = productMapper.selectById(cart.getProductId());

              // 校验商品是否存在、是否上架
              if (Objects.isNull(product) || product.getStatus() != 1) {
                  throw new BusinessException(400,
                          "商品【" + (product != null ? product.getName() : "未知") + "】已下架或不存在");
              }

              // ① 锁前快速检查（大部分不够的请求在这就拦住了，不需要等锁）
              if (product.getStock() < cart.getQuantity()) {
                  throw new BusinessException(400,
                          "商品【" + product.getName() + "】库存不足，剩余：" + product.getStock());
              }

              // 加分布式锁
              RLock lock = redissonClient.getLock("lock:product:" + product.getId());
              lock.lock();
              try {
                  // ② 锁内重新查一次——等锁期间库存可能被别的线程改了
                  product = productMapper.selectById(cart.getProductId());
                  if (product.getStock() < cart.getQuantity()) {
                      throw new BusinessException(400,
                              "商品【" + product.getName() + "】库存不足，剩余：" + product.getStock());
                  }

                  // 扣库存
                  product.setStock(product.getStock() - cart.getQuantity());
                  productMapper.updateById(product);
              } finally {
                  lock.unlock();
              }

              // 计算小计（用最新 product 数据）
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
      public OrderVO detail(Long id) {
          Order order = orderMapper.selectById(id);
          if (Objects.isNull(order)) {
              throw new BusinessException(404, "订单不存在");
          }

          LambdaQueryWrapper<OrderItem> wrapper = new LambdaQueryWrapper<>();
          wrapper.eq(OrderItem::getOrderId, id);
          List<OrderItem> items = orderItemMapper.selectList(wrapper);

          return buildOrderVO(order, items);
      }

      @Override
      public List<OrderVO> myOrders() {
          LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
          wrapper.eq(Order::getUserId, UserContext.getUserId())
                 .orderByDesc(Order::getCreateTime);
          List<Order> orders = orderMapper.selectList(wrapper);

          return orders.stream()
                  .map(order -> {
                      LambdaQueryWrapper<OrderItem> itemWrapper = new LambdaQueryWrapper<>();
                      itemWrapper.eq(OrderItem::getOrderId, order.getId());
                      List<OrderItem> items = orderItemMapper.selectList(itemWrapper);
                      return buildOrderVO(order, items);
                  })
                  .collect(Collectors.toList());
      }

      @Override
      @Transactional(rollbackFor = Exception.class)
      public void pay(Long orderId) {
          Order order = orderMapper.selectById(orderId);
          if (Objects.isNull(order)) {
              throw new BusinessException(404, "订单不存在");
          }
          if (!"WAIT_PAY".equals(order.getStatus())) {
              throw new BusinessException(400, "订单状态不允许支付，当前状态：" + order.getStatus());
          }

          // 模拟支付成功
          order.setStatus("PAID");
          orderMapper.updateById(order);

          log.info("订单支付成功，orderNo={}", order.getOrderNo());
      }

      @Override
      @Transactional(rollbackFor = Exception.class)
      public void cancel(Long orderId) {
          Order order = orderMapper.selectById(orderId);
          if (Objects.isNull(order)) {
              throw new BusinessException(404, "订单不存在");
          }
          String status = order.getStatus();
          // 已取消 → 幂等拒绝（友好提示）；其他非法状态 → 明确拒绝
          if ("CANCELLED".equals(status)) {
              throw new BusinessException(400, "订单已是取消状态，无需重复操作");
          }
          if (!"WAIT_PAY".equals(status) && !"PAID".equals(status)) {
              throw new BusinessException(400, "当前状态（" + status + "）不允许取消");
          }

          // 恢复库存
          LambdaQueryWrapper<OrderItem> wrapper = new LambdaQueryWrapper<>();
          wrapper.eq(OrderItem::getOrderId, orderId);
          List<OrderItem> items = orderItemMapper.selectList(wrapper);
          for (OrderItem item : items) {
              Product product = productMapper.selectById(item.getProductId());
              if (Objects.nonNull(product)) {
                  product.setStock(product.getStock() + item.getQuantity());
                  productMapper.updateById(product);
              }
          }

          order.setStatus("CANCELLED");
          orderMapper.updateById(order);

          // 已支付订单取消 → 触发退款（同事务落 REFUND 事件，交给事件调度器兜底）
          if ("PAID".equals(status)) {
              insertRefundEvent(order);
          }

          log.info("订单已取消，orderNo={}", order.getOrderNo());
      }

      @Override
      public OrderVO getByOrderNo(String orderNo) {
          Order order = getOrderByNo(orderNo);
          return detail(order.getId());
      }

      @Override
      @Transactional(rollbackFor = Exception.class)
      public Map<String, Object> cancelByOrderNo(String orderNo) {
          Order order = getOrderByNo(orderNo);
          boolean refundTriggered = "PAID".equals(order.getStatus());
          cancel(order.getId());
          return Map.of(
                  "orderNo", order.getOrderNo(),
                  "status", "CANCELLED",
                  "refundTriggered", refundTriggered
          );
      }

      @Override
      @Transactional(rollbackFor = Exception.class)
      public OrderVO updateAddress(String orderNo, String newAddress) {
          if (newAddress == null || newAddress.isBlank()) {
              throw new BusinessException(400, "收货地址不能为空");
          }
          if (newAddress.length() > 500) {
              throw new BusinessException(400, "收货地址过长（最多 500 字）");
          }
          Order order = getOrderByNo(orderNo);
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
          return detail(order.getId());
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