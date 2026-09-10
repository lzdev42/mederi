#!/usr/bin/env bash
# =====================================================================
# e2e-p2b.sh — P2 盲区：手动压缩（R3）+ WORK 模式（R4）+ ask_user（R5）
#
# R3 压缩：POST /v1/sessions/{id}/compress 手动触发（70% pre-flight 无法
#   低成本触达——最小窗口 200k，改验同一压缩管线的手动入口），断言：
#   - 返回 200 且历史中有压缩标记
#   - 压缩后下一轮对话正常（模型知道早前事实）
# R4 WORK 模式：非编码任务（总结文档），断言 turn 完成 + 报告引用真实内容
# R5 ask_user：模糊指令诱导提问，断言 QUESTION_REQUESTED 事件 + resolve
#   后模型拿到答案继续执行
#
# 用法: BASE=http://localhost:8081 scripts/e2e-p2b.sh
# =====================================================================
set -euo pipefail

BASE="${BASE:-http://localhost:8081}"
MODEL_ID="${MODEL_ID:-mdl_b12f4148}"
TIMEOUT="${TIMEOUT:-300}"

C_G="\033[1;32m"; C_R="\033[1;31m"; C_B="\033[1;36m"; C_0="\033[0m"
say()  { printf "${C_B}== %s ==${C_0}\n" "$*"; }
pass() { printf "${C_G}判定: PASS${C_0}\n"; }
fail() { printf "${C_R}判定: FAIL — %s${C_0}\n" "$*"; exit 1; }

[ -n "$(curl -s -m 3 "$BASE/v1/ready" 2>/dev/null || true)" ] || fail "server 未就绪"

WORK="$(mktemp -d /tmp/mederi-p2b.XXXXXX)"
trap '[ "${KEEP_TMP:-0}" = "1" ] || rm -rf "$WORK"' EXIT
FIXTURE="$WORK/fixture"
mkdir -p "$FIXTURE"

cat > "$FIXTURE/notes.md" <<'EOF'
# Meeting notes
- Budget approved: 42,000 USD for Q4
- Launch date moved to November 3
- Owner: Alice
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

new_session() {  # $1 = agent id (autonomous-code / autonomous-work)
  PROJ="$(curl -s -m 10 -X POST "$BASE/v1/projects" -H 'Content-Type: application/json' \
    --data "{\"name\":\"e2e-p2b\",\"directory\":\"$FIXTURE\"}")"
  P="$(echo "$PROJ" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"
  local AGENT_NAME="自主 · 编程"
  [ "$1" = "autonomous-work" ] && AGENT_NAME="自主 · 通用"
  SESS="$(curl -s -m 10 -X POST "$BASE/v1/sessions" -H 'Content-Type: application/json' \
    --data "{\"projectId\":\"$P\",\"agent\":{\"id\":\"$1\",\"name\":\"$AGENT_NAME\",\"description\":null,\"mode\":\"AUTONOMOUS\",\"workType\":\"$([ "$1" = "autonomous-work" ] && echo WORK || echo CODE)\"}}")"
  echo "$SESS" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])'
}

send_and_wait() {  # $1=SID $2=prompt $3=agentId
  local SID=$1 PROMPT=$2 AGENT=${3:-autonomous-code}
  EVENTS_LOG="$WORK/events.log"
  : > "$EVENTS_LOG"
  curl -sN -m "$((TIMEOUT+60))" "$BASE/v1/sessions/$SID/events" > "$EVENTS_LOG" 2>/dev/null &
  SSE_PID=$!
  sleep 1
  python3 - "$PROMPT" "$MODEL_JSON" "$AGENT" > "$WORK/msg.json" <<'PY'
import json,sys
model=json.loads(open(sys.argv[2]).read())
isWork = sys.argv[3]=="autonomous-work"
body={"text":sys.argv[1],"model":model,"agent":{"id":sys.argv[3],"name":"a","description":None,"mode":"AUTONOMOUS","workType":"WORK" if isWork else "CODE"},"thinkingLevel":None}
json.dump(body, sys.stdout, ensure_ascii=False)
PY
  HTTP=$(curl -s -m 30 -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/sessions/$SID/messages" -H 'Content-Type: application/json' --data @"$WORK/msg.json")
  [ "$HTTP" = "200" ] || { kill $SSE_PID 2>/dev/null || true; fail "消息发送 HTTP $HTTP"; }
  for i in $(seq 1 "$TIMEOUT"); do
    if grep -q "MESSAGE_COMPLETED\|MESSAGE_ERROR" "$EVENTS_LOG" 2>/dev/null; then break; fi
    sleep 1
  done
  sleep 1
  kill $SSE_PID 2>/dev/null || true
}

tool_sequence() {
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

last_assistant_text() {
  curl -s -m 10 "$BASE/v1/sessions/$1/messages/raw" | python3 -c '
import json,sys
msgs=json.load(sys.stdin)
for m in reversed(msgs):
    p=json.loads(m["payload"]) if isinstance(m.get("payload"),str) else m.get("payload",{})
    if p.get("role")=="ASSISTANT":
        for x in p.get("parts",[]):
            if x.get("type","").endswith(".Text"):
                print(x.get("text","")); break
        break
'
}

# =====================================================================
say "R3: 手动压缩（compress endpoint）"
# =====================================================================
SID3=$(new_session autonomous-code)
echo "  session: $SID3"

# 先造一段有可压缩事实的对话（多轮：压缩策略要求 older 消息 >=2 才动手）
send_and_wait "$SID3" "Read notes.md and remember: what is the approved budget? Just answer briefly, do not modify anything."
ANS1="$(last_assistant_text "$SID3")"
case "$ANS1" in *42,000*|*42000*) echo "  R3.1 首轮问答真实 ✓" ;; *) fail "R3.1 首轮回答不含预算: $(echo "$ANS1" | tail -c 100)" ;; esac

send_and_wait "$SID3" "Also remember: what is the launch date mentioned in notes.md? Answer briefly from the file."
ANS1B="$(last_assistant_text "$SID3")"
case "$ANS1B" in *November*|*november*|*Nov*) echo "  R3.1b 二轮问答真实 ✓" ;; *) fail "R3.1b 二轮回答不含日期: $(echo "$ANS1B" | tail -c 100)" ;; esac

# 第三轮：压缩策略 n=max(30%,5)、older>=2 才动手——3 轮问答历史 10 条 → older=5 ✓
send_and_wait "$SID3" "Finally remember: who is the owner mentioned in notes.md? Answer briefly from the file."
ANS1C="$(last_assistant_text "$SID3")"
case "$ANS1C" in *Alice*) echo "  R3.1c 三轮问答真实 ✓" ;; *) fail "R3.1c 三轮回答不含 owner: $(echo "$ANS1C" | tail -c 100)" ;; esac

# 手动压缩
HTTP=$(curl -s -m 60 -o "$WORK/compress.out" -w '%{http_code}' -X POST "$BASE/v1/sessions/$SID3/compress")
[ "$HTTP" = "200" ] || { cat "$WORK/compress.out"; fail "compress HTTP $HTTP"; }
echo "  R3.2 compress 200 ✓"

# 压缩后追问——模型仍须知道预算（压缩保留事实）
send_and_wait "$SID3" "Without reading any file again: what was the approved budget and the launch date I asked about earlier? Answer both from memory."
ANS2="$(last_assistant_text "$SID3")"
case "$ANS2" in
  *42,000*|*42000*) case "$ANS2" in *November*|*november*|*Nov*|*11*) echo "  R3.3 压缩后事实保留 ✓" ;; *) fail "R3.3 压缩后丢了日期: $(echo "$ANS2" | tail -c 150)" ;; esac ;;
  *) fail "R3.3 压缩后模型丢失事实: $(echo "$ANS2" | tail -c 150)" ;;
esac

# 压缩标记在历史（压缩策略 n=max(30%,5)、older>=2 才动手；10 条历史 → n=5 → older=5 ✓）
MARKED=$(curl -s -m 10 "$BASE/v1/sessions/$SID3/messages/raw" | grep -ciE "summary|compressed|tldr" || true)
[ "$MARKED" != "0" ] || fail "R3.4 历史无压缩标记"
echo "  R3.4 压缩标记在历史 ✓"
pass "R3 手动压缩"

# =====================================================================
say "R4: WORK 模式（非编码任务）"
# =====================================================================
SID4=$(new_session autonomous-work)
echo "  session: $SID4"
send_and_wait "$SID4" "Read notes.md and summarize the key decisions in one short paragraph. Answer in chat only, do not write files." autonomous-work
grep -q "MESSAGE_ERROR" "$EVENTS_LOG" && fail "R4 turn MESSAGE_ERROR"
SEQ_R4="$(tool_sequence)"
echo "  工具序列: $SEQ_R4"
case "$SEQ_R4" in
  *read_file*) echo "  R4.1 读了 notes.md ✓" ;;
  *) fail "R4.1 未满足：未读文件（序列=$SEQ_R4）" ;;
esac
SUM4="$(last_assistant_text "$SID4")"
case "$SUM4" in *budget*|*Budget*|*Alice*|*November*) echo "  R4.2 总结引用真实事实 ✓" ;; *) fail "R4.2 总结无真实事实: $(echo "$SUM4" | tail -c 150)" ;; esac
pass "R4 WORK 模式"

# =====================================================================
say "R5: ask_user 挂起/恢复"
# =====================================================================
SID5=$(new_session autonomous-code)
echo "  session: $SID5"
EVENTS_LOG="$WORK/events5.log"
: > "$EVENTS_LOG"
curl -sN -m "$((TIMEOUT+60))" "$BASE/v1/sessions/$SID5/events" > "$EVENTS_LOG" 2>/dev/null &
SSE_PID=$!
sleep 1
python3 - 'Create a config file named app.cfg at the project root. The content must contain a port number — I cannot decide the value, so ask me which port to use before creating it. Use the ask_user tool.' "$MODEL_JSON" > "$WORK/msg5.json" <<'PY'
import json,sys
model=json.loads(open(sys.argv[2]).read())
body={"text":sys.argv[1],"model":model,"agent":{"id":"autonomous-code","name":"a","description":None,"mode":"AUTONOMOUS","workType":"CODE"},"thinkingLevel":None}
json.dump(body, sys.stdout, ensure_ascii=False)
PY
HTTP=$(curl -s -m 30 -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/sessions/$SID5/messages" -H 'Content-Type: application/json' --data @"$WORK/msg5.json")
[ "$HTTP" = "200" ] || { kill $SSE_PID 2>/dev/null || true; fail "R5 消息发送 HTTP $HTTP"; }

# 等 QUESTION_REQUESTED
for i in $(seq 1 180); do
  grep -q "QUESTION_REQUESTED" "$EVENTS_LOG" 2>/dev/null && break
  grep -q "MESSAGE_COMPLETED\|MESSAGE_ERROR" "$EVENTS_LOG" 2>/dev/null && break
  sleep 1
done
grep -q "QUESTION_REQUESTED" "$EVENTS_LOG" || { kill $SSE_PID 2>/dev/null || true; echo "--- 事件流尾部:"; tail -5 "$EVENTS_LOG"; fail "R5 未满足：180s 内无 QUESTION_REQUESTED"; }
echo "  R5.1 QUESTION_REQUESTED ✓（turn 挂起中）"

# 解析 questionId
QID=$(grep -m1 "QUESTION_REQUESTED" "$EVENTS_LOG" | sed 's/^data: //' | python3 -c 'import json,sys;print(json.load(sys.stdin).get("payload",{}).get("questionId",""))')
[ -n "$QID" ] || { kill $SSE_PID 2>/dev/null || true; fail "R5 questionId 解析失败"; }
echo "  question: $QID"

# 挂起观察 15s：不应完成
sleep 15
grep -q "MESSAGE_COMPLETED\|MESSAGE_ERROR" "$EVENTS_LOG" && { kill $SSE_PID 2>/dev/null || true; fail "R5.2 挂起期间 turn 已结束"; }
echo "  R5.2 挂起持续 ✓"

# resolve：答案 [[“8080”]]
HTTP=$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/sessions/$SID5/questions/$QID" -H 'Content-Type: application/json' --data '{"answers":[["8080"]]}')
[ "$HTTP" = "200" ] || { kill $SSE_PID 2>/dev/null || true; fail "R5.3 resolve HTTP $HTTP"; }
echo "  R5.3 resolve 200 ✓，等恢复执行..."

for i in $(seq 1 "$TIMEOUT"); do
  grep -q "MESSAGE_COMPLETED\|MESSAGE_ERROR" "$EVENTS_LOG" 2>/dev/null && break
  sleep 1
done
sleep 1
kill $SSE_PID 2>/dev/null || true
grep -q "MESSAGE_ERROR" "$EVENTS_LOG" && fail "R5.4 恢复后 MESSAGE_ERROR"
SEQ_R5="$(python3 - "$EVENTS_LOG" <<'PY'
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
echo "  工具序列: $SEQ_R5"
case "$SEQ_R5" in
  *write_file*|*edit_file*) echo "  R5.4 恢复后写文件 ✓" ;;
  *) fail "R5.4 未满足：恢复后未执行写动作（序列=$SEQ_R5）" ;;
esac
[ -f "$FIXTURE/app.cfg" ] && grep -q "8080" "$FIXTURE/app.cfg" || fail "R5.5 未满足：app.cfg 未包含答案端口 8080"
echo "  R5.5 app.cfg 含 8080 ✓"
grep -q "QUESTION_RESOLVED" "$EVENTS_LOG" && echo "  R5.6 QUESTION_RESOLVED 事件 ✓"
pass "R5 ask_user"

echo ""
say "总结"
echo "  R3 手动压缩 : PASS"
echo "  R4 WORK 模式: PASS"
echo "  R5 ask_user : PASS"
echo "  工作目录: $WORK (KEEP_TMP=1 保留)"
