package xyz.mederi.browser.bidi

object BiDiJsScripts {

    val BUILD_SELECTOR_FN: String = """
        function buildSelector(el) {
            function cssEsc(s) {
                return (window.CSS && CSS.escape) ? CSS.escape(s) : s.replace(/[^a-zA-Z0-9_-]/g, '\\$&');
            }
            if (el.id && el.id.trim()) {
                var esc = '#' + cssEsc(el.id);
                try { if (document.querySelectorAll(esc).length === 1) return esc; } catch(e) {}
            }
            var sa = ['data-testid', 'data-id', 'data-key', 'name', 'aria-label'];
            for (var i = 0; i < sa.length; i++) {
                var v = el.getAttribute(sa[i]);
                if (v && v.trim()) {
                    var s = el.tagName.toLowerCase() + '[' + sa[i] + '="' + v.replace(/"/g, '\\"') + '"]';
                    try { if (document.querySelectorAll(s).length === 1) return s; } catch(e) {}
                }
            }
            var parts = [], cur = el;
            while (cur && cur.nodeType === 1 && cur !== document.documentElement) {
                var tag = cur.tagName.toLowerCase(), par = cur.parentElement;
                if (!par) { parts.unshift(tag); break; }
                var sibs = par.children, st = 0, idx = 0;
                for (var j = 0; j < sibs.length; j++) {
                    if (sibs[j].tagName === cur.tagName) { st++; if (sibs[j] === cur) idx = st; }
                }
                parts.unshift(st > 1 ? tag + ':nth-of-type(' + idx + ')' : tag);
                cur = par;
            }
            return parts.join(' > ');
        }
    """

    private val BUILD_RESULT_JS = """
        $BUILD_SELECTOR_FN
        function buildResult(el) {
            var rect = el.getBoundingClientRect();
            return {
                centerX: Math.round(rect.left + window.scrollX + rect.width / 2),
                centerY: Math.round(rect.top + window.scrollY + rect.height / 2),
                width: Math.round(rect.width),
                height: Math.round(rect.height),
                tagName: el.tagName.toLowerCase(),
                role: el.getAttribute('role') || '',
                text: (function() {
                    var t = '';
                    for (var i = 0; i < el.childNodes.length; i++) {
                        if (el.childNodes[i].nodeType === 3) {
                            var s = el.childNodes[i].textContent.trim();
                            if (s) t += s + ' ';
                        }
                    }
                    return t.trim();
                })(),
                isVisible: rect.width > 0 && rect.height > 0,
                attributes: (function() {
                    var a = {};
                    for (var i = 0; i < el.attributes.length; i++) {
                        var attr = el.attributes[i];
                        if (attr.name !== 'class' && attr.name !== 'id' && attr.name !== 'style') {
                            a[attr.name] = attr.value.substring(0, 100);
                        }
                    }
                    return a;
                })(),
                selector: buildSelector(el)
            };
        }
    """

    private val GET_DIRECT_TEXT_JS = """
        function getDirectText(node) {
            var t = '';
            for (var i = 0; i < node.childNodes.length; i++) {
                if (node.childNodes[i].nodeType === 3) {
                    var s = node.childNodes[i].textContent.trim();
                    if (s) t += s + ' ';
                }
            }
            return t.trim();
        }
    """

    val FIND_ALL_BY_CSS: String = """
        (function() {
            var selector = '__SELECTOR__';
            var results = [];
            var elements = document.querySelectorAll(selector);
            $BUILD_RESULT_JS
            for (var i = 0; i < elements.length; i++) {
                results.push(buildResult(elements[i]));
            }
            return JSON.stringify(results);
        })()
    """.trimIndent()

    val FIND_ALL_BY_XPATH: String = """
        (function() {
            var xpath = '__SELECTOR__';
            var results = [];
            var iterator = document.evaluate(xpath, document, null, XPathResult.ORDERED_NODE_ITERATOR_TYPE, null);
            var node = iterator.iterateNext();
            $BUILD_RESULT_JS
            while (node) {
                if (node.nodeType === 1) {
                    results.push(buildResult(node));
                }
                node = iterator.iterateNext();
            }
            return JSON.stringify(results);
        })()
    """.trimIndent()

    val FIND_ALL_BY_TEXT: String = """
        (function() {
            var text = '__TEXT__';
            var exact = __EXACT__;
            var results = [];
            var all = document.querySelectorAll('*');
            $BUILD_RESULT_JS
            $GET_DIRECT_TEXT_JS
            for (var i = 0; i < all.length; i++) {
                var elText = getDirectText(all[i]);
                if (exact ? elText === text : elText.toLowerCase().includes(text.toLowerCase())) {
                    var rect = all[i].getBoundingClientRect();
                    if (rect.width > 0 && rect.height > 0) {
                        results.push(buildResult(all[i]));
                    }
                }
            }
            return JSON.stringify(results);
        })()
    """.trimIndent()

    val FIND_ALL_BY_ROLE: String = """
        (function() {
            var role = '__ROLE__';
            var name = '__NAME__';
            var results = [];
            $BUILD_RESULT_JS
            $GET_DIRECT_TEXT_JS

            var candidates = Array.from(document.querySelectorAll('[role="' + role + '"]'));

            if (candidates.length === 0) {
                var implicitMap = {
                    'button': ['button', 'input[type="submit"]', 'input[type="button"]', 'input[type="reset"]', 'summary'],
                    'link': ['a[href]'],
                    'textbox': ['input:not([type])', 'input[type="text"]', 'input[type="email"]', 'input[type="password"]', 'input[type="url"]', 'input[type="tel"]', 'textarea'],
                    'combobox': ['select'],
                    'checkbox': ['input[type="checkbox"]'],
                    'radio': ['input[type="radio"]'],
                    'slider': ['input[type="range"]'],
                    'heading': ['h1', 'h2', 'h3', 'h4', 'h5', 'h6'],
                    'img': ['img', 'svg'],
                    'table': ['table'],
                    'navigation': ['nav'],
                    'main': ['main'],
                    'banner': ['header'],
                    'contentinfo': ['footer'],
                    'form': ['form'],
                    'dialog': ['dialog'],
                    'list': ['ul', 'ol'],
                    'listitem': ['li'],
                    'group': ['details', 'fieldset'],
                    'progressbar': ['progress'],
                    'meter': ['meter'],
                    'option': ['option'],
                    'cell': ['td'],
                    'columnheader': ['th'],
                    'row': ['tr']
                };
                var tagSelectors = implicitMap[role] || [];
                for (var j = 0; j < tagSelectors.length; j++) {
                    try {
                        var found = document.querySelectorAll(tagSelectors[j]);
                        if (found.length > 0) {
                            candidates = Array.from(found);
                            break;
                        }
                    } catch(e) {}
                }
            }

            if (name) {
                candidates = candidates.filter(function(el) {
                    return (el.getAttribute('aria-label') || '').toLowerCase().includes(name.toLowerCase()) ||
                           (el.getAttribute('name') || '').toLowerCase().includes(name.toLowerCase()) ||
                           getDirectText(el).toLowerCase().includes(name.toLowerCase());
                });
            }

            for (var i = 0; i < candidates.length; i++) {
                results.push(buildResult(candidates[i]));
            }
            return JSON.stringify(results);
        })()
    """.trimIndent()

    val FIND_ALL_BY_LABEL: String = """
        (function() {
            var label = '__LABEL__';
            var results = [];
            $BUILD_RESULT_JS

            var byAriaLabel = document.querySelectorAll('[aria-label="' + label + '"]');
            for (var i = 0; i < byAriaLabel.length; i++) {
                results.push(buildResult(byAriaLabel[i]));
            }

            var labels = document.querySelectorAll('label');
            for (var i = 0; i < labels.length; i++) {
                if (labels[i].textContent.trim() === label) {
                    var forId = labels[i].getAttribute('for');
                    if (forId) {
                        var input = document.getElementById(forId);
                        if (input) results.push(buildResult(input));
                    } else {
                        var innerInput = labels[i].querySelector('input, select, textarea');
                        if (innerInput) results.push(buildResult(innerInput));
                    }
                }
            }
            return JSON.stringify(results);
        })()
    """.trimIndent()

    val FIND_ALL_BY_PLACEHOLDER: String = """
        (function() {
            var text = '__TEXT__';
            var results = [];
            $BUILD_RESULT_JS
            var exact = document.querySelectorAll('[placeholder="' + text + '"]');
            for (var i = 0; i < exact.length; i++) results.push(buildResult(exact[i]));
            if (results.length === 0) {
                var partial = document.querySelectorAll('[placeholder*="' + text + '"]');
                for (var i = 0; i < partial.length; i++) results.push(buildResult(partial[i]));
            }
            return JSON.stringify(results);
        })()
    """.trimIndent()

    val FIND_ALL_BY_TEST_ID: String = """
        (function() {
            var testId = '__TESTID__';
            var results = [];
            $BUILD_RESULT_JS
            var elements = document.querySelectorAll('[data-testid="' + testId + '"]');
            for (var i = 0; i < elements.length; i++) results.push(buildResult(elements[i]));
            return JSON.stringify(results);
        })()
    """.trimIndent()

    val FIND_ALL_BY_ALT_TEXT: String = """
        (function() {
            var text = '__TEXT__';
            var results = [];
            $BUILD_RESULT_JS
            var elements = document.querySelectorAll('[alt="' + text + '"]');
            for (var i = 0; i < elements.length; i++) results.push(buildResult(elements[i]));
            return JSON.stringify(results);
        })()
    """.trimIndent()

    val FIND_ALL_BY_TITLE: String = """
        (function() {
            var title = '__TITLE__';
            var results = [];
            $BUILD_RESULT_JS
            var elements = document.querySelectorAll('[title="' + title + '"]');
            for (var i = 0; i < elements.length; i++) results.push(buildResult(elements[i]));
            return JSON.stringify(results);
        })()
    """.trimIndent()

    private val FIND_ELEMENT_JS = """
        function findElement(selectorType, selector) {
            var el = null;
            if (selectorType === 'CSS') {
                el = document.querySelector(selector);
            } else if (selectorType === 'XPATH') {
                var result = document.evaluate(selector, document, null, XPathResult.FIRST_ORDERED_TYPE, null);
                el = result.singleNodeValue;
            } else if (selectorType === 'LABEL') {
                el = document.querySelector('[aria-label="' + selector + '"]');
                if (!el) {
                    var labels = document.querySelectorAll('label');
                    for (var i = 0; i < labels.length; i++) {
                        if (labels[i].textContent.trim() === selector) {
                            var forId = labels[i].getAttribute('for');
                            if (forId) el = document.getElementById(forId);
                            if (!el) el = labels[i].querySelector('input, select, textarea');
                            if (el) break;
                        }
                    }
                }
            } else if (selectorType === 'PLACEHOLDER') {
                el = document.querySelector('[placeholder="' + selector + '"]');
                if (!el) el = document.querySelector('[placeholder*="' + selector + '"]');
            } else if (selectorType === 'TEST_ID') {
                el = document.querySelector('[data-testid="' + selector + '"]');
            } else if (selectorType === 'ROLE') {
                el = document.querySelector('[role="' + selector + '"]');
            } else if (selectorType === 'TEXT') {
                var all = document.querySelectorAll('*');
                for (var i = 0; i < all.length; i++) {
                    var t = '';
                    for (var j = 0; j < all[i].childNodes.length; j++) {
                        if (all[i].childNodes[j].nodeType === 3) {
                            var s = all[i].childNodes[j].textContent.trim();
                            if (s) t += s + ' ';
                        }
                    }
                    var directText = t.trim();
                    if (directText === selector || directText.toLowerCase().includes(selector.toLowerCase())) {
                        el = all[i];
                        break;
                    }
                }
            } else if (selectorType === 'ALT_TEXT') {
                el = document.querySelector('[alt="' + selector + '"]');
            } else if (selectorType === 'TITLE') {
                el = document.querySelector('[title="' + selector + '"]');
            }
            return el;
        }
    """

    val SET_VALUE_NATIVE: String = """
        (function() {
            var selectorType = '__SELECTOR_TYPE__';
            var selector = '__SELECTOR__';
            var value = '__VALUE__';

            $FIND_ELEMENT_JS
            var el = findElement(selectorType, selector);
            if (!el) return 'null';

            var nativeSetter = Object.getOwnPropertyDescriptor(
                window.HTMLInputElement.prototype, 'value'
            ) && Object.getOwnPropertyDescriptor(
                window.HTMLInputElement.prototype, 'value'
            ).set || Object.getOwnPropertyDescriptor(
                window.HTMLTextAreaElement.prototype, 'value'
            ) && Object.getOwnPropertyDescriptor(
                window.HTMLTextAreaElement.prototype, 'value'
            ).set;

            if (nativeSetter) {
                nativeSetter.call(el, value);
            } else {
                el.value = value;
            }

            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));

            return 'ok';
        })()
    """.trimIndent()

    val FIND_OPTION_AND_CLICK: String = """
        (function() {
            $BUILD_SELECTOR_FN
            var selector = '__SELECTOR__';
            var value = '__VALUE__';

            var selectEl = document.querySelector(selector);
            if (!selectEl || selectEl.tagName.toLowerCase() !== 'select') return 'null';

            var options = selectEl.options;
            for (var i = 0; i < options.length; i++) {
                if (options[i].value === value || options[i].textContent.trim() === value) {
                    selectEl.value = options[i].value;
                    selectEl.dispatchEvent(new Event('change', { bubbles: true }));

                    var rect = options[i].getBoundingClientRect();
                    return JSON.stringify({
                        centerX: Math.round(rect.left + window.scrollX + rect.width / 2),
                        centerY: Math.round(rect.top + window.scrollY + rect.height / 2),
                        width: Math.round(rect.width),
                        height: Math.round(rect.height),
                        tagName: 'option',
                        role: 'option',
                        text: options[i].textContent.trim(),
                        isVisible: rect.width > 0 && rect.height > 0,
                        selector: buildSelector(options[i])
                    });
                }
            }
            return 'null';
        })()
    """.trimIndent()

    val EXTRACT_SNAPSHOT: String = """
        (function() {
            if (!window.__kb_element_map) {
                window.__kb_element_map = new Map();
            }
            if (!window.__kb_ref_counter) {
                window.__kb_ref_counter = 0;
            }

            var elements = [];
            var allNodes = document.querySelectorAll('*');

            $GET_DIRECT_TEXT_JS

            $BUILD_SELECTOR_FN

            function inferRole(el, attrs) {
                var role = attrs['role'] || '';
                if (role) return role;

                var tag = el.tagName.toLowerCase();
                if (tag === 'button') return 'button';
                if (tag === 'a') return 'link';
                if (tag === 'input') {
                    var inputType = (el.type || 'text').toLowerCase();
                    if (inputType === 'checkbox') return 'checkbox';
                    if (inputType === 'radio') return 'radio';
                    if (inputType === 'submit' || inputType === 'button') return 'button';
                    if (inputType === 'range') return 'slider';
                    return 'textbox';
                }
                if (tag === 'select') return 'combobox';
                if (tag === 'textarea') return 'textbox';
                if (tag === 'option') return 'option';
                if (tag === 'img') return 'img';
                if (tag === 'table') return 'table';
                if (tag === 'td') return 'cell';
                if (tag === 'th') return 'columnheader';
                if (tag === 'tr') return 'row';
                if (tag === 'h1' || tag === 'h2' || tag === 'h3' || tag === 'h4' || tag === 'h5' || tag === 'h6') return 'heading';
                if (tag === 'nav') return 'navigation';
                if (tag === 'main') return 'main';
                if (tag === 'header') return 'banner';
                if (tag === 'footer') return 'contentinfo';
                if (tag === 'form') return 'form';
                if (tag === 'dialog') return 'dialog';
                if (tag === 'ul' || tag === 'ol') return 'list';
                if (tag === 'li') return 'listitem';
                if (tag === 'details') return 'group';
                if (tag === 'summary') return 'button';
                if (tag === 'progress') return 'progressbar';
                if (tag === 'meter') return 'meter';

                return '';
            }

            var overlayEl = document.getElementById('__kb_overlay__');
            allNodes.forEach(function(el) {
                if (overlayEl && (el === overlayEl || overlayEl.contains(el))) return;
                if (!el.__kb_refid) {
                    var refid = 'r' + (window.__kb_ref_counter++);
                    el.__kb_refid = refid;
                    window.__kb_element_map.set(refid, el);
                }
            });

            allNodes.forEach(function(el) {
                if (overlayEl && (el === overlayEl || overlayEl.contains(el))) return;
                var refid = el.__kb_refid;
                var rect = el.getBoundingClientRect();
                var isVisible = rect.width > 0 && rect.height > 0;

                var textContent = '';
                try { textContent = getDirectText(el); } catch(e) {}
                if (textContent.length > 200) textContent = textContent.substring(0, 200);
                if (!textContent && el.title) textContent = el.title;
                if (!textContent && el.getAttribute('aria-label')) textContent = el.getAttribute('aria-label');
                if (!textContent && el.tagName === 'IMG' && el.alt) textContent = el.alt;
                if (!textContent) {
                    try {
                        var inner = (el.innerText || '').trim();
                        if (inner.length > 0 && inner.length <= 50) textContent = inner;
                    } catch(e) {}
                }

                var attrs = {};
                for (var i = 0; i < el.attributes.length; i++) {
                    var attr = el.attributes[i];
                    if (attr.name !== 'class' && attr.name !== 'id' && attr.name !== 'style') {
                        attrs[attr.name] = attr.value.substring(0, 100);
                    }
                }

                var role = inferRole(el, attrs);

                var clientX = rect.left + rect.width / 2;
                var clientY = rect.top + rect.height / 2;
                var occludedBy = null;
                var tag = el.tagName.toLowerCase();
                var isInteractive = (tag === 'a' || tag === 'button' || tag === 'input' ||
                    tag === 'select' || tag === 'textarea' || tag === 'label' ||
                    role === 'button' || role === 'link' || role === 'checkbox' ||
                    role === 'radio' || role === 'textbox' || role === 'combobox' ||
                    role === 'menuitem' || role === 'tab' || role === 'option' ||
                    attrs['onclick'] !== undefined || attrs['tabindex'] !== undefined);
                if (isVisible && isInteractive) {
                    try {
                        var topEl = document.elementFromPoint(clientX, clientY);
                        if (topEl && topEl !== el && !el.contains(topEl)) {
                            occludedBy = topEl.__kb_refid || null;
                        }
                    } catch(e) {}
                }

                var childRefids = [];
                for (var ci = 0; ci < el.children.length; ci++) {
                    var childRefid = el.children[ci].__kb_refid;
                    if (childRefid) childRefids.push(childRefid);
                }

                elements.push({
                    refid: refid,
                    tagName: el.tagName.toLowerCase(),
                    role: role,
                    id: el.id || '',
                    className: (el.className && typeof el.className === 'string') ? el.className : '',
                    text: textContent,
                    isVisible: isVisible,
                    occludedBy: occludedBy,
                    x: Math.round(rect.left + window.scrollX),
                    y: Math.round(rect.top + window.scrollY),
                    width: Math.round(rect.width),
                    height: Math.round(rect.height),
                    centerX: Math.round(rect.left + window.scrollX + rect.width / 2),
                    centerY: Math.round(rect.top + window.scrollY + rect.height / 2),
                    childCount: el.children.length,
                    attributes: attrs,
                    selector: buildSelector(el),
                    nodeId: refid,
                    childIds: childRefids
                });
            });

            var diag = {
                url: window.location.href,
                innerWidth: window.innerWidth,
                innerHeight: window.innerHeight,
                scrollX: window.scrollX,
                scrollY: window.scrollY,
                documentWidth: document.documentElement.scrollWidth,
                documentHeight: document.documentElement.scrollHeight,
                devicePixelRatio: window.devicePixelRatio || 1.0,
                totalElements: elements.length,
                visibleElements: elements.filter(function(e) { return e.isVisible; }).length,
                hiddenElements: elements.filter(function(e) { return !e.isVisible; }).length,
                iframeCount: document.querySelectorAll('iframe').length
            };

            return JSON.stringify({
                url: diag.url,
                innerWidth: diag.innerWidth,
                innerHeight: diag.innerHeight,
                scrollX: diag.scrollX,
                scrollY: diag.scrollY,
                documentWidth: diag.documentWidth,
                documentHeight: diag.documentHeight,
                devicePixelRatio: diag.devicePixelRatio,
                totalElements: diag.totalElements,
                visibleElements: diag.visibleElements,
                hiddenElements: diag.hiddenElements,
                iframeCount: diag.iframeCount,
                nodes: elements
            });
        })()
    """.trimIndent()
}
