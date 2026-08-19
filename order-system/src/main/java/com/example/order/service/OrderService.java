package com.example.order.service;

  import com.example.order.dto.CreateOrderDTO;
  import com.example.order.vo.OrderVO;

  import java.util.List;
  import java.util.Map;

  public interface OrderService {

      /**
       * 创建订单
       */
      OrderVO create(CreateOrderDTO dto);

      /**
       * 订单详情
       */
      OrderVO detail(Long id);

      /**
       * 我的订单列表
       */
      List<OrderVO> myOrders();

      /**
       * 支付订单
       */
      void pay(Long orderId);

      /**
       * 取消订单
       */
      void cancel(Long orderId);

      /**
       * 按订单号查订单（内部接口用）
       */
      OrderVO getByOrderNo(String orderNo);

      /**
       * 按订单号取消（内部接口用），返回取消结果（是否触发退款）
       */
      Map<String, Object> cancelByOrderNo(String orderNo);
  }
