#!/usr/bin/env bash
# ============================================
# 扁鹊 BianQue 冒烟验收脚本：对运行中的平台执行核心链路断言
# 用法: bash scripts/smoke.sh [BASE_URL] [INGEST_TOKEN]
# 任何一步失败立即退出（非 0）
# ============================================
set -e
BASE="${1:-http://127.0.0.1:8080}"
INGEST_TOKEN="${2:-}"

ok()  { echo "[PASS] $1"; }
fail(){ echo "[FAIL] $1"; exit 1; }

# 1 探活
curl -sf "$BASE/api/health" | grep -q '"status":"UP"' || fail "health"
ok "探活 /api/health"

# 2 未登录 401
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/fleet/overview")" = "401" ] || fail "unauthenticated 401"
ok "未登录访问被拒（401）"

# 3 管理员登录
TOKEN=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' \
  | python -c "import json,sys;print(json.load(sys.stdin)['token'])") || fail "admin login"
ok "管理员登录"

# 4 错误口令 400
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"wrong"}')" = "400" ] || fail "bad password 400"
ok "错误口令拒绝（400）"

# 5 只读角色越权 403
VT=$(curl -sf -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"viewer","password":"viewer123"}' \
  | python -c "import json,sys;print(json.load(sys.stdin)['token'])")
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/diag/execute" \
  -H "Authorization: Bearer $VT" -H 'Content-Type: application/json' \
  -d '{"instanceId":"pod-3","command":"thread -n 3"}')" = "403" ] || fail "viewer 403"
ok "只读角色执行被拒（403）"

# 6 拟真执行 + 通道标识
RES=$(curl -sf -X POST "$BASE/api/diag/execute" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"instanceId":"pod-3","command":"thread -n 3"}')
echo "$RES" | grep -q '"success":true' || fail "diag execute"
echo "$RES" | grep -q '"channel":"simulated"' || fail "channel=simulated"
ok "拟真通道执行成功"

# 7 高危拦截
curl -sf -X POST "$BASE/api/diag/execute" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"instanceId":"pod-3","command":"ognl 1+1"}' \
  | grep -q '"success":false' || fail "ognl blocked"
ok "高危黑名单拦截（ognl）"

# 8 审计哈希链
curl -sf "$BASE/api/audit/verify" -H "Authorization: Bearer $TOKEN" \
  | grep -q '"valid":true' || fail "audit verify"
ok "审计哈希链校验通过"

# 9 巡检落库
curl -sf -X POST "$BASE/api/checkup/run" -H "Authorization: Bearer $TOKEN" \
  | grep -q '"instanceCount"' || fail "checkup run"
ok "巡检运行"

# 10 文档中心
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/docs/catalog" -H "Authorization: Bearer $TOKEN")" = "200" ] || fail "docs catalog"
ok "文档中心目录"

# 11 告警接入（配置了 ingest-token 才执行）
if [ -n "$INGEST_TOKEN" ]; then
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/alerts/ingest" \
    -H "X-Alert-Token: $INGEST_TOKEN" -H 'Content-Type: application/json' \
    -d '{"appId":"order-app","instanceId":"pod-3","message":"smoke test alert","severity":"Warning"}')
  [ "$CODE" = "200" ] || fail "alert ingest ($CODE)"
  ok "告警接入入站（ingest）"
else
  echo "[SKIP] 告警接入（未提供 INGEST_TOKEN）"
fi

echo ""
echo "=== 冒烟验收全部通过 ==="
