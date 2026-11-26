import { test } from '@playwright/test';
import { attachGuards } from './support/guards.ts';
import { newMember } from './support/member.ts';
import { sellOneTied } from './support/order.ts';
import {
  checkSeasonProgress,
  expectEmptyHome,
  startSeason,
} from './support/seasons.ts';
import {
  coldStartToLogin,
  checkLeaderboardFromHeader,
  expectTokensInMemoryOnly,
  logoutAndProbe,
  signUpFromLogin,
} from './support/shell.ts';
import { checkpoint, startRun, writeAxeSummary } from './support/steps.ts';
import {
  browsePool,
  buyFromPool,
  checkBigPool,
  checkProjection,
  expectTeamBuilt,
} from './support/team.ts';
import { backToTrade, changeFormation, openTrade } from './support/trade.ts';
import {
  expectFinished,
  openReport,
  openReview,
  playRemainingTurns,
  playTurnOne,
  playTurnTwo,
} from './support/turn.ts';

const TURNS = 12;

test('plays one 12-turn season from signup to review', async ({
  page,
}, testInfo) => {
  const project = testInfo.project.name;
  const run = startRun(page, project);
  const guards = attachGuards(page);
  const member = newMember(project);

  // 01 cold start, 02 sign up, 03 empty home
  await coldStartToLogin(page, run);
  await signUpFromLogin(page, run, guards, member);
  await checkLeaderboardFromHeader(page, run);
  await expectEmptyHome(page, run);

  // 04 new season, 04b pool browser, 04c a 180-asset pool
  const seasonId = await startSeason(page, run, TURNS);
  await browsePool(page, run);
  await checkBigPool(page, run);

  // 05 first buy: two shares of the first pool row; 06 one share of the second
  await buyFromPool(page, 0, 2, {
    run,
    sheetAxe: true,
    resultName: 'buy-result',
  });
  await buyFromPool(page, 1, 1);
  await expectTeamBuilt(page, run);

  // 06b projection page, opt-in
  if (process.env.E2E_PROJECTION === '1') {
    await checkProjection(page, run, () => openTrade(page));
  }

  // 07 and 08 turns 1 and 2
  await playTurnOne(page, run);
  await checkSeasonProgress(page, run, seasonId, 1);
  await playTurnTwo(page, run);

  // 09 formation change at turn 2, 10 one sell tied to it
  await changeFormation(page, run, 2);
  await backToTrade(page, 2);
  await sellOneTied(page, run);

  // 11 turns 3 to 12
  await playRemainingTurns(page, run, TURNS);
  await expectFinished(page, run);

  // 12 report, 13 review
  await openReport(page);
  await checkpoint(run, 'report', { axe: 'report' });
  await openReview(page, run, TURNS);

  // 14 storage, logout and the bearer probe
  await expectTokensInMemoryOnly(page);
  await logoutAndProbe(page, guards, seasonId);

  writeAxeSummary(run);
  guards.assertClean();
});
