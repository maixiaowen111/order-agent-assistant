package com.example.order.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
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

    /**
     * 客户端幂等键（可选）：防网络重试产生重复订单。
     * 客户端在「一次下单尝试」里生成同一个 UUID，重试时原样带上——
     * 服务端同一键只落一个订单，后续请求回放已有订单，不再扣库存/重复下单。
     * 不带该字段的旧客户端行为不变（t_order 唯一索引允许多个 NULL）。
     */
    @Size(max = 64, message = "clientRequestId 不能超过 64 字")
    private String clientRequestId;
}
