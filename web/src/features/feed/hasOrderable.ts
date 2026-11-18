import type { Season } from '../seasons/labels.ts';
import { canTrade } from '../seasons/seasonGate.ts';
import type { FeedItem } from './orderSource.ts';

// True when at least one item can start an order in this season.
export function hasOrderable(items: FeedItem[], season: Season): boolean {
  return canTrade(season) && items.some((item) => item.assetId !== null);
}
