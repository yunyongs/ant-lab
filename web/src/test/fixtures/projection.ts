import type { components } from '../../api/schema';

type ProjectionDto = components['schemas']['ProjectionDto'];
type ProjectionBand = components['schemas']['ProjectionBand'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented.
// Like the server, bands start at turn 1 (no turn-0 row).
// Every figure is distinct, so a test can tell the rows apart.
export const bandRows: ProjectionBand[] = [
  {
    turn: 1,
    p05: '96100000',
    p25: '98200000',
    p50: '100400000',
    p75: '102600000',
    p95: '105100000',
  },
  {
    turn: 2,
    p05: '93300000',
    p25: '97100000',
    p50: '100900000',
    p75: '104700000',
    p95: '109300000',
  },
  {
    turn: 3,
    p05: '90700000',
    p25: '96000000',
    p50: '101300000',
    p75: '106900000',
    p95: '113600000',
  },
  {
    turn: 4,
    p05: '88200000',
    p25: '95100000',
    p50: '101800000',
    p75: '109200000',
    p95: '117900000',
  },
  {
    turn: 5,
    p05: '85900000',
    p25: '94200000',
    p50: '102200000',
    p75: '111400000',
    p95: '122500000',
  },
];

export function projectionFixture(
  overrides: Partial<ProjectionDto> = {},
): ProjectionDto {
  return {
    phase: 'CURRENT',
    turnNo: 4,
    totalValue: '100000000',
    horizonTurns: 60,
    pathCount: 2000,
    riskLimit: '-0.20000000',
    bands: bandRows,
    summaries: [
      {
        turn: 12,
        end: {
          p05: '81234500',
          p25: '92345600',
          p50: '103456700',
          p75: '114567800',
          p95: '125678900',
        },
        mdd: {
          p05: '-0.31000000',
          p10: '-0.24000000',
          p25: '-0.15000000',
          p50: '-0.08000000',
          p75: '-0.04000000',
          p95: '-0.01000000',
        },
        breachProbability: '0.12340000',
      },
      {
        turn: 60,
        end: {
          p05: '71234500',
          p25: '95345600',
          p50: '123456700',
          p75: '154567800',
          p95: '195678900',
        },
        mdd: {
          p05: '-0.42000000',
          p10: '-0.35000000',
          p25: '-0.26000000',
          p50: '-0.17000000',
          p75: '-0.09000000',
          p95: '-0.03000000',
        },
        breachProbability: '0.38760000',
      },
    ],
    ...overrides,
  };
}
