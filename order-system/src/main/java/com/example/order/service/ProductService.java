package com.example.order.service;

  import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
  import com.example.order.dto.ProductDTO;
  import com.example.order.vo.ProductVO;

  public interface ProductService {

      /**
       * 分页查询商品列表
       * @param pageNum  页码
       * @param pageSize 每页条数
       * @param category 分类（可选）
       */
      Page<ProductVO> page(Integer pageNum, Integer pageSize, String category);

      /**
       * 商品详情
       */
      ProductVO detail(Long id);

      /**
       * 新增商品
       */
      ProductVO create(ProductDTO dto);

      /**
       * 修改商品
       */
      ProductVO update(Long id, ProductDTO dto);

      /**
       * 管理后台商品列表（含下架商品）
       */
      Page<ProductVO> adminPage(Integer pageNum, Integer pageSize);

      /**
       * 商品上下架
       * @param status 1-上架 0-下架
       */
      void updateStatus(Long id, Integer status);
  }