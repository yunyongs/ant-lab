import type { components } from '../../api/schema';

type OrderResultDto = components['schemas']['OrderResultDto'];

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
