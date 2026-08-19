# 项目学习记录 — 阶段一

## 一、阶段一完成的功能

基于 SpringBoot + MySQL + MyBatis-Plus 的单体应用，完成电商交易系统基础闭环：

| 模块 | 功能 | 涉及技术 |
|------|------|---------|
| 基础架构 | 统一返回结果、统一异常处理、参数校验、日志记录 | SpringBoot、Lombok、Validation |
| 用户模块 | 注册（BCrypt加密）、登录、用户名唯一校验 | MyBatis-Plus、Spring Security Crypto |
| 商品模块 | 分页查询、分类筛选、商品详情、新增/修改/上下架 | MyBatis-Plus 分页插件 |
| 购物车模块 | 加入购物车（同商品合并数量）、修改数量、删除、清空 | 数据库联合唯一索引 |
| 订单模块 | 创建订单（事务+库存扣减+快照）、支付、取消（恢复库存） | @Transactional、订单号生成 |

---

## 二、涉及技术栈

| 技术 | 为什么使用 | 解决了什么问题 |
|------|-----------|--------------|
| SpringBoot 2.7.x | 企业主流版本，JDK 8/11/17 通用 | 内嵌 Tomcat，自动配置，零 XML |
| MyBatis-Plus 3.5 | 比原生 MyBatis 少写 90% SQL | 单表 CRUD 不用写 XML，自动生成 SQL |
| MySQL 8.0 + InnoDB | InnoDB 支持事务、行锁 | 订单扣库存必须事务保证一致性 |
| BCrypt | 自动加盐，不可逆，可调强度 | MD5 可彩虹表碰撞，BCrypt 无法暴力破解 |
| Lombok | 编译期生成 getter/setter/构造器 | 减少样板代码，代码更干净 |
| Bean Validation | JSR-380 标准 | 参数校验不写在业务代码里，声明式校验 |
| spring-boot-devtools | 改代码自动重启 | 开发效率提升，不用手动停→启 |

---

## 三、核心业务流程

### 3.1 请求完整链路（以注册为例）

```
Postman 发送 POST /api/user/register
    ↓
Tomcat 接收 HTTP 请求（8080 端口）
    ↓
DispatcherServlet 路由匹配 → UserController.register()
    ↓
@RequestBody：JSON 自动转 RegisterDTO 对象
    ↓
@Validated：触发参数校验
    ├─ @NotBlank 检查 username → 为空 → 抛 MethodArgumentNotValidException
    │     ↓
    │   GlobalExceptionHandler.handleValidException() 捕获
    │     ↓
    │   返回 {"code":400, "message":"用户名不能为空"}
    │
    └─ 校验通过 ↓
    ↓
userService.register(dto) → UserServiceImpl.register()
    ├─ selectOne(username=?) → 查到 → throw BusinessException(400, "用户名已存在")
    │     ↓
    │   GlobalExceptionHandler.handleBusinessException() 捕获
    │     ↓
    │   返回 {"code":400, "message":"用户名已存在"}
    │
    └─ 未查到 ↓
    ├─ BCrypt 加密密码
    ├─ insert 到数据库
    ├─ Entity → VO（脱敏，不含 password）
    └─ return Result.success(userVO)
          ↓
        前端收到 {"code":200, "message":"success", "data":{...}}
```

### 3.2 订单创建流程

```
POST /api/order {cartIds, receiverName, receiverPhone, receiverAddress}
    ↓
@Validated 参数校验
    ↓
@Transactional 开启事务（以下所有操作在同一事务中）
    ↓
① selectBatchIds(cartIds) → 查出购物车记录
    ↓
② 逐条校验：商品存在？上架？库存够？
    ├─ 任一失败 → throw BusinessException → 事务回滚
    └─ 全部通过 ↓
③ 计算总金额 totalAmount
④ for each: UPDATE t_product SET stock = stock - qty（扣库存）
⑤ 生成订单号 = 时间戳(yyyyMMddHHmmss) + UUID前6位
⑥ INSERT t_order（等待支付）
⑦ for each: INSERT t_order_item（快照商品信息）
⑧ DELETE FROM t_cart WHERE id IN (cartIds)
    ↓
事务提交（任何一步失败全部回滚）
    ↓
返回 OrderVO
```

### 3.3 订单状态流转

```
WAIT_PAY（待支付）
    ├─ pay()    → PAID（已支付）→ 后续阶段加：SHIPPED → COMPLETED
    └─ cancel() → CANCELLED（已取消）→ 恢复库存

PAID
    └─ cancel() → 报错："只有待支付订单可以取消"
```

---

## 四、我真实踩过的坑（基于学习过程中的提问整理）

### 4.1 编译 ≠ 运行
- **问题**：以为 `mvn compile` 就够了，为什么测试接口还是 404？
- **答案**：`mvn compile` 只是把 .java → .class，检查语法。SpringBoot 服务根本没启动，Tomcat 没监听 8080，接口当然调不通。
- **口诀**：编译 = 检查图纸。运行 = 房子盖起来。Postman 能调的前提是 `mvn spring-boot:run`。

### 4.2 热部署到底帮我做了什么
- **理解**：改代码后，.class 文件在 target/ 里更新了，但 JVM 内存里还是旧的类。没有 DevTools 时，必须停服务 + 重新启动，JVM 才加载新类。DevTools 就是帮你监控 target/ 变化，自动干"停 + 启"这件事。
- **DevTools 规则**：只改 Java 代码 → `mvn compile`（DevTools 自动重启）。改 pom.xml 或配置文件 → 手动停 + 重新 `spring-boot:run`。

### 4.3 YAML 格式问题
- 冒号后面必须有空格：`key: value`（正确）vs `key:value`（错误，会被当字符串）
- 顶级配置必须顶格：`spring:` 前面不能有空格
- 缩进必须用空格不能是 Tab
- 下划线输入变空格 → 中文输入法全角模式导致的

### 4.4 @Validated 到底放哪？为什么不放 DTO 上？
- `@Validated` 放在 **Controller 参数上**，告诉 Spring："这个方法入参需要校验"
- `@NotBlank` 等放在 **DTO 字段上**，定义校验规则
- 两者缺一不可：DTO 写了 @NotBlank 但 Controller 没写 @Validated → 校验不触发，注解是摆设
- Service 不需要 @Validated，因为参数校验在 Controller 层已做完，Service 收到的是干净数据

### 4.5 Spring 怎么"知道"要进 GlobalExceptionHandler？
- 不是魔法，是 Java 的异常类型匹配。抛出 BusinessException → Spring 遍历 @ExceptionHandler → 找到参数类型匹配的 → 执行。找不到 → 走 Exception.class 兜底。
- 是自己写的 `throw new BusinessException(...)` 在起作用，不是什么 AI 判断

### 4.6 业务异常该返回 400 还是 500？
- 4xx = 客户端的问题（用户输错、库存不够），5xx = 服务端的问题（代码 bug、数据库挂了）
- "用户名已存在" → 400（客户换一个名字就行）
- NullPointerException → 500（代码有问题，客户没做错什么）

### 4.7 编译报错怎么看？
- 第一条错误是根因，后面的往往是连锁反应。修好第一条，重新编译，90% 后面自动消失
- `No plugin found for prefix 'xxx'` → 99% 是单词拼错
- `文件不包含类xxx` → 文件为空或路径和 package 声明不匹配
- `（` `）` → 用了中文全角括号（）而非英文半角 ()

### 4.8 为什么 pom.xml 必须先写？
- pom.xml = 购物清单。Maven 按清单下载依赖 → 依赖就绪 → 代码里才能 import
- 不先写 pom.xml：代码里 import SpringApplication → 红色报错 → Maven 不认识这个类

### 4.9 MySQL `->` 提示符是什么？
- `->` 不是报错！是 MySQL 在多行 SQL 时的续行提示符，表示"我还在等你输入，没完呢"
- 打了分号 `;` 再回车才执行

### 4.10 逻辑删除为什么是 `WHERE id = 1`？
- `1` 不是写死的，是调用者传进来的参数。`deleteById(5)` → `WHERE id=5`，`deleteById(99)` → `WHERE id=99`
- 核心是机制：delete 变 update，对所有数据生效

### 4.11 下划线转驼峰为什么重要？
- 数据库字段 `user_name`，Java 属性 `userName`
- 不配置 `map-underscore-to-camel-case: true` → MyBatis 查出来的 `user_name` 找不到 Java 的 `userName` → 字段值为 null
- 数据在数据库里，Java 里是 null，排查半天发现是命名风格不匹配

### 4.12 Cart（购物车）和 OrderItem（订单详情）为什么不能合并？
- Cart = 临时的、过程数据（超市推车，随时改）
- OrderItem = 永久的、结果数据（收银小票，付完钱不变）
- Cart 展示实时价格，OrderItem 存下单那一刻的快照价格
- 合并会导致字段越来越多，大部分场景只用到一半字段

### 4.13 Order 和 OrderItem 为什么拆成两张表？
- 一个订单可以买多个商品 → 1:N 关系
- 不拆分：收货信息每个商品都重复存一遍，浪费空间+更新异常
- 拆分：订单信息存一次，商品明细一个一行，通过 order_id 关联

### 4.14 购物车的 productId 为什么不是集合？
- 数据库一行就是一个商品。用户的购物车 = `List<Cart>`（表中 user_id=1 的所有行）
- 不是因为 Cart.java 里有集合字段，而是查出来的多条记录组成集合

### 4.15 金额为什么用 BigDecimal？
- Float/Double 有精度问题，`0.1 + 0.2 ≠ 0.3`
- 金额必须精确到分，用 `DECIMAL(10,2)` 存，Java 用 `BigDecimal` 接收

### 4.16 Result 的 data 字段为 null 要不要用 @JsonInclude 去掉？
- 大多数企业选择不去掉：`"data":null` 让 TypeScript 类型定义稳定，前端只需判断 `code === 200`
- 多传输 9 个字节可忽略

### 4.17 JDBC URL 记不住怎么办？
- 本质只由 3 部分组成：`jdbc:mysql://[主机]:[端口]/[数据库名]?参数`
- 参数部分是固定模板，每次只改 IP、端口、数据库名
- 公司运维会提供这些信息

### 4.18 Stream/map/collect 看不懂
- 就是 for 循环的另一种写法
- `.stream()` = `for(Cart cart : cartList)`
- `.map(cart -> {...})` = 循环体里的转换逻辑
- `.collect(Collectors.toList())` = 把结果装进新 List

---

## 五、面试问题

### 5.1 为什么 Controller 不能直接操作数据库？
Controller 负责接收参数和返回结果（接口层），不应该包含业务逻辑。直接操作 Mapper 会导致：
- 业务逻辑散落在 Controller 里，无法复用
- 无法做事务管理（@Transactional 要放在 Service 层）
- 无法做单元测试（Controller 依赖 HTTP 请求，不好 Mock）

### 5.2 Entity、DTO、VO 有什么区别？为什么不能用一个类？
- Entity：映射数据库表，和数据库字段一一对应
- DTO：前端→后端，只包含需要的入参字段（如 LoginDTO 只有 username+password）
- VO：后端→前端，只返回需要展示的字段（如 UserVO 不含 password）
- 混用会导致：返回给前端的 JSON 里出现 password、数据库字段变更影响前端、一个字段变所有层都要改

### 5.3 为什么要统一返回结果？
- 不用的话 100 个接口 100 种格式，前端要写 100 套解析
- 统一 `{code, message, data}` 后，前端写一套解析逻辑
- HTTP 状态码不够用——404 可能是"用户不存在"也可能是"商品不存在"，需要业务 code 区分

### 5.4 为什么需要全局异常处理？
- 减少 try-catch 重复代码
- 统一异常处理逻辑，保证返回格式一致
- 区分业务异常（400）和系统异常（500），防止敏感信息泄露给前端

### 5.5 @NotNull 和 @NotBlank 有什么区别？
| 注解 | null | "" 空字符串 | "  " 纯空格 |
|------|:--:|:--:|:--:|
| @NotNull | ❌ | ✅ | ✅ |
| @NotEmpty | ❌ | ❌ | ✅ |
| @NotBlank | ❌ | ❌ | ❌ |

String 类型永远用 @NotBlank，数字类型只能用 @NotNull。

### 5.6 为什么下单要存商品快照（product_name、product_price）？
如果只存 productId，商品后续涨价/改名后，查历史订单看到的是新价格、新名称，不是下单时的信息。快照保证历史订单数据永远准确，不受商品后续修改影响。

### 5.7 MySQL utf8 和 utf8mb4 有什么区别？
MySQL 的 utf8 最多存 3 字节，存不了 emoji（😀）和部分生僻字。utf8mb4 才是真 UTF-8，最多 4 字节。建库必须用 utf8mb4。

### 5.8 为什么选择 InnoDB 而不是 MyISAM？
- InnoDB 支持事务 → 订单扣库存需要原子操作
- InnoDB 支持行锁 → 高并发下性能更好
- InnoDB 支持崩溃恢复 → 数据更安全
- MyISAM 以上都不支持，MySQL 5.7+ 默认引擎已是 InnoDB

### 5.9 为什么密码用 BCrypt 而不是 MD5？
- MD5 是摘要算法，计算速度快，可用彩虹表反向查找原文
- BCrypt 自动加盐（同一密码两次加密结果不同），不可逆，计算强度可调
- 即使数据库被拖库，BCrypt 也能大幅增加破解成本

### 5.10 为什么登录/注册接口的"用户名或密码错误"不要分开提示？
防止用户名枚举攻击。如果提示"用户名不存在"，攻击者可以批量试出哪些用户名已注册，然后用密码库尝试破解。

### 5.11 为什么订单号不用数据库自增 ID？
- 自增 ID 暴露业务量（竞争对手看 ID 就知道你卖了多少单）
- 自增 ID 无规律，容易被遍历攻击
- 分布式场景下自增 ID 会冲突（多台机器同时插入）
- 用雪花算法或时间戳+随机数生成，全局唯一、有序、不暴露信息

### 5.12 为什么要分 Service 接口和实现？
- Spring AOP 事务需要基于接口代理（JDK 动态代理）
- 接口 = 模块能力说明书，看方法签名就知道这个模块做什么
- 面向接口编程，Controller 依赖接口而非实现，降低耦合

### 5.13 扣库存为什么要在事务里？
订单创建流程：扣库存 → 生成订单 → 生成订单详情 → 清空购物车。如果扣库存成功但订单生成失败（数据库宕机），库存白白扣掉了。事务保证：要么全部成功，要么全部回滚。

### 5.14 为什么取消订单要恢复库存？
订单取消后商品应该回到可售状态。如果不恢复库存，每次取消订单库存就永久少一块，越卖越少，月底对不上账。

---

## 六、设计思考

### 6.1 项目搭建顺序
pom.xml → 配置文件（application.yml + dev）→ 启动类 → 数据库建表 → 基础框架（Result + 异常处理）→ 业务模块（entity → mapper → dto/vo → service → controller）。先搭脚手架，再搬业务。

### 6.2 校验放 Controller 层的原因
Controller 是系统入口，外部数据在这里最"脏"。校验完再往下传，后面所有层（Service、Mapper）拿到的都是干净数据，不用重复校验。

### 6.3 订单号的生成
当前方案：时间戳(14位) + UUID前6位 = 20位。阶段三引入雪花算法，更规范、更短、性能更好。

### 6.4 购物车同商品合并数量
数据库有联合唯一索引 `(user_id, product_id)`，如果不合并直接插，会触发唯一约束异常。所以代码里先查询，有则累加，无则新增。

### 6.5 MOCK_USER_ID = 1L
当前未实现登录态，用户 ID 写死为 1。阶段六引入 JWT 后，从 Token 中解析真实用户 ID，替换所有写死的值。

---

## 七、可以优化的地方

| 当前问题 | 优化方案 | 阶段 |
|---------|---------|:--:|
| 用户 ID 写死 | JWT 登录态，从 Token 解析用户 | 阶段六 |
| 库存扣减存在并发超卖风险 | 乐观锁（version字段）或 Redis 分布式锁 | 阶段三 |
| 商品查询每次都查数据库 | Redis 缓存，缓存预热 | 阶段二 |
| 订单创建后无通知 | MQ 异步通知库存/积分/消息服务 | 阶段四 |
| 支付是模拟的 | 对接真实支付接口 + 回调 + 幂等 | 阶段六 |
| 所有模块在一个项目里 | 微服务拆分 user/product/order/stock | 阶段五 |
| 无接口权限控制 | JWT + RBAC 权限模型 | 阶段六 |
| 无压力测试 | JMeter 压测 + 性能优化 | 阶段七 |
| 无部署方案 | Docker 容器化 + Linux 部署 | 阶段七 |
| createTime 插入后不自动回填 | 配置 @TableField 或统一处理 | 后续优化 |

---

# 阶段二：Redis 缓存

## 一、本次新增功能

给商品详情查询加入 Redis 缓存，解决"每次查商品都走 DB"的性能问题。

涉及文件：

| 文件 | 改动 |
|------|------|
| `pom.xml` | 添加 `spring-boot-starter-data-redis`、`commons-pool2`、`jackson-datatype-jsr310` |
| `application-dev.yml` | 添加 Redis 连接配置 |
| `RedisConfig.java` | 自定义 `RedisTemplate`（JSON 序列化）+ `CacheManager` |
| `ProductServiceImpl.java` | `detail()` 加缓存读写，`create/update/updateStatus` 加缓存删除 |

## 二、问题清单 → 逐一解决

### 问题 1：JSON 序列化选型

**不用默认 JDK 序列化的原因**：

- JDK 序列化存的是二进制，`redis-cli` 里看到的是乱码，排错无从下手
- 强制所有实体类 `implements Serializable`，污染模型层

**解决**：自定义 `RedisTemplate`，Key 用 `StringRedisSerializer`，Value 用 `GenericJackson2JsonRedisSerializer`。

**为什么选 Generic 而不是 Jackson2JsonRedisSerializer？**

| 序列化器 | 做法 | 问题 |
|----------|------|------|
| `Jackson2JsonRedisSerializer` | 绑死一个类型，如 `ProductVO.class` | 只能存一种类型，CartVO、OrderVO 没法用 |
| `GenericJackson2JsonRedisSerializer` | JSON 中自动带 `@class` 类型信息 | 通用，一个 Template 存所有类型 |

### 问题 2：LocalDateTime 序列化失败

**现象**：`Java 8 date/time type java.time.LocalDateTime not supported by default`

**根因**：Jackson 默认不认识 Java 8 日期类型。

**解决**：
1. `pom.xml` 添加 `jackson-datatype-jsr310`
2. `ObjectMapper` 注册 `JavaTimeModule`
3. 关闭 `WRITE_DATES_AS_TIMESTAMPS`——存 ISO 8601 字符串而非数组，人能看懂

### 问题 3：反序列化为 LinkedHashMap

**现象**：缓存写入成功，但读取时报 `ClassCastException: LinkedHashMap cannot be cast to ProductVO`

**根因**：JSON 中没有 `@class` 类型信息，Jackson 不知道要还原成什么类型，只能给你一个 `LinkedHashMap`。

**解决**：启用 `activateDefaultTyping`，让 JSON 中带 `["com.example.order.vo.ProductVO", {...}]` 格式的类型标记。

**还踩了一个坑**：`BasicPolymorphicTypeValidator.builder().build()` 空构建器**默认拒绝所有类型解析**。必须加 `.allowIfBaseType(Object.class)`。

### 问题 4：缓存穿透

**场景**：攻击者用不存在的 ID 疯狂请求，每次都穿透 Redis 打到 DB。

**解决**：查询 DB 也不存在时，缓存一个 `"NULL"` 标记，TTL=1分钟。

**为什么是 1 分钟不是 30 分钟？**
- 太长 → 管理员后续新增了这个商品，用户 1 分钟内查还是"不存在"
- 1 分钟足够挡住攻击流量，兜底策略——即使管理员绕过了应用的 create 接口（如 DBA 直接插库），1 分钟后缓存过期，下次查询就能返回正常数据

### 问题 5：缓存雪崩

**场景**：1000 个缓存的 TTL 同时到期，瞬间所有请求打到 DB。

**解决**：TTL = 30 + random(0~9) 分钟，把过期时间均匀打散。

### 问题 6：缓存击穿（未解决，留给阶段三）

**场景**：热点商品缓存刚好过期，1000 个请求同时涌入查 DB。

**当前代码的局限**：`redisTemplate.opsForValue().get()` 不是原子操作，1000 个线程同时判定缓存为空、同时查 DB、同时写缓存——写了 1000 次，但前 999 次 DB 查询已经白白浪费了 DB 连接。

### 问题 7：先写 DB 还是先删缓存？

**当前代码**：先写 DB，再删缓存。

**为什么不是反过来？** 先删缓存再写 DB → 删完之后、写 DB 之前，一个读请求进来查 DB（还是旧数据）→ 把旧数据写回缓存 → 脏了。

**当前方案也非完美**：小概率时序问题——缓存刚好在写操作期间过期 + 读请求读到旧数据 + 读请求写缓存发生在写请求删缓存之后 → 脏数据残留。概率极小但并发量越大出现次数越多。终极方案：Canal 监听 binlog + MQ 异步删缓存（阶段四/五会涉及）。

## 三、核心流程

```
GET /api/product/{id}
    ↓
① 查 Redis: GET product:detail:{id}
    ├─ 命中 "NULL" → 直接抛 404（防穿透）
    ├─ 命中正常数据 → 直接返回（缓存命中）
    └─ 未命中 ↓
② 查 DB: SELECT * FROM t_product WHERE id=?
    ├─ 不存在 → SET product:detail:{id} "NULL" EX 60 → 抛 404
    └─ 存在 ↓
③ 写缓存: SET product:detail:{id} {JSON} EX (1800+random)
④ 返回 ProductVO

更新商品:
  UPDATE t_product → DEL product:detail:{id}
```

## 四、技术要点

| 技术 | 为什么使用 |
|------|-----------|
| Spring Data Redis | Spring 官方集成，自动配置连接池 |
| GenericJackson2JsonRedisSerializer | 通用 JSON 序列化，一个 Template 存所有类型 |
| JavaTimeModule | 解决 LocalDateTime 序列化 |
| activateDefaultTyping | JSON 中保留类型信息，反序列化不丢类型 |
| 随机 TTL | 防止缓存雪崩 |
| 空值缓存 | 防止缓存穿透 |

## 五、面试问题

### 1. 缓存穿透、击穿、雪崩的区别？

| | 原因 | 现象 | 你的方案 |
|--|------|------|---------|
| 穿透 | 查不存在的数据 | 每次都打到 DB | 空值缓存（1分钟TTL） |
| 击穿 | 热点 key 刚好过期 | 大量请求同时查 DB | 分布式锁（阶段三） |
| 雪崩 | 大量 key 同时过期 | DB 瞬间承受所有流量 | 随机 TTL（30+0~9分钟） |

### 2. 为什么自定义 RedisTemplate 而不是用 Spring Boot 自动配置的？

自动配置用的是 JDK 序列化——存的是二进制，Redis 命令行里是乱码，排错无从下手。而且强制实体类实现 Serializable。自定义后换成 JSON，可读、可排查、不污染模型。

### 3. GenericJackson2JsonRedisSerializer 和 Jackson2JsonRedisSerializer 的区别？

Generic 在 JSON 中存储了类型信息（`@class`），反序列化时自动识别类型，一个 Template 可以存多种类型。Jackson2Json 绑死一个类型，但不需要类型信息，JSON 更干净。

### 4. 为什么空值缓存设 1 分钟而不是 30 分钟？

1 分钟足够挡住攻击流量。设太长的话，DBA 直接插了一条商品，用户要等 30 分钟才能查到。1 分钟是一个防御性编程的兜底值。

### 5. 更新商品时为什么要删缓存？先删缓存再写 DB 有什么问题？

删缓存是为了保证下一次查询能拿到最新数据。先删缓存再写 DB 的问题：删完后、写 DB 前，一个读请求进来查 DB 拿到旧数据，把旧数据写回缓存——缓存就脏了。先写 DB 再删缓存能避免这个问题，但仍有极小概率的时序漏洞。

### 6. 什么场景下缓存和数据库会不一致？怎么解决？

写 DB 和删缓存不是原子操作。极端时序下（缓存刚好过期 + 读线程读到旧数据 + 写线程删缓存发生在读线程写缓存之前），脏数据会残留。企业解决方案：Canal 监听 MySQL binlog → 发 MQ → 消费者删除缓存，异步保证最终一致。

## 六、设计思考

- 缓存不应该让调用方感知——Service 层封装了"先查缓存、未命中查 DB、写缓存"的全部逻辑，Controller 和调用方完全不知道缓存的存在
- 防御性编程很重要——你写的 cache 逻辑要考虑 DBA 绕过应用直接改库、非法 ID 攻击、缓存同时过期等各种边界
- 随机 TTL 是低成本高收益的设计——一行 `new Random().nextInt(10)` 就解决了雪崩

---

# 阶段三：高并发与分布式锁

## 一、本次新增功能

用 Redisson 分布式锁解决下单扣库存时的并发超卖问题。

涉及文件：

| 文件 | 改动 |
|------|------|
| `pom.xml` | 添加 `redisson-spring-boot-starter` 3.17.7 |
| `RedissonConfig.java` | 新建，配置 `RedissonClient` Bean |
| `OrderServiceImpl.java` | `create()` 方法加锁：lock → 双重检查 → unlock |

## 二、问题清单 → 逐一解决

### 问题 1：库存为什么一定会超卖？

**当前代码的扣库存流程**：

```java
Product product = productMapper.selectById(id);  // ① 读
if (product.getStock() < quantity) throw ...;     // ② 判断
product.setStock(product.getStock() - quantity);   // ③ 计算
productMapper.updateById(product);                 // ④ 写
```

**并发时间线**：

```
请求 A                          请求 B
──────                          ──────
T1: selectById → stock=10
T2: 判断 10>=1 ✅                selectById → stock=10
T3:                             判断 10>=1 ✅
T4: stock=9, updateById
T5:                             stock=9, updateById → 覆盖了！
```

最终库存 = 9，但卖了 2 件，应该是 8。**根因：读和写之间有间隙，这个间隙里其他线程也能读。**

### 问题 2：synchronized 能解决吗？

**单机部署**：能。同一时刻只有一个线程能进同步方法。

**多台服务器部署**：不能。

```
服务器 A: synchronized 锁的是 A 的 JVM 里的对象
服务器 B: synchronized 锁的是 B 的 JVM 里的对象
两个锁互不认识 → 同时放行 → 超卖
```

`synchronized` 只能锁住**一个 JVM 内部的线程**，跨进程无能为力。

### 问题 3：Redis 分布式锁怎么实现互斥？

需要一个**所有服务器都能看到的公共锁标记**。Redis 天然适合：

```bash
SET lock:product:1 uuid NX EX 30
```

| 参数 | 含义 |
|------|------|
| `lock:product:1` | 锁的 key，按商品粒度 |
| `uuid` | 锁的 value，标识"这把锁是我的" |
| `NX` | Not eXists——只有 key 不存在才能设成功（互斥的核心） |
| `EX 30` | 30 秒自动过期（防死锁） |

**为什么用 NX？** 第一个请求 SET NX 成功（key 不存在），后面所有请求 SET NX 失败（key 已存在）→ 只能等待或失败。

### 问题 4：锁的粒度怎么选？

| 粗粒度 | 细粒度 |
|--------|--------|
| `lock:order`——所有商品共用 | `lock:product:{id}`——按商品加锁 |
| iPhone 和 MacBook 互相阻塞 | 互不影响 |

**选择细粒度**。100 个人抢 iPhone 不影响正常买 MacBook 的人。

### 问题 5：业务执行超时，锁自己过期了怎么办？

**问题**：业务跑了 40 秒，锁 30 秒就过期了 → B 拿到锁 → A 和 B 同时操作。

**Redisson 的解决——看门狗（Watchdog）**：

```
lock.lock()（不传超时时间）
    ↓
Redisson 设 key，默认 TTL=30s
    ↓
启动看门狗（后台定时任务，每 10s 执行一次）
    ├─ 业务还在跑 → 续期，TTL 重置为 30s
    └─ 业务结束 → unlock() → 不再续期
```

**注意**：如果你传了超时时间 `lock.lock(5, TimeUnit.SECONDS)`，看门狗不会启动——你自己承担过期风险。

### 问题 6：怎么防止线程 A 误删线程 B 的锁？

**问题时间线**：

```
T1: A 获取锁，执行业务
T2: 业务太慢，锁自动过期
T3: B 获取锁
T4: A 业务结束，执行 DEL lock:product:1 → 删的是 B 的锁！
T5: C 获取锁 → B 和 C 同时操作 → 超卖
```

**解决**：释放前先判断这把锁是不是自己的，且整个判断+删除必须**原子**。

Redisson 用 **Lua 脚本**实现：

```lua
if redis.call("GET", KEYS[1]) == ARGV[1] then
    return redis.call("DEL", KEYS[1])
else
    return 0
end
```

Redis 执行 Lua 脚本期间不会插入任何其他命令 → `GET + DEL` 绝对原子。

### 问题 7：拿到锁之后为什么还要重新查一次库存？

```
T1: A 读到 stock=10（锁外），开始等锁
T2: B 持锁，扣到 stock=5，释放锁
T3: A 拿到锁
    如果不再查 → 还以为 stock=10 → 超卖
    锁内重查   → 发现 stock=5 < 6 → 正确拦截
```

**这就是"双重检查"**：
- ① 锁前检查：快速失败，大部分不够的请求在这拦住了，不用等锁
- ② 锁内重查：等锁期间库存可能被别的线程改了，重查保证数据正确

### 问题 8：Redisson 版本踩坑

**官网拉了 `4.6.1` → Maven 找不到。**

**原因**：Redisson PRO（商业付费版）版本号是 4.x，社区免费版是 3.x。官网文档如果不注意会点到 PRO 文档。对于 Spring Boot 2.7.x，社区版选 `3.17.7` 最稳定。

## 三、核心流程

```
POST /api/order {cartIds, ...}
    ↓
查购物车记录
    ↓
for each cart:
    ↓
  锁前读: Product product = selectById(id)
    ↓
  校验商品状态 + ①锁前库存检查（快速失败）
    ↓
  lock = redissonClient.getLock("lock:product:" + id)
  lock.lock()  ← SET NX EX，抢不到就排队
    ↓
  try:
    ② 锁内重查: product = selectById(id)  ← 拿到最新数据
    再次校验库存
    ↓
    扣库存: stock = stock - quantity → updateById
  finally:
    lock.unlock()  ← Lua 脚本：判断归属 + DEL
    ↓
  计算金额、构建 OrderItem
    ↓
保存订单 → 保存订单详情 → 删除购物车
```

**为什么 lock.unlock() 必须放 finally？**

业务抛异常直接跳出去了，不放 finally 的话 `unlock()` 永远执行不到 → 锁死在那直到 TTL 过期。这 30 秒内所有请求全部阻塞。

## 四、并发测试

### 测试方法

1. 注释掉删购物车代码（测试需要重复用同一个 cartId）
2. 库存设为 2
3. 两个 `curl` 请求同时发出（`&` 并发）

### 测试结果

```
请求 A: {"code":200, "orderNo":"...081430"}  → 下单成功
请求 B: {"code":200, "orderNo":"...a71945"}  → 下单成功
最终库存:  stock=0  ✅
```

两个请求各买 1 件，库存从 2 精准扣到 0，没有超卖。

**如果不加锁**：两个请求同时读到 stock=2，各自扣到 1，最终库存 = 1（B 覆盖了 A），超卖 1 件。

## 五、面试问题

### 1. synchronized 和分布式锁有什么区别？

`synchronized` 锁的是 JVM 内部的对象，多台服务器各自锁各自的，互不认识。分布式锁存在 Redis 里，所有服务器共享同一把锁。

### 2. Redis 分布式锁为什么用 SET NX？

互斥。NX（Not eXists）保证只有第一个请求能创建成功，后面的全部失败——和 `synchronized` 的 monitor enter 是一个原理。

### 3. 释放锁时为什么要用 Lua 脚本？

判断"锁是不是我的"和"删除锁"这两个操作必须原子。如果分开执行，判断通过后、删除前，锁可能刚好过期被别的线程抢走——然后你删的是别人的锁。Lua 脚本在 Redis 里是原子执行的。

### 4. 看门狗解决了什么问题？

业务执行时间超过锁的过期时间时，自动续期防止锁提前释放。线程存活期间每 10 秒续一次，线程结束不再续期。

### 5. 为什么锁内要重新查一次数据库？

等锁期间库存可能被上一个持锁线程改了。锁内重查拿到的是最新数据，用最新数据做判断才准确。

### 6. 分布式锁和乐观锁怎么选？

| | 分布式锁 | 乐观锁 |
|--|---------|--------|
| 原理 | 加锁排队，同时只有一个人操作 | 不加锁，更新时检测冲突，冲突则重试 |
| 适合 | 冲突概率高的场景（秒杀） | 冲突概率低的场景（普通更新） |
| 代价 | 排队等待 | 冲突多时大量重试浪费 DB 连接 |
| 库存扣减 | ✅ 推荐 | 可用但不是最优 |

### 7. Redisson PRO 和社区版有什么区别？

PRO 是商业付费版（4.x），社区版免费（3.x）。学习用社区版，企业如果买了 PRO 可以用集群模式、多数据中心等高级功能。

## 六、设计思考

- **锁的粒度是设计决策**：锁整个方法简单但性能差，锁单个商品复杂但并发能力高。企业项目选后者。
- **双重检查是最小成本的正确性保证**：锁前查一次挡住大多数无效请求，锁内重查保证并发下数据正确。
- **Redisson 的价值不在于"能加锁"**——你手写 SET NX EX + Lua 也能加。它的价值在于把看门狗、可重入、Lua 原子释放都封装好了，使用体验和 `ReentrantLock` 一致。
- **测试并发不是简单的事**：购物车的唯一约束、删购物车的逻辑都会干扰测试。真实企业项目会用 JMeter 压测而不是 curl。
- **排查问题的通用思路**：找关键字 → 忽略噪音 → 读错误信息 → 验证假设 → 最小修复。这套方法在分布式问题排查中反复使用。

## 七、可以优化的地方

| 当前问题 | 优化方案 | 阶段 |
|---------|---------|:--:|
| 锁内扣库存仍用 updateById | 改用 `UPDATE stock = stock - ? WHERE stock >= ?` 原子 SQL，减少一次查询 | 后续优化 |
| 单机 Redis | 哨兵/集群模式，保证 Redis 高可用 | 阶段七 |
| 注释代码做测试 | 写单元测试 + JMeter 压测脚本 | 阶段七 |
| 锁等待无超时 | `lock.tryLock(waitTime, leaseTime, unit)` 设置等待超时 | 后续优化 |
| 取消订单未加锁 | `cancel()` 恢复库存时也存在并发问题 | 后续优化 |

---

# 阶段四：消息队列（MQ）

## 一、本次新增功能

引入消息队列的异步解耦思想。先用 `@Async` + 自定义线程池模拟，后续换 RocketMQ 时只需要改"发消息"的方式，架构不变。

涉及文件：

| 文件 | 改动 |
|------|------|
| `AsyncConfig.java` | 新建，开启 `@EnableAsync` + 自定义线程池 |
| `OrderEventService.java` | 新建，`@Async` 异步处理下单后事件 |
| `OrderServiceImpl.java` | `create()` 结尾加 `orderEventService.onOrderCreated(orderVO)` |

**不装 RocketMQ 的原因**：初学阶段直接上重量级中间件，配置复杂、排查困难。先用 Spring 内置的异步能力理解"异步解耦"的思想，有了基础再切换只是换一种发送方式。

## 二、问题清单 → 逐一解决

### 问题 1：同步调用导致下单太慢

**现在 `create()` 里的全链路**：

```
查购物车 → 校验商品 → 等锁 → 扣库存 → 保存订单 → 保存订单详情 → 删购物车 → 返回
```

全是串行。如果未来加了发短信、送积分、生成发票……都串在这里，用户等 5 秒才能看到"下单成功"。

**核心 vs 非核心**：

```
核心（必须同步成功）：          非核心（可以异步慢慢做）：
  扣库存                          发短信通知
  保存订单                        送积分
  保存订单详情                    写操作日志
                                  生成发票
```

**解决**：核心做完，发一个异步事件，立即返回。非核心的事给线程池慢慢处理。

### 问题 2：耦合——非核心服务挂了拖死核心业务

```
createOrder()
    ↓ 同步调用
pointsService.addPoints()  ← 积分服务挂了！抛异常！
    ↓
createOrder() 事务回滚 ← 用户明明该下单成功，就因为积分挂了
```

**解决**：异步之后，积分处理在自己的线程/消费者里执行，不参与订单事务。积分失败 → 重试，重试还失败 → 死信队列 → 人工介入。**订单侧完全不受影响。**

### 问题 3：Maven 编译 ≠ 应用重启

`AsyncConfig.java` 新建后，Maven 不重新编译就不会生成 class，DevTools 不重启就不加载新配置。

**解决**：新建文件后执行 `mvn compile`，DevTools 检测到变化自动重启。

## 三、MQ 核心五大问题及解决方案

虽然阶段四没真上 RocketMQ，但已经把 MQ 的理论问题全部梳理了一遍：

| # | 问题 | 场景 | 解决方案 |
|---|------|------|---------|
| **1** | **消息丢失** | Producer 发一半网络断、Broker 宕机未刷盘、Consumer 处理完未 ACK | 同步发送 + 同步刷盘 + 手动 ACK + 本地消息表 |
| **2** | **消息重复** | Consumer 处理完了但 Broker 没收到 ACK，重发 | 消费幂等——消费记录表和业务操作同事务 |
| **3** | **消费失败** | 消费者代码抛异常 | MQ 自动递增重试（10s→30s→1min→…→16次→死信队列） |
| **4** | **顺序问题** | 下单消息和取消消息到达顺序反转 | orderId 取模 → 固定 Queue → FIFO |
| **5** | **Broker 宕机** | MQ 整个挂了，消息发不出去 | 本地消息表——消息先落 DB（和订单同事务），定时任务扫描重发 |

### 消费幂等详解

**为什么消费记录表和业务操作必须在同一个数据库事务里？**

```
方案 A（Redis SET NX）：                方案 B（数据库消费记录表）：
  业务成功 → SET NX 失败（Redis 挂了）    业务成功 ←─────────┐
  → MQ 重发 → 重复消费                   消费记录插入 ←── 同事务 │
                                       → 事务提交 ←──────────┘

方案 A 的事跨了两个系统（MySQL + Redis），无法原子。
方案 B 的事在同一个 MySQL 事务里，要么都成，要么都回滚。
```

**关键时刻选数据库**：涉及钱、积分的场景，可靠性优先于性能。

### 本地消息表（Broker 宕机降级方案）

```java
@Transactional
public OrderVO create(CreateOrderDTO dto) {
    // 扣库存、保存订单...
    
    // 和订单同事务插入消息记录
    MessageRecord record = new MessageRecord();
    record.setOrderNo(orderNo);
    record.setStatus("WAIT_SEND");
    messageRecordMapper.insert(record);
    
    // 事务提交 → 订单和待发送消息同时落库
}

// 定时任务：每5秒扫描未发送的记录，尝试发送
@Scheduled(fixedDelay = 5000)
public void retrySend() { ... }
```

即使 MQ 挂了 5 分钟，消息在数据库里，MQ 恢复后自动补发。

## 四、核心流程

```
POST /api/order {cartIds, ...}
    ↓
查购物车 → 加锁 → 双重检查 → 扣库存
    ↓
保存订单 → 保存订单详情 → 删购物车
    ↓
OrderVO orderVO = buildOrderVO(order, orderItems)
    ↓
orderEventService.onOrderCreated(orderVO)  ← @Async("orderEventExecutor")
    │                                            ↓
    │                                    线程池 order-event-1
    │                                           ↓
    │                                    送积分、发通知、写日志
    ↓
return orderVO  ← 立即返回（不等待异步任务）
```

## 五、技术要点

| 技术 | 为什么使用 |
|------|-----------|
| `@EnableAsync` | 开启 Spring 的异步执行能力 |
| `@Async("orderEventExecutor")` | 指定使用自定义线程池，不指定的话走默认线程池（每次 new 线程，OOM 风险） |
| 自定义 `ThreadPoolTaskExecutor` | 控制核心线程数、最大线程数、队列长度、拒绝策略 |
| `CallerRunsPolicy` | 队列+线程全满时，任务交给调用线程执行——宁可慢点也不丢任务 |
| `@RequiredArgsConstructor` | Lombok 自动生成构造函数，所有 `final` 字段自动注入，不需要手动 `= new` |
| 线程名 `order-event-` | 看日志能一眼区分主线程和异步线程 |

### 线程池参数设计

```
corePoolSize=5:   平时常驻 5 个线程
maxPoolSize=10:   高峰期扩展到 10 个
queueCapacity=100: 100 个任务排队，超出触发拒绝策略
CallerRunsPolicy:  不丢任务，让调用方（主线程）自己跑
```

## 六、并发测试验证

### 测试方法

1. Postman 下单
2. 观察控制台日志的线程名

### 测试结果

```
2026-XX-XX INFO [nio-8080-exec-1] 订单创建成功，orderNo=...    ← 主线程
2026-XX-XX INFO [  order-event-1] [异步] 订单创建事件          ← 异步线程
2026-XX-XX INFO [  order-event-1] [异步] 赠送积分               ← 异步线程
2026-XX-XX INFO [  order-event-1] [异步] 发送下单通知短信        ← 异步线程
2026-XX-XX INFO [  order-event-1] [异步] 记录操作日志           ← 异步线程
```

**线程名不同 = 异步执行成功**。Postman 在主线程返回后就收到了 200，积分/短信/日志在另一个线程里异步执行。

## 七、面试问题

### 1. 为什么需要 MQ？不用 MQ 行不行？

不用 MQ 也行——全写同步代码跑得通。但三个问题会随着业务增长暴露：

- **慢**：用户等所有非核心动作跑完才看到"下单成功"
- **耦合**：非核心服务挂了会导致下单失败
- **没削峰**：秒杀时流量直接打到 DB

MQ 本质是用"异步 + 解耦"换"响应速度 + 可用性"。

### 2. MQ 有哪些使用场景？

削峰填谷、异步解耦、数据同步（Canal + MQ 更新缓存）、日志收集、分布式事务的最终一致性。

### 3. 消息如何保证不丢失？

发送端 → 同步发送，失败重试。Broker → 同步刷盘（性能换可靠性）。消费端 → 手动 ACK，处理完再确认。

### 4. 如何解决消息重复消费？

消费幂等。用消费记录表 + 本地事务：`IF 没消费过 → 执行业务 + 插入消费记录`，两步在一个事务里。

**关键**：消费记录表必须和业务数据在同一个数据库里，否则无法原子保证。

### 5. 为什么消费记录表放 DB 而不是 Redis？

Redis SET NX 是 99% 可靠的"最佳努力"防重。但涉及钱/积分时，必须 100% 可靠——DB 事务是唯一答案。两害相权取其重：多一次 DB 查询 vs 重复送积分。

### 6. 消息顺序乱了怎么办？

同一个业务 ID（如订单号）的消息发到 RocketMQ 的同一个 MessageQueue，Queue 内部 FIFO，消费者单线程处理。

### 7. MQ 挂了怎么办？

本地消息表兜底。消息和业务数据在同一个事务里落库（订单表 + 消息记录表），定时任务扫描 `WAIT_SEND` 状态的消息重发。MQ 恢复后自动补上。

### 8. `@Async` 不指定线程池会怎样？

Spring 默认用 `SimpleAsyncTaskExecutor`——**每来一个任务就新建一个线程**。高并发下线程爆炸，直接 OOM。所以企业代码里 `@Async` 必须指定自定义线程池。

### 9. 怎么验证异步生效了？

看日志线程名。主请求线程是 `nio-8080-exec-X`，异步任务是 `order-event-X`。名字不同就是异步了。

## 八、设计思考

- **架构演进是渐进式的**：先理解"为什么需要异步"，用最简单的 `@Async` 实现，后面换 RocketMQ 只是换一种消息投递方式。不因为"以后要用 MQ"就一开始上重武器。
- **异步带来的复杂度**：消息丢失、重复、顺序——都是在"同步"世界里不存在的新问题。引入任何中间件之前，先想清楚你愿不愿意为它带来的新问题埋单。
- **线程池参数不是玄学**：`corePoolSize` 看平时负载，`maxPoolSize` 看峰值，`queueCapacity` 看能接受多少排队延迟。数字是根据业务量估算的，不是配置文件里随便填的。
- **区分"核心"和"非核心"** 是架构师的基本功。不是所有逻辑都应该在同步链路里，用户只关心"订单成功没有"，不关心积分什么时候到账。

## 九、可以优化的地方

| 当前问题 | 优化方案 | 阶段 |
|---------|---------|:--:|
| `@Async` 只是模拟 | 换 RocketMQ 真正的消息队列 | 阶段五 |
| 异步处理无重试 | MQ 自带重试 + 死信队列兜底 | 阶段五 |
| 线程池参数是拍脑袋定的 | 根据线上监控（Prometheus）动态调整 | 阶段七 |
| 异步任务没有持久化 | 本地消息表保证不丢 | 阶段五 |
| 消费者无幂等保护 | 消费记录表 + 事务 | 阶段五 |

---

# 阶段四补充：本地事件表 + 定时重试

## 一、解决的问题

用 `@Async` 打日志模拟异步处理 → 服务重启任务丢失。

## 二、方案：Transactional Outbox 模式

```
同一事务内：
  INSERT t_order + INSERT t_event_record → 同生共死

事务外：
  @Scheduled 每5秒扫描 → 处理 → SUCCESS / 重试 / FAIL(死信)
```

核心思想：**"记"和"做"分离。** 先记在数据库里（事务保证不丢），再异步做。

## 三、关键技术决策

| 决策 | 原因 |
|------|------|
| 事件记录和订单同事务 | 订单成功但事件丢失 = 积分永远不会送 |
| 状态机 WAIT→PROCESSING→SUCCESS/FAIL | PROCESSING 防止并发重复处理，FAIL 替代死信队列 |
| `@Scheduled(fixedDelay=5000)` | 防任务堆积，上一轮结束+5秒再下一轮 |
| 指数退避重试 | 1min → 3min → 5min → FAIL，不无限重试 |
| LIMIT 50 | 防止一次扫太多 OOM |

## 四、踩坑

- `@EnableScheduling` 不加 → `@Scheduled` 是摆设，方法永远不执行
- Spring 默认单线程跑所有定时任务，多个任务时互相阻塞 → 需自定义线程池

---

# 阶段五：JWT + RBAC + 前端

## 一、JWT 登录态

**问题**：`MOCK_USER_ID = 1L` 写死，所有人调接口系统当同一个人。

**方案**：jjwt 库，HMAC-SHA256 签名，24h TTL。

```
登录 → JwtUtil.generate(userId, username, role) → 返回 Token
请求 → Authorization Header → 拦截器解析 → UserContext(ThreadLocal) → Service取
```

## 二、Token 黑名单（退出登录）

**问题**：JWT 无状态，签出去就收不回。退出登录后旧 Token 仍有效。

**方案**：Redis 存 `TOKEN_VER:{userId} = 当前时间戳`，拦截器比对 Token 签发时间。

```
退出 → SET TOKEN_VER:3 = NOW
旧Token.iat < 黑名单时间 → 401 "Token已失效"
```

不额外查数据库，O(1) Redis 查询，TTL 和 Token 同步过期。

## 三、RBAC 权限控制

**问题**：任何人都能新增/修改商品，没有管理员概念。

**方案**：用户表 `role` 字段 → JWT 携带 → UserContext.isAdmin() → Controller checkAdmin()。

```
用户 → 角色(ADMIN/USER) → 权限(商品管理/下单)
```

## 四、前端页面

纯 HTML + Vanilla JS + Bootstrap CDN，零前端工具链。

| 页面 | 功能 |
|------|------|
| login.html | 登录/注册，管理员自动跳后台 |
| index.html | 商品列表、分类筛选、加入购物车 |
| cart.html | 购物车管理、提交订单 |
| orders.html | 订单列表、支付、取消、详情 |
| admin.html | 商品管理：新增、编辑、上下架 |

## 五、踩坑

| 问题 | 原因 | 解决 |
|------|------|------|
| 管理员接口 403 | WebConfig `excludePathPatterns("/api/product/**")` 范围过大，管理员接口也被跳过，UserContext 未设置 | 只放行公开接口 `page` 和 `{id}`，管理接口不放行 |
| 浏览器缓存旧 JS | 静态资源强缓存，改了 auth.js 不生效 | 加版本号 `?v=2` 强制重新下载 |
| Postman Header 格式 | `Bearer` 和 Token 之间多余空格/换行 | 用环境变量 `{{token}}` |

## 六、SQL 注入验证

LambdaQueryWrapper 全部参数化查询 → `' OR '1'='1` 被当字面量查 → 返回空，不是绕过登录。

```
输入: username = ' OR '1'='1
SQL:  SELECT * FROM t_user WHERE username = ?  ← 安全
而不是: SELECT * FROM t_user WHERE username = '' OR '1'='1'  ← 注入
```

---

# 项目全局总结

## 技术栈全景

| 层次 | 技术 | 解决了什么 |
|------|------|-----------|
| 基础框架 | SpringBoot 2.7 + MyBatis-Plus 3.5 | 快速开发，零XML |
| 数据存储 | MySQL 8.0 + InnoDB | 事务、行锁 |
| 缓存 | Redis + GenericJackson2Json | 商品查询加速、防穿透/雪崩 |
| 分布式锁 | Redisson + 看门狗 | 库存扣减不超卖 |
| 认证鉴权 | JWT + ThreadLocal + 黑名单 | 无状态登录 + 能踢人 |
| 异步处理 | 本地事件表 + @Scheduled | 订单和通知解耦，不丢任务 |
| 前端 | HTML + JS + Bootstrap | 零工具链，浏览器即用 |

## 可回答的面试问题

- 为什么用分布式锁而不是 synchronized？
- 缓存穿透/击穿/雪崩怎么防？
- JWT 怎么实现退出登录？
- RBAC 权限模型怎么落地？
- @Scheduled 定时任务怎么防止并发重复执行？
- 本地事件表为什么和订单放在同一个事务里？
- LambdaQueryWrapper 为什么能防 SQL 注入？
