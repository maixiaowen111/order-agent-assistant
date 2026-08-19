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

      private Integer deleted;

      private LocalDateTime createTime;

      private LocalDateTime updateTime;
  }