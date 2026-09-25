// ============================================================
// Raw WebRecorder Output
// Recorded:  2026-09-23 14:23
// Start URL: https://assetmanagementqa.rishabhsoft.com/
// Browser:   chrome
// Name:      Login
// ============================================================

import { test } from '@playwright/test';

// Raw captured element selectors
export const Locators = {
  LOGIN_WITH_MICROSOFT_BUTTON: 'text=Login with Microsoft',
  LOGINFMT: '[name=\'loginfmt\']',
  IDSIBUTTON9: '#idSIButton9',
  PASSWD: '[name=\'passwd\']',
};

test('raw recorded flow - Login', async ({ page }) => {
  await page.goto('https://assetmanagementqa.rishabhsoft.com/');
  const [popup] = await Promise.all([
    page.waitForEvent('popup'),
    page.locator(Locators.LOGIN_WITH_MICROSOFT_BUTTON).click(),
  ]);
  await popup.waitForLoadState('domcontentloaded');
  await popup.locator(Locators.LOGINFMT).fill('rachit.mehta@rishabhsoft.com');
  await popup.locator(Locators.IDSIBUTTON9).click();
  await popup.locator(Locators.PASSWD).fill('Rm@16092026');
  await popup.locator(Locators.IDSIBUTTON9).click();
  try { await popup.waitForEvent('close', { timeout: 15000 }); } catch {}
  await page.waitForLoadState('networkidle');
});
