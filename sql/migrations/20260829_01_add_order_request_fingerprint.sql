-- 下单请求指纹：t_order 加 request_fingerprint 列，存「收货信息 + 商品明细」的 JSON 快照。
-- 幂等回放时直接比对新请求算出的指纹与库里这份快照——不依赖购物车是否还在
-- （下单成功后购物车已被删，旧的比对逻辑因此失效，只能比收货信息，同 key 换商品会静默放行）。
ALTER TABLE `t_order`
  ADD COLUMN `request_fingerprint` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL
  COMMENT '下单请求指纹（收货信息+商品明细 JSON 快照，幂等回放比对用，不依赖购物车是否还在）' AFTER `client_request_id`;
