  package com.example.order.controller;

  import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
  import com.example.order.common.Result;
import com.example.order.context.UserContext;
import com.example.order.exception.BusinessException;
  import com.example.order.dto.ProductDTO;
  import com.example.order.service.ProductService;
  import com.example.order.vo.ProductVO;
  import lombok.RequiredArgsConstructor;
  import org.springframework.validation.annotation.Validated;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.bind.annotation.PostMapping;
  import org.springframework.web.bind.annotation.PutMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RequestParam;
  import org.springframework.web.bind.annotation.RestController;

  @RestController
  @RequestMapping("/api/product")
  @RequiredArgsConstructor
  public class ProductController {

      private final ProductService productService;

      /**
       * 分页查询商品列表
       */
      @GetMapping("/page")
      public Result<Page<ProductVO>> page(
              @RequestParam(defaultValue = "1") Integer pageNum,
              @RequestParam(defaultValue = "10") Integer pageSize,
              @RequestParam(required = false) String category) {
          Page<ProductVO> page = productService.page(pageNum, pageSize, category);
          return Result.success(page);
      }

      /**
       * 商品详情
       */
      @GetMapping("/{id}")
      public Result<ProductVO> detail(@PathVariable Long id) {
          ProductVO vo = productService.detail(id);
          return Result.success(vo);
      }

      /**
       * 管理后台商品列表（含下架商品，仅管理员）
       */
      @GetMapping("/admin/page")
      public Result<Page<ProductVO>> adminPage(
              @RequestParam(defaultValue = "1") Integer pageNum,
              @RequestParam(defaultValue = "20") Integer pageSize) {
          checkAdmin();
          Page<ProductVO> page = productService.adminPage(pageNum, pageSize);
          return Result.success(page);
      }

      /**
       * 新增商品（管理员）
       */
      @PostMapping
      public Result<ProductVO> create(@Validated @RequestBody ProductDTO dto) {
          checkAdmin();
          ProductVO vo = productService.create(dto);
          return Result.success(vo);
      }

      /**
       * 修改商品（管理员）
       */
      @PutMapping("/{id}")
      public Result<ProductVO> update(@PathVariable Long id,
                                       @Validated @RequestBody ProductDTO dto) {
          checkAdmin();
          ProductVO vo = productService.update(id, dto);
          return Result.success(vo);
      }

      /**
       * 商品上下架（管理员）
       */
      @PutMapping("/{id}/status")
      public Result<Void> updateStatus(@PathVariable Long id,
                                        @RequestParam Integer status) {
          checkAdmin();
          productService.updateStatus(id, status);
          return Result.success();
      }

      /** 校验管理员权限 */
      private void checkAdmin() {
          if (!UserContext.isAdmin()) {
              throw new BusinessException(403, "无权限，仅管理员可操作");
          }
      }
  }
