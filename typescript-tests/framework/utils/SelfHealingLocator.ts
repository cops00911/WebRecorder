import { Page, Locator } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

export type LocatorAction = (locator: Locator) => Promise<void>;

export class SelfHealingLocator {

  static async performWithHealing(
    page: Page,
    key: string,
    defaultSelector: string,
    action: LocatorAction
  ): Promise<void> {
    try {
      // 1. Primary locator attempt with a 10s default timeout for transitions
      const originalTimeout = 30000;
      page.setDefaultTimeout(10000);
      await action(page.locator(defaultSelector));
      page.setDefaultTimeout(originalTimeout);
    } catch (err: any) {
      page.setDefaultTimeout(30000);
      const errorMsg: string = err?.message || String(err);
      console.warn(`[Self-Healing] Action failed for '${key}' using '${defaultSelector}': ${errorMsg}`);

      // 2. Strict Mode Violation Healing (multiple matching elements)
      if (errorMsg.includes('strict mode violation')) {
        console.log(`[Self-Healing] Strict mode violation detected for '${key}'. Resolving to first visible match...`);
        const locator = page.locator(defaultSelector);
        try {
          const count = await locator.count();
          for (let i = 0; i < count; i++) {
            const item = locator.nth(i);
            if (await item.isVisible() && await item.isEnabled()) {
              console.log(`[Self-Healing Success] Resolved strict mode for '${key}' using match index ${i}`);
              await action(item);
              return;
            }
          }
        } catch (subErr) {
          console.error(`[Self-Healing] Iteration through strict mode elements failed:`, subErr);
        }

        // Fallback to first element
        try {
          await action(locator.first());
          return;
        } catch (firstErr) {
          console.error(`[Self-Healing] Fallback to first element failed:`, firstErr);
        }
      }

      // 3. Text & Structural Fallbacks
      console.log(`[Self-Healing] Attempting fallback locator healing for '${key}'...`);
      const extractedText = this.extractText(defaultSelector);
      const fallbacks: string[] = [];

      if (extractedText && extractedText.trim().length > 0) {
        const cleaned = extractedText.trim();
        fallbacks.push(`text=${cleaned}`);
        fallbacks.push(`xpath=//*[contains(text(), '${cleaned}')]`);
        fallbacks.push(`xpath=//*[contains(normalize-space(), '${cleaned}')]`);

        const words = cleaned.split(/\s+/);
        if (words.length >= 1 && words[0].length >= 3) {
          fallbacks.push(`xpath=//button[contains(normalize-space(), '${words[0]}')]`);
          fallbacks.push(`xpath=//a[contains(normalize-space(), '${words[0]}')]`);
          fallbacks.push(`xpath=//input[contains(@id, '${words[0].toLowerCase()}')]`);
          fallbacks.push(`xpath=//input[contains(@name, '${words[0].toLowerCase()}')]`);
        }
      }

      // If selector contains id or name, generate fallback candidates
      const idMatch = defaultSelector.match(/#([a-zA-Z0-9_-]+)/);
      if (idMatch) {
        fallbacks.push(`[data-test="${idMatch[1]}"]`);
        fallbacks.push(`[name="${idMatch[1]}"]`);
        fallbacks.push(`[placeholder*="${idMatch[1]}" i]`);
      }

      for (const fallback of fallbacks) {
        try {
          console.log(`[Self-Healing] Trying fallback selector: ${fallback}`);
          const fbLocator = page.locator(fallback);
          const count = await fbLocator.count();
          if (count > 0) {
            for (let i = 0; i < count; i++) {
              const item = fbLocator.nth(i);
              if (await item.isVisible() && await item.isEnabled()) {
                console.log(`[Self-Healing Success] Successfully healed '${key}' using fallback: '${fallback}'`);
                await action(item);
                this.persistHealedSelector(key, fallback);
                return;
              }
            }
          }
        } catch {
          // ignore and proceed to next candidate
        }
      }

      console.error(`[Self-Healing Warning] All healing attempts failed for '${key}' (${defaultSelector})`);
      console.error(`[Self-Healing Diagnostic] Current URL: ${page.url()}`);
      throw err;
    }
  }

  private static extractText(selector: string): string | null {
    if (!selector) return null;
    const match = selector.match(/['"]([^'"]+)['"]/);
    return match ? match[1] : null;
  }

  static async click(page: Page, key: string, defaultSelector: string): Promise<void> {
    await this.performWithHealing(page, key, defaultSelector, async (loc) => {
      try {
        await loc.click({ timeout: 5000 });
      } catch {
        await loc.click({ force: true });
      }
    });
  }

  static async fill(page: Page, key: string, defaultSelector: string, value: string): Promise<void> {
    await this.performWithHealing(page, key, defaultSelector, async (loc) => {
      await loc.fill(value);
    });
  }

  static async selectOption(page: Page, key: string, defaultSelector: string, value: string): Promise<void> {
    await this.performWithHealing(page, key, defaultSelector, async (loc) => {
      await loc.selectOption(value);
    });
  }

  static async check(page: Page, key: string, defaultSelector: string): Promise<void> {
    await this.performWithHealing(page, key, defaultSelector, async (loc) => {
      await loc.check();
    });
  }

  static async uncheck(page: Page, key: string, defaultSelector: string): Promise<void> {
    await this.performWithHealing(page, key, defaultSelector, async (loc) => {
      await loc.uncheck();
    });
  }

  static async press(page: Page, key: string, defaultSelector: string, keyToPress: string): Promise<void> {
    await this.performWithHealing(page, key, defaultSelector, async (loc) => {
      await loc.press(keyToPress);
    });
  }

  static async hover(page: Page, key: string, defaultSelector: string): Promise<void> {
    await this.performWithHealing(page, key, defaultSelector, async (loc) => {
      try {
        await loc.first().scrollIntoViewIfNeeded();
        await loc.first().hover();
      } catch {
        await loc.first().hover({ force: true });
      }
    });
  }

  private static persistHealedSelector(key: string, healedSelector: string): void {
    try {
      const pagesDir = path.resolve(__dirname, '../../pages');
      if (!fs.existsSync(pagesDir)) return;
      const files = fs.readdirSync(pagesDir).filter(f => f.endsWith('.ts'));
      for (const file of files) {
        const fullPath = path.join(pagesDir, file);
        let content = fs.readFileSync(fullPath, 'utf8');
        const regex = new RegExp(`(static\\s+readonly\\s+${key}\\s*=\\s*['"])([^'"]+)(['"];)`);
        if (regex.test(content)) {
          content = content.replace(regex, `$1${healedSelector}$3`);
          fs.writeFileSync(fullPath, content, 'utf8');
          console.log(`[Self-Healing Persistent] Updated selector for '${key}' in ${file} -> "${healedSelector}"`);
        }
      }
    } catch (e: any) {
      console.warn(`[Self-Healing Warning] Could not persist healed selector: ${e?.message}`);
    }
  }
}
