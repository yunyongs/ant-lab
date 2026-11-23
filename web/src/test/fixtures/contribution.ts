import type { components } from '../../api/schema';

type TurnContributionDto = components['schemas']['TurnContributionDto'];
type HoldingContributionDto = components['schemas']['HoldingContributionDto'];
type PositionContributionDto = components['schemas']['PositionContributionDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented.
export function holdingContributionFixture(
  overrides: Partial<HoldingContributionDto> = {},
): HoldingContributionDto {
  return {
    assetId: 101,
    declaredPosition: 'MIDFIELDER',
    quantity: 40,
    previousPrice: '250000',
    price: '294186',
    value: '11767440',
    contribution: '1767440',
    priceReturn: '0.17674400',
    contributionReturn: '0.01710978',
    ...overrides,
  };
}

function position(
  overrides: Partial<PositionContributionDto> = {},
): PositionContributionDto {
  return {
    value: '0',
    previousValue: '0',
    valueChange: '0',
    contribution: '0',
    contributionReturn: '0.00000000',
    ...overrides,
  };
}

export function contributionFixture(
  overrides: Partial<TurnContributionDto> = {},
): TurnContributionDto {
  return {
    seasonId: 7,
    turnNo: 5,
    previousTotalValue: '103300000',
    totalValue: '104820000',
    valueChange: '1520000',
    holdingsContribution: '1537440',
    tradingCost: '17440',
    totalReturn: '0.04820000',
    positions: {
      goalkeeper: position({
        value: '12000000',
        previousValue: '12000000',
      }),
      defender: position({
        value: '30000000',
        previousValue: '30000000',
      }),
      midfielder: position({
        value: '40000000',
        previousValue: '38232560',
        valueChange: '1767440',
        contribution: '1767440',
        contributionReturn: '0.01710978',
      }),
      forward: position({
        value: '22820000',
        previousValue: '23050000',
        valueChange: '-230000',
        contribution: '-230000',
        contributionReturn: '-0.00222652',
      }),
    },
    holdings: [
      holdingContributionFixture(),
      holdingContributionFixture({
        assetId: 103,
        declaredPosition: 'DEFENDER',
        quantity: 300,
        previousPrice: '100000',
        price: '100000',
        value: '30000000',
        contribution: '0',
        priceReturn: '0.00000000',
        contributionReturn: '0.00000000',
      }),
      holdingContributionFixture({
        assetId: 102,
        declaredPosition: 'FORWARD_BREAKOUT',
        quantity: 10,
        previousPrice: '2305000',
        price: '2282000',
        value: '22820000',
        contribution: '-230000',
        priceReturn: '-0.00997831',
        contributionReturn: '-0.00222652',
      }),
    ],
    benchmark: {
      value: '102800000',
      previousValue: '102300000',
      valueChange: '500000',
      periodReturn: '0.00488759',
      totalReturn: '0.02800000',
      excessReturn: '0.02020000',
    },
    ...overrides,
  };
}
