package com.example.order.service.impl;

import com.example.order.dto.ProductDTO;
import com.example.order.entity.Product;
import com.example.order.mapper.ProductMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品图片旧图删除测试：换图删旧文件、旧图被复用不删、未换图/外链不碰磁盘。
 * 用真实临时目录验证文件删除，uploadDir 通过 ReflectionTestUtils 注入（@Value 字段没法直接构造传）。
 */
class ProductServiceImplTest {

    private ProductMapper productMapper;
    private ProductServiceImpl service;
    private Path uploadDir;

    @BeforeEach
    void setUp() throws IOException {
        productMapper = mock(ProductMapper.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        service = new ProductServiceImpl(productMapper, redisTemplate);
        uploadDir = Files.createTempDirectory("img-test");
        ReflectionTestUtils.setField(service, "uploadDir", uploadDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var stream = Files.walk(uploadDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不影响断言结果
                }
            });
        }
    }

    private static Product product(Long id, String image) {
        Product p = new Product();
        p.setId(id);
        p.setName("测试商品");
        p.setPrice(new BigDecimal("99.00"));
        p.setImage(image);
        p.setDeleted(0);
        return p;
    }

    private static ProductDTO dto(String image) {
        ProductDTO d = new ProductDTO();
        d.setName("测试商品");
        d.setPrice(new BigDecimal("99.00"));
        d.setImage(image);
        return d;
    }

    /** 在临时 uploadDir 里造一张"旧图"文件，返回其路径 */
    private Path oldFile(String filename) throws IOException {
        Path f = uploadDir.resolve(filename);
        Files.writeString(f, "fake-image-bytes");
        return f;
    }

    @Test
    void 换图_旧图文件被删除() throws IOException {
        Path old = oldFile("abc.jpg");
        when(productMapper.selectById(1L)).thenReturn(product(1L, "/uploads/abc.jpg"));
        when(productMapper.selectCount(any())).thenReturn(0L); // 旧图无其他商品引用

        service.update(1L, dto("/uploads/def.png"));

        assertThat(Files.exists(old)).isFalse();
    }

    @Test
    void 旧图仍被其他商品引用_不删文件() throws IOException {
        Path old = oldFile("abc.jpg");
        when(productMapper.selectById(1L)).thenReturn(product(1L, "/uploads/abc.jpg"));
        when(productMapper.selectCount(any())).thenReturn(1L); // 别处还在用

        service.update(1L, dto("/uploads/def.png"));

        assertThat(Files.exists(old)).isTrue();
    }

    @Test
    void 未换图_不删文件_也不查引用() throws IOException {
        Path old = oldFile("abc.jpg");
        when(productMapper.selectById(1L)).thenReturn(product(1L, "/uploads/abc.jpg"));

        service.update(1L, dto("/uploads/abc.jpg")); // 新图还是同一张

        assertThat(Files.exists(old)).isTrue();
        verify(productMapper, never()).selectCount(any());
    }

    @Test
    void 旧图是外链_不碰磁盘() throws IOException {
        Path old = oldFile("abc.jpg");
        when(productMapper.selectById(1L)).thenReturn(product(1L, "https://example.com/x.jpg"));

        service.update(1L, dto("/uploads/def.png"));

        assertThat(Files.exists(old)).isTrue();
        verify(productMapper, never()).selectCount(any());
    }

    @Test
    void 删除未引用的上传图_文件被删() throws IOException {
        Path uploaded = oldFile("orphan.jpg");
        when(productMapper.selectCount(any())).thenReturn(0L); // 没有任何商品引用

        service.deleteUploadedImage("/uploads/orphan.jpg");

        assertThat(Files.exists(uploaded)).isFalse();
    }

    @Test
    void 上传图已被商品引用_不删() throws IOException {
        Path uploaded = oldFile("orphan.jpg");
        when(productMapper.selectCount(any())).thenReturn(1L); // 已有商品在用

        service.deleteUploadedImage("/uploads/orphan.jpg");

        assertThat(Files.exists(uploaded)).isTrue();
    }

    @Test
    void 上传图是外链_不删也不查引用() throws IOException {
        Path uploaded = oldFile("orphan.jpg");

        service.deleteUploadedImage("https://example.com/x.jpg");

        assertThat(Files.exists(uploaded)).isTrue();
        verify(productMapper, never()).selectCount(any());
    }
}
