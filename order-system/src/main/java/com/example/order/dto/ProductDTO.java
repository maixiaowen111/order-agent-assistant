 package com.example.order.dto;

  import lombok.Data;

  import javax.validation.constraints.DecimalMin;
  import javax.validation.constraints.Min;
  import javax.validation.constraints.NotBlank;
  import javax.validation.constraints.NotNull;
  import java.math.BigDecimal;

  @Data
  public class ProductDTO {

      @NotBlank(message = "商品名称不能为空")
      private String name;

      private String description;

      @NotNull(message = "商品价格不能为空")
      @DecimalMin(value = "0.01", message = "商品价格必须大于 0")
      private BigDecimal price;

      @NotNull(message = "库存数量不能为空")
      @Min(value = 0, message = "库存不能为负数")
      private Integer stock;

      private String category;

      private String image;
  }