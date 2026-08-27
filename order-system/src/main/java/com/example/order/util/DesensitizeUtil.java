package com.example.order.util;

import com.example.order.vo.OrderVO;

/**
 * 收货信息脱敏（最小权限原则）：
 * 内部读接口返回给 agent 的收货人/电话/地址，先打码再出去——agent 根本不该拿到完整地址/手机号。
 * 打码格式与 order-agent-assistant 侧 LogSanitizer 对齐（手机号 138****8000、地址前6+***），
 * 两个模块对同一数据打码结果一致，叙事干净。
 */
public final class DesensitizeUtil {

    private DesensitizeUtil() {
    }

    /** 收货人：张小明→张*明、张三→张*、张→*；空值原样返回 */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String v = name.trim();
        int len = v.length();
        if (len == 1) {
            return "*";
        }
        if (len == 2) {
            return v.charAt(0) + "*";
        }
        return v.charAt(0) + "*" + v.charAt(len - 1);
    }

    /** 手机号：11 位手机号 13800138000→138****8000；其他形状→前1+***+后1；空值原样返回 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String v = phone.trim();
        if (v.length() == 11 && v.startsWith("1")) {
            return v.substring(0, 3) + "****" + v.substring(7);
        }
        if (v.length() <= 2) {
            return v.substring(0, 1) + "***";
        }
        return v.substring(0, 1) + "***" + v.substring(v.length() - 1);
    }

    /** 地址：前 6 字 + ***（≤6 字则整串 + ***）；空值原样返回 */
    public static String maskAddress(String address) {
        if (address == null || address.isBlank()) {
            return address;
        }
        String v = address.trim();
        int keep = Math.min(6, v.length());
        return v.substring(0, keep) + "***";
    }

    /** 给内部读接口的 OrderVO 就地打码（空字段跳过，不会被打成 ***），返回同一对象 */
    public static OrderVO mask(OrderVO vo) {
        vo.setReceiverName(maskName(vo.getReceiverName()));
        vo.setReceiverPhone(maskPhone(vo.getReceiverPhone()));
        vo.setReceiverAddress(maskAddress(vo.getReceiverAddress()));
        return vo;
    }
}
