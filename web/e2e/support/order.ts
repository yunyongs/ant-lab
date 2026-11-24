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

// A server weight such as "0.02574155" in the 2-place percent the sheet shows.
function weightText(weight: string): string {
  const [whole = '0', fraction = ''] = weight.split('.');
  const units = BigInt(`${whole}${fraction.padEnd(8, '0').slice(0, 8)}`);
  // 1e-8 units to 1e-2 percent places: x100, then drop 6 digits, rounding once.
  const rounded = (units + 5_000n) / 10_000n;
  const digits = rounded.toString().padStart(3, '0');
  return `${digits.slice(0, -2)}.${digits.slice(-2)}%`;
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
    // The preview and the ratio buttons are on screen before the scan.
    await expect(sheet.getByText('체결 뒤 이 종목 비중')).toBeVisible();
    await expect(
      sheet.getByRole('group', { name: '최대 수량 대비' }),
    ).toBeVisible();
    await checkpoint(report.run, 'order-sheet', { axe: 'order-sheet' });
  }
  // The preview weight must equal the holdings answer after the fill.
  const row = sheet.getByText('체결 뒤 이 종목 비중').locator('xpath=..');
  await expect(row).toBeVisible();
  const previewed = (await row.locator('dd').innerText()).trim();
  const isHoldings = (url: string) => /\/seasons\/\d+\/holdings$/.test(url);
  const filled = page.waitForResponse(
    (r) => r.request().method() === 'POST' && /\/orders$/.test(r.url()),
  );
  const refreshed = page.waitForResponse(
    (r) => r.request().method() === 'GET' && isHoldings(r.url()),
  );
  // The total on the button is hidden from the name, which stays "N주 매수".
  await sheet.getByRole('button', { name: `${String(shares)}주 매수` }).click();
  const { assetId } = (await (await filled).json()) as { assetId: number };
  const held = (await (await refreshed).json()) as {
    holdings: { assetId: number; weight: string }[];
  };
  const after = held.holdings.find((h) => h.assetId === assetId);
  expect(after).toBeDefined();
  expect(previewed).toBe(weightText(after?.weight ?? ''));
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
