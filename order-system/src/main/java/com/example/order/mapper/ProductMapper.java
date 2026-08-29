
  package com.example.order.mapper;

  import com.baomidou.mybatisplus.core.mapper.BaseMapper;
  import com.example.order.entity.Product;
  import org.apache.ibatis.annotations.Mapper;
  import org.apache.ibatis.annotations.Param;
  import org.apache.ibatis.annotations.Update;

  @Mapper
  public interface ProductMapper extends BaseMapper<Product> {

      /**
       * 原子扣库存：只够扣才扣，返回影响行数。
       * 并发安全靠 SQL 的 stock >= ? 条件，而不是应用层"先查再写"——
       * 两个请求同时买最后一件，只有一个 affected=1。
       * 注意：这是手写 SQL，MyBatis-Plus 的逻辑删除不会自动拼 deleted=0，需手动加。
       */
      @Update("UPDATE t_product SET stock = stock - #{quantity} " +
              "WHERE id = #{id} AND stock >= #{quantity} AND deleted = 0")
      int deductStock(@Param("id") Long id, @Param("quantity") Integer quantity);

      /**
       * 原子恢复库存：直接加，不先查再写。
       * 幂等由调用方（订单取消的状态守卫）保证——只有真正把订单置为 CANCELLED 的请求才会走到这。
       */
      @Update("UPDATE t_product SET stock = stock + #{quantity} " +
              "WHERE id = #{id} AND deleted = 0")
      int restoreStock(@Param("id") Long id, @Param("quantity") Integer quantity);
  }
