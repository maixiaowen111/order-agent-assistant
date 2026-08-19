 package com.example.order.dto;

  import lombok.Data;

  import javax.validation.constraints.NotBlank;
  import javax.validation.constraints.NotNull;
  import java.math.BigDecimal;

  @Data
  public class ProductDTO {

      @NotBlank(message = "商品名称不能为空")
      private String name;

      private String description;

      @NotNull(message = "商品价格不能为空")
      private BigDecimal price;

      @NotNull(message = "库存数量不能为空")
      private Integer stock;

      private String category;
  }