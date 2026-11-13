import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router';
import { MAX_ROUTE_ID, parseWholeNumber } from '../features/format/integer.ts';
import { useReview, useTurnValues } from '../features/review/queries.ts';
import { REVIEW_LOADING_TEXT } from '../features/review/reviewMessages.ts';
import { ReviewView } from '../features/review/ReviewView.tsx';
import { isNotFinishedProblem } from '../features/seasons/notFinished.ts';
import { QueryError } from '../features/seasons/QueryError.tsx';
import { SeasonNotFinished } from '../features/seasons/SeasonNotFinished.tsx';
import { seasonKey } from '../features/seasons/queries.ts';
import seasons from '../features/seasons/Seasons.module.css';
import { useFinishedSeason } from '../features/seasons/useFinishedSeason.ts';
import { NotFoundPage } from './NotFoundPage.tsx';
import styles from './Page.module.css';

export function ReviewPage() {
  const { seasonId = '' } = useParams();
  const id = parseWholeNumber(seasonId, MAX_ROUTE_ID);
  const queryClient = useQueryClient();
  const gate = useFinishedSeason(id);
  const finished = id !== null && gate.state === 'finished';
  const review = useReview(id ?? 0, finished);
  // Runs next to the review request, never for an unfinished season.
  const values = useTurnValues(
    id ?? 0,
    gate.state === 'finished' ? gate.season.currentTurnNo : 0,
    finished,
  );
  const stale = isNotFinishedProblem(review.error);
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
        <h1 className={styles.heading}>복기</h1>
        <p role="status" className={seasons.note}>
          {REVIEW_LOADING_TEXT}
        </p>
      </div>
    );
  }
  if (gate.state === 'error') {
    return (
      <div className={styles.page}>
        <h1 className={styles.heading}>복기</h1>
        <QueryError error={gate.error} onRetry={gate.retry} />
      </div>
    );
  }
  if (gate.state === 'pending' || stale) {
    return (
      <div className={styles.page}>
        <h1 className={styles.heading}>복기</h1>
        <SeasonNotFinished season={gate.season} screen="review" />
      </div>
    );
  }
  return (
    <div className={styles.page}>
      {review.isPending && (
        <>
          <h1 className={styles.heading}>복기</h1>
          <p role="status" className={seasons.note}>
            {REVIEW_LOADING_TEXT}
          </p>
        </>
      )}
      {review.isError && (
        <>
          <h1 className={styles.heading}>복기</h1>
          <QueryError
            error={review.error}
            onRetry={() => {
              void review.refetch();
            }}
          />
        </>
      )}
      {review.data && (
        <ReviewView review={review.data} season={gate.season} values={values} />
      )}
    </div>
  );
}
