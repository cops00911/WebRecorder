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
        this(projectRoot, browserType, pageObjectName, false);
    }

    public TestScriptExporter(String projectRoot, String browserType, String pageObjectName, boolean overwrite) {
        this.projectRoot = projectRoot;
        this.browserType = browserType;
        this.pageObjectName = overwrite ? pageObjectName : resolveUniqueName(projectRoot, pageObjectName);
    }

    public String getPageObjectName() {
        return pageObjectName;
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
        boolean inPopup = false;

        for (int i = 0; i < actions.size(); i++) {
            ActionModel a = actions.get(i);
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

                boolean triggersPopup = !a.isPopup && !inPopup && hasUpcomingPopup(actions, i + 1);

                if (triggersPopup && a.type == ActionType.CLICK) {
                    testBody.append("        // Wait for popup window triggered by click\n");
                    testBody.append("        Page popup = page.waitForPopup(() -> {\n");
                    testBody.append("            SelfHealingLocator.click(page, ").append(keyStr).append(", ").append(fullConstantName).append(");\n");
                    testBody.append("        });\n");
                    testBody.append("        popup.waitForLoadState();\n\n");
                    inPopup = true;
                    continue;
                }

                if (a.isPopup && !inPopup) {
                    testBody.append("        Page popup = page.waitForPopup(() -> {});\n");
                    testBody.append("        popup.waitForLoadState();\n\n");
                    inPopup = true;
                }

                String targetPage = a.isPopup ? "popup" : "page";

                switch (a.type) {
                    case CLICK:
                        testBody.append("        SelfHealingLocator.click(").append(targetPage).append(", ").append(keyStr).append(", ").append(fullConstantName).append(");\n");
                        break;
                    case INPUT:
                        String pNameIn = actionToParam.get(a);
                        if (pNameIn != null) {
                            testBody.append("        SelfHealingLocator.fill(").append(targetPage).append(", ").append(keyStr).append(", ").append(fullConstantName).append(", ").append(pNameIn).append(");\n");
                        } else {
                            testBody.append("        SelfHealingLocator.fill(").append(targetPage).append(", ").append(keyStr).append(", ").append(fullConstantName).append(", \"").append(esc(a.value)).append("\");\n");
                        }
                        break;
                    case SELECT:
                        String pNameSel = actionToParam.get(a);
                        if (pNameSel != null) {
                            testBody.append("        SelfHealingLocator.selectOption(").append(targetPage).append(", ").append(keyStr).append(", ").append(fullConstantName).append(", ").append(pNameSel).append(");\n");
                        } else {
                            testBody.append("        SelfHealingLocator.selectOption(").append(targetPage).append(", ").append(keyStr).append(", ").append(fullConstantName).append(", \"").append(esc(a.value)).append("\");\n");
                        }
                        break;
                    case CHECK:
                        testBody.append("        SelfHealingLocator.check(").append(targetPage).append(", ").append(keyStr).append(", ").append(fullConstantName).append(");\n");
                        break;
                    case UNCHECK:
                        testBody.append("        SelfHealingLocator.uncheck(").append(targetPage).append(", ").append(keyStr).append(", ").append(fullConstantName).append(");\n");
                        break;
                    case PRESS:
                        testBody.append("        SelfHealingLocator.press(").append(targetPage).append(", ").append(keyStr).append(", ").append(fullConstantName).append(", \"").append(esc(a.value)).append("\");\n");
                        break;
                    case HOVER:
                        testBody.append("        SelfHealingLocator.hover(").append(targetPage).append(", ").append(keyStr).append(", ").append(fullConstantName).append(");\n");
                        break;
                }

                if (inPopup && (i + 1 == actions.size() || !hasUpcomingPopup(actions, i + 1))) {
                    testBody.append("\n        // Wait for popup to complete and return to main page\n");
                    testBody.append("        try {\n");
                    testBody.append("            popup.waitForClose(new Page.WaitForCloseOptions().setTimeout(15000), () -> {});\n");
                    testBody.append("        } catch (Exception ignored) {}\n\n");
                    inPopup = false;
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
                "// Generated by Web Recorder - " + timestamp + "\n" +
                "// Start URL: " + startUrl + "\n" +
                "// Browser:   " + browserType + "\n" +
                "// Data File: " + (hasData ? relativeDataPath : "None") + "\n" +
                "// ============================================================\n\n" +
                "import com.microsoft.playwright.*;\n" +
                "import org.testng.annotations.*;\n" +
                "import pageobjects." + pageObjectName + ";\n" +
                "import utils.SelfHealingLocator;\n\n" +
                "public class " + getJavaTestClassName() + " {\n\n" +
                "    private Playwright playwright;\n" +
                "    private Browser browser;\n" +
                "    private BrowserContext context;\n" +
                "    private Page page;\n\n" +
                "    @BeforeClass\n" +
                "    public void setUp() {\n" +
                "        java.util.Map<String, String> env = new java.util.HashMap<>(System.getenv());\n" +
                "        env.put(\"NODE_TLS_REJECT_UNAUTHORIZED\", \"0\");\n" +
                "        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));\n" +
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

        String testPath = projectRoot + "/src/test/java/recorder/" + getJavaTestClassName() + ".java";
        writeFile(testPath, testContent);

        // 3. Generate TypeScript output (dual output matching recorded name)
        String tsTestPath = exportTypeScript(actions, startUrl, timestamp, selectorToVarName, actionToParam, paramNames, hasData, relativeDataPath);
        System.out.println("  📘 TypeScript test saved to:\n     " + tsTestPath);

        return testPath;
    }

    private String getTsPageClassName() {
        return pageObjectName.endsWith("Page") ? pageObjectName : pageObjectName + "Page";
    }

    private String getJavaTestClassName() {
        if ("GeneratedWebPage".equalsIgnoreCase(pageObjectName)) {
            return "GeneratedWebTest";
        }
        return pageObjectName.endsWith("Test") ? pageObjectName : pageObjectName + "Test";
    }

    private String getTsSpecFileName() {
        return pageObjectName;
    }

    // ─────────────────────── TypeScript Generation ──────────────────────────

    private String exportTypeScript(
            List<ActionModel> actions,
            String startUrl,
            String timestamp,
            Map<String, String> selectorToVarName,
            Map<ActionModel, String> actionToParam,
            List<String> paramNames,
            boolean hasData,
            String javaRelativeDataPath) throws IOException {

        String tsRoot = projectRoot + "/typescript-tests";
        String tsPageClass = getTsPageClassName();
        String tsSpecName = getTsSpecFileName();

        // 1. Raw Recording Backup: typescript-tests/recordings/<Name>.recorded.ts
        String rawRecordingPath = tsRoot + "/recordings/" + pageObjectName + ".recorded.ts";
        writeFile(rawRecordingPath, buildRawRecordedSpec(actions, startUrl, timestamp, selectorToVarName));

        // 2. Framework Page Object: typescript-tests/pages/<Name>Page.ts
        String tsPagePath = tsRoot + "/pages/" + tsPageClass + ".ts";
        writeFile(tsPagePath, buildTsPageObject(selectorToVarName, timestamp, tsPageClass));

        // 3. Framework Test Data: typescript-tests/fixtures/<Name>Data.json
        String tsFixturePath = tsRoot + "/fixtures/" + pageObjectName + "Data.json";
        if (hasData) {
            String javaDataFull = projectRoot + "/" + javaRelativeDataPath;
            java.io.File srcFile = new java.io.File(javaDataFull);
            if (srcFile.exists()) {
                String dataContent = new String(java.nio.file.Files.readAllBytes(srcFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                writeFile(tsFixturePath, dataContent);
            }
        }

        // 4. Framework Spec File: typescript-tests/tests/<Name>.spec.ts
        String tsSpecPath = tsRoot + "/tests/" + tsSpecName + ".spec.ts";
        writeFile(tsSpecPath, buildTsTestSpec(actions, startUrl, timestamp, selectorToVarName, actionToParam, paramNames, hasData, tsPageClass));

        // 5. Scaffold files (only write if not already present)
        String pkgPath = tsRoot + "/package.json";
        if (!new java.io.File(pkgPath).exists()) {
            writeFile(pkgPath, buildPackageJson());
        }
        String tscPath = tsRoot + "/tsconfig.json";
        if (!new java.io.File(tscPath).exists()) {
            writeFile(tscPath, buildTsConfig());
        }
        String pwConfigPath = tsRoot + "/playwright.config.ts";
        if (!new java.io.File(pwConfigPath).exists()) {
            writeFile(pwConfigPath, buildPlaywrightConfig());
        }
        String batPath = tsRoot + "/run-tests.bat";
        if (!new java.io.File(batPath).exists()) {
            writeFile(batPath, "@echo off\r\nset \"PATH=%USERPROFILE%\\tools\\nodejs\\node-v22.16.0-win-x64;%PATH%\"\r\nset \"NODE_TLS_REJECT_UNAUTHORIZED=0\"\r\ncall npx playwright test %*\r\n");
        }

        System.out.println("  📁 Raw recording saved to:\n     " + rawRecordingPath);
        System.out.println("  🏛️  Framework Page Object :\n     " + tsPagePath);
        return tsSpecPath;
    }

    private String buildRawRecordedSpec(
            List<ActionModel> actions,
            String startUrl,
            String timestamp,
            Map<String, String> selectorToVarName) {

        StringBuilder sb = new StringBuilder();
        sb.append("// ============================================================\n");
        sb.append("// Raw WebRecorder Output\n");
        sb.append("// Recorded:  ").append(timestamp).append("\n");
        sb.append("// Start URL: ").append(startUrl).append("\n");
        sb.append("// Browser:   ").append(browserType).append("\n");
        sb.append("// Name:      ").append(pageObjectName).append("\n");
        sb.append("// ============================================================\n\n");
        sb.append("import { test } from '@playwright/test';\n\n");

        sb.append("// Raw captured element selectors\n");
        sb.append("export const Locators = {\n");
        for (Map.Entry<String, String> entry : selectorToVarName.entrySet()) {
            sb.append("  ").append(entry.getValue()).append(": '").append(entry.getKey().replace("'", "\\'")).append("',\n");
        }
        sb.append("};\n\n");

        sb.append("test('raw recorded flow - ").append(pageObjectName).append("', async ({ page }) => {\n");
        boolean inPopup = false;
        for (int i = 0; i < actions.size(); i++) {
            ActionModel a = actions.get(i);
            if (a.type == ActionType.NAVIGATE) {
                sb.append("  await page.goto('").append(a.locator.replace("'", "\\'")).append("');\n");
            } else if (a.type == ActionType.COMMENT) {
                sb.append("  // ").append(a.value).append("\n");
            } else {
                String varName = selectorToVarName.get(getRawSelector(a.locator));
                String locExpr = "Locators." + varName;

                boolean triggersPopup = !a.isPopup && !inPopup && hasUpcomingPopup(actions, i + 1);

                if (triggersPopup && a.type == ActionType.CLICK) {
                    sb.append("  const [popup] = await Promise.all([\n");
                    sb.append("    page.waitForEvent('popup'),\n");
                    sb.append("    page.locator(").append(locExpr).append(").click(),\n");
                    sb.append("  ]);\n");
                    sb.append("  await popup.waitForLoadState('domcontentloaded');\n");
                    inPopup = true;
                    continue;
                }

                if (a.isPopup && !inPopup) {
                    sb.append("  const popup = await page.waitForEvent('popup');\n");
                    sb.append("  await popup.waitForLoadState('domcontentloaded');\n");
                    inPopup = true;
                }

                String target = a.isPopup ? "popup" : "page";

                switch (a.type) {
                    case CLICK:
                        sb.append("  await ").append(target).append(".locator(").append(locExpr).append(").click();\n");
                        break;
                    case INPUT:
                        sb.append("  await ").append(target).append(".locator(").append(locExpr).append(").fill('").append(esc(a.value)).append("');\n");
                        break;
                    case SELECT:
                        sb.append("  await ").append(target).append(".locator(").append(locExpr).append(").selectOption('").append(esc(a.value)).append("');\n");
                        break;
                    case CHECK:
                        sb.append("  await ").append(target).append(".locator(").append(locExpr).append(").check();\n");
                        break;
                    case UNCHECK:
                        sb.append("  await ").append(target).append(".locator(").append(locExpr).append(").uncheck();\n");
                        break;
                    case PRESS:
                        sb.append("  await ").append(target).append(".locator(").append(locExpr).append(").press('").append(esc(a.value)).append("');\n");
                        break;
                    case HOVER:
                        sb.append("  await ").append(target).append(".locator(").append(locExpr).append(").hover();\n");
                        break;
                }

                if (inPopup && (i + 1 == actions.size() || !hasUpcomingPopup(actions, i + 1))) {
                    sb.append("  try { await popup.waitForEvent('close', { timeout: 15000 }); } catch {}\n");
                    sb.append("  await page.waitForLoadState('networkidle');\n");
                    inPopup = false;
                }
            }
        }
        sb.append("});\n");
        return sb.toString();
    }

    private String buildTsPageObject(Map<String, String> selectorToVarName, String timestamp, String tsPageClass) {
        StringBuilder sb = new StringBuilder();
        sb.append("// ============================================================\n");
        sb.append("// Generated by Web Recorder - ").append(timestamp).append("\n");
        sb.append("// ============================================================\n\n");
        sb.append("import { Page } from '@playwright/test';\n");
        sb.append("import { BasePage } from '../framework/base/BasePage';\n\n");
        sb.append("export class ").append(tsPageClass).append(" extends BasePage {\n");
        for (Map.Entry<String, String> entry : selectorToVarName.entrySet()) {
            sb.append("  static readonly ").append(entry.getValue())
              .append(" = '").append(entry.getKey().replace("'", "\\'")).append("';\n");
        }
        sb.append("\n");
        sb.append("  constructor(page: Page) {\n");
        sb.append("    super(page);\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String buildTsTestSpec(
            List<ActionModel> actions,
            String startUrl,
            String timestamp,
            Map<String, String> selectorToVarName,
            Map<ActionModel, String> actionToParam,
            List<String> paramNames,
            boolean hasData,
            String tsPageClass) {

        StringBuilder sb = new StringBuilder();
        String pageVar = toLowerCamelCase(tsPageClass);

        sb.append("// ============================================================\n");
        sb.append("// Generated by Web Recorder - ").append(timestamp).append("\n");
        sb.append("// Start URL: ").append(startUrl).append("\n");
        sb.append("// Browser:   ").append(browserType).append("\n");
        sb.append("// Framework: Playwright TypeScript POM + TestNG\n");
        sb.append("// ============================================================\n\n");
        sb.append("import { test, expect } from '../framework/base/BaseTest';\n");
        sb.append("import { ").append(tsPageClass).append(" } from '../pages/").append(tsPageClass).append("';\n");

        File pagesDir = new File(projectRoot, "typescript-tests/pages");
        String detectedLoginClass = null;
        String detectedLoginData = null;

        if (pagesDir.exists() && pagesDir.isDirectory()) {
            File[] files = pagesDir.listFiles((d, name) -> name.endsWith("Page.ts") && (name.contains("Login") || name.contains("Auth")));
            if (files != null && files.length > 0) {
                File chosen = null;
                for (File f : files) {
                    if (f.getName().equals("LoginPage.ts")) { chosen = f; break; }
                }
                if (chosen == null) {
                    for (File f : files) {
                        if (f.getName().equals("AssetLoginPage.ts")) { chosen = f; break; }
                    }
                }
                if (chosen == null) chosen = files[0];

                String className = chosen.getName().replace(".ts", "");
                if (!className.equals(tsPageClass)) {
                    detectedLoginClass = className;
                    String baseName = className.endsWith("Page") ? className.substring(0, className.length() - 4) : className;
                    detectedLoginData = baseName + "Data.json";
                }
            }
        }

        if (detectedLoginClass != null) {
            sb.append("import { ").append(detectedLoginClass).append(" } from '../pages/").append(detectedLoginClass).append("';\n");
            if (!hasData) {
                sb.append("import { DataProvider } from '../framework/utils/DataProvider';\n");
            }
            sb.append("\n// Automatically execute Login flow before running dashboard actions\n");
            sb.append("test.beforeEach(async ({ page }) => {\n");
            sb.append("  const loginPage = new ").append(detectedLoginClass).append("(page);\n");
            sb.append("  const [credentials] = DataProvider.loadJson<any>('").append(detectedLoginData).append("');\n");
            sb.append("  await loginPage.loginWithMicrosoft(\n");
            sb.append("    'https://assetmanagementqa.rishabhsoft.com/',\n");
            sb.append("    credentials?.loginfmt,\n");
            sb.append("    credentials?.passwd\n");
            sb.append("  );\n");
            sb.append("});\n\n");
        }

        if (hasData) {
            sb.append("import { DataProvider } from '../framework/utils/DataProvider';\n\n");
            sb.append("const testData = DataProvider.loadJson<any>('").append(pageObjectName).append("Data.json');\n\n");

            StringBuilder destructure = new StringBuilder("{ ");
            for (int i = 0; i < paramNames.size(); i++) {
                destructure.append(paramNames.get(i));
                if (i < paramNames.size() - 1) destructure.append(", ");
            }
            destructure.append(" }");
            sb.append("for (const ").append(destructure).append(" of testData) {\n");
            String firstParam = paramNames.isEmpty() ? "test" : paramNames.get(0);
            sb.append("  test(`testRecordedFlow - ${").append(firstParam).append("}`, async ({ page }) => {\n");
            sb.append("    const ").append(pageVar).append(" = new ").append(tsPageClass).append("(page);\n\n");
            sb.append(buildTsTestBody(actions, selectorToVarName, actionToParam, pageVar, tsPageClass, "    "));
            sb.append("  });\n");
            sb.append("}\n");
        } else {
            sb.append("\n");
            sb.append("test('testRecordedFlow', async ({ page }) => {\n");
            sb.append("  const ").append(pageVar).append(" = new ").append(tsPageClass).append("(page);\n\n");
            sb.append(buildTsTestBody(actions, selectorToVarName, actionToParam, pageVar, tsPageClass, "  "));
            sb.append("});\n");
        }

        return sb.toString();
    }

    private String buildTsTestBody(
            List<ActionModel> actions,
            Map<String, String> selectorToVarName,
            Map<ActionModel, String> actionToParam,
            String pageVar,
            String pageClass,
            String indent) {

        StringBuilder sb = new StringBuilder();
        boolean inPopup = false;

        for (int i = 0; i < actions.size(); i++) {
            ActionModel a = actions.get(i);
            if (a.type == ActionType.NAVIGATE) {
                sb.append(indent).append("await ").append(pageVar).append(".goto('").append(a.locator.replace("'", "\\'")).append("');\n");
            } else if (a.type == ActionType.COMMENT) {
                if (a.value.startsWith("==")) sb.append("\n");
                sb.append(indent).append("// ").append(a.value).append("\n");
                if (a.value.startsWith("==")) sb.append("\n");
            } else {
                String varName = selectorToVarName.get(getRawSelector(a.locator));
                String sel = pageClass + "." + varName;
                String keyStr = "\"" + varName + "\"";

                boolean triggersPopup = !a.isPopup && !inPopup && hasUpcomingPopup(actions, i + 1);

                if (triggersPopup && a.type == ActionType.CLICK) {
                    sb.append("\n").append(indent).append("// Wait for popup window triggered by click\n");
                    sb.append(indent).append("const [popup] = await Promise.all([\n");
                    sb.append(indent).append("  page.waitForEvent('popup'),\n");
                    sb.append(indent).append("  ").append(pageVar).append(".click(").append(keyStr).append(", ").append(sel).append("),\n");
                    sb.append(indent).append("]);\n");
                    sb.append(indent).append("await popup.waitForLoadState('domcontentloaded');\n");
                    sb.append(indent).append("const popupPage = new ").append(pageClass).append("(popup);\n\n");
                    inPopup = true;
                    continue;
                }

                if (a.isPopup && !inPopup) {
                    sb.append("\n").append(indent).append("const popup = await page.waitForEvent('popup');\n");
                    sb.append(indent).append("await popup.waitForLoadState('domcontentloaded');\n");
                    sb.append(indent).append("const popupPage = new ").append(pageClass).append("(popup);\n\n");
                    inPopup = true;
                }

                String currentTarget = a.isPopup ? "popupPage" : pageVar;

                switch (a.type) {
                    case CLICK:
                        sb.append(indent).append("await ").append(currentTarget).append(".click(").append(keyStr).append(", ").append(sel).append(");\n");
                        break;
                    case INPUT:
                        String pIn = actionToParam.get(a);
                        String valIn = pIn != null ? pIn : "'" + esc(a.value) + "'";
                        sb.append(indent).append("await ").append(currentTarget).append(".fill(").append(keyStr).append(", ").append(sel).append(", ").append(valIn).append(");\n");
                        break;
                    case SELECT:
                        String pSel = actionToParam.get(a);
                        String valSel = pSel != null ? pSel : "'" + esc(a.value) + "'";
                        sb.append(indent).append("await ").append(currentTarget).append(".selectOption(").append(keyStr).append(", ").append(sel).append(", ").append(valSel).append(");\n");
                        break;
                    case CHECK:
                        sb.append(indent).append("await ").append(currentTarget).append(".check(").append(keyStr).append(", ").append(sel).append(");\n");
                        break;
                    case UNCHECK:
                        sb.append(indent).append("await ").append(currentTarget).append(".click(").append(keyStr).append(", ").append(sel).append(");\n");
                        break;
                    case PRESS:
                        sb.append(indent).append("await ").append(currentTarget).append(".press(").append(keyStr).append(", ").append(sel).append(", '").append(esc(a.value)).append("');\n");
                        break;
                    case HOVER:
                        sb.append(indent).append("await ").append(currentTarget).append(".hover(").append(keyStr).append(", ").append(sel).append(");\n");
                        break;
                }

                if (inPopup && (i + 1 == actions.size() || !hasUpcomingPopup(actions, i + 1))) {
                    sb.append("\n").append(indent).append("// Wait for popup to complete and return to main page\n");
                    sb.append(indent).append("if (!popup.isClosed()) {\n");
                    sb.append(indent).append("  try {\n");
                    sb.append(indent).append("    await popup.waitForEvent('close', { timeout: 15000 });\n");
                    sb.append(indent).append("  } catch {}\n");
                    sb.append(indent).append("}\n");
                    sb.append(indent).append("await page.waitForLoadState('domcontentloaded');\n\n");
                    inPopup = false;
                }
            }
        }
        return sb.toString();
    }

    private boolean hasUpcomingPopup(List<ActionModel> actions, int startIndex) {
        for (int j = startIndex; j < actions.size(); j++) {
            ActionModel next = actions.get(j);
            if (next.type != ActionType.COMMENT) {
                return next.isPopup;
            }
        }
        return false;
    }

    private static String buildPackageJson() {
        return "{\n" +
               "  \"name\": \"web-recorder-ts\",\n" +
               "  \"version\": \"1.0.0\",\n" +
               "  \"scripts\": {\n" +
               "    \"test\": \"npx playwright test\",\n" +
               "    \"report:allure\": \"npx allure generate allure-results --clean -o reports/allure-report && npx allure open reports/allure-report\",\n" +
               "    \"report:html\": \"npx playwright show-report reports/html-report\"\n" +
               "  },\n" +
               "  \"devDependencies\": {\n" +
               "    \"@playwright/test\": \"^1.44.0\",\n" +
               "    \"typescript\": \"^5.0.0\",\n" +
               "    \"@types/node\": \"^26.6.1\",\n" +
               "    \"allure-commandline\": \"^2.32.0\",\n" +
               "    \"allure-playwright\": \"^3.12.2\"\n" +
               "  }\n" +
               "}\n";
    }

    private static String buildTsConfig() {
        return "{\n" +
               "  \"compilerOptions\": {\n" +
               "    \"target\": \"ES2022\",\n" +
               "    \"module\": \"commonjs\",\n" +
               "    \"moduleResolution\": \"node\",\n" +
               "    \"strict\": true,\n" +
               "    \"resolveJsonModule\": true,\n" +
               "    \"esModuleInterop\": true,\n" +
               "    \"skipLibCheck\": true,\n" +
               "    \"types\": [\"node\"],\n" +
               "    \"outDir\": \"./dist\"\n" +
               "  },\n" +
               "  \"include\": [\"**/*.ts\"],\n" +
               "  \"exclude\": [\"node_modules\", \"dist\"]\n" +
               "}\n";
    }

    private String buildPlaywrightConfig() {
        String browserProject;
        switch (browserType.toLowerCase()) {
            case "firefox": browserProject = "firefox"; break;
            case "webkit":  browserProject = "webkit";  break;
            default:        browserProject = "chromium"; break;
        }
        return "import { defineConfig } from '@playwright/test';\n\n" +
               "export default defineConfig({\n" +
               "  testDir: './tests',\n" +
               "  timeout: 30000,\n" +
               "  reporter: [\n" +
               "    ['list'],\n" +
               "    ['html', { outputFolder: 'reports/html-report', open: 'never' }],\n" +
               "    ['allure-playwright', { outputFolder: 'allure-results', detail: true }]\n" +
               "  ],\n" +
               "  use: {\n" +
               "    headless: false,\n" +
               "    screenshot: 'only-on-failure',\n" +
               "    trace: 'retain-on-failure',\n" +
               "    video: 'retain-on-failure',\n" +
               "  },\n" +
               "  projects: [\n" +
               "    { name: '" + browserProject + "', use: { browserName: '" + browserProject + "' } },\n" +
               "  ],\n" +
               "});\n";
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
        try (FileWriter fw = new FileWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
            fw.write(content);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * Checks if any generated test file or Page Object already exists for this name.
     */
    public static boolean pageObjectExists(String projectRoot, String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String cleaned = name.trim();
        String javaTestClass = "GeneratedWebPage".equalsIgnoreCase(cleaned)
                ? "GeneratedWebTest"
                : (cleaned.endsWith("Test") ? cleaned : cleaned + "Test");
        String tsPageClass = cleaned.endsWith("Page") ? cleaned : cleaned + "Page";

        File javaPO = new File(new File(projectRoot, "src/main/java/pageobjects"), cleaned + ".java");
        File javaTest = new File(new File(projectRoot, "src/test/java/recorder"), javaTestClass + ".java");
        File tsRecord = new File(new File(projectRoot, "typescript-tests/recordings"), cleaned + ".recorded.ts");
        File tsPage = new File(new File(projectRoot, "typescript-tests/pages"), tsPageClass + ".ts");
        File tsSpec = new File(new File(projectRoot, "typescript-tests/tests"), cleaned + ".spec.ts");

        return javaPO.exists() || javaTest.exists() || tsRecord.exists() || tsPage.exists() || tsSpec.exists();
    }

    /**
     * Resolves a unique name by appending 1, 2, 3... if the name already exists.
     */
    public static String resolveUniqueName(String projectRoot, String name) {
        if (name == null || name.trim().isEmpty()) {
            name = "GeneratedWebPage";
        }
        name = name.trim();
        if (!pageObjectExists(projectRoot, name)) {
            return name;
        }

        java.util.regex.Pattern p = java.util.regex.Pattern.compile("^(.*?)(\\d+)$");
        java.util.regex.Matcher m = p.matcher(name);
        String base;
        int counter;
        if (m.matches()) {
            base = m.group(1);
            if (base.isEmpty()) {
                base = "Page";
            }
            counter = Integer.parseInt(m.group(2)) + 1;
        } else {
            base = name;
            counter = 1;
        }

        String candidate = base + counter;
        while (pageObjectExists(projectRoot, candidate)) {
            counter++;
            candidate = base + counter;
        }
        return candidate;
    }
}
