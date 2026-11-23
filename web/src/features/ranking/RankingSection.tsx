import { useId, useState } from 'react';
import { SectionHeading, type Collapse } from '../layout/SectionHeading.tsx';
import { QueryError } from '../seasons/QueryError.tsx';
import { usePool } from '../team/queries.ts';
import { RankingTabs } from './RankingTabs.tsx';
import { useRanking } from './queries.ts';
import { rankingRows } from './rankingRows.ts';
import styles from './Ranking.module.css';

// The ranking board of one played turn, read-only. The caller passes a turn
// that is at most the season's current turn; anything else is not requested.
export function RankingSection({
  seasonId,
  turnNo,
  currentTurnNo,
  collapse,
}: {
  seasonId: number;
  turnNo: number;
  currentTurnNo: number;
  collapse?: Collapse;
}) {
  const titleId = useId();
  const panelId = useId();
  const [open, setOpen] = useState(collapse?.defaultOpen ?? true);
  const inRange = turnNo >= 1 && turnNo <= currentTurnNo;
  const board = useRanking(seasonId, turnNo, inRange && open);
  const pool = usePool();
  if (!inRange) return null;
  const error = board.isError ? board.error : pool.isError ? pool.error : null;
  return (
    <section aria-labelledby={titleId} className={styles.section}>
      <SectionHeading
        id={titleId}
        className={styles.title}
        collapsible={collapse !== undefined}
        open={open}
        panelId={panelId}
        onToggle={() => {
          setOpen((value) => !value);
        }}
      >
        순위
      </SectionHeading>
      {open && (
        <div id={panelId} className={styles.panel}>
          {(board.isPending || pool.isPending) && error === null && (
            <p role="status" className={styles.note}>
              순위를 불러오는 중입니다…
            </p>
          )}
          {error !== null && (
            <QueryError
              error={error}
              onRetry={() => {
                if (board.isError) void board.refetch();
                if (pool.isError) void pool.refetch();
              }}
            />
          )}
          {board.data !== undefined && pool.data !== undefined && (
            <RankingTabs lists={rankingRows(board.data, pool.data)} />
          )}
        </div>
      )}
    </section>
  );
}
