
  package com.example.order.vo;

  import lombok.Data;

  import java.math.BigDecimal;
  import java.time.LocalDateTime;
  import java.util.List;

  @Data
  public class OrderVO {

      private Long id;

      private String orderNo;

      private BigDecimal totalAmount;

      private String status;

      private String receiverName;

      private String receiverPhone;

      private String receiverAddress;

      private List<OrderItemVO> items;

      private LocalDateTime createTime;

      @Data
      public static class OrderItemVO {
          private Long productId;
          private String productName;
          private BigDecimal productPrice;
          private Integer quantity;
          private BigDecimal totalPrice;
      }
  }