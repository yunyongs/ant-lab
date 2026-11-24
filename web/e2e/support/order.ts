import { expect, type Page } from '@playwright/test';
import { sheetOf } from './shell.ts';
import { checkpoint, type Run } from './steps.ts';

export interface BuyReport {
  run: Run;
  // Checkpoint the open sheet with an axe scan.
  sheetAxe?: boolean;
  // Checkpoint the result view under this name.
  resultName?: string;
}

// The order sheet is open: buy `shares` and close the result.
export async function buyInSheet(
  page: Page,
  shares: number,
  report?: BuyReport,
): Promise<void> {
  const sheet = sheetOf(page);
  await expect(sheet).toBeVisible();
  if (shares !== 1) {
    await expect(page.getByLabel('선언 포지션')).not.toHaveValue('');
    await page.getByLabel('수량', { exact: true }).fill(String(shares));
  }
  if (report?.sheetAxe === true) {
    await checkpoint(report.run, 'order-sheet', { axe: 'order-sheet' });
  }
  await sheet.getByRole('button', { name: `${String(shares)}주 매수` }).click();
  await expect(sheet.getByText('체결가')).toBeVisible();
  if (report?.resultName !== undefined) {
    await expect(sheet.getByText('수수료')).toBeVisible();
    await checkpoint(report.run, report.resultName);
  }
  await sheet.getByRole('button', { name: '닫기' }).click();
  await expect(sheet).toBeHidden();
}

// Sell one share of the first holding, after a formation change in the same
// turn, so the result shows the tie to that change.
export async function sellOneTied(page: Page, run: Run): Promise<void> {
  const sheet = sheetOf(page);
  const sells = page.getByRole('button', { name: /^.+ 매도$/ });
  await expect(sells.first()).toBeVisible();
  await checkpoint(run, 'trade', { axe: 'trade' });
  await sells.first().click();
  await expect(sheet).toBeVisible();
  await sheet.getByRole('button', { name: '1주 매도' }).click();
  await expect(sheet.getByText('실현 손익')).toBeVisible();
  await expect(
    sheet.getByText(/이번 턴 전술 변경에 묶인 주문입니다/),
  ).toBeVisible();
  await checkpoint(run, 'sell-result');
  await sheet.getByRole('button', { name: '닫기' }).click();
  await expect(sheet).toBeHidden();
}
