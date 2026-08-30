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
 import org.springframework.beans.factory.annotation.Value;
 import org.springframework.data.redis.core.RedisTemplate;
 import org.springframework.stereotype.Service;
 import org.springframework.transaction.annotation.Transactional;
 import org.springframework.util.StringUtils;

 import java.io.IOException;
 import java.math.BigDecimal;
 import java.nio.file.Files;
 import java.nio.file.Path;
 import java.nio.file.Paths;
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

     /** 图片存储目录：本地默认 ./uploads，Docker 用 APP_UPLOAD_DIR=/app/uploads 覆盖（与 ProductController 一致） */
     @Value("${app.upload-dir:./uploads}")
     private String uploadDir;

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
      public List<ProductVO> search(String keyword, int limit) {
          LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
          wrapper.eq(Product::getDeleted, 0)
                 .like(StringUtils.hasText(keyword), Product::getName, keyword)
                 .orderByAsc(Product::getId)
                 .last("limit " + Math.min(limit, 20));
          log.debug("商品搜索，keyword={}, 最多{}条", keyword, Math.min(limit, 20));
          return productMapper.selectList(wrapper).stream()
                  .map(this::toProductVO)
                  .collect(Collectors.toList());
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
          validateProductDTO(dto);
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
          validateProductDTO(dto);
          Product product = getById(id);
          String oldImage = product.getImage();
          product.setName(dto.getName());
          product.setDescription(dto.getDescription());
          product.setPrice(dto.getPrice());
          product.setStock(dto.getStock());
          product.setCategory(dto.getCategory());
          product.setImage(dto.getImage());
          productMapper.updateById(product);

          // 换图后删掉磁盘上的旧图（先落库再删，DB 失败就不会误删还被引用的图）
          deleteOldImageFile(oldImage, id, dto.getImage());

          log.info("修改商品成功，id={}", id);
          redisTemplate.delete(PRODUCT_CACHE_PREFIX + id);
          return toProductVO(product);
      }

      @Override
      @Transactional(rollbackFor = Exception.class)
      public void updateStatus(Long id, Integer status) {
          validateStatus(status);
          Product product = getById(id);
          product.setStatus(status);
          productMapper.updateById(product);
          redisTemplate.delete(PRODUCT_CACHE_PREFIX + id);

          log.info("商品状态变更，id={}, status={}", id, status);
      }

      // ============ 参数校验（Service 层兜底，不能只依赖 Controller 的 Bean Validation） ============

      /**
       * 商品新增/修改共用的参数规则：价格必须 > 0，库存必须 >= 0。
       * 在写库之前拦死，非法参数绝不落库。
       */
      private void validateProductDTO(ProductDTO dto) {
          if (dto.getPrice() == null) {
              throw new BusinessException(400, "商品价格不能为空");
          }
          if (dto.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
              throw new BusinessException(400, "商品价格必须大于 0");
          }
          if (dto.getStock() == null) {
              throw new BusinessException(400, "库存数量不能为空");
          }
          if (dto.getStock() < 0) {
              throw new BusinessException(400, "库存不能为负数");
          }
      }

      /**
       * 商品状态只允许 0（下架）/ 1（上架），其他值一律 400，不落库。
       */
      private void validateStatus(Integer status) {
          if (status == null || (status != 0 && status != 1)) {
              throw new BusinessException(400, "商品状态只能为 0 或 1");
          }
      }

      @Override
      public void deleteUploadedImage(String imageUrl) {
          if (imageUrl == null || imageUrl.isBlank() || !imageUrl.startsWith("/uploads/")) {
              return; // 只删本地上传的
          }
          // 已被商品引用的图不删（前端清理的是"没保存"的图，但防一手误删正在用的）
          LambdaQueryWrapper<Product> used = new LambdaQueryWrapper<>();
          used.eq(Product::getImage, imageUrl);
          if (productMapper.selectCount(used) > 0) {
              log.info("图片仍被商品引用，跳过删除，image={}", imageUrl);
              return;
          }
          deleteImageFile(imageUrl);
      }

      // ============ 内部方法 ============

      /**
       * 换图后删磁盘上的旧图。先落库再删；旧图不再被其他商品引用时才删。
       */
      private void deleteOldImageFile(String oldImage, Long productId, String newImage) {
          if (oldImage == null || oldImage.isBlank() || oldImage.equals(newImage)) {
              return; // 没换图，或本来就是空，无旧文件可删
          }
          if (!oldImage.startsWith("/uploads/")) {
              return; // 旧图是外链，不是本地上传的，没有文件可删，也不用查引用
          }
          // 防御：旧图还被别的商品用着就不删（管理员手动复用了同一张图时）
          LambdaQueryWrapper<Product> used = new LambdaQueryWrapper<>();
          used.eq(Product::getImage, oldImage).ne(Product::getId, productId);
          if (productMapper.selectCount(used) > 0) {
              return;
          }
          deleteImageFile(oldImage);
      }

      /**
       * 删一个本地上传的图片文件。只碰 /uploads/**；路径做防穿越校验，只允许删 uploadDir 内的文件；
       * 删除失败不影响主流程（记 WARN 即可）。
       */
      private void deleteImageFile(String imageUrl) {
          if (imageUrl == null || !imageUrl.startsWith("/uploads/")) {
              return; // 外链图（不是本地上传的）不碰
          }
          String filename = imageUrl.substring("/uploads/".length());
          try {
              Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
              Path target = dir.resolve(filename).normalize();
              if (!target.startsWith(dir)) {
                  log.warn("拒绝删除 uploadDir 外的图片路径，image={}", imageUrl);
                  return;
              }
              Files.deleteIfExists(target);
              log.info("已删除商品图片文件，image={}", imageUrl);
          } catch (IOException e) {
              log.warn("删除商品图片失败，image={}", imageUrl, e);
          }
      }

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
