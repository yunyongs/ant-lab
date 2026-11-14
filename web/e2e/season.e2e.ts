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

  // 07 and 08 turns 1 and 2
  await page.getByRole('button', { name: '다음 턴 (1턴으로)' }).click();
  await expect(page.getByRole('heading', { name: '1턴 결과' })).toBeVisible();
  await expect(turnCard).toBeVisible();
  await checkpoint(run, 'turn-1', { axe: 'turn-result' });
  await page.getByRole('button', { name: '다음 턴 (2턴으로)' }).click();
  await expect(page.getByRole('heading', { name: '2턴 결과' })).toBeVisible();
  await expect(turnCard).toBeVisible();
  await checkpoint(run, 'turn-2');

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
