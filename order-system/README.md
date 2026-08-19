# OrderSystem — 企业订单交易系统

一个从零搭建的电商后端项目，用于展示 Java 后端工程化能力。

## 快速启动

```bash
# 1. 确保 MySQL + Redis 已启动
# 2. 创建数据库
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS order_db DEFAULT CHARSET utf8mb4;"

# 3. 启动应用
mvn clean compile spring-boot:run

# 4. 打开浏览器
http://localhost:8080
```

## 功能概览

| 模块 | 功能 |
|------|------|
| 用户 | 注册（BCrypt加密）、登录（JWT）、退出（黑名单踢人） |
| 商品 | 分页浏览、分类筛选、缓存加速、后台管理 |
| 购物车 | 加入/修改/删除、同商品合并数量 |
| 订单 | 创建（分布式锁防超卖）、支付、取消（恢复库存） |
| 权限 | RBAC：管理员管理商品、普通用户只能购买 |
| 异步 | 下单后积分/短信/通知异步处理、失败重试、死信兜底 |

## 技术栈

| 层次 | 技术 |
|------|------|
| 框架 | SpringBoot 2.7 + MyBatis-Plus 3.5 |
| 数据库 | MySQL 8.0（InnoDB + 事务） |
| 缓存 | Redis（商品缓存 + Token黑名单） |
| 分布式锁 | Redisson（看门狗自动续期） |
| 认证 | JWT（jjwt 0.12）+ ThreadLocal 上下文 |
| 异步 | 本地事件表 + @Scheduled 定时扫描重试 |

## 项目结构

```
src/main/java/com/example/order/
├── common/        Result 统一返回
├── config/        线程池、Redis、Web拦截器
├── context/       UserContext（ThreadLocal）
├── controller/    接口层
├── dto/           入参对象
├── entity/        数据库实体
├── enums/         枚举（事件类型）
├── exception/     全局异常处理
├── interceptor/   JWT 登录拦截器
├── mapper/        MyBatis-Plus 持久层
├── scheduler/     定时任务（事件扫描重试）
├── service/       业务层接口 + 实现
├── util/          JWT 工具类
└── vo/            出参对象
```

## 设计亮点

- **Transactional Outbox**：订单和异步事件同事务落库，重启不丢任务
- **Token 黑名单**：JWT 无状态 + Redis 黑名单 = 既能无状态鉴权又能主动踢人
- **双重检查加锁**：锁前快速失败 + 锁内重新读库 = 最高并发下的库存安全
- **缓存三防**：空值防穿透、随机TTL防雪崩、分布式锁防击穿
- **状态机重试**：WAIT→PROCESSING→SUCCESS/FAIL，PROCESSING 防并发重复处理

## 详细学习记录

见 [project-learning-record.md](project-learning-record.md)
