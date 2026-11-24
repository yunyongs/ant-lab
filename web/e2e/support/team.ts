import { expect, type Page } from '@playwright/test';
import { buyInSheet, type BuyReport } from './order.ts';
import { checkpoint, expectNoOverflow, type Run } from './steps.ts';

const poolRegion = (page: Page) =>
  page.getByRole('region', { name: '종목 풀', exact: true });

const teamBuildHeading = (page: Page) =>
  page.getByRole('heading', { name: '0턴 팀 구성' });

// Filter and sort live in the address; clearing restores the list.
export async function browsePool(page: Page, run: Run): Promise<void> {
  await page.getByLabel('시장').selectOption('US');
  await expect(page).toHaveURL(/[?&]market=US/);
  await expect(poolRegion(page).getByRole('status')).toHaveText(
    /^전체 \d+개 중 \d+개$/,
  );
  await page.getByLabel('정렬').selectOption('vol-asc');
  await expect(page).toHaveURL(/[?&]sort=vol-asc/);
  await checkpoint(run, 'pool-filtered', { axe: 'pool-filtered' });
  await page.getByRole('button', { name: '조건 지우기' }).click();
  await expect(page).not.toHaveURL(/[?&](market|sort)=/);
  await expectNoOverflow(page, 'pool-cleared');
}

// A 180-asset pool with long names must not widen the page (the pool is
// cached per session, so reload to refetch, then restore the real pool).
export async function checkBigPool(page: Page, run: Run): Promise<void> {
  const big = Array.from({ length: 180 }, (_, i) => ({
    assetId: 900000 + i,
    market: i < 50 ? 'KR' : 'US',
    ticker: `LONGTICKER${String(i)}`,
    name: `아주 긴 이름의 종목 ${String(i)} ${'Verylongunbrokenname'.repeat(3)}`,
    assetType: i >= 150 ? 'ETF' : 'STOCK',
    returnBasis: 'PRICE',
    proxyIndexName: null,
    groupId: (i % 12) + 1,
    groupName: `아주 긴 이름의 묶음 ${String(i % 12)} ${'Group'.repeat(12)}`,
    groupKind: 'SECTOR',
    systemPosition: 'MIDFIELDER',
    upParticipation: '1.12345678',
    downParticipation: '0.87654321',
    volatility: '0.06200000',
    maxDrawdown: '-0.31000000',
  }));
  await page.route('**/api/v1/pool', (route) =>
    route.fulfill({ json: { assets: big } }),
  );
  await page.reload();
  await expect(poolRegion(page)).toBeVisible();
  await expect(poolRegion(page).getByRole('status')).toHaveText('180개');
  await checkpoint(run, 'pool-180', { axe: 'pool-180' });
  await page.unroute('**/api/v1/pool');
  await page.reload();
  await expect(teamBuildHeading(page)).toBeVisible();
}

// The pool's pick buttons, in pool order.
export const picks = (page: Page) =>
  page.getByRole('button', { name: /선택$/ });

// Open the order sheet of the pool row at `index` and buy `shares`.
export async function buyFromPool(
  page: Page,
  index: number,
  shares: number,
  report?: BuyReport,
): Promise<void> {
  await picks(page).nth(index).click();
  await buyInSheet(page, shares, report);
}

export async function expectTeamBuilt(page: Page, run: Run): Promise<void> {
  await expect(page.getByText(/^보유 \d+주 · /)).toHaveCount(2);
  await checkpoint(run, 'team-built');
}

// Opt-in: the projection page needs the engine service and enough price
// history in the stack (the guards fail on any 4xx/5xx).
export async function checkProjection(
  page: Page,
  run: Run,
  openTrade: () => Promise<void>,
): Promise<void> {
  await openTrade();
  await page.getByRole('link', { name: '예상 분포 보기' }).click();
  await expect(page.getByRole('heading', { name: '예상 분포' })).toBeVisible();
  await expect(page.getByText(/예측이 아닙니다/).first()).toBeVisible();
  await expect(
    page.getByRole('img', { name: /예상 분포 띠 그림/ }),
  ).toBeVisible({ timeout: 30_000 });
  await checkpoint(run, 'projection', { axe: 'projection' });
  await page.getByRole('link', { name: '시즌으로 돌아가기' }).click();
  await expect(teamBuildHeading(page)).toBeVisible();
}
