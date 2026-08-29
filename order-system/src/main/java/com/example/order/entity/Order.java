package com.example.order.entity;

  import com.baomidou.mybatisplus.annotation.IdType;
  import com.baomidou.mybatisplus.annotation.TableId;
  import com.baomidou.mybatisplus.annotation.TableName;
  import lombok.Data;

  import java.math.BigDecimal;
  import java.time.LocalDateTime;

  @Data
  @TableName("t_order")
  public class Order {

      @TableId(type = IdType.AUTO)
      private Long id;

      private String orderNo;

      private Long userId;

      private BigDecimal totalAmount;

      private String status;

      private String receiverName;

      private String receiverPhone;

      private String receiverAddress;

      /** 客户端幂等键（可选）：t_order.client_request_id，唯一索引防网络重试重复下单 */
      private String clientRequestId;

      /**
       * 下单请求指纹：创建订单时把「收货人/收货电话/收货地址 + 商品明细(productId:quantity)」落成一份
       * 不可变的 JSON 快照。幂等回放时直接比对新请求算出的指纹与库里的指纹——不依赖购物车是否还在
       * （下单成功后购物车已被删，旧的比对逻辑因此失效，只能比收货信息）。
       */
      private String requestFingerprint;

      private Integer deleted;

      private LocalDateTime createTime;

      private LocalDateTime updateTime;
  }