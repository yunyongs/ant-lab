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
  await checkSeasonHome(page, run, seasonId);
  return seasonId;
}

// The list with the season just started (turn 0): the featured card leads and
// its link leads back into team building. The token lives in memory, so the
// way there and back is the nav, never a reload.
async function checkSeasonHome(
  page: Page,
  run: Run,
  seasonId: string,
): Promise<void> {
  await page
    .getByRole('navigation', { name: '주 메뉴' })
    .getByRole('link', { name: '시즌', exact: true })
    .click();
  const card = page.getByRole('article', {
    name: `진행 중인 시즌 ${seasonId}`,
  });
  await expect(card).toBeVisible();
  await expect(
    card.getByRole('progressbar', { name: '현재 턴' }),
  ).toBeVisible();
  await expect(
    page.getByRole('region', { name: '매니저 레이팅', exact: true }),
  ).toBeVisible();
  await checkpoint(run, 'season-home', { axe: 'season-home' });
  await card.getByRole('link', { name: '팀 구성 이어하기' }).click();
  await expect(
    page.getByRole('heading', { name: '0턴 팀 구성' }),
  ).toBeVisible();
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

// The list with the season at turn 1 or later: the gain line, the benchmark
// fact and the filled segments, in both colour conventions. Leaves through the
// card link, back on the season screen.
export async function checkSeasonProgress(
  page: Page,
  run: Run,
  seasonId: string,
  turn: number,
): Promise<void> {
  await page
    .getByRole('navigation', { name: '주 메뉴' })
    .getByRole('link', { name: '시즌', exact: true })
    .click();
  const card = page.getByRole('article', {
    name: `진행 중인 시즌 ${seasonId}`,
  });
  await expect(card).toBeVisible();
  await expect(card.getByText('기준 대비')).toBeVisible();
  await expect(card.locator('[data-direction]').first()).toBeVisible();
  const bar = card.getByRole('progressbar', { name: '현재 턴' });
  await expect(bar).toHaveAttribute('aria-valuenow', String(turn));
  await checkpoint(run, 'season-progress', { axe: 'season-progress' });
  const toggle = page.getByRole('button', { name: /^등락 색:/ });
  await toggle.click();
  await expect(card.locator('[data-direction]').first()).toBeVisible();
  await checkpoint(run, 'season-progress-alt-colors', {
    axe: 'season-progress-alt-colors',
  });
  await toggle.click();
  // The convention is a stored per-viewer choice; the storage check at the
  // end of the flow expects an empty store.
  await page.evaluate(() => {
    window.localStorage.clear();
  });
  await card
    .getByRole('link', { name: `${String(turn + 1)}턴으로 이어하기` })
    .click();
  await expect(
    page.getByRole('button', { name: `다음 턴 (${String(turn + 1)}턴으로)` }),
  ).toBeVisible();
}
