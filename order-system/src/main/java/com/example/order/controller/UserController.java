 package com.example.order.controller;

  import com.example.order.common.Result;
import com.example.order.context.UserContext;
  import com.example.order.dto.LoginDTO;
  import com.example.order.dto.RegisterDTO;
  import com.example.order.service.UserService;
  import com.example.order.vo.UserVO;
  import lombok.RequiredArgsConstructor;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.validation.annotation.Validated;
  import org.springframework.web.bind.annotation.PostMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RestController;

  /**
   * 用户接口层
   */
  @Slf4j
  @RestController
  @RequestMapping("/api/user")
  @RequiredArgsConstructor
  public class UserController {

      private final UserService userService;

      /**
       * 用户注册
       */
      @PostMapping("/register")
      public Result<UserVO> register(@Validated @RequestBody RegisterDTO dto) {
          UserVO userVO = userService.register(dto);
          return Result.success(userVO);
      }

      /**
       * 用户登录
       */
      @PostMapping("/login")
      public Result<UserVO> login(@Validated @RequestBody LoginDTO dto) {
          UserVO userVO = userService.login(dto);
          return Result.success(userVO);
      }

      /**
       * 退出登录
       * 将当前 Token 加入黑名单，所有旧 Token 立即失效
       */
      @PostMapping("/logout")
      public Result<Void> logout() {
          userService.logout(UserContext.getUserId());
          return Result.success();
      }
  }
