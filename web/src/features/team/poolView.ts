import { compareDecimal, isDecimal } from '../format/compareDecimal.ts';
import { POSITIONS, type Position } from '../seasons/labels.ts';
import type { PoolAsset } from './queries.ts';

export type Market = PoolAsset['market'];
export type AssetType = PoolAsset['assetType'];

export const MARKETS: Market[] = ['KR', 'US'];
export const ASSET_TYPES: AssetType[] = ['STOCK', 'ETF'];

export const marketLabel: Record<Market, string> = {
  KR: '국내',
  US: '미국',
};

export const assetTypeLabel: Record<AssetType, string> = {
  STOCK: '주식',
  ETF: 'ETF',
};

type SortField =
  'volatility' | 'maxDrawdown' | 'upParticipation' | 'downParticipation';

interface SortSpec {
  field: SortField;
  direction: 'asc' | 'desc';
  label: string;
}

// The maximum drawdown is 0 or less, so "smaller drawdown" is the larger value.
export const SORTS = {
  'vol-asc': { field: 'volatility', direction: 'asc', label: '변동성 낮은 순' },
  'vol-desc': {
    field: 'volatility',
    direction: 'desc',
    label: '변동성 높은 순',
  },
  'mdd-small': {
    field: 'maxDrawdown',
    direction: 'desc',
    label: '최대 낙폭 작은 순',
  },
  'mdd-large': {
    field: 'maxDrawdown',
    direction: 'asc',
    label: '최대 낙폭 큰 순',
  },
  'up-desc': {
    field: 'upParticipation',
    direction: 'desc',
    label: '상승 참여 높은 순',
  },
  'up-asc': {
    field: 'upParticipation',
    direction: 'asc',
    label: '상승 참여 낮은 순',
  },
  'down-asc': {
    field: 'downParticipation',
    direction: 'asc',
    label: '하락 참여 낮은 순',
  },
  'down-desc': {
    field: 'downParticipation',
    direction: 'desc',
    label: '하락 참여 높은 순',
  },
} as const satisfies Record<string, SortSpec>;

export type SortKey = keyof typeof SORTS;
export const SORT_KEYS = Object.keys(SORTS) as SortKey[];

// The chips filter by one position, or by the three forward kinds together.
export const FORWARD_GROUP = 'FORWARD';
export type PositionFilter = Position | typeof FORWARD_GROUP;
const POSITION_FILTERS: readonly PositionFilter[] = [
  ...POSITIONS,
  FORWARD_GROUP,
];

export interface PositionChip {
  label: string;
  value: PositionFilter | null;
}

export const POSITION_CHIPS: readonly PositionChip[] = [
  { label: '전체', value: null },
  { label: '수비수', value: 'DEFENDER' },
  { label: '미드필더', value: 'MIDFIELDER' },
  { label: '공격수', value: FORWARD_GROUP },
];

// A chip is pressed only for its exact filter value. A single position the
// chips do not cover (an older address such as pos=FORWARD_TARGET) keeps
// filtering exactly that kind and is shown through subFilterPosition.
export function chipPressed(
  chip: PositionChip,
  position: PositionFilter | null,
): boolean {
  return chip.value === position;
}

export function subFilterPosition(
  position: PositionFilter | null,
): Position | null {
  if (position === null || position === FORWARD_GROUP) return null;
  const covered = POSITION_CHIPS.some((chip) => chip.value === position);
  return covered ? null : position;
}

export interface PoolFilters {
  query: string;
  market: Market | null;
  assetType: AssetType | null;
  position: PositionFilter | null;
  groupId: string | null;
  sort: SortKey | null;
}

export const PARAM = {
  query: 'q',
  market: 'market',
  assetType: 'type',
  position: 'pos',
  groupId: 'group',
  sort: 'sort',
} as const;

function pick<T extends string>(
  raw: string | null,
  allowed: readonly T[],
): T | null {
  return allowed.find((item) => item === raw) ?? null;
}

// Unknown values in the address are ignored rather than trusted.
export function parseFilters(params: URLSearchParams): PoolFilters {
  const group = params.get(PARAM.groupId);
  return {
    query: params.get(PARAM.query) ?? '',
    market: pick(params.get(PARAM.market), MARKETS),
    assetType: pick(params.get(PARAM.assetType), ASSET_TYPES),
    position: pick(params.get(PARAM.position), POSITION_FILTERS),
    groupId: group !== null && /^\d+$/.test(group) ? group : null,
    sort: pick(params.get(PARAM.sort), SORT_KEYS),
  };
}

export function isFiltered(filters: PoolFilters): boolean {
  return (
    filters.query.trim() !== '' ||
    filters.market !== null ||
    filters.assetType !== null ||
    filters.position !== null ||
    filters.groupId !== null
  );
}

// Filters that live in the collapsed "더보기" panel (not the search or chips).
export function hasMoreFilters(filters: PoolFilters): boolean {
  return (
    filters.market !== null ||
    filters.assetType !== null ||
    filters.groupId !== null ||
    filters.sort !== null
  );
}

export function isCustomized(filters: PoolFilters): boolean {
  return isFiltered(filters) || filters.sort !== null;
}

export interface GroupOption {
  id: string;
  name: string;
}

export function groupOptions(assets: PoolAsset[]): GroupOption[] {
  const seen = new Map<string, string>();
  for (const asset of assets) seen.set(String(asset.groupId), asset.groupName);
  return [...seen]
    .map(([id, name]) => ({ id, name }))
    .sort((a, b) => a.name.localeCompare(b.name, 'ko'));
}

// A group id the pool does not have is ignored, so what the select shows
// and what is applied never disagree.
export function withKnownGroup(
  filters: PoolFilters,
  groups: GroupOption[],
): PoolFilters {
  if (filters.groupId === null) return filters;
  return groups.some((group) => group.id === filters.groupId)
    ? filters
    : { ...filters, groupId: null };
}

// NFC so composed and decomposed Hangul match; spaces are ignored.
function fold(text: string): string {
  return text.normalize('NFC').toLowerCase().replace(/\s+/g, '');
}

function matchesQuery(asset: PoolAsset, query: string): boolean {
  const needle = fold(query);
  if (needle === '') return true;
  return (
    fold(asset.name).includes(needle) || fold(asset.ticker).includes(needle)
  );
}

function matchesPosition(
  asset: PoolAsset,
  position: PositionFilter | null,
): boolean {
  if (position === null) return true;
  if (position === FORWARD_GROUP) {
    return asset.systemPosition.startsWith('FORWARD');
  }
  return asset.systemPosition === position;
}

export function filterPool(
  assets: PoolAsset[],
  filters: PoolFilters,
): PoolAsset[] {
  return assets.filter(
    (asset) =>
      matchesQuery(asset, filters.query) &&
      (filters.market === null || asset.market === filters.market) &&
      (filters.assetType === null || asset.assetType === filters.assetType) &&
      matchesPosition(asset, filters.position) &&
      (filters.groupId === null || String(asset.groupId) === filters.groupId),
  );
}

// Array.prototype.sort is stable: equal values keep the server order.
export function sortPool(
  assets: PoolAsset[],
  sort: SortKey | null,
): PoolAsset[] {
  if (sort === null) return assets;
  const { field, direction } = SORTS[sort];
  const sign = direction === 'asc' ? 1 : -1;
  // Malformed values stay last in both directions.
  return [...assets].sort((a, b) => {
    const left = a[field];
    const right = b[field];
    if (!isDecimal(left) || !isDecimal(right)) {
      return compareDecimal(left, right);
    }
    return sign * compareDecimal(left, right);
  });
}

export function applyPoolView(
  assets: PoolAsset[],
  filters: PoolFilters,
): PoolAsset[] {
  return sortPool(filterPool(assets, filters), filters.sort);
}
