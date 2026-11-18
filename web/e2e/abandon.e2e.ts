import { expect, test } from '@playwright/test';
import { attachGuards } from './support/guards.ts';
import { newMember } from './support/member.ts';
import {
  checkpoint,
  expectNoOverflow,
  startRun,
  writeAxeSummary,
} from './support/steps.ts';

const ABANDON_AT = 3;

test('abandons a 12-turn season at turn 3 and still gets the report', async ({
  page,
}, testInfo) => {
  const project = testInfo.project.name;
  const run = startRun(page, `${project}-abandon`);
  const guards = attachGuards(page);
  const member = newMember(project);
  const sheet = page.getByRole('dialog');

  // sign up (the app logs in right after)
  await page.goto('/signup');
  await page.getByLabel('이메일').fill(member.email);
  await page.getByLabel('표시 이름').fill(member.name);
  await page.getByLabel('비밀번호', { exact: true }).fill(member.password);
  await page.getByRole('button', { name: '가입하고 로그인' }).click();
  await expect(page.getByRole('heading', { name: '내 시즌' })).toBeVisible();
  guards.setLoggedIn(true);

  // a 12-turn season with a -20% risk limit, one share of the first pool row
  await page.getByRole('link', { name: '첫 시즌 시작' }).click();
  await page.getByRole('radio', { name: '-20%' }).check();
  await page.getByRole('button', { name: '시즌 시작', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: '0턴 팀 구성' }),
  ).toBeVisible();
  await page.getByRole('button', { name: /선택$/ }).first().click();
  await expect(sheet).toBeVisible();
  await sheet.getByRole('button', { name: '1주 매수' }).click();
  await expect(sheet.getByText('체결가')).toBeVisible();
  await sheet.getByRole('button', { name: '닫기' }).click();
  await expect(sheet).toBeHidden();

  // advance to the abandon turn
  for (let turn = 1; turn <= ABANDON_AT; turn += 1) {
    await page
      .getByRole('button', { name: `다음 턴 (${String(turn)}턴으로)` })
      .click();
    await expect(
      page.getByRole('heading', { name: `${String(turn)}턴 결과` }),
    ).toBeVisible();
  }
  await checkpoint(run, 'before-abandon', { axe: 'abandon-entry' });

  // the confirm step names the consequences; cancel changes nothing
  await page.getByRole('button', { name: '시즌 포기' }).click();
  const dialog = page.getByRole('dialog', { name: '시즌을 포기할까요?' });
  await expect(dialog).toBeVisible();
  await expect(
    dialog.getByText('남은 9턴을 매매 없이 자동으로 진행합니다.'),
  ).toBeVisible();
  await expect(
    dialog.getByRole('button', { name: '포기하지 않기' }),
  ).toBeFocused();
  await checkpoint(run, 'abandon-dialog', { axe: 'abandon-dialog' });
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(
    page.getByRole('heading', { name: `${String(ABANDON_AT)}턴 결과` }),
  ).toBeVisible();

  // confirm: the season ends on the finished screen with the abandon label
  await page.getByRole('button', { name: '시즌 포기' }).click();
  await dialog.getByRole('button', { name: '포기하고 끝까지 진행' }).click();
  await expect(page.getByText('시즌이 끝났습니다')).toBeVisible();
  await expect(
    page.getByText(`${String(ABANDON_AT)}턴에서 포기`, { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: '시즌 포기' })).toHaveCount(0);
  await checkpoint(run, 'abandoned-finished', { axe: 'abandoned-finished' });

  // the report opens like for any finished season
  await page.getByRole('link', { name: '성적표 보기' }).click();
  await expect(
    page.getByRole('heading', { name: /^시즌 \d+ 성적표/ }),
  ).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expectNoOverflow(page, 'abandoned-report');

  writeAxeSummary(run);
  guards.assertClean();
});
