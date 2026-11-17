import type { FeedItem } from './orderSource.ts';

export interface ItemActions {
  // Buying is always offered; selling only for a held asset.
  sell: boolean;
  disabled: boolean;
}

export interface ItemContext {
  tradable: boolean;
  advancing: boolean;
  inPool: (assetId: number) => boolean;
  held: (assetId: number) => boolean;
}

// Which buttons a feed item gets, from server fields only. An item that names
// no asset gets none, so a missing button says nothing about the sentence.
export function itemActions(
  item: Pick<FeedItem, 'assetId'>,
  ctx: ItemContext,
): ItemActions | null {
  if (!ctx.tradable || item.assetId === null) return null;
  if (!ctx.inPool(item.assetId)) return null;
  return { sell: ctx.held(item.assetId), disabled: ctx.advancing };
}
