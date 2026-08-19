package com.example.order.service;

  import com.example.order.dto.AddCartDTO;
  import com.example.order.vo.CartVO;

  import java.util.List;

  public interface CartService {

      /**
       * 加入购物车
       */
      void add(AddCartDTO dto);

      /**
       * 查看我的购物车
       */
      List<CartVO> myCart();

      /**
       * 修改数量
       */
      void updateQuantity(Long cartId, Integer quantity);

      /**
       * 删除购物车中的商品
       */
      void remove(Long cartId);

      /**
       * 清空我的购物车
       */
      void clear();
  }