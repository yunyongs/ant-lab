import { Link, useParams } from 'react-router';
import { MAX_ROUTE_ID, parseWholeNumber } from '../features/format/integer.ts';
import { QueryError } from '../features/seasons/QueryError.tsx';
import { useSeason } from '../features/seasons/queries.ts';
import { useTurn } from '../features/turn/queries.ts';
import { TurnResultCard } from '../features/turn/TurnResultCard.tsx';
import { NOT_REACHED_TEXT } from '../features/turn/turnMessages.ts';
import turnStyles from '../features/turn/Turn.module.css';
import seasons from '../features/seasons/Seasons.module.css';
import { ProblemError } from '../api/problem.ts';
import { NotFoundPage } from './NotFoundPage.tsx';
import styles from './Page.module.css';

// One earlier turn, read-only. The turn is checked against the season's
// current turn before any request: the server would answer an out-of-range
// number with an error anyway.
export function TurnPage() {
  const { seasonId = '', turnNo: turnText = '' } = useParams();
  const id = parseWholeNumber(seasonId, MAX_ROUTE_ID);
  const k = parseWholeNumber(turnText, MAX_ROUTE_ID);
  const season = useSeason(id);
  const current = season.data?.currentTurnNo;
  const inRange = k !== null && current !== undefined && k >= 1 && k <= current;
  const turn = useTurn(id ?? 0, k ?? 0, inRange);
  if (id === null || k === null) return <NotFoundPage />;
  const base = `/seasons/${String(id)}`;
  const notFound =
    season.error instanceof ProblemError && season.error.code === 'NOT_FOUND';

  return (
    <div className={styles.page}>
      <h1 className={styles.heading}>{`시즌 ${String(id)}`}</h1>
      {season.isPending && (
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
      {season.isError && !notFound && (
        <QueryError
          error={season.error}
          onRetry={() => {
            void season.refetch();
          }}
        />
      )}
      {season.data && !inRange && (
        <div className={turnStyles.stack}>
          <p role="status" className={turnStyles.note}>
            {NOT_REACHED_TEXT}
          </p>
          <Link className={turnStyles.link} to={base}>
            지금 턴으로
          </Link>
        </div>
      )}
      {season.data && inRange && (
        <div className={turnStyles.stack}>
          <h2 className={turnStyles.heading}>{`${String(k)}턴 결과`}</h2>
          {turn.isPending && (
            <p role="status" className={turnStyles.note}>
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
          <nav aria-label="턴 이동" className={turnStyles.links}>
            {k >= 2 && (
              <Link
                className={turnStyles.link}
                to={`${base}/turns/${String(k - 1)}`}
              >
                {`← ${String(k - 1)}턴`}
              </Link>
            )}
            <Link className={turnStyles.link} to={base}>
              지금 턴으로
            </Link>
            {k < (current ?? 0) && (
              <Link
                className={turnStyles.link}
                to={`${base}/turns/${String(k + 1)}`}
              >
                {`${String(k + 1)}턴 →`}
              </Link>
            )}
          </nav>
        </div>
      )}
    </div>
  );
}
