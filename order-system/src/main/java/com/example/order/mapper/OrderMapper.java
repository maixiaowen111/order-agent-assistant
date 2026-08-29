
  package com.example.order.mapper;

  import com.baomidou.mybatisplus.core.mapper.BaseMapper;
  import com.example.order.entity.Order;
  import org.apache.ibatis.annotations.Mapper;
  import org.apache.ibatis.annotations.Param;
  import org.apache.ibatis.annotations.Update;

  @Mapper
  public interface OrderMapper extends BaseMapper<Order> {

      /**
       * 原子状态流转：只有还是 WAIT_PAY/PAID 才置 CANCELLED，返回影响行数。
       * 两个并发取消同时读到 WAIT_PAY → 只有一个 affected=1，另一个被拦下，
       * 库存恢复因此天然只执行一次（幂等的关键，而不是靠"再查一次状态"）。
       * 手写 SQL 不会自动拼逻辑删除条件，手动加 deleted = 0。
       */
      @Update("UPDATE t_order SET status = 'CANCELLED' " +
              "WHERE id = #{id} AND status IN ('WAIT_PAY','PAID') AND deleted = 0")
      int markCancelled(@Param("id") Long id);
  }
