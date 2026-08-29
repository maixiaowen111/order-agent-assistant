package com.example.order.service;

  import com.example.order.dto.CreateOrderDTO;
  import com.example.order.vo.OrderVO;

  import java.util.List;
  import java.util.Map;

  public interface OrderService {

      /**
       * 创建订单（购物车结算）
       */
      OrderVO create(CreateOrderDTO dto);

      /**
       * 订单详情。userId 非空时校验订单归属（只能看自己的订单），
       * 为空则跳过（MCP 只读开发路径没有用户身份，数据由内部接口脱敏兜底）。
       */
      OrderVO detail(Long id, Long userId);

      /**
       * 我的订单列表
       */
      List<OrderVO> myOrders();

      /**
       * 支付订单（模拟支付）。校验订单归属。
       */
      void pay(Long orderId, Long userId);

      /**
       * 取消订单。校验订单归属；库存原子恢复；并发下状态守卫保证只恢复一次。
       */
      void cancel(Long orderId, Long userId);

      /**
       * 按订单号查订单（内部接口用）。userId 非空时校验归属，为空跳过（MCP 只读）。
       */
      OrderVO getByOrderNo(String orderNo, Long userId);

      /**
       * 按订单号取消（内部接口用），返回取消结果（是否触发退款）。
       * userId 必传，校验订单归属——写操作不能没有"是谁在操作"。
       */
      Map<String, Object> cancelByOrderNo(String orderNo, Long userId);

      /**
       * 修改收货地址（内部接口用）。业务边界：已取消订单不可改。
       * 改地址不触发退款/库存变化，无需写 Outbox 事件。userId 必传，校验订单归属。
       */
      OrderVO updateAddress(String orderNo, String newAddress, Long userId);
  }
