 package com.example.order.dto;

  import lombok.Data;

  import javax.validation.constraints.Max;
  import javax.validation.constraints.Min;
  import javax.validation.constraints.NotNull;
  import javax.validation.constraints.Positive;

  @Data
  public class AddCartDTO {

      /** 加入购物车时必填，更新数量时不需要 */
      @NotNull(message = "商品不能为空")
      @Positive(message = "商品ID必须为正数")
      private Long productId;

      /** 数量限制 [1,999]：0/负数会让库存公式 stock - quantity 反向加库存 */
      @NotNull(message = "数量不能为空")
      @Min(value = 1, message = "数量必须大于 0")
      @Max(value = 999, message = "数量不能超过 999")
      private Integer quantity;
  }
