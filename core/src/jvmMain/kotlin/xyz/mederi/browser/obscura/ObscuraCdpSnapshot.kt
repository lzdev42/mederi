package xyz.mederi.browser.obscura

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.mederi.debug.DebugLog

/**
 * Obscura CDP 快照层（ST3，配合 ObscuraCdpControl/ObscuraCdpLocator；观测落档
 * `core/build/obscura-cdp-control-test.json`，键 `snapshot_ref_format` 断言本层输出格式）。
 *
 * 快照格式锚点（BrowserPrompt 依赖，零漂移）：
 * - 首两行 `URL: <url>` / `Title: <title>`；
 * - 元素行 `- <role> "<name>" [ref=eN]`（控件描述优先）；
 * - 正文文本（body innerText）在控件行之后，max_chars 预算超限时截断正文（正常截断，非错误）。
 *
 * 可交互元素编号（执行拍板，防再纠结）：
 * - ref 编号以**页面侧 DOM 序**为准：一次 [Runtime.evaluate] 注入
 *   `data-mederi-ref="eN"` 临时属性（选择器 = 交互元素 + h1-h6 heading，与 CDP 页面侧自判一致），
 *   点击/填充/滚动等交互全部经 `document.querySelector('[data-mederi-ref="eN"]')` 重定位——
 *   **点击路径与 AX 树完全解耦**（不依赖 DOM.setAttributeValue 等未实测 CDP 方法）。
 * - role/name 以 [Accessibility.getFullAXTree] 为准（近全树、隐藏子树已剔除，ST1 探针实测）：
 *   [DOM.getDocument]（depth=-1）树按文档序枚举交互元素 → backendNodeId 与注入序号一一对应
 *   （ST1 探针实测 CDP nodeId 与 backendNodeId 同源）→ AX 树按 backendDOMNodeId 对齐
 *   （探针实测同值：AX RootWebArea backendDOMNodeId=0 ↔ DOM #document nodeId=0）。
 *   对齐失败（元素被 AX 剔除/无 AX 节点）→ 回退 DOM 侧 tag→role 推导 + 属性名，ref 照发（宁缺毋假）。
 *
 * 坐标一律真实布局（[Runtime.evaluate] getBoundingClientRect，ST1 实测）；DOMSnapshot 合成 bounds 禁用。
 * 数值解析走 Double（JsonPrimitive.doubleOrNull/content.toDoubleOrNull，ST1 探针实测数值序列化为 0.0 字面量）。
 */

/** ref 表条目（ref=eN → 对齐信息；[backendNodeId] 对齐失败时 -1；[relselector] 供诊断/未来重定位）。 */
data class RefEntry(
    val backendNodeId: Int,
    val role: String,
    val name: String,
    val relselector: String,
)

/** 快照构建结果：文本 + 解析出的 url/title + ref 表（key = "eN"，DOM 文档序递增）。 */
data class CdpSnapshot(
    val text: String,
    val url: String,
    val title: String,
    val refIndex: Map<String, RefEntry>,
)

/** DOM 树节点（kotlinx 解析；attributes 扁平数组 [k,v,k,v...]，ST1 实测 shape）。 */
internal class DomNode(
    val nodeId: Int?,
    val backendNodeId: Int?,
    val nodeType: Int,
    val localName: String?,
    val attributes: Map<String, String>,
    val children: List<DomNode>,
)

/** 按文档序枚举出的可交互元素（与注入 JS 的选择器集合语义一致）。 */
internal class InteractiveRef(
    val backendNodeId: Int,
    val tag: String,
    val id: String?,
    val domRole: String,
    val domName: String,
)

/** AX 节点对齐结果。 */
internal class AxNode(
    val role: String?,
    val name: String?,
)

/**
 * 快照捕获：注入 ref → DOM 树枚举 + AX 树对齐 → 行组装（max_chars 预算：控件行优先、正文靠后截断）。
 * 会话级命令（Runtime/DOM/Accessibility）全部携带 [sessionId]（ST2 显式 attach 的唯一路由身份）。
 */
internal suspend fun captureCdpSnapshot(
    client: ObscuraCdpClient,
    sessionId: String?,
    maxChars: Int = 20_000,
): CdpSnapshot {
    // ① DOM 全树 → 文档序可交互元素（backendNodeId 映射；失败回退空表，ref 仍照发）
    val refs = runCatching {
        client.send(
            "DOM.getDocument",
            buildJsonObject { put("depth", JsonPrimitive(-1)) },
            sessionId,
        )
    }.getOrNull()
    val domRefs = refs?.let { parseDomTree(it) }?.let { listOf(it) }?.collectInteractive() ?: emptyList()

    // ② 页面侧注入 data-mederi-ref（ref 编号 = 注入 JS 的 DOM 序；count 与 ① 枚举互校）
    val injectRaw = runCatching {
        client.send("Runtime.evaluate", buildJsonObject {
            put("expression", REF_INJECT_SCRIPT)
            put("returnByValue", true)
        }, sessionId)
    }.getOrNull()
    val inject = parseInjectResult(injectRaw?.get("result")?.jsonObject)
    val refCount = minOf(
        inject?.count ?: domRefs.size,
        domRefs.size,
    ).coerceAtLeast(0)
    if (inject != null && inject.count != domRefs.size) {
        DebugLog.info(
            "ObscuraCdpSnapshot",
            "ref 注入 count=${inject.count} 与 DOM 树枚举=${domRefs.size} 不一致（取 min 对齐，交互仍按注入属性定位）",
        )
    }

    // ③ AX 树：backendDOMNodeId → (role, name)（对齐源；ignored 节点剔除）
    val axByBackend = runCatching {
        client.send("Accessibility.getFullAXTree", buildJsonObject {}, sessionId)
    }.getOrNull()
        ?.let { parseAxTree(it) }
        ?: emptyMap()

    // ④ 元素行：AX 优先，回退 DOM 推导；refIndex 同时存 backendNodeId/relselector
    val refIndex = (0 until refCount).associate { i ->
        val refName = "e" + (i + 1)
        val dom = domRefs.getOrNull(i)
        val ax = dom?.backendNodeId?.let { axByBackend[it] }
        val role = ax?.role?.takeIf { it != "generic" } ?: dom?.domRole ?: "generic"
        val name = ax?.name?.takeIf { it.isNotEmpty() } ?: dom?.domName.orEmpty()
        refName to RefEntry(
            backendNodeId = dom?.backendNodeId ?: -1,
            role = role,
            name = name,
            relselector = dom?.id?.let { "#$it" } ?: "[data-mederi-ref=\"$refName\"]",
        )
    }

    // ⑤ URL/Title + 正文（单次 evaluate；失败空串，快照照出）
    val meta = runCatching {
        client.send(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", META_SCRIPT)
                put("returnByValue", true)
            },
            sessionId,
        )
    }.getOrNull()
    val (url, title) = parseMeta(meta?.get("result")?.jsonObject)
    val bodyRaw = runCatching {
        client.send(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", BODY_TEXT_SCRIPT)
                put("returnByValue", true)
            },
            sessionId,
        )
    }.getOrNull()
    val bodyText = extractBodyText(bodyRaw?.get("result")?.jsonObject)

    return CdpSnapshot(
        text = buildSnapshotText(maxChars, refIndex, url, title, bodyText),
        url = url,
        title = title,
        refIndex = refIndex,
    )
}

// ==================== 页面侧脚本（一次注入 + 元数据；表达式全文在测试 FakeCdpServer 按 contains 匹配）====================

/**
 * 注入脚本：可交互元素（交互控件 + h1-h6 heading）按 DOM 文档序赋 `data-mederi-ref="eN"`，
 * 返回 JSON `{count, ids}`（ids 供 refIndex.relselector 诊断）。选择器集合与 [isInteractive] 语义一致。
 */
internal const val REF_INJECT_SCRIPT =
    "(function(){var sel='a[href],button,input:not([type=hidden]),select,textarea," +
        "[role=button],[role=link],[role=checkbox],[role=radio],[role=textbox],[role=combobox]," +
        "[role=slider],[role=tab],[role=menuitem],[contenteditable=true],h1,h2,h3,h4,h5,h6';" +
        "var els=document.querySelectorAll(sel);var out=[];" +
        "for(var i=0;i<els.length;i++){var e=els[i];var r='e'+(i+1);" +
        "e.setAttribute('data-mederi-ref',r);out.push(e.id||'');}" +
        "return JSON.stringify({count:els.length,ids:out});})()"

/** 页面元数据：URL + Title（单次 evaluate 省往返）。 */
internal const val META_SCRIPT =
    "(function(){return JSON.stringify({url:location.href,title:document.title})})()"

/** 正文文本（预算尾部；换行折叠为单空格——正文按行追加在控件行之后）。 */
internal const val BODY_TEXT_SCRIPT =
    "(function(){return (document.body&&document.body.innerText)||''})()"

// ==================== kotlinx 解析（禁止手拼 JSON / 正则解析 JSON）====================

internal fun parseDomTree(res: JsonObject): DomNode? {
    val rootElem = res["root"]?.jsonObject ?: return null
    return parseDomNode(rootElem)
}

internal fun parseDomNode(elem: JsonObject): DomNode {
    fun intOf(key: String): Int? =
        (elem[key] as? JsonPrimitive)?.content?.toIntOrNull()
    val children = (elem["children"] as? JsonArray)?.mapNotNull {
        val obj = (it as? JsonObject) ?: return@mapNotNull null
        parseDomNode(obj)
    } ?: emptyList()
    val attrs = (elem["attributes"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        ?.chunked(2)
        ?.associate { if (it.size == 2) it[0] to it[1] else "" to "" }
        ?: emptyMap()
    return DomNode(
        nodeId = intOf("nodeId"),
        backendNodeId = intOf("backendNodeId"),
        nodeType = intOf("nodeType") ?: 0,
        localName = (elem["localName"] as? JsonPrimitive)?.contentOrNull,
        attributes = attrs,
        children = children,
    )
}

/** 文档序（children 即 DOM 序）枚举可交互元素；backendNodeId 缺失的节点跳过（无对齐信息）。 */
internal fun List<DomNode>.collectInteractive(): List<InteractiveRef> {
    val out = ArrayList<InteractiveRef>()
    fun walk(nodes: List<DomNode>) {
        for (n in nodes) {
            if (n.nodeType == 1 && isInteractive(n)) {
                n.backendNodeId?.let { bid ->
                    out.add(
                        InteractiveRef(
                            backendNodeId = bid,
                            tag = n.localName?.lowercase().orEmpty(),
                            id = n.attributes["id"].orEmpty().takeIf { it.isNotEmpty() },
                            domRole = domRoleOf(n),
                            domName = domNameOf(n),
                        ),
                    )
                }
            }
            if (n.children.isNotEmpty()) walk(n.children)
        }
    }
    walk(this)
    return out
}

/** [REF_INJECT_SCRIPT] 中 `[role=...]` 选择器集合（attribute role 值判定共用）。 */
internal val ROLE_AGGREGATES = setOf(
    "button", "link", "checkbox", "radio", "textbox", "combobox", "slider", "tab", "menuitem",
)

/** 与 [REF_INJECT_SCRIPT] 选择器集合一致的交互判定（KISS：标签/属性直判）。 */
internal fun isInteractive(node: DomNode): Boolean {
    val tag = node.localName?.lowercase() ?: return false
    return when (tag) {
        "a" -> node.attributes.containsKey("href")
        "button" -> true
        "input" -> node.attributes["type"]?.lowercase() != "hidden"
        "select", "textarea", "h1", "h2", "h3", "h4", "h5", "h6" -> true
        else ->
            node.attributes["role"]?.lowercase() in ROLE_AGGREGATES ||
                node.attributes["contenteditable"]?.lowercase() == "true"
    }
}

/** 标签 → AX role 推导（AX 对齐失败时的回退；input 细分按 type 属性）。 */
private fun domRoleOf(node: DomNode): String {
    val attrRole = node.attributes["role"]?.lowercase()
    if (attrRole != null) return attrRole
    val tag = node.localName?.lowercase().orEmpty()
    return when (tag) {
        "button" -> "button"
        "a" -> "link"
        "select" -> "combobox"
        "textarea" -> "textbox"
        "h1", "h2", "h3", "h4", "h5", "h6" -> "heading"
        "input" -> when (node.attributes["type"]?.lowercase()) {
            "checkbox" -> "checkbox"
            "radio" -> "radio"
            "range" -> "slider"
            else -> "textbox"
        }
        else -> "generic"
    }
}

/** 属性名回退：aria-label → title → alt → input value（截断折叠，行文本用）。 */
private fun domNameOf(node: DomNode): String {
    val attr = listOf("aria-label", "title", "alt")
        .mapNotNull { node.attributes[it] }
        .firstOrNull { it.isNotBlank() }
    val value = if (node.localName?.lowercase() == "input") node.attributes["value"] else null
    val raw = attr ?: value?.takeIf { it.isNotBlank() } ?: ""
    return raw.replace(Regex("\\s+"), " ").trim().take(120)
}

/** AX 树解析：backendDOMNodeId → AxNode(role.value, name.value)；ignored 节点剔除（ST1 实测 hidden 子树已裁）。 */
internal fun parseAxTree(res: JsonObject): Map<Int, AxNode> {
    val nodes = res["nodes"]?.jsonArray ?: return emptyMap()
    val out = HashMap<Int, AxNode>()
    for (n in nodes) {
        val obj = n.jsonObject
        if (obj["ignored"]?.jsonPrimitive?.booleanOrNull == true) continue
        val bid = (obj["backendDOMNodeId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
        out[bid] = AxNode(
            role = (obj["role"]?.jsonObject?.get("value") as? JsonPrimitive)?.contentOrNull,
            name = (obj["name"]?.jsonObject?.get("value") as? JsonPrimitive)?.contentOrNull,
        )
    }
    return out
}

/** 注入应答 `{count, ids}`（Runtime.evaluate result.value 为 JSON 字符串——数值走 Double 解析）。 */
internal class InjectResult(val count: Int, val ids: List<String>)

internal fun parseInjectResult(result: JsonObject?): InjectResult? {
    val value = (result?.get("value") as? JsonPrimitive)?.contentOrNull ?: return null
    val obj = runCatching { JSON.parseToJsonElement(value).jsonObject }.getOrNull() ?: return null
    val count = (obj["count"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0
    val ids = (obj["ids"] as? JsonArray)
        ?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
        ?: emptyList()
    return InjectResult(count, ids)
}

internal fun parseMeta(result: JsonObject?): Pair<String, String> {
    val value = (result?.get("value") as? JsonPrimitive)?.contentOrNull ?: return "" to ""
    val obj = runCatching { JSON.parseToJsonElement(value).jsonObject }.getOrNull() ?: return "" to ""
    return ((obj["url"] as? JsonPrimitive)?.contentOrNull ?: "") to
        ((obj["title"] as? JsonPrimitive)?.contentOrNull ?: "")
}

/** 正文 innerText（换行折叠单空格，截断 5000 防失控预算）；数值/缺失 → 空串。 */
internal fun extractBodyText(result: JsonObject?): String {
    val value = result?.get("value") ?: return ""
    val text = (value as? JsonPrimitive)?.contentOrNull ?: return ""
    return if (value is JsonPrimitive && value.isString) {
        text.replace(Regex("\\s*\\n\\s*"), " ").take(5_000)
    } else ""
}

// ==================== 行组装（格式锚点：URL:/Title: 首两行 + 控件行 + 正文尾部）====================

internal fun buildSnapshotText(
    maxChars: Int,
    refIndex: Map<String, RefEntry>,
    url: String,
    title: String,
    bodyText: String,
): String {
    val lines = StringBuilder()
    lines.append("URL: ").append(url).append('\n')
    lines.append("Title: ").append(title).append('\n')
    for ((ref, entry) in refIndex) {
        lines.append('-').append(' ')
            .append(entry.role)
            .append(' ')
            .append('\"').append(escapeSnapshotName(entry.name)).append('\"')
            .append(" [ref=").append(ref).append(']')
            .append('\n')
    }
    // 正文预算：控件行优先（URL/Title + 元素行全额保留），剩余预算给正文（超预算截断是正常行为）
    val control = lines.toString()
    val bodyBudget = (maxChars - control.length).coerceAtLeast(0)
    val body = if (bodyText.length > bodyBudget) bodyText.take(bodyBudget) else bodyText
    if (body.isNotEmpty()) {
        lines.append(body).append('\n')
    }
    return lines.toString()
}

/** 行内 name 转义（引号/反斜杠，防 `- role "a"b" [ref=eN]` 行解析歧义；对齐 MCP 版快照行语义）。 */
internal fun escapeSnapshotName(name: String): String =
    name.replace("\\", "\\\\").replace("\"", "\\\"")

/** JSON 实例（对象内解析共用；禁止手拼/正则解析 JSON——kotlinx 全程）。 */
internal val JSON = kotlinx.serialization.json.Json
