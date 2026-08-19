package com.example.order.vo;

  import lombok.Data;

  import java.time.LocalDateTime;

  /**
   * 用户信息返回（脱敏后）
   *
   * 设计原因：
   * 1. 绝不能把 password 返回给前端
   * 2. 和 entity 分离，entity 只跟数据库交互，VO 只跟前端交互
   */
  @Data
  public class UserVO {

      private Long id;

      private String username;

      private String phone;

      private String role;

      private Integer status;

      private LocalDateTime createTime;

      /** JWT Token — 只有登录接口会返回，注册返回 null */
      private String token;
  }
