import type { components } from '../../api/schema';

type FeedItemDto = components['schemas']['FeedItemDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; sentences are invented.
export function feedItem(overrides: Partial<FeedItemDto> = {}): FeedItemDto {
  return {
    turnNo: 5,
    seq: 1,
    assetId: 101,
    body: '종목 가의 이번 달 소식입니다.',
    ...overrides,
  };
}

// Three items of turn 5 in an order that is not sorted by id or sentence.
export function feedOfTurn(turnNo = 5): FeedItemDto[] {
  return [
    feedItem({
      turnNo,
      seq: 1,
      assetId: 102,
      body: '나 소식: 거래가 늘었습니다.',
    }),
    feedItem({
      turnNo,
      seq: 2,
      assetId: null,
      body: '가 소식: 시장이 조용했습니다.',
    }),
    feedItem({
      turnNo,
      seq: 3,
      assetId: 101,
      body: '다 소식: 일부에서 급등이 보였습니다.',
    }),
  ];
}

// Two alerts of a turn; their seq continues after the turn's feed.
export function alertsOfTurn(turnNo = 5): FeedItemDto[] {
  return [
    feedItem({
      turnNo,
      seq: 4,
      assetId: 102,
      body: '나 알림: 오늘의 흐름을 확인해 보세요.',
    }),
    feedItem({
      turnNo,
      seq: 5,
      assetId: null,
      body: '다 알림: 놓치기 전에 살펴보세요.',
    }),
  ];
}
