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

## 定时任务：为什么扫数据库而不是 Redis 延迟队列

下单后的事件（积分/短信/通知）用「本地事件表 + @Scheduled 定时扫描」重试，而不是 Redis ZSet 延迟队列，核心是**可靠性优先**：

- **真相只有一个**：事件状态在 MySQL、和订单**同一事务**落库（Transactional Outbox），数据库是唯一真相——不会出现"订单提交了但任务丢了"
- **Redis ZSet 更快的代价**：`ZADD`/`ZRANGEBYSCORE` 内存操作确实比扫表快，但引入**跨系统一致性**问题：Redis 写入失败、重启丢数据、任务和 DB 状态对不上时，谁兜底？
- **判断原则**：任务数据在哪个存储，任务就该由哪个存储调度。事件和订单同库 → 扫库天然一致
- **什么时候升级**：任务量大到扫不动、或任务允许丢失时，可升级为 **ZSet 做索引、DB 做真相**的混合方案——定时器从 ZSet 取到期任务（快），执行前回库确认状态（防不一致）

> 面试话术：先承认 ZSet 延迟队列更快，再讲我选扫库是因为 Outbox 要求同事务可靠性；量大时可升级成"Redis 索引 + DB 兜底"——性能和一致性要按场景取舍，不是越花哨越好。

## 踩坑记录（面试官最爱问）

一个从零学的后端项目，踩坑特别多——**18 个全量记录**在 [project-learning-record.md](project-learning-record.md)「四、我真实踩过的坑」，这里挑面试价值最高的几个：

| 坑 | 一句话根因 |
|----|-----------|
| 接口一直 404，`mvn compile` 后直接去测 | 编译只生成 .class，服务根本没启动——`mvn spring-boot:run` 才算运行 |
| DTO 写了 @NotBlank 但校验没生效 | Controller 参数上漏了 @Validated，注解变成摆设 |
| 下划线字段查出来全是 null | 没配 `map-underscore-to-camel-case`，`user_name` 对不上 `userName` |
| 金额用 double 算错 | `0.1 + 0.2 ≠ 0.3`，金额必须 `BigDecimal` + `DECIMAL(10,2)` |
| 业务异常该返 400 还是 500 分不清 | 4xx = 客户端问题（换输入就行），5xx = 服务端问题（代码 bug） |
| 逻辑删除的 `WHERE id = 1` 从哪来 | 不是写死的，是 `deleteById(5)` 传参；机制是 delete 变 update |

> 每个坑都有「问题 → 答案 → 口诀」的完整版，见 [project-learning-record.md](project-learning-record.md)。

## 详细学习记录

见 [project-learning-record.md](project-learning-record.md)
