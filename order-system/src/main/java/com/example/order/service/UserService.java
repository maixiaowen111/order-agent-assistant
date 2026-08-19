package com.example.order.service;

  import com.example.order.dto.LoginDTO;
  import com.example.order.dto.RegisterDTO;
  import com.example.order.vo.UserVO;

  /**
   * 用户服务接口
   */
  public interface UserService {

      /**
       * 用户注册
       * 返回注册成功的用户信息
       */
      UserVO register(RegisterDTO registerDTO);

      /**
       * 用户登录
       * 返回登录成功的用户信息（含 JWT Token），失败抛异常
       */
      UserVO login(LoginDTO loginDTO);

      /**
       * 退出登录
       * 将当前用户加入 Token 黑名单，所有旧 Token 立即失效
       */
      void logout(Long userId);
  }