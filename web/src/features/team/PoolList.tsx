import { useIsMutating } from '@tanstack/react-query';
import { useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { advanceKey } from '../turn/queries.ts';
import { OrderSheet } from './OrderSheet.tsx';
import { PoolCard } from './PoolCard.tsx';
import controlStyles from './PoolControls.module.css';
import { PoolControls } from './PoolControls.tsx';
import {
  PARAM,
  applyPoolView,
  groupOptions,
  isFiltered,
  parseFilters,
  withKnownGroup,
} from './poolView.ts';
import type { Holding, PoolAsset } from './queries.ts';
import styles from './Team.module.css';

export function PoolList({
  assets,
  holdings,
  prices,
  pricesFailed,
  seasonId,
  turnNo,
  cashBalance,
  onStale,
}: {
  assets: PoolAsset[];
  holdings: Holding[];
  prices: Map<number, string> | undefined;
  pricesFailed: boolean;
  seasonId: number;
  turnNo: number;
  cashBalance: string;
  onStale: (code: 'TURN_MISMATCH' | 'SEASON_NOT_ACTIVE') => void;
}) {
  const advancing = useIsMutating({ mutationKey: advanceKey(seasonId) }) > 0;
  const [selected, setSelected] = useState<PoolAsset | null>(null);
  const opener = useRef<HTMLButtonElement | null>(null);
  const [params, setParams] = useSearchParams();
  const groups = useMemo(() => groupOptions(assets), [assets]);
  const filters = withKnownGroup(parseFilters(params), groups);
  const searchRef = useRef<HTMLInputElement | null>(null);
  const shown = applyPoolView(assets, filters);
  const viewParams = Object.values(PARAM);

  // Only the six view parameters are touched; replace keeps history short.
  const change = (param: string, value: string) => {
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (value === '') next.delete(param);
        else next.set(param, value);
        return next;
      },
      { replace: true },
    );
  };
  const reset = () => {
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        for (const param of viewParams) next.delete(param);
        return next;
      },
      { replace: true },
    );
    searchRef.current?.focus();
  };
  return (
    <section aria-label="종목 풀" className={styles.section}>
      <h3 className={styles.sectionTitle}>종목 풀</h3>
      <p className={styles.hint}>
        참여율은 시장 대비 배수, 변동성·최대 낙폭은 월 기준 비율입니다.
      </p>
      <PoolControls
        filters={filters}
        groups={groups}
        onChange={change}
        onReset={reset}
        showReset={shown.length > 0}
        searchRef={searchRef}
      />
      <p className={controlStyles.count} role="status">
        {isFiltered(filters)
          ? `전체 ${String(assets.length)}개 중 ${String(shown.length)}개`
          : `${String(assets.length)}개`}
      </p>
      {shown.length === 0 && (
        <div className={controlStyles.empty}>
          <p>조건에 맞는 종목이 없습니다.</p>
          <button type="button" className={styles.secondary} onClick={reset}>
            조건 지우기
          </button>
        </div>
      )}
      <ul className={styles.list}>
        {shown.map((asset) => (
          <PoolCard
            key={asset.assetId}
            asset={asset}
            holding={holdings.find((h) => h.assetId === asset.assetId)}
            disabled={advancing}
            onSelect={(picked, button) => {
              opener.current = button;
              setSelected(picked);
            }}
          />
        ))}
      </ul>
      {selected && (
        <OrderSheet
          key={selected.assetId}
          side="BUY"
          asset={selected}
          holding={holdings.find((h) => h.assetId === selected.assetId)}
          price={prices?.get(selected.assetId)}
          pricesFailed={pricesFailed}
          seasonId={seasonId}
          turnNo={turnNo}
          cashBalance={cashBalance}
          onClose={() => {
            setSelected(null);
            opener.current?.focus();
          }}
          onStale={(code) => {
            setSelected(null);
            onStale(code);
          }}
        />
      )}
    </section>
  );
}
