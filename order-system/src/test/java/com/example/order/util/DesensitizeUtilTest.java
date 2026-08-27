package com.example.order.util;

import com.example.order.vo.OrderVO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收货信息脱敏工具测试：姓名/手机号/地址的三种长度边界 + 空值 + 整个 OrderVO 打码。
 */
class DesensitizeUtilTest {

    @Test
    void maskName_三字名_留首尾打中间() {
        assertThat(DesensitizeUtil.maskName("张小明")).isEqualTo("张*明");
    }

    @Test
    void maskName_两字名_留姓() {
        assertThat(DesensitizeUtil.maskName("张三")).isEqualTo("张*");
    }

    @Test
    void maskName_单字名_全打码() {
        assertThat(DesensitizeUtil.maskName("张")).isEqualTo("*");
    }

    @Test
    void maskName_空值原样返回() {
        assertThat(DesensitizeUtil.maskName(null)).isNull();
        assertThat(DesensitizeUtil.maskName("")).isEmpty();
        // 契约：空白字符串原样返回（不 trim、不打码）
        assertThat(DesensitizeUtil.maskName("  ")).isEqualTo("  ");
    }

    @Test
    void maskPhone_11位手机号_留前3后4() {
        assertThat(DesensitizeUtil.maskPhone("13800138000")).isEqualTo("138****8000");
    }

    @Test
    void maskPhone_非11位_留首尾() {
        assertThat(DesensitizeUtil.maskPhone("021-5555")).isEqualTo("0***5");
    }

    @Test
    void maskPhone_空值原样返回() {
        assertThat(DesensitizeUtil.maskPhone(null)).isNull();
        assertThat(DesensitizeUtil.maskPhone("")).isEmpty();
    }

    @Test
    void maskAddress_长地址_留前6字() {
        assertThat(DesensitizeUtil.maskAddress("上海市浦东新区张江高科技园区")).isEqualTo("上海市浦东新***");
    }

    @Test
    void maskAddress_短地址_整串加星() {
        assertThat(DesensitizeUtil.maskAddress("北京")).isEqualTo("北京***");
    }

    @Test
    void maskAddress_空值原样返回() {
        assertThat(DesensitizeUtil.maskAddress(null)).isNull();
        assertThat(DesensitizeUtil.maskAddress("")).isEmpty();
    }

    @Test
    void maskOrderVO_三字段就地打码_返回同一对象() {
        OrderVO vo = new OrderVO();
        vo.setReceiverName("张小明");
        vo.setReceiverPhone("13800138000");
        vo.setReceiverAddress("上海市浦东新区张江高科技园区");

        OrderVO out = DesensitizeUtil.mask(vo);

        assertThat(out).isSameAs(vo);
        assertThat(vo.getReceiverName()).isEqualTo("张*明");
        assertThat(vo.getReceiverPhone()).isEqualTo("138****8000");
        assertThat(vo.getReceiverAddress()).isEqualTo("上海市浦东新***");
    }

    @Test
    void maskOrderVO_空字段跳过_不打成星号() {
        OrderVO vo = new OrderVO();
        vo.setReceiverName(null);
        vo.setReceiverPhone("");
        vo.setReceiverAddress(null);

        DesensitizeUtil.mask(vo);

        assertThat(vo.getReceiverName()).isNull();
        assertThat(vo.getReceiverPhone()).isEmpty();
        assertThat(vo.getReceiverAddress()).isNull();
    }
}
