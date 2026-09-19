#!/usr/bin/env bash
# =====================================================================
# e2e-approval.sh — APPROVAL 模式挂起/恢复 e2e 测试（P1 盲区覆盖）
#
# 场景：
#   A1 批准路径：APPROVAL agent → create_plan 挂起 → approve → 计划执行完
#   A2 拒绝路径：create_plan 挂起 → reject → 模型不执行、说明被拒
#
# 断言：
#   - PLAN_APPROVAL_REQUESTED 事件出现（挂起真发生）
#   - 不批准时 turn 不完成（挂起持续，session 非终态）
#   - approve 后 PLAN_APPROVAL_RESOLVED 出现且 turn 走完（恢复真发生）
#   - 拒绝后无计划执行动作（无 generate_spec/spawn_agent）
#
# 用法: BASE=http://localhost:8081 scripts/e2e-approval.sh
# 依赖: server 运行中（8081）、jq 不需要（用 python3）
# =====================================================================
set -euo pipefail

BASE="${BASE:-http://localhost:8081}"
MODEL_ID="${MODEL_ID:-mdl_b12f4148}"          # agnes-2.5-flash（默认，免费）
TIMEOUT="${TIMEOUT:-300}"                      # 批准后等待 turn 完成的秒数
KEEP_TMP="${KEEP_TMP:-0}"

# 颜色
C_G="\033[1;32m"; C_R="\033[1;31m"; C_B="\033[1;36m"; C_0="\033[0m"
say()  { printf "${C_B}== %s ==${C_0}\n" "$*"; }
pass() { printf "${C_G}判定: PASS${C_0}\n"; }
fail() { printf "${C_R}判定: FAIL — %s${C_0}\n" "$*"; exit 1; }

[ -n "$(curl -s -m 3 "$BASE/v1/ready" 2>/dev/null || true)" ] || fail "server 未就绪 ($BASE)"

# ------------------------------------------------------------------
WORK="$(mktemp -d /tmp/mederi-approval.XXXXXX)"
trap '[ "$KEEP_TMP" = "1" ] || rm -rf "$WORK"' EXIT
FIXTURE="$WORK/fixture"
mkdir -p "$FIXTURE"

# fixture：极简 python 项目（README + calc.py）
cat > "$FIXTURE/README.md" <<'EOF'
# approval-fixture
A tiny python calculator used by mederi e2e approval tests.
EOF
cat > "$FIXTURE/calc.py" <<'EOF'
def add(a, b):
    return a + b
EOF

MODEL_JSON="$WORK/model.json"
curl -s -m 10 "$BASE/v1/models" | MODEL_ID="$MODEL_ID" python3 -c '
import json,sys,os
models=json.load(sys.stdin)
mid=os.environ["MODEL_ID"]
m=next((x for x in models if x.get("id")==mid), None)
if m is None: sys.stderr.write(f"model {mid} not found\n"); sys.exit(1)
json.dump(m, sys.stdout)
' > "$MODEL_JSON"

# ------------------------------------------------------------------
# 公共步骤：建项目 + APPROVAL 会话 + 订阅 SSE + 发消息 → 等 PLAN_APPROVAL_REQUESTED
# 输出: APPROVAL_SID / APPROVAL_PLANID / SSE_PID / EVENTS_LOG
# ------------------------------------------------------------------
start_approval_session() {  # $1 = 任务提示词
  PROJ="$(curl -s -m 10 -X POST "$BASE/v1/projects" -H 'Content-Type: application/json' \
    --data "{\"name\":\"e2e-approval\",\"directory\":\"$FIXTURE\"}")"
  PID_A="$(echo "$PROJ" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"

  SESS="$(curl -s -m 10 -X POST "$BASE/v1/sessions" -H 'Content-Type: application/json' \
    --data "{\"projectId\":\"$PID_A\",\"agent\":{\"id\":\"approval\",\"mode\":\"APPROVAL\"}}")"
  APPROVAL_SID="$(echo "$SESS" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"
  echo "  session: $APPROVAL_SID"

  EVENTS_LOG="$WORK/events.log"
  : > "$EVENTS_LOG"
  curl -sN -m "$((TIMEOUT+120))" "$BASE/v1/sessions/$APPROVAL_SID/events" > "$EVENTS_LOG" 2>/dev/null &
  SSE_PID=$!
  sleep 1

  curl -s -m 30 -X POST "$BASE/v1/sessions/$APPROVAL_SID/messages" -H 'Content-Type: application/json' \
    --data "{\"text\":\"$1\",\"model\":$(cat "$MODEL_JSON"),\"agent\":null,\"thinkingLevel\":null}" > /dev/null

  # 等 PLAN_APPROVAL_REQUESTED（最多 180s）
  echo "  等待 PLAN_APPROVAL_REQUESTED..."
  for i in $(seq 1 180); do
    if grep -q "PLAN_APPROVAL_REQUESTED" "$EVENTS_LOG" 2>/dev/null; then break; fi
    if grep -q "MESSAGE_ERROR" "$EVENTS_LOG" 2>/dev/null; then break; fi
    sleep 1
  done

  if ! grep -q "PLAN_APPROVAL_REQUESTED" "$EVENTS_LOG"; then
    kill $SSE_PID 2>/dev/null || true
    echo "  --- 事件流 ---"; tail -30 "$EVENTS_LOG"
    fail "180s 内未收到 PLAN_APPROVAL_REQUESTED（挂起未发生）"
  fi

  APPROVAL_PLANID="$(grep "PLAN_APPROVAL_REQUESTED" "$EVENTS_LOG" | head -1 | \
    python3 -c 'import json,sys
for line in sys.stdin:
    line=line.strip()
    if not line.startswith("data:"): continue
    try: ev=json.loads(line[5:])
    except Exception: continue
    p=ev.get("payload",{})
    if p.get("planId"): print(p["planId"]); break')"
  if [ -z "$APPROVAL_PLANID" ]; then
    kill $SSE_PID 2>/dev/null || true
    fail "PLAN_APPROVAL_REQUESTED 事件存在但解析不到 planId"
  fi
  echo "  plan: $APPROVAL_PLANID（挂起中）"
}

wait_turn_done() {  # $1 = 最长等待秒；打后续工具序列到 stdout
  local LIMIT=$1
  for i in $(seq 1 "$LIMIT"); do
    if grep -q "MESSAGE_COMPLETED\|MESSAGE_ERROR\|TURN_ABORTED" "$EVENTS_LOG" 2>/dev/null; then break; fi
    sleep 1
  done
  sleep 1
  kill $SSE_PID 2>/dev/null || true
}

tool_sequence() {  # 从 events.log 抽 TOOL_CALLED 工具名序列
  python3 - "$EVENTS_LOG" <<'PY'
import json,sys
seen=[]
for line in open(sys.argv[1]):
    line=line.strip()
    if not line.startswith("data:"): continue
    try: ev=json.loads(line[5:])
    except Exception: continue
    if ev.get("type")=="TOOL_CALLED":
        seen.append(ev.get("payload",{}).get("tool",""))
print(" ".join(t for t in seen if t))
PY
}

session_state() {  # session 终态
  curl -s -m 5 "$BASE/v1/sessions/$APPROVAL_SID" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("status","?"))' 2>/dev/null || echo "?"
}

# =====================================================================
say "A1: 批准路径 — create_plan 挂起 → approve → 计划执行完"
# =====================================================================
PROMPT_A1="Use the plan workflow to: create a file NOTES.md containing the line 'approved plan ran'. One subtask is enough. Wait for my approval, then execute."
start_approval_session "$PROMPT_A1"

# ---- 断言 1: 挂起期间 turn 不完成（给 20s 观察窗）----
say "A1.1 挂起期观察（20s，不应出现 MESSAGE_COMPLETED/ERROR）"
sleep 20
if grep -q "MESSAGE_COMPLETED\|MESSAGE_ERROR" "$EVENTS_LOG" 2>/dev/null; then
  kill $SSE_PID 2>/dev/null || true
  fail "挂起期间 turn 已结束（approval 没有真正挂起 turn）"
fi
STATE_SUSPENDED="$(session_state)"
echo "  20s 挂起观察通过，session 状态: $STATE_SUSPENDED（非终态即可）"

# ---- 断言 2: approve 后恢复 ----
say "A1.2 approve → 恢复执行"
HTTP="$(curl -s -m 10 -o "$WORK/appr.out" -w '%{http_code}' -X POST \
  "$BASE/v1/sessions/$APPROVAL_SID/plans/$APPROVAL_PLANID/approve" \
  -H 'Content-Type: application/json' --data '{"approved": true}')"
[ "$HTTP" = "200" ] || { kill $SSE_PID 2>/dev/null || true; cat "$WORK/appr.out"; fail "approve HTTP $HTTP"; }
grep -q "PLAN_APPROVAL_RESOLVED" "$EVENTS_LOG" || { kill $SSE_PID 2>/dev/null || true; fail "approve 200 但无 PLAN_APPROVAL_RESOLVED 事件"; }
echo "  approve 200 + RESOLVED 事件 ✓，等 turn 完成..."

wait_turn_done "$TIMEOUT"
SEQ_A1="$(tool_sequence)"
echo "  工具序列: $SEQ_A1"
echo "  session 终态: $(session_state)"

grep -q "MESSAGE_ERROR" "$EVENTS_LOG" && fail "恢复后 turn MESSAGE_ERROR"
case "$SEQ_A1" in
  *generate_spec*spawn_agent*verify_subtask*) echo "  计划执行链完整 ✓" ;;
  *) fail "恢复后未走完计划执行链（generate_spec/spawn_agent/verify_subtask 缺失）" ;;
esac
[ -f "$FIXTURE/NOTES.md" ] && grep -q "approved plan ran" "$FIXTURE/NOTES.md" \
  || fail "产物缺失：NOTES.md 未包含预期内容"
echo "  产物 NOTES.md 内容正确 ✓"
pass "A1 批准路径"

# =====================================================================
say "A2: 拒绝路径 — create_plan 挂起 → reject → 模型不执行"
# =====================================================================
PROMPT_A2="Use the plan workflow to: create a file REJECTED.md containing 'never ran'. One subtask. Wait for my approval."
start_approval_session "$PROMPT_A2"

HTTP="$(curl -s -m 10 -o "$WORK/rej.out" -w '%{http_code}' -X POST \
  "$BASE/v1/sessions/$APPROVAL_SID/plans/$APPROVAL_PLANID/approve" \
  -H 'Content-Type: application/json' --data '{"approved": false}')"
[ "$HTTP" = "200" ] || { kill $SSE_PID 2>/dev/null || true; cat "$WORK/rej.out"; fail "reject HTTP $HTTP"; }
echo "  reject 200，等模型反应..."

wait_turn_done 120
SEQ_A2="$(tool_sequence)"
echo "  工具序列: $SEQ_A2"
echo "  session 终态: $(session_state)"

case "$SEQ_A2" in
  *generate_spec*|*spawn_agent*) fail "拒绝后模型仍执行了计划（generate_spec/spawn_agent 出现）" ;;
esac
[ ! -f "$FIXTURE/REJECTED.md" ] || fail "拒绝后产物 REJECTED.md 竟然存在"
grep -q "MESSAGE_ERROR" "$EVENTS_LOG" && fail "拒绝后 turn MESSAGE_ERROR（应为正常收尾：模型说明计划被拒）"
pass "A2 拒绝路径"

# =====================================================================
echo ""
say "总结"
echo "  A1 批准路径 : PASS（挂起→approve→恢复→产物）"
echo "  A2 拒绝路径 : PASS（挂起→reject→不执行→正常收尾）"
echo "  工作目录保留: $WORK (KEEP_TMP=1)"
