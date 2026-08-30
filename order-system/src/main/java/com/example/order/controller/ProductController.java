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
  import org.springframework.web.bind.annotation.DeleteMapping;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.bind.annotation.PostMapping;
  import org.springframework.web.bind.annotation.PutMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RequestParam;
  import org.springframework.web.bind.annotation.RestController;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.util.StringUtils;
  import org.springframework.web.multipart.MultipartFile;

  import java.io.IOException;
  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.nio.file.Paths;
  import java.util.Set;
  import java.util.UUID;

  @RestController
  @RequestMapping("/api/product")
  @RequiredArgsConstructor
  public class ProductController {

      private final ProductService productService;

      /** 允许上传的图片扩展名（白名单） */
      private static final Set<String> ALLOWED_IMAGE_EXT = Set.of("jpg", "jpeg", "png", "webp");

      /** 图片大小上限 2MB（与 spring.servlet.multipart 配置保持一致） */
      private static final long MAX_IMAGE_BYTES = 2 * 1024 * 1024;

      /** 图片存储目录：本地默认 ./uploads，Docker 用环境变量 APP_UPLOAD_DIR=/app/uploads 覆盖 */
      @Value("${app.upload-dir:./uploads}")
      private String uploadDir;

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
          if (status == null || (status != 0 && status != 1)) {
              throw new BusinessException(400, "商品状态只能为 0 或 1");
          }
          productService.updateStatus(id, status);
          return Result.success();
      }

      /**
       * 上传商品图片（管理员）
       * 返回相对 URL（/uploads/xxx.jpg），保存商品时写入 image 字段
       */
      @PostMapping("/image")
      public Result<String> uploadImage(@RequestParam("file") MultipartFile file) {
          checkAdmin();
          return Result.success(storeImage(file));
      }

      /**
       * 删除已上传但未保存的图片（管理员）。
       * 前端取消/换图时清理孤儿文件；已被商品引用（已保存）的图后端会跳过，不误删。
       */
      @DeleteMapping("/image")
      public Result<Void> deleteImage(@RequestParam("url") String url) {
          checkAdmin();
          productService.deleteUploadedImage(url);
          return Result.success();
      }

      /** 校验并保存图片文件，返回可访问的相对 URL */
      private String storeImage(MultipartFile file) {
          // 1. 空文件校验
          if (file == null || file.getSize() == 0) {
              throw new BusinessException(400, "请选择图片文件");
          }
          // 2. 扩展名白名单（可伪造，仅作第一道粗筛）
          String ext = StringUtils.getFilenameExtension(file.getOriginalFilename());
          if (ext == null || !ALLOWED_IMAGE_EXT.contains(ext.toLowerCase())) {
              throw new BusinessException(400, "仅支持 jpg / jpeg / png / webp 图片");
          }
          // 3. 大小校验
          if (file.getSize() > MAX_IMAGE_BYTES) {
              throw new BusinessException(400, "图片大小不能超过 2MB");
          }
          byte[] bytes;
          try {
              bytes = file.getBytes();
          } catch (IOException e) {
              throw new BusinessException(500, "读取图片失败");
          }
          // 4. 魔数校验（读真实文件头字节，防扩展名伪造的非图片文件）
          if (!hasImageMagic(bytes)) {
              throw new BusinessException(400, "图片内容不合法");
          }
          // 5. UUID 文件名：杜绝路径穿越 / 重名覆盖
          String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext.toLowerCase();
          try {
              Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
              Files.createDirectories(dir);
              Files.write(dir.resolve(filename), bytes);
          } catch (IOException e) {
              throw new BusinessException(500, "图片保存失败");
          }
          return "/uploads/" + filename;
      }

      /** 校验图片文件头魔数：JPG(FF D8 FF) / PNG(89 50 4E 47) / WebP(RIFF....WEBP) */
      private boolean hasImageMagic(byte[] b) {
          if (b.length < 12) {
              return false;
          }
          if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
              return true;
          }
          if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
              return true;
          }
          return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                  && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
      }

      /** 校验管理员权限 */
      private void checkAdmin() {
          if (!UserContext.isAdmin()) {
              throw new BusinessException(403, "无权限，仅管理员可操作");
          }
      }
  }
