import { Link } from 'react-router';
import { useRating, type Rating } from './queries.ts';
import {
  countedText,
  HISTORY_TITLE,
  historyRowText,
  missingText,
  noScoreText,
  notStartedText,
  pendingText,
  RATING_ERROR_TEXT,
  RATING_LOADING_TEXT,
  RATING_NONE_TEXT,
  RATING_RETRY_LABEL,
  RATING_TITLE,
  ratingValueText,
} from './ratingMessages.ts';
import styles from './Rating.module.css';

function Headline({ rating }: { rating: Rating }) {
  if (rating.rating !== null) {
    return (
      <>
        <p className={styles.value}>{ratingValueText(rating.rating)}</p>
        <p className={styles.muted}>{countedText(rating.countedSeasons)}</p>
      </>
    );
  }
  if (rating.seasonsUntilPublic > 0) {
    return (
      <p className={styles.text}>
        {missingText(rating.seasonsUntilPublic, rating.countedSeasons)}
      </p>
    );
  }
  return <p className={styles.text}>{RATING_NONE_TEXT}</p>;
}

function Facts({ rating }: { rating: Rating }) {
  const lines: string[] = [];
  if (rating.notStartedCount > 0)
    lines.push(notStartedText(rating.notStartedCount));
  if (rating.noScoreCount > 0) lines.push(noScoreText(rating.noScoreCount));
  if (rating.pendingSeasons > 0) lines.push(pendingText(rating.pendingSeasons));
  if (lines.length === 0) return null;
  return (
    <ul className={styles.facts}>
      {lines.map((line) => (
        <li key={line}>{line}</li>
      ))}
    </ul>
  );
}

function History({ rating }: { rating: Rating }) {
  if (rating.history.length === 0) return null;
  return (
    <details>
      <summary className={styles.summary}>{HISTORY_TITLE}</summary>
      <ul className={styles.history}>
        {rating.history.map((entry) => (
          <li key={entry.seqNo}>
            <Link
              className={styles.historyLink}
              to={`/seasons/${String(entry.seasonId)}/report`}
            >
              {historyRowText(entry.seasonId, entry.score, entry.rating)}
            </Link>
          </li>
        ))}
      </ul>
    </details>
  );
}

// Printed as the server sends it: the "three seasons" rule lives on the
// server, so a null rating is never filled in here.
export function RatingCard() {
  const query = useRating(true);
  return (
    <section aria-labelledby="rating-heading" className={styles.card}>
      <h2 id="rating-heading" className={styles.heading}>
        {RATING_TITLE}
      </h2>
      {query.isFetching && (
        <p role="status" className={styles.muted}>
          {RATING_LOADING_TEXT}
        </p>
      )}
      {!query.isFetching && query.isError && (
        <>
          <p role="status" className={styles.text}>
            {RATING_ERROR_TEXT}
          </p>
          <button
            type="button"
            className={styles.retry}
            onClick={() => {
              void query.refetch();
            }}
          >
            {RATING_RETRY_LABEL}
          </button>
        </>
      )}
      {query.data && (
        <>
          <Headline rating={query.data} />
          <Facts rating={query.data} />
          {!query.isFetching && query.data.pendingSeasons > 0 && (
            <button
              type="button"
              className={styles.retry}
              onClick={() => {
                void query.refetch();
              }}
            >
              {RATING_RETRY_LABEL}
            </button>
          )}
          <History rating={query.data} />
        </>
      )}
    </section>
  );
}
