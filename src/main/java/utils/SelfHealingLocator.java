package utils;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.util.ArrayList;
import java.util.List;

/**
 * Basic standalone implementation of SelfHealingLocator.
 * This mirrors the interface in your main test workspace.
 */
public class SelfHealingLocator {

    @FunctionalInterface
    public interface LocatorAction {
        void perform(Locator locator);
    }

    private static void performWithHealing(Page page, String key, String defaultSelector, LocatorAction action) {
        try {
            // Set 10-second timeout for primary locator attempt to allow async page transitions to complete
            page.setDefaultTimeout(10000);
            action.perform(page.locator(defaultSelector));
            page.setDefaultTimeout(30000); // restore default timeout
        } catch (com.microsoft.playwright.PlaywrightException e) {
            page.setDefaultTimeout(30000); // restore default timeout on exception
            String errorMsg = e.getMessage();
            System.err.println("[Self-Healing] Error interacting with element '" + key + "' using selector '"
                    + defaultSelector + "': " + errorMsg);

            // 2. Strict Mode Violation Healing
            if (errorMsg != null && errorMsg.contains("strict mode violation")) {
                System.out.println(
                        "[Self-Healing] Strict mode violation detected. Attempting to find a visible and enabled matching element...");
                Locator locator = page.locator(defaultSelector);
                try {
                    int count = locator.count();
                    for (int i = 0; i < count; i++) {
                        Locator item = locator.nth(i);
                        if (item.isVisible() && item.isEnabled()) {
                            System.out.println(
                                    "[Self-Healing] Success! Resolved strict mode violation by interacting with match index "
                                            + i);
                            action.perform(item);
                            return;
                        }
                    }
                } catch (Exception ex) {
                    System.err.println("[Self-Healing] Failed during strict mode item iteration: " + ex.getMessage());
                }

                // Fallback to the first matching element
                System.out.println("[Self-Healing] Falling back to first matching element.");
                try {
                    action.perform(locator.first());
                    return;
                } catch (Exception ex) {
                    System.err.println("[Self-Healing] Fallback to first element failed: " + ex.getMessage());
                }
            }

            // 3. Element Not Found / Timeout / Locator Changed Healing
            System.out.println("[Self-Healing] Attempting text-based recovery healing...");
            String extractedText = extractText(defaultSelector);
            if (extractedText != null && !extractedText.isEmpty()) {
                String cleanedText = extractedText.replaceAll("\\s+", " ").trim();
                List<String> fallbacks = new ArrayList<>();
                
                // Full text fallbacks
                fallbacks.add("text=" + extractedText);
                fallbacks.add("xpath=//*[contains(text(), '" + extractedText + "')]");
                fallbacks.add("xpath=//*[contains(normalize-space(), '" + extractedText + "')]");

                String[] words = cleanedText.split("\\s+");
                // 1-word fallback (e.g. "Brands")
                if (words.length >= 1 && words[0].length() >= 3) {
                    String firstWord = words[0];
                    if (!firstWord.equalsIgnoreCase(extractedText)) {
                        fallbacks.add("text=" + firstWord);
                        fallbacks.add("xpath=//*[contains(normalize-space(), '" + firstWord + "')]");
                        fallbacks.add("xpath=//button[contains(normalize-space(), '" + firstWord + "')]");
                        fallbacks.add("xpath=//a[contains(normalize-space(), '" + firstWord + "')]");
                        fallbacks.add("xpath=//div[contains(normalize-space(), '" + firstWord + "')]");
                    }
                }
                // 2-word fallback (e.g. "Brands Controlled")
                if (words.length >= 2) {
                    String twoWords = words[0] + " " + words[1];
                    if (!twoWords.equalsIgnoreCase(extractedText)) {
                        fallbacks.add("text=" + twoWords);
                        fallbacks.add("xpath=//*[contains(normalize-space(), '" + twoWords + "')]");
                    }
                }
                // 3-word fallback (e.g. "Brands Controlled list")
                if (words.length >= 3) {
                    String threeWords = words[0] + " " + words[1] + " " + words[2];
                    if (!threeWords.equalsIgnoreCase(extractedText)) {
                        fallbacks.add("text=" + threeWords);
                        fallbacks.add("xpath=//*[contains(normalize-space(), '" + threeWords + "')]");
                    }
                }

                String cleanAlphaNum = cleanedText.replaceAll("[^a-zA-Z0-9 ]", "").trim().replaceAll("\\s+", " ");
                if (!cleanAlphaNum.isEmpty() && !cleanAlphaNum.equals(extractedText)) {
                    fallbacks.add("text=" + cleanAlphaNum);
                    fallbacks.add("xpath=//*[contains(normalize-space(), '" + cleanAlphaNum + "')]");
                }

                for (String fallback : fallbacks) {
                    try {
                        System.out.println("[Self-Healing] Trying fallback selector: " + fallback);
                        Locator fallbackLocator = page.locator(fallback);
                        int count = fallbackLocator.count();
                        if (count > 0) {
                            for (int i = 0; i < count; i++) {
                                Locator item = fallbackLocator.nth(i);
                                if (item.isVisible() && item.isEnabled()) {
                                    System.out.println("[Self-Healing Success] Successfully healed '" + key
                                            + "' using fallback selector: '" + fallback + "'");
                                    persistHealedSelector(key, fallback);
                                    action.perform(item);
                                    return;
                                }
                            }
                        }
                    } catch (Exception ex) {
                        // ignore and try next fallback
                    }
                }
            }

            // Diagnostic summary if healing attempts fail
            System.err.println("[Self-Healing Warning] Unable to find or interact with '" + key + "' (selector: " + defaultSelector + ").");
            System.err.println("[Self-Healing Diagnostic] Current Page URL: " + page.url());

            // Re-throw the original exception if all healing attempts fail
            throw e;
        }
    }

    private static String extractText(String selector) {
        if (selector == null)
            return null;
        int firstQuote = selector.indexOf('\'');
        if (firstQuote != -1) {
            int secondQuote = selector.indexOf('\'', firstQuote + 1);
            if (secondQuote != -1) {
                return selector.substring(firstQuote + 1, secondQuote);
            }
        }
        int firstDblQuote = selector.indexOf('"');
        if (firstDblQuote != -1) {
            int secondDblQuote = selector.indexOf('"', firstDblQuote + 1);
            if (secondDblQuote != -1) {
                return selector.substring(firstDblQuote + 1, secondDblQuote);
            }
        }
        return null;
    }

    public static void click(Page page, String key, String defaultSelector) {
        performWithHealing(page, key, defaultSelector, loc -> {
            try {
                loc.click();
            } catch (Exception e) {
                loc.click(new com.microsoft.playwright.Locator.ClickOptions().setForce(true));
            }
        });
    }

    public static void fill(Page page, String key, String defaultSelector, String value) {
        performWithHealing(page, key, defaultSelector, loc -> loc.fill(value));
    }

    public static void selectOption(Page page, String key, String defaultSelector, String value) {
        performWithHealing(page, key, defaultSelector, loc -> loc.selectOption(value));
    }

    public static void check(Page page, String key, String defaultSelector) {
        performWithHealing(page, key, defaultSelector, Locator::check);
    }

    public static void uncheck(Page page, String key, String defaultSelector) {
        performWithHealing(page, key, defaultSelector, Locator::uncheck);
    }

    public static void press(Page page, String key, String defaultSelector, String value) {
        performWithHealing(page, key, defaultSelector, loc -> loc.press(value));
    }

    public static void hover(Page page, String key, String defaultSelector) {
        // Extract human-readable text from selector for JS fallback
        String hoverText = defaultSelector;
        if (hoverText.startsWith("text=")) {
            hoverText = hoverText.substring(5).trim();
        } else if (hoverText.contains("normalize-space()='") || hoverText.contains("contains(.,")) {
            // Extract text between quotes
            int q1 = hoverText.lastIndexOf("'");
            int q0 = hoverText.lastIndexOf("'", q1 - 1);
            if (q0 >= 0 && q1 > q0) hoverText = hoverText.substring(q0 + 1, q1);
        }
        final String textForJS = hoverText;

        performWithHealing(page, key, defaultSelector, loc -> {
            // Strategy 1: Standard Playwright hover (scroll + hover)
            try {
                loc.first().scrollIntoViewIfNeeded();
                loc.first().hover();
                return;
            } catch (Exception ignored) {}

            // Strategy 2: Force hover (bypasses visibility check)
            try {
                loc.first().hover(new com.microsoft.playwright.Locator.HoverOptions().setForce(true));
                System.out.println("[Hover] Force hover succeeded for: " + key);
                return;
            } catch (Exception ignored) {}

            // Strategy 3: JS mouseover — works for collapsed nav/sidebars
            // Finds the element by text and dispatches mouseover + mouseenter events
            try {
                String js = "(text) => {" +
                    "var els = Array.from(document.querySelectorAll('button,a,li,[role]'));" +
                    "var el = els.find(function(e){ return e.textContent && e.textContent.trim().indexOf(text) !== -1; });" +
                    "if (el) {" +
                    "  el.dispatchEvent(new MouseEvent('mouseover', {bubbles:true,cancelable:true}));" +
                    "  el.dispatchEvent(new MouseEvent('mouseenter', {bubbles:true,cancelable:true}));" +
                    "}" +
                    "}";
                page.evaluate(js, textForJS);
                System.out.println("[Hover] JS mouseover dispatched for: " + key + " (text='" + textForJS + "')");
                // Give the page time to react to the hover event before next action
                page.waitForTimeout(500);
            } catch (Exception e3) {
                System.err.println("[Hover] All hover strategies failed for '" + key + "': " + e3.getMessage());
                throw new RuntimeException("Hover failed for " + key, e3);
            }
        });
    }



    private static void persistHealedSelector(String key, String healedSelector) {
        try {
            String projectRoot = System.getProperty("user.dir");
            java.io.File poDir = new java.io.File(projectRoot, "src/main/java/pageobjects");
            if (!poDir.exists()) {
                return;
            }
            java.io.File[] files = poDir.listFiles((dir, name) -> name.endsWith(".java"));
            if (files == null) return;
            for (java.io.File file : files) {
                String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(public\\s+static\\s+final\\s+String\\s+" + java.util.regex.Pattern.quote(key) + "\\s*=\\s*\")([^\"]+)(\";)"
                );
                java.util.regex.Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    String newContent = matcher.replaceFirst("$1" + java.util.regex.Matcher.quoteReplacement(healedSelector) + "$3");
                    java.nio.file.Files.write(file.toPath(), newContent.getBytes());
                    System.out.println("[Self-Healing Persistent] Auto-saved healed selector for '" + key + "' in " + file.getName() + " -> \"" + healedSelector + "\"");
                }
            }
        } catch (Exception e) {
            System.err.println("[Self-Healing Warning] Failed to save healed selector: " + e.getMessage());
        }
    }
}
