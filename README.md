# Order Agent Assistant

一个 **AI Agent 管理订单** 的可运行作品：用自然语言让 AI 帮你查单、查商品库存、改收货地址、取消订单；取消已支付的订单会自动触发退款，并在通知中心生成一条真实的退款通知。前端、决策层、执行层三层齐全，Docker 一键起，clone 下来就能跑。

- 🤖 **AI 决策层** `order-agent-assistant`：Agent 循环 + 权限闸门（写操作要人工批准）+ Redis 多轮记忆 + DeepSeek 工具调用 + **MCP 兼容层**（`POST /mcp` 标准协议，Claude Desktop / Cursor 可直接连接调用工具）。模型是唯一决策点，代码只负责执行和搬运。
- ⚙️ **业务执行层** `order-system`：订单状态机 + Transactional Outbox（取消+退款同事务落库）+ 通知中心 + 管理员商品管理（含**商品图片上传**：扩展名/魔数校验 + UUID 文件名 + 静态映射，换图自动删旧图防磁盘孤儿）。业务规则只留一份，agent 不直连数据库。
- 🖥️ **前端** `frontend/`：Vue 3 精致单页——完整电商体验 + 右下角 AI 聊天面板；取消订单时聊天气泡弹出「批准执行」按钮，一键走完「确认 → 执行 → 订单刷新 → 退款通知」闭环。
- 📦 **一键部署**：Docker Compose 起全套（MySQL + Redis + 两个后端 + 前端），浏览器只访问一个源，无跨域。

```
┌───────────────────────────────────────────────────────────────────┐
│  客户端：浏览器（http://localhost:8082）                             │
│    Vue3 电商页 + 右下角 AI 聊天面板（批准按钮在气泡里）               │
└──────────────┬────────────────────────────────────────────────────┘
               │ /api/**（商品/购物车/订单/通知）    /query /approve
               ▼                                        ▼
┌──────────────────────────────┐      ┌───────────────────────────────┐
│  order-system  业务执行层 8080 │      │  order-agent-assistant 决策层 │
│  · 订单状态机（幂等取消）       │◄────►│  8081  AgentLoop: 模型=决策点  │
│  · Transactional Outbox       │HTTP  │  · 权限闸门：写操作人工批准     │
│  · 通知中心（退款通知）         │内部  │  · Redis 多轮记忆（TTL 30min） │
│  · 管理员商品管理              │密钥  │  · DeepSeek 工具调用           │
│                              │      │  · MCP 兼容层（/mcp 协议）       │
└──────────┬────────────────────┘      └──────────────┬────────────────┘
           ▼                                          ▼
      MySQL (order_db)                          Redis（会话记忆）
```

## 核心闭环：AI 取消订单 → 人工确认 → 退款通知

```
用户: "帮我取消订单 2026..."        → 模型决定调 cancel_order
闸门: 写操作，未批准 → 拦截          → 模型转述"需要人工确认后才能执行"
前端聊天气泡出现「批准执行」按钮     → 点击（POST /approve）
模型再次执行 cancel_order           → 订单 CANCELLED + 库存恢复 + REFUND 事件
                                   → 通知中心生成真实退款通知
```

## Agent 可靠性设计

把 Agent 从"能运行"推进到"能安全失败、方便排查"：

- **最大执行步数：8** —— 每次模型调用、每次工具执行都算一步，超限立即停止并给用户可理解的提示，防模型"停不下来"拖进无限循环
- **工具调用具备超时和异常转换** —— 连不上 / 超时 / 4xx / 5xx / 坏 JSON / 业务拒绝，统一转成 `{"success":false,"errorCode":...,"message":...}`，不把 Java 异常堆栈喂给模型
- **写操作必须经过人工批准** —— 权限闸门：只读放行、写操作拦截，批准后才执行
- **工具失败不会直接暴露内部堆栈** —— AgentLoop 兜底：任何工具异常都转成干净的结构化错误，循环不崩
- **每次执行记录 session、步骤、工具、耗时和结果** —— 结构化日志，出问题能还原现场
- **日志脱敏** —— 不打印完整地址/手机号：参数按字段打码，自由文本手机号打码+截断
- **收货信息最小权限** —— order-system 的内部读接口只返回脱敏收货信息（姓名/手机号/地址打码），agent 根本拿不到完整地址；用户端自己的订单页仍显示完整地址
- **业务副作用由 order-system 统一负责** —— 状态机/库存/退款事件只在业务层一份，agent 只管决策和触发

> **收货信息脱敏已落在源头**：此前"日志层拦不全自由文本里的短地址"的局限，已通过 order-system 内部读接口（`/internal/order/byOrderNo`、改地址回显）只返回脱敏收货信息解决——agent 拿不到完整地址/手机号，日志层自然安全。`LogSanitizer` 继续保留为纵深防御（拦截用户输入自由文本里的手机号）。

## 快速开始

前置：`DEEPSEEK_API_KEY` 配到 `.env`（已 gitignore，绝不提交）。

```bash
# 1. 配置 key
cp order-agent-assistant/.env.example order-agent-assistant/.env   # 填 DEEPSEEK_API_KEY=sk-xxx

# 2. 一键起全套（MySQL + Redis + order-system + agent + 前端）
cd order-agent-assistant
docker compose up -d --build

# 3. 打开 http://localhost:8082
```

> MySQL/Redis 容器不占用宿主机端口，避免和你本机已有的 3306/6379 冲突。
> 管理员账号 `admin / admin123`（启动自动初始化，幂等），登录后导航多一个「商品管理」。

## 踩坑记录（面试官最爱问）

这个项目踩过 10 个**真实踩过、真的修好了**的坑，每个都有完整的「现象 → 根因 → 修复 → 面试怎么讲」，见 [PITFALLS.md](order-agent-assistant/PITFALLS.md)：

| # | 坑 | 一句话根因 |
|---|----|-----------|
| 1 | 编辑商品报「无权限」，其他操作正常 | Spring `*` 通配符不区分 HTTP 方法，误放行了管理员的 PUT |
| 2 | 部署新版用户看不到新功能 | 浏览器缓存住旧 index.html，SPA 入口没配 `no-cache` |
| 3 | 聊天返回 200 却「没有收到回复」 | agent 的 axios 实例漏配解包拦截器 |
| 4 | 说「取消订单」模型却调了查单 | LLM 工具选择有随机性，提示词压不住，需双保险 |
| 5 | order-system 容器启动即崩 | Redis 地址硬编码 `localhost`，容器里 localhost=自己 |
| 6 | curl 传中文参数落库乱码 | Windows Git Bash 按 GBK 传参（测试侧，非程序 bug） |
| 7 | 消息对象存 Redis 再读类型不对 | Jackson 多态序列化需中间格式摊平 |
| 8 | 全新部署商品接口报 Unknown column | compose 只挂基线 SQL，增量 migration 不自动执行，基线需同步最新结构 |
| 9 | `@RequestParam Long` 收到非数字返回 500 | 类型转换失败（MethodArgumentTypeMismatchException）没被全局异常处理，掉进 500 兜底 | 客户端输错是 4xx 问题，全局异常处理器要单接类型转换异常 |
| 10 | 批准第二个写操作，模型却去取消订单 | 批准链路（闸门 reason/markApproved/前端 follow-up）把「取消订单」写死在文案里 | 通用机制别点名具体工具：批准消息泛化成「继续执行你刚才要执行的写操作」 |

## 文档导航

| 文档 | 内容 |
|------|------|
| [order-agent-assistant/README.md](order-agent-assistant/README.md) | agent 架构详解、设计决策（面试常问）、完整链路演示话术 |
| [PITFALLS.md](order-agent-assistant/PITFALLS.md) | 踩坑记录：现象 → 根因 → 修复 → 面试怎么讲（10 个真实坑） |
| [sql/](sql/) | 建库基线 `order_db.sql` + 增量迁移脚本 |

## 测试

```bash
cd order-system && mvn test          # 14 个用例：状态机/通知中心/管理员引导
cd order-agent-assistant && mvn test  # 52 个用例：AgentLoop/闸门/会话存储/工具/异常/脱敏/MCP
```

纯 Mockito 单元测试，不依赖中间件，任何机器都能跑绿。

## 目录结构

```
order-agent-assistant/   # AI 决策层 + 前端 + 部署编排
  src/main/java/com/orderagent/   AgentLoop / Tool / PermissionGate / RedisSessionStore
  frontend/                       Vue3 电商 + AI 聊天面板
  Dockerfile / docker-compose.yml / .env.example
order-system/            # 业务执行层
  OrderServiceImpl        取消状态机 + 恢复库存 + 插退款事件（Outbox）
  Notification*           通知中心
  config/AdminBootstrapRunner  管理员账号启动引导（幂等）
sql/                     # 建库基线 + 迁移脚本
```
