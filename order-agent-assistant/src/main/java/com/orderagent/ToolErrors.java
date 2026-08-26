package com.orderagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具失败的统一 JSON 外壳。模型看到 success=false 就知道"这活没干成"，
 * 再根据 errorCode / message 决定下一步（告诉用户 or 换方案 or 再试）。
 * 所有工具、OrderSystemApiClient 都用这一个方法生成失败结果，格式只维护一份。
 */
public final class ToolErrors {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolErrors() {
    }

    public static String fail(String errorCode, String message) {
        ObjectNode node = JSON.createObjectNode();
        node.put("success", false);
        node.put("errorCode", errorCode);
        node.put("message", message);
        return node.toString();
    }
}
