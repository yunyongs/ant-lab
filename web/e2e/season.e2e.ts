import { expect, test } from '@playwright/test';
import { attachGuards } from './support/guards.ts';
import { newMember } from './support/member.ts';
import {
  checkpoint,
  expectNoOverflow,
  startRun,
  writeAxeSummary,
} from './support/steps.ts';

const TURNS = 12;

test('plays one 12-turn season from signup to review', async ({
  page,
}, testInfo) => {
  const project = testInfo.project.name;
  const run = startRun(page, project);
  const guards = attachGuards(page);
  const member = newMember(project);
  const nav = page.getByRole('navigation', { name: '주 메뉴' });
  const sheet = page.getByRole('dialog');
  const turnCard = page.getByRole('region', { name: '턴 결과', exact: true });

  // 01 cold start: the visitor is sent to the login form
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible();
  await checkpoint(run, 'login', { axe: 'login' });

  // 02 sign up (the app logs in right after)
  await page.getByRole('link', { name: '처음이세요? 계정 만들기' }).click();
  await expect(
    page.getByRole('heading', { name: '계정 만들기' }),
  ).toBeVisible();
  await page.getByLabel('이메일').fill(member.email);
  await page.getByLabel('표시 이름').fill(member.name);
  await page.getByLabel('비밀번호', { exact: true }).fill(member.password);
  await checkpoint(run, 'signup-form', { axe: 'signup' });
  await page.getByRole('button', { name: '가입하고 로그인' }).click();
  await expect(page.getByRole('heading', { name: '내 시즌' })).toBeVisible();
  guards.setLoggedIn(true);

  // 03 home: empty list, status line reached the API
  await expect(page.getByText('아직 시즌이 없습니다')).toBeVisible();
  await expect(page.getByText(/^API 연결됨 · 종목 \d+개$/)).toBeVisible();
  await checkpoint(run, 'home-empty', { axe: 'home' });

  // 04 new season: the risk limit is a deliberate choice, the rest is default
  await page.getByRole('link', { name: '첫 시즌 시작' }).click();
  await expect(page.getByRole('heading', { name: '새 시즌' })).toBeVisible();
  await page.getByRole('radio', { name: '-20%' }).check();
  await expect(page.getByRole('radio', { name: '12턴' })).toBeChecked();
  await checkpoint(run, 'new-season', { axe: 'new-season' });
  await page.getByRole('button', { name: '시즌 시작', exact: true }).click();
  await expect(page).toHaveURL(/\/seasons\/\d+$/);
  const seasonId = /\/seasons\/(\d+)$/.exec(page.url())?.[1] ?? '';
  await expect(
    page.getByRole('heading', { name: '0턴 팀 구성' }),
  ).toBeVisible();
  await expect(page.getByText(`0/${String(TURNS)}턴`)).toBeVisible();
  await checkpoint(run, 'team-build', { axe: 'team-build' });

  // 04b pool browser: filter and sort live in the address; clearing restores the list
  const poolRegion = page.getByRole('region', { name: '종목 풀' });
  await page.getByLabel('시장').selectOption('US');
  await expect(page).toHaveURL(/[?&]market=US/);
  await expect(poolRegion.getByRole('status')).toHaveText(
    /^전체 \d+개 중 \d+개$/,
  );
  await page.getByLabel('정렬').selectOption('vol-asc');
  await expect(page).toHaveURL(/[?&]sort=vol-asc/);
  await checkpoint(run, 'pool-filtered', { axe: 'pool-filtered' });
  await page.getByRole('button', { name: '조건 지우기' }).click();
  await expect(page).not.toHaveURL(/[?&](market|sort)=/);
  await expectNoOverflow(page, 'pool-cleared');

  // 04c a 180-asset pool with long names must not widen the page (pool is
  // cached per session, so reload to refetch, then restore the real pool)
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
  await expect(page.getByRole('region', { name: '종목 풀' })).toBeVisible();
  await expect(
    page.getByRole('region', { name: '종목 풀' }).getByRole('status'),
  ).toHaveText('180개');
  await checkpoint(run, 'pool-180', { axe: 'pool-180' });
  await page.unroute('**/api/v1/pool');
  await page.reload();
  await expect(
    page.getByRole('heading', { name: '0턴 팀 구성' }),
  ).toBeVisible();

  // 05 first buy: two shares of the first pool row
  const picks = page.getByRole('button', { name: /선택$/ });
  await picks.first().click();
  await expect(sheet).toBeVisible();
  await expect(page.getByLabel('선언 포지션')).not.toHaveValue('');
  await page.getByLabel('수량', { exact: true }).fill('2');
  await checkpoint(run, 'order-sheet', { axe: 'order-sheet' });
  await sheet.getByRole('button', { name: '2주 매수' }).click();
  await expect(sheet.getByText('체결가')).toBeVisible();
  await expect(sheet.getByText('수수료')).toBeVisible();
  await checkpoint(run, 'buy-result');
  await sheet.getByRole('button', { name: '닫기' }).click();
  await expect(sheet).toBeHidden();

  // 06 second buy: one share of the second row
  await picks.nth(1).click();
  await expect(sheet).toBeVisible();
  await sheet.getByRole('button', { name: '1주 매수' }).click();
  await expect(sheet.getByText('체결가')).toBeVisible();
  await sheet.getByRole('button', { name: '닫기' }).click();
  await expect(sheet).toBeHidden();
  await expect(page.getByText(/^보유 \d+주 · /)).toHaveCount(2);
  await checkpoint(run, 'team-built');

  // 06b projection page: opt-in, because it needs the engine service and
  // enough price history in the stack (the guards fail on any 4xx/5xx)
  if (process.env.E2E_PROJECTION === '1') {
    await nav.getByRole('link', { name: '매매' }).click();
    await page.getByRole('link', { name: '예상 분포 보기' }).click();
    await expect(
      page.getByRole('heading', { name: '예상 분포' }),
    ).toBeVisible();
    await expect(page.getByText(/예측이 아닙니다/).first()).toBeVisible();
    await expect(
      page.getByRole('img', { name: /예상 분포 띠 그림/ }),
    ).toBeVisible({ timeout: 30_000 });
    await checkpoint(run, 'projection', { axe: 'projection' });
    await page.getByRole('link', { name: '시즌으로 돌아가기' }).click();
    await expect(
      page.getByRole('heading', { name: '0턴 팀 구성' }),
    ).toBeVisible();
  }

  // 07 and 08 turns 1 and 2
  await page.getByRole('button', { name: '다음 턴 (1턴으로)' }).click();
  await expect(page.getByRole('heading', { name: '1턴 결과' })).toBeVisible();
  await expect(turnCard).toBeVisible();
  // The actions stay in reach without scrolling, summary first (WEB-14).
  await expect(
    page.getByRole('region', { name: '요약', exact: true }),
  ).toBeInViewport();
  const actions = page.getByRole('group', { name: '턴 행동' });
  await expect(
    actions.getByRole('button', { name: '다음 턴 (2턴으로)' }),
  ).toBeInViewport();
  await expect(actions.getByRole('link', { name: '매매' })).toBeInViewport();
  // The bar must end at or above the menu's top edge.
  const barBox = await actions.boundingBox();
  const navBox = await page
    .getByRole('navigation', { name: '주 메뉴' })
    .boundingBox();
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
  const navEnd = await page
    .getByRole('navigation', { name: '주 메뉴' })
    .boundingBox();
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
  await expect(page.getByRole('region', { name: '소식' })).toBeVisible();
  await checkpoint(run, 'turn-1', { axe: 'turn-result' });
  await page.getByRole('button', { name: '다음 턴 (2턴으로)' }).click();
  await expect(page.getByRole('heading', { name: '2턴 결과' })).toBeVisible();
  await expect(turnCard).toBeVisible();
  // The ranking board: first tab selected, the keyboard moves to the next.
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

  // 09 formation change at turn 2
  await nav.getByRole('link', { name: '매매' }).click();
  await expect(page.getByRole('heading', { name: '2턴 매매' })).toBeVisible();
  await page.getByRole('link', { name: '전술 변경' }).click();
  await expect(
    page.getByRole('heading', { name: '2턴 전술 변경' }),
  ).toBeVisible();
  await page.getByLabel('골키퍼', { exact: true }).fill('30');
  await page.getByLabel('수비수', { exact: true }).fill('30');
  await page.getByLabel('미드필더', { exact: true }).fill('25');
  await page.getByLabel('공격수', { exact: true }).fill('15');
  await page.getByLabel('사유 (선택, 한 줄)').fill('종단 시험');
  await checkpoint(run, 'formation-form', { axe: 'formation' });
  await page.getByRole('button', { name: '전술 변경 적용' }).click();
  await expect(page.getByText('2턴 전술을 바꿨습니다')).toBeVisible();
  await checkpoint(run, 'formation-result');

  // 10 one sell, after the change so its result shows the tie
  await page.getByRole('link', { name: '매매로 돌아가기' }).click();
  await expect(page.getByRole('heading', { name: '2턴 매매' })).toBeVisible();
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

  // 11 turns 3 to 12, each waiting for the next turn number
  await nav.getByRole('link', { name: '턴' }).click();
  await expect(page.getByRole('heading', { name: '2턴 결과' })).toBeVisible();
  for (let turn = 3; turn <= TURNS; turn += 1) {
    const last = turn === TURNS;
    await page
      .getByRole('button', {
        name: last ? /^마지막 턴 넘기기/ : `다음 턴 (${String(turn)}턴으로)`,
      })
      .click();
    await expect(
      page.getByRole('heading', {
        name: last
          ? `${String(turn)}턴 결과 (마지막)`
          : `${String(turn)}턴 결과`,
      }),
    ).toBeVisible();
    await expect(turnCard).toBeVisible();
    if (!last) {
      await checkpoint(run, `turn-${String(turn)}`, { screenshot: false });
    }
  }
  await expect(page.getByText('시즌이 끝났습니다')).toBeVisible();
  // A finished season shows its feed read-only: no order button.
  await expect(page.getByRole('region', { name: '소식' })).toBeVisible();
  await expect(page.getByRole('button', { name: /매수|매도/ })).toHaveCount(0);
  await checkpoint(run, 'finished', { axe: 'finished' });

  // 12 report
  await page.getByRole('link', { name: '성적표 보기' }).click();
  await expect(
    page.getByRole('heading', { name: /^시즌 \d+ 성적표/ }),
  ).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await checkpoint(run, 'report', { axe: 'report' });

  // 13 review: the only screen with real dates
  await page.getByRole('link', { name: '복기 보기' }).click();
  await expect(
    page.getByRole('heading', { name: /^시즌 \d+ 복기$/ }),
  ).toBeVisible();
  await expect(page.getByText('경로 시드', { exact: true })).toBeVisible();
  await expect(page.getByText('엔진 버전', { exact: true })).toBeVisible();
  await expect(page.getByText('데이터 기준일', { exact: true })).toBeVisible();
  await expect(
    page.getByRole('region', { name: '턴별 복기' }).locator('ol > li'),
  ).toHaveCount(TURNS);
  await expect(page.getByText('사유: 종단 시험')).toBeVisible();
  await checkpoint(run, 'review', { axe: 'review', dates: 'present' });

  // Tokens live in memory only; the refresh cookie is HttpOnly.
  const storage = await page.evaluate(() => ({
    local: localStorage.length,
    session: sessionStorage.length,
    cookie: document.cookie,
  }));
  expect(storage.local, 'localStorage entries').toBe(0);
  expect(storage.session, 'sessionStorage entries').toBe(0);
  expect(storage.cookie, 'script-readable cookies').not.toContain(
    'antlab_refresh',
  );
  const cookies = await page.context().cookies();
  expect(
    cookies.some((cookie) => cookie.httpOnly && cookie.secure),
    'an HttpOnly Secure refresh cookie is set',
  ).toBe(true);

  // 14 logout, then the bearer probe
  guards.setLoggedIn(false);
  await page.getByRole('button', { name: '로그아웃' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible();
  await expectNoOverflow(page, 'logged-out');
  expect(guards.bearerRequests(), 'season requests seen').toBeGreaterThan(0);
  const probe = await page.request.get('/api/v1/seasons');
  expect(probe.status()).toBe(401);
  expect(probe.headers()['content-type']).toContain('application/problem+json');
  expect(((await probe.json()) as { code?: string }).code).toBe(
    'UNAUTHENTICATED',
  );
  await page.goto(`/seasons/${seasonId}`);
  await expect(page).toHaveURL(/\/login/);

  writeAxeSummary(run);
  guards.assertClean();
});
