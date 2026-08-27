#!/usr/bin/env bash
# =============================================================================
# 端到端冒烟测试：一键验证「代码 + 中间件 + 配置」真的协作。
#
# 单元测试（mvn test）用 Mockito 隔离了中间件，测不到「MySQL/Redis 真起来、
# 两个服务的 X-Internal-Key 对得上、收货信息真的脱敏了」这类协作问题。
# 本脚本起真中间件 + 两个后端，跑一条核心链路做断言，塞进 CI 当冒烟 job。
#
# 为什么用 MCP 而不是 /query 接口：/query 会调真实 DeepSeek（有成本、随机）。
# MCP 的 tools/call 直接执行工具，绕开模型——所以 DEEPSEEK_API_KEY 填占位符
# dummy-smoke-key 也能跑，且不花钱、不依赖外网。
#
# 用法：
#   bash scripts/smoke.sh          # 需要本机 Docker 在运行
#   全部绿会打印 ✓；任何一步失败打印 ✗ 并退出非零，容器自动清理。
#
# 前提：8080/8081 没被本机其他服务占用（和 compose 发布端口一致）。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT/order-agent-assistant/docker-compose.yml")
OS="http://localhost:8080"    # order-system（执行层）
AGENT="http://localhost:8081" # agent（决策层）

GREEN=$'\e[32m'; RED=$'\e[31m'; RESET=$'\e[0m'
step() { printf '\n▶ %s\n' "$1"; }
ok()   { printf '%s  ✓ %s%s\n' "$GREEN" "$1" "$RESET"; }
fail() { printf '%s  ✗ %s%s\n' "$RED" "$1" "$RESET"; exit 1; }

# 断言一整串文本包含某个固定子串（-F 字面匹配，规避正则特殊字符）
assert_contains() { # $1=haystack $2=needle $3=步骤名
  if ! printf '%s' "$1" | grep -qF "$2"; then
    fail "$3 —— 期望包含「$2」，实际：$(printf '%s' "$1" | head -c 300)"
  fi
  ok "$3"
}

# 轮询等 HTTP 就绪（$1=标签 $2=最大秒数，其余为 curl 参数），200 才算活
wait_for() {
  local label=$1 max=$2 t=0; shift 2
  while :; do
    if curl -s -o /dev/null -w '%{http_code}' "$@" 2>/dev/null | grep -q '^200$'; then
      ok "$label 就绪"; return 0
    fi
    t=$((t + 2))
    [ "$t" -lt "$max" ] || fail "等待 $label 超时（${max}s）—— 中间件或服务没起来？"
    sleep 2
  done
}

mcp_call() { # $1=request-id $2=方法 $3=params JSON（可空）
  local body
  if [ -n "${3:-}" ]; then
    body="{\"jsonrpc\":\"2.0\",\"id\":$1,\"method\":\"$2\",\"params\":$3}"
  else
    body="{\"jsonrpc\":\"2.0\",\"id\":$1,\"method\":\"$2\"}"
  fi
  curl -s -X POST "$AGENT/mcp" -H "Content-Type: application/json" -d "$body"
}

# 从 JSON 里抓第一个字段值（jq 在 Windows Git Bash 不保证有，用 grep/sed 免依赖）
# 用法：echo '{"token":"abc"}' | json_get token   → abc ；数字字段同样返回裸值
json_get() { # $1=字段名；JSON 从 stdin 读
  grep -oE "\"${1}\":\"?[^\",}]*\"?" | head -1 | sed -E "s/^\"${1}\":\"?//; s/\"?$//"
}

docker info >/dev/null 2>&1 || {
  echo "✗ Docker 没在运行（或 docker 不在 PATH）。请先启动 Docker Desktop 再跑冒烟。"; exit 1
}

echo "============================================================="
echo "  order-agent-assistant 端到端冒烟"
echo "============================================================="

step "0 起全套（MySQL + Redis + order-system + agent）"
# DEEPSEEK_API_KEY 注入占位符：MCP tools/call 绕开模型，不触真 key、不花钱。
# --wait 等 mysql/redis 健康检查通过；order-system/agent 无健康检查，后面用 HTTP 轮询。
DEEPSEEK_API_KEY=dummy-smoke-key "${COMPOSE[@]}" up -d --build --wait --wait-timeout 300 \
  mysql redis order-system agent
trap 'printf "\n清理：停容器\n"; "${COMPOSE[@]}" down >/dev/null 2>&1 || true' EXIT

step "1 等服务就绪"
wait_for "order-system" 120 "$OS/api/product/page"
wait_for "agent(MCP)" 120 -X POST "$AGENT/mcp" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":0,"method":"ping"}'

step "2 MCP 握手（initialize：协议版本协商 + 服务身份）"
HANDSHAKE=$(mcp_call 1 initialize '{"protocolVersion":"2025-06-18"}')
assert_contains "$HANDSHAKE" '"name":"order-agent"'   "initialize 回 serverInfo.name=order-agent"
assert_contains "$HANDSHAKE" '"protocolVersion":"2025-06-18"' "协议版本回声 2025-06-18"

step "3 发现工具（tools/list 暴露 4 个）"
TOOLS=$(mcp_call 2 "tools/list")
for t in query_product_stock query_order cancel_order update_order_address; do
  assert_contains "$TOOLS" "$t" "tools/list 暴露 $t"
done

step "4 只读工具直接执行（X-Internal-Key 配对 + 真实 MySQL 查询）"
STOCK=$(mcp_call 3 "tools/call" '{"name":"query_product_stock","arguments":{"productId":1}}')
assert_contains "$STOCK" 'iPhone 15 Ultra' "查库存回真实商品名（内部密钥配对成功）"

# —— 造一单全新的，不依赖种子数据状态（种子订单可能被之前演示改过）——
step "5 造一单全新的（注册 → 加购 → 下单 → 支付）"
UNAME="smoke$(date +%s)"
REG=$(curl -s -X POST "$OS/api/user/register" -H "Content-Type: application/json" \
  -d "{\"username\":\"$UNAME\",\"password\":\"smoke123\",\"phone\":\"13800138000\"}")
assert_contains "$REG" '"code":200' "注册成功"

TOKEN=$(curl -s -X POST "$OS/api/user/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$UNAME\",\"password\":\"smoke123\"}" | json_get token)
[ -n "$TOKEN" ] || fail "登录没拿到 token"
ok "登录拿到 token"

curl -s -X POST "$OS/api/cart" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"productId":1,"quantity":1}' \
  | grep -q '"code":200' || fail "加购失败"
ok "加购成功"

CART_ID=$(curl -s "$OS/api/cart" -H "Authorization: Bearer $TOKEN" | json_get id)
[ -n "$CART_ID" ] || fail "没拿到 cartId"
ok "拿到 cartId=$CART_ID"

ORDER_JSON=$(curl -s -X POST "$OS/api/order" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"cartIds\":[$CART_ID],\"receiverName\":\"smoke\",\"receiverPhone\":\"13800138000\",\"receiverAddress\":\"shanghai-road-1\"}")
ORDER_ID=$(printf '%s' "$ORDER_JSON" | json_get id)
ORDER_NO=$(printf '%s' "$ORDER_JSON" | json_get orderNo)
[ -n "$ORDER_NO" ] || fail "下单没拿到 orderNo：$(printf '%s' "$ORDER_JSON" | head -c 300)"
ok "下单成功 orderNo=$ORDER_NO（id=$ORDER_ID）"

curl -s -X PUT "$OS/api/order/$ORDER_ID/pay" -H "Authorization: Bearer $TOKEN" \
  | grep -q '"code":200' || fail "支付失败"
ok "支付成功 → PAID"

step "6 agent 查这单 → 收货信息已脱敏（源头最小权限，贯穿全链路）"
ORDER_RESP=$(mcp_call 4 "tools/call" \
  "{\"name\":\"query_order\",\"arguments\":{\"orderNo\":\"$ORDER_NO\"}}")
assert_contains "$ORDER_RESP" '138****8000' "收货电话脱敏 138****8000（agent 拿不到完整手机号）"
assert_contains "$ORDER_RESP" 'shangh***'  "收货地址脱敏（前6字+***）"
assert_contains "$ORDER_RESP" 's*e'        "收货人脱敏 smoke→s*e"

step "7 写操作被闸门拦（MCP 层也绕不过人工批准）"
WRITE=$(mcp_call 5 "tools/call" \
  "{\"name\":\"update_order_address\",\"arguments\":{\"orderNo\":\"$ORDER_NO\",\"address\":\"beijing-road-2\"}}")
assert_contains "$WRITE" '"isError":true' "改地址被闸门拦下（isError:true，未执行）"

step "8 确认地址没被改（写操作真的没执行）"
AFTER=$(mcp_call 6 "tools/call" \
  "{\"name\":\"query_order\",\"arguments\":{\"orderNo\":\"$ORDER_NO\"}}")
assert_contains "$AFTER" 'shangh***' "地址仍是原值（被拦的写没落库）"
if printf '%s' "$AFTER" | grep -qF 'beijin***'; then fail "地址被改了！写闸门漏了"; fi
ok "地址未被篡改"

echo
echo "============================================================="
echo "  全部通过 ✅  代码 + MySQL + Redis + 内部密钥 + 脱敏 + 写闸门 协作正常"
echo "============================================================="
