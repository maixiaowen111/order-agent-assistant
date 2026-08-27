package com.example.order.service;

  import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
  import com.example.order.dto.ProductDTO;
  import com.example.order.vo.ProductVO;

  import java.util.List;

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
       * 按名称关键字搜索商品（模糊匹配；含下架；未删除）。
       * 给 agent 内部接口用：模型先按商品名搜出 id，再查库存。
       */
      List<ProductVO> search(String keyword, int limit);

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

      /**
       * 删除一个已上传但未被任何商品引用的图片文件（管理员）。
       * 前端清理"传了没保存"的孤儿图；被商品引用（已保存）的图会跳过，不误删。
       */
      void deleteUploadedImage(String imageUrl);
  }