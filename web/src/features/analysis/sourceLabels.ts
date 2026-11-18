import type { components } from '../../api/schema';

type OrderSource = components['schemas']['OrderSource'];

export const SOURCE_LABEL = {
  RANKING: '랭킹',
  FEED: '피드',
  ALERT: '알림',
  BENCH: '벤치',
  DIRECT: '직접',
} satisfies Record<OrderSource, string>;
