---
name: test-case-integrator
description: >-
  Autonomous sub-agent skill that inspects multiple recorded Playwright test cases,
  uses a 4-layer intelligence model (URL continuity, shared entity data, UI hierarchy,
  and composite outcome assembly) to infer dependencies, extracts reusable POM methods,
  and chains them into unified, continuous end-to-end test workflows. Trigger on mentions of:
  "integrate tests", "connect test cases", "chain tests", "run login before test", "merge recordings",
  "multi-file test workflow", or any automated test dependency resolution.
---

# Autonomous Test Case Integrator Skill

This skill allows Antigravity to act as an autonomous test architect that analyzes multiple recorded Playwright test cases, discovers their relationships using a **4-Layer Intelligence Model**, and synthesizes them into maintainable, enterprise Page Object Model (POM) end-to-end workflows.

---

## The 4-Layer Intelligence Pipeline

When tasked with integrating or connecting recorded test cases, execute this multi-layer analysis:

```
┌─────────────────────────────────────────────────────────────────┐
│ Layer 1: URL State Continuity (Exit State ➔ Entry State)        │
├─────────────────────────────────────────────────────────────────┤
│ Layer 2: Shared Entity & Data Dependency Graph                  │
├─────────────────────────────────────────────────────────────────┤
│ Layer 3: Locator & UI Hierarchy Context (SPA / Modals)          │
├─────────────────────────────────────────────────────────────────┤
│ Layer 4: Composite Outcome Assembly (POM Methods & E2E Journey) │
└─────────────────────────────────────────────────────────────────┘
```

---

### Layer 1: URL State Continuity
1. **Extract Entry & Exit URLs**:
   - Inspect `page.goto(url)` or navigation events in each recorded test.
   - Record the **initial URL** (Entry State) and the **final URL** reached (Exit State).
2. **Path Matching**:
   - If File B's initial URL equals or builds upon File A's final URL (e.g., File A lands on `/dashboard`, and File B navigates within `/dashboard/branchadmin`), File A is established as a direct prerequisite of File B.
3. **Authentication Boundary**:
   - If File B's initial URL is a protected path (e.g. `/dashboard`, `/admin`, `/vendors`) and File A is a login flow, File A is an automatic mandatory prerequisite.

---

### Layer 2: Shared Entity & Data Dependency Graph
1. **Fixture & Input Inspection**:
   - Read associated JSON fixtures (`typescript-tests/fixtures/<Name>Data.json`).
   - Identify entities created in one file: e.g. `userName`, `email`, `roleName`, `vendorName`, `assetId`.
2. **Consumer Matching**:
   - Scan downstream test files for selectors or input values matching entities created upstream:
     - Search inputs (`fill("SEARCH_INPUT", createdEntity)`)
     - Selection clicks (`text=createdEntity`)
     - Table rows / grid items containing the entity
3. **Data Binding**:
   - Automatically pass output data from the producer test into the consumer test (or share the common fixture record).

---

### Layer 3: Locator & UI Hierarchy Context (SPA Support)
For Single-Page Applications (React, Vue, Angular) where URLs do not change:
1. **Modal / Overlay Continuity**:
   - If File A clicks an action that opens a modal (e.g. `+ Assign Role` opening a dialog), and File B begins by interacting with elements inside that dialog (e.g. `div.css-19bb58m`, `#react-select-2-input`), File B is an in-place continuation of File A.
2. **Breadcrumb / Tab Detection**:
   - Analyze navigation button text:
     - `Dashboard > User Management > Assign Role`
     - If File A clicks `User Management`, and File B clicks `Assign Role`, they share an execution container.

---

### Layer 4: Composite Outcome Assembly
Once the dependency order `[Test 1] ➔ [Test 2] ➔ ... ➔ [Test N]` is determined:

1. **POM Action Encapsulation**:
   - Ensure each Page Object in `typescript-tests/pages/<Name>Page.ts` provides reusable async action methods (e.g., `loginWithMicrosoft()`, `navigateToUserManagement()`, `assignRole()`) rather than leaving raw sequential calls in specs.
2. **Hook or Workflow Creation**:
   - **For Single-Test Prerequisite (e.g. Auth)**:
     Inject `test.beforeEach` in the downstream test spec loading credentials from the auth fixture.
   - **For Multi-Stage Business Journeys (e.g. Create ➔ Edit ➔ Verify)**:
     Generate a composite E2E journey spec (`tests/e2e/<Feature>Journey.spec.ts`) utilizing `test.step('...', async () => { ... })` for each stage.
3. **Validation**:
   - Run `npx.cmd playwright test <specPath>` or `npm.cmd test -- --list` to guarantee syntax, imports, and execution succeed without error.

---

## Standard Code Patterns

### Pattern A: Auth Prerequisite Injection (`beforeEach`)
When a feature test depends on a login provider:

```typescript
import { test, expect } from '../framework/base/BaseTest';
import { TargetPage } from '../pages/TargetPage';
import { LoginPage } from '../pages/LoginPage';
import { DataProvider } from '../framework/utils/DataProvider';

test.beforeEach(async ({ page }) => {
  const loginPage = new LoginPage(page);
  const [credentials] = DataProvider.loadJson<any>('LoginData.json');
  await loginPage.loginWithMicrosoft(
    'https://assetmanagementqa.rishabhsoft.com/',
    credentials?.loginfmt,
    credentials?.passwd
  );
});

test('Feature Test Name', async ({ page }) => {
  const targetPage = new TargetPage(page);
  // Feature actions execute in authenticated context
});
```

### Pattern B: Continuous Multi-Step Journey (`test.step`)
When multiple recorded modules chain together:

```typescript
import { test, expect } from '../framework/base/BaseTest';
import { LoginPage } from '../pages/LoginPage';
import { UserManagementPage } from '../pages/UserManagementPage';
import { AssignRolePage } from '../pages/AssignRolePage';
import { DataProvider } from '../framework/utils/DataProvider';

test('E2E Complete User Role Assignment Flow', async ({ page }) => {
  const loginPage = new LoginPage(page);
  const userMgmtPage = new UserManagementPage(page);
  const assignRolePage = new AssignRolePage(page);

  await test.step('Step 1: Authenticate with SSO', async () => {
    const [creds] = DataProvider.loadJson<any>('LoginData.json');
    await loginPage.loginWithMicrosoft(undefined, creds?.loginfmt, creds?.passwd);
  });

  await test.step('Step 2: Navigate to User Management', async () => {
    await userMgmtPage.click('USER_MANAGEMENT_BUTTON', UserManagementPage.USER_MANAGEMENT_BUTTON);
  });

  await test.step('Step 3: Assign Role to User', async () => {
    await assignRolePage.assignRole('rachit', 'Admin Team');
  });
});
```
