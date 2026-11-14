import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router';
import { MAX_ROUTE_ID, parseWholeNumber } from '../features/format/integer.ts';
import { useReport } from '../features/report/queries.ts';
import { REPORT_LOADING_TEXT } from '../features/report/reportMessages.ts';
import { ReportView } from '../features/report/ReportView.tsx';
import { isNotFinishedProblem } from '../features/seasons/notFinished.ts';
import { QueryError } from '../features/seasons/QueryError.tsx';
import { SeasonNotFinished } from '../features/seasons/SeasonNotFinished.tsx';
import { seasonKey } from '../features/seasons/queries.ts';
import seasons from '../features/seasons/Seasons.module.css';
import { useFinishedSeason } from '../features/seasons/useFinishedSeason.ts';
import { NotFoundPage } from './NotFoundPage.tsx';
import styles from './Page.module.css';

export function ReportPage() {
  const { seasonId = '' } = useParams();
  const id = parseWholeNumber(seasonId, MAX_ROUTE_ID);
  const queryClient = useQueryClient();
  const gate = useFinishedSeason(id);
  const report = useReport(id ?? 0, id !== null && gate.state === 'finished');
  const stale = isNotFinishedProblem(report.error);
  // A stale season cache said FINISHED: follow the server's answer.
  useEffect(() => {
    if (stale && id !== null) {
      void queryClient.invalidateQueries({ queryKey: seasonKey(id) });
    }
  }, [stale, id, queryClient]);
  if (id === null) return <NotFoundPage />;

  if (gate.state === 'loading') {
    return (
      <div className={styles.page}>
        <h1 className={styles.heading}>성적표</h1>
        <p role="status" className={seasons.note}>
          {REPORT_LOADING_TEXT}
        </p>
      </div>
    );
  }
  if (gate.state === 'error') {
    return (
      <div className={styles.page}>
        <h1 className={styles.heading}>성적표</h1>
        <QueryError error={gate.error} onRetry={gate.retry} />
      </div>
    );
  }
  if (gate.state === 'pending' || stale) {
    return (
      <div className={styles.page}>
        <h1 className={styles.heading}>성적표</h1>
        <SeasonNotFinished season={gate.season} screen="report" />
      </div>
    );
  }
  return (
    <div className={styles.page}>
      {report.isPending && (
        <>
          <h1 className={styles.heading}>성적표</h1>
          <p role="status" className={seasons.note}>
            {REPORT_LOADING_TEXT}
          </p>
        </>
      )}
      {report.isError && (
        <>
          <h1 className={styles.heading}>성적표</h1>
          <QueryError
            error={report.error}
            onRetry={() => {
              void report.refetch();
            }}
          />
        </>
      )}
      {report.data && <ReportView report={report.data} season={gate.season} />}
    </div>
  );
}
