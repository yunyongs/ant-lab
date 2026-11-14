import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { ProblemError } from '../api/problem.ts';
import { MAX_ROUTE_ID, parseWholeNumber } from '../features/format/integer.ts';
import { QueryError } from '../features/seasons/QueryError.tsx';
import { useSeason } from '../features/seasons/queries.ts';
import { canChangeFormation } from '../features/seasons/seasonGate.ts';
import { FormationChangeForm } from '../features/trade/FormationChangeForm.tsx';
import trade from '../features/trade/Trade.module.css';
import { TURN_ZERO_TEXT } from '../features/trade/tradeMessages.ts';
import { NotFoundPage } from './NotFoundPage.tsx';
import styles from './Page.module.css';

export function FormationChangePage() {
  const { seasonId = '' } = useParams();
  const id = parseWholeNumber(seasonId, MAX_ROUTE_ID);
  const query = useSeason(id);
  const [notice, setNotice] = useState<string | null>(null);
  if (id === null) return <NotFoundPage />;
  const season = query.data;
  const notFound =
    query.error instanceof ProblemError && query.error.code === 'NOT_FOUND';
  const guard =
    season === undefined || canChangeFormation(season)
      ? null
      : season.status === 'FINISHED'
        ? '끝난 시즌은 전술을 바꿀 수 없습니다.'
        : season.status === 'ABANDONING'
          ? '정리 중인 시즌은 전술을 바꿀 수 없습니다.'
          : TURN_ZERO_TEXT;
  return (
    <div className={styles.page}>
      <h1 className={styles.heading}>
        {season === undefined
          ? `시즌 ${String(id)} 전술 변경`
          : `${String(season.currentTurnNo)}턴 전술 변경`}
      </h1>
      {notice !== null && (
        <div role="alert" className={trade.alert}>
          <p>{notice}</p>
        </div>
      )}
      {query.isPending && (
        <p role="status" className={trade.muted}>
          시즌을 불러오는 중입니다…
        </p>
      )}
      {notFound && (
        <div role="alert" className={trade.alert}>
          <p>시즌을 찾을 수 없습니다.</p>
          <Link to="/" className={trade.link}>
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
      {guard !== null && (
        <>
          <p role="status">{guard}</p>
          <Link to={`/seasons/${String(id)}`} className={trade.link}>
            시즌으로 돌아가기
          </Link>
        </>
      )}
      {season !== undefined && guard === null && (
        <FormationChangeForm season={season} onNotice={setNotice} />
      )}
    </div>
  );
}
