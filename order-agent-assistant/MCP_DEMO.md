# MCP 演示：让 Claude Desktop / Cursor 直接调用 agent 工具

agent 是一个**标准 MCP server**（`POST /mcp`，Streamable HTTP transport）。任何 MCP 客户端连上后，就能把「查库存 / 查订单 / 改地址 / 取消订单」当工具用，模型自己决定什么时候调。

一个关键区别：**写操作在 MCP 层也被权限闸门拦**——客户端里的模型调到写工具会得到 `isError:true` 的「需要人工批准」结果，**批准发生在浏览器里**（`POST /approve`），MCP 客户端这边绕不过去。这是作品集的安全叙事核心：模型只有「提议权」，人保留「决定权」。

## 前置

1. 两个服务都起：order-system（:8080）+ agent（:8081），Redis 在，`DEEPSEEK_API_KEY` 配到 `.env`（快速开始见根目录 README）
2. agent 端口确认是 8081（`application.yml` 默认即 8081）
3. **先拿登录 token**：`/mcp` 和 `/query` 一样强制登录（`Authorization: Bearer <order-system JWT>`）。在 order-system 注册/登录拿 token：
   ```bash
   curl -X POST localhost:8080/api/user/register -H "Content-Type: application/json" \
     -d '{"username":"demo","password":"123456","phone":"13800138000"}'
   curl -X POST localhost:8080/api/user/login -H "Content-Type: application/json" \
     -d '{"username":"demo","password":"123456"}'    # 返回里的 token 存成 $TOKEN
   ```

## 方式一：Claude Desktop

Windows 配置文件在 `%APPDATA%\Claude\claude_desktop_config.json`，加一个 http 类型的 server，**headers 里带上登录 token**（不带会握手 401）：

```json
{
  "mcpServers": {
    "order-agent": {
      "type": "http",
      "url": "http://localhost:8081/mcp",
      "headers": {
        "Authorization": "Bearer 你的token"
      }
    }
  }
}
```

存盘后**重启 Claude Desktop**。设置里能看到工具列表（4 个工具），对话里就能直接用了。

## 方式二：Cursor

Settings → MCP Servers → `+ Add`：
- Type：`http`
- URL：`http://localhost:8081/mcp`
- Headers：`{"Authorization": "Bearer 你的token"}`

加完在 MCP 面板能看到 4 个工具及参数 schema，Agent 模式里让模型「查一下库存」就会触发。

## 方式三：MCP Inspector（浏览器调试，可选）

官方调试工具，能看每个请求的 JSON-RPC 报文。需网络装包：

```bash
npx @modelcontextprotocol/inspector
```

打开后把 URL 填 `http://localhost:8081/mcp`，**Headers 填 `Authorization: Bearer 你的token`** 再连接（Inspector 是浏览器应用，所以 `WebConfig` 给 `/mcp` 开了 CORS）。适合给面试官演示「协议报文长什么样」。

## 试什么

**只读（直接能过）：**
> 查一下 iPhone 还有货吗
> 订单 2026... 的收货地址是什么

**写操作（演示批准闭环）：**
1. 让模型「把订单 2026... 的地址改成 上海市浦东新区」
2. 模型调 `update_order_address` → 未批准 → 返回 `isError:true`，正文带「需要人工确认 + 批准入口 + 本次会话 sessionId」（MCP 无会话概念，会话固定是 `mcp-<你的userId>`，block 响应里直接给出，也可从登录 token 的 userId 推导）
3. 批准（`/approve` 也要登录凭证，body 里的 sessionId 填 `mcp-<你的userId>`）：
   ```bash
   curl -X POST "http://localhost:8081/approve" -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" -d '{"sessionId":"mcp-<你的userId>"}'
   ```
   （前端聊天面板会直接弹「批准执行」按钮，点了走同一条链路；MCP 会话批准后**跳过**"注入已批准消息"——它不是聊天会话，没有 AgentLoop 历史可注入）
4. 再让模型重试**同一操作** → 这次真正执行（批准一次性：改参数 / 换工具 / 换用户 / 过期都要重新批准）

## 原理（30 秒讲清楚）

| 层 | 做了什么 |
|----|---------|
| Transport | Streamable HTTP：单 `POST /mcp`；通知回 **202** 空 body；`initialize` 做**协议版本协商**（支持 2025-06-18 / 2025-03-26，客户端版本受支持就回声） |
| 发现 | `tools/list` 把 Tool 接口的 name/description/inputSchema 原样暴露成 MCP 工具 |
| 调用 | `tools/call` 只读直接跑；**写工具走同一个权限闸门**：未批准 → `isError:true`（正文带 /approve 入口 + `mcp-<userId>` 会话）；已批准 → 真正执行 + 消费一次性批准 |
| 刻意不做 | 会话管理（`Mcp-Session-Id`）和 SSE 流式响应都是规范里对服务器的可选能力，跳过；手写 JSON-RPC 零依赖，将来要接官方 SDK transport 这层业务代码不动 |

完整握手序列（对应 `McpHandshakeTest`）：`initialize` → `notifications/initialized`(202) → `tools/list` → `tools/call` 只读 → `tools/call` 写未批准被拦（带 sessionId）→ `/approve`（`mcp-<userId>`）→ `tools/call` 写已批准真正执行。

## 常见问题

- **客户端连不上 / 握手 401**：确认 agent 起着、URL 没拼错（`http://localhost:8081/mcp`，不是 8080）；**`/mcp` 要登录**——headers 里 `Authorization: Bearer <token>` 是否填了、token 是不是刚从 order-system 登录拿的（过期/不对会 401）；看 agent 日志有没有请求进来
- **写操作永远成功不了**：批准是 per-session 的。MCP 的会话固定是 `mcp-<你的userId>`（block 响应正文里也直接给出），`POST /approve` 的 body 里 sessionId 填它；批准后**重新让模型执行同一操作**那一步（不是客户端自动重试）。批准一次性：改参数 / 换工具 / 换用户 / 过期都要重新批准
- **同一会话连着拦下两个不同操作，批准的是哪一个**：pending 首拦优先——先被拦下的那个优先，批准放行它；后一个不会顶掉前一个，须等第一个批准/作废后才能被批准。所以人批准的一定是他第一次看到的那次操作，不会「批准了 A 实际放行 B」。
- **想改端口**：`application.yml` 的 `server.port`，Claude Desktop 配置里的 URL 同步改
