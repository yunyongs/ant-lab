import type { components } from '../../api/schema';

type SeasonReportDto = components['schemas']['SeasonReportDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented
// and every figure is distinct so a test can tell the rows apart.
export function reportFixture(
  overrides: Partial<SeasonReportDto> = {},
): SeasonReportDto {
  return {
    seasonStatus: 'FINISHED',
    isFinal: true,
    reportTurns: 12,
    pathLength: 12,
    pendingOrders: 0,
    pendingChanges: 0,
    initialCash: '100000000',
    finalValue: '112345600',
    totalReturn: '0.12345600',
    monthlyVolatility: '0.03210000',
    maxDrawdown: '-0.08760000',
    baselineFinalValue: '105432100',
    baselineReturn: '0.05432100',
    excessReturn: '0.06913500',
    riskLimit: '-0.20000000',
    riskBreachTurns: 2,
    riskBreachDepth: '0.01230000',
    maxSingleWeight: '0.31500000',
    tradeNotional: '48765400',
    turnoverAnnual: '0.48765400',
    formationDeviation: '0.21098000',
    roleFit: '0.66670000',
    rolePlayersJudged: 5,
    rolePlayersUnjudged: 3,
    nudgeShare: '0.25000000',
    nudgeSameTurnShare: '0.12500000',
    totalFee: '71234',
    teamFee: '15432',
    formationChangeFee: '3210',
    platformNudgeFee: '1876',
    formationChanges: 3,
    formationChangeOrders: 4,
    changesWithoutOrders: 1,
    reconMismatchRows: 6,
    reconTurnBundles: 11,
    reconManualBundles: 7,
    reconOutOfRangeRows: 8,
    ...overrides,
  };
}

// The four cells the contract allows to be null.
export const reportWithNulls = reportFixture({
  monthlyVolatility: null,
  roleFit: null,
  rolePlayersJudged: 0,
  nudgeShare: null,
  nudgeSameTurnShare: null,
});

// Adds fields the report never carries, to prove only whitelisted fields
// reach the page (and that no date does).
export function poisonedReport(dto: SeasonReportDto): SeasonReportDto {
  return {
    ...dto,
    yearMonth: '2008-09',
    seed: '-8123456789012345678',
    dataSnapshotDate: '2026-11-12',
  } as SeasonReportDto;
}
