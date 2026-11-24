import { expect, type Page } from '@playwright/test';
import { mainNav } from './shell.ts';
import { checkpoint, type Run } from './steps.ts';

export const turnCard = (page: Page) =>
  page.getByRole('region', { name: '턴 결과', exact: true });

export const feedRegion = (page: Page) =>
  page.getByRole('region', { name: '소식', exact: true });

export async function advanceTo(page: Page, turn: number): Promise<void> {
  await page
    .getByRole('button', { name: `다음 턴 (${String(turn)}턴으로)` })
    .click();
  await expect(
    page.getByRole('heading', { name: `${String(turn)}턴 결과` }),
  ).toBeVisible();
}

// Turn 1: the actions stay in reach without scrolling, summary first
// (WEB-14), and the bar ends at or above the menu's top edge.
export async function playTurnOne(page: Page, run: Run): Promise<void> {
  await advanceTo(page, 1);
  await expect(turnCard(page)).toBeVisible();
  await expect(
    page.getByRole('region', { name: '요약', exact: true }),
  ).toBeInViewport();
  const actions = page.getByRole('group', { name: '턴 행동' });
  await expect(
    actions.getByRole('button', { name: '다음 턴 (2턴으로)' }),
  ).toBeInViewport();
  await expect(actions.getByRole('link', { name: '매매' })).toBeInViewport();
  const barBox = await actions.boundingBox();
  const navBox = await mainNav(page).boundingBox();
  expect(barBox).not.toBeNull();
  expect(navBox).not.toBeNull();
  expect(barBox!.y + barBox!.height).toBeLessThanOrEqual(navBox!.y + 1);
  // At the end of the page the last control is fully clear of the bar and the menu.
  const abandon = page.getByRole('button', { name: '시즌 포기' });
  await abandon.focus();
  await page.evaluate(() => {
    window.scrollTo(0, document.documentElement.scrollHeight);
  });
  const abandonBox = await abandon.boundingBox();
  const barEnd = await actions.boundingBox();
  const navEnd = await mainNav(page).boundingBox();
  expect(abandonBox).not.toBeNull();
  expect(barEnd).not.toBeNull();
  expect(navEnd).not.toBeNull();
  const abandonBottom = abandonBox!.y + abandonBox!.height;
  expect(abandonBottom).toBeLessThanOrEqual(navEnd!.y + 1);
  expect(
    abandonBottom <= barEnd!.y + 1 ||
      abandonBox!.y >= barEnd!.y + barEnd!.height - 1,
  ).toBe(true);
  // Every link has an accessible name (the chart's logo link is off).
  await expect(
    page.locator('main a:not([aria-label])').filter({ hasText: /^$/ }),
  ).toHaveCount(0);
  // The feed region is always there: items, or the no-news sentence.
  await expect(feedRegion(page)).toBeVisible();
  await checkpoint(run, 'turn-1', { axe: 'turn-result' });
}

// Turn 2: the ranking board opens on its first tab, the keyboard moves on.
export async function playTurnTwo(page: Page, run: Run): Promise<void> {
  await page.getByRole('button', { name: '다음 턴 (2턴으로)' }).click();
  await expect(page.getByRole('heading', { name: '2턴 결과' })).toBeVisible();
  await expect(turnCard(page)).toBeVisible();
  const ranking = page.getByRole('region', { name: '순위', exact: true });
  await expect(page.getByRole('button', { name: /^순위/ })).toHaveAttribute(
    'aria-expanded',
    'false',
  );
  await page.getByRole('button', { name: /^순위/ }).click();
  await expect(ranking.getByRole('tab', { name: '등락' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await ranking.getByRole('tab', { name: '등락' }).focus();
  await page.keyboard.press('ArrowRight');
  await expect(ranking.getByRole('tab', { name: '관심' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await checkpoint(run, 'turn-2', { axe: 'turn-ranking' });
}

// Back on the turn page after trading, then turns 3 to `last`.
export async function playRemainingTurns(
  page: Page,
  run: Run,
  last: number,
): Promise<void> {
  await mainNav(page).getByRole('link', { name: '턴' }).click();
  await expect(page.getByRole('heading', { name: '2턴 결과' })).toBeVisible();
  for (let turn = 3; turn <= last; turn += 1) {
    const isLast = turn === last;
    await page
      .getByRole('button', {
        name: isLast ? /^마지막 턴 넘기기/ : `다음 턴 (${String(turn)}턴으로)`,
      })
      .click();
    await expect(
      page.getByRole('heading', {
        name: isLast
          ? `${String(turn)}턴 결과 (마지막)`
          : `${String(turn)}턴 결과`,
      }),
    ).toBeVisible();
    await expect(turnCard(page)).toBeVisible();
    if (!isLast) {
      await checkpoint(run, `turn-${String(turn)}`, { screenshot: false });
    }
  }
}

// A finished season shows its feed read-only: no order button.
export async function expectFinished(page: Page, run: Run): Promise<void> {
  await expect(page.getByText('시즌이 끝났습니다')).toBeVisible();
  await expect(feedRegion(page)).toBeVisible();
  await expect(page.getByRole('button', { name: /매수|매도/ })).toHaveCount(0);
  await checkpoint(run, 'finished', { axe: 'finished' });
}

export async function openReport(page: Page): Promise<void> {
  await page.getByRole('link', { name: '성적표 보기' }).click();
  await expect(
    page.getByRole('heading', { name: /^시즌 \d+ 성적표/ }),
  ).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
}

// The review is the only screen with real dates.
export async function openReview(
  page: Page,
  run: Run,
  turns: number,
): Promise<void> {
  await page.getByRole('link', { name: '복기 보기' }).click();
  await expect(
    page.getByRole('heading', { name: /^시즌 \d+ 복기$/ }),
  ).toBeVisible();
  await expect(page.getByText('경로 시드', { exact: true })).toBeVisible();
  await expect(page.getByText('엔진 버전', { exact: true })).toBeVisible();
  await expect(page.getByText('데이터 기준일', { exact: true })).toBeVisible();
  await expect(
    page
      .getByRole('region', { name: '턴별 복기', exact: true })
      .locator('ol > li'),
  ).toHaveCount(turns);
  await expect(page.getByText('사유: 종단 시험')).toBeVisible();
  await checkpoint(run, 'review', { axe: 'review', dates: 'present' });
}
