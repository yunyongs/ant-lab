import type { components } from '../../api/schema';

type TurnPricesDto = components['schemas']['TurnPricesDto'];

export function pricesFixture(turnNo = 0): TurnPricesDto {
  return {
    turnNo,
    prices: [
      { assetId: 101, price: '187400' },
      { assetId: 102, price: '52300' },
      { assetId: 103, price: '10450' },
    ],
  };
}
