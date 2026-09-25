import { Page, Locator, expect } from '@playwright/test';
import { SelfHealingLocator } from '../utils/SelfHealingLocator';

/**
 * BasePage is the foundation for all Page Objects in the framework.
 * Encapsulates common page interactions, self-healing locators,
 * smart assertions, and visual regression snapshot validations.
 */
export abstract class BasePage {
  readonly page: Page;

  constructor(page: Page) {
    this.page = page;
  }

  /**
   * Navigate to a URL
   */
  async goto(url: string): Promise<void> {
    await this.page.goto(url, { waitUntil: 'domcontentloaded' });
  }

  /**
   * Click an element with self-healing support
   */
  async click(key: string, selector: string): Promise<void> {
    await SelfHealingLocator.click(this.page, key, selector);
  }

  /**
   * Fill an input with self-healing support
   */
  async fill(key: string, selector: string, value: string): Promise<void> {
    await SelfHealingLocator.fill(this.page, key, selector, value);
  }

  /**
   * Hover over an element with self-healing support
   */
  async hover(key: string, selector: string): Promise<void> {
    await SelfHealingLocator.hover(this.page, key, selector);
  }

  /**
   * Select dropdown option with self-healing support
   */
  async selectOption(key: string, selector: string, value: string): Promise<void> {
    await SelfHealingLocator.selectOption(this.page, key, selector, value);
  }

  /**
   * Check checkbox/radio with self-healing support
   */
  async check(key: string, selector: string): Promise<void> {
    await SelfHealingLocator.check(this.page, key, selector);
  }

  /**
   * Press a key on an element with self-healing support
   */
  async press(key: string, selector: string, keyToPress: string): Promise<void> {
    await SelfHealingLocator.press(this.page, key, selector, keyToPress);
  }

  /**
   * Get text content of an element
   */
  async getText(selector: string): Promise<string> {
    const text = await this.page.locator(selector).textContent();
    return text ? text.trim() : '';
  }

  /**
   * Wait for a selector to become visible
   */
  async waitForSelector(selector: string, timeout = 10000): Promise<Locator> {
    const loc = this.page.locator(selector);
    await loc.waitFor({ state: 'visible', timeout });
    return loc;
  }

  /**
   * Capture a full-page or element screenshot
   */
  async takeScreenshot(name: string): Promise<Buffer> {
    return await this.page.screenshot({ path: `test-results/screenshots/${name}.png`, fullPage: true });
  }

  /**
   * Get current URL
   */
  getUrl(): string {
    return this.page.url();
  }

  /**
   * Get page title
   */
  async getTitle(): Promise<string> {
    return await this.page.title();
  }

  // ── Smart Assertions ──────────────────────────────────────

  /**
   * Asserts the current page URL matches an expected string or RegExp.
   */
  async assertUrl(expected: string | RegExp, timeout = 7000): Promise<void> {
    await expect(this.page).toHaveURL(expected, { timeout });
  }

  /**
   * Asserts the page title matches an expected string or RegExp.
   */
  async assertTitle(expected: string | RegExp, timeout = 7000): Promise<void> {
    await expect(this.page).toHaveTitle(expected, { timeout });
  }

  /**
   * Asserts an element is visible on the page with self-healing fallback.
   */
  async assertVisible(key: string, selector: string, timeout = 7000): Promise<void> {
    const loc = this.page.locator(selector).first();
    await expect(loc).toBeVisible({ timeout });
  }

  /**
   * Asserts an element is NOT visible on the page.
   */
  async assertNotVisible(key: string, selector: string, timeout = 7000): Promise<void> {
    const loc = this.page.locator(selector).first();
    await expect(loc).not.toBeVisible({ timeout });
  }

  /**
   * Asserts an element contains expected text.
   */
  async assertText(key: string, selector: string, expectedText: string | RegExp, timeout = 7000): Promise<void> {
    const loc = this.page.locator(selector).first();
    await expect(loc).toContainText(expectedText, { timeout });
  }

  /**
   * Asserts an input element has an expected value.
   */
  async assertValue(key: string, selector: string, expectedValue: string, timeout = 7000): Promise<void> {
    const loc = this.page.locator(selector).first();
    await expect(loc).toHaveValue(expectedValue, { timeout });
  }

  // ── Visual Regression Validations ────────────────────────

  /**
   * Compares the current page visually against a baseline screenshot.
   * On first run or with --update-snapshots, creates the baseline.
   */
  async assertVisualSnapshot(snapshotName: string, options?: { maxDiffPixelRatio?: number; fullPage?: boolean }): Promise<void> {
    await expect(this.page).toHaveScreenshot(snapshotName, {
      fullPage: options?.fullPage ?? true,
      maxDiffPixelRatio: options?.maxDiffPixelRatio ?? 0.05,
      animations: 'disabled',
    });
  }

  /**
   * Compares an individual element/component visually against a baseline.
   */
  async assertElementSnapshot(key: string, selector: string, snapshotName: string, options?: { maxDiffPixelRatio?: number }): Promise<void> {
    const loc = this.page.locator(selector).first();
    await expect(loc).toHaveScreenshot(snapshotName, {
      maxDiffPixelRatio: options?.maxDiffPixelRatio ?? 0.05,
      animations: 'disabled',
    });
  }
}
