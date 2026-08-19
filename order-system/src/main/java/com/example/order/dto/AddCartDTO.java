 package com.example.order.dto;

  import lombok.Data;

  import javax.validation.constraints.NotNull;

  @Data
  public class AddCartDTO {

      /** 加入购物车时必填，更新数量时不需要 */
      private Long productId;

      @NotNull(message = "数量不能为空")
      private Integer quantity;
  }
