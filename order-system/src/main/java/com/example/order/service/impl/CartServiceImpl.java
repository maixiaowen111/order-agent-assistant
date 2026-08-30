 package com.example.order.service.impl;

  import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
  import com.example.order.dto.AddCartDTO;
  import com.example.order.entity.Cart;
  import com.example.order.entity.Product;
  import com.example.order.context.UserContext;
import com.example.order.exception.BusinessException;
  import com.example.order.mapper.CartMapper;
  import com.example.order.mapper.ProductMapper;
  import com.example.order.service.CartService;
  import com.example.order.vo.CartVO;
  import lombok.RequiredArgsConstructor;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;
  import org.springframework.util.CollectionUtils;

  import java.math.BigDecimal;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.Objects;

  @Slf4j
  @Service
  @RequiredArgsConstructor
  public class CartServiceImpl implements CartService {

      private final CartMapper cartMapper;
      private final ProductMapper productMapper;


      @Override
      @Transactional(rollbackFor = Exception.class)
      public void add(AddCartDTO dto) {
          // 0. 参数校验：productId 非空正数 + quantity 在 [1,999]。
          //    数量不拦的话，负数会一路进库存公式 stock - quantity——quantity=-5 让库存反向 +5。
          if (dto.getProductId() == null || dto.getProductId() < 1) {
              throw new BusinessException(400, "商品不能为空");
          }
          validateQuantity(dto.getQuantity());

          // 1. 校验商品是否存在且已上架
          Product product = productMapper.selectById(dto.getProductId());
          if (Objects.isNull(product) || product.getStatus() != 1) {
              throw new BusinessException(400, "商品不存在或已下架");
          }

          // 2. 查询购物车是否已有该商品
          LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
          wrapper.eq(Cart::getUserId, UserContext.getUserId())
                 .eq(Cart::getProductId, dto.getProductId());
          Cart existCart = cartMapper.selectOne(wrapper);

          if (Objects.nonNull(existCart)) {
              // 已有，累加数量——但合并后的总数量也要 ≤999，超了直接 400，绝不写库
              int merged = existCart.getQuantity() + dto.getQuantity();
              if (merged > 999) {
                  throw new BusinessException(400, "购物车商品总数量不能超过 999");
              }
              existCart.setQuantity(merged);
              cartMapper.updateById(existCart);
          } else {
              // 没有，新增
              Cart cart = new Cart();
              cart.setUserId(UserContext.getUserId());
              cart.setProductId(dto.getProductId());
              cart.setQuantity(dto.getQuantity());
              cartMapper.insert(cart);
          }
      }

      /** 数量合法性：>0 且 ≤999。0 和负数会让库存公式 stock - quantity 反向加库存，必须拦死 */
      private void validateQuantity(Integer quantity) {
          if (quantity == null) {
              throw new BusinessException(400, "数量不能为空");
          }
          if (quantity < 1) {
              throw new BusinessException(400, "数量必须大于 0");
          }
          if (quantity > 999) {
              throw new BusinessException(400, "数量不能超过 999");
          }
      }

      @Override
      public List<CartVO> myCart() {
          // 1. 查购物车数据
          LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
          wrapper.eq(Cart::getUserId, UserContext.getUserId())
                 .orderByDesc(Cart::getCreateTime);
          List<Cart> cartList = cartMapper.selectList(wrapper);

          if (CollectionUtils.isEmpty(cartList)) {
              return new ArrayList<>();
          }

          // 2. 填充商品信息
          return cartList.stream()
                  .map(cart -> {
                      Product product = productMapper.selectById(cart.getProductId());
                      CartVO vo = new CartVO();
                      vo.setId(cart.getId());
                      vo.setProductId(cart.getProductId());
                      vo.setQuantity(cart.getQuantity());

                      if (Objects.nonNull(product)) {
                          vo.setProductName(product.getName());
                          vo.setProductPrice(product.getPrice());

                          // 小计 = 单价 × 数量
                          BigDecimal totalPrice = product.getPrice()
                                  .multiply(BigDecimal.valueOf(cart.getQuantity()));
                          vo.setTotalPrice(totalPrice);
                      }
                      vo.setCreateTime(cart.getCreateTime());
                      return vo;
                  })
                  .collect(java.util.stream.Collectors.toList());
      }

      @Override
      @Transactional(rollbackFor = Exception.class)
      public void updateQuantity(Long cartId, Integer quantity) {
          // 先拦非法数量：0/负数/超上限都别进库里（负数同理会反向加库存）
          validateQuantity(quantity);
          Cart cart = cartMapper.selectById(cartId);
          if (Objects.isNull(cart)) {
              throw new BusinessException(404, "购物车记录不存在");
          }
          // 越权保护：cartId 是客户端传入的，直接改会改到别人的购物车——先校验归属
          if (!cart.getUserId().equals(UserContext.getUserId())) {
              throw new BusinessException(403, "无权访问该购物车记录");
          }
          cart.setQuantity(quantity);
          cartMapper.updateById(cart);
      }

      @Override
      @Transactional(rollbackFor = Exception.class)
      public void remove(Long cartId) {
          Cart cart = cartMapper.selectById(cartId);
          if (Objects.isNull(cart)) {
              throw new BusinessException(404, "购物车记录不存在");
          }
          // 越权保护：cartId 是客户端传入的，直接删会删掉别人的购物车——先校验归属
          if (!cart.getUserId().equals(UserContext.getUserId())) {
              throw new BusinessException(403, "无权访问该购物车记录");
          }
          cartMapper.deleteById(cartId);
      }

      @Override
      @Transactional(rollbackFor = Exception.class)
      public void clear() {
          LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
          wrapper.eq(Cart::getUserId, UserContext.getUserId());
          cartMapper.delete(wrapper);
      }
  }