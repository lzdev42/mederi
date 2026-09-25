package xyz.mederi.browser.bidi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BiDiLocator internal constructor(
    private val page: BiDiPage,
    private val selector: String,
    private val selectorType: SelectorType,
    private val name: String? = null,
    private val exact: Boolean = true,
    private val filter: ((LocateResult) -> Boolean)? = null,
    private val index: Int? = null
) {
    fun filter(predicate: (LocateResult) -> Boolean): BiDiLocator =
        BiDiLocator(page, selector, selectorType, name, exact, predicate, index)

    fun nth(index: Int): BiDiLocator =
        BiDiLocator(page, selector, selectorType, name, exact, filter, index)

    fun first(): BiDiLocator = nth(0)
    fun last(): BiDiLocator = nth(-1)

    // ────────────────────────────────────────────────
    // 定位
    // ────────────────────────────────────────────────

    private suspend fun locate(): List<LocateResult> {
        val js = buildLocateJs()
        val raw = page.evaluateJavascript(js)
        if (raw.isEmpty()) return emptyList()
        var results = try {
            Json.decodeFromString<List<LocateResult>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
        if (results.isEmpty()) return emptyList()
        filter?.let { results = results.filter(it) }
        index?.let { idx ->
            val realIdx = if (idx < 0) results.size + idx else idx
            results = listOfNotNull(results.getOrNull(realIdx))
        }
        return results
    }

    private fun buildLocateJs(): String {
        val sel = escapeJsString(selector)
        return when (selectorType) {
            SelectorType.CSS -> BiDiJsScripts.FIND_ALL_BY_CSS.replace("__SELECTOR__", sel)
            SelectorType.XPATH -> BiDiJsScripts.FIND_ALL_BY_XPATH.replace("__SELECTOR__", sel)
            SelectorType.ROLE -> BiDiJsScripts.FIND_ALL_BY_ROLE
                .replace("__ROLE__", escapeJsString(selector))
                .replace("__NAME__", escapeJsString(name ?: ""))
            SelectorType.TEXT -> BiDiJsScripts.FIND_ALL_BY_TEXT
                .replace("__TEXT__", sel)
                .replace("__EXACT__", exact.toString())
            SelectorType.LABEL -> BiDiJsScripts.FIND_ALL_BY_LABEL.replace("__LABEL__", sel)
            SelectorType.PLACEHOLDER -> BiDiJsScripts.FIND_ALL_BY_PLACEHOLDER.replace("__TEXT__", sel)
            SelectorType.ALT_TEXT -> BiDiJsScripts.FIND_ALL_BY_ALT_TEXT.replace("__TEXT__", sel)
            SelectorType.TITLE -> BiDiJsScripts.FIND_ALL_BY_TITLE.replace("__TITLE__", sel)
            SelectorType.TEST_ID -> BiDiJsScripts.FIND_ALL_BY_TEST_ID.replace("__TESTID__", sel)
        }
    }

    // ────────────────────────────────────────────────
    // 交互（坐标优先）
    // ────────────────────────────────────────────────

    suspend fun click() {
        val r = locate().firstOrNull() ?: throw ElementNotFoundException(selector)
        page.clickByCoordinates(r.centerX, r.centerY)
    }

    suspend fun hover() {
        val r = locate().firstOrNull() ?: throw ElementNotFoundException(selector)
        page.hoverByCoordinates(r.centerX, r.centerY)
    }

    suspend fun scroll(deltaX: Int, deltaY: Int) {
        val r = locate().firstOrNull() ?: throw ElementNotFoundException(selector)
        page.scrollByCoordinates(r.centerX, r.centerY, deltaX, deltaY)
    }

    suspend fun fill(value: String): OperationResult {
        val results = locate()
        if (results.isEmpty()) throw ElementNotFoundException(selector)
        val sel = results[0].selector.ifEmpty { selector }
        val js = BiDiJsScripts.SET_VALUE_NATIVE
            .replace("__SELECTOR_TYPE__", selectorType.name)
            .replace("__SELECTOR__", escapeJsString(sel))
            .replace("__VALUE__", escapeJsString(value))
        val ok = page.evaluateJavascript(js)
        if (ok != "ok") return OperationResult.Failure("fill", "fill returned: $ok")
        val current = page.evaluateJavascript(
            "(function(){var e=document.querySelector('${escapeJsString(sel)}');return e?e.value:''})()"
        )
        return if (current == value) {
            OperationResult.Success("fill", verified = true, detail = "value matched")
        } else {
            OperationResult.Failure("fill", "value verification failed: expected=$value, actual=$current")
        }
    }

    suspend fun type(text: String) {
        val r = locate().firstOrNull() ?: throw ElementNotFoundException(selector)
        page.clickByCoordinates(r.centerX, r.centerY)
        kotlinx.coroutines.delay(80)
        page.pressKeyCombination(KeyboardKey.CONTROL, KeyboardKey.A)
        kotlinx.coroutines.delay(40)
        page.press(KeyboardKey.DELETE)
        kotlinx.coroutines.delay(40)
        page.type(text)
    }

    suspend fun focus() {
        val r = locate().firstOrNull() ?: throw ElementNotFoundException(selector)
        page.clickByCoordinates(r.centerX, r.centerY)
    }

    suspend fun check() {
        val r = locate().firstOrNull() ?: throw ElementNotFoundException(selector)
        page.clickByCoordinates(r.centerX, r.centerY)
    }

    suspend fun selectOption(value: String) {
        val results = locate()
        if (results.isEmpty()) throw ElementNotFoundException(selector)
        val sel = results[0].selector.ifEmpty { selector }
        val js = BiDiJsScripts.FIND_OPTION_AND_CLICK
            .replace("__SELECTOR__", escapeJsString(sel))
            .replace("__VALUE__", escapeJsString(value))
        val raw = page.evaluateJavascript(js)
        if (raw == "null" || raw.isEmpty()) throw ElementNotFoundException("option $value in $sel")
        val opt = Json.decodeFromString(LocateResult.serializer(), raw)
        page.clickByCoordinates(opt.centerX, opt.centerY)
    }

    suspend fun press(key: KeyboardKey) {
        focus()
        kotlinx.coroutines.delay(40)
        page.press(key)
    }

    suspend fun pressKeyCombination(modifier: KeyboardKey, key: KeyboardKey) {
        focus()
        kotlinx.coroutines.delay(40)
        page.pressKeyCombination(modifier, key)
    }

    // ────────────────────────────────────────────────
    // JS 变体
    // ────────────────────────────────────────────────

    suspend fun jsClick() {
        val results = locate()
        if (results.isEmpty()) throw ElementNotFoundException(selector)
        val sel = results[0].selector.ifEmpty { selector }
        page.evaluateJavascript("var e=document.querySelector('${escapeJsString(sel)}');if(e)e.click();")
    }

    suspend fun jsHover() {
        val results = locate()
        if (results.isEmpty()) throw ElementNotFoundException(selector)
        val sel = results[0].selector.ifEmpty { selector }
        page.evaluateJavascript(
            "var e=document.querySelector('${escapeJsString(sel)}');" +
            "if(e){e.dispatchEvent(new MouseEvent('mouseover',{bubbles:true,cancelable:true}));" +
            "e.dispatchEvent(new MouseEvent('mouseenter',{bubbles:false,cancelable:true}));}"
        )
    }

    suspend fun jsScroll(deltaX: Int, deltaY: Int) {
        val results = locate()
        if (results.isEmpty()) throw ElementNotFoundException(selector)
        val sel = results[0].selector.ifEmpty { selector }
        page.evaluateJavascript("var e=document.querySelector('${escapeJsString(sel)}');if(e)e.scrollBy($deltaX,$deltaY);")
    }

    suspend fun jsFill(value: String) {
        val results = locate()
        if (results.isEmpty()) throw ElementNotFoundException(selector)
        val sel = results[0].selector.ifEmpty { selector }
        val js = BiDiJsScripts.SET_VALUE_NATIVE
            .replace("__SELECTOR_TYPE__", selectorType.name)
            .replace("__SELECTOR__", escapeJsString(sel))
            .replace("__VALUE__", escapeJsString(value))
        page.evaluateJavascript(js)
    }

    suspend fun jsType(text: String) {
        val results = locate()
        if (results.isEmpty()) throw ElementNotFoundException(selector)
        val sel = results[0].selector.ifEmpty { selector }
        page.evaluateJavascript(
            "var e=document.querySelector('${escapeJsString(sel)}');if(e){" +
            "var ns=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value')&&Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set||Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value')&&Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;" +
            "if(ns)ns.call(e,'$text');else e.value='$text';" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}"
        )
    }

    suspend fun jsFocus() {
        val results = locate()
        if (results.isEmpty()) throw ElementNotFoundException(selector)
        val sel = results[0].selector.ifEmpty { selector }
        page.evaluateJavascript("var e=document.querySelector('${escapeJsString(sel)}');if(e)e.focus();")
    }

    suspend fun jsCheck() {
        val results = locate()
        if (results.isEmpty()) throw ElementNotFoundException(selector)
        val sel = results[0].selector.ifEmpty { selector }
        page.evaluateJavascript(
            "var e=document.querySelector('${escapeJsString(sel)}');if(e){e.checked=true;e.dispatchEvent(new Event('change',{bubbles:true}));}"
        )
    }

    suspend fun jsSelectOption(value: String) {
        val results = locate()
        if (results.isEmpty()) throw ElementNotFoundException(selector)
        val sel = results[0].selector.ifEmpty { selector }
        page.evaluateJavascript(
            "var e=document.querySelector('${escapeJsString(sel)}');" +
            "if(e&&e.tagName.toLowerCase()==='select'){for(var i=0;i<e.options.length;i++){if(e.options[i].value==='$value'||e.options[i].textContent.trim()==='$value'){e.value=e.options[i].value;e.dispatchEvent(new Event('change',{bubbles:true}));break;}}}"
        )
    }

    suspend fun jsPress(key: KeyboardKey) {
        jsFocus()
        kotlinx.coroutines.delay(40)
        page.press(key)
    }

    suspend fun jsPressKeyCombination(modifier: KeyboardKey, key: KeyboardKey) {
        jsFocus()
        kotlinx.coroutines.delay(40)
        page.pressKeyCombination(modifier, key)
    }

    // ────────────────────────────────────────────────
    // 查询
    // ────────────────────────────────────────────────

    suspend fun isVisible(): Boolean = locate().any { it.isVisible }

    suspend fun getText(): String = locate().firstOrNull()?.text ?: ""

    suspend fun getAttribute(attr: String): String? =
        locate().firstOrNull()?.attributes?.get(attr)

    suspend fun count(): Int = locate().size

    suspend fun boundingBox(): Rect? {
        val r = locate().firstOrNull() ?: return null
        return Rect(r.centerX - r.width / 2, r.centerY - r.height / 2, r.width, r.height)
    }
}
