import type { components } from '../../api/schema';

type SeasonDto = components['schemas']['SeasonDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented.
export function seasonFixture(overrides: Partial<SeasonDto> = {}): SeasonDto {
  return {
    id: 7,
    status: 'IN_PROGRESS',
    currentTurnNo: 0,
    pathLength: 12,
    riskLimit: '-0.20000000',
    startFormation: {
      goalkeeper: 40,
      defender: 30,
      midfielder: 20,
      forward: 10,
    },
    currentFormation: {
      goalkeeper: 40,
      defender: 30,
      midfielder: 20,
      forward: 10,
    },
    initialCash: '100000000',
    cashBalance: '100000000',
    feeRateKr: '0.00015000',
    feeRateUs: '0.00025000',
    fxSpreadRate: '0.00400000',
    ...overrides,
  };
}

export const seasonList: SeasonDto[] = [
  seasonFixture({
    id: 9,
    currentTurnNo: 3,
    pathLength: 24,
    riskLimit: '-0.30000000',
    cashBalance: '62300000',
  }),
  seasonFixture({ id: 7 }),
  seasonFixture({
    id: 4,
    status: 'FINISHED',
    currentTurnNo: 12,
    cashBalance: '104500000',
  }),
];
