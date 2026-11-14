import { useEffect, useRef } from 'react';
import { Link } from 'react-router';
import { isNavEnabled } from '../../app/navItems.ts';
import { QueryError } from '../seasons/QueryError.tsx';
import type { Season } from '../seasons/labels.ts';
import { useTurn } from './queries.ts';
import { TurnResultCard } from './TurnResultCard.tsx';
import styles from './Turn.module.css';

function Entry({
  enabled,
  to,
  label,
  primary,
}: {
  enabled: boolean;
  to: string;
  label: string;
  primary: boolean;
}) {
  const look = primary ? styles.primary : styles.secondary;
  if (enabled) {
    return (
      <Link to={to} className={look}>
        {label}
      </Link>
    );
  }
  return (
    <span
      aria-disabled="true"
      className={`${styles.secondary} ${styles.secondaryOff}`}
    >
      {`${label} (준비 중)`}
    </span>
  );
}

export function FinishedView({
  season,
  focusTurnNo,
}: {
  season: Season;
  focusTurnNo: number | null;
}) {
  const last = season.pathLength;
  const turn = useTurn(season.id, last);
  const heading = useRef<HTMLHeadingElement>(null);
  // Only after advancing here; a page load straight into the end moves nothing.
  const shouldFocus = turn.data !== undefined && focusTurnNo === last;
  useEffect(() => {
    if (shouldFocus) heading.current?.focus();
  }, [shouldFocus]);
  const base = `/seasons/${String(season.id)}`;
  return (
    <div className={styles.stack}>
      <div role="status" className={styles.finished}>
        <p className={styles.strong}>시즌이 끝났습니다</p>
        <p>
          성적표와 복기에서 이 시즌을 돌아볼 수 있습니다. 실제 연월과 사건은
          복기에서 공개됩니다.
        </p>
      </div>
      <h2 ref={heading} tabIndex={-1} className={styles.heading}>
        {`${String(last)}턴 결과 (마지막)`}
      </h2>
      {turn.isPending && (
        <p role="status" className={styles.note}>
          턴 결과를 불러오는 중입니다…
        </p>
      )}
      {turn.isError && (
        <QueryError
          error={turn.error}
          onRetry={() => {
            void turn.refetch();
          }}
        />
      )}
      {turn.data && <TurnResultCard turn={turn.data} />}
      <div className={styles.entries}>
        <Entry
          enabled={isNavEnabled('report')}
          to={`${base}/report`}
          label="성적표 보기"
          primary
        />
        <Entry
          enabled={isNavEnabled('review')}
          to={`${base}/review`}
          label="복기 보기"
          primary={false}
        />
      </div>
      {season.currentTurnNo >= 2 && (
        <Link className={styles.link} to={`${base}/turns/${String(last - 1)}`}>
          지난 턴 보기
        </Link>
      )}
    </div>
  );
}
