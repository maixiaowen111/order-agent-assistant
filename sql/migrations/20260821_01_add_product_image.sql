-- 商品图片：t_product 加 image 列，存相对 URL（/uploads/xxx.jpg）
-- 图片文件由 order-system 静态映射（WebConfig addResourceHandlers）+ nginx 反代 /uploads/ 访问
ALTER TABLE `t_product`
  ADD COLUMN `image` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL
  COMMENT '商品图片（相对路径）' AFTER `category`;
