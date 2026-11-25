import { useEffect, useRef } from 'react';
import { MacroPanel } from '../macro/MacroPanel.tsx';
import { AlertList } from '../feed/AlertList.tsx';
import { FeedSection } from '../feed/FeedSection.tsx';
import { RankingSection } from '../ranking/RankingSection.tsx';
import { ProjectionLink } from '../projection/ProjectionLink.tsx';
import { QueryError } from '../seasons/QueryError.tsx';
import type { Season } from '../seasons/labels.ts';
import { canTrade } from '../seasons/seasonGate.ts';
import { Link } from 'react-router';
import { TurnActionBar } from './TurnActionBar.tsx';
import { useTurn } from './queries.ts';
import { BreachNotice } from './TurnDetailRows.tsx';
import { TurnTabs } from './TurnTabs.tsx';
import { TurnSummary } from './TurnSummary.tsx';
import styles from './Turn.module.css';

// The now page for turn 1 or later: the stored result of the current turn.
// A season that is not in progress shows it read-only: no advance button.
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
      {turn.data && <TurnSummary turn={turn.data} />}
      {turn.data && (
        <AlertList
          items={turn.data.alerts}
          feed={turn.data.feed}
          season={season}
          onNotice={onNotice}
        />
      )}
      {turn.data && <BreachNotice turn={turn.data} />}
      {turn.data && <TurnTabs season={season} turn={turn.data} />}
      {turn.data && <MacroPanel macro={turn.data.macro} />}
      {turn.data && (
        <FeedSection
          items={turn.data.feed}
          season={season}
          onNotice={onNotice}
          collapse={{ defaultOpen: true }}
        />
      )}
      {turn.data && (
        <RankingSection
          seasonId={season.id}
          turnNo={season.currentTurnNo}
          currentTurnNo={season.currentTurnNo}
          collapse={{ defaultOpen: false }}
        />
      )}
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
      <ProjectionLink seasonId={season.id} />
      {canTrade(season) && (
        <TurnActionBar
          season={season}
          onNotice={onNotice}
          onAdvanced={onAdvanced}
        />
      )}
    </div>
  );
}
