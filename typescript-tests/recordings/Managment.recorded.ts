// ============================================================
// Raw WebRecorder Output
// Recorded:  2026-09-23 14:30
// Start URL: https://assetmanagementqa.rishabhsoft.com/
// Browser:   chrome
// Name:      Managment
// ============================================================

import { test } from '@playwright/test';

// Raw captured element selectors
export const Locators = {
  USER_MANAGEMENT_BUTTON: 'text=User Management',
  ASSIGN_ROLE_BUTTON: 'text=+ Assign Role',
  DIV_CSS_19BB58M: 'div.css-19bb58m',
  REACT_SELECT_2_INPUT: '#react-select-2-input',
  RACHIT_MEHTA_BUTTON: 'text=Rachit Mehta',
  REC_RECORDING_ACTIVE_DASHBOARD_USER_MANAGEMENT_LOCATION_MANAGEMENT_VENDOR_M_BUTTON: 'text=🔴 REC (Recording Active) Dashboard User Management Location Management Vendor M',
  ADMIN_TEAM_FINANCE_TEAM_BUTTON: 'text=Admin Team Finance Team',
  CANCEL_BUTTON: 'text=Cancel',
  CONTINUE_BUTTON: 'text=Continue',
};

test('raw recorded flow - Managment', async ({ page }) => {
  await page.goto('https://assetmanagementqa.rishabhsoft.com/dashboard/branchadmin');
  await page.locator(Locators.USER_MANAGEMENT_BUTTON).click();
  await page.locator(Locators.ASSIGN_ROLE_BUTTON).click();
  await page.locator(Locators.DIV_CSS_19BB58M).click();
  await page.locator(Locators.REACT_SELECT_2_INPUT).fill('rachit');
  await page.locator(Locators.RACHIT_MEHTA_BUTTON).click();
  await page.locator(Locators.REC_RECORDING_ACTIVE_DASHBOARD_USER_MANAGEMENT_LOCATION_MANAGEMENT_VENDOR_M_BUTTON).click();
  await page.locator(Locators.ADMIN_TEAM_FINANCE_TEAM_BUTTON).selectOption('Admin Team');
  await page.locator(Locators.CANCEL_BUTTON).click();
  await page.locator(Locators.CONTINUE_BUTTON).click();
});
