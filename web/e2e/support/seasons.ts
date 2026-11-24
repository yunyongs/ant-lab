import { expect, type Page } from '@playwright/test';
import { checkpoint, type Run } from './steps.ts';

// The empty home: no season yet, and the status line reached the API.
export async function expectEmptyHome(page: Page, run: Run): Promise<void> {
  await expect(page.getByText('아직 시즌이 없습니다')).toBeVisible();
  await expect(page.getByText(/^API 연결됨 · 종목 \d+개$/)).toBeVisible();
  await checkpoint(run, 'home-empty', { axe: 'home' });
}

// The risk limit is a deliberate choice, the rest is default. Returns the id.
export async function startSeason(
  page: Page,
  run: Run,
  turns: number,
): Promise<string> {
  await page.getByRole('link', { name: '첫 시즌 시작' }).click();
  await expect(page.getByRole('heading', { name: '새 시즌' })).toBeVisible();
  await page.getByRole('radio', { name: '-20%' }).check();
  await expect(
    page.getByRole('radio', { name: `${String(turns)}턴` }),
  ).toBeChecked();
  await checkpoint(run, 'new-season', { axe: 'new-season' });
  await page.getByRole('button', { name: '시즌 시작', exact: true }).click();
  await expect(page).toHaveURL(/\/seasons\/\d+$/);
  const seasonId = /\/seasons\/(\d+)$/.exec(page.url())?.[1] ?? '';
  await expect(
    page.getByRole('heading', { name: '0턴 팀 구성' }),
  ).toBeVisible();
  await expect(page.getByText(`0/${String(turns)}턴`)).toBeVisible();
  await checkpoint(run, 'team-build', { axe: 'team-build' });
  return seasonId;
}

// The shorter start used by the abandon spec: the 12-turn default is kept.
export async function startSeasonQuick(page: Page): Promise<void> {
  await page.getByRole('link', { name: '첫 시즌 시작' }).click();
  await page.getByRole('radio', { name: '-20%' }).check();
  await page.getByRole('button', { name: '시즌 시작', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: '0턴 팀 구성' }),
  ).toBeVisible();
}
