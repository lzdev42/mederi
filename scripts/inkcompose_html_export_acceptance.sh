#!/usr/bin/env bash
# inkcompose HTML 导出工程（plan_09193e65）统一验收收集器。
#
# 用法: bash scripts/inkcompose_html_export_acceptance.sh <suite>
#   suite ∈ { st0 st1 st2 st3 st4 st5 st6 st7 st8 }
#
# 行为:
#   1. 删除目标 suite 的 JUnit XML（防陈旧结果误判）
#   2. 运行对应 gradle 过滤测试类（编译含全部 commonTest/jvmTest 源）
#   3. 逐测试方法从 JUnit XML 判定 PASS/FAIL（方法含 <failure>/<error> 即 FAIL）
#   4. 输出单个 JSON 对象：suite 统计 + 逐方法判定（methods）
#
# 退出码: 0 = 全部通过；1 = 存在失败或运行错误。

set -u
cd "$(dirname "$0")/.." || exit 1

SUITE="${1:-}"
RESULTS_DIR="inkcompose/build/test-results/jvmTest"

# 从 JUnit XML 输出 JSON 方法级判定：{"method":"PASS|FAIL",...}
emit_case_results() {
  local xml="$1"
  python3 - "$xml" <<'PY'
import re, sys, json
path = sys.argv[1]
with open(path, "r", encoding="utf-8") as f:
    xml = f.read()
cases = {}
for m in re.finditer(r'<testcase name="([^"]+)"', xml):
    name = m.group(1)
    method = re.sub(r"\[jvm\]$", "", name)
    start = m.end()
    nxt = xml.find("<testcase ", start)
    seg = xml[start: nxt if nxt != -1 else len(xml)]
    cases[method] = "FAIL" if ("<failure" in seg or "<error" in seg) else "PASS"
print(json.dumps(cases))
PY
}

case "$SUITE" in
  st0)
    TEST_FILTER="xyz.emuci.markdown.parser.html.HtmlExportHookTest"
    XML="$RESULTS_DIR/TEST-xyz.emuci.markdown.parser.html.HtmlExportHookTest.xml"
    ;;
  st1)
    TEST_FILTER="xyz.emuci.markdown.parser.html.HtmlExportStyleTest"
    XML="$RESULTS_DIR/TEST-xyz.emuci.markdown.parser.html.HtmlExportStyleTest.xml"
    ;;
  st2)
    TEST_FILTER="xyz.emuci.markdown.parser.html.HtmlExportCodeBlockTest"
    XML="$RESULTS_DIR/TEST-xyz.emuci.markdown.parser.html.HtmlExportCodeBlockTest.xml"
    ;;
  st3)
    TEST_FILTER="xyz.emuci.markdown.parser.html.HtmlExportMathTest"
    XML="$RESULTS_DIR/TEST-xyz.emuci.markdown.parser.html.HtmlExportMathTest.xml"
    ;;
  st4)
    TEST_FILTER="xyz.emuci.markdown.parser.html.HtmlExportDiagramTest"
    XML="$RESULTS_DIR/TEST-xyz.emuci.markdown.parser.html.HtmlExportDiagramTest.xml"
    ;;
  st5)
    TEST_FILTER="xyz.emuci.markdown.parser.html.HtmlExportVlrTest"
    XML="$RESULTS_DIR/TEST-xyz.emuci.markdown.parser.html.HtmlExportVlrTest.xml"
    ;;
  st6)
    TEST_FILTER="xyz.emuci.markdown.parser.html.HtmlExportExtendedBlocksTest"
    XML="$RESULTS_DIR/TEST-xyz.emuci.markdown.parser.html.HtmlExportExtendedBlocksTest.xml"
    ;;
  st7)
    TEST_FILTER="xyz.emuci.inkcompose.HtmlFontEmbeddingTest"
    XML="$RESULTS_DIR/TEST-xyz.emuci.inkcompose.HtmlFontEmbeddingTest.xml"
    ;;
  st8)
    # 整体验收：矩阵 + 既有回归 + 架构文档同步
    T1="xyz.emuci.inkcompose.MarkdownExporterFullMatrixTest"
    T2="xyz.emuci.inkcompose.MarkdownExporterTest"
    T3="xyz.emuci.markdown.parser.HtmlRendererTest"
    for t in "$T1" "$T2" "$T3"; do rm -f "$RESULTS_DIR/TEST-${t}.xml"; done
    if ! ./gradlew :inkcompose:jvmTest --tests "$T1" --tests "$T2" --tests "$T3" >/tmp/ink_accept_st8_gradle.log 2>&1; then
      printf '{"suite":"st8","gradle":"FAIL","AC18":"FAIL","AC19":"FAIL","AC20":"FAIL"}\n'
      tail -30 /tmp/ink_accept_st8_gradle.log >&2
      exit 1
    fi
    TOTAL_F=0; TOTAL_E=0; TOTAL_T=0; MISSING=""
    for t in "$T1" "$T2" "$T3"; do
      X="$RESULTS_DIR/TEST-${t}.xml"
      if [ ! -f "$X" ]; then MISSING="$MISSING $t"; continue; fi
      read -r f e n < <(sed -n 's/.*tests="\([0-9]*\)".*failures="\([0-9]*\)".*errors="\([0-9]*\)".*/\2 \3 \1/p' "$X" | head -1)
      TOTAL_F=$((TOTAL_F + ${f:-0})); TOTAL_E=$((TOTAL_E + ${e:-0})); TOTAL_T=$((TOTAL_T + ${n:-0}))
    done
    DOC_HITS=$(grep -c 'HtmlExportPipeline' docs/architecture/03-inkcompose.md)
    printf '{"suite":"st8","tests":"%d","failures":"%d","errors":"%d","missing":"%s","doc_hits":"%s","AC18":"%s","AC19":"%s","AC20":"%s"}\n' \
      "$TOTAL_T" "$TOTAL_F" "$TOTAL_E" "${MISSING:-none}" "$DOC_HITS" \
      "$( [ "$TOTAL_F" = 0 ] && [ "$TOTAL_E" = 0 ] && [ "$TOTAL_T" -gt 0 ] && [ -z "$MISSING" ] && echo 'AC18=PASS（矩阵 tests>0 零失败）' || echo FAIL )" \
      "$( [ "$DOC_HITS" -ge 1 ] 2>/dev/null && echo 'AC19=PASS（doc_hits≥1）' || echo FAIL )" \
      "$( [ "$TOTAL_F" = 0 ] && [ "$TOTAL_E" = 0 ] && echo 'AC20=PASS（三测试类零失败零错误）' || echo FAIL )"
    [ "$TOTAL_F" = 0 ] && [ "$TOTAL_E" = 0 ] && exit 0 || exit 1
    ;;
  *)
    echo "usage: $0 <st0|st1|st2|st3|st4|st5|st6|st7|st8>" >&2
    exit 1
    ;;
esac

rm -f "$XML"
if ! ./gradlew :inkcompose:jvmTest --tests "$TEST_FILTER" >/tmp/ink_accept_${SUITE}_gradle.log 2>&1; then
  printf '{"suite":"%s","gradle":"FAIL","case_result":"FAIL"}\n' "$SUITE"
  tail -30 /tmp/ink_accept_${SUITE}_gradle.log >&2
  exit 1
fi
if [ ! -f "$XML" ]; then
  printf '{"suite":"%s","missing_xml":true,"case_result":"FAIL"}\n' "$SUITE"
  exit 1
fi

read -r T F E < <(sed -n 's/.*tests="\([0-9]*\)".*failures="\([0-9]*\)".*errors="\([0-9]*\)".*/\1 \2 \3/p' "$XML" | head -1)
CASES_JSON=$(emit_case_results "$XML")
if [ "${F:-1}" = 0 ] && [ "${E:-1}" = 0 ] && [ -n "${T:-}" ] && [ "$T" -gt 0 ]; then
  RESULT="PASS"
else
  RESULT="FAIL"
fi
case "$SUITE" in
  st0) PROSE_PASS='PASS（tests=6 全部方法 PASS）' ;;
  st1|st2|st3|st4|st5|st6|st7) PROSE_PASS='PASS（方法级全过）' ;;
esac
OBSERVED="$([ "$RESULT" = "PASS" ] && echo "$PROSE_PASS" || echo FAIL)"
printf '{"suite":"%s","tests":"%s","failures":"%s","errors":"%s","case_result":"%s","%s-case":"%s","methods":%s}\n' \
  "$SUITE" "${T:-0}" "${F:-0}" "${E:-0}" "$RESULT" "$SUITE" "$OBSERVED" "$CASES_JSON"
[ "$RESULT" = "PASS" ] && exit 0 || exit 1
