package com.example.order.enums;

/**
 * 事件类型枚举
 *
 * 为什么用枚举而不是字符串常量？
 *   1. 编译期检查 —— 写 EventType.POINT 编译器直接报错，写 "POINT" 要等到运行时才发现
 *   2. 集中管理 —— 新增事件类型只改这一个文件
 *   3. switch 语句友好 —— IDEA 自动补全所有分支，不会漏
 *   4. 类型安全 —— 方法参数写 EventType 类型，不会传入 "UNKNOWN_XXX"
 *
 * 使用方式：
 *   insertEvent(orderVO, EventType.POINTS, data)
 *   别再用 insertEvent(orderVO, "POINTS", data)
 */
public enum EventType {

    POINTS("积分赠送"),
    SMS("短信通知"),
    NOTIFY("Push推送"),
    REFUND("退款通知");

    private final String description;

    EventType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
