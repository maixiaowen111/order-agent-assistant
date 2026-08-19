
  package com.example.order.entity;

  import com.baomidou.mybatisplus.annotation.IdType;
  import com.baomidou.mybatisplus.annotation.TableId;
  import com.baomidou.mybatisplus.annotation.TableName;
  import lombok.Data;

  import java.math.BigDecimal;
  import java.time.LocalDateTime;

  @Data
  @TableName("t_order_item")
  public class OrderItem {

      @TableId(type = IdType.AUTO)
      private Long id;

      private Long orderId;

      private String orderNo;

      private Long productId;

      private String productName;

      private BigDecimal productPrice;

      private Integer quantity;

      private BigDecimal totalPrice;

      private LocalDateTime createTime;
  }