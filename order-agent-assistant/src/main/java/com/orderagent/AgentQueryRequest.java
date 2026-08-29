package com.orderagent;

/**
 * POST /query 的请求体。用 DTO 代替裸 Map<String,String>：
 * 字段有了名字和校验边界（q 必填、有最大长度），Controller 里逐条校验。
 * 校验放 Controller（见 validateQuery），不依赖额外校验框架，直观、单测能覆盖。
 */
public record AgentQueryRequest(String q, String sessionId) {
}
