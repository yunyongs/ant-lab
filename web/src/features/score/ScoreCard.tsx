import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ProblemError } from '../../api/problem.ts';
import { ratioToPercent } from '../format/ratio.ts';
import { isNotFinishedProblem } from '../seasons/notFinished.ts';
import { QueryError } from '../seasons/QueryError.tsx';
import { seasonKey } from '../seasons/queries.ts';
import { useScore, type SeasonScore } from './queries.ts';
import { EXCLUSION_TEXT, ITEM_LABEL, ITEM_ORDER } from './scoreLabels.ts';
import {
  COUNTED_TEXT,
  ENGINE_BUSY_TEXT,
  itemsTitle,
  LUCK_NOTE,
  NO_DISTRIBUTION_TEXT,
  NO_SCORE_TEXT,
  NO_VALUE_TEXT,
  NOT_COUNTED_TEXT,
  NOT_READY_TEXT,
  pointsText,
  PROCESS_NOTE,
  SCORE_LOADING_TEXT,
  SCORE_RETRY_LABEL,
  SCORE_TITLE,
  versionText,
} from './scoreMessages.ts';
import styles from './Score.module.css';

const nullable = (value: string | null, empty: string) =>
  value === null ? empty : pointsText(value);

const percentile = (value: string | null) =>
  value === null
    ? NO_DISTRIBUTION_TEXT
    : `경로 백분위 ${ratioToPercent(value)}`;

function ratingLine(score: SeasonScore): string {
  if (score.countedInRating) return COUNTED_TEXT;
  return score.ratingExclusion === null
    ? NOT_COUNTED_TEXT
    : EXCLUSION_TEXT[score.ratingExclusion];
}

function Row({
  label,
  value,
  big = false,
}: {
  label: string;
  value: string;
  big?: boolean;
}) {
  return (
    <div className={styles.row}>
      <dt>{label}</dt>
      <dd className={big ? styles.big : undefined}>{value}</dd>
    </div>
  );
}

function ScoreBody({ score }: { score: SeasonScore }) {
  return (
    <>
      <dl className={styles.rows}>
        <Row label="점수" value={nullable(score.score, NO_SCORE_TEXT)} big />
        <Row
          label="결과 점수"
          value={nullable(score.resultScore, NO_DISTRIBUTION_TEXT)}
        />
        <Row label="과정 점수" value={pointsText(score.processScore)} />
      </dl>
      <h3 className={styles.subheading}>{itemsTitle(score.processItems)}</h3>
      <dl className={styles.rows}>
        {ITEM_ORDER.map((key) => (
          <Row
            key={key}
            label={ITEM_LABEL[key]}
            value={nullable(score.items[key], NO_VALUE_TEXT)}
          />
        ))}
      </dl>
      <p className={styles.note}>{PROCESS_NOTE}</p>
      <dl className={styles.rows}>
        <Row label="운 백분위" value={percentile(score.luckPercentile)} />
        <Row
          label="샤프 차이 백분위"
          value={percentile(score.sharpeDiffPercentile)}
        />
      </dl>
      <p className={styles.note}>{`운 백분위는 ${LUCK_NOTE}`}</p>
      <p className={styles.note}>{ratingLine(score)}</p>
      <p className={styles.small}>{versionText(score.scoreVersion)}</p>
    </>
  );
}

// Printed as the server sends it; nothing here is computed. The request is
// sent once per view and repeated only by the retry button.
export function ScoreCard({ seasonId }: { seasonId: number }) {
  const queryClient = useQueryClient();
  const query = useScore(seasonId, true);
  const stale = isNotFinishedProblem(query.error);
  // A stale season cache said FINISHED: follow the server's answer.
  useEffect(() => {
    if (stale) {
      void queryClient.invalidateQueries({ queryKey: seasonKey(seasonId) });
    }
  }, [stale, seasonId, queryClient]);

  const busy =
    query.error instanceof ProblemError &&
    query.error.code === 'ENGINE_UNAVAILABLE';
  const retry = () => {
    void query.refetch();
  };

  return (
    <section aria-labelledby="score-heading" className={styles.card}>
      <h2 id="score-heading" className={styles.heading}>
        {SCORE_TITLE}
      </h2>
      {query.isFetching && (
        <p role="status" className={styles.note}>
          {SCORE_LOADING_TEXT}
        </p>
      )}
      {!query.isFetching && busy && (
        <div className={styles.status}>
          <p role="status">{ENGINE_BUSY_TEXT}</p>
          <button type="button" className={styles.retry} onClick={retry}>
            {SCORE_RETRY_LABEL}
          </button>
        </div>
      )}
      {!query.isFetching && query.isError && !busy && !stale && (
        <QueryError error={query.error} onRetry={retry} />
      )}
      {!query.isFetching && stale && (
        <p role="status" className={styles.note}>
          {NOT_READY_TEXT}
        </p>
      )}
      {query.data && <ScoreBody score={query.data} />}
    </section>
  );
}
