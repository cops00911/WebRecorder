package recorder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Exports the recorded actions into a Page Object Model (POM) structure using native Playwright Java:
 * 1. src/main/java/pageobjects/<PageObjectName>.java (stores locator constants only)
 * 2. src/test/java/recorder/GeneratedWebTest.java (uses constants and executes native Playwright actions)
 */
public class TestScriptExporter {

    private final String projectRoot;
    private final String browserType; // chromium | firefox | webkit
    private final String pageObjectName; // class name for the page object

    public TestScriptExporter(String projectRoot, String browserType, String pageObjectName) {
        this.projectRoot = projectRoot;
        this.browserType = browserType;
        this.pageObjectName = pageObjectName;
    }

    /**
     * Export the recorded actions.
     *
     * @param actions  list of recorded actions
     * @param startUrl the initial URL that was navigated to
     * @return path of the generated test file
     */
    public String export(List<ActionModel> actions, String startUrl) throws IOException {
        // Auto-check and sanitize recorded actions to remove duplicate clicks, premature form submits, and redundant input focuses
        actions = RecorderEngine.optimizeActions(actions);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        // Map selectors to unique, clean constant names
        Map<String, String> selectorToVarName = new LinkedHashMap<>();
        Set<String> allocatedVarNames = new HashSet<>();

        for (ActionModel action : actions) {
            if (action.type == ActionType.NAVIGATE || action.type == ActionType.COMMENT) {
                continue;
            }
            String rawSelector = getRawSelector(action.locator);
            if (!selectorToVarName.containsKey(rawSelector)) {
                String candidate = deriveVariableName(rawSelector);
                String finalName = candidate;
                int counter = 1;
                while (allocatedVarNames.contains(finalName)) {
                    finalName = candidate + "_" + counter;
                    counter++;
                }
                allocatedVarNames.add(finalName);
                selectorToVarName.put(rawSelector, finalName);
            }
        }

        // Extract parameters for Data-Driven testing (INPUT and SELECT actions)
        List<String> paramNames = new ArrayList<>();
        Map<String, String> dataJsonMap = new LinkedHashMap<>();
        Set<String> usedParamNames = new HashSet<>();
        Map<ActionModel, String> actionToParam = new HashMap<>();

        for (ActionModel a : actions) {
            if (a.type == ActionType.INPUT || a.type == ActionType.SELECT) {
                String varName = selectorToVarName.get(getRawSelector(a.locator));
                String paramName = toLowerCamelCase(varName);
                String baseParam = paramName;
                int counter = 1;
                while (usedParamNames.contains(paramName)) {
                    paramName = baseParam + "_" + counter++;
                }
                usedParamNames.add(paramName);
                paramNames.add(paramName);
                dataJsonMap.put(paramName, a.value);
                actionToParam.put(a, paramName);
            }
        }

        boolean hasData = !dataJsonMap.isEmpty();
        String relativeDataPath = "src/test/resources/data/" + pageObjectName + "Data.json";
        if (hasData) {
            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("[\n  {\n");
            int idx = 0;
            for (Map.Entry<String, String> entry : dataJsonMap.entrySet()) {
                jsonBuilder.append("    \"").append(entry.getKey()).append("\": \"").append(esc(entry.getValue())).append("\"");
                if (idx < dataJsonMap.size() - 1) {
                    jsonBuilder.append(",");
                }
                jsonBuilder.append("\n");
                idx++;
            }
            jsonBuilder.append("  }\n]");
            String fullDataPath = projectRoot + "/" + relativeDataPath;
            writeFile(fullDataPath, jsonBuilder.toString());
            System.out.println("  📄 Test Data JSON saved to:\n     " + fullDataPath);
        }

        // 1. Generate Page Object (constants only): <PageObjectName>.java
        String pageObjectContent = buildPageObjectContent(selectorToVarName);
        String pageObjectPath = projectRoot + "/src/main/java/pageobjects/" + pageObjectName + ".java";
        writeFile(pageObjectPath, pageObjectContent);

        // 2. Generate Test: GeneratedWebTest.java
        String launchMethod;
        switch (browserType.toLowerCase()) {
            case "firefox":
                launchMethod = "playwright.firefox()";
                break;
            case "webkit":
                launchMethod = "playwright.webkit()";
                break;
            default:
                launchMethod = "playwright.chromium()";
                break;
        }

        StringBuilder testBody = new StringBuilder();
        for (ActionModel a : actions) {
            if (a.type == ActionType.NAVIGATE) {
                testBody.append("        page.navigate(\"").append(esc(a.locator)).append("\");\n");
            } else if (a.type == ActionType.COMMENT) {
                if (a.value.startsWith("==")) {
                    testBody.append("\n");
                }
                testBody.append("        // ").append(a.value).append("\n");
                if (a.value.startsWith("==")) {
                    testBody.append("\n");
                }
            } else {
                String varName = selectorToVarName.get(getRawSelector(a.locator));
                String fullConstantName = pageObjectName + "." + varName;
                String keyStr = "\"" + varName + "\"";
                switch (a.type) {
                    case CLICK:
                        testBody.append("        SelfHealingLocator.click(page, ").append(keyStr).append(", ").append(fullConstantName).append(");\n");
                        break;
                    case INPUT:
                        String pNameIn = actionToParam.get(a);
                        if (pNameIn != null) {
                            testBody.append("        SelfHealingLocator.fill(page, ").append(keyStr).append(", ").append(fullConstantName).append(", ").append(pNameIn).append(");\n");
                        } else {
                            testBody.append("        SelfHealingLocator.fill(page, ").append(keyStr).append(", ").append(fullConstantName).append(", \"").append(esc(a.value)).append("\");\n");
                        }
                        break;
                    case SELECT:
                        String pNameSel = actionToParam.get(a);
                        if (pNameSel != null) {
                            testBody.append("        SelfHealingLocator.selectOption(page, ").append(keyStr).append(", ").append(fullConstantName).append(", ").append(pNameSel).append(");\n");
                        } else {
                            testBody.append("        SelfHealingLocator.selectOption(page, ").append(keyStr).append(", ").append(fullConstantName).append(", \"").append(esc(a.value)).append("\");\n");
                        }
                        break;
                    case CHECK:
                        testBody.append("        SelfHealingLocator.check(page, ").append(keyStr).append(", ").append(fullConstantName).append(");\n");
                        break;
                    case UNCHECK:
                        testBody.append("        SelfHealingLocator.uncheck(page, ").append(keyStr).append(", ").append(fullConstantName).append(");\n");
                        break;
                    case PRESS:
                        testBody.append("        SelfHealingLocator.press(page, ").append(keyStr).append(", ").append(fullConstantName).append(", \"").append(esc(a.value)).append("\");\n");
                        break;
                    case HOVER:
                        testBody.append("        SelfHealingLocator.hover(page, ").append(keyStr).append(", ").append(fullConstantName).append(");\n");
                        break;
                }
            }
        }

        StringBuilder dataProviderBlock = new StringBuilder();
        StringBuilder methodParams = new StringBuilder();
        String testAnnotation = "    @Test\n";

        if (hasData) {
            dataProviderBlock.append("    @DataProvider(name = \"testData\")\n");
            dataProviderBlock.append("    public Object[][] getTestData() {\n");
            dataProviderBlock.append("        return utils.JsonDataReader.loadData(\"").append(relativeDataPath).append("\");\n");
            dataProviderBlock.append("    }\n\n");

            testAnnotation = "    @Test(dataProvider = \"testData\")\n";
            for (int i = 0; i < paramNames.size(); i++) {
                methodParams.append("String ").append(paramNames.get(i));
                if (i < paramNames.size() - 1) {
                    methodParams.append(", ");
                }
            }
        }

        String testContent = "package recorder;\n\n" +
                "// ============================================================\n" +
                "// Generated by Web Recorder — " + timestamp + "\n" +
                "// Start URL: " + startUrl + "\n" +
                "// Browser:   " + browserType + "\n" +
                "// Data File: " + (hasData ? relativeDataPath : "None") + "\n" +
                "// ============================================================\n\n" +
                "import com.microsoft.playwright.*;\n" +
                "import org.testng.annotations.*;\n" +
                "import pageobjects." + pageObjectName + ";\n" +
                "import utils.SelfHealingLocator;\n\n" +
                "public class GeneratedWebTest {\n\n" +
                "    private Playwright playwright;\n" +
                "    private Browser browser;\n" +
                "    private BrowserContext context;\n" +
                "    private Page page;\n\n" +
                "    @BeforeClass\n" +
                "    public void setUp() {\n" +
                "        playwright = Playwright.create();\n" +
                "        browser = " + launchMethod + ".launch(\n" +
                "            new BrowserType.LaunchOptions().setHeadless(false).setArgs(java.util.Arrays.asList(\"--start-maximized\"))\n" +
                "        );\n" +
                "        context = browser.newContext(\n" +
                "            new Browser.NewContextOptions().setViewportSize(null)\n" +
                "        );\n" +
                "        page = context.newPage();\n" +
                "    }\n\n" +
                dataProviderBlock.toString() +
                testAnnotation +
                "    public void testRecordedFlow(" + methodParams.toString() + ") {\n" +
                testBody.toString() +
                "    }\n\n" +
                "    @AfterClass\n" +
                "    public void tearDown() {\n" +
                "        if (page    != null) page.close();\n" +
                "        if (context != null) context.close();\n" +
                "        if (browser != null) browser.close();\n" +
                "        if (playwright != null) playwright.close();\n" +
                "    }\n" +
                "}\n";

        String testPath = projectRoot + "/src/test/java/recorder/GeneratedWebTest.java";
        writeFile(testPath, testContent);

        return testPath;
    }

    private String buildPageObjectContent(Map<String, String> selectorToVarName) {
        StringBuilder sb = new StringBuilder();
        sb.append("package pageobjects;\n\n");
        sb.append("import com.microsoft.playwright.Page;\n\n");
        sb.append("public class ").append(pageObjectName).append(" {\n");
        sb.append("    private Page page;\n\n");
        sb.append("    public ").append(pageObjectName).append("(Page page) {\n");
        sb.append("        this.page = page;\n");
        sb.append("    }\n\n");

        sb.append("    // ===== LOCATORS =====\n");
        for (Map.Entry<String, String> entry : selectorToVarName.entrySet()) {
            sb.append("    public static final String ").append(entry.getValue())
                    .append(" = \"").append(esc(entry.getKey())).append("\";\n");
        }

        sb.append("}\n");
        return sb.toString();
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

    private static String deriveVariableName(String selector) {
        if (selector == null || selector.isEmpty()) {
            return "ELEMENT";
        }

        // 0. Handle Playwright text= shorthand (e.g. text=Login → LOGIN_BUTTON)
        if (selector.startsWith("text=")) {
            String textVal = selector.substring(5).trim();
            String cleaned = cleanName(textVal);
            // Guess a meaningful suffix from the text
            String lower = textVal.toLowerCase();
            if (lower.contains("login") || lower.contains("sign in") || lower.contains("submit")) {
                return cleaned + "_BUTTON";
            } else if (lower.contains("link") || lower.contains("menu") || lower.contains("setup") ||
                       lower.contains("set") || lower.contains("value") || lower.contains("module")) {
                return cleaned + "_LINK";
            } else {
                return cleaned + "_BUTTON";
            }
        }

        // 1. Try to find text if it's an XPath like //button[normalize-space()='Login']
        if (selector.contains("normalize-space()='")) {
            int start = selector.indexOf("normalize-space()='") + "normalize-space()='".length();
            int end = selector.indexOf("'", start);
            if (end > start) {
                return cleanName(selector.substring(start, end)) + "_" + getTagName(selector);
            }
        }

        // 2. Try to find normalize-space contains (e.g. xpath=//*[contains(normalize-space(), 'Business Setup')])
        if (selector.contains("contains(normalize-space(),")) {
            int q = selector.indexOf("contains(normalize-space(),");
            int start = selector.indexOf("'", q) + 1;
            int end = selector.indexOf("'", start);
            if (end > start) {
                return cleanName(selector.substring(start, end)) + "_" + getTagName(selector);
            }
        }

        // 3. Try to find text contains
        if (selector.contains("contains(text(),'")) {
            int start = selector.indexOf("contains(text(),'") + "contains(text(),'".length();
            int end = selector.indexOf("'", start);
            if (end > start) {
                return cleanName(selector.substring(start, end)) + "_" + getTagName(selector);
            }
        }
        if (selector.contains("contains(text(), '")) {
            int start = selector.indexOf("contains(text(), '") + "contains(text(), '".length();
            int end = selector.indexOf("'", start);
            if (end > start) {
                return cleanName(selector.substring(start, end)) + "_" + getTagName(selector);
            }
        }

        // 4. Try to extract ID (e.g. #partner_id)
        if (selector.startsWith("#")) {
            return cleanName(selector.substring(1));
        }

        // 5. Try to extract attribute values like name, placeholder, data-testid, aria-label
        String[] attrNames = {"name=", "placeholder=", "data-testid=", "aria-label="};
        for (String attr : attrNames) {
            if (selector.contains(attr)) {
                int start = selector.indexOf(attr) + attr.length();
                // skip quote if present
                char quote = selector.charAt(start);
                if (quote == '\'' || quote == '"') {
                    start++;
                }
                int end = start;
                while (end < selector.length() && selector.charAt(end) != '\'' && selector.charAt(end) != '"' && selector.charAt(end) != ']') {
                    end++;
                }
                if (end > start) {
                    return cleanName(selector.substring(start, end));
                }
            }
        }

        // 6. Fallback: clean the selector
        String fallback = selector.replaceAll("[^a-zA-Z0-9]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "").toUpperCase();
        if (!fallback.isEmpty() && fallback.length() >= 3) {
            return fallback.length() > 40 ? fallback.substring(0, 40) : fallback;
        }
        return "ELEMENT_" + Math.abs(selector.hashCode() % 1000);
    }

    private static String getTagName(String selector) {
        String s = selector.toLowerCase();
        if (s.contains("//button")) return "BUTTON";
        if (s.contains("//a")) return "LINK";
        if (s.contains("//input")) return "INPUT";
        if (s.contains("//select")) return "SELECT";
        if (s.contains("//*") || s.contains("text=")) {
            // Infer type from the text content
            String lower = selector.toLowerCase();
            if (lower.contains("button") || lower.contains("submit") || lower.contains("login") ||
                lower.contains("add") || lower.contains("create") || lower.contains("save") ||
                lower.contains("delete") || lower.contains("confirm") || lower.contains("cancel")) {
                return "BUTTON";
            } else if (lower.contains("link") || lower.contains("setup") || lower.contains("set") ||
                       lower.contains("menu") || lower.contains("nav") || lower.contains("module")) {
                return "LINK";
            }
            return "BUTTON";
        }
        return "ELEMENT";
    }

    private static String cleanName(String name) {
        String cleaned = name.toUpperCase()
                .replaceAll("[^A-Z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (cleaned.isEmpty()) {
            cleaned = "ELEMENT";
        }
        return cleaned;
    }

    private static String toCamelCase(String s) {
        String[] parts = s.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    private static String toLowerCamelCase(String s) {
        String cc = toCamelCase(s);
        if (cc.isEmpty()) return "param";
        return Character.toLowerCase(cc.charAt(0)) + cc.substring(1);
    }

    private static void writeFile(String path, String content) throws IOException {
        File file = new File(path);
        file.getParentFile().mkdirs();
        try (FileWriter fw = new FileWriter(file)) {
            fw.write(content);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
