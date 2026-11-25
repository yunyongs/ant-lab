import { expect, test } from '@playwright/test';
import { attachGuards } from './support/guards.ts';
import { newMember } from './support/member.ts';
import { startSeasonQuick } from './support/seasons.ts';
import {
  menuButton,
  openAbandonDialog,
  signUpDirect,
} from './support/shell.ts';
import {
  checkpoint,
  expectNoOverflow,
  startRun,
  writeAxeSummary,
} from './support/steps.ts';
import { buyFromPool } from './support/team.ts';
import { advanceTo, openReport } from './support/turn.ts';

const ABANDON_AT = 3;

test('abandons a 12-turn season at turn 3 and still gets the report', async ({
  page,
}, testInfo) => {
  const project = testInfo.project.name;
  const run = startRun(page, `${project}-abandon`);
  const guards = attachGuards(page);
  const member = newMember(project);

  // sign up (the app logs in right after)
  await signUpDirect(page, guards, member);

  // a 12-turn season with a -20% risk limit, one share of the first pool row
  await startSeasonQuick(page);
  await buyFromPool(page, 0, 1);

  // advance to the abandon turn
  for (let turn = 1; turn <= ABANDON_AT; turn += 1) {
    await advanceTo(page, turn);
  }
  await checkpoint(run, 'before-abandon', { axe: 'abandon-entry' });

  // the confirm step names the consequences; cancel changes nothing
  await openAbandonDialog(page);
  const dialog = page.getByRole('dialog', { name: '시즌을 포기할까요?' });
  await expect(
    dialog.getByText('남은 9턴을 매매 없이 자동으로 진행합니다.'),
  ).toBeVisible();
  await expect(
    dialog.getByRole('button', { name: '포기하지 않기' }),
  ).toBeFocused();
  await checkpoint(run, 'abandon-dialog', { axe: 'abandon-dialog' });
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(menuButton(page)).toBeFocused();
  await expect(
    page.getByRole('heading', { name: `${String(ABANDON_AT)}턴 결과` }),
  ).toBeVisible();

  // confirm: the season ends on the finished screen with the abandon label
  await openAbandonDialog(page);
  await dialog.getByRole('button', { name: '포기하고 끝까지 진행' }).click();
  await expect(page.getByText('시즌이 끝났습니다')).toBeVisible();
  await expect(
    page.getByText(`${String(ABANDON_AT)}턴에서 포기`, { exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: '메뉴' }).click();
  await expect(page.getByRole('button', { name: '시즌 포기…' })).toHaveCount(0);
  await page.keyboard.press('Escape');
  await checkpoint(run, 'abandoned-finished', { axe: 'abandoned-finished' });

  // the report opens like for any finished season
  await openReport(page);
  await expectNoOverflow(page, 'abandoned-report');

  writeAxeSummary(run);
  guards.assertClean();
});
