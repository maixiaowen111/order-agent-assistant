package com.example.order.vo;

  import lombok.Data;

  import java.math.BigDecimal;
  import java.time.LocalDateTime;

  @Data
  public class CartVO {

      private Long id;

      private Long productId;

      private String productName;

      private BigDecimal productPrice;

      private Integer quantity;

      private BigDecimal totalPrice;

      private LocalDateTime createTime;
  }