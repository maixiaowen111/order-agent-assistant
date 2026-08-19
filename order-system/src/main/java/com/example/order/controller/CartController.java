package com.example.order.controller;

  import com.example.order.common.Result;
  import com.example.order.dto.AddCartDTO;
  import com.example.order.service.CartService;
  import com.example.order.vo.CartVO;
  import lombok.RequiredArgsConstructor;
  import org.springframework.validation.annotation.Validated;
  import org.springframework.web.bind.annotation.DeleteMapping;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.bind.annotation.PostMapping;
  import org.springframework.web.bind.annotation.PutMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RestController;

  import java.util.List;

  @RestController
  @RequestMapping("/api/cart")
  @RequiredArgsConstructor
  public class CartController {

      private final CartService cartService;

      @PostMapping
      public Result<Void> add(@Validated @RequestBody AddCartDTO dto) {
          cartService.add(dto);
          return Result.success();
      }

      @GetMapping
      public Result<List<CartVO>> myCart() {
          List<CartVO> list = cartService.myCart();
          return Result.success(list);
      }

      @PutMapping("/{cartId}")
      public Result<Void> updateQuantity(@PathVariable Long cartId,
                                          @RequestBody AddCartDTO dto) {
          cartService.updateQuantity(cartId, dto.getQuantity());
          return Result.success();
      }

      @DeleteMapping("/{cartId}")
      public Result<Void> remove(@PathVariable Long cartId) {
          cartService.remove(cartId);
          return Result.success();
      }

      @DeleteMapping("/clear")
      public Result<Void> clear() {
          cartService.clear();
          return Result.success();
      }
  }