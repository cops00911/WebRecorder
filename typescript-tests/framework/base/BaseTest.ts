import { test as base, expect } from '@playwright/test';
import * as allure from 'allure-js-commons';

export interface TestMetadata {
  epic?: string;
  feature?: string;
  story?: string;
  severity?: 'trivial' | 'minor' | 'normal' | 'critical' | 'blocker';
  tags?: string[];
  owner?: string;
  issue?: string;
  testCaseId?: string;
  description?: string;
}

/**
 * BaseTest provides TestNG-style lifecycle hooks (@BeforeMethod, @AfterMethod),
 * Allure Enterprise Reporting integration, and automatic failure diagnostics.
 */
export const test = base.extend<{
  testSetup: void;
}>({
  testSetup: [
    async ({ page }, use, testInfo) => {
      // @BeforeMethod equivalent: Setup before each test
      console.log(`\n============================================================`);
      console.log(`[TEST START] ${testInfo.title}`);
      console.log(`============================================================`);

      // Execute test
      await use();

      // @AfterMethod equivalent: Teardown and failure handling
      if (testInfo.status !== testInfo.expectedStatus) {
        console.error(`[TEST FAILED] ${testInfo.title} - Capturing failure screenshot...`);
        const screenshotPath = testInfo.outputPath(`failure-${Date.now()}.png`);
        try {
          const screenshot = await page.screenshot({ path: screenshotPath, fullPage: true });
          await testInfo.attach('Failure Screenshot', {
            body: screenshot,
            contentType: 'image/png',
          });
          console.log(`[Diagnostic] Screenshot saved to ${screenshotPath}`);
        } catch (e) {
          console.warn(`[Diagnostic Warning] Could not take failure screenshot:`, e);
        }
      } else {
        console.log(`[TEST PASSED] ${testInfo.title}`);
      }
    },
    { auto: true }, // Automatically runs for every test
  ],
});

/**
 * Helper to enrich tests with Allure metadata & Jira / Test Case links.
 */
export const annotateTest = (meta: TestMetadata) => {
  if (meta.epic) allure.epic(meta.epic);
  if (meta.feature) allure.feature(meta.feature);
  if (meta.story) allure.story(meta.story);
  if (meta.severity) allure.severity(meta.severity);
  if (meta.owner) allure.owner(meta.owner);
  if (meta.description) allure.description(meta.description);
  if (meta.testCaseId) allure.testCaseId(meta.testCaseId);
  if (meta.issue) allure.issue(meta.issue, meta.issue);
  if (meta.tags && meta.tags.length > 0) {
    allure.tags(...meta.tags);
  }
};

export { allure };
export { expect } from '@playwright/test';

