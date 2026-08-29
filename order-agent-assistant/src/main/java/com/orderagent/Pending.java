package com.orderagent;

/**
 * 一次被闸门拦下的写提议（/approve 要批准的东西）。
 * toolName + 参数指纹：人工批准只对这个"工具名 + 特定参数"生效一次。
 */
public record Pending(String toolName, String fingerprint) {
}
