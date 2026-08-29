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
 *   ③ X-User-Id 是「当前在操作订单的用户」——agent 在 /query 路径已用 JWT 验过这个人，
 *      这里把它透传过来，order-system 据此校验订单归属（X-Internal-Key 只能证明"是 agent 在调"，
 *      不能当用户身份）。
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
                                        @RequestHeader(value = "X-Internal-Key", required = false) String key,
                                        @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        checkKey(key);
        // 读路径：带了用户就校验归属（web 路径必带）；不带则放行（MCP 只读开发路径，
        // 数据已脱敏 + 有 X-Internal-Key 兜底）。写操作不允许这样，见下面两个方法。
        return Result.success(orderService.getByOrderNo(orderNo, userId));
    }

    @PostMapping("/cancel")
    public Result<Map<String, Object>> cancel(@RequestParam("orderNo") String orderNo,
                                              @RequestHeader(value = "X-Internal-Key", required = false) String key,
                                              @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        checkKey(key);
        requireUser(userId);   // 写操作没有"是谁在操作"直接拒绝，绝不放过无主订单
        return Result.success(orderService.cancelByOrderNo(orderNo, userId));
    }

    @PostMapping("/updateAddress")
    public Result<OrderVO> updateAddress(@RequestParam("orderNo") String orderNo,
                                         @RequestParam("address") String address,
                                         @RequestHeader(value = "X-Internal-Key", required = false) String key,
                                         @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        checkKey(key);
        requireUser(userId);
        return Result.success(orderService.updateAddress(orderNo, address, userId));
    }

    private void checkKey(String key) {
        if (key == null || !internalKey.equals(key)) {
            throw new BusinessException(401, "内部接口密钥错误");
        }
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "缺少用户身份");
        }
    }
}
