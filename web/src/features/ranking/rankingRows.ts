import { signedChange, type SignedText } from '../format/signedChange.ts';
import type { PoolAsset } from '../team/queries.ts';
import { UNKNOWN_ASSET_NAME } from '../trade/HoldingCard.tsx';
import { gradeLabel, UNKNOWN_GROUP_NAME } from './rankingLabels.ts';
import type { RankingBoard } from './queries.ts';

export interface RankingRow {
  rank: number;
  name: string;
  // False when the pool has no such asset; the name is then a placeholder.
  known: boolean;
  // Server value as signed percent text, when the list carries one.
  change: SignedText | null;
  streak: number | null;
  grade: string | null;
}

export interface RankingLists {
  gainers: RankingRow[];
  losers: RankingRow[];
  attention: RankingRow[];
  risingStreaks: RankingRow[];
  fallingStreaks: RankingRow[];
  groupGainers: RankingRow[];
  groupLosers: RankingRow[];
}

// Names come from the pool; every number is the server's. Nothing is sorted,
// cut or recomputed.
export function rankingRows(
  board: RankingBoard,
  pool: readonly PoolAsset[],
): RankingLists {
  const assets = new Map(pool.map((a) => [a.assetId, a.name]));
  const groups = new Map(pool.map((a) => [a.groupId, a.groupName]));
  const asset = (assetId: number) => {
    const name = assets.get(assetId);
    return { name: name ?? UNKNOWN_ASSET_NAME, known: name !== undefined };
  };
  const group = (groupId: number) => {
    const name = groups.get(groupId);
    return { name: name ?? UNKNOWN_GROUP_NAME, known: name !== undefined };
  };
  return {
    gainers: board.gainers.map((r) => ({
      rank: r.rank,
      ...asset(r.assetId),
      change: signedChange(r.changeRate, 'percent'),
      streak: null,
      grade: null,
    })),
    losers: board.losers.map((r) => ({
      rank: r.rank,
      ...asset(r.assetId),
      change: signedChange(r.changeRate, 'percent'),
      streak: null,
      grade: null,
    })),
    attention: board.attention.map((r) => ({
      rank: r.rank,
      ...asset(r.assetId),
      change: null,
      streak: null,
      grade: gradeLabel(r.grade),
    })),
    risingStreaks: board.risingStreaks.map((r) => ({
      rank: r.rank,
      ...asset(r.assetId),
      change: signedChange(r.changeRate, 'percent'),
      streak: r.streak,
      grade: null,
    })),
    fallingStreaks: board.fallingStreaks.map((r) => ({
      rank: r.rank,
      ...asset(r.assetId),
      change: signedChange(r.changeRate, 'percent'),
      streak: r.streak,
      grade: null,
    })),
    groupGainers: board.groupGainers.map((r) => ({
      rank: r.rank,
      ...group(r.groupId),
      change: signedChange(r.changeRate, 'percent'),
      streak: null,
      grade: null,
    })),
    groupLosers: board.groupLosers.map((r) => ({
      rank: r.rank,
      ...group(r.groupId),
      change: signedChange(r.changeRate, 'percent'),
      streak: null,
      grade: null,
    })),
  };
}
