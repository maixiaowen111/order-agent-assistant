-- 防重复事件：同订单同类型事件只允许一条
ALTER TABLE `t_event_record` ADD UNIQUE INDEX `uk_order_event` (`order_no` ASC, `event_type` ASC) USING BTREE;
