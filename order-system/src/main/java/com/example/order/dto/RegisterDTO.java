 package com.example.order.dto;

  import lombok.Data;

  import javax.validation.constraints.NotBlank;
  import javax.validation.constraints.Size;

  /**
   * 注册请求参数
   */
  @Data
  public class RegisterDTO {

      @NotBlank(message = "用户名不能为空")
      @Size(min = 3, max = 20, message = "用户名长度3-20位")
      private String username;

      @NotBlank(message = "密码不能为空")
      @Size(min = 6, max = 50, message = "密码长度6-50位")
      private String password;

      @NotBlank(message = "手机号不能为空")
      private String phone;
  }