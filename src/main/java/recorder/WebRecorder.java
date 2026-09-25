package recorder;

import com.microsoft.playwright.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebRecorder — opens a headed browser, injects a JS event recorder into every page,
 * collects actions via page.exposeFunction, and provides an interactive CLI.
 *
 * Commands while recording:
 *   stop / exit / done  → export GeneratedWebTest.java and quit
 *   undo                → remove the last recorded action
 *   list                → print all recorded actions so far
 *   comment <text>      → insert a comment step
 */
public class WebRecorder {

    // ─────────────────────────── JS Recorder Script ────────────────────────
    /**
     * This script is injected into EVERY page/frame via page.addInitScript().
     * It captures clicks, input changes, select changes, checkbox changes,
     * and calls window.__recordAction(JSON) to send data back to Java.
     * (Written as string concatenation to remain compatible with Java 11.)
     */
    private static final String JS_RECORDER = buildJsRecorder();

    private static String buildJsRecorder() {
        return "(function() {\n" +
            "  var inputTimers = new WeakMap();\n" +
            "\n" +
            "  function cssPath(el) {\n" +
            "    if (!el || el === document.body) return 'body';\n" +
            "    var parts = [];\n" +
            "    while (el && el.nodeType === Node.ELEMENT_NODE) {\n" +
            "      var selector = el.nodeName.toLowerCase();\n" +
            "      if (el.id && !/^\\d/.test(el.id) && !/:/.test(el.id)) {\n" +
            "        selector += '#' + el.id;\n" +
            "        parts.unshift(selector);\n" +
            "        break;\n" +
            "      } else {\n" +
            "        var sib = el, nth = 1;\n" +
            "        while ((sib = sib.previousElementSibling)) {\n" +
            "          if (sib.nodeName === el.nodeName) nth++;\n" +
            "        }\n" +
            "        if (nth > 1) selector += ':nth-of-type(' + nth + ')';\n" +
            "      }\n" +
            "      parts.unshift(selector);\n" +
            "      el = el.parentElement;\n" +
            "    }\n" +
            "    return parts.join(' > ');\n" +
            "  }\n" +
            "\n" +
            "  var SKIP_CLASSES = new Set([\n" +
            "    'active','btn','button','input','selected','disabled','error',\n" +
            "    'hidden','show','hide','flex','block','container','row','col',\n" +
            "    'form','field','control','wrapper','inner','outer','content','item',\n" +
            "    'inline-flex','inline-block','grid','relative','absolute','fixed',\n" +
            "    'items-center','justify-center','justify-between','cursor-pointer',\n" +
            "    'w-full','h-full','rounded','shadow','border','truncate','uppercase'\n" +
            "  ]);\n" +
            "\n" +
            "  function bestClass(el) {\n" +
            "    if (!el.className || typeof el.className !== 'string') return '';\n" +
            "    var classes = el.className.trim().split(/\\s+/);\n" +
            "    for (var i = 0; i < classes.length; i++) {\n" +
            "      var c = classes[i];\n" +
            "      if (c.length > 3 && !SKIP_CLASSES.has(c.toLowerCase()) && !/\\d{4,}/.test(c)) {\n" +
            "        return c;\n" +
            "      }\n" +
            "    }\n" +
            "    return '';\n" +
            "  }\n" +
            "\n" +
            "  function implicitRole(tag, inputType) {\n" +
            "    if (tag === 'button') return 'button';\n" +
            "    if (tag === 'a') return 'link';\n" +
            "    if (tag === 'select') return 'combobox';\n" +
            "    if (tag === 'input') {\n" +
            "      if (inputType === 'checkbox' || inputType === 'radio') return '';\n" +
            "      return 'textbox';\n" +
            "    }\n" +
            "    return '';\n" +
            "  }\n" +
            "\n" +
            "  function findLabelText(el) {\n" +
            "    if (!el) return '';\n" +
            "    try {\n" +
            "      if (el.id) {\n" +
            "        var label = document.querySelector('label[for=\"' + el.id + '\"]');\n" +
            "        if (label && label.innerText) return label.innerText.replace(/[*:]/g, '').trim();\n" +
            "      }\n" +
            "      var parentLabel = el.closest('label');\n" +
            "      if (parentLabel && parentLabel.innerText) {\n" +
            "        return parentLabel.innerText.split('\\n')[0].replace(/[*:]/g, '').trim();\n" +
            "      }\n" +
            "      var current = el;\n" +
            "      var depth = 0;\n" +
            "      while (current && current !== document.body && depth < 3) {\n" +
            "        var sibling = current.previousElementSibling;\n" +
            "        while (sibling) {\n" +
            "          if (sibling.tagName === 'LABEL') {\n" +
            "            return sibling.innerText.split('\\n')[0].replace(/[*:]/g, '').trim();\n" +
            "          }\n" +
            "          var childLabel = sibling.querySelector('label');\n" +
            "          if (childLabel && childLabel.innerText) {\n" +
            "            return childLabel.innerText.split('\\n')[0].replace(/[*:]/g, '').trim();\n" +
            "          }\n" +
            "          var text = (sibling.innerText || sibling.textContent || '').trim();\n" +
            "          if (text && text.length > 0 && text.length < 40) {\n" +
            "            return text.split('\\n')[0].replace(/[*:]/g, '').trim();\n" +
            "          }\n" +
            "          sibling = sibling.previousElementSibling;\n" +
            "        }\n" +
            "        current = current.parentElement;\n" +
            "        depth++;\n" +
            "      }\n" +
            "    } catch(e) {}\n" +
            "    return '';\n" +
            "  }\n" +
            "\n" +
            "  function describe(el) {\n" +
            "    if (!el) return {};\n" +
            "    var tag = el.tagName ? el.tagName.toLowerCase() : '';\n" +
            "    var inputType = (el.getAttribute('type') || '').toLowerCase();\n" +
            "    var role = el.getAttribute('role') || implicitRole(tag, inputType);\n" +
            "    try {\n" +
            "      document.querySelectorAll('[data-recorder-temp]').forEach(function(x) { x.removeAttribute('data-recorder-temp'); });\n" +
            "      el.setAttribute('data-recorder-temp', 'true');\n" +
            "    } catch(e) {}\n" +
            "    return {\n" +
            "      tag:         tag,\n" +
            "      id:          el.id || '',\n" +
            "      name:        el.getAttribute('name') || '',\n" +
            "      testid:      el.getAttribute('data-testid') || el.getAttribute('data-test') || el.getAttribute('data-cy') || '',\n" +
            "      ariaLabel:   el.getAttribute('aria-label') || el.getAttribute('aria-labelledby') || '',\n" +
            "      role:        role,\n" +
            "      placeholder: el.getAttribute('placeholder') || '',\n" +
            "      type:        el.getAttribute('type') || '',\n" +
            "      text:        (el.innerText || el.textContent || '').trim().substring(0, 80),\n" +
            "      cssClass:    bestClass(el),\n" +
            "      cssPath:     cssPath(el),\n" +
            "      labelText:   findLabelText(el)\n" +
            "    };\n" +
            "  }\n" +
            "\n" +
            "  function isInteractive(el) {\n" +
            "    if (!el) return false;\n" +
            "    var tag = el.tagName ? el.tagName.toLowerCase() : '';\n" +
            "    if (['a','button','select','input','textarea','label'].indexOf(tag) !== -1) return true;\n" +
            "    var role = el.getAttribute('role') || '';\n" +
            "    if (['button','link','menuitem','tab','checkbox','radio','option','combobox'].indexOf(role) !== -1) return true;\n" +
            "    if (el.getAttribute('ng-click') || el.getAttribute('@click') || el.getAttribute('onclick')) return true;\n" +
            "    return false;\n" +
            "  }\n" +
            "\n" +
            "  // Returns true if el carries stable, meaningful locator attributes.\n" +
            "  function hasMeaningfulAttrs(el) {\n" +
            "    if (!el) return false;\n" +
            "    var id = el.id || '';\n" +
            "    // id must not be auto-generated (React: _r_23_, :r0:, mui-12345)\n" +
            "    if (id && !/^_/.test(id) && !/^[:\\d]/.test(id) && !/\\d{3,}/.test(id)) return true;\n" +
            "    if (el.getAttribute('name')) return true;\n" +
            "    if (el.getAttribute('placeholder')) return true;\n" +
            "    if (el.getAttribute('aria-label')) return true;\n" +
            "    if (el.getAttribute('data-testid') || el.getAttribute('data-test') || el.getAttribute('data-cy')) return true;\n" +
            "    var tag = el.tagName ? el.tagName.toLowerCase() : '';\n" +
            "    var text = (el.innerText || el.textContent || '').trim();\n" +
            "    if (['button','a'].indexOf(tag) !== -1 && text.length > 0 && text.length <= 60) return true;\n" +
            "    return false;\n" +
            "  }\n" +
            "\n" +
            "  // Structural/icon-only elements whose click should bubble to a meaningful parent.\n" +
            "  var STRUCTURAL = {'svg':1,'path':1,'circle':1,'rect':1,'polyline':1,'line':1,\n" +
            "    'g':1,'use':1,'symbol':1,'img':1,'i':1,'strong':1,'em':1,'br':1,'hr':1,'figure':1};\n" +
            "\n" +
            "  function findTarget(el) {\n" +
            "    if (!el) return el;\n" +
            "    // If the clicked element itself has stable attributes, use it directly\n" +
            "    if (hasMeaningfulAttrs(el)) return el;\n" +
            "    // Walk up — but only 3 levels — looking for a meaningful interactive parent\n" +
            "    var current = el.parentElement;\n" +
            "    var depth = 0;\n" +
            "    while (current && current !== document.body && depth < 3) {\n" +
            "      if (hasMeaningfulAttrs(current) || isInteractive(current)) return current;\n" +
            "      current = current.parentElement;\n" +
            "      depth++;\n" +
            "    }\n" +
            "    // Fall back to original target\n" +
            "    return el;\n" +
            "  }\n" +
            "\n" +
            "  document.addEventListener('click', function(e) {\n" +
            "    if (e.target && (e.target.id === '__recorder_toolbar__' || (e.target.closest && e.target.closest('#__recorder_toolbar__')))) return;\n" +
            "    var target = findTarget(e.target);\n" +
            "    var desc = describe(target);\n" +
            "    var tag = desc.tag;\n" +
            "    var type = desc.type;\n" +
            "    if (tag === 'select') return;\n" +
            "    if (tag === 'input' && (type === 'checkbox' || type === 'radio')) return;\n" +
            "    desc.actionType = 'CLICK';\n" +
            "    if (typeof window.__recordAction === 'function') {\n" +
            "      window.__recordAction(JSON.stringify(desc));\n" +
            "    }\n" +
            "  }, true);\n" +
            "\n" +
            "  document.addEventListener('input', function(e) {\n" +
            "    var el = e.target;\n" +
            "    var tag = el.tagName ? el.tagName.toLowerCase() : '';\n" +
            "    if (tag !== 'input' && tag !== 'textarea') return;\n" +
            "    var inputType = (el.getAttribute('type') || '').toLowerCase();\n" +
            "    if (inputType === 'checkbox' || inputType === 'radio' || inputType === 'submit' || inputType === 'button') return;\n" +
            "    if (inputTimers.has(el)) clearTimeout(inputTimers.get(el));\n" +
            "    var timer = setTimeout(function() {\n" +
            "      var desc = describe(el);\n" +
            "      desc.actionType = 'INPUT';\n" +
            "      desc.value = el.value;\n" +
            "      if (typeof window.__recordAction === 'function') {\n" +
            "        window.__recordAction(JSON.stringify(desc));\n" +
            "      }\n" +
            "    }, 600);\n" +
            "    inputTimers.set(el, timer);\n" +
            "  }, true);\n" +
            "\n" +
            "  document.addEventListener('change', function(e) {\n" +
            "    var el = e.target;\n" +
            "    var tag = el.tagName ? el.tagName.toLowerCase() : '';\n" +
            "    if (tag === 'select') {\n" +
            "      var desc = describe(el);\n" +
            "      desc.actionType = 'SELECT';\n" +
            "      desc.value = el.value;\n" +
            "      if (typeof window.__recordAction === 'function') {\n" +
            "        window.__recordAction(JSON.stringify(desc));\n" +
            "      }\n" +
            "      return;\n" +
            "    }\n" +
            "    if (tag === 'input') {\n" +
            "      var itype = (el.getAttribute('type') || '').toLowerCase();\n" +
            "      if (itype === 'checkbox') {\n" +
            "        var desc2 = describe(el);\n" +
            "        desc2.actionType = el.checked ? 'CHECK' : 'UNCHECK';\n" +
            "        if (typeof window.__recordAction === 'function') {\n" +
            "          window.__recordAction(JSON.stringify(desc2));\n" +
            "        }\n" +
            "        return;\n" +
            "      }\n" +
            "    }\n" +
            "  }, true);\n" +
            "\n" +
            "  document.addEventListener('keydown', function(e) {\n" +
            "    if (e.key !== 'Enter') return;\n" +
            "    var el = e.target;\n" +
            "    var tag = el.tagName ? el.tagName.toLowerCase() : '';\n" +
            "    if (tag !== 'input' && tag !== 'textarea') return;\n" +
            "    var desc = describe(el);\n" +
            "    desc.actionType = 'PRESS';\n" +
            "    desc.value = 'Enter';\n" +
            "    if (typeof window.__recordAction === 'function') {\n" +
            "      window.__recordAction(JSON.stringify(desc));\n" +
            "    }\n" +
            "  }, true);\n" +
            "\n" +
            "  // ── Hover: fire after 1500ms (1.5s) on interactive element, cancel on click ──\n" +
            "  // mouseover/mouseout are used (not mouseenter/mouseleave) because\n" +
            "  // mouseenter does NOT bubble — document-level listener would never fire.\n" +
            "  var hoverTimer = null;\n" +
            "  var hoverTarget = null;\n" +
            "\n" +
            "  document.addEventListener('mouseover', function(e) {\n" +
            "    var target = findTarget(e.target);\n" +
            "    if (!target || target === document.body) return;\n" +
            "    var tag = target.tagName ? target.tagName.toLowerCase() : '';\n" +
            "    if (!isInteractive(target)) return;\n" +
            "    if (tag === 'input' || tag === 'textarea') return;\n" +
            "    if (target === hoverTarget) return;\n" +
            "    if (hoverTimer) { clearTimeout(hoverTimer); hoverTimer = null; }\n" +
            "    hoverTarget = target;\n" +
            "    hoverTimer = setTimeout(function() {\n" +
            "      hoverTimer = null;\n" +
            "      var desc = describe(hoverTarget);\n" +
            "      desc.actionType = 'HOVER';\n" +
            "      if (typeof window.__recordAction === 'function') {\n" +
            "        window.__recordAction(JSON.stringify(desc));\n" +
            "      }\n" +
            "    }, 1500);\n" +
            "  }, true);\n" +
            "\n" +
            "  document.addEventListener('mouseout', function(e) {\n" +
            "    if (hoverTarget && (e.target === hoverTarget || hoverTarget.contains(e.target))) {\n" +
            "      if (hoverTimer) { clearTimeout(hoverTimer); hoverTimer = null; }\n" +
            "      hoverTarget = null;\n" +
            "    }\n" +
            "  }, true);\n" +
            "\n" +
            "  document.addEventListener('click', function(e) {\n" +
            "    if (hoverTimer) { clearTimeout(hoverTimer); hoverTimer = null; }\n" +
            "    hoverTarget = null;\n" +
            "  }, true);\n" +
            "\n" +
            "})();\n";
    }

    private static final String JS_INSPECTOR = buildJsInspector();

    private static String buildJsInspector() {
        return "(function() {\n" +
            "  var tooltip = null;\n" +
            "\n" +
            "  function cssPath(el) {\n" +
            "    if (!el || el === document.body) return 'body';\n" +
            "    var parts = [];\n" +
            "    while (el && el.nodeType === Node.ELEMENT_NODE) {\n" +
            "      var selector = el.nodeName.toLowerCase();\n" +
            "      if (el.id && !/^\\d/.test(el.id) && !/:/.test(el.id)) {\n" +
            "        selector += '#' + el.id;\n" +
            "        parts.unshift(selector);\n" +
            "        break;\n" +
            "      } else {\n" +
            "        var sib = el, nth = 1;\n" +
            "        while ((sib = sib.previousElementSibling)) {\n" +
            "          if (sib.nodeName === el.nodeName) nth++;\n" +
            "        }\n" +
            "        if (nth > 1) selector += ':nth-of-type(' + nth + ')';\n" +
            "      }\n" +
            "      parts.unshift(selector);\n" +
            "      el = el.parentElement;\n" +
            "    }\n" +
            "    return parts.join(' > ');\n" +
            "  }\n" +
            "\n" +
            "  function findLabelText(el) {\n" +
            "    if (!el) return '';\n" +
            "    try {\n" +
            "      if (el.id) {\n" +
            "        var label = document.querySelector('label[for=\"' + el.id + '\"]');\n" +
            "        if (label && label.innerText) return label.innerText.replace(/[*:]/g, '').trim();\n" +
            "      }\n" +
            "      var parentLabel = el.closest('label');\n" +
            "      if (parentLabel && parentLabel.innerText) {\n" +
            "        return parentLabel.innerText.split('\\n')[0].replace(/[*:]/g, '').trim();\n" +
            "      }\n" +
            "      var current = el.parentElement;\n" +
            "      var depth = 0;\n" +
            "      while (current && current !== document.body && depth < 4) {\n" +
            "        var labelEl = current.querySelector('label');\n" +
            "        if (labelEl && labelEl.innerText) {\n" +
            "          return labelEl.innerText.split('\\n')[0].replace(/[*:]/g, '').trim();\n" +
            "        }\n" +
            "        var children = current.querySelectorAll('div, span, p, label');\n" +
            "        for (var i = 0; i < children.length; i++) {\n" +
            "          var child = children[i];\n" +
            "          if (child === el || child.contains(el)) continue;\n" +
            "          var text = child.innerText || child.textContent || '';\n" +
            "          text = text.trim();\n" +
            "          if (text && text.length > 0 && text.length < 40) {\n" +
            "            var comparison = child.compareDocumentPosition(el);\n" +
            "            if (comparison & Node.DOCUMENT_POSITION_FOLLOWING) {\n" +
            "              return text.split('\\n')[0].replace(/[*:]/g, '').trim();\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "        current = current.parentElement;\n" +
            "        depth++;\n" +
            "      }\n" +
            "    } catch(e) {}\n" +
            "    return '';\n" +
            "  }\n" +
            "\n" +
            "  function countMatches(selectorStr) {\n" +
            "    try {\n" +
            "      if (selectorStr.startsWith('locator(\"xpath=')) {\n" +
            "        var xp = selectorStr.substring('locator(\"xpath='.length, selectorStr.length - 2);\n" +
            "        var res = document.evaluate(xp, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);\n" +
            "        return res.snapshotLength;\n" +
            "      } else if (selectorStr.startsWith('locator(\"')) {\n" +
            "        var sel = selectorStr.substring('locator(\"'.length, selectorStr.length - 2);\n" +
            "        return document.querySelectorAll(sel).length;\n" +
            "      } else if (selectorStr.startsWith('getByPlaceholder(\"')) {\n" +
            "        var ph = selectorStr.substring('getByPlaceholder(\"'.length, selectorStr.length - 2);\n" +
            "        return document.querySelectorAll('[placeholder=\"' + ph + '\"]').length;\n" +
            "      } else if (selectorStr.startsWith('getByTestId(\"')) {\n" +
            "        var tid = selectorStr.substring('getByTestId(\"'.length, selectorStr.length - 2);\n" +
            "        return document.querySelectorAll('[data-testid=\"' + tid + '\"]').length;\n" +
            "      } else if (selectorStr.startsWith('getByLabel(\"')) {\n" +
            "        var label = selectorStr.substring('getByLabel(\"'.length, selectorStr.length - 2);\n" +
            "        return document.querySelectorAll('[aria-label=\"' + label + '\"]').length;\n" +
            "      }\n" +
            "      return 0;\n" +
            "    } catch(e) { return 0; }\n" +
            "  }\n" +
            "\n" +
            "  function getLocatorCandidates(el) {\n" +
            "    var candidates = [];\n" +
            "    if (!el || el === document.body || el === document.documentElement) return candidates;\n" +
            "\n" +
            "    var tag = el.tagName ? el.tagName.toLowerCase() : '*';\n" +
            "    var text = (el.innerText || el.textContent || '').trim().substring(0, 60);\n" +
            "    var id = el.id || '';\n" +
            "    var name = el.getAttribute('name') || '';\n" +
            "    var ph = el.getAttribute('placeholder') || '';\n" +
            "    var testid = el.getAttribute('data-testid') || el.getAttribute('data-test') || el.getAttribute('data-cy') || '';\n" +
            "    var ariaLabel = el.getAttribute('aria-label') || '';\n" +
            "    var role = el.getAttribute('role') || '';\n" +
            "\n" +
            "    // 1. Text-based XPath\n" +
            "    if (text) {\n" +
            "      candidates.push('locator(\"xpath=//' + tag + '[normalize-space()=\\'' + text.replace(/'/g, \"',\\\\\\\"'\\\\\\\",'\") + '\\']\")');\n" +
            "    }\n" +
            "\n" +
            "    // 2. ID, Name, Placeholder, TestID, AriaLabel\n" +
            "    if (id && !/^\\d/.test(id) && !/^_/.test(id) && !/^:/.test(id) && !/\\d{3,}/.test(id)) {\n" +
            "      candidates.push('locator(\"#' + id + '\")');\n" +
            "    }\n" +
            "    if (name) {\n" +
            "      candidates.push('locator(\"[name=\\\"' + name + '\\\"]\")');\n" +
            "    }\n" +
            "    if (ph) {\n" +
            "      candidates.push('getByPlaceholder(\"' + ph + '\")');\n" +
            "    }\n" +
            "    if (testid) {\n" +
            "      candidates.push('getByTestId(\"' + testid + '\")');\n" +
            "    }\n" +
            "    if (ariaLabel) {\n" +
            "      candidates.push('getByLabel(\"' + ariaLabel + '\")');\n" +
            "    }\n" +
            "\n" +
            "    // 3. Smart label parent XPath\n" +
            "    var labelText = findLabelText(el);\n" +
            "    if (labelText) {\n" +
            "      var escLabel = labelText.replace(/'/g, \"',\\\\\\\"'\\\\\\\",'\");\n" +
            "      if (role) {\n" +
            "        candidates.push('locator(\"xpath=//div[.//label[contains(normalize-space(), \\'' + escLabel + '\\')]]//' + tag + '[@role=\\'' + role + '\\']\")');\n" +
            "      }\n" +
            "      candidates.push('locator(\"xpath=//div[.//label[contains(normalize-space(), \\'' + escLabel + '\\')]]//' + tag + '\")');\n" +
            "    }\n" +
            "\n" +
            "    // 4. Role + Text (e.g. role option)\n" +
            "    if (role && text) {\n" +
            "      candidates.push('locator(\"xpath=//' + tag + '[@role=\\'' + role + '\\' and normalize-space()=\\'' + text.replace(/'/g, \"',\\\\\\\"'\\\\\\\",'\") + '\\']\")');\n" +
            "    }\n" +
            "\n" +
            "    // 5. CSS Path fallback\n" +
            "    candidates.push('locator(\"' + cssPath(el) + '\")');\n" +
            "\n" +
            "    return candidates;\n" +
            "  }\n" +
            "\n" +
            "  function findTarget(el) {\n" +
            "    if (!el) return el;\n" +
            "    var id = el.id || '';\n" +
            "    if (id && !/^\\d/.test(id) && !/:/.test(id) && !/\\d{3,}/.test(id)) return el;\n" +
            "    if (el.getAttribute('name') || el.getAttribute('placeholder') || el.getAttribute('aria-label')) return el;\n" +
            "    if (el.getAttribute('data-testid') || el.getAttribute('data-test') || el.getAttribute('data-cy')) return el;\n" +
            "    var tag = el.tagName ? el.tagName.toLowerCase() : '';\n" +
            "    if (['button','a'].indexOf(tag) !== -1) return el;\n" +
            "\n" +
            "    var current = el.parentElement;\n" +
            "    var depth = 0;\n" +
            "    while (current && current !== document.body && depth < 3) {\n" +
            "      var curTag = current.tagName ? current.tagName.toLowerCase() : '';\n" +
            "      var role = current.getAttribute('role') || '';\n" +
            "      if (['button','link','menuitem','tab','checkbox','radio','option','combobox'].indexOf(role) !== -1) return current;\n" +
            "      if (['a','button','select','input','textarea','label'].indexOf(curTag) !== -1) return current;\n" +
            "      current = current.parentElement;\n" +
            "      depth++;\n" +
            "    }\n" +
            "    return el;\n" +
            "  }\n" +
            "\n" +
            "  function getLocator(el) {\n" +
            "    var target = findTarget(el);\n" +
            "    var candidates = getLocatorCandidates(target);\n" +
            "    for (var i = 0; i < candidates.length; i++) {\n" +
            "      if (countMatches(candidates[i]) === 1) {\n" +
            "        return candidates[i];\n" +
            "      }\n" +
            "    }\n" +
            "    return candidates[0] || '';\n" +
            "  }\n" +
            "\n" +
            "  function ensureTooltip() {\n" +
            "    if (tooltip) return;\n" +
            "    tooltip = document.createElement('div');\n" +
            "    tooltip.id = '__recorder_tooltip__';\n" +
            "    Object.assign(tooltip.style, {\n" +
            "      position: 'fixed', zIndex: '2147483647', pointerEvents: 'none',\n" +
            "      background: '#1e293b', color: '#38bdf8', fontFamily: 'monospace',\n" +
            "      fontSize: '12px', padding: '4px 8px', borderRadius: '4px',\n" +
            "      maxWidth: '480px', wordBreak: 'break-all', display: 'none',\n" +
            "      border: '1px solid #38bdf8', lineHeight: '1.5'\n" +
            "    });\n" +
            "    document.body.appendChild(tooltip);\n" +
            "  }\n" +
            "\n" +
            "  document.addEventListener('mousemove', function(e) {\n" +
            "    ensureTooltip();\n" +
            "    var el = document.elementFromPoint(e.clientX, e.clientY);\n" +
            "    if (!el || el === tooltip || el.id === '__recorder_toolbar__' || (el.closest && el.closest('#__recorder_toolbar__'))) { tooltip.style.display = 'none'; return; }\n" +
            "    var loc = getLocator(el);\n" +
            "    if (!loc) { tooltip.style.display = 'none'; return; }\n" +
            "    tooltip.textContent = 'page.' + loc;\n" +
            "    tooltip.style.display = 'block';\n" +
            "    var x = e.clientX + 14, y = e.clientY + 14;\n" +
            "    if (x + 490 > window.innerWidth)  x = e.clientX - 14 - Math.min(490, tooltip.offsetWidth);\n" +
            "    if (y + 50  > window.innerHeight) y = e.clientY - 30;\n" +
            "    tooltip.style.left = x + 'px';\n" +
            "    tooltip.style.top  = y + 'px';\n" +
            "  }, true);\n" +
            "\n" +
            "  function ensureToolbar() {\n" +
            "    if (document.getElementById('__recorder_toolbar__') || !document.body) return;\n" +
            "    var isPaused = window.__recorderStartPaused;\n" +
            "    var bar = document.createElement('div');\n" +
            "    bar.id = '__recorder_toolbar__';\n" +
            "    Object.assign(bar.style, {\n" +
            "      position: 'fixed', top: '10px', right: '10px', zIndex: '2147483647',\n" +
            "      display: 'flex', alignItems: 'center', gap: '8px',\n" +
            "      background: '#0f172a', color: '#f8fafc', padding: '6px 14px',\n" +
            "      borderRadius: '20px', boxShadow: '0 4px 16px rgba(0,0,0,0.4)',\n" +
            "      fontFamily: 'system-ui, -apple-system, sans-serif', fontSize: '13px', fontWeight: '600',\n" +
            "      userSelect: 'none'\n" +
            "    });\n" +
            "    if (isPaused) {\n" +
            "      bar.innerHTML = '<span style=\"color:#facc15;\">⏸ PAUSED (Manual Login)</span> ' +\n" +
            "        '<button id=\"__rec_start_btn__\" style=\"background:#ef4444;color:#fff;border:none;padding:5px 12px;border-radius:12px;cursor:pointer;font-weight:700;font-size:12px;\">🔴 Start Recording</button>';\n" +
            "      var btn = bar.querySelector('#__rec_start_btn__');\n" +
            "      if (btn) {\n" +
            "        btn.onclick = function(e) {\n" +
            "          e.preventDefault(); e.stopPropagation();\n" +
            "          window.__recorderStartPaused = false;\n" +
            "          bar.innerHTML = '<span style=\"color:#22c55e;\">🔴 REC (Recording Active)</span>';\n" +
            "          if (typeof window.__resumeRecording === 'function') {\n" +
            "            window.__resumeRecording();\n" +
            "          }\n" +
            "        };\n" +
            "      }\n" +
            "    } else {\n" +
            "      bar.innerHTML = '<span style=\"color:#22c55e;\">🔴 REC (Recording Active)</span>';\n" +
            "    }\n" +
            "    document.body.appendChild(bar);\n" +
            "  }\n" +
            "  if (document.readyState === 'loading') {\n" +
            "    document.addEventListener('DOMContentLoaded', ensureToolbar);\n" +
            "  } else {\n" +
            "    ensureToolbar();\n" +
            "  }\n" +
            "\n" +
            "  document.addEventListener('click', function(e) {\n" +
            "    if (!e.altKey) return;\n" +
            "    e.preventDefault(); e.stopPropagation();\n" +
            "    var el = document.elementFromPoint(e.clientX, e.clientY);\n" +
            "    if (!el || el.id === '__recorder_toolbar__' || (el.closest && el.closest('#__recorder_toolbar__'))) return;\n" +
            "    var loc = getLocator(el);\n" +
            "    if (typeof window.__inspectLocator === 'function') {\n" +
            "      window.__inspectLocator('page.' + loc);\n" +
            "    }\n" +
            "  }, true);\n" +
            "})();\n";
    }

    // ─────────────────────────── Main recorder ──────────────────────────────

    private final RecorderEngine engine = new RecorderEngine();
    private final String startUrl;
    private final String browserChoice;
    private final String projectRoot;
    private final String pageObjectName;

    /** Last URL seen — used to auto-record navigation changes. */
    private final AtomicReference<String> lastUrl = new AtomicReference<>("");

    /** Primary / main browser page. */
    private final AtomicReference<Page> primaryPage = new AtomicReference<>(null);

    /** Live Playwright page — set once the browser opens, used for locator validation. */
    private final AtomicReference<Page> currentPage = new AtomicReference<>(null);

    private final java.util.concurrent.atomic.AtomicBoolean isPaused = new java.util.concurrent.atomic.AtomicBoolean(false);

    private final java.util.concurrent.atomic.AtomicLong lastInteractionTime = new java.util.concurrent.atomic.AtomicLong(0);
    private boolean isResume = false;

    public WebRecorder(String startUrl, String browserChoice, String projectRoot, String pageObjectName) {
        this.startUrl = startUrl;
        this.browserChoice = browserChoice;
        this.projectRoot = projectRoot;
        this.pageObjectName = pageObjectName;
    }

    public void start() throws Exception {
        start(new ArrayList<>(), false);
    }

    public void start(List<ActionModel> preRecordedActions) throws Exception {
        start(preRecordedActions, false);
    }

    public void start(List<ActionModel> preRecordedActions, boolean startPaused) throws Exception {
        this.isResume = (preRecordedActions != null && !preRecordedActions.isEmpty());
        this.isPaused.set(startPaused);
        if (preRecordedActions != null && !preRecordedActions.isEmpty()) {
            for (ActionModel action : preRecordedActions) {
                engine.addAction(action);
            }
            engine.addComment("============================================================");
            engine.addComment("  RESUMED RECORDING — NEW STEPS START BELOW");
            engine.addComment("============================================================");
        }

        System.out.println("\n╔══════════════════════════════════════════════════╗");
        System.out.println("║         Web Recorder — Playwright Java           ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.println("║  Commands while recording:                       ║");
        System.out.println("║    stop / done / exit → export & quit            ║");
        System.out.println("║    undo               → remove last action       ║");
        System.out.println("║    list               → show recorded actions    ║");
        System.out.println("║    comment <text>     → add a comment step       ║");
        System.out.println("║    pause              → pause recording          ║");
        System.out.println("║    resume             → resume/start recording   ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.println("║  Inspector (no recording):                       ║");
        System.out.println("║    Hover any element → see locator tooltip       ║");
        System.out.println("║    Alt+Click         → print locator to CLI      ║");
        System.out.println("╚══════════════════════════════════════════════════╝\n");

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("NODE_TLS_REJECT_UNAUTHORIZED", "0");
        Playwright.CreateOptions playwrightOptions = new Playwright.CreateOptions().setEnv(env);

        try (Playwright playwright = Playwright.create(playwrightOptions)) {
            BrowserType bt = chooseBrowser(playwright);

            Browser browser = bt.launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setArgs(Arrays.asList("--start-maximized"))
            );

            BrowserContext context = browser.newContext(
                new Browser.NewContextOptions().setViewportSize(null)
            );

            Page page = context.newPage();
            primaryPage.set(page);
            currentPage.set(page); // make available to processAction for validation

            // ── Register callback that JS will call with recorded actions ──
            context.exposeBinding("__recordAction", (source, args) -> {
                if (args == null || args.length == 0) return null;
                boolean isPopup = source.page() != null && !source.page().equals(primaryPage.get());
                String json = args[0].toString();
                processAction(json, isPopup);
                return null;
            });

            // ── Register inspector callback (Alt+Click, not recorded) ──────
            context.exposeBinding("__inspectLocator", (source, args) -> {
                if (args == null || args.length == 0) return null;
                boolean isPopup = source.page() != null && !source.page().equals(primaryPage.get());
                System.out.println();
                System.out.println("  🔍 INSPECT " + (isPopup ? "[POPUP] " : "") + "→ " + args[0].toString());
                System.out.print("  > ");
                return null;
            });

            // ── Register toolbar resume callback ───────────────────────────
            context.exposeBinding("__resumeRecording", (source, args) -> {
                if (isPaused.get()) {
                    isPaused.set(false);
                    Page p = (source != null && source.page() != null) ? source.page() : primaryPage.get();
                    String currentUrl = p.url();
                    lastUrl.set(currentUrl);
                    ActionModel nav = new ActionModel(
                        ActionType.NAVIGATE, currentUrl, currentUrl,
                        "Navigate → " + currentUrl
                    );
                    engine.addAction(nav);
                    System.out.println("\n  ▶ Recording resumed from browser button at URL: " + currentUrl);
                    System.out.println("  ✔ NAVIGATE → " + currentUrl);
                    System.out.print("  > ");
                }
                return null;
            });

            // ── Inject recorder script into every page load ────────────────
            context.addInitScript("window.__recorderStartPaused = " + isPaused.get() + ";");
            context.addInitScript(JS_RECORDER);
            context.addInitScript(JS_INSPECTOR);

            // ── Track popup windows / new tabs ─────────────────────────────
            context.onPage(newPage -> {
                currentPage.set(newPage);
                System.out.println("  🪟 [POPUP/TAB OPENED] Active target switched to: " + (newPage.url().isEmpty() ? "about:blank" : newPage.url()));

                newPage.onFrameNavigated(frame -> {
                    if (frame.equals(newPage.mainFrame())) {
                        String url = frame.url();
                        if (!url.equals("about:blank") && !url.equals(lastUrl.get())) {
                            lastUrl.set(url);
                        }
                    }
                });

                newPage.onClose(closedPage -> {
                    System.out.println("  🪟 [POPUP/TAB CLOSED]");
                    List<Page> remaining = context.pages();
                    if (!remaining.isEmpty()) {
                        currentPage.set(remaining.get(remaining.size() - 1));
                    }
                });
            });

            // ── Track navigation changes on primary page ───────────────────
            page.onFrameNavigated(frame -> {
                if (frame.equals(page.mainFrame())) {
                    String url = frame.url();
                    if (!url.equals("about:blank") && !url.equals(lastUrl.get())) {
                        lastUrl.set(url);
                        if (!isPaused.get()) {
                            // Only record NAVIGATE if it was not triggered by a recent click/input interaction
                            long elapsed = System.currentTimeMillis() - lastInteractionTime.get();
                            if (elapsed > 3000) {
                                ActionModel nav = new ActionModel(
                                    ActionType.NAVIGATE, url, url,
                                    "Navigate → " + url
                                );
                                engine.addAction(nav);
                                System.out.println("  ✔ NAVIGATE → " + url);
                            }
                        }
                    }
                }
            });

            if (preRecordedActions != null && !preRecordedActions.isEmpty()) {
                boolean wasPaused = isPaused.get();
                isPaused.set(true); // Pause recording during playback
                playbackPreRecorded(page, preRecordedActions);
                isPaused.set(wasPaused); // Restore previous paused state
                lastUrl.set(page.url());
            } else {
                // ── Open starting URL ──────────────────────────────────────────
                System.out.println("  Opening: " + startUrl + "\n");
                lastUrl.set(startUrl);
                page.navigate(startUrl);

                if (!isPaused.get()) {
                    // Add initial navigate action
                    engine.addAction(new ActionModel(
                        ActionType.NAVIGATE, startUrl, startUrl,
                        "Navigate → " + startUrl
                    ));
                }
            }

            // ── CLI loop ───────────────────────────────────────────────────
            Scanner scanner = new Scanner(System.in);
            if (isPaused.get()) {
                System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
                System.out.println("║  ⏸  RECORDING IS PAUSED (Manual Login / Setup Mode)         ║");
                System.out.println("╠══════════════════════════════════════════════════════════════╣");
                System.out.println("║  1. Go to the browser and complete your login manually.      ║");
                System.out.println("║  2. Navigate to your target page.                            ║");
                System.out.println("║  3. Once on target page, click [🔴 Start Recording] on the   ║");
                System.out.println("║     browser page OR type 'resume' here in CLI.               ║");
                System.out.println("║                                                              ║");
                System.out.println("║  ⚠️  DO NOT type 'resume' before completing your login!       ║");
                System.out.println("╚══════════════════════════════════════════════════════════════╝\n");
            } else {
                System.out.println("  Recording started. Interact with the browser...\n");
            }

            while (true) {
                System.out.print("  > ");
                if (!scanner.hasNextLine()) break;
                String line = scanner.nextLine().trim();

                if (line.isEmpty()) continue;

                if (line.equalsIgnoreCase("stop")
                        || line.equalsIgnoreCase("done")
                        || line.equalsIgnoreCase("exit")) {
                    break;

                } else if (line.equalsIgnoreCase("undo")) {
                    engine.undoLast();

                } else if (line.equalsIgnoreCase("list")) {
                    engine.printSummary();

                } else if (line.equalsIgnoreCase("pause")) {
                    isPaused.set(true);
                    System.out.println("  ⏸ Recording paused. Interact manually. Type 'resume' to start recording again.");

                } else if (line.equalsIgnoreCase("resume") || line.equalsIgnoreCase("record")) {
                    if (isPaused.get()) {
                        isPaused.set(false);
                        String currentUrl = page.url();
                        lastUrl.set(currentUrl);
                        ActionModel nav = new ActionModel(
                            ActionType.NAVIGATE, currentUrl, currentUrl,
                            "Navigate → " + currentUrl
                        );
                        engine.addAction(nav);
                        System.out.println("  ▶ Recording resumed at URL: " + currentUrl);
                        System.out.println("  ✔ NAVIGATE → " + currentUrl);
                    } else {
                        System.out.println("  Already recording.");
                    }

                } else if (line.toLowerCase().startsWith("comment ")) {
                    String comment = line.substring(8).trim();
                    engine.addComment(comment);
                    System.out.println("  ✔ Comment added: " + comment);

                } else {
                    System.out.println("  Unknown command. Try: stop | undo | list | comment <text> | pause | resume");
                }
            }

            // ── Export ─────────────────────────────────────────────────────
            browser.close();

            if (engine.size() == 0) {
                System.out.println("\n  No actions recorded. Nothing to export.");
                return;
            }

            engine.printSummary();
            System.out.println("\n  Exporting generated test...");
            TestScriptExporter exporter = new TestScriptExporter(projectRoot, browserChoice, pageObjectName, isResume);
            String javaOutputPath = exporter.export(engine.getActions(), startUrl);
            String exportedName = exporter.getPageObjectName();

            System.out.println("\n  ✅ Java test saved to:");
            System.out.println("     " + javaOutputPath);
            System.out.println("\n  Run Java tests:       mvn test");
            System.out.println("\n  Run TypeScript tests:");
            System.out.println("     cd typescript-tests");
            System.out.println("     .\\run-tests.bat");
            System.out.println("\n  🤖 Sub-Agent Tip: Ask Antigravity:");
            System.out.println("     \"Integrate " + exportedName + " recording into the framework\"\n");
        }
    }

    // ── Handle JSON action from JS ─────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private void processAction(String json, boolean isPopup) {
        if (isPaused.get()) {
            return;
        }
        lastInteractionTime.set(System.currentTimeMillis());
        try {
            Map<String, Object> el = parseJson(json);
            if (el == null || el.isEmpty()) return;

            String actionType = str(el, "actionType");
            String locator    = SmartLocatorWeb.buildLocator(el);
            String value      = str(el, "value");
            String tag        = str(el, "tag");

            ActionModel action;
            String display;
            String popupPrefix = isPopup ? "[POPUP] " : "";

            switch (actionType) {
                case "CLICK":
                    action  = new ActionModel(ActionType.CLICK, locator, "", "Click → " + locator, isPopup);
                    display = "  ✔ " + popupPrefix + "CLICK → " + locator;
                    break;

                case "INPUT":
                    action  = new ActionModel(ActionType.INPUT, locator, value, "Fill → " + locator + " = \"" + value + "\"", isPopup);
                    display = "  ✔ " + popupPrefix + "INPUT → " + locator + "  =  \"" + value + "\"";
                    break;

                case "SELECT":
                    action  = new ActionModel(ActionType.SELECT, locator, value, "Select → " + locator + " = \"" + value + "\"", isPopup);
                    display = "  ✔ " + popupPrefix + "SELECT → " + locator + "  =  \"" + value + "\"";
                    break;

                case "CHECK":
                    action  = new ActionModel(ActionType.CHECK, locator, "", "Check → " + locator, isPopup);
                    display = "  ✔ " + popupPrefix + "CHECK → " + locator;
                    break;

                case "UNCHECK":
                    action  = new ActionModel(ActionType.UNCHECK, locator, "", "Uncheck → " + locator, isPopup);
                    display = "  ✔ " + popupPrefix + "UNCHECK → " + locator;
                    break;

                case "PRESS":
                    action  = new ActionModel(ActionType.PRESS, locator, value, "Press → " + locator + " [" + value + "]", isPopup);
                    display = "  ✔ " + popupPrefix + "PRESS → " + locator + "  key=" + value;
                    break;

                case "HOVER":
                    action  = new ActionModel(ActionType.HOVER, locator, "", "Hover → " + locator, isPopup);
                    display = "  ✔ " + popupPrefix + "HOVER → " + locator;
                    break;

                default:
                    return; // ignore unknown types
            }

            engine.addAction(action);
            System.out.println(display);

            // ── Pre-script uniqueness check: swap locator if ambiguous ──────
            if (action.type != ActionType.NAVIGATE && action.type != ActionType.COMMENT) {
                String refined = ensureUniqueLocator(locator, el);
                if (!refined.equals(locator)) {
                    engine.replaceLastLocator(refined);
                    System.out.println("  ✎ Refined → " + refined);
                }
            }

            System.out.print("  > "); // re-prompt

        } catch (Exception e) {
            // Silently ignore JSON parsing errors from injected script
        }
    }

    /**
     * Validates the current locator. If it matches 0 or >1 elements,
     * tries progressively more specific compound XPath candidates from
     * SmartLocatorWeb.buildCandidates() until exactly 1 match is found.
     *
     * @return the best unique locator found, or the original if nothing better exists.
     */
    private String ensureUniqueLocator(String locatorExpr, Map<String, Object> el) {
        try {
            Page page = currentPage.get();
            if (page == null) return locatorExpr;

            // Check current locator
            int count = countMatches(page, locatorExpr);
            if (count == 1) return locatorExpr; // already unique ✔

            // Try compound candidates in order (most stable → most specific)
            for (String candidate : SmartLocatorWeb.buildCandidates(el)) {
                if (candidate.equals(locatorExpr)) continue; // already tried
                int c = countMatches(page, candidate);
                if (c == 1) {
                    if (count == 0) {
                        System.out.println("  ⚠️  Original matched 0 — using: " + candidate);
                    } else {
                        System.out.println("  ⚠️  Original ambiguous (" + count + " matches) — using: " + candidate);
                    }
                    return candidate;
                }
            }

            // ── Try Browser-Side Agentic Refinement Loop ──
            try {
                Object result = page.evaluate(
                    "() => {\n" +
                    "  var el = document.querySelector('[data-recorder-temp=\"true\"]');\n" +
                    "  if (!el) return null;\n" +
                    "  \n" +
                    "  function isUnique(sel) {\n" +
                    "    try { return document.querySelectorAll(sel).length === 1; } catch(e) { return false; }\n" +
                    "  }\n" +
                    "  \n" +
                    "  var tag = el.tagName.toLowerCase();\n" +
                    "  \n" +
                    "  // 1. Try class combinations\n" +
                    "  if (el.className && typeof el.className === 'string') {\n" +
                    "    var classes = el.className.trim().split(/\\s+/).filter(c => c && c.length > 2);\n" +
                    "    for (var c of classes) {\n" +
                    "      var sel = tag + '.' + c;\n" +
                    "      if (isUnique(sel)) return 'page.locator(\"' + sel + '\")';\n" +
                    "    }\n" +
                    "    if (classes.length >= 2) {\n" +
                    "      var sel2 = tag + '.' + classes[0] + '.' + classes[1];\n" +
                    "      if (isUnique(sel2)) return 'page.locator(\"' + sel2 + '\")';\n" +
                    "    }\n" +
                    "  }\n" +
                    "  \n" +
                    "  // 2. Try unique parent selector contexts\n" +
                    "  var current = el.parentElement;\n" +
                    "  var depth = 0;\n" +
                    "  while (current && current !== document.body && depth < 5) {\n" +
                    "    if (current.id && !/^\\d/.test(current.id)) {\n" +
                    "      var sel = '#' + current.id + ' ' + tag;\n" +
                    "      if (isUnique(sel)) return 'page.locator(\"' + sel + '\")';\n" +
                    "    }\n" +
                    "    if (current.className && typeof current.className === 'string') {\n" +
                    "      var pClasses = current.className.trim().split(/\\s+/).filter(c => c && c.length > 2);\n" +
                    "      for (var pc of pClasses) {\n" +
                    "        var sel = '.' + pc + ' ' + tag;\n" +
                    "        if (isUnique(sel)) return 'page.locator(\"' + sel + '\")';\n" +
                    "      }\n" +
                    "    }\n" +
                    "    current = current.parentElement;\n" +
                    "    depth++;\n" +
                    "  }\n" +
                    "  \n" +
                    "  // 3. Fallback to cssPath\n" +
                    "  var parts = [];\n" +
                    "  var curr = el;\n" +
                    "  while (curr && curr.nodeType === Node.ELEMENT_NODE && curr !== document.body) {\n" +
                    "    var selector = curr.nodeName.toLowerCase();\n" +
                    "    if (curr.id && !/^\\d/.test(curr.id) && !/:/.test(curr.id)) {\n" +
                    "      selector += '#' + curr.id;\n" +
                    "      parts.unshift(selector);\n" +
                    "      break;\n" +
                    "    } else {\n" +
                    "      var sib = curr, nth = 1;\n" +
                    "      while ((sib = sib.previousElementSibling)) {\n" +
                    "        if (sib.nodeName === curr.nodeName) nth++;\n" +
                    "      }\n" +
                    "      if (nth > 1) selector += ':nth-of-type(' + nth + ')';\n" +
                    "    }\n" +
                    "    parts.unshift(selector);\n" +
                    "    curr = curr.parentElement;\n" +
                    "  }\n" +
                    "  var fullPath = parts.join(' > ');\n" +
                    "  if (isUnique(fullPath)) return 'page.locator(\"' + fullPath + '\")';\n" +
                    "  \n" +
                    "  return null;\n" +
                    "}"
                );
                if (result != null) {
                    String refined = result.toString();
                    System.out.println("  🤖 Agent Refined → " + refined);
                    return refined;
                }
            } catch (Exception ex) {
                // ignore page script error
            }

            // Nothing resolved to exactly 1 — warn and keep original
            if (count == 0) {
                System.out.println("  ⚠️  WARNING: locator matched 0 elements → " + locatorExpr);
            } else if (count > 1) {
                System.out.println("  ⚠️  WARNING: still ambiguous (" + count + " matches) → " + locatorExpr);
            }
            return locatorExpr;

        } catch (Exception e) {
            return locatorExpr; // page closed / nav in progress
        } finally {
            try {
                Page page = currentPage.get();
                if (page != null) {
                    page.evaluate("() => document.querySelectorAll('[data-recorder-temp]').forEach(x => x.removeAttribute('data-recorder-temp'))");
                }
            } catch (Exception ex) {}
        }
    }

    /** Counts how many elements a locator expression matches on the live page. */
    private int countMatches(Page page, String locatorExpr) {
        try {
            if (locatorExpr.startsWith("page.locator(\"") && locatorExpr.endsWith("\")")) {
                String selector = locatorExpr.substring("page.locator(\"".length(), locatorExpr.length() - 2);
                return page.locator(selector).count();
            } else if (locatorExpr.startsWith("page.getByPlaceholder(\"") && locatorExpr.endsWith("\")")) {
                String ph = locatorExpr.substring("page.getByPlaceholder(\"".length(), locatorExpr.length() - 2);
                return page.getByPlaceholder(ph).count();
            } else if (locatorExpr.startsWith("page.getByTestId(\"") && locatorExpr.endsWith("\")")) {
                String tid = locatorExpr.substring("page.getByTestId(\"".length(), locatorExpr.length() - 2);
                return page.getByTestId(tid).count();
            } else if (locatorExpr.startsWith("page.getByLabel(\"") && locatorExpr.endsWith("\")")) {
                String label = locatorExpr.substring("page.getByLabel(\"".length(), locatorExpr.length() - 2);
                return page.getByLabel(label).count();
            } else if (locatorExpr.startsWith("page.getByText(\"") && locatorExpr.endsWith("\")")) {
                String text = locatorExpr.substring("page.getByText(\"".length(), locatorExpr.length() - 2);
                return page.getByText(text).count();
            }
            return -1; // can't extract selector
        } catch (Exception e) {
            return -1;
        }
    }

    // ─────────────────────────── Helpers ───────────────────────────────────

    private BrowserType chooseBrowser(Playwright playwright) {
        switch (browserChoice.toLowerCase()) {
            case "firefox": return playwright.firefox();
            case "webkit":  return playwright.webkit();
            default:        return playwright.chromium();
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v == null) ? "" : v.toString().trim();
    }

    /**
     * Minimal JSON object parser (no external library needed).
     * Handles flat string-value maps as produced by the JS recorder.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return map;
        json = json.substring(1, json.length() - 1);

        // Tokenize key:value pairs, respecting quoted strings
        int i = 0;
        while (i < json.length()) {
            // Skip whitespace and commas
            while (i < json.length() && (json.charAt(i) == ',' || json.charAt(i) == ' ')) i++;
            if (i >= json.length()) break;

            // Parse key
            if (json.charAt(i) != '"') break;
            i++; // skip opening quote
            StringBuilder key = new StringBuilder();
            while (i < json.length() && json.charAt(i) != '"') {
                if (json.charAt(i) == '\\') i++; // skip escape
                if (i < json.length()) key.append(json.charAt(i++));
            }
            i++; // skip closing quote

            // Skip colon
            while (i < json.length() && json.charAt(i) != ':') i++;
            i++; // skip ':'

            // Skip whitespace
            while (i < json.length() && json.charAt(i) == ' ') i++;

            // Parse value
            StringBuilder val = new StringBuilder();
            if (i < json.length() && json.charAt(i) == '"') {
                i++; // skip opening quote
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') {
                        i++;
                        if (i < json.length()) {
                            char esc = json.charAt(i);
                            switch (esc) {
                                case 'n': val.append('\n'); break;
                                case 't': val.append('\t'); break;
                                case '"': val.append('"');  break;
                                case '\\': val.append('\\'); break;
                                default:  val.append(esc);  break;
                            }
                            i++;
                        }
                    } else {
                        val.append(json.charAt(i++));
                    }
                }
                i++; // skip closing quote
            } else {
                // non-string value (number, boolean, null)
                while (i < json.length() && json.charAt(i) != ',' && json.charAt(i) != '}') {
                    val.append(json.charAt(i++));
                }
            }

            map.put(key.toString(), val.toString());
        }
        return map;
    }

    public static List<ActionModel> parseExistingTest(String projectRoot) {
        List<ActionModel> actions = new ArrayList<>();
        java.io.File testDir = new java.io.File(projectRoot + "/src/test/java/recorder");
        if (!testDir.exists()) {
            System.out.println("  [Warning] Test directory not found. Starting fresh.");
            return actions;
        }

        java.io.File[] testFiles = testDir.listFiles((dir, name) -> name.endsWith(".java"));
        if (testFiles == null || testFiles.length == 0) {
            System.out.println("  [Warning] No existing test files found. Starting fresh.");
            return actions;
        }

        java.io.File testFile = java.util.Arrays.stream(testFiles)
                .max(java.util.Comparator.comparingLong(java.io.File::lastModified))
                .orElse(testFiles[0]);

        try {
            String content = new String(java.nio.file.Files.readAllBytes(testFile.toPath()));
            
            // 1. Find the page object name
            java.util.regex.Pattern importPattern = java.util.regex.Pattern.compile("import pageobjects\\.(\\w+);");
            java.util.regex.Matcher importMatcher = importPattern.matcher(content);
            String pageObjectName = "GeneratedWebPage";
            if (importMatcher.find()) {
                pageObjectName = importMatcher.group(1);
            }
            
            // 2. Read constants from the Page Object file
            Map<String, String> selectorMap = new HashMap<>();
            java.io.File poFile = new java.io.File(projectRoot + "/src/main/java/pageobjects/" + pageObjectName + ".java");
            if (poFile.exists()) {
                String poContent = new String(java.nio.file.Files.readAllBytes(poFile.toPath()));
                java.util.regex.Pattern constPattern = java.util.regex.Pattern.compile("public static final String (\\w+)\\s*=\\s*\"([^\"]+)\";");
                java.util.regex.Matcher constMatcher = constPattern.matcher(poContent);
                while (constMatcher.find()) {
                    selectorMap.put(pageObjectName + "." + constMatcher.group(1), constMatcher.group(2));
                }
            }

            // 3. Parse testRecordedFlow statements
            int startIdx = content.indexOf("public void testRecordedFlow()");
            if (startIdx != -1) {
                int methodBodyStart = content.indexOf('{', startIdx);
                if (methodBodyStart != -1) {
                    int bracketCount = 1;
                    int cur = methodBodyStart + 1;
                    while (cur < content.length() && bracketCount > 0) {
                        char c = content.charAt(cur);
                        if (c == '{') bracketCount++;
                        else if (c == '}') bracketCount--;
                        cur++;
                    }
                    String body = content.substring(methodBodyStart + 1, cur - 1);
                    
                    String[] statements = body.split(";");
                    for (String stmt : statements) {
                        stmt = stmt.trim();
                        if (stmt.isEmpty()) continue;
                        
                        if (stmt.contains("page.navigate(")) {
                            int q1 = stmt.indexOf('"');
                            int q2 = stmt.indexOf('"', q1 + 1);
                            if (q1 != -1 && q2 != -1) {
                                String url = stmt.substring(q1 + 1, q2);
                                actions.add(new ActionModel(ActionType.NAVIGATE, url, "", "Navigate → " + url));
                            }
                        }
                        else if (stmt.startsWith("SelfHealingLocator.")) {
                            String op = stmt.substring("SelfHealingLocator.".length(), stmt.indexOf('('));
                            String argsStr = stmt.substring(stmt.indexOf('(') + 1, stmt.lastIndexOf(')'));
                            String[] args = splitArgs(argsStr);
                            
                            if (args.length >= 3) {
                                String key = cleanArg(args[1]);
                                String selectorKey = args[2].trim();
                                String selector = selectorMap.getOrDefault(selectorKey, selectorKey);
                                String wrappedLocator = wrapLocator(selector);
                                
                                switch (op) {
                                    case "click":
                                        actions.add(new ActionModel(ActionType.CLICK, wrappedLocator, "", "Click → " + selector));
                                        break;
                                    case "hover":
                                        actions.add(new ActionModel(ActionType.HOVER, wrappedLocator, "", "Hover → " + selector));
                                        break;
                                    case "check":
                                        actions.add(new ActionModel(ActionType.CHECK, wrappedLocator, "", "Check → " + selector));
                                        break;
                                    case "uncheck":
                                        actions.add(new ActionModel(ActionType.UNCHECK, wrappedLocator, "", "Uncheck → " + selector));
                                        break;
                                    case "fill":
                                        String val = args.length >= 4 ? cleanArg(args[3]) : "";
                                        actions.add(new ActionModel(ActionType.INPUT, wrappedLocator, val, "Fill → " + selector + " = \"" + val + "\""));
                                        break;
                                    case "selectOption":
                                        String selVal = args.length >= 4 ? cleanArg(args[3]) : "";
                                        actions.add(new ActionModel(ActionType.SELECT, wrappedLocator, selVal, "Select → " + selector + " = \"" + selVal + "\""));
                                        break;
                                    case "press":
                                        String pressKey = args.length >= 4 ? cleanArg(args[3]) : "";
                                        actions.add(new ActionModel(ActionType.PRESS, wrappedLocator, pressKey, "Press → " + selector + " key=" + pressKey));
                                        break;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("  [Warning] Failed to parse existing test: " + e.getMessage());
        }
        return actions;
    }

    private static String[] splitArgs(String argsStr) {
        List<String> list = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                sb.append(c);
            } else if (c == ',' && !inQuotes) {
                list.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            list.add(sb.toString());
        }
        return list.toArray(new String[0]);
    }

    private static String cleanArg(String arg) {
        arg = arg.trim();
        if (arg.startsWith("\"") && arg.endsWith("\"")) {
            return arg.substring(1, arg.length() - 1);
        }
        return arg;
    }

    private static String wrapLocator(String raw) {
        if (raw == null) return "";
        if (raw.startsWith("page.locator") || raw.startsWith("page.getBy")) {
            return raw;
        }
        if (raw.startsWith("[aria-label='") && raw.endsWith("']")) {
            return "page.getByLabel(\"" + raw.substring("[aria-label='".length(), raw.length() - 2) + "\")";
        }
        if (raw.startsWith("[placeholder='") && raw.endsWith("']")) {
            return "page.getByPlaceholder(\"" + raw.substring("[placeholder='".length(), raw.length() - 2) + "\")";
        }
        if (raw.startsWith("[data-testid='") && raw.endsWith("']")) {
            return "page.getByTestId(\"" + raw.substring("[data-testid='".length(), raw.length() - 2) + "\")";
        }
        if (raw.startsWith("text=")) {
            return "page.getByText(\"" + raw.substring("text=".length()) + "\")";
        }
        return "page.locator(\"" + raw + "\")";
    }

    private static String getRawSelector(String locatorExpr) {
        if (locatorExpr == null) return "";
        if (locatorExpr.startsWith("page.locator(\"") && locatorExpr.endsWith("\")")) {
            return locatorExpr.substring("page.locator(\"".length(), locatorExpr.length() - 2);
        } else if (locatorExpr.startsWith("page.getByPlaceholder(\"") && locatorExpr.endsWith("\")")) {
            String val = locatorExpr.substring("page.getByPlaceholder(\"".length(), locatorExpr.length() - 2);
            return "[placeholder='" + val + "']";
        } else if (locatorExpr.startsWith("page.getByTestId(\"") && locatorExpr.endsWith("\")")) {
            String val = locatorExpr.substring("page.getByTestId(\"".length(), locatorExpr.length() - 2);
            return "[data-testid='" + val + "']";
        } else if (locatorExpr.startsWith("page.getByLabel(\"") && locatorExpr.endsWith("\")")) {
            String val = locatorExpr.substring("page.getByLabel(\"".length(), locatorExpr.length() - 2);
            return "[aria-label='" + val + "']";
        } else if (locatorExpr.startsWith("page.getByText(\"") && locatorExpr.endsWith("\")")) {
            String val = locatorExpr.substring("page.getByText(\"".length(), locatorExpr.length() - 2);
            return "text=" + val;
        }
        return locatorExpr;
    }

    private void playbackPreRecorded(Page page, List<ActionModel> actions) {
        if (actions == null || actions.isEmpty()) return;
        System.out.println("  Playing back " + actions.size() + " pre-recorded actions...");
        for (ActionModel a : actions) {
            try {
                if (a.type == ActionType.NAVIGATE) {
                    System.out.println("    [Playback] Navigate → " + a.locator);
                    page.navigate(a.locator);
                    Thread.sleep(500);
                    continue;
                }

                String rawSel = getRawSelector(a.locator);
                Locator loc = page.locator(rawSel);
                
                // Wait up to 5 seconds for element to be visible
                try {
                    loc.waitFor(new Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE).setTimeout(5000));
                } catch (Exception e) {
                    // ignore and try interaction anyway
                }

                // Handle strict mode violation in playback if multiple elements match
                Locator target = loc;
                try {
                    int count = loc.count();
                    if (count > 1) {
                        for (int i = 0; i < count; i++) {
                            Locator item = loc.nth(i);
                            if (item.isVisible() && item.isEnabled()) {
                                target = item;
                                break;
                            }
                        }
                    }
                } catch (Exception e) {}

                switch (a.type) {
                    case CLICK:
                        System.out.println("    [Playback] Click → " + rawSel);
                        target.click();
                        break;
                    case INPUT:
                        System.out.println("    [Playback] Fill → " + rawSel + " = \"" + a.value + "\"");
                        target.fill(a.value);
                        break;
                    case SELECT:
                        System.out.println("    [Playback] Select → " + rawSel + " = \"" + a.value + "\"");
                        target.selectOption(a.value);
                        break;
                    case CHECK:
                        System.out.println("    [Playback] Check → " + rawSel);
                        target.check();
                        break;
                    case UNCHECK:
                        System.out.println("    [Playback] Uncheck → " + rawSel);
                        target.uncheck();
                        break;
                    case HOVER:
                        System.out.println("    [Playback] Hover → " + rawSel);
                        target.hover();
                        break;
                    case PRESS:
                        System.out.println("    [Playback] Press → " + rawSel + " key=" + a.value);
                        target.press(a.value);
                        break;
                }
                Thread.sleep(500);
            } catch (Exception e) {
                System.err.println("    [Playback Warning] Action failed: " + e.getMessage());
            }
        }
        try {
            // Wait for final playback page redirects and DOM settling while still paused
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(5000));
            Thread.sleep(1500); 
        } catch (Exception e) {
            // ignore timeout
        }
        System.out.println("  Playback completed. Ready for new interactions.");
    }
}
