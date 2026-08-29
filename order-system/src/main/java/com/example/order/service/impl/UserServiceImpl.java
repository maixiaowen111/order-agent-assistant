package com.example.order.service.impl;

  import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
  import com.example.order.dto.LoginDTO;
  import com.example.order.dto.RegisterDTO;
  import com.example.order.entity.User;
  import com.example.order.exception.BusinessException;
  import com.example.order.mapper.UserMapper;
  import com.example.order.service.UserService;
  import com.example.order.util.JwtUtil;
import org.springframework.data.redis.core.RedisTemplate;
import com.example.order.vo.UserVO;
import java.util.concurrent.TimeUnit;
  import lombok.RequiredArgsConstructor;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;

  import java.util.Objects;

  /**
   * 用户服务实现类
   */
  @Slf4j
  @Service
  @RequiredArgsConstructor
  public class UserServiceImpl implements UserService {

      private final UserMapper userMapper;
      private final RedisTemplate<String, Object> redisTemplate;
      private final JwtUtil jwtUtil;

      // BCrypt 密码加密器
      private static final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

      @Override
      @Transactional(rollbackFor = Exception.class)
      public UserVO register(RegisterDTO dto) {
          // 1. 检查用户名是否已存在
          LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
          wrapper.eq(User::getUsername, dto.getUsername());
          User existUser = userMapper.selectOne(wrapper);
          if (Objects.nonNull(existUser)) {
              throw new BusinessException(400, "用户名已存在");
          }

          // 2. 创建用户，密码加密
          User user = new User();
          user.setUsername(dto.getUsername());
          user.setPassword(passwordEncoder.encode(dto.getPassword()));  // BCrypt 加密
          user.setPhone(dto.getPhone());
          user.setRole("USER");      // 默认普通用户
          user.setStatus(1);         // 默认正常状态

          // 3. 保存到数据库
          userMapper.insert(user);
          log.info("用户注册成功，username={}", user.getUsername());

          // 4. 返回 UserVO（不包含密码）
          return toUserVO(user);
      }

      @Override
      public UserVO login(LoginDTO dto) {
          // 1. 根据用户名查询
          LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
          wrapper.eq(User::getUsername, dto.getUsername());
          User user = userMapper.selectOne(wrapper);

          if (Objects.isNull(user)) {
              throw new BusinessException(401, "用户名或密码错误");
          }

          // 2. 校验密码
          if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
              throw new BusinessException(401, "用户名或密码错误");
          }

          log.info("用户登录成功，username={}", user.getUsername());

          // 3. 生成 JWT Token
          String token = jwtUtil.generate(user.getId(), user.getUsername(), user.getRole());

          // 4. 返回 UserVO（含 Token）
          UserVO vo = toUserVO(user);
          vo.setToken(token);
          return vo;
      }

      @Override
      public void logout(Long userId) {
          // 将当前时间戳写入 Redis，所有签发时间早于此值的 Token 全部失效
          long now = System.currentTimeMillis();
          redisTemplate.opsForValue()
                  .set(jwtUtil.blacklistKey(userId), now, 24, TimeUnit.HOURS);
          log.info("用户退出登录，userId={}, 旧 Token 已全部失效", userId);
      }

      /**
       * Entity → VO 转换
       * 目的：脱敏，不把 password 传给前端
       */
      private UserVO toUserVO(User user) {
          UserVO vo = new UserVO();
          vo.setId(user.getId());
          vo.setUsername(user.getUsername());
          vo.setPhone(user.getPhone());
          vo.setRole(user.getRole());
          vo.setStatus(user.getStatus());
          vo.setCreateTime(user.getCreateTime());
          return vo;
      }
  }
