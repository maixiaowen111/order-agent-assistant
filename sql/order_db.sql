/*
 Navicat Premium Data Transfer

 Source Server         : life
 Source Server Type    : MySQL
 Source Server Version : 80046 (8.0.46)
 Source Host           : localhost:3306
 Source Schema         : order_db

 Target Server Type    : MySQL
 Target Server Version : 80046 (8.0.46)
 File Encoding         : 65001

 Date: 10/08/2026 14:28:57
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for t_cart
-- ----------------------------
DROP TABLE IF EXISTS `t_cart`;
CREATE TABLE `t_cart`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `product_id` bigint NOT NULL COMMENT '商品ID',
  `quantity` int NOT NULL DEFAULT 1 COMMENT '数量',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_product`(`user_id` ASC, `product_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 16 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '购物车表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of t_cart
-- ----------------------------

-- ----------------------------
-- Table structure for t_event_record
-- ----------------------------
DROP TABLE IF EXISTS `t_event_record`;
CREATE TABLE `t_event_record`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '关联订单号',
  `event_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '事件类型：POINTS-积分 SMS-短信 NOTIFY-推送 REFUND-退款',
  `event_data` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '事件数据（JSON），存放处理所需参数',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'WAIT' COMMENT '状态：WAIT-待处理 PROCESSING-处理中 SUCCESS-成功\r\n  FAIL-超过重试上限',
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `max_retry` int NOT NULL DEFAULT 3 COMMENT '最大重试次数',
  `next_retry_time` datetime NULL DEFAULT NULL COMMENT '下次重试时间',
  `error_msg` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '最后一次失败原因',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `claim_owner` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '领取处理权的实例标识（多实例部署标记谁在处理，崩溃回收依据）',
  `claimed_at` datetime NULL DEFAULT NULL COMMENT '领取时间（SENDING 僵尸判定依据：在途超时才回收）',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_order_no`(`order_no` ASC) USING BTREE,
  INDEX `idx_status_next_retry`(`status` ASC, `next_retry_time` ASC) USING BTREE,
  UNIQUE INDEX `uk_order_event`(`order_no` ASC, `event_type` ASC) USING BTREE COMMENT '同订单同类型事件唯一，防重复通知',
  INDEX `idx_status_claimed_at`(`status` ASC, `claimed_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '本地事件记录表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for t_notification
-- ----------------------------
DROP TABLE IF EXISTS `t_notification`;
CREATE TABLE `t_notification`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '关联订单号',
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '标题',
  `content` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '内容',
  `is_read` tinyint NOT NULL DEFAULT 0 COMMENT '是否已读：0未读 1已读',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user` (`user_id` ASC, `create_time` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '应用内通知';

-- ----------------------------
-- Records of t_event_record
-- ----------------------------
INSERT INTO `t_event_record` VALUES (1, '202608041909409c300e', 'POINTS', '{\"amount\":26997.00,\"userId\":1}', 'SUCCESS', 0, 3, '2026-08-04 19:09:41', NULL, '2026-08-04 19:09:40', '2026-08-04 19:13:57', NULL, NULL);
INSERT INTO `t_event_record` VALUES (2, '202608041909409c300e', 'SMS', '{\"receiverName\":\"牛战士\",\"phone\":\"13800138000\"}', 'SUCCESS', 0, 3, '2026-08-04 19:09:41', NULL, '2026-08-04 19:09:40', '2026-08-04 19:09:40', NULL, NULL);
INSERT INTO `t_event_record` VALUES (3, '202608041909409c300e', 'NOTIFY', '{\"orderNo\":\"202608041909409c300e\",\"userId\":1}', 'SUCCESS', 0, 3, '2026-08-04 19:09:41', NULL, '2026-08-04 19:09:40', '2026-08-04 19:09:40', NULL, NULL);
INSERT INTO `t_event_record` VALUES (4, '20260806185649de7fd2', 'POINTS', '{\"amount\":8999.00,\"userId\":3}', 'SUCCESS', 0, 3, '2026-08-06 18:56:50', NULL, '2026-08-06 18:56:49', '2026-08-06 18:56:49', NULL, NULL);
INSERT INTO `t_event_record` VALUES (5, '20260806185649de7fd2', 'SMS', '{\"receiverName\":\"张三\",\"phone\":\"13800138000\"}', 'SUCCESS', 0, 3, '2026-08-06 18:56:50', NULL, '2026-08-06 18:56:50', '2026-08-06 18:56:50', NULL, NULL);
INSERT INTO `t_event_record` VALUES (6, '20260806185649de7fd2', 'NOTIFY', '{\"orderNo\":\"20260806185649de7fd2\",\"userId\":3}', 'SUCCESS', 0, 3, '2026-08-06 18:56:50', NULL, '2026-08-06 18:56:50', '2026-08-06 18:56:50', NULL, NULL);
INSERT INTO `t_event_record` VALUES (7, '202608071720206c1a28', 'POINTS', '{\"userId\":3,\"amount\":10998.00}', 'SUCCESS', 0, 3, '2026-08-07 17:20:21', NULL, '2026-08-07 17:20:20', '2026-08-07 17:20:20', NULL, NULL);
INSERT INTO `t_event_record` VALUES (8, '202608071720206c1a28', 'SMS', '{\"phone\":\"17513702810\",\"receiverName\":\"牛\"}', 'SUCCESS', 0, 3, '2026-08-07 17:20:21', NULL, '2026-08-07 17:20:20', '2026-08-07 17:20:20', NULL, NULL);
INSERT INTO `t_event_record` VALUES (9, '202608071720206c1a28', 'NOTIFY', '{\"userId\":3,\"orderNo\":\"202608071720206c1a28\"}', 'SUCCESS', 0, 3, '2026-08-07 17:20:21', NULL, '2026-08-07 17:20:20', '2026-08-07 17:20:20', NULL, NULL);

-- ----------------------------
-- Table structure for t_message
-- ----------------------------
DROP TABLE IF EXISTS `t_message`;
CREATE TABLE `t_message`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'POINTS/SMS/NOTIFY',
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `content` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user`(`user_id` ASC, `create_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户消息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of t_message
-- ----------------------------
INSERT INTO `t_message` VALUES (1, 3, '2026080915402067d931', 'POINTS', '积分到账', '下单成功，赠送 8999.0 积分', '2026-08-09 15:40:21');
INSERT INTO `t_message` VALUES (2, 3, '2026080915402067d931', 'SMS', '短信通知', '订单 2026080915402067d931 已创建，等待支付', '2026-08-09 15:40:21');
INSERT INTO `t_message` VALUES (3, 3, '2026080915402067d931', 'NOTIFY', 'App推送', '您有一个新订单：2026080915402067d931', '2026-08-09 15:40:21');

-- ----------------------------
-- Table structure for t_order
-- ----------------------------
DROP TABLE IF EXISTS `t_order`;
CREATE TABLE `t_order`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '订单号',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `total_amount` decimal(10, 2) NOT NULL COMMENT '订单总金额',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'WAIT_PAY' COMMENT '订单状态',
  `receiver_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '收货人',
  `receiver_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '收货电话',
  `receiver_address` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '收货地址',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `client_request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '客户端请求幂等键（网络重试防重复下单，唯一索引允许多个NULL）',
  `request_fingerprint` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '下单请求指纹（收货信息+商品明细 JSON 快照，幂等回放比对用，不依赖购物车是否还在）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_order_no`(`order_no` ASC) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  UNIQUE INDEX `uk_client_request_id`(`client_request_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 21 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of t_order
-- ----------------------------
INSERT INTO `t_order` VALUES (1, '2026073113563149f68e', 1, 69990.00, 'PAID', '张三', '13800138000', '北京市朝阳区望京SOHO', 0, '2026-07-31 13:56:31', '2026-07-31 13:56:31', NULL, NULL);
INSERT INTO `t_order` VALUES (2, '20260802150040e623c2', 1, 8999.00, 'WAIT_PAY', '张三', '13800138000', '北京市朝阳区', 0, '2026-08-02 15:00:40', '2026-08-02 15:00:40', NULL, NULL);
INSERT INTO `t_order` VALUES (3, '2026080215331861b77e', 1, 8999.00, 'WAIT_PAY', '张三', '13800138000', '北京市朝阳区', 0, '2026-08-02 15:33:18', '2026-08-02 15:33:18', NULL, NULL);
INSERT INTO `t_order` VALUES (4, '202608021615418d0ae8', 1, 8999.00, 'WAIT_PAY', 'A', '1', 'a', 0, '2026-08-02 16:15:41', '2026-08-02 16:15:41', NULL, NULL);
INSERT INTO `t_order` VALUES (5, '20260802163208869d31', 1, 8999.00, 'WAIT_PAY', 'A', '1', 'a', 0, '2026-08-02 16:32:08', '2026-08-02 16:32:08', NULL, NULL);
INSERT INTO `t_order` VALUES (6, '202608021637569bd5a3', 1, 8999.00, 'WAIT_PAY', '牛战士', '13800138000', '小屯', 0, '2026-08-02 16:37:56', '2026-08-02 16:37:56', NULL, NULL);
INSERT INTO `t_order` VALUES (7, '20260802164428081430', 1, 8999.00, 'WAIT_PAY', 'A', '1', 'a', 0, '2026-08-02 16:44:28', '2026-08-02 16:44:28', NULL, NULL);
INSERT INTO `t_order` VALUES (8, '20260802164429a71945', 1, 8999.00, 'WAIT_PAY', 'B', '2', 'b', 0, '2026-08-02 16:44:29', '2026-08-02 16:44:29', NULL, NULL);
INSERT INTO `t_order` VALUES (9, '2026080221553451552c', 1, 1999.00, 'WAIT_PAY', '张三', '13800138000', '小屯', 0, '2026-08-02 21:55:34', '2026-08-02 21:55:34', NULL, NULL);
INSERT INTO `t_order` VALUES (10, '202608041909409c300e', 1, 26997.00, 'WAIT_PAY', '牛战士', '13800138000', '小屯', 0, '2026-08-04 19:09:40', '2026-08-04 19:09:40', NULL, NULL);
INSERT INTO `t_order` VALUES (11, '20260806185649de7fd2', 3, 8999.00, 'CANCELLED', '张三', '13800138000', '北京市朝阳区', 0, '2026-08-06 18:56:49', '2026-08-06 18:56:49', NULL, NULL);
INSERT INTO `t_order` VALUES (12, '202608071720206c1a28', 3, 10998.00, 'PAID', '牛', '17513702810', '河南省辉县市拍石头乡四里厂村78号', 0, '2026-08-07 17:20:20', '2026-08-07 17:20:20', NULL, NULL);
INSERT INTO `t_order` VALUES (13, '20260808220632f31778', 3, 8999.00, 'WAIT_PAY', '张三', '13800138000', '北京市朝阳区', 0, '2026-08-08 22:06:32', '2026-08-08 22:06:32', NULL, NULL);
INSERT INTO `t_order` VALUES (14, '202608091508343f5da2', 3, 8999.00, 'WAIT_PAY', '牛', '17513702810', '河南省辉县市拍石头乡四里厂村78号', 0, '2026-08-09 15:08:35', '2026-08-09 15:08:35', NULL, NULL);
INSERT INTO `t_order` VALUES (15, '2026080915083760898b', 3, 8999.00, 'PAID', '牛', '17513702810', '河南省辉县市拍石头乡四里厂村78号', 0, '2026-08-09 15:08:37', '2026-08-09 15:08:37', NULL, NULL);
INSERT INTO `t_order` VALUES (16, '20260809151348f759fc', 3, 8999.00, 'WAIT_PAY', '牛', '17513702810', '河南省辉县市拍石头乡四里厂村78号', 0, '2026-08-09 15:13:48', '2026-08-09 15:13:48', NULL, NULL);
INSERT INTO `t_order` VALUES (17, '20260809151350db3132', 3, 8999.00, 'WAIT_PAY', '牛', '17513702810', '河南省辉县市拍石头乡四里厂村78号', 0, '2026-08-09 15:13:50', '2026-08-09 15:13:50', NULL, NULL);
INSERT INTO `t_order` VALUES (18, '2026080915135246c29e', 3, 8999.00, 'CANCELLED', '牛', '17513702810', '河南省辉县市拍石头乡四里厂村78号', 0, '2026-08-09 15:13:52', '2026-08-09 15:13:52', NULL, NULL);
INSERT INTO `t_order` VALUES (19, '20260809151812ad48e5', 3, 8999.00, 'CANCELLED', '牛', '17513702810', '河南省辉县市拍石头乡四里厂村78号', 0, '2026-08-09 15:18:12', '2026-08-09 15:18:12', NULL, NULL);
INSERT INTO `t_order` VALUES (20, '2026080915402067d931', 3, 8999.00, 'WAIT_PAY', '牛', '17513702810', '河南省辉县市拍石头乡四里厂村78号', 0, '2026-08-09 15:40:20', '2026-08-09 15:40:20', NULL, NULL);

-- ----------------------------
-- Table structure for t_order_item
-- ----------------------------
DROP TABLE IF EXISTS `t_order_item`;
CREATE TABLE `t_order_item`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '订单号',
  `product_id` bigint NOT NULL COMMENT '商品ID',
  `product_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '商品名称（快照）',
  `product_price` decimal(10, 2) NOT NULL COMMENT '商品单价（快照）',
  `quantity` int NOT NULL COMMENT '购买数量',
  `total_price` decimal(10, 2) NOT NULL COMMENT '小计金额',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_order_id`(`order_id` ASC) USING BTREE,
  INDEX `idx_order_no`(`order_no` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 22 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '订单详情表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of t_order_item
-- ----------------------------
INSERT INTO `t_order_item` VALUES (1, 1, '2026073113563149f68e', 1, 'iPhone 15', 6999.00, 10, 69990.00, '2026-07-31 13:56:31');
INSERT INTO `t_order_item` VALUES (2, 2, '20260802150040e623c2', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-02 15:00:40');
INSERT INTO `t_order_item` VALUES (3, 3, '2026080215331861b77e', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-02 15:33:18');
INSERT INTO `t_order_item` VALUES (4, 4, '202608021615418d0ae8', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-02 16:15:42');
INSERT INTO `t_order_item` VALUES (5, 5, '20260802163208869d31', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-02 16:32:08');
INSERT INTO `t_order_item` VALUES (6, 6, '202608021637569bd5a3', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-02 16:37:56');
INSERT INTO `t_order_item` VALUES (7, 7, '20260802164428081430', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-02 16:44:28');
INSERT INTO `t_order_item` VALUES (8, 8, '20260802164429a71945', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-02 16:44:29');
INSERT INTO `t_order_item` VALUES (9, 9, '2026080221553451552c', 3, '红米k70', 1999.00, 1, 1999.00, '2026-08-02 21:55:34');
INSERT INTO `t_order_item` VALUES (10, 10, '202608041909409c300e', 1, 'iPhone 15 Ultra', 8999.00, 3, 26997.00, '2026-08-04 19:09:40');
INSERT INTO `t_order_item` VALUES (11, 11, '20260806185649de7fd2', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-06 18:56:49');
INSERT INTO `t_order_item` VALUES (12, 12, '202608071720206c1a28', 3, '红米k70', 1999.00, 1, 1999.00, '2026-08-07 17:20:20');
INSERT INTO `t_order_item` VALUES (13, 12, '202608071720206c1a28', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-07 17:20:20');
INSERT INTO `t_order_item` VALUES (14, 13, '20260808220632f31778', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-08 22:06:32');
INSERT INTO `t_order_item` VALUES (15, 14, '202608091508343f5da2', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-09 15:08:35');
INSERT INTO `t_order_item` VALUES (16, 15, '2026080915083760898b', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-09 15:08:37');
INSERT INTO `t_order_item` VALUES (17, 16, '20260809151348f759fc', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-09 15:13:48');
INSERT INTO `t_order_item` VALUES (18, 17, '20260809151350db3132', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-09 15:13:50');
INSERT INTO `t_order_item` VALUES (19, 18, '2026080915135246c29e', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-09 15:13:52');
INSERT INTO `t_order_item` VALUES (20, 19, '20260809151812ad48e5', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-09 15:18:12');
INSERT INTO `t_order_item` VALUES (21, 20, '2026080915402067d931', 1, 'iPhone 15 Ultra', 8999.00, 1, 8999.00, '2026-08-09 15:40:20');

-- ----------------------------
-- Table structure for t_product
-- ----------------------------
DROP TABLE IF EXISTS `t_product`;
CREATE TABLE `t_product`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '商品名称',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '商品描述',
  `price` decimal(10, 2) NOT NULL COMMENT '价格',
  `stock` int NOT NULL DEFAULT 0 COMMENT '库存数量',
  `category` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '分类',
  `image` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '商品图片（相对路径）',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1-上架 0-下架',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '商品表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of t_product
-- ----------------------------
INSERT INTO `t_product` VALUES (1, 'iPhone 15 Ultra', '描述内容', 8999.00, 90, '手机', NULL, 1, 0, '2026-07-30 21:09:25', '2026-08-09 15:40:20');
INSERT INTO `t_product` VALUES (2, 'MacBook Pro 14', 'Apple laptop M3', 12999.00, 50, '电子产品', NULL, 1, 0, '2026-07-31 21:19:48', '2026-07-31 21:19:48');
INSERT INTO `t_product` VALUES (3, '红米k70', NULL, 1999.00, 97, NULL, NULL, 1, 0, '2026-08-02 21:53:22', '2026-08-02 21:53:22');

-- ----------------------------
-- Table structure for t_user
-- ----------------------------
DROP TABLE IF EXISTS `t_user`;
CREATE TABLE `t_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户名',
  `password` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'BCrypt加密后的密码',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '手机号',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'USER' COMMENT '角色：USER/ADMIN',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1-正常 0-禁用',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Records of t_user
-- ----------------------------
INSERT INTO `t_user` VALUES (1, 'test', '$2a$10$V5Y4zgP9UhW5AIxG8unvWOkDkNH6PuNileHSP4NDT/EV24lYgeraq', '13800138000', 'USER', 1, 0, '2026-07-29 18:16:16', '2026-07-29 18:16:16');
INSERT INTO `t_user` VALUES (2, 'test2', '$2a$10$1O43mHLpq88lde.hDmgfzOqQwxclT6G9tVStB0Ow3CTXfLSmGfmuu', '13800138001', 'USER', 1, 0, '2026-07-29 18:42:12', '2026-07-29 18:42:12');
INSERT INTO `t_user` VALUES (3, 'zhangsan', '$2a$10$kjj5JgLw9JUPRtY9hMSPaeP47uI4/32WZXyIkhgc3JB/Ca4PYbZbm', '13800138000', 'ADMIN', 1, 0, '2026-08-06 18:28:43', '2026-08-07 17:33:22');
INSERT INTO `t_user` VALUES (4, 'lisi', '$2a$10$9K3T40TssL4nLwkCR1mjMefZLNlYkej4x84ESKRa2oc.3KwBcyMIS', '17513702810', 'USER', 1, 0, '2026-08-07 17:25:13', '2026-08-07 17:25:13');

SET FOREIGN_KEY_CHECKS = 1;
