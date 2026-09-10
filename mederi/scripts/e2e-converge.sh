#!/usr/bin/env bash
# =====================================================================
# e2e-converge.sh — converge_plan 故意 FAIL 分支 e2e 测试（P1 盲区覆盖）
#
# 场景：fixture 预置只读文件（chmod 444）→ executor 写入必失败（OS 级
# permission denied，与模型/沙箱无关）→ verify FAIL → converge_plan
# 追加补救子任务 → 重执行 → PASS → 归档。
#
# 断言：
#   C1 首轮执行失败（verify FAIL / PLAN_PROGRESS failed>=1）
#   C2 converge_plan 被调用且计划追加补救子任务
#   C3 补救子任务重执行后 verify PASS、全子任务 COMPLETED、计划归档
#   C4 产物 locked.txt = "v2"
#
# 用法: BASE=http://localhost:8081 scripts/e2e-converge.sh
# =====================================================================
set -euo pipefail

BASE="${BASE:-http://localhost:8081}"
MODEL_ID="${MODEL_ID:-mdl_b12f4148}"
TIMEOUT="${TIMEOUT:-480}"

C_G="\033[1;32m"; C_R="\033[1;31m"; C_B="\033[1;36m"; C_0="\033[0m"
say()  { printf "${C_B}== %s ==${C_0}\n" "$*"; }
pass() { printf "${C_G}判定: PASS${C_0}\n"; }
fail() { printf "${C_R}判定: FAIL — %s${C_0}\n" "$*"; exit 1; }

[ -n "$(curl -s -m 3 "$BASE/v1/ready" 2>/dev/null || true)" ] || fail "server 未就绪 ($BASE)"

WORK="$(mktemp -d /tmp/mederi-converge.XXXXXX)"
trap '[ "${KEEP_TMP:-0}" = "1" ] || rm -rf "$WORK"' EXIT
FIXTURE="$WORK/fixture"
mkdir -p "$FIXTURE"

# fixture：目标文件 "v1"；一次性看门狗：检测到 v2 即改回 v1 并退出（恰好一次）
printf 'v1' > "$FIXTURE/locked.txt"
printf '# converge-fixture\n' > "$FIXTURE/README.md"
(
  for i in $(seq 1 600); do
    if [ "$(cat "$FIXTURE/locked.txt" 2>/dev/null)" = "v2" ]; then
      printf 'v1' > "$FIXTURE/locked.txt"
      exit 0
    fi
    sleep 0.5
  done
) &
WATCHDOG_PID=$!

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
say "1. 建项目 + AUTONOMOUS 会话"
PROJ="$(curl -s -m 10 -X POST "$BASE/v1/projects" -H 'Content-Type: application/json' \
  --data "{\"name\":\"e2e-converge\",\"directory\":\"$FIXTURE\"}")"
PID_C="$(echo "$PROJ" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"
SESS="$(curl -s -m 10 -X POST "$BASE/v1/sessions" -H 'Content-Type: application/json' \
  --data "{\"projectId\":\"$PID_C\",\"agent\":{\"id\":\"autonomous-code\",\"name\":\"自主 · 编程\",\"description\":null,\"mode\":\"AUTONOMOUS\",\"workType\":\"CODE\"}}")"
SID="$(echo "$SESS" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"
echo "  session: $SID"

say "2. 订阅事件流 + 发送任务"
EVENTS_LOG="$WORK/events.log"
: > "$EVENTS_LOG"
curl -sN -m "$((TIMEOUT+60))" "$BASE/v1/sessions/$SID/events" > "$EVENTS_LOG" 2>/dev/null &
SSE_PID=$!
sleep 1
PROMPT='Use the plan workflow (create_plan, generate_spec, spawn_agent, verify_subtask) to make locked.txt at the project root contain exactly v2 (currently v1). The verification command must be assert-style that exits non-zero on failure, e.g. python3 -c "assert open('"'"'locked.txt'"'"').read()=='"'"'v2'"'"'". Do not edit project files yourself — all writes via spawn_agent. If verify_subtask fails, use converge_plan to append a remediation subtask, then execute it and finish.'

# 用 python 生成 JSON body（PROMPT 含双引号，inline --data 会坏）
python3 - "$PROMPT" "$MODEL_JSON" > "$WORK/msg.json" <<'PY'
import json,sys
prompt=sys.argv[1]
model=json.loads(open(sys.argv[2]).read())
body={"text":prompt,"model":model,"agent":{"id":"autonomous-code","name":"自主 · 编程","description":None,"mode":"AUTONOMOUS","workType":"CODE"},"thinkingLevel":None}
json.dump(body, sys.stdout, ensure_ascii=False)
PY
curl -s -m 30 -X POST "$BASE/v1/sessions/$SID/messages" -H 'Content-Type: application/json' --data @"$WORK/msg.json" > /dev/null

say "3. 等 turn 完成（最长 ${TIMEOUT}s）"
for i in $(seq 1 "$TIMEOUT"); do
  if grep -q "MESSAGE_COMPLETED\|MESSAGE_ERROR" "$EVENTS_LOG" 2>/dev/null; then break; fi
  sleep 1
done
kill $WATCHDOG_PID 2>/dev/null || true
sleep 1
kill $SSE_PID 2>/dev/null || true

grep -q "MESSAGE_ERROR" "$EVENTS_LOG" && fail "turn MESSAGE_ERROR"
grep -q "MESSAGE_COMPLETED" "$EVENTS_LOG" || fail "${TIMEOUT}s 内 turn 未完成（超时）"

# ------------------------------------------------------------------
say "4. 工具调用序列"
SEQ="$(python3 - "$EVENTS_LOG" <<'PY'
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
)"
echo "  $SEQ"

say "5. PLAN_PROGRESS 事件（verify 结果轨迹）"
python3 - "$EVENTS_LOG" <<'PY'
import json,sys
for line in open(sys.argv[1]):
    line=line.strip()
    if not line.startswith("data:"): continue
    try: ev=json.loads(line[5:])
    except Exception: continue
    if ev.get("type")=="PLAN_PROGRESS":
        p=ev.get("payload",{})
        print(f"  action={p.get('action')} passed={p.get('passed')} failed={p.get('failed')} lastStatus={p.get('lastSubtaskStatus')}")
PY

say "6. 断言"
# C1: 首轮 verify 被拒——auto-verify exit 非 0 时工具返回 "exited non-zero"
REFUSED=$(grep -c "exited non-zero" "$EVENTS_LOG" 2>/dev/null || echo 0)
[ "$REFUSED" != "0" ] || fail "C1 未满足：verify_subtask 从未被 auto-verify 拒绝（没构造出失败）"
echo "  C1 auto-verify 拒绝 PASS ✓（${REFUSED} 次）"

# C2: converge_plan 被调用
case "$SEQ" in
  *converge_plan*) echo "  C2 converge_plan 被调用 ✓" ;;
  *) fail "C2 未满足：converge_plan 未被调用（序列=${SEQ}）" ;;
esac

# C3: 计划归档（全 COMPLETED 才归档）
PLAN_FILE="$(find "$FIXTURE/.mederi/plans-done" -name "*.json" -type f 2>/dev/null | head -1)"
[ -n "$PLAN_FILE" ] || fail "C3 未满足：plans-done 无归档计划"
python3 - "$PLAN_FILE" <<'PY'
import json,sys
p=json.load(open(sys.argv[1]))
bad=[f"{i}:{s['status']}" for i,s in enumerate(p["subtasks"]) if s["status"]!="COMPLETED"]
assert not bad, f"未全 COMPLETED: {bad}"
print(f"  C3 计划 {p['id']} 归档 ✓，{len(p['subtasks'])} 个子任务全 COMPLETED")
PY

# C4: 产物内容
CONTENT="$(cat "$FIXTURE/locked.txt" | tr -d '[:space:]')"
[ "$CONTENT" = "v2" ] || fail "C4 未满足：locked.txt 内容='$CONTENT'，期望 v2"
echo "  C4 产物 locked.txt=v2 ✓"

pass "converge_plan FAIL→补救→PASS→归档 全链"
echo "  工作目录: $WORK (KEEP_TMP=1 保留)"
