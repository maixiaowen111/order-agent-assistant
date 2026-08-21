  package com.example.order.entity;

  import com.baomidou.mybatisplus.annotation.IdType;
  import com.baomidou.mybatisplus.annotation.TableId;
  import com.baomidou.mybatisplus.annotation.TableName;
  import lombok.Data;

  import java.math.BigDecimal;
  import java.time.LocalDateTime;

  @Data
  @TableName("t_product")
  public class Product {

      @TableId(type = IdType.AUTO)
      private Long id;

      private String name;

      private String description;

      private BigDecimal price;

      private Integer stock;

      private String category;

      private String image;

      private Integer status;

      private Integer deleted;

      private LocalDateTime createTime;

      private LocalDateTime updateTime;
  }