# Order Agent Assistant

一个 **AI Agent 管理订单** 的可运行项目：用户用自然语言查单、查商品库存、取消订单；取消已支付订单会自动触发退款，并在通知中心生成一条真实的退款通知。

- **决策层**（`order-agent-assistant`）：Agent 循环 + 权限闸门 + 多轮记忆 + 工具调用。模型是唯一决策点，代码只负责执行和搬运。
- **执行层**（`order-system`）：订单业务 + Transactional Outbox（事务性发件箱）+ 通知中心。业务规则只留一份，agent 不直连数据库。
- **前端**（`frontend/`）：Vue 3 + Vite 精致单页——完整电商体验 + 右下角 AI 聊天面板；取消订单时聊天里弹出「批准执行」按钮，一键走完「确认 → 执行 → 订单刷新 → 退款通知」闭环。
- **端到端验证通过**：真实 DeepSeek 模型跑通「查单（含收货地址）→ 查商品库存 → 取消被拦截 → 人工批准 → 取消成功 → 退款事件 → 通知落库」。

```
┌───────────────────────────────────────────────────────────────────┐
│  客户端：curl（未来可接任意 MCP 客户端）                              │
└───────────────────────────┬───────────────────────────────────────┘
                            │ GET /query?q=..&sessionId=..   POST /approve
                            ▼
┌───────────────────────────────────────────────────────────────────┐
│  order-agent-assistant   (agent 决策层, 8081)                       │
│                                                                    │
│   AgentLoop  while 循环: 模型要工具→执行→结果喂回→直到模型给最终答案   │
│     ├─ Tool 接口    name / description / inputSchema / run          │
│     ├─ PermissionGate  只读放行；写操作要人工 /approve 才放行         │
│     ├─ RedisSessionStore  多轮记忆（TTL 30min，多实例共享）           │
│     └─ OrderSystemApiClient  调内部接口，带 X-Internal-Key           │
└───────────────────────────┬───────────────────────────────────────┘
                            │ HTTP /internal/**（服务间密钥鉴权）
                            ▼
┌───────────────────────────────────────────────────────────────────┐
│  order-system   (业务执行层, 8080)                                  │
│    状态机: WAIT_PAY → PAID → CANCELLED（重复取消幂等拒绝）            │
│    Transactional Outbox: 取消+退款事件同一事务落库，调度器兜底重试     │
│    通知中心: t_notification（列表 / 标已读 / 全部已读 / 未读数）       │
└──────────┬────────────────────────────┬────────────────────────────┘
           ▼                            ▼
      MySQL (order_db)             Redis（JWT 黑名单等）
```

> 上图的 curl 是直接调后端接口；日常演示直接用 **http://localhost:8082** 前端页面（见下文「前端」章节），
> 浏览器只访问这一个源，nginx 反代把请求分发到两个后端，无跨域。

## 完整链路（一次真实的取消订单）

```
用户: "帮我取消订单 2026..."        → 模型决定调 cancel_order
闸门: 写操作，未批准 → 拦截          → 模型转述"需要人工确认"
                                    前端聊天气泡出现「批准执行」按钮
用户: POST /approve?sessionId=..    → 闸门放行 + 注入"已批准"消息
用户: "我已批准，继续"              → 模型再调 cancel_order → 执行
                                    → 订单 CANCELLED、库存恢复、
                                      REFUND 事件同事务落库 →
                                      调度器处理 → 通知中心生成退款通知
```

## 设计决策（面试常问）

**1. 为什么单 Agent + 权限闸门，而不是多 Agent？**
订单管理就「查单 / 查库存 / 取消 / 退款通知」四件事，一个循环足够。多 Agent 解决的是"复杂组织的协调"问题——这里没有，硬上只会增加延迟和 token 成本。真正的关键是**权限闸门**：把"人工确认"这个动作放进决策流，模型负责决策，闸门负责边界。

**2. 工具层为什么符合 MCP 模型？**
MCP 的本质是把工具以「名字 + 描述 + 输入 Schema」暴露给模型、由 executor 执行——`Tool` 接口就是这个抽象，本地直接执行省掉协议开销。如果未来要对接其他 MCP 客户端，只需把这层换成标准 MCP server，业务代码不动。

**3. 为什么不拆微服务？**
决策层 / 执行层两个服务已经是按职责拆的最优粒度。数据层只有一份 MySQL，再拆是纯成本。

**4. Transactional Outbox 为什么值得？**
"取消订单"和"退款事件"必须**同一事务落库**，否则会出现"订单取消了但通知丢了"。事件先落库、调度器异步处理 + 重试，DB 唯一索引 `(order_no, event_type)` 保证重复执行不会产生重复通知。

**5. agent 为什么不直连数据库？**
业务规则只留一份在 order-system，agent 所有数据读写都走内部 HTTP + `X-Internal-Key` 密钥鉴权——agent 是纯决策者，不碰存储细节。

## 踩坑记录（面试实战弹药）

「为什么这么设计」在上一节，这一节是**真的踩过、真的修好的坑**：Spring 通配符不看方法导致编辑商品 403、浏览器缓存旧 JS 看不到新功能、agent 拦截器缺解包、LLM 工具选择随机性……每条都是「现象 → 根因 → 修复 → 面试怎么讲」，见 [PITFALLS.md](PITFALLS.md)。

## 快速开始

### 方式一：Docker Compose 一键起（推荐）

```bash
# 1. 配置 key
cp .env.example .env        # 填上 DEEPSEEK_API_KEY=sk-xxx

# 2. 一键起（MySQL + Redis + order-system + agent + 前端）
docker compose up -d --build

# 3. 用起来
# 前端页面（推荐）：http://localhost:8082 —— 完整电商 + AI 聊天面板
# 或直接调 agent 接口：
curl "http://localhost:8081/query?q=帮我查询订单&sessionId=demo"
```

> 前置：宿主机需要 `DEEPSEEK_API_KEY`（.env 注入，不写死在镜像里）；`mysql`/`redis` 容器**不占用宿主机端口**，避免和你本机已有的 3306/6379 冲突。
>
> Windows Git Bash 注意：中文参数直接写命令行会按 GBK 编码传过去导致乱码。实测可用的做法：把查询语句存成 UTF-8 文件，再读进变量传给 curl，例如
> `Q=$(cat query.txt); curl -G localhost:8081/query --data-urlencode "q=$Q" --data-urlencode "sessionId=demo"`。
> 注意 `--data-urlencode "q@file"` 在这种 curl 上不可用，别用那个写法。

### 方式二：手动起（本地开发）

```bash
# 需要本机 MySQL(order_db, root/123456) + Redis(6379)

# 终端1：起执行层
cd order-system && mvn spring-boot:run        # :8080

# 终端2：起决策层
cd order-agent-assistant \
  && DEEPSEEK_API_KEY=sk-xxx mvn spring-boot:run   # :8081
```

### 演示一次完整链路

```bash
# ① 注册/登录拿 token（order-system）
curl -X POST localhost:8080/api/user/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"123456","phone":"13800138000"}'
curl -X POST localhost:8080/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"123456"}'   # 取 token

# ② 下单并支付（商品 id=3）
curl -X POST localhost:8080/api/cart -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"productId":3,"quantity":1}'
curl -X POST localhost:8080/api/order -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cartIds":[<cartId>],"receiverName":"zhangsan","receiverPhone":"13800138000","receiverAddress":"beijing"}'
curl -X PUT localhost:8080/api/order/<orderId>/pay -H "Authorization: Bearer $TOKEN"

# ③ 让 agent 查询 / 取消（取消会被闸门拦住）
curl -G "localhost:8081/query" --data-urlencode "q=帮我查一下订单 <orderNo>" --data-urlencode "sessionId=demo"
curl -G "localhost:8081/query" --data-urlencode "q=帮我取消订单 <orderNo>" --data-urlencode "sessionId=demo"
curl -X POST "localhost:8081/approve?sessionId=demo"
curl -G "localhost:8081/query" --data-urlencode "q=我已批准，请继续取消 <orderNo>" --data-urlencode "sessionId=demo"

# ④ 通知中心看到真实退款通知
curl "localhost:8080/api/notification/my" -H "Authorization: Bearer $TOKEN"
```

## 前端（Vue 3 + Vite，精致单页）

完整电商体验 + 右下角 AI 聊天面板，把「AI agent 管订单」做成看得见的演示。技术栈 Vue 3 + Vue Router + Pinia + Vite，不引 UI 组件库（手写 scoped CSS + 设计令牌）。

- **部署**：compose 第 5 个服务，nginx 托管构建产物 + 反代（浏览器只访问 8082 一个源，两个后端零 CORS 改动）
- **页面**：登录/注册 → 商城 → 购物车 → 下单 → 订单列表 → 通知中心 + 右下角 AI 面板
- **核心卖点——批准按钮**：在聊天里对 agent 说「帮我取消订单 <订单号>」→ agent 回复「需要人工确认」→ 气泡里出现 **「批准执行」按钮** → 点击 → agent 真正执行取消 → 订单列表自动刷新成「已取消」+ 通知中心出现退款通知
- **稳定性兜底**：DeepSeek 偶尔会把「取消」误判成「查单」（工具选择有随机性）。前端检测到取消意图但模型只回了查询结果时，会自动补一句命令把模型拉回取消路径，保证演示不赌模型心情

### 本地开发

```bash
cd frontend && npm install && npm run dev   # http://localhost:5173
```

Vite 代理已配好：`/api`→8080、`/query`+`/approve`→8081，同样无跨域。

### 演示话术

1. 打开 http://localhost:8082 → 注册/登录
2. 商城加购 → 下单 → 支付 → 订单列表出现「已支付」；再造一单**不支付**留个「待支付」
3. 右下角打开 AI 助手，发：
   - `帮我查一下订单`
   - `iPhone 15 Ultra 还有货吗`（查库存：模型先按商品名搜出 id，再报库存）
   - `帮我查一下订单 <订单号> 的收货地址`（只读工具，无需批准，直接回地址）
   - `帮我取消订单 <订单号>`
4. 关键一幕：回复「需要人工确认」+ **「批准执行」按钮** → 点击
5. 订单列表自动变「已取消」，通知中心多一条退款通知

> 每次对话调用真实 DeepSeek（有成本），别反复空跑。

## 管理员功能（商品管理）

管理员登录后导航多一个「商品管理」入口：新增 / 编辑 / 上下架商品，列表含已下架商品。新增/编辑时支持**上传商品图片**（选中即上传拿 URL，保存时随商品写入），商城卡片显示真实图片、无图回退渐变占位。

图片上传链路：`POST /api/product/image`（管理员，扩展名白名单 + 文件头魔数校验 + UUID 文件名）→ 存 `app.upload-dir`（Docker 里是命名卷 `product-images:/app/uploads`）→ 返回相对 URL `/uploads/xxx.jpg` → Spring `addResourceHandlers` 静态映射 + nginx `location /uploads/` 反代访问。

- **管理员账号**：`admin / admin123`（order-system 启动时自动初始化、幂等；密码可用配置 `admin.init-password` 改）
- **接口**（`ProductController`，全部校验 ADMIN 角色，非管理员返回 403）：
  - `GET  /api/product/admin/page`  管理列表（含下架）
  - `POST /api/product`             新增（默认上架）
  - `PUT  /api/product/{id}`        编辑
  - `PUT  /api/product/{id}/status` 上下架
- **权限链路**：注册永远建 `USER` → JWT 带 role → `UserContext.isAdmin()` → Controller `checkAdmin()`。管理员账号没有注册入口，只能由启动引导或运维手动建号——刻意设计：提权不该走业务接口。
- **生产安全**：真实系统应移除 `AdminBootstrapRunner`（管理员由运维建号），或至少把 `admin.init-password` 改成环境变量注入的随机值。

## 测试

```bash
cd order-agent-assistant && mvn test    # 22 个用例：AgentLoop / 闸门 / 会话存储 / 工具参数解析
cd order-system && mvn test             # 12 个用例：取消状态机 / 通知中心越权保护
```

不依赖中间件，纯 Mockito 单元测试，任何机器都能跑绿。

## 目录结构

```
order-agent-assistant/     # 决策层（本仓库）
  src/main/java/com/orderagent/
    AgentLoop.java         核心循环（模型=决策点，harness=执行）
    Tool / ToolCall        工具抽象（MCP 模型）
    PermissionGate         权限闸门（只读放行/写操作要批准）
    RedisSessionStore      多轮记忆（TTL + 裁剪 + 损坏兜底）
    PersistedMessage       消息序列化中间格式（解决 Jackson 多态）
    DeepSeekLlmClient      LLM 通道（OpenAI 兼容 API）
    AgentController        REST 入口（/query、/approve）
  src/test/java/           单元测试
  Dockerfile / docker-compose.yml / .env.example

order-system/              # 执行层（同级目录）
  OrderServiceImpl         取消状态机 + 恢复库存 + 插退款事件（Outbox）
  OrderEventService        事件处理（幂等 + 调度器兜底）
  Notification*            通知中心（Service + Controller）

sql/
  order_db.sql             建库基线（含唯一索引 + t_notification）
  migrations/              增量迁移脚本
```
