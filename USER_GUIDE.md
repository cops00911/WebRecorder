# Web Recorder User Guide

This tool records browser interactions and **automatically generates both Java (TestNG) and TypeScript (Playwright Test) test files** from a single recording session.

---

## Prerequisites

Ensure you have the following installed:

| Tool | Required For | Download |
|------|-------------|----------|
| Java JDK 11+ | Running the recorder & Java tests | [adoptium.net](https://adoptium.net) |
| Apache Maven | Building and running Java tests | [maven.apache.org](https://maven.apache.org) |
| Node.js 18+ | Running TypeScript tests | [nodejs.org](https://nodejs.org) |

> **Note (No Admin?)** If you cannot install Node.js via the installer, a portable version is available in `%USERPROFILE%\tools\nodejs\node-v22.16.0-win-x64`. Add it to your PATH once:
> ```powershell
> $nodePath = "$env:USERPROFILE\tools\nodejs\node-v22.16.0-win-x64"
> [System.Environment]::SetEnvironmentVariable("Path", "$nodePath;" + [System.Environment]::GetEnvironmentVariable("Path","User"), "User")
> ```

---

## Quick Reference: Run Commands Cheat Sheet

| Task | Command | Working Directory |
|---|---|---|
| **Record Browser Actions (Dual Java + TS)** | `.\record.bat` or `mvn exec:java` | Project root (`WebRecorder-main/`) |
| **Record from typescript-tests/ folder** | `.\record.bat` or `npm run record` | `typescript-tests/` |
| **Playwright Codegen (Raw TS)** | `npx playwright codegen <url>` | `typescript-tests/` |
| **Run Java Tests (All)** | `mvn test` | Project root (`WebRecorder-main/`) |
| **Run Specific Java Test** | `mvn test -Dtest=<TestClassName>` | Project root (`WebRecorder-main/`) |
| **Run TypeScript Tests (All)** | `.\run-tests.bat` or `npm test` | `typescript-tests/` |
| **Run Specific TypeScript Spec** | `.\run-tests.bat tests/<Name>.spec.ts` | `typescript-tests/` |
| **Run in Headed Mode (Visible Browser)** | `.\run-tests.bat --headed` | `typescript-tests/` |
| **Run Interactive UI Mode** | `npx playwright test --ui` | `typescript-tests/` |
| **Run Debug Mode (Inspector)** | `npx playwright test --debug` | `typescript-tests/` |
| **View Allure Dashboard** | `.\generate-allure-report.bat` | `typescript-tests/` |
| **View Playwright HTML Report** | `.\show-html-report.bat` | `typescript-tests/` |
| **Update Visual Baselines** | `.\update-visual-baselines.bat` | `typescript-tests/` |

---

## 1. Recording Browser Actions

The Web Recorder opens a browser, records your interactions, and simultaneously generates Java and TypeScript test files.

### Launch the Recorder

#### From Project Root (`WebRecorder-main/`):
```powershell
# Option A — One-click batch runner (Recommended on Windows)
.\record.bat

# Option B — Maven command
mvn exec:java
```

#### From `typescript-tests/` directory:
```powershell
# Launch the dual Java + TypeScript recorder:
.\record.bat
# or via npm:
npm run record

# Or launch Playwright's built-in interactive code generator:
npx playwright codegen https://www.saucedemo.com
# or via npm:
npm run codegen -- https://www.saucedemo.com
```

### Prompts
1. **Resume recording? (y/N)** — choose `y` to continue an existing test or `N` for fresh recording.
2. **Enter target URL** — the page you want to test (e.g., `https://www.saucedemo.com/`)
3. **Choose browser** — `chrome` (default), `firefox`, or `webkit`
4. **Enter Page Object name** — e.g. `Login` or `Saucelogin` (default: `GeneratedWebPage`)
   > 💡 **Auto-Increment / Collision Protection**:
   > If a test or Page Object with that name already exists (e.g. `Login`), the recorder automatically numbers the new recording (`Login1`, then `Login2`, etc.) to protect existing tests from being overwritten!
5. Perform your actions in the browser
6. Type `stop` in the terminal when done

### Output (both generated automatically)

```
src/
  main/java/pageobjects/<Name>.java          ← Java Page Object (locator constants)
  test/java/recorder/<Name>Test.java         ← Java TestNG test
  test/resources/data/<Name>Data.json        ← Test data (if inputs recorded)

typescript-tests/
  recordings/<Name>.recorded.ts              ← Raw TypeScript recording backup
  pages/<Name>Page.ts                        ← TypeScript Framework Page Object
  tests/<Name>.spec.ts                       ← TypeScript Playwright Test spec
  fixtures/<Name>Data.json                   ← Test data (same as Java)
```

---

## 2. Running Java Tests (TestNG)

### Run all tests
```bash
mvn test
```

- Compiles the project and runs generated tests via TestNG.
- Browser launches in **headed** (visible) mode by default.

### Run a specific test class
```bash
mvn test -Dtest=LoginTest
```

---

## 3. Running TypeScript Tests (Playwright Test)

### First-time setup (run once inside `typescript-tests/`)

```powershell
cd typescript-tests
npm.cmd install
npm.cmd run npx.cmd playwright install
```

Or if Node is on your PATH normally:
```bash
cd typescript-tests
npm install
npx playwright install chromium
```

### Record New Tests
While working inside `typescript-tests/`, you can launch the recorder directly:
```powershell
# Option 1: Dual Java + TypeScript recorder (generates POM, Spec & Fixtures):
.\record.bat
# or via npm:
npm run record

# Option 2: Standalone Playwright Codegen (generates raw TypeScript):
npx playwright codegen https://www.saucedemo.com
# or via npm:
npm run codegen -- https://www.saucedemo.com
```

### Run tests

#### Option 1 — Batch Script Runner (Recommended on Windows)
Works regardless of PowerShell execution policy or local PATH:
```powershell
# From typescript-tests/

# Run all test suites
.\run-tests.bat

# Run a specific test file
.\run-tests.bat tests/Login.spec.ts

# Run in headed mode (visible browser window)
.\run-tests.bat --headed

# Run with Playwright Interactive UI Mode
.\run-tests.bat --ui
```

#### Option 2 — Standard npm test
```powershell
# From typescript-tests/

# Run all tests
npm test

# Run a specific test file
npm test -- tests/Login.spec.ts
```

#### Option 3 — Direct Playwright CLI (npx)
> **Note**: If your terminal was already open before Node was installed, either **close and reopen your terminal**, or refresh PATH in your current PowerShell session first:
> ```powershell
> $env:Path = [System.Environment]::GetEnvironmentVariable("Path","User") + ";" + $env:Path
> ```

```powershell
# From typescript-tests/

# Run all tests (headless)
npx playwright test
# (On Windows if npx isn't recognized directly: npx.cmd playwright test)

# Run a specific test spec
npx playwright test tests/Login.spec.ts

# Run with headed browser
npx playwright test --headed

# Open interactive UI Mode (time-travel debugging, watch mode)
npx playwright test --ui

# Run in step-by-step Debug mode with Playwright Inspector
npx playwright test --debug

# Run tests on a specific browser project (chromium, firefox, webkit)
npx playwright test --project=chromium
```

#### Running TypeScript tests from the Project Root
If you are currently at the repository root (`WebRecorder-main/`), you can execute tests without manually `cd`ing:
```powershell
# Run via npm prefix
npm --prefix typescript-tests test

# Or run the batch file directly
cmd /c "cd typescript-tests && run-tests.bat"
```
### View Test Execution Reports

After running tests, you can view two rich reporting dashboards:

#### 1. Allure Executive Dashboard (Recommended)
Features pass/fail trends, severity breakdown, BDD steps, and test case/Jira linkage:
```powershell
# In typescript-tests/
.\generate-allure-report.bat
```
*(Or via npm: `npm.cmd run report:allure`)*

#### 2. Playwright Native HTML Report
Features step-by-step DOM snapshots, Playwright trace logs, and video playback:
```powershell
# In typescript-tests/
.\show-html-report.bat
```
*(Or via npm: `npm.cmd run report:html`)*

### Smart Assertions & Visual Regression Testing

All Page Objects inheriting from `BasePage` provide built-in smart assertions and visual regression checks:

```typescript
// Smart Assertions
await pageObj.assertUrl(/.*inventory.html/);
await pageObj.assertTitle(/.*Swag Labs/);
await pageObj.assertVisible('CART_LINK', '.shopping_cart_link');
await pageObj.assertText('BADGE', '.shopping_cart_badge', '1');

// Visual Snapshot Comparison (full page or component)
await pageObj.assertVisualSnapshot('dashboard.png');
await pageObj.assertElementSnapshot('HEADER', '.header_container', 'header.png');
```

To capture or update visual baseline screenshots:
```powershell
# In typescript-tests/
.\update-visual-baselines.bat
```

---

## 4. Project Structure

```
WebRecorder-main/
├── .agents/
│   └── skills/
│       └── webrecorder-adapter/   ← Sub-Agent skill to migrate recorded code into framework
├── src/
│   ├── main/java/
│   │   ├── recorder/              ← Recorder engine (WebRecorder, TestScriptExporter, etc.)
│   │   ├── pageobjects/           ← Generated Java Page Objects
│   │   └── utils/                 ← Java SelfHealingLocator, JsonDataReader
│   └── test/
│       ├── java/recorder/         ← Generated Java tests (<Name>Test.java)
│       └── resources/data/        ← Test data JSON files
├── typescript-tests/
│   ├── recordings/                ← Raw WebRecorder scripts (<Name>.recorded.ts)
│   ├── pages/                     ← TypeScript Page Objects extending BasePage
│   ├── tests/                     ← Enterprise spec files using BaseTest & POM
│   ├── fixtures/                  ← Parameterized test data JSON files
│   ├── framework/
│   │   ├── base/
│   │   │   ├── BasePage.ts        ← Foundational POM class with built-in SelfHealingLocator
│   │   │   └── BaseTest.ts        ← TestNG-style fixture (@BeforeMethod/@AfterMethod, auto screenshots)
│   │   └── utils/
│   │       ├── SelfHealingLocator.ts ← Fallback locator recovery & auto-healing
│   │       └── DataProvider.ts    ← TestNG @DataProvider equivalent for JSON datasets
│   ├── reports/                   ← HTML and list test execution reports
│   ├── run-tests.bat              ← One-click test runner
│   ├── package.json
│   ├── tsconfig.json
│   └── playwright.config.ts
└── pom.xml
```

---

## 5. Automatic Framework Generation & Sub-Agent

### 100% Automatic on `stop`
Whenever you finish a recording session and type `stop`, WebRecorder **automatically generates everything immediately**:
- 📁 **Raw Backup**: `typescript-tests/recordings/<Name>.recorded.ts`
- 🏛️ **Framework Page Object**: `typescript-tests/pages/<Name>Page.ts` (extending `BasePage` with self-healing locators)
- 📊 **Test Data Fixture**: `typescript-tests/fixtures/<Name>Data.json`
- 🧪 **Enterprise Test Spec**: `typescript-tests/tests/<Name>.spec.ts` (using `BaseTest`, `DataProvider`, and POM)

You can run the generated test immediately with:
```powershell
cd typescript-tests
.\run-tests.bat tests/<Name>.spec.ts
# or
npx playwright test tests/<Name>.spec.ts
```

### Optional: Sub-Agent Semantic Refactoring
If you want higher-level business methods (e.g., grouping `username` + `password` + `click` into a single `login()` method), you can ask Antigravity:
> *"Refactor the <Name> Page Object and spec with clean business methods."*

The built-in **`webrecorder-adapter`** skill will analyze the flow and bundle consecutive actions into clean, semantic functions.

---

## 6. Corporate Network / VPN Note

If you are behind a corporate proxy/firewall (e.g., Zscaler) that intercepts SSL, the recorder is preconfigured to bypass TLS verification so Playwright can download browser binaries smoothly.

If you still encounter `SELF_SIGNED_CERT_IN_CHAIN`, run:

```powershell
# For Java recorder
$env:NODE_TLS_REJECT_UNAUTHORIZED="0"
mvn exec:java

# For TypeScript Playwright install
$env:NODE_TLS_REJECT_UNAUTHORIZED="0"
npx.cmd playwright install chromium
```
