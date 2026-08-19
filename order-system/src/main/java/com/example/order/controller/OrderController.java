package com.example.order.controller;

  import com.example.order.common.Result;
  import com.example.order.dto.CreateOrderDTO;
  import com.example.order.service.OrderService;
  import com.example.order.vo.OrderVO;
  import lombok.RequiredArgsConstructor;
  import org.springframework.validation.annotation.Validated;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.bind.annotation.PostMapping;
  import org.springframework.web.bind.annotation.PutMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RestController;

  import java.util.List;

  @RestController
  @RequestMapping("/api/order")
  @RequiredArgsConstructor
  public class OrderController {

      private final OrderService orderService;

      @PostMapping
      public Result<OrderVO> create(@Validated @RequestBody CreateOrderDTO dto) {
          OrderVO vo = orderService.create(dto);
          return Result.success(vo);
      }

      @GetMapping("/{id}")
      public Result<OrderVO> detail(@PathVariable Long id) {
          OrderVO vo = orderService.detail(id);
          return Result.success(vo);
      }

      @GetMapping
      public Result<List<OrderVO>> myOrders() {
          List<OrderVO> list = orderService.myOrders();
          return Result.success(list);
      }

      @PutMapping("/{id}/pay")
      public Result<Void> pay(@PathVariable Long id) {
          orderService.pay(id);
          return Result.success();
      }

      @PutMapping("/{id}/cancel")
      public Result<Void> cancel(@PathVariable Long id) {
          orderService.cancel(id);
          return Result.success();
      }
  }
