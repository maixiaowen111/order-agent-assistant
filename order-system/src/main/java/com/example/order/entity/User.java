
  package com.example.order.entity;

  import com.baomidou.mybatisplus.annotation.IdType;
  import com.baomidou.mybatisplus.annotation.TableId;
  import com.baomidou.mybatisplus.annotation.TableName;
  import lombok.Data;

  import java.time.LocalDateTime;

  /**
   * 用户实体类 - 对应数据库 t_user 表
   */
  @Data
  @TableName("t_user")
  public class User {

      @TableId(type = IdType.AUTO)
      private Long id;

      private String username;

      private String password;

      private String phone;

      private String role;

      private Integer status;

      private Integer deleted;

      private LocalDateTime createTime;

      private LocalDateTime updateTime;
  }