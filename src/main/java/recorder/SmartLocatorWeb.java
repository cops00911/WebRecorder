package recorder;

import java.util.Map;

/**
 * Converts raw DOM element descriptor maps (sent from the injected JS) into
 * stable Playwright Java locator strings.
 *
 * Priority (most stable → least stable):
 * 1. id → page.locator("#id")
 * 2. name attribute → page.locator("[name='val']")
 * 3. placeholder → page.getByPlaceholder("...")
 * 4. data-testid → page.getByTestId("...")
 * 5. aria-label → page.getByLabel("...")
 * 6. role + visible text → page.getByRole(AriaRole.BUTTON, ...)
 * 7. unique CSS class → page.locator(".class-name")
 * 8. visible text → page.getByText("...")
 * 9. XPath (fallback) → page.locator("xpath=//tag[@attr='val']")
 */
public class SmartLocatorWeb {

    public static String buildLocator(Map<String, Object> el) {
        // Just return the highest priority candidate.
        // WebRecorder will validate its uniqueness and fallback to other candidates if
        // needed.
        return buildCandidates(el).get(0);
    }

    // ─────────────────────── Locator Candidates ────────────────────────────────

    /**
     * Returns a list of increasingly specific selector strings to try in order.
     * The caller validates each against the live page and uses the first one
     * that resolves to exactly 1 element.
     *
     * Candidates (from least to most specific):
     * - single-attribute XPath
     * - compound XPath with 2 attributes (AND)
     * - compound XPath with 3 attributes
     */
    public static java.util.List<String> buildCandidates(java.util.Map<String, Object> el) {
        java.util.List<String> candidates = new java.util.ArrayList<>();

        String tag = str(el, "tag");
        String id = str(el, "id");
        String name = str(el, "name");
        String placeholder = str(el, "placeholder");
        String ariaLabel = str(el, "ariaLabel");
        String role = str(el, "role");
        String type = str(el, "type");
        String text = str(el, "text").replaceAll("\\s+", " ").trim();
        String testId = str(el, "testid");
        String labelText = str(el, "labelText").replaceAll("\\s+", " ").trim();
        String cssClass = str(el, "cssClass");

        if (tag.isEmpty())
            tag = "*";
        boolean hasText = !text.isEmpty()
                && !tag.equalsIgnoreCase("input") && !tag.equalsIgnoreCase("textarea");

        // ── 1. Priority: Text-based locators (Text=, Exact & Contains XPath) ──
        if (hasText) {
            // Text exact locator
            if (text.length() <= 80) {
                candidates.add("text=" + text);
                candidates.add(xpath(tag, "normalize-space()='" + escXPath(text) + "'"));
            }
            // Tag-agnostic exact match
            if (!tag.equals("*") && text.length() <= 60) {
                candidates.add(xpath("*", "normalize-space()='" + escXPath(text) + "'"));
            }
            // First-word key phrase locator (e.g. "Brands", "Units")
            String[] words = text.split("\\s+");
            if (words.length > 1 && words[0].length() >= 3) {
                candidates.add("text=" + words[0]);
                candidates.add(xpath(tag, "contains(normalize-space(), '" + escXPath(words[0]) + "')"));
                if (!tag.equals("*")) {
                    candidates.add(xpath("*", "contains(normalize-space(), '" + escXPath(words[0]) + "')"));
                }
            }
            // Tag-specific contains match
            String cleanSub = text;
            if (cleanSub.length() > 40) {
                cleanSub = cleanSub.substring(0, 40);
                int lastSpace = cleanSub.lastIndexOf(' ');
                if (lastSpace > 20) {
                    cleanSub = cleanSub.substring(0, lastSpace);
                }
            }
            cleanSub = cleanSub.trim();
            if (!cleanSub.isEmpty() && cleanSub.length() >= 3) {
                candidates.add(xpath(tag, "contains(normalize-space(), '" + escXPath(cleanSub) + "')"));
                if (!tag.equals("*")) {
                    candidates.add(xpath("*", "contains(normalize-space(), '" + escXPath(cleanSub) + "')"));
                }
            }
        }

        // ── 1. Priority: Stable unique attributes (always unique, preferred over text) ──
        // These are checked FIRST so ambiguous text= selectors are never picked when a testid/id exists
        if (!id.isEmpty() && !isAutoId(id)) {
            candidates.add("#" + escape(id));
        }
        if (!testId.isEmpty()) {
            candidates.add("[data-testid='" + escape(testId) + "']");
        }
        if (!name.isEmpty()) {
            candidates.add("[name='" + escape(name) + "']");
        }
        if (!placeholder.isEmpty()) {
            candidates.add("[placeholder='" + escape(placeholder) + "']");
        }
        if (!ariaLabel.isEmpty()) {
            candidates.add("[aria-label='" + escape(ariaLabel) + "']");
        }

        // ── 2. Priority: Text-based locators (only used when no unique attribute found) ──
        if (hasText) {
            // Exact text match
            if (text.length() <= 80) {
                candidates.add("text=" + text);
                candidates.add(xpath(tag, "normalize-space()='" + escXPath(text) + "'"));
            }
            // Tag-agnostic exact match
            if (!tag.equals("*") && text.length() <= 60) {
                candidates.add(xpath("*", "normalize-space()='" + escXPath(text) + "'"));
            }
            // First-word key phrase locator (e.g. "Brands", "Units")
            String[] words = text.split("\\s+");
            if (words.length > 1 && words[0].length() >= 3) {
                candidates.add("text=" + words[0]);
                candidates.add(xpath(tag, "contains(normalize-space(), '" + escXPath(words[0]) + "')"));
                if (!tag.equals("*")) {
                    candidates.add(xpath("*", "contains(normalize-space(), '" + escXPath(words[0]) + "')"));
                }
            }
            // Tag-specific contains match
            String cleanSub = text;
            if (cleanSub.length() > 40) {
                cleanSub = cleanSub.substring(0, 40);
                int lastSpace = cleanSub.lastIndexOf(' ');
                if (lastSpace > 20) {
                    cleanSub = cleanSub.substring(0, lastSpace);
                }
            }
            cleanSub = cleanSub.trim();
            if (!cleanSub.isEmpty() && cleanSub.length() >= 3) {
                candidates.add(xpath(tag, "contains(normalize-space(), '" + escXPath(cleanSub) + "')"));
                if (!tag.equals("*")) {
                    candidates.add(xpath("*", "contains(normalize-space(), '" + escXPath(cleanSub) + "')"));
                }
            }
        }

        // ── 3. Priority: CSS Class-based Locators ───────────────────────
        if (!cssClass.isEmpty()) {
            candidates.add(tag + "." + escape(cssClass));
            candidates.add("." + escape(cssClass));
        }

        // ── 4. Priority: Parent/Adjacent Label Selector ─────────────
        if (!labelText.isEmpty()) {
            String escLabel = escXPath(labelText);
            if (!role.isEmpty()) {
                candidates.add(xpath(tag, "@role='" + escXPath(role) + "' and .//label[contains(normalize-space(), '" + escLabel + "')]"));
            }
            candidates.add(xpath("div", ".//label[contains(normalize-space(), '" + escLabel + "')]/following-sibling::" + tag));
        }

        // ── 5. Priority: Compound/Interactive Attributes ──────────────
        if (!role.isEmpty() && hasText) {
            candidates.add(xpath(tag, "@role='" + escXPath(role) + "' and normalize-space()='" + escXPath(text) + "'"));
        }
        if (!cssClass.isEmpty() && hasText) {
            candidates.add(xpath(tag, "contains(@class, '" + escXPath(cssClass) + "') and normalize-space()='" + escXPath(text) + "'"));
        }
        if (!role.isEmpty() && !ariaLabel.isEmpty()) {
            candidates.add(xpath(tag, "@role='" + escXPath(role) + "' and @aria-label='" + escXPath(ariaLabel) + "'"));
        }
        if (!name.isEmpty() && !type.isEmpty()) {
            candidates.add(xpath(tag, "@name='" + escXPath(name) + "' and @type='" + escXPath(type) + "'"));
        }

        // Fallback to tag
        if (candidates.isEmpty()) {
            candidates.add("page.locator(\"" + escapeJava(tag) + "\")");
        }

        return candidates;
    }

    /**
     * Wraps an XPath predicate into a full page.locator("xpath=...") expression.
     */
    private static String xpath(String tag, String predicate) {
        String xp = "//" + tag + "[" + predicate + "]";
        return "page.locator(\"xpath=" + escapeJava(xp) + "\")";
    }

    // ───────────────────────────────── helpers ─────────────────────────────

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v == null) ? "" : v.toString().trim();
    }

    /** Escape for Java string literal (double-quotes, backslashes). */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    /** Escape for use inside a Java string that already wraps an XPath string. */
    private static String escapeJava(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Escape single-quoted XPath predicate values. */
    private static String escXPath(String s) {
        // XPath 1.0 has no escaping for single quotes; use concat() trick for values with '
        if (!s.contains("'")) return s;
        return "concat('" + s.replace("'", "',\"'\",'") + "')";
    }

    private static boolean isAutoId(String id) {
        if (id == null || id.isEmpty()) return false;
        // Match common patterns for auto-generated IDs:
        // - Starts with underscore (e.g. _r_24_, _r_2d_-search)
        // - Starts with colon (e.g. :r0:)
        // - Contains multiple consecutive digits (e.g. mui-12345, id123)
        if (id.startsWith("_") || id.startsWith(":")) return true;
        if (id.matches(".*\\d{3,}.*")) return true; // 3 or more digits
        return false;
    }
}
