#!/usr/bin/env bash
# Mederi 端到端冒烟：用 curl 真实驱动 server（REST + SSE），让真实模型（默认 Agnes）
# 在一个临时项目里完成一个编码任务，最后给出「流程问题 / 模型问题 / 通过」的结构化判定。
#
# 用法：
#   scripts/e2e-smoke.sh <fixture目录> "<提示词>" '<校验命令>'
#   校验命令里用 $FIXTURE 引用项目根目录，返回 0 视为产物正确。
#
# 环境变量：
#   BASE         server 地址（默认 http://localhost:8081）
#   MODEL_ID     模型 id（默认 mdl_b12f4148 = agnes-2.5-flash，免费）
#   AGENT_ID     会话 agent id（留空 = AUTONOMOUS/CODE 默认）
#   TIMEOUT      等待 turn 结束秒数（默认 240）
#   EXPECT_TOOLS 逗号分隔的工具名列表：这些工具必须被真实调用过，缺失任何即 FLOW_FAIL。
#                判定从"没出错"升级为"该发生的发生了"（防模型绕过计划循环走 shell 直改）。
set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
MODEL_ID="${MODEL_ID:-mdl_b12f4148}"
AGENT_ID="${AGENT_ID:-}"
TIMEOUT="${TIMEOUT:-240}"
EXPECT_TOOLS="${EXPECT_TOOLS:-}"

FIXTURE="${1:?需要 fixture 目录}"
PROMPT="${2:?需要提示词}"
VERIFY="${3:?需要校验命令}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ------------------------------------------------------------------
say "0. 克隆干净 fixture（杀状态污染：上次运行的产物/计划残留会造成假 PASS）"
RUN_FIXTURE="$WORK/fixture"
mkdir -p "$RUN_FIXTURE"
cp -R "$FIXTURE/." "$RUN_FIXTURE/"
rm -rf "$RUN_FIXTURE/.mederi"   # 计划/notebook 状态不继承上次运行
FIXTURE="$RUN_FIXTURE"
echo "运行目录: $FIXTURE"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31mFATAL: %s\033[0m\n' "$*" >&2; exit 2; }

# ------------------------------------------------------------------
say "0. 健康检查"
READY="$(curl -s -m 5 "$BASE/v1/ready")" || die "server 无响应：$BASE"
echo "$READY"
echo "$READY" | grep -q '"ready":true' || die "server 未就绪"

# 解析出完整 model 对象（回显进 ChatPromptInput.model，保证字段合法）
curl -s -m 5 "$BASE/v1/models" > "$WORK/models.json" || die "拉取模型失败"
python3 - "$WORK/models.json" "$MODEL_ID" "$WORK/model.json" <<'PY' || die "模型 $MODEL_ID 不存在/未启用"
import json,sys
models=json.load(open(sys.argv[1])); mid=sys.argv[2]
m=next((x for x in models if x["id"]==mid), None)
if not m: sys.exit(1)
json.dump(m, open(sys.argv[3],"w"))
print("使用模型:", m["name"], "ctx=", m.get("contextWindow"))
PY

# ------------------------------------------------------------------
say "1. 建项目（fixture 目录 = 写白名单）"
python3 - "$FIXTURE" > "$WORK/proj.json" <<'PY'
import json,sys
json.dump({"name":"e2e-smoke","directory":sys.argv[1]}, sys.stdout)
PY
PROJ="$(curl -s -m 10 -X POST "$BASE/v1/projects" -H 'Content-Type: application/json' --data @"$WORK/proj.json")"
echo "$PROJ"
PID="$(echo "$PROJ" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])' 2>/dev/null)" || die "建项目失败"

# ------------------------------------------------------------------
say "2. 建会话（AUTONOMOUS/CODE）"
python3 - "$PID" "$AGENT_ID" > "$WORK/sess.json" <<'PY'
import json,sys
body={"projectId":sys.argv[1]}
if sys.argv[2]: body["agent"]=json.loads(sys.argv[2])
json.dump(body, sys.stdout)
PY
SESS="$(curl -s -m 10 -X POST "$BASE/v1/sessions" -H 'Content-Type: application/json' --data @"$WORK/sess.json")"
echo "$SESS"
SID="$(echo "$SESS" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])' 2>/dev/null)" || die "建会话失败"

# ------------------------------------------------------------------
say "3. 订阅会话事件流（后台 SSE → events.log）"
curl -sN -m "$((TIMEOUT+30))" "$BASE/v1/sessions/$SID/events" > "$WORK/events.log" 2>/dev/null &
SSE_PID=$!
sleep 1   # 让 SSE 先连上，避免漏事件

# ------------------------------------------------------------------
say "4. 发送任务消息"
python3 - "$PROMPT" "$WORK/model.json" > "$WORK/msg.json" <<'PY'
import json,sys
model=json.load(open(sys.argv[2]))
json.dump({"text":sys.argv[1],"model":model,"agent":None,"thinkingLevel":None}, sys.stdout)
PY
HTTP=$(curl -s -m 30 -o "$WORK/send.out" -w '%{http_code}' -X POST "$BASE/v1/sessions/$SID/messages" -H 'Content-Type: application/json' --data @"$WORK/msg.json")
echo "HTTP $HTTP"; cat "$WORK/send.out"; echo
[ "$HTTP" = "200" ] || die "发送消息失败（HTTP $HTTP）→ 流程问题"

# ------------------------------------------------------------------
say "5. 轮询直到会话结束（Idle/Error）或超时"
STATUS=""; DEADLINE=$(( $(date +%s) + TIMEOUT ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  curl -s -m 10 "$BASE/v1/sessions/$SID/snapshot" > "$WORK/snap.json"
  STATUS="$(python3 -c 'import json;print(json.load(open("'"$WORK"'/snap.json"))["conversation"]["status"])' 2>/dev/null)"
  printf '  status=%s\r' "$STATUS"
  [ "$STATUS" = "Idle" ] || [ "$STATUS" = "Error" ] && break
  sleep 3
done
echo
kill "$SSE_PID" 2>/dev/null; wait "$SSE_PID" 2>/dev/null

# ------------------------------------------------------------------
say "6. 产物校验（fixture 真实内容）"
FIXTURE="$FIXTURE" bash -c "$VERIFY"; VRC=$?
echo "校验命令退出码: $VRC"

# ------------------------------------------------------------------
say "7. 判定报告"
curl -s -m 10 "$BASE/v1/sessions/$SID/messages/raw" > "$WORK/raw.json"
python3 - "$WORK/snap.json" "$WORK/events.log" "$WORK/raw.json" "$WORK/diff.json" "$SID" "$BASE" "$VRC" "$STATUS" "$EXPECT_TOOLS" "$FIXTURE" <<'PY'
import json,sys,urllib.request
snap_path, ev_path, raw_path, _d, sid, base, vrc, status, expect_tools, fixture_path = sys.argv[1:11]
vrc=int(vrc)
expected = [t.strip() for t in expect_tools.split(",") if t.strip()] if expect_tools else []

try:
    with urllib.request.urlopen(f"{base}/v1/sessions/{sid}/diffs", timeout=10) as r:
        diffs=json.load(r)
except Exception as e:
    diffs=[]

snap=json.load(open(snap_path)); err=snap.get("errorMessage")

# 从 raw payload 提取权威工具序列（按 call id 配对 CALL/RESULT）+ 失败分类
tool_seq=[]   # [tool, args, status, output]
call_idx={}   # callId -> tool_seq 下标
harness_err=[]; model_err=[]
try: raws=json.load(open(raw_path))
except Exception: raws=[]
# 流程侧故障签名：harness 自身没处理好（序列化/崩溃），模型无法控制。
# 注意："Tool with name X failed to execute" 是所有工具失败的通用包装，不能当 harness 签名——
# 路径不存在等域错误是模型侧的。真正的 harness 症状 = 参数解析失败/序列化器缺失/裸 Java 异常。
HARNESS=("failed to parse","serializer for class","exception","stack trace","caused by","java.lang.","kotlin.")
# 失败词白名单：输出里含这些子串不算失败（如 "0 failed, 3 passed"）
NOT_FAIL=(" 0 failed","verified: pass","all passed","0 failed")
for r in raws:
    try: p=json.loads(r["payload"])
    except Exception: continue
    for part in p.get("parts",[]):
        t=part.get("type","")
        if t.endswith("ToolCall"):
            call_idx[part.get("id")]=len(tool_seq)
            tool_seq.append([part.get("tool"), part.get("args",""), "?", ""])
        elif t.endswith("ToolResult"):
            out=str(part.get("output","")); low=out.lower()
            whitelisted = any(w in low for w in NOT_FAIL)
            failed = not whitelisted and (part.get("isError") or low.startswith("error") or "failed" in low or "not found" in low)
            i=call_idx.get(part.get("id"))
            if i is not None:
                tool_seq[i][2]="ERR" if failed else "OK"
                tool_seq[i][3]=out
            if any(s in low for s in HARNESS):
                harness_err.append((part.get("tool"), out[:160]))
            elif failed or "exit code:" in low or "command not found" in low or "no such file" in low:
                model_err.append((part.get("tool"), out[:160]))

ev_types={}
for line in open(ev_path, encoding="utf-8", errors="ignore"):
    if line.startswith("data:"):
        try:
            ty=json.loads(line[5:].strip()).get("type"); ev_types[ty]=ev_types.get(ty,0)+1
        except: pass

print("  会话结束状态 :", status)
print("  事件类型计数 :", ev_types or "(无)")
ok=sum(1 for c in tool_seq if c[2]=="OK"); bad=sum(1 for c in tool_seq if c[2]=="ERR")
print(f"  工具调用序列 ({len(tool_seq)} 次, 成功 {ok} / 失败 {bad}):")
for n,a,st,o in tool_seq:
    print(f"     - [{st:3}] {n}  {a[:70]}")
    if st=="ERR": print(f"         └─ {o[:110]}")
print("  流程侧故障   :", harness_err or "无")
print("  模型/环境报错:", model_err or "无")
print("  流程记录 diff :", [d["filePath"] for d in diffs] or "无（可能走了 shell 改文件，不经 diffTracker）")
print("  产物校验退出码:", vrc, "(0=正确)")
called_names = {c[0] for c in tool_seq}
print("  必需工具断言 :", (f"期望 {expected} → 缺失 {[t for t in expected if t not in called_names]}" if expected else "未配置 EXPECT_TOOLS（仅'没出错'级判定）"))
# PlanStore 状态机证据（三角互证之一：消息流之外，落库状态是否一致）
import glob, os
plan_files = sorted(glob.glob(os.path.join(fixture_path, ".mederi/plans/*.json")))
done_files = sorted(glob.glob(os.path.join(fixture_path, ".mederi/plans-done/*.json")))
print(f"  PlanStore     : 活跃 {len(plan_files)} / 已归档 {len(done_files)}")
for f in plan_files + done_files:
    try:
        d = json.load(open(f))
        sts = [(s.get("index"), s.get("status")) for s in d.get("subtasks", [])]
        loc = "plans-done" if "/plans-done/" in f.replace(os.sep, "/") else "plans"
        print(f"     - {os.path.basename(f)} [{loc}] plan={d.get('status')} 子任务: {sts}")
    except Exception as e:
        print(f"     - {os.path.basename(f)} 读取失败: {e}")
if err: print("  会话错误信息 :", err)

flow_fail=[]; model_fail=[]
# 必需行为断言：期望的工具一个都没被调用 = 计划闭环/目标行为没发生（即使产物对也是 FLOW_FAIL，
# 例如模型绕过计划循环用 shell 直改文件达成产物）
for t in expected:
    if t not in called_names:
        flow_fail.append(f"必需工具未调用: {t}（该发生的行为没发生）")
if status=="Error": flow_fail.append(f"会话进入 Error（errorMessage={err}）")
if status not in ("Idle","Error"):
    # 超时：区分"模型不收敛（工具一直在执行）"与"流程卡死（无流量）"
    if ev_types.get("TOOL_RESULT",0) >= 5:
        model_fail.append(f"超时未收敛（已执行 {ev_types.get('TOOL_RESULT',0)} 次工具，模型在 thrash/过度发挥）")
    else:
        flow_fail.append(f"超时且几乎无工具流量（status={status}，疑似流程卡死）")
# 事件断裂仅在会话已正常结束时才有意义（超时/中止时最后一个调用无结果是正常的）
if status in ("Idle","Error") and ev_types.get("TOOL_CALLED",0) > ev_types.get("TOOL_RESULT",0):
    flow_fail.append("存在 TOOL_CALLED 未收到 TOOL_RESULT（事件流断裂）")
for n,e in harness_err:
    flow_fail.append(f"工具 {n} 流程侧故障: {e}")
for n,e in model_err:
    model_fail.append(f"工具 {n} 模型/环境报错: {e}")
if not tool_seq and vrc!=0 and status=="Idle":
    model_fail.append("模型未调用任何工具，也没产出正确结果")

if flow_fail:
    verdict="FLOW_FAIL（流程问题）"; reasons=flow_fail
elif vrc==0 and status=="Idle":
    verdict="PASS（流程通 + 产物对）"; reasons=model_fail  # 模型噪音仅作提示
elif vrc!=0:
    verdict="MODEL_FAIL（流程通但产物错/未达成）"; reasons=model_fail or ["产物校验未通过，但流程无异常"]
else:
    verdict="INCONCLUSIVE"; reasons=model_fail

print("\n\033[1m判定:", verdict, "\033[0m")
for r in reasons: print("   ·", r)
PY
