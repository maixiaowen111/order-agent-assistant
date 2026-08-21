 package com.example.order.service.impl;

 import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
 import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
 import com.example.order.dto.ProductDTO;
 import com.example.order.entity.Product;
 import com.example.order.exception.BusinessException;
 import com.example.order.mapper.ProductMapper;
 import com.example.order.service.ProductService;
 import com.example.order.vo.ProductVO;
 import lombok.RequiredArgsConstructor;
 import lombok.extern.slf4j.Slf4j;
 import org.springframework.data.redis.core.RedisTemplate;
 import org.springframework.stereotype.Service;
 import org.springframework.transaction.annotation.Transactional;
 import org.springframework.util.StringUtils;

 import java.util.List;
 import java.util.Random;
 import java.util.concurrent.TimeUnit;
 import java.util.stream.Collectors;


 @Slf4j
  @Service
  @RequiredArgsConstructor
  public class ProductServiceImpl implements ProductService {

      private final ProductMapper productMapper;
     // Redis 缓存前缀
     private static final String PRODUCT_CACHE_PREFIX = "product:detail:";

     // 缓存过期时间（分钟）
     private static final int CACHE_TTL_MINUTES = 30;

     // 空值缓存过期时间（分钟），用于防止缓存穿透
     private static final int NULL_CACHE_TTL_MINUTES = 1;

      private final RedisTemplate<String, Object> redisTemplate;

      @Override
      public Page<ProductVO> adminPage(Integer pageNum, Integer pageSize) {
          LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
          wrapper.eq(Product::getDeleted, 0)
                 .orderByDesc(Product::getCreateTime);
          Page<Product> page = new Page<>(pageNum, pageSize);
          Page<Product> productPage = productMapper.selectPage(page, wrapper);
          List<ProductVO> voList = productPage.getRecords()
                  .stream().map(this::toProductVO).collect(Collectors.toList());
          Page<ProductVO> voPage = new Page<>(pageNum, pageSize, productPage.getTotal());
          voPage.setRecords(voList);
          return voPage;
      }

      @Override
      public Page<ProductVO> page(Integer pageNum, Integer pageSize, String category) {
          // 1. 构建查询条件：只查上架商品 + 未删除
          LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
          wrapper.eq(Product::getStatus, 1)            // 上架
                 .eq(Product::getDeleted, 0);           // 未删除

          // 2. 如果传了分类，加上分类条件
          if (StringUtils.hasText(category)) {
              wrapper.eq(Product::getCategory, category);
          }

          // 3. 按创建时间倒序
          wrapper.orderByDesc(Product::getCreateTime);

          // 4. 分页查询
          Page<Product> page = new Page<>(pageNum, pageSize);
          Page<Product> productPage = productMapper.selectPage(page, wrapper);

          // 5. Entity → VO
          List<ProductVO> voList = productPage.getRecords()
                  .stream()
                  .map(this::toProductVO)
                  .collect(Collectors.toList());

          // 6. 构建分页返回
          Page<ProductVO> voPage = new Page<>(pageNum, pageSize, productPage.getTotal());
          voPage.setRecords(voList);
          return voPage;
      }
     @Override
     public ProductVO detail(Long id) {
         String cacheKey = PRODUCT_CACHE_PREFIX + id;

         // 1. 先查 Redis 缓存
         Object cached = redisTemplate.opsForValue().get(cacheKey);

         if (cached != null) {
             // 命中空值标记 → 商品不存在（防缓存穿透）
             if (cached instanceof String && "NULL".equals(cached)) {
                 log.debug("缓存命中（空值），productId={}", id);
                 throw new BusinessException(404, "商品不存在");
             }
             // 命中正常数据
             log.debug("缓存命中，productId={}", id);
             return (ProductVO) cached;
         }

         // 2. 缓存未命中，查数据库
         Product product = productMapper.selectById(id);
         if (product == null || product.getDeleted() == 1) {
             // 3. 数据库中也没有 → 缓存空值，防止缓存穿透
             redisTemplate.opsForValue().set(cacheKey, "NULL", NULL_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
             throw new BusinessException(404, "商品不存在");
         }

         // 4. 数据库查到 → 转 VO，写缓存，加随机过期时间防雪崩
         ProductVO vo = toProductVO(product);
         int ttl = CACHE_TTL_MINUTES + new Random().nextInt(10);  // 30 + 0~9分钟随机
         redisTemplate.opsForValue().set(cacheKey, vo, ttl, TimeUnit.MINUTES);

         log.debug("缓存写入，productId={}, ttl={}分钟", id, ttl);
         return vo;
     }

      @Override
      @Transactional(rollbackFor = Exception.class)
      public ProductVO create(ProductDTO dto) {
          Product product = new Product();
          product.setName(dto.getName());
          product.setDescription(dto.getDescription());
          product.setPrice(dto.getPrice());
          product.setStock(dto.getStock());
          product.setCategory(dto.getCategory());
          product.setImage(dto.getImage());
          product.setStatus(1);  // 默认上架
          productMapper.insert(product);

          log.info("新增商品成功，id={}, name={}", product.getId(), product.getName());

          // 删除缓存（确保下次查询拿最新数据）
          redisTemplate.delete(PRODUCT_CACHE_PREFIX + product.getId());
          return toProductVO(product);
      }

      @Override
      @Transactional(rollbackFor = Exception.class)
      public ProductVO update(Long id, ProductDTO dto) {
          Product product = getById(id);
          product.setName(dto.getName());
          product.setDescription(dto.getDescription());
          product.setPrice(dto.getPrice());
          product.setStock(dto.getStock());
          product.setCategory(dto.getCategory());
          product.setImage(dto.getImage());
          productMapper.updateById(product);

          log.info("修改商品成功，id={}", id);
          redisTemplate.delete(PRODUCT_CACHE_PREFIX + id);
          return toProductVO(product);
      }

      @Override
      @Transactional(rollbackFor = Exception.class)
      public void updateStatus(Long id, Integer status) {
          Product product = getById(id);
          product.setStatus(status);
          productMapper.updateById(product);
          redisTemplate.delete(PRODUCT_CACHE_PREFIX + id);

          log.info("商品状态变更，id={}, status={}", id, status);
      }

      // ============ 内部方法 ============

      /**
       * 根据 ID 查询商品，不存在则抛异常
       */
      private Product getById(Long id) {
          Product product = productMapper.selectById(id);
          if (product == null || product.getDeleted() == 1) {
              throw new BusinessException(404, "商品不存在");
          }
          return product;
      }

      /**
       * Entity → VO
       */
      private ProductVO toProductVO(Product product) {
          ProductVO vo = new ProductVO();
          vo.setId(product.getId());
          vo.setName(product.getName());
          vo.setDescription(product.getDescription());
          vo.setPrice(product.getPrice());
          vo.setStock(product.getStock());
          vo.setCategory(product.getCategory());
          vo.setImage(product.getImage());
          vo.setStatus(product.getStatus());
          vo.setCreateTime(product.getCreateTime());
          return vo;
      }
  }
