# Mederi 端到端冒烟测试文档

> 脚本：`scripts/e2e-smoke.sh` ｜ 最后更新：2026-09-07

## 1. 这个测试是干什么的

**测对象**：Mederi harness（core + server + Koog 管线）——不是测模型，不是测用户代码。

**为什么必须这样测**：core 模块**零单元测试**，inkcompose 的 2600+ 测试只保护渲染库。
harness 的真实行为 = 真实模型 × 真实工具分发 × 真实持久化 × 真实状态机，单元测试无法覆盖
这条链路（历史上 6 个真实 bug 全部只在端到端跑通时暴露：序列化器被注释吞掉、工具漏注册、
边注册顺序错误静默丢工具调用等）。所以这个脚本是 harness **唯一的安全网**，职责有二：

1. **回归测试**：每次改 core 后跑一遍，防已知修复回退
2. **故障归因**：失败时区分"流程问题（harness 坏）"和"模型问题（模型差）"，
   否则每次失败都要人工扒日志分辨

**测不到的**：UI（那是 Compose Hot Reload MCP 的职责，见 AGENTS.md §4）、
inkcompose 渲染（jvmTest 覆盖）、供应商鉴权细节。

## 2. 测试方法

### 2.1 黑盒真实驱动

不 mock 任何一层。脚本用 curl 完成一次完整任务：

```
GET  /v1/ready                          → 健康检查
POST /v1/projects                       → 建项目（fixture 目录 = 写白名单）
POST /v1/sessions                       → 建会话（AUTONOMOUS / CODE）
GET  /v1/sessions/{id}/events (SSE)     → 后台录事件流
POST /v1/sessions/{id}/messages         → 发任务提示词
轮询 GET /v1/sessions/{id}/snapshot     → 等 Idle/Error/超时
GET  /v1/sessions/{id}/messages/raw     → 拉原始消息（判定输入之一）
执行校验命令（对 fixture 产物）          → 产物锚点
```

**每次运行克隆干净 fixture**（`cp -R` 到临时目录并剔除 `.mederi`）——上次运行的产物、
计划文件、notebook 一概不继承。这是硬性防污染：没有它，"模型什么都没干但残留产物
让校验通过"会造成假 PASS。

### 2.2 判定等级

| 判定 | 含义 | 依据 |
|---|---|---|
| `PASS` | 流程通 + 产物对 | 无流程侧故障、产物校验退出码 0、会话 Idle、必需行为全发生 |
| `FLOW_FAIL` | **harness 坏** | harness 签名命中，或必需行为缺失，或会话 Error，或事件流断裂 |
| `MODEL_FAIL` | 流程通但产物错 | 流程无故障而校验退出码非 0 |
| `INCONCLUSIVE` | 无法判定 | 边界组合 |

### 2.3 有效性的五个支柱（为什么能测出问题）

**支柱一：产物锚点独立于被测系统。**
最终判定跑的是对 fixture 文件系统的**真实命令**（python 导入断言、文件存在性检查）。
工具返回文本说"✅ 完成"、模型说"已验证"都**不算数**——只有文件系统真相算数。
被测系统的任何一方都没有能力伪造这个证据。

**支柱二：多层证据三角互证。**
同一件事要求三层独立证据一致：
- raw 消息（持久化 JSON）：按 **call id** 逐对配对 CALL/RESULT，不是数次数——
  数次数会漏"调用发出但结果被静默丢弃"（边序 bug 的症状）
- PlanStore 落库状态机（`.mederi/plans/*.json`）：报告直接打印 plan status 和每个子任务
  状态——工具文本可以撒谎，落库状态机不会
- 落盘产物 + 真实命令执行

**支柱三：故障归因签名可证伪。**
FLOW_FAIL 只认 harness 自己才会吐的特征串，不认模型也能吐的话：

| 分类 | 签名（输出小写匹配） | 理由 |
|---|---|---|
| 流程侧 | `failed to parse` / `serializer for class` | 参数反序列化失败 = 框架层，模型无法控制 |
| 流程侧 | `exception` / `stack trace` / `caused by` / `java.lang.` / `kotlin.` | 裸异常 = 崩溃 |
| 模型侧 | `isError` / `error:` 开头 / `failed` / `not found` / `exit code:` / `command not found` / `no such file` | 域错误（路径猜错、命令失败）是模型行为 |

注意：**`Tool with name X failed to execute` 不是流程签名**——它是所有工具失败的通用包装，
"Path does not exist" 这类域错误是模型侧的（此误分类在实测中发生过一次并已修正）。
签名清单是可证伪的：每次误判都要修清单而不是放宽判定。

**支柱四：必需行为断言（EXPECT_TOOLS）。**
"没出错"是弱证据，"该发生的发生了"才是强证据。`EXPECT_TOOLS="create_plan,generate_spec,spawn_agent,verify_subtask"`
要求这些工具**必须被真实调用过**，缺失任何即 FLOW_FAIL——即使产物正确。
防的攻击面：模型绕过计划循环用 `execute_command`（shell）直接改文件达成产物，
计划闭环根本没被测到。实测证明了这个威胁是真的（见 §5 M1：模型确实会用 `cat` 绕过坏工具）。

**支柱五：变异测试（判定器自己的考试）。**
测试器本身也可能是错的。验证方法：故意回滚一个已知修复（把 `ReadFileArgs` 的
`@Serializable` 注释掉），重编译重启，跑 S1——判定器必须报 FLOW_FAIL。
**实测结果（sess_e3d3f315）：产物校验退出码 0（模型用 `cat` 绕过坏掉的 read_file 把任务干成了），
但判定器凭 harness 签名抓到 FLOW_FAIL。** 这同时证明了两件事：
判定器对真实 bug 敏感；且"产物对 ≠ 流程通"，支柱一和支柱四缺一不可。
修复恢复后同场景回到 PASS（sess_b54f2800），前后对照成立。

## 3. 环境

| 项 | 值 |
|---|---|
| server | `http://localhost:8081`（内建 core），启动方式见下 |
| 启动规矩 | **必须经 idea-mcp 运行配置 `ApplicationKt (1)` 启动，禁止 bash 启动**（AGENTS.md §4 硬性义务）；bash 只允许 kill。改 core 后需 kill + 重启 |
| 就绪探针 | `curl http://localhost:8081/v1/ready` 返回 `"ready":true` |
| **模型（AI）** | **agnes-2.5-flash**（model id `mdl_b12f4148`），供应商 Agnes SG（`prov_60ec51f9`，https://apihub.agnes-ai.com/v1），免费，上下文 512k，无密码 |
| 选型理由 | 免费（可反复跑）；弱模型 = harness 容错的最佳压力测试——强模型会绕过 harness 缺陷，弱模型才暴露它们 |
| 持久化 | server 读写 `~/.mederi/config.db` + `data/data.db`（测试会在此积累 `e2e-smoke` 命名的项目，删除需按删库规矩经用户批准） |
| 脚本环境变量 | `BASE` / `MODEL_ID` / `AGENT_ID` / `TIMEOUT` / `EXPECT_TOOLS` |

### 3.1 fixture 模板（原始状态，脚本每次克隆，模板永不改动）

**`mederi-e2e/calc-proj/`**（S1/M1 用）：
```python
# calc.py（项目根目录，无 pkg/）
def add(a, b):
    return a + b
```

**`mederi-e2e/calc2-proj/`**（S2 用）：
```python
# pkg/calc.py
def add(a, b):
    return a + b

def mul(a, b):
    return a * b
```
```python
# pkg/api.py
from calc import add, mul
```

**`mederi-e2e/trivial-proj/`**（T1 用）：
```markdown
# README.md
# Trivial Project

Two-file exercise for e2e archive verification.
```

fixture 位置：`/var/folders/c0/3ltcz7px54gg1q8s45n42hjw0000gn/T/opencode/mederi-e2e/`（macOS 临时目录，重启会丢——丢了按上面定义重建即可，1 分钟的事）。

## 4. 测试场景明细

每个场景 = 测什么 + 精确输入 + 预期结果。所有场景共用判定逻辑（§2.2）。

---

### S1 — 小改动分诊路径（主代理直改）

**测什么**：§5.6 分诊流程的【小改动】路径——模型应判断这是几行代码的改动，**不建计划、不 spawn**，
直接 `read_file` → `edit_file` → 自验。顺带覆盖：read_file/edit_file 序列化与执行、
diffTracker 记录（报告"流程记录 diff"行）、产物落盘。

**输入**：
- fixture：`calc-proj`
- 提示词：`Read calc.py at the project root first, then add a function pow(a, b) that returns a ** b, matching the existing style. Do not change anything else.`
- `EXPECT_TOOLS="read_file,edit_file"`
- 校验命令：
  ```bash
  cd "$FIXTURE" && python3 -c "import sys; sys.path.insert(0, \".\"); from calc import pow as p; assert p(2,3)==8; print(\"ok\")"
  ```

**预期**：
- 判定 **PASS**；`read_file`、`edit_file` 各至少 1 次且有 RESULT；diff 记录含 `calc.py`；产物校验 0
- 反例信号：出现 `serializer for class 'ReadFileArgs'`（@Serializable 回归）即 FLOW_FAIL

**实测记录**：
| 会话 | 结果 | 备注 |
|---|---|---|
| sess_8426e2c8 / sess_8b194d6b / sess_5dce7bc1 | PASS ×3 | 边序修复后连跑；**8b194d6b 踩到 Text+ToolCall 混排消息**（`['Text','ToolCall','ToolCall']`），4/4 调用全执行——修复路径被真实覆盖 |
| sess_b54f2800 | PASS | 判定器收紧签名后绿灯收尾；模型幻觉 `verify_subtask(planId:"placeholder")` 被硬门禁拒绝（`Error: Plan not found: placeholder`）——模型侧噪音，判 PASS 正确，幻觉被代码拦截而非放行 |

---

### S2 — 计划闭环（Plan Loop 全链路）

**测什么**：§5.6 分诊流程的【复杂改动】路径：`create_plan` → 自动批准 → `generate_spec` ×N →
`spawn_agent`(planId+subtaskIndex 硬绑定执行存储的 spec) ×N → `verify_subtask` ×N → 全 PASS 收尾。
这是 harness 最复杂的状态机路径，也是历史上坏得最彻底的（generate_spec 漏注册 = 根断）。

**输入**：
- fixture：`calc2-proj`
- 提示词：`Add a subtraction feature to this calculator project: add sub(a,b) to pkg/calc.py (matching the style of add/mul), export it from pkg/api.py (keep the existing import style 'from calc import add, mul' working), and add a test file pkg/test_calc.py that asserts sub(5,3)==2. Use the plan workflow.`
- `EXPECT_TOOLS="create_plan,generate_spec,spawn_agent,verify_subtask"`
- 校验命令：
  ```bash
  cd "$FIXTURE" && python3 -c "import sys; sys.path.insert(0, \"pkg\"); from calc import sub; assert sub(5,3)==2; from api import sub; print(\"ok\")" && test -f pkg/test_calc.py && python3 pkg/test_calc.py
  ```

**预期**：
- 判定 **PASS**；四个计划工具全部真实调用；PlanStore 行显示子任务落库 COMPLETED；
  spawn_agent 的 RESULT 是子代理回的 spec checklist 执行报告（子代理在内存中执行、
  不创建持久化 Session——报告里看不到子会话是**设计如此**，见 `SpawnAgentTool.kt` 注释）
- 已知的模型侧噪音（不影响判定）：executor 用错误 import 风格自验导致的 `exit code: 1`；
  模型首调 create_plan 字段形状错误后自愈（见 §7 已知问题 1）

**实测记录**：
| 会话 | 结果 | 备注 |
|---|---|---|
| sess_efddb343 | **失败**（修复前） | 边序 bug 现场：Text+ToolCall 混排消息直接终结 turn，工具调用静默丢弃，edit_file 报成功但从未落盘 |
| sess_e5a95388 | PASS | 修复后首绿：16 次调用收敛 Idle，3 spec → 3 spawn → 3 verify 全 PASS，子任务落库 COMPLETED，产物独立验证正确 |
| sess_2f622185 | MODEL_FAIL | 模型把 api.py 改成相对导入 `from .calc import`，产物校验立刻抓住（vrc=1）——**模型方差的真实案例**，归因正确 |
| sess_ccef7dfb | PASS | **converge_plan 分支首次自然触发**：verify PARTIAL → 聚合校验拦下缺 gapType 的调用 → converge_plan 追加补救子任务 → spec → spawn → verify PASS。模型收尾 sloppy（1 FAILED + 1 PENDING 就写日志收工），产物最终正确 |

---

### T1 — 计划归档状态机

**测什么**：verify 全 PASS 后 plan status → COMPLETED + 归档 `plans → plans-done`。
这是 VerifyTools 归档修复（`isAllCompleted` / `archive` 原为零调用孤儿）的专门验证场景。

**输入**：
- fixture：`trivial-proj`
- 提示词：`Use the plan workflow (create_plan, then generate_spec, spawn_agent, verify_subtask for each subtask) to: 1) append a line 'powered by mederi' to README.md, and 2) create a new file VERSION containing exactly the text 0.1.0. Keep both subtasks small and verifiable. After each verify returns PASS, continue until every subtask is verified PASS, then finish.`
- `EXPECT_TOOLS="create_plan,generate_spec,spawn_agent,verify_subtask"`
- 校验命令：
  ```bash
  cd "$FIXTURE" && grep -q "powered by mederi" README.md && [ "$(cat VERSION)" = "0.1.0" ] && echo "artifact ok"
  ```

**预期**：
- 判定 **PASS**，且报告 PlanStore 行必须显示：`已归档 1`，`plan=COMPLETED`，子任务全 COMPLETED
- （S2 收尾 sloppy 的模型行为会让归档不触发——所以归档验证用极简场景，保证全 PASS 可达）

**实测记录**：
| 会话 | 结果 | 备注 |
|---|---|---|
| sess_f01ec14e | FLOW_FAIL（判定正确）+ **归档实锤** | PlanStore 行：`plans-done/plan_7da21c08.json plan=COMPLETED 子任务 [(0,'COMPLETED'),(1,'COMPLETED')]` ✓。FLOW_FAIL 因模型首调 create_plan 崩在参数形状（已知问题 1），模型自愈后走完全程——判定器如实记录，不放行 |

---

### M1 — 判定器自检（变异测试）

**测什么**：**测测试器本身**。回滚 `FileSystemTools.kt` 中 `ReadFileArgs` 的 `@Serializable`
（原 bug：注解被 `//` 注释行吞掉），重编译重启 server，跑 S1 场景。

**预期**（判定器必须做到的两点）：
1. 判 **FLOW_FAIL**——`serializer for class 'ReadFileArgs'` 签名命中
2. 产物校验很可能退出码 **0**（模型会用 `cat` 绕过坏掉的 read_file）——
   证明"产物对 ≠ 流程通"，必需行为 + 签名检测不可省

**恢复步骤**：还原 `@Serializable` → 重编译 → 重启 → 跑 S1 确认回到 PASS。

**实测记录**：
| 会话 | 结果 | 备注 |
|---|---|---|
| sess_e3d3f315 | **FLOW_FAIL ✓ 且 vrc=0** | 完全符合预期：模型 `cat` 绕过 + edit_file 成功 + 产物对，但 3 次 read_file 全部解析失败被签名抓到。变异测试通过 |
| sess_b54f2800 | PASS | 修复恢复后同场景绿灯，前后对照成立 |

## 5. 运行手册

```bash
# 前置（一次性）：server 经 idea-mcp 启动并就绪
#   idea-mcp: execute_run_configuration("ApplicationKt (1)")
#   curl http://localhost:8081/v1/ready → {"ready":true,...}

FIX=/var/folders/c0/3ltcz7px54gg1q8s45n42hjw0000gn/T/opencode/mederi-e2e

# S1 小改动
EXPECT_TOOLS="read_file,edit_file" TIMEOUT=240 scripts/e2e-smoke.sh "$FIX/calc-proj" \
  "Read calc.py at the project root first, then add a function pow(a, b) that returns a ** b, matching the existing style. Do not change anything else." \
  'cd "$FIXTURE" && python3 -c "import sys; sys.path.insert(0, \".\"); from calc import pow as p; assert p(2,3)==8; print(\"ok\")"'

# S2 计划闭环
EXPECT_TOOLS="create_plan,generate_spec,spawn_agent,verify_subtask" TIMEOUT=420 scripts/e2e-smoke.sh "$FIX/calc2-proj" \
  "Add a subtraction feature to this calculator project: add sub(a,b) to pkg/calc.py (matching the style of add/mul), export it from pkg/api.py (keep the existing import style 'from calc import add, mul' working), and add a test file pkg/test_calc.py that asserts sub(5,3)==2. Use the plan workflow." \
  'cd "$FIXTURE" && python3 -c "import sys; sys.path.insert(0, \"pkg\"); from calc import sub; assert sub(5,3)==2; from api import sub; print(\"ok\")" && test -f pkg/test_calc.py && python3 pkg/test_calc.py'

# T1 归档
EXPECT_TOOLS="create_plan,generate_spec,spawn_agent,verify_subtask" TIMEOUT=300 scripts/e2e-smoke.sh "$FIX/trivial-proj" \
  "Use the plan workflow (create_plan, then generate_spec, spawn_agent, verify_subtask for each subtask) to: 1) append a line 'powered by mederi' to README.md, and 2) create a new file VERSION containing exactly the text 0.1.0. Keep both subtasks small and verifiable. After each verify returns PASS, continue until every subtask is verified PASS, then finish." \
  'cd "$FIXTURE" && grep -q "powered by mederi" README.md && [ "$(cat VERSION)" = "0.1.0" ] && echo "artifact ok"'
```

改了 core 之后：重编译 → kill server（bash 允许）→ idea-mcp 重启 → 就绪探针 → 再跑场景。
**M1 变异测试**按 §4 M1 的步骤手工执行（临时注释 `@Serializable` → 重启 → 跑 S1 → 必须看到 FLOW_FAIL → 还原 → 重启 → S1 回 PASS）。

### M2 — 限流重试（供应商 RPM 耗尽）

**测什么**：限流自动重试机制（`RetryableLLMClient` 装饰器）：429/rpm 耗尽时随机 1~10s 退避重试
（默认 10 次），重试进度经 `STATUS` 事件推 UI（状态栏"重试中"），**不污染 session 状态机**；
重试耗尽后 session 保持 Idle（环境态可恢复）而非 Error。

**触发方式**：deepseek-v4-flash（`mdl_7e7e6eaf`）并行双跑（免费通道 RPM 低，两个并发 turn 必撞限流）。

**输入**：S1 同款提示词/校验命令，`MODEL_ID=mdl_7e7e6eaf`，两进程同时启动。

**预期**：
- 至少一个 run 的事件计数含 `STATUS: N`（N≥1，重试发生）且最终 PASS（恢复后跑完）
- session 全程 Working → Idle，**不出现 ERROR**
- `MEDERI_LLM_RETRY_MAX=1` 强制耗尽路径：MESSAGE_ERROR 照发 + session 保持 **Idle**

**实测记录**：
| 运行 | 结果 | 备注 |
|---|---|---|
| 并行 run1/run2（默认 10 次） | PASS ×2 | STATUS 1 次/5 次；重试后恢复，会话未受污染 |
| 并行 run1（RETRY_MAX=1） | 耗尽路径触发 | STATUS 1 次 → 再撞限流 → MESSAGE_ERROR，**session=Idle** ✓ |
| 并行 run2（RETRY_MAX=1） | PASS | 重试 1 次后成功 |

**已知边界**：重试耗尽时 turn 中途崩溃，已执行的 assistant 消息/工具调用不落库
（消息持久化在 turn 末尾，见已知问题 6）——必需行为断言会把这种轮次判 FLOW_FAIL。

## 6. 已知局限（这份测试**不能**保证的事）

诚实声明，防止假信心：

1. **单一模型、小样本**。全部结论来自 agnes-2.5-flash 一个免费模型，每场景跑 1~3 次。
   模型方差不可控（sess_2f622185 的相对导入就是真实方差案例）；"修好了"和"这次模型状态好"
   在 N<5 时无法统计区分。判定器报告会如实呈现每次差异，但不要把单次 PASS 当收敛证明。
2. **覆盖面是场景级，不是分支级**。已覆盖：小改动直改、计划闭环 PASS 路径、converge_plan
   补救路径（自然触发一次）、归档、diff 记录、硬门禁（幻觉 planId 被拒）。
   **未测**：APPROVAL 模式（计划批准挂起/恢复）、沙箱拒绝路径（白名单外写入被拒的错误反馈）、
   自动上下文压缩（70% 预检）、WORK 模式、ask_user、rollbackToMessage、并发会话、
   maxAgentIterations 撞顶后的行为。
3. **签名分类是启发式**。关键词清单靠人维护，新的 harness 故障形态可能漏报
   （历史上已发生过一次误分类并修正）。PASS 的可信度高于 FLOW_FAIL 的完备性。
4. **EXPECT_TOOLS 只查"调用过"，不查语义正确**。模型调了 create_plan 但计划内容一塌糊涂，
   断言仍会放行——语义质量靠人工看报告里的工具序列和 PlanStore 内容。
5. **免费供应商稳定性**。Agnes 免费通道可能限流/超时，失败先看"模型/环境报错"行归因，
   不要直接当成 harness 回归。

## 7. 已知问题清单（测试发现的、尚未修的）

| # | 问题 | 严重度 | 状态 |
|---|---|---|---|
| 1 | `List<DecisionArg>` / `List<PlannedChangeArg>` 无宽松化：模型把对象列表发成 JSON 字符串或裸对象时，kotlinx 解析直接崩（`Expected JsonArray, but had JsonLiteral`），模型通常自愈但浪费 1-2 轮。根治方案 = 描述符驱动的参数整形（在 decodeArgs 前按序列化器描述符把字符串/裸对象 coerce 成数组），改动面较大 | 中 | 待办 |
| 2 | 子代理写文件不回滚到父会话 diffTracker——报告"流程记录 diff"为空（改动走了子代理），UI 差异面板对计划路径的改动不可见 | 中 | 设计取舍待定 |
| 3 | `execute_command`/shell 直改文件不经 diffTracker（与 #2 同根）：diff 面板只覆盖文件工具路径 | 中 | 设计取舍待定 |
| 4 | 模型收尾纪律弱（agnes-2.5-flash）：计划没全 PASS 就 write_log 收工；首调参数形状错后自愈。harness 可加"结束前计划必须收敛"的软门禁提示词 | 低 | 观察中 |
| 5 | verify_subtask 计数文案从 1 开始（`Subtask ${index+1}`）与 0 基索引错位，读日志时易混淆 | 低 | 待办（化妆性） |
| 6 | **assistant 消息在 turn 末尾才持久化**：turn 中途崩溃（限流重试耗尽、供应商断流）时，本轮已执行的 assistant 消息与工具调用全部丢失——事件流里可见（TOOL_CALLED 3 次）、raw 消息里为空。已落盘的文件改动成为"无历史的改动"，下轮模型不知道自己做了什么。修复方向：按 LLM 响应增量持久化 | 高 | 待办 |

### 限流重试设定（2026-09-08 新增）

- `RetryableLLMClient`（core）：429/rpm/quota/网关过载自动重试，随机 1~10s 平退避，默认 10 次；流式首帧后失败不重试（防重复内容）；CancellationException 永不重试
- 环境变量（server 启动）：`MEDERI_LLM_RETRY_MAX`（默认 10，0=关闭）、`MEDERI_LLM_RETRY_MIN_MS` / `MEDERI_LLM_RETRY_MAX_MS`（默认 1000/10000）
- 重试进度 = `STATUS` 事件（scope=provider, code=RETRYING），UI 派生"重试中"；session 状态机零改动
- 耗尽后：session 保持 Idle（环境态），MESSAGE_ERROR 照发

## 8. 与修复记录的对应

本套测试共抓出并修复 6 个真实 harness bug（全部经端到端复跑验证）：

1. `ReadFileArgs` 的 `@Serializable` 被注释行吞掉 → read_file 永远解析失败（M1 变异测试的模板）
2. `PLAN_TOOL_NAMES` 漏 `generate_spec` → 工具未注册、计划闭环根断（S2 覆盖）
3. `DecisionArg` 必填字段无默认 → create_plan 反复被拒（S2 覆盖，含聚合校验改进）
4. 提示词缺 create_plan JSON 范例与报错即停规则（S2 覆盖）
5. **边注册顺序 bug**：Text+ToolCall 混排消息静默丢工具调用（S1 混排实测 + S2 修复前后对照）
6. 计划归档无触发点：`isAllCompleted`/`archive` 孤儿（T1 专门覆盖）
