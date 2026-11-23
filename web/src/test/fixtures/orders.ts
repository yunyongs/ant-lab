import type { components } from '../../api/schema';

type OrderResultDto = components['schemas']['OrderResultDto'];
type OrderQuoteDto = components['schemas']['OrderQuoteDto'];

export function orderResultFixture(
  overrides: Partial<OrderResultDto> = {},
): OrderResultDto {
  return {
    orderId: 31,
    turnNo: 0,
    side: 'BUY',
    assetId: 101,
    quantity: 13,
    fillPrice: '187400',
    notional: '2436200',
    fee: '365',
    cashBalance: '97563435',
    holdingQuantity: 13,
    avgPrice: '187400.0000',
    realizedPnl: null,
    declaredPosition: 'MIDFIELDER',
    formationChangeId: null,
    ...overrides,
  };
}

// One share of 종목 가 at turn 0 from the starting cash (fee 0.015%).
export function orderQuoteFixture(
  overrides: Partial<OrderQuoteDto> = {},
): OrderQuoteDto {
  return {
    fillPrice: '187400',
    notional: '187400',
    fee: '28',
    fxCost: '0',
    cashAfter: '99812572',
    maxBuyQuantity: 533,
    ...overrides,
  };
}
