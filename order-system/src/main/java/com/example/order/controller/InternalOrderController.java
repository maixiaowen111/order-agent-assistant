package com.example.order.controller;

import com.example.order.common.Result;
import com.example.order.exception.BusinessException;
import com.example.order.service.OrderService;
import com.example.order.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 内部接口（给 order-agent-assistant 调用）。
 * 与对外接口的区别：
 *   ① 走 /internal/**，不经过登录拦截器（agent 没有用户 Token）
 *   ② 靠 X-Internal-Key 密钥头做服务间鉴权（对外的接口靠用户 JWT）
 * 业务规则全部复用 OrderService，保证 agent 和前端看到的行为一致。
 */
@RestController
@RequestMapping("/internal/order")
@RequiredArgsConstructor
public class InternalOrderController {

    private final OrderService orderService;

    @Value("${internal-api.key}")
    private String internalKey;

    @GetMapping("/byOrderNo")
    public Result<OrderVO> getByOrderNo(@RequestParam("orderNo") String orderNo,
                                        @RequestHeader(value = "X-Internal-Key", required = false) String key) {
        checkKey(key);
        return Result.success(orderService.getByOrderNo(orderNo));
    }

    @PostMapping("/cancel")
    public Result<Map<String, Object>> cancel(@RequestParam("orderNo") String orderNo,
                                              @RequestHeader(value = "X-Internal-Key", required = false) String key) {
        checkKey(key);
        return Result.success(orderService.cancelByOrderNo(orderNo));
    }

    @PostMapping("/updateAddress")
    public Result<OrderVO> updateAddress(@RequestParam("orderNo") String orderNo,
                                         @RequestParam("address") String address,
                                         @RequestHeader(value = "X-Internal-Key", required = false) String key) {
        checkKey(key);
        return Result.success(orderService.updateAddress(orderNo, address));
    }

    private void checkKey(String key) {
        if (key == null || !internalKey.equals(key)) {
            throw new BusinessException(401, "内部接口密钥错误");
        }
    }
}
