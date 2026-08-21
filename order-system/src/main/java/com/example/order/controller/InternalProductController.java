package com.example.order.controller;

import com.example.order.common.Result;
import com.example.order.exception.BusinessException;
import com.example.order.service.ProductService;
import com.example.order.vo.ProductVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 内部商品接口（给 order-agent-assistant 调用）。
 * 鉴权与 /internal/order 一致：靠 X-Internal-Key 密钥头，不经过用户 JWT 拦截器。
 * 业务规则全部复用 ProductService，agent 和前端看到的行为一致。
 */
@RestController
@RequestMapping("/internal/product")
@RequiredArgsConstructor
public class InternalProductController {

    private final ProductService productService;

    @Value("${internal-api.key}")
    private String internalKey;

    /** 按商品 id 查库存。用 detail（含 Redis 缓存），能查下架商品，只挡已删除。 */
    @GetMapping("/stock")
    public Result<Map<String, Object>> stock(@RequestParam("productId") Long productId,
                                             @RequestHeader(value = "X-Internal-Key", required = false) String key) {
        checkKey(key);
        return Result.success(toMap(productService.detail(productId)));
    }

    /** 按商品名模糊搜索（含下架），最多 5 条，让模型先按名找到商品 id。 */
    @GetMapping("/search")
    public Result<List<Map<String, Object>>> search(@RequestParam("name") String name,
                                                    @RequestHeader(value = "X-Internal-Key", required = false) String key) {
        checkKey(key);
        return Result.success(productService.search(name, 5).stream()
                .map(this::toMap)
                .collect(Collectors.toList()));
    }

    private Map<String, Object> toMap(ProductVO vo) {
        Map<String, Object> m = new HashMap<>();
        m.put("productId", vo.getId());
        m.put("name", vo.getName());
        m.put("price", vo.getPrice());
        m.put("stock", vo.getStock());
        m.put("status", vo.getStatus());
        return m;
    }

    private void checkKey(String key) {
        if (key == null || !internalKey.equals(key)) {
            throw new BusinessException(401, "内部接口密钥错误");
        }
    }
}
