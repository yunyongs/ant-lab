import type { components } from '../../api/schema';

export type FeedItem = components['schemas']['FeedItemDto'];
export type OrderSource = NonNullable<
  components['schemas']['PlaceOrderRequest']['source']
>;

// The only shapes the app sends. The tapped item decides the source; it is
// never upgraded, downgraded or replaced by DIRECT.
export function fromFeed(item: Pick<FeedItem, 'turnNo' | 'seq'>): OrderSource {
  return { kind: 'FEED', feedTurnNo: item.turnNo, feedSeq: item.seq };
}

export function fromRanking(): OrderSource {
  return { kind: 'RANKING' };
}

export function fromAlert(item: Pick<FeedItem, 'turnNo' | 'seq'>): OrderSource {
  return { kind: 'ALERT', feedTurnNo: item.turnNo, feedSeq: item.seq };
}
