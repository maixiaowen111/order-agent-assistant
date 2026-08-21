# 增量迁移脚本

基线：`../order_db.sql`（Navicat 全量导出，只在重建库时用，**会清空旧数据**）。

从基线之后的所有表结构变更，按日期记录在这里，一行一个文件：
- 先执行 `order_db.sql` 建出基线结构
- 再按文件名顺序执行 `20xxxxxx_xx_*.sql` 增量脚本

| 文件 | 内容 | 备注 |
|------|------|------|
| `20260818_01_uk_order_event.sql` | t_event_record 加 (order_no, event_type) 唯一索引 | 防重复事件 |
| `20260819_01_create_t_notification.sql` | 新建 t_notification 应用内通知表 | 退款通知落库用 |
| `20260821_01_add_product_image.sql` | t_product 加 image 商品图片列 | 存相对 URL，静态映射访问 |
