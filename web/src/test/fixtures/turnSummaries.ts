import type { components } from '../../api/schema';

type TurnSummaryDto = components['schemas']['TurnSummaryDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented.
// Turn 0 is the initial capital with drawdown and return 0.
const ROWS: Omit<TurnSummaryDto, 'turnNo'>[] = [
  {
    totalValue: '100000000',
    drawdown: '0.00000000',
    periodReturn: '0.00000000',
  },
  {
    totalValue: '103300000',
    drawdown: '0.00000000',
    periodReturn: '0.03300000',
  },
  {
    totalValue: '101250000',
    drawdown: '-0.01984511',
    periodReturn: '-0.01984511',
  },
  {
    totalValue: '104820000',
    drawdown: '0.00000000',
    periodReturn: '0.03525926',
  },
  {
    totalValue: '97000000',
    drawdown: '-0.07460408',
    periodReturn: '-0.07460408',
  },
  {
    totalValue: '99100000',
    drawdown: '-0.05456974',
    periodReturn: '0.02164948',
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
