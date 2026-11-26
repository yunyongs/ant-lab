import type { components } from '../../api/schema';

type TurnSummaryDto = components['schemas']['TurnSummaryDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented.
// Turn 0 is the initial capital with drawdown and return 0.
const ROWS: Omit<TurnSummaryDto, 'turnNo'>[] = [
  {
    totalValue: '100000000',
    drawdown: '0.00000000',
    periodReturn: '0.00000000',
    benchmarkValue: '100000000',
  },
  {
    totalValue: '103300000',
    drawdown: '0.00000000',
    periodReturn: '0.03300000',
    benchmarkValue: '100512000',
  },
  {
    totalValue: '101250000',
    drawdown: '-0.01984511',
    periodReturn: '-0.01984511',
    benchmarkValue: '99870000',
  },
  {
    totalValue: '104820000',
    drawdown: '0.00000000',
    periodReturn: '0.03525926',
    benchmarkValue: '101230000',
  },
  {
    totalValue: '97000000',
    drawdown: '-0.07460408',
    periodReturn: '-0.07460408',
    benchmarkValue: '98650000',
  },
  {
    totalValue: '99100000',
    drawdown: '-0.05456974',
    periodReturn: '0.02164948',
    benchmarkValue: '99410000',
  },
];

export function turnSummariesFixture(currentTurnNo: number): {
  items: TurnSummaryDto[];
} {
  const items: TurnSummaryDto[] = [];
  for (let turnNo = 0; turnNo <= currentTurnNo; turnNo += 1) {
    const row = ROWS[turnNo % ROWS.length] ?? ROWS[0];
    if (row !== undefined) items.push({ turnNo, ...row });
  }
  return { items };
}

export function turnSummariesWithNullBenchmarkFixture(currentTurnNo: number): {
  items: TurnSummaryDto[];
} {
  const fixture = turnSummariesFixture(currentTurnNo);
  return {
    items: fixture.items.map((item, index) =>
      index === 1 ? { ...item, benchmarkValue: null } : item,
    ),
  };
}
