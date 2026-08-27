# MCP 演示：让 Claude Desktop / Cursor 直接调用 agent 工具

agent 是一个**标准 MCP server**（`POST /mcp`，Streamable HTTP transport）。任何 MCP 客户端连上后，就能把「查库存 / 查订单 / 改地址 / 取消订单」当工具用，模型自己决定什么时候调。

一个关键区别：**写操作在 MCP 层也被权限闸门拦**——客户端里的模型调到写工具会得到 `isError:true` 的「需要人工批准」结果，**批准发生在浏览器里**（`POST /approve`），MCP 客户端这边绕不过去。这是作品集的安全叙事核心：模型只有「提议权」，人保留「决定权」。

## 前置

1. 两个服务都起：order-system（:8080）+ agent（:8081），Redis 在，`DEEPSEEK_API_KEY` 配到 `.env`（快速开始见根目录 README）
2. agent 端口确认是 8081（`application.yml` 默认即 8081）

## 方式一：Claude Desktop

Windows 配置文件在 `%APPDATA%\Claude\claude_desktop_config.json`，加一个 http 类型的 server：

```json
{
  "mcpServers": {
    "order-agent": {
      "type": "http",
      "url": "http://localhost:8081/mcp"
    }
  }
}
```

存盘后**重启 Claude Desktop**。设置里能看到工具列表（4 个工具），对话里就能直接用了。

## 方式二：Cursor

Settings → MCP Servers → `+ Add`：
- Type：`http`
- URL：`http://localhost:8081/mcp`

加完在 MCP 面板能看到 4 个工具及参数 schema，Agent 模式里让模型「查一下库存」就会触发。

## 方式三：MCP Inspector（浏览器调试，可选）

官方调试工具，能看每个请求的 JSON-RPC 报文。需网络装包：

```bash
npx @modelcontextprotocol/inspector
```

打开后把 URL 填 `http://localhost:8081/mcp` 连接（Inspector 是浏览器应用，所以 `WebConfig` 给 `/mcp` 开了 CORS）。适合给面试官演示「协议报文长什么样」。

## 试什么

**只读（直接能过）：**
> 查一下 iPhone 还有货吗
> 订单 2026... 的收货地址是什么

**写操作（演示批准闭环）：**
1. 让模型「把订单 2026... 的地址改成 上海市浦东新区」
2. 模型调 `update_order_address` → 被闸门拦 → 返回 `isError:true`「需要人工确认后才能执行」，客户端里模型会转述这句话
3. 打开浏览器（或 curl）批准：
   ```bash
   curl -X POST "http://localhost:8081/approve?sessionId=你的会话id"
   ```
   （前端聊天面板会直接弹「批准执行」按钮，点了就走同一条链路）
4. 再让模型重试 → 这次真正改成功

## 原理（30 秒讲清楚）

| 层 | 做了什么 |
|----|---------|
| Transport | Streamable HTTP：单 `POST /mcp`；通知回 **202** 空 body；`initialize` 做**协议版本协商**（支持 2025-06-18 / 2025-03-26，客户端版本受支持就回声） |
| 发现 | `tools/list` 把 Tool 接口的 name/description/inputSchema 原样暴露成 MCP 工具 |
| 调用 | `tools/call` 只读直接跑；**写工具走同一个权限闸门**，返回 `isError:true`，绝不真正执行 |
| 刻意不做 | 会话管理（`Mcp-Session-Id`）和 SSE 流式响应都是规范里对服务器的可选能力，跳过；手写 JSON-RPC 零依赖，将来要接官方 SDK transport 这层业务代码不动 |

完整握手序列（对应 `McpHandshakeTest`）：`initialize` → `notifications/initialized`(202) → `tools/list` → `tools/call` 只读 → `tools/call` 写被拦。

## 常见问题

- **客户端连不上 / 握手失败**：确认 agent 起着、URL 没拼错（`http://localhost:8081/mcp`，不是 8080）；看 agent 日志有没有请求进来
- **写操作永远成功不了**：批准是 per-session 的，浏览器 `POST /approve` 时 sessionId 要和客户端会话对应；批准后**重新让模型执行**那一步（不是客户端自动重试）
- **想改端口**：`application.yml` 的 `server.port`，Claude Desktop 配置里的 URL 同步改
