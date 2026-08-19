
  package com.example.order.mapper;

  import com.baomidou.mybatisplus.core.mapper.BaseMapper;
  import com.example.order.entity.User;
  import org.apache.ibatis.annotations.Mapper;

  /**
   * 用户数据访问层
   *
   * 继承 BaseMapper<User> 后自动拥有：
   * - insert(User)        新增
   * - deleteById(Long)    逻辑删除
   * - updateById(User)    更新
   * - selectById(Long)    根据ID查询
   * - selectList(...)     条件查询
   *
   * 不需要写 XML，不需要写 SQL
   */
  @Mapper
  public interface UserMapper extends BaseMapper<User> {
  }
