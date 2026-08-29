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

      private Integer deleted;

      private LocalDateTime createTime;

      private LocalDateTime updateTime;
  }