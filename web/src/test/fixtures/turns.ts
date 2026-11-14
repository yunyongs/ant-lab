import type { components } from '../../api/schema';

type TurnResultDto = components['schemas']['TurnResultDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented.
// The weights add to 0.99999998 on purpose: the screen prints what is sent.
export function turnResultFixture(
  overrides: Partial<TurnResultDto> = {},
): TurnResultDto {
  return {
    seasonId: 7,
    turnNo: 5,
    status: 'IN_PROGRESS',
    pathLength: 12,
    cashBalance: '12000000',
    holdingsValue: '92820000',
    totalValue: '104820000',
    goalkeeperValue: '12000000',
    defenderValue: '30000000',
    midfielderValue: '40000000',
    forwardValue: '22820000',
    drawdown: '-0.04120000',
    previousTotalValue: '103300000',
    valueChange: '1520000',
    periodReturn: '0.01471442',
    riskLimit: '-0.20000000',
    riskHeadroom: '0.15880000',
    riskBreached: false,
    goalkeeperWeight: '0.11448',
    defenderWeight: '0.28620000',
    midfielderWeight: '0.38160000',
    forwardWeight: '0.21771998',
    targetFormation: {
      goalkeeper: 40,
      defender: 30,
      midfielder: 20,
      forward: 10,
    },
    goalkeeperGap: '-0.28552000',
    defenderGap: '-0.01380000',
    midfielderGap: '0.18160000',
    forwardGap: '0.11771998',
    formationDeviation: '0.59864000',
    ...overrides,
  };
}

export const turnBreached = turnResultFixture({
  turnNo: 6,
  totalValue: '78000000',
  valueChange: '-26820000',
  periodReturn: '-0.25587002',
  drawdown: '-0.22000000',
  riskHeadroom: '-0.02000000',
  riskBreached: true,
});

export const turnFinal = turnResultFixture({
  turnNo: 12,
  status: 'FINISHED',
});

// Adds fields the server never sends on this endpoint, to prove the screen
// shows whitelisted fields only.
export function poisoned(dto: TurnResultDto): TurnResultDto {
  return {
    ...dto,
    yearMonth: '2008-09',
    monthBlock: 987654,
    seed: 'SEED-XYZ',
  } as TurnResultDto;
}
