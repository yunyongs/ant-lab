import { expect, type Page } from '@playwright/test';
import { checkSeasonMenu, mainNav } from './shell.ts';
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

// At the end of the page the last ordinary control (outside the action bar)
// is fully clear of the action bar and the bottom menu.
async function expectEndClearOfBars(page: Page): Promise<void> {
  await page.evaluate(() => {
    window.scrollTo(0, document.documentElement.scrollHeight);
  });
  const found = await page.evaluate(() => {
    const bar = document.querySelector('[role="group"][aria-label="턴 행동"]');
    const nav = document.querySelector('nav[aria-label="주 메뉴"]');
    const controls = Array.from(
      document.querySelectorAll<HTMLElement>('main a[href], main button'),
    ).filter(
      (el) =>
        !(bar?.contains(el) ?? false) &&
        el.getClientRects().length > 0 &&
        el.getBoundingClientRect().height > 0,
    );
    const last = controls.reduce<HTMLElement | null>(
      (best, el) =>
        best === null ||
        el.getBoundingClientRect().bottom >= best.getBoundingClientRect().bottom
          ? el
          : best,
      null,
    );
    if (last === null || bar === null || nav === null) return null;
    return {
      bottom: last.getBoundingClientRect().bottom,
      barTop: bar.getBoundingClientRect().top,
      navTop: nav.getBoundingClientRect().top,
    };
  });
  expect(
    found,
    'a last control, the action bar and the menu exist',
  ).not.toBeNull();
  expect(found!.bottom, 'last control above the menu').toBeLessThanOrEqual(
    found!.navTop + 1,
  );
  expect(
    found!.bottom,
    'last control above the action bar',
  ).toBeLessThanOrEqual(found!.barTop + 1);
}

// Turn 1: the actions stay in reach without scrolling, summary first
// (WEB-14), and the bar ends at or above the menu's top edge.
export async function playTurnOne(page: Page, run: Run): Promise<void> {
  await advanceTo(page, 1);
  await expect(turnCard(page)).toBeVisible();
  const summary = page.getByRole('region', { name: '1턴 요약', exact: true });
  await expect(summary).toBeInViewport();
  await expect(summary.getByText('시장 기준선보다')).toBeVisible();
  await expect(
    summary.getByRole('meter', { name: '위험 선언 대비 누적 낙폭' }),
  ).toBeVisible();
  const actions = page.getByRole('group', { name: '턴 행동' });
  await expect(
    actions.getByRole('button', { name: '다음 턴 (2턴으로)' }),
  ).toBeInViewport();
  await expect(actions.getByRole('link', { name: '매매' })).toBeInViewport();
  // Trade on the left, the primary advance on the right.
  const tradeBox = await actions
    .getByRole('link', { name: '매매' })
    .boundingBox();
  const advanceBox = await actions
    .getByRole('button', { name: '다음 턴 (2턴으로)' })
    .boundingBox();
  expect(tradeBox).not.toBeNull();
  expect(advanceBox).not.toBeNull();
  expect(tradeBox!.x).toBeLessThan(advanceBox!.x);
  const barBox = await actions.boundingBox();
  const navBox = await mainNav(page).boundingBox();
  expect(barBox).not.toBeNull();
  expect(navBox).not.toBeNull();
  expect(barBox!.y + barBox!.height).toBeLessThanOrEqual(navBox!.y + 1);
  // No abandon button stays on the page: the action lives in the header menu.
  await expect(page.getByRole('button', { name: '시즌 포기' })).toHaveCount(0);
  await expectEndClearOfBars(page);
  // Every link has an accessible name (the chart's logo link is off).
  await expect(
    page.locator('main a:not([aria-label])').filter({ hasText: /^$/ }),
  ).toHaveCount(0);
  // The feed region is always there: items, or the no-news sentence.
  await expect(feedRegion(page)).toBeVisible();
  await checkpoint(run, 'turn-1', { axe: 'turn-result' });
  await checkSeasonMenu(page);
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
