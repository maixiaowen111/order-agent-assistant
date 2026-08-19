package com.example.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.order.entity.EventRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事件记录 Mapper
 *
 * 继承 BaseMapper<EventRecord> 后自动拥有：
 *   insert、updateById、selectById、selectList 等单表操作
 *   不需要写任何 XML
 */
@Mapper
public interface EventRecordMapper extends BaseMapper<EventRecord> {
}
