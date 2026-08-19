# 订单助手前端（Vue 3 + Vite）

「AI agent 帮你管订单」的演示前端。完整电商体验（商品/购物车/下单/订单/通知）+ 右下角 AI 聊天面板。

## 技术栈

- Vue 3（`<script setup>`）+ Vue Router 4 + Pinia
- Vite 5，axios，无 UI 组件库（手写 scoped CSS + 设计令牌 `src/assets/base.css`）

## 本地开发

前置：两个后端在跑（`order-system` :8080、`order-agent-assistant` :8081），以及 `.env` 里配好 `DEEPSEEK_API_KEY`。

```bash
npm install
npm run dev      # http://localhost:5173
```

Vite 代理已配置：`/api` → 8080，`/query` `/approve` → 8081。浏览器只跟 5173 说话，无跨域。

## 生产 / Docker

根目录 `docker-compose.yml` 里有 `frontend` 服务，nginx 托管构建产物并反代 API：

```bash
docker compose up -d --build
# 打开 http://localhost:8082
```

## 演示话术（重点）

1. 注册 / 登录 → 商城加购 → 下单 → 支付 → 订单列表看到「已支付」。
2. 再造一单**不支付**，留一条「待支付」。
3. 右下角打开 AI 助手，输入：
   - `帮我查一下订单`
   - `帮我取消订单 <订单号>`
4. 关键一幕：agent 回复「需要人工确认」，气泡里出现 **「批准执行」按钮**。
   （若模型偶尔只回了查单结果，前端会自动补一句命令把它拉回取消路径——这是稳定性兜底，正常情况看不到。）
5. 点按钮 → agent 真正执行取消 → 订单列表自动变成「已取消」，通知中心多一条退款通知。

> 每次对话会调用真实 DeepSeek 模型（有成本），别反复空跑。

## 管理员

- 账号：`admin / admin123`（order-system 启动自动初始化，幂等）
- 登录后导航多「商品管理」入口：新增 / 编辑 / 上下架商品
- 普通用户看不到入口，手输 `/admin/products` 会被路由守卫弹回首页
