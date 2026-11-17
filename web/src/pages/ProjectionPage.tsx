import { Link, useParams } from 'react-router';
import { ProblemError } from '../api/problem.ts';
import { MAX_ROUTE_ID, parseWholeNumber } from '../features/format/integer.ts';
import { ProjectionView } from '../features/projection/ProjectionView.tsx';
import { QueryError } from '../features/seasons/QueryError.tsx';
import { useSeason } from '../features/seasons/queries.ts';
import seasons from '../features/seasons/Seasons.module.css';
import { NotFoundPage } from './NotFoundPage.tsx';
import styles from './Page.module.css';

export function ProjectionPage() {
  const { seasonId = '' } = useParams();
  const id = parseWholeNumber(seasonId, MAX_ROUTE_ID);
  const query = useSeason(id);
  if (id === null) return <NotFoundPage />;
  const notFound =
    query.error instanceof ProblemError && query.error.code === 'NOT_FOUND';
  return (
    <div className={styles.page}>
      <h1 className={styles.heading}>예상 분포</h1>
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
      {query.data && <ProjectionView season={query.data} />}
      <Link to={`/seasons/${String(id)}`} className={styles.link}>
        시즌으로 돌아가기
      </Link>
    </div>
  );
}
