import { expect, type Page } from '@playwright/test';
import { mainNav } from './shell.ts';
import { checkpoint, type Run } from './steps.ts';

export async function openTrade(page: Page): Promise<void> {
  await mainNav(page).getByRole('link', { name: '매매' }).click();
}

export async function expectTradeHeading(
  page: Page,
  turn: number,
): Promise<void> {
  await expect(
    page.getByRole('heading', { name: `${String(turn)}턴 매매` }),
  ).toBeVisible();
}

// Formation change at `turn`: 30/30/25/15 with a one-line reason.
export async function changeFormation(
  page: Page,
  run: Run,
  turn: number,
): Promise<void> {
  await openTrade(page);
  await expectTradeHeading(page, turn);
  await page.getByRole('link', { name: '전술 변경' }).click();
  await expect(
    page.getByRole('heading', { name: `${String(turn)}턴 전술 변경` }),
  ).toBeVisible();
  await page.getByLabel('골키퍼', { exact: true }).fill('30');
  await page.getByLabel('수비수', { exact: true }).fill('30');
  await page.getByLabel('미드필더', { exact: true }).fill('25');
  await page.getByLabel('공격수', { exact: true }).fill('15');
  await page.getByLabel('사유 (선택, 한 줄)').fill('종단 시험');
  await checkpoint(run, 'formation-form', { axe: 'formation' });
  await page.getByRole('button', { name: '전술 변경 적용' }).click();
  await expect(
    page.getByText(`${String(turn)}턴 전술을 바꿨습니다`),
  ).toBeVisible();
  await checkpoint(run, 'formation-result');
}

export async function backToTrade(page: Page, turn: number): Promise<void> {
  await page.getByRole('link', { name: '매매로 돌아가기' }).click();
  await expectTradeHeading(page, turn);
}
