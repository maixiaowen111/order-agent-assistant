
  package com.example.order.vo;

  import lombok.Data;

  import java.math.BigDecimal;
  import java.time.LocalDateTime;

  @Data
  public class ProductVO {

      private Long id;

      private String name;

      private String description;

      private BigDecimal price;

      private Integer stock;

      private String category;

      private String image;

      private Integer status;

      private LocalDateTime createTime;
  }
