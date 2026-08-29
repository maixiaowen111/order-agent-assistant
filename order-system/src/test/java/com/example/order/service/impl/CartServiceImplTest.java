package com.example.order.service.impl;

import com.example.order.context.UserContext;
import com.example.order.entity.Cart;
import com.example.order.exception.BusinessException;
import com.example.order.mapper.CartMapper;
import com.example.order.mapper.ProductMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 购物车越权修复测试：updateQuantity / remove 只能操作自己的购物车记录（mock mapper，不碰真库）。
 * cartId 是客户端传入的，不校验归属会直接改/删到别人的购物车。
 */
class CartServiceImplTest {

    private CartMapper cartMapper;
    private CartServiceImpl service;

    @BeforeEach
    void setUp() {
        cartMapper = mock(CartMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        service = new CartServiceImpl(cartMapper, productMapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Cart cartOf(Long id, Long userId) {
        Cart c = new Cart();
        c.setId(id);
        c.setUserId(userId);
        c.setProductId(10L);
        c.setQuantity(1);
        return c;
    }

    @Test
    void 改数量_不是自己的购物车_403() {
        when(cartMapper.selectById(1L)).thenReturn(cartOf(1L, 2L));  // 购物车属于用户2
        UserContext.set(1L, "u", "USER");   // 用户1来改

        Throwable t = catchThrowable(() -> service.updateQuantity(1L, 5));

        assertThat(((BusinessException) t).getCode()).isEqualTo(403);
        assertThat(((BusinessException) t).getMessage()).contains("无权访问该购物车记录");
        verify(cartMapper, never()).updateById(any(Cart.class));
    }

    @Test
    void 改数量_是自己的购物车_正常更新() {
        when(cartMapper.selectById(1L)).thenReturn(cartOf(1L, 1L));
        UserContext.set(1L, "u", "USER");

        service.updateQuantity(1L, 5);

        verify(cartMapper).updateById(any(Cart.class));
    }

    @Test
    void 删除_不是自己的购物车_403() {
        when(cartMapper.selectById(1L)).thenReturn(cartOf(1L, 2L));
        UserContext.set(1L, "u", "USER");

        Throwable t = catchThrowable(() -> service.remove(1L));

        assertThat(((BusinessException) t).getCode()).isEqualTo(403);
        assertThat(((BusinessException) t).getMessage()).contains("无权访问该购物车记录");
        verify(cartMapper, never()).deleteById(anyLong());
    }

    @Test
    void 删除_是自己的购物车_正常删除() {
        when(cartMapper.selectById(1L)).thenReturn(cartOf(1L, 1L));
        UserContext.set(1L, "u", "USER");

        service.remove(1L);

        verify(cartMapper).deleteById(1L);
    }
}
