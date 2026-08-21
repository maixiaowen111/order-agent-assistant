package com.orderagent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 只读工具：查商品库存。
 * 支持两种入参（二选一）：
 *   productId —— 已知商品 id 直接查；
 *   keyword   —— 只知道商品名时按名搜索，返回匹配商品的 id/名称/库存。
 * 用户口语里通常只有商品名，所以 description 里强调让模型用 keyword 先搜出 id。
 */
@Component
public class QueryStockTool implements Tool {

    private final OrderSystemApiClient api;

    public QueryStockTool(OrderSystemApiClient api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "query_product_stock";
    }

    @Override
    public String description() {
        return "查商品库存。参数二选一：productId（商品 id，数字）直接查该商品库存；"
                + "keyword（商品名称关键字）按名称搜索并返回匹配商品的 id、名称和库存。"
                + "用户只说商品名、不知道 id 时，用 keyword 先搜出 id 再查。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> productId = Map.<String, Object>of(
                "type", "integer",
                "description", "商品 id（数字），与 keyword 二选一");
        Map<String, Object> keyword = Map.<String, Object>of(
                "type", "string",
                "description", "商品名称关键字，与 productId 二选一");
        Map<String, Object> properties = Map.<String, Object>of(
                "productId", productId,
                "keyword", keyword);
        return Map.<String, Object>of(
                "type", "object",
                "properties", properties);
    }

    @Override
    public String run(Map<String, Object> args) {
        String idStr = args.get("productId") == null ? "" : String.valueOf(args.get("productId")).trim();
        String kw = args.get("keyword") == null ? "" : String.valueOf(args.get("keyword")).trim();
        boolean hasId = !idStr.isEmpty();
        boolean hasKw = !kw.isEmpty();

        if (hasId && hasKw) {
            return "错误：productId 和 keyword 只能传一个";
        }
        if (hasId) {
            try {
                return api.queryProductStock(Long.parseLong(idStr));
            } catch (NumberFormatException e) {
                return "错误：productId 必须是数字，收到 " + idStr;
            }
        }
        if (hasKw) {
            return api.queryProductSearch(kw);
        }
        return "错误：请提供 productId（商品 id 数字）或 keyword（商品名称关键字）";
    }
}
