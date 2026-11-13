import { useEffect, useRef } from 'react';
import { QueryError } from '../seasons/QueryError.tsx';
import type { Season } from '../seasons/labels.ts';
import { Link } from 'react-router';
import { AdvanceTurnButton } from './AdvanceTurnButton.tsx';
import { useTurn } from './queries.ts';
import { TurnResultCard } from './TurnResultCard.tsx';
import styles from './Turn.module.css';

// The now page for turn 1 or later: the stored result of the current turn.
export function TurnView({
  season,
  focusTurnNo,
  onNotice,
  onAdvanced,
}: {
  season: Season;
  focusTurnNo: number | null;
  onNotice: (text: string | null) => void;
  onAdvanced: (turnNo: number) => void;
}) {
  const turn = useTurn(season.id, season.currentTurnNo);
  const heading = useRef<HTMLHeadingElement>(null);
  const shouldFocus =
    turn.data !== undefined && focusTurnNo === season.currentTurnNo;

  useEffect(() => {
    if (shouldFocus) heading.current?.focus();
  }, [shouldFocus]);

  return (
    <div className={styles.stack}>
      <h2 ref={heading} tabIndex={-1} className={styles.heading}>
        {`${String(season.currentTurnNo)}턴 결과`}
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
      {season.currentTurnNo >= 2 && (
        <div className={styles.links}>
          <Link
            className={styles.link}
            to={`/seasons/${String(season.id)}/turns/${String(season.currentTurnNo - 1)}`}
          >
            지난 턴 보기
          </Link>
        </div>
      )}
      <AdvanceTurnButton
        season={season}
        onNotice={onNotice}
        onAdvanced={onAdvanced}
      />
    </div>
  );
}
