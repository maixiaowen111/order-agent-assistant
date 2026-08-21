# 踩坑记录（面试实战弹药库）

> 复习用。每个坑按「现象 → 根因 → 修复 → 面试怎么讲」组织。
> 这些不是背的八股，是这个项目里**真实踩过、真的修好了**的问题——面试官最吃这一套，
> 因为能体现「排查路径」和「系统思维」，而不是「背结论」。

## 一句话清单

| # | 坑 | 一句话根因 | 教训 |
|---|----|-----------|------|
| 1 | 编辑商品报「无权限」 | Spring `excludePathPatterns` 的 `*` 通配符不看 HTTP 方法，误放行管理员的 PUT | 路径通配符表达不了「方法」，豁免要么拆 URL 前缀要么按方法判断 |
| 2 | 部署新前端用户看不到新功能 | nginx 没配缓存头，浏览器缓存住旧 index.html 引用旧 JS | SPA 部署：入口 html 必须 `no-cache`，带 hash 的资源天然防缓存 |
| 3 | 聊天回复「没有收到回复」但状态 200 | agent 的 axios 实例忘了配解包拦截器，拿到的是响应对象不是数据体 | 两个后端两种响应格式，拦截器要分开配；调试先看拿到的是啥 |
| 4 | 说「取消订单」模型却调了查单 | LLM 工具选择是概率的，软指令有歧义 | 演示不能赌模型心情：压随机性（temperature 0）+ 前端确定性兜底 |
| 5 | order-system 容器启动即崩 | Redis 连接地址硬编码 `localhost`，容器里 localhost=自己 | 连接地址必须可配置外置，改环境变量要覆盖对地方 |
| 6 | Windows 下 curl 中文参数乱码 | Git Bash 按系统 GBK 编码传参 | 中文存 UTF-8 文件读进变量；这是测试侧坑，不是程序 bug |
| 7 | 消息对象存 Redis 再读出来类型不对 | Jackson 多态序列化：content 是 Object | 用中间格式摊平（PersistedMessage），别依赖 Jackson 原生多态 |
| 8 | 全新部署商品接口报 `Unknown column` | compose 只挂基线 SQL，增量 migration 不自动执行 | 基线必须同步最新结构，别让基线和 migration 漂移 |
| 9 | `@RequestParam Long` 收到非数字返回 500 | Spring 参数类型转换失败（MethodArgumentTypeMismatchException）没被全局异常处理 | 客户端输错是 4xx 问题，给全局异常处理器补类型转换的专门处理 |

---

## 1. 权限拦截：Spring 通配符不看 HTTP 方法

**现象**：管理员新增商品、上下架都正常，唯独**编辑商品**返回 `{"code":403,"message":"无权限，仅管理员可操作"}`。curl 用同一个 admin token 复现，只有 `PUT /api/product/{id}` 403，其他 admin 接口全 200。

**根因**：`WebConfig` 里为了放行公开的「商品详情」，写了 `excludePathPatterns("/api/product/*")`。但 Spring 的 `*` 是**单级路径通配符、不区分 HTTP 方法**——它把管理员的 `PUT /api/product/{id}`（编辑）也一起放行了。放行 = 请求**不经过 LoginInterceptor** = token 不解析 = `UserContext` 里 role 为空 → controller 的 `checkAdmin()` 看到空角色 → 403。

为什么只有编辑挂？
- `POST /api/product`（新增）：单级路径，**不匹配** `/*`（`/*` 要求两级）→ 正常走拦截器 ✓
- `PUT /api/product/4/status`（上下架）：三级路径，不匹配单级 `/*` → 正常走拦截器 ✓
- `PUT /api/product/4`（编辑）：正好二级路径，**匹配** `/*` → 被误放行 ✗

**修复**：删掉这个 exclude，把豁免下沉到 `LoginInterceptor.preHandle`，按「GET 方法 + 单级路径」精确放行：
```java
String uri = request.getRequestURI();
if ("GET".equalsIgnoreCase(request.getMethod()) && uri.matches("/api/product/[^/]+")) {
    return true; // 只有 GET 详情免登录，写操作照常走完整校验
}
```
验证矩阵全过：公开详情 200、admin 编辑 200、无 token 编辑 401、普通用户编辑 403。

**面试怎么讲**：先说「我踩过一个通配符坑」——`excludePathPatterns` 按路径匹配不看方法，`*` 放行公开 GET 时误伤了管理员的 PUT，表现是"只有编辑接口 403、其他接口都正常"。然后讲我是怎么定位的（curl 用同一 token 逐个接口复现，锁定是权限链路的 URL 匹配问题而不是权限本身）。最后讲修复思路：「公开只读接口的豁免，要么拆独立 URL 前缀（/public/**），要么在拦截器里按方法判断——永远别用路径通配符去表达『这个方法公开』，路径表达不了方法」。这条能引出你对 Spring MVC 拦截器机制的熟悉度。

---

## 2. 前端部署：用户看到的是旧版本

**现象**：管理员功能代码全部完成、curl 验证全过、bundle 也确认包含新页面，但用户登录 admin 就是看不到「商品管理」入口。

**根因**：nginx 托管 Vue 产物时没配任何缓存头。浏览器对 `index.html` 做启发式缓存，一直拿着**旧 index.html**（引用旧 JS hash），新代码根本没加载。排查时先确认了服务器端一切正常（curl 拿到的 index.html 引用的是含新逻辑的 JS），所以问题锁定在客户端缓存。

**修复**（SPA 标准做法）：入口 html 每次校验，带 hash 的资源天然防缓存。
```nginx
location = /index.html {
    add_header Cache-Control "no-cache";
}
```
`/assets/*.js` 文件名带内容 hash，改代码后文件名变、浏览器自然拿到新文件，无需管缓存。

**面试怎么讲**：这是个很典型的「开发环境 vs 生产环境」坑——本地 dev server 热更新看不出问题，部署后用户缓存旧版。要点：**SPA 缓存策略 = 内容寻址的资源长缓存（hash 文件名）+ 入口 html 永远 no-cache**。再补一句排查方法：先 curl 服务器端确认「服务器给的就是新页面」，再判断「是服务器旧还是浏览器旧」，二分定位。这个案例能体现你懂浏览器缓存机制而不只是会 npm run build。

---

## 3. 前端：两个后端两种响应格式，拦截器要分开配

**现象**：AI 聊天面板调用 `/query` 返回 200，但气泡永远显示「没有收到回复」。

**根因**：项目里有**两个后端**，响应格式完全不同——
- 业务接口（order-system）：统一包 `{code, message, data}`，且业务错误是 HTTP 200 + body code≠200
- agent 接口（order-agent-assistant）：**裸 JSON**，没有 code 字段

前端为此拆了两个 axios 实例：业务实例配了「解包 + code 判断」拦截器，但 **agent 实例漏配了解包拦截器**。结果 chat store 拿到的是 axios 响应对象 `{data, status, ...}`，`res.answer`、`res.sessionId` 全是 undefined。

**修复**：补一行解包拦截器。
```js
agentHttp.interceptors.response.use((res) => res.data)
```

**面试怎么讲**：这个故事说明两点。第一，**设计上拆两个实例是刻意的**——如果把业务实例的解包/报错拦截器套到 agent 接口上，agent 正常返回（裸 JSON 没有 code）会被误判成错误。第二，**排查方法**：第一版用 curl 验证一直通过，因为 curl 直接看响应体；浏览器里多包了一层 axios，看不到数据体。教训是「接口联调要区分『服务器返回了什么』和『前端代码拿到了什么』，中间隔着拦截器」。体现你对 axios 拦截器、前端数据流的理解。

---

## 4. LLM 工具选择有随机性，演示不能赌模型心情

**现象**：对 agent 说「帮我取消订单 20260819...」，DeepSeek 偶尔调用 `query_order` 而不是 `cancel_order`。权限闸门拦不到取消请求，前端的「批准执行」按钮出不来。

**根因**：LLM 的工具选择是**概率行为**，同一句软指令在不同轮次可能选不同工具。「取消」本身有语义歧义（先查状态再决定？还是直接取消？），提示词压不住。

**修复**（双保险）：
1. **后端压随机性**：请求加 `temperature: 0`；SYSTEM_PROMPT 写死规则「用户消息出现『取消』→ 必须调 cancel_order，禁止调 query_order，禁止反问」。
2. **前端确定性兜底**：检测到取消意图（正则 `/取消|退单|退款/`）且提取到订单号，但模型回复没触发批准也没取消 → **自动补一句命令重发**，把模型拉回取消路径。中间轮不展示，不污染对话。

连续回归验证通过。

**面试怎么讲**：这个坑最能体现「和 LLM 打交道的工程经验」。核心观点：**LLM 输出是概率分布，凡是演示的关键路径，都不能只靠提示词赌它稳定**——要么从参数层压随机性（temperature 0），要么在应用层做确定性兜底（意图检测 + 重定向）。再提一句技术细节：DeepSeek 的 `json_object` 和 function calling 可以同时用（不冲突），`json_object` 只保证「合法 JSON」不保证「符合 schema」，prompt 里必须出现「json」字样。这条能让你在 AI 方向的面试里显得不是只调了 API 而已。

---

## 5. Docker：连接地址硬编码，容器里 localhost 不是本机

**现象**：order-system 容器化后启动即崩，日志报连不上 Redis。

**根因**：`RedissonConfig` 手写 bean，地址硬编码 `redis://localhost:6379`。容器里 `localhost` 指**容器自己**，不是宿主机；而 docker-compose 里 Redis 的服务名是 `redis`。

**修复**：地址改为可配置，compose 用环境变量覆盖。
```java
@Value("${redisson.address:redis://localhost:6379}")
private String address;
```
```yaml
# compose
REDISSON_ADDRESS: redis://redis:6379
```
**关键坑**：`SPRING_DATA_REDIS_HOST` 只能覆盖 Spring Data Redis 的自动配置，**覆盖不到手写的 Redisson bean**——所以地址必须单独做配置项，不能用 Spring 标准的 host 环境变量。

**面试怎么讲**：体现两点：一是「配置外置」意识（连接地址、密钥都应该走环境变量/配置中心，不进代码）；二是「容器化思维的坑」——localhost 语义在容器内外完全不同，跨容器互连必须用服务名/DNS。再主动讲「为什么 Spring 的标准环境变量没生效」——因为它只作用于自动配置的 bean，手动 new 出来的 Redisson 客户端不吃那套，这种「配置被两套体系接管」的坑很有面试价值。

---

## 6. Windows 下的编码坑（测试侧，不是程序 bug）

**现象**：curl 直接传中文参数调用接口，落库的数据是乱码。

**根因**：Windows 的 Git Bash 会把命令行里的中文按系统编码（GBK）传给 curl，而服务端按 UTF-8 解析，字节对不上。

**修复**（README 里记录了可复用写法）：
```bash
Q=$(cat query.txt)   # 中文存 UTF-8 文件
curl -G localhost:8081/query --data-urlencode "q=$Q" --data-urlencode "sessionId=demo"
```
注意 `--data-urlencode "q@file"` 在这种 curl 上不可用，别用那个写法。

**面试怎么讲**：一句话带过即可——「排查过程中发现是测试工具侧的编码问题，不是程序 bug，我把可复用的写法写进了文档」。主动区分「程序 bug」和「环境/测试问题」，体现工程严谨性。

---

## 7. Jackson 多态序列化：Object 字段的读写不一致

**现象**：消息对象里 `content` 是 `Object`（可能是文本也可能是工具调用列表），存进 Redis 再读出来，类型对不上。

**根因**：Jackson 对多态类型（抽象类型/接口/Object）需要类型信息才能还原，默认反序列化会丢失具体类型，或需要 `@JsonTypeInfo` 而这里没有。

**修复**：不依赖 Jackson 原生多态，定义一个**中间格式** `PersistedMessage`——在存之前把 `Message` 摊平成 `text + toolCalls` 两个字段，读出来再还原成 `Message`。

**面试怎么讲**：体现「序列化要显式设计，而不是依赖框架默认」——尤其涉及跨系统/跨存储（这里 Redis 缓存、以后可能是 MQ）时，中间格式是稳定边界。这也是「数据契约」思维的体现。

---

## 8. compose 只挂基线 SQL，增量 migration 不会自动执行

**现象**：加了 `sql/migrations/20260821_01_add_product_image.sql`、实体也加了 `image` 字段，但**全新** `docker compose up` 部署后商品接口直接崩：`Unknown column 'image'`。

**根因**：`docker-compose.yml` 的 mysql 服务只挂载了基线 `../sql/order_db.sql`（`/docker-entrypoint-initdb.d/01-init.sql`），而这个 init 目录**只在 MySQL 数据目录首次为空时执行一次**。`sql/migrations/` 里的增量脚本根本没有被挂载、更不会自动执行。结果就是「新代码（实体带 image 字段）＋ 旧结构（没有 image 列）＝ 崩溃」。已存在的 volume 更不会重跑基线。

**修复**（双管齐下）：
- 增量脚本管**已存在的库**：`ALTER TABLE ... ADD COLUMN`，手动执行（`mysql < 脚本` 或 `docker exec -i order-mysql mysql ... < 脚本`）。
- **基线 `order_db.sql` 同步更新**，管**全新安装**——因为 compose 首次启动只执行基线，基线必须含最新结构。新增列后，基线的 `INSERT INTO ... VALUES (...)` 按列位置插值的种子数据也要同步补值（这次 3 条商品就在 `category` 后补了 `NULL`）。

**面试怎么讲**：这个坑体现「数据库结构演进方式」的系统意识。核心一句：**容器 init 脚本只在首次启动执行，之后的结构变更一律走 migrations，所以任何 schema 变更都要同时想清两条路径——已有库怎么升级（migration）、全新库怎么建（基线），并保证两者不漂移**。大部分面试者只讲「要用 migration 管理 DDL」，你能补上「基线也要同步、否则全新部署直接炸」就是差异化。顺手可以引出「migration 工具（Flyway/Liquibase）解决的就是这个自动化问题」。

---

## 9. 参数类型不匹配：客户端输错返回 500 而不是 400

**现象**：新增内部接口 `GET /internal/product/stock?productId=`（参数类型 `Long`），curl 传 `productId=abc`（非数字），返回 `500 服务器内部错误`，而不是「参数格式不对」的 400。

**根因**：Spring 在把请求参数 `"abc"` 转成 `Long` 时抛出 `MethodArgumentTypeMismatchException`，但它不是 `BusinessException`、也不是 `MethodArgumentNotValidException`（那是 `@Valid` 注解校验的异常），全局异常处理器没接住 → 掉进最底层的 `Exception` 兜底 → 500。**「类型转换失败」和「校验不通过」是两回事，Spring 抛的是不同的异常**。

**修复**：全局异常处理器加一个专门分支，返回 400：
```java
@ExceptionHandler(MethodArgumentTypeMismatchException.class)
public Result<?> handleTypeMismatch(...) {
    return Result.fail(400, "参数格式错误：" + e.getName());
}
```

**面试怎么讲**：把「4xx = 客户端问题、5xx = 服务端 bug」这个原则落到具体异常上。业务系统最常见的三种「非 200 却 200」之外的错误，其实要分三类接：业务异常（`BusinessException`）、参数校验（`MethodArgumentNotValidException`）、参数类型转换（`MethodArgumentTypeMismatchException`）。面试官问「你的全局异常怎么设计的」时，能主动补出第三类说明你想过真实边界——**模型传参、外部调用方传参都不可信，服务端要兜住格式错误**。

---

## 附：这些设计决策和坑的配合

README 里的「设计决策」章节（单 agent 而非多 agent、Tool 层符合 MCP、Transaction Outbox 防重、agent 不直连库）是**为什么这么设计**，本文件是**实际踩了什么坑**。面试时把两者配合讲：

> 「这个项目我做了一个权限闸门——写操作必须人工批准才执行。中途踩过一个坑：Spring 的通配符放行规则不区分 HTTP 方法，导致编辑商品被误放行、checkAdmin 拿不到角色返回 403。我把豁免从 URL 层下沉到了拦截器的方法层。后来 LLM 工具选择有随机性，我又加了 temperature 0 和前端兜底……」
