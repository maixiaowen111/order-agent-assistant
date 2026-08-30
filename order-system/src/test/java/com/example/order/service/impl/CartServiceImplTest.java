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
import static org.mockito.ArgumentMatchers.argThat;
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
    private ProductMapper productMapper;
    private CartServiceImpl service;

    @BeforeEach
    void setUp() {
        cartMapper = mock(CartMapper.class);
        productMapper = mock(ProductMapper.class);
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

    // ---------- 购物车数量校验：0/负数/超上限一律拦下，绝不写库 ----------

    private static com.example.order.dto.AddCartDTO dtoOf(Long productId, Integer quantity) {
        com.example.order.dto.AddCartDTO dto = new com.example.order.dto.AddCartDTO();
        dto.setProductId(productId);
        dto.setQuantity(quantity);
        return dto;
    }

    @Test
    void 加购_数量0_400_不写库() {
        Throwable t = catchThrowable(() -> service.add(dtoOf(10L, 0)));

        assertThat(((BusinessException) t).getCode()).isEqualTo(400);
        assertThat(((BusinessException) t).getMessage()).contains("大于 0");
        verify(cartMapper, never()).insert(any(Cart.class));
        verify(cartMapper, never()).updateById(any(Cart.class));
    }

    @Test
    void 加购_数量负数_400_不写库() {
        Throwable t = catchThrowable(() -> service.add(dtoOf(10L, -5)));

        assertThat(((BusinessException) t).getCode()).isEqualTo(400);
        verify(cartMapper, never()).insert(any(Cart.class));
        verify(cartMapper, never()).updateById(any(Cart.class));
    }

    @Test
    void 加购_数量超上限_400_不写库() {
        Throwable t = catchThrowable(() -> service.add(dtoOf(10L, 1000)));

        assertThat(((BusinessException) t).getCode()).isEqualTo(400);
        assertThat(((BusinessException) t).getMessage()).contains("999");
        verify(cartMapper, never()).insert(any(Cart.class));
        verify(cartMapper, never()).updateById(any(Cart.class));
    }

    @Test
    void 加购_数量为空_400_不写库() {
        Throwable t = catchThrowable(() -> service.add(dtoOf(10L, null)));

        assertThat(((BusinessException) t).getCode()).isEqualTo(400);
        assertThat(((BusinessException) t).getMessage()).contains("数量不能为空");
        verify(cartMapper, never()).insert(any(Cart.class));
    }

    @Test
    void 加购_商品id为空_400_不写库() {
        Throwable t = catchThrowable(() -> service.add(dtoOf(null, 2)));

        assertThat(((BusinessException) t).getCode()).isEqualTo(400);
        assertThat(((BusinessException) t).getMessage()).contains("商品不能为空");
        verify(cartMapper, never()).insert(any(Cart.class));
    }

    @Test
    void 改数量_数量0_400_不查库不改库() {
        Throwable t = catchThrowable(() -> service.updateQuantity(1L, 0));

        assertThat(((BusinessException) t).getCode()).isEqualTo(400);
        // 非法数量在归属校验之前就拦下：连 cartMapper.selectById 都不该发生
        verify(cartMapper, never()).selectById(anyLong());
        verify(cartMapper, never()).updateById(any(Cart.class));
    }

    @Test
    void 改数量_数量负数_400() {
        Throwable t = catchThrowable(() -> service.updateQuantity(1L, -1));

        assertThat(((BusinessException) t).getCode()).isEqualTo(400);
        verify(cartMapper, never()).updateById(any(Cart.class));
    }

    @Test
    void 改数量_数量超上限_400() {
        Throwable t = catchThrowable(() -> service.updateQuantity(1L, 1000));

        assertThat(((BusinessException) t).getCode()).isEqualTo(400);
        assertThat(((BusinessException) t).getMessage()).contains("999");
        verify(cartMapper, never()).updateById(any(Cart.class));
    }

    @Test
    void 改数量_数量为空_400() {
        Throwable t = catchThrowable(() -> service.updateQuantity(1L, null));

        assertThat(((BusinessException) t).getCode()).isEqualTo(400);
        assertThat(((BusinessException) t).getMessage()).contains("数量不能为空");
        verify(cartMapper, never()).updateById(any(Cart.class));
    }

    @Test
    void 加购_合法数量_正常插入() {
        com.example.order.entity.Product p = new com.example.order.entity.Product();
        p.setId(10L);
        p.setStatus(1);
        when(productMapper.selectById(10L)).thenReturn(p);
        when(cartMapper.selectOne(any())).thenReturn(null);
        UserContext.set(1L, "u", "USER");

        service.add(dtoOf(10L, 2));

        verify(cartMapper).insert(any(Cart.class));
    }

    // ---------- 合并数量上限：已有 + 本次 不能超 999，超了 400 且不写库 ----------

    @Test
    void 加购_已有900再买200_400_不写库() {
        com.example.order.entity.Product p = new com.example.order.entity.Product();
        p.setId(10L);
        p.setStatus(1);
        when(productMapper.selectById(10L)).thenReturn(p);
        Cart exist = cartOf(1L, 1L);
        exist.setQuantity(900);
        when(cartMapper.selectOne(any())).thenReturn(exist);
        UserContext.set(1L, "u", "USER");

        Throwable t = catchThrowable(() -> service.add(dtoOf(10L, 200)));

        assertThat(((BusinessException) t).getCode()).isEqualTo(400);
        assertThat(((BusinessException) t).getMessage()).contains("999");
        // 合并超限直接拦下，不写库 → 购物车仍保持 900
        verify(cartMapper, never()).updateById(any(Cart.class));
        verify(cartMapper, never()).insert(any(Cart.class));
    }

    @Test
    void 加购_合并后恰为999_允许更新() {
        com.example.order.entity.Product p = new com.example.order.entity.Product();
        p.setId(10L);
        p.setStatus(1);
        when(productMapper.selectById(10L)).thenReturn(p);
        Cart exist = cartOf(1L, 1L);
        exist.setQuantity(800);
        when(cartMapper.selectOne(any())).thenReturn(exist);
        UserContext.set(1L, "u", "USER");

        service.add(dtoOf(10L, 199));

        verify(cartMapper).updateById(argThat(c -> c.getQuantity() == 999));
        verify(cartMapper, never()).insert(any(Cart.class));
    }
}
