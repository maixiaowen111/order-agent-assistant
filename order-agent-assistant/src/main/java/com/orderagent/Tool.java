package com.orderagent;

import java.util.Map;

/**
 * 一个工具 = 模型的一只手。
 * 前三个方法(name/description/inputSchema)是"给模型看的"，
 * 决定模型会不会用、怎么用；run 是"面向数据的"，真正干活，模型看不见。
 */
public interface Tool {

    String name();

    /** 模型靠它决定"该不该用、什么时候用"——这是调工具最常改的地方。 */
    String description();

    /** 参数格式，给模型看。 */
    Map<String, Object> inputSchema();

    /** 真正执行：查库 / 改数据。参数不能全信，要自己兜住。 */
    String run(Map<String, Object> args);

    /** 只读工具返回 true（默认）；写操作工具（如取消订单）覆写返回 false，闸门据此拦截。 */
    default boolean readOnly() {
        return true;
    }
}
