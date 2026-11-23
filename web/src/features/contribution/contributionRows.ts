import { compareDecimal } from '../format/compareDecimal.ts';
import { positionLabel } from '../seasons/labels.ts';
import type { PoolAsset } from '../team/queries.ts';
import { UNKNOWN_ASSET_NAME } from '../trade/HoldingCard.tsx';
import type { HoldingContribution } from './queries.ts';

export const TOP_MOVERS = 3;

export interface ContributionRow {
  assetId: number;
  name: string;
  position: string;
  quantity: number;
  contribution: string;
  priceReturn: string;
}

export interface ContributionLists {
  // Largest positive contributions first.
  up: ContributionRow[];
  // Largest negative contributions first.
  down: ContributionRow[];
  // Every holding in the server's order (largest contribution first).
  all: ContributionRow[];
  // True when some holding is in neither top list.
  hasMore: boolean;
}

// The server sends holdings largest contribution first; the top lists are cut
// from both ends of that order without re-sorting. A zero contribution is in
// neither top list. Signs come from an exact decimal comparison.
export function contributionLists(
  holdings: readonly HoldingContribution[],
  pool: readonly PoolAsset[],
): ContributionLists {
  const names = new Map(pool.map((a) => [a.assetId, a.name]));
  const all = holdings.map((h) => ({
    assetId: h.assetId,
    name: names.get(h.assetId) ?? UNKNOWN_ASSET_NAME,
    position: positionLabel[h.declaredPosition],
    quantity: h.quantity,
    contribution: h.contribution,
    priceReturn: h.priceReturn,
  }));
  const up = all
    .filter((row) => compareDecimal(row.contribution, '0') > 0)
    .slice(0, TOP_MOVERS);
  const down = all
    .filter((row) => compareDecimal(row.contribution, '0') < 0)
    .reverse()
    .slice(0, TOP_MOVERS);
  return { up, down, all, hasMore: all.length > up.length + down.length };
}
