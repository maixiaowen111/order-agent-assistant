 package com.example.order.dto;

  import lombok.Data;

  import javax.validation.constraints.NotBlank;
  import javax.validation.constraints.NotEmpty;
  import java.util.List;

  @Data
  public class CreateOrderDTO {

      /** 要购买的购物车记录ID列表 */
      @NotEmpty(message = "购物车记录不能为空")
      private List<Long> cartIds;

      @NotBlank(message = "收货人不能为空")
      private String receiverName;

      @NotBlank(message = "收货电话不能为空")
      private String receiverPhone;

      @NotBlank(message = "收货地址不能为空")
      private String receiverAddress;
  }