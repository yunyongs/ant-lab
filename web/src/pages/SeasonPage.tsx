import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { ProblemError } from '../api/problem.ts';
import { MAX_ROUTE_ID, parseWholeNumber } from '../features/format/integer.ts';
import { TeamBuild } from '../features/team/TeamBuild.tsx';
import { FinishedView } from '../features/turn/FinishedView.tsx';
import { TurnView } from '../features/turn/TurnView.tsx';
import { QueryError } from '../features/seasons/QueryError.tsx';
import { SeasonSummary } from '../features/seasons/SeasonSummary.tsx';
import { useSeason } from '../features/seasons/queries.ts';
import type { Season } from '../features/seasons/labels.ts';
import seasons from '../features/seasons/Seasons.module.css';
import { NotFoundPage } from './NotFoundPage.tsx';
import styles from './Page.module.css';

function isTeamBuild(season: Season): boolean {
  return season.status === 'IN_PROGRESS' && season.currentTurnNo === 0;
}

export function SeasonPage() {
  const { seasonId = '' } = useParams();
  const id = parseWholeNumber(seasonId, MAX_ROUTE_ID);
  const query = useSeason(id);
  const [notice, setNotice] = useState<string | null>(null);
  // The turn whose result heading takes focus once it is on screen.
  const [focusTurnNo, setFocusTurnNo] = useState<number | null>(null);
  if (id === null) return <NotFoundPage />;
  const notFound =
    query.error instanceof ProblemError && query.error.code === 'NOT_FOUND';
  return (
    <div className={styles.page}>
      <h1 className={styles.heading}>{`시즌 ${String(id)}`}</h1>
      {notice !== null && (
        <div role="alert" className={seasons.alert}>
          <p>{notice}</p>
        </div>
      )}
      {query.isPending && (
        <p role="status" className={seasons.note}>
          시즌을 불러오는 중입니다…
        </p>
      )}
      {notFound && (
        <div role="alert" className={seasons.alert}>
          <p>시즌을 찾을 수 없습니다.</p>
          <Link to="/" className={seasons.secondary}>
            내 시즌으로
          </Link>
        </div>
      )}
      {query.isError && !notFound && (
        <QueryError
          error={query.error}
          onRetry={() => {
            void query.refetch();
          }}
        />
      )}
      {query.data && (
        <>
          <SeasonSummary season={query.data} />
          {query.data.status === 'ABANDONING' && (
            <p className={seasons.note}>시즌을 정리하는 중입니다.</p>
          )}
          {query.data.status === 'FINISHED' && (
            <FinishedView season={query.data} focusTurnNo={focusTurnNo} />
          )}
          {isTeamBuild(query.data) && (
            <TeamBuild
              season={query.data}
              onNotice={setNotice}
              onAdvanced={setFocusTurnNo}
            />
          )}
          {query.data.status === 'IN_PROGRESS' &&
            query.data.currentTurnNo >= 1 && (
              <TurnView
                season={query.data}
                focusTurnNo={focusTurnNo}
                onNotice={setNotice}
                onAdvanced={setFocusTurnNo}
              />
            )}
        </>
      )}
    </div>
  );
}
