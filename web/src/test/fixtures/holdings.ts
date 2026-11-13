import type { components } from '../../api/schema';

type HoldingsDto = components['schemas']['HoldingsDto'];
type HoldingDto = components['schemas']['HoldingDto'];

// Turn 0, nothing bought: all value is cash, which counts as the goalkeeper.
export function holdingsFixture(
  overrides: Partial<HoldingsDto> = {},
): HoldingsDto {
  return {
    seasonId: 7,
    turnNo: 0,
    cashBalance: '100000000',
    holdingsValue: '0',
    totalValue: '100000000',
    goalkeeperValue: '100000000',
    defenderValue: '0',
    midfielderValue: '0',
    forwardValue: '0',
    goalkeeperWeight: '1.00000000',
    defenderWeight: '0.00000000',
    midfielderWeight: '0.00000000',
    forwardWeight: '0.00000000',
    currentFormation: {
      goalkeeper: 40,
      defender: 30,
      midfielder: 20,
      forward: 10,
    },
    goalkeeperGap: '0.60000000',
    defenderGap: '-0.30000000',
    midfielderGap: '-0.20000000',
    forwardGap: '-0.10000000',
    formationDeviation: '1.20000000',
    holdings: [],
    ...overrides,
  };
}

export const heldDefender: HoldingDto = {
  assetId: 103,
  quantity: 30,
  avgPrice: '10450.0000',
  declaredPosition: 'DEFENDER',
  price: '10450',
  value: '313500',
  weight: '0.00313500',
};

// Numbers on purpose unrelated to anything a browser could derive from one
// order, so a test can tell a refetched value from a computed one.
export const holdingsAfterBuy: HoldingsDto = holdingsFixture({
  cashBalance: '97112345',
  holdingsValue: '2887655',
  totalValue: '100000000',
  goalkeeperValue: '96812345',
  defenderValue: '313500',
  midfielderValue: '2574155',
  goalkeeperWeight: '0.96812345',
  defenderWeight: '0.00313500',
  midfielderWeight: '0.02574155',
  goalkeeperGap: '0.56812345',
  defenderGap: '-0.29686500',
  midfielderGap: '-0.17425845',
  holdings: [
    heldDefender,
    {
      assetId: 101,
      quantity: 13,
      avgPrice: '187400.0000',
      declaredPosition: 'MIDFIELDER',
      price: '187400',
      value: '2574155',
      weight: '0.02574155',
    },
  ],
});
