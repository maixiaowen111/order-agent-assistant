# Order Agent Assistant

一个 **AI Agent 管理订单** 的可运行作品：用自然语言让 AI 帮你查单、取消订单；取消已支付的订单会自动触发退款，并在通知中心生成一条真实的退款通知。前端、决策层、执行层三层齐全，Docker 一键起，clone 下来就能跑。

- 🤖 **AI 决策层** `order-agent-assistant`：Agent 循环 + 权限闸门（写操作要人工批准）+ Redis 多轮记忆 + DeepSeek 工具调用。模型是唯一决策点，代码只负责执行和搬运。
- ⚙️ **业务执行层** `order-system`：订单状态机 + Transactional Outbox（取消+退款同事务落库）+ 通知中心 + 管理员商品管理。业务规则只留一份，agent 不直连数据库。
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

## 文档导航

| 文档 | 内容 |
|------|------|
| [order-agent-assistant/README.md](order-agent-assistant/README.md) | agent 架构详解、设计决策（面试常问）、完整链路演示话术 |
| [PITFALLS.md](order-agent-assistant/PITFALLS.md) | 踩坑记录：现象 → 根因 → 修复 → 面试怎么讲（7 个真实坑） |
| [sql/](sql/) | 建库基线 `order_db.sql` + 增量迁移脚本 |

## 测试

```bash
cd order-system && mvn test          # 14 个用例：状态机/通知中心/管理员引导
cd order-agent-assistant && mvn test  # 17 个用例：AgentLoop/闸门/会话存储
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
