import { Link } from 'react-router';
import { useRating, type Rating } from './queries.ts';
import {
  countedUntilText,
  countedText,
  DETAILS_TITLE,
  HISTORY_TITLE,
  historyRowText,
  notStartedText,
  noScoreText,
  pendingText,
  RATING_ERROR_TEXT,
  RATING_LOADING_TEXT,
  RATING_NONE_TEXT,
  RATING_RETRY_LABEL,
  RATING_TITLE,
  ratingValueText,
  untilText,
} from './ratingMessages.ts';
import styles from './Rating.module.css';

// Each part is capped so the row never exceeds six dots (3 + 3); beyond
// that only the text is shown. Nothing is summed: the two server numbers
// are drawn as they are.
const MAX_DOTS_PER_PART = 3;

function Dots({ counted, missing }: { counted: number; missing: number }) {
  if (counted > MAX_DOTS_PER_PART || missing > MAX_DOTS_PER_PART) return null;
  return (
    <span className={styles.dots} aria-hidden="true">
      {Array.from({ length: counted }, (_, index) => (
        <span
          key={`c${String(index)}`}
          className={`${styles.dot} ${styles.dotFilled}`}
        />
      ))}
      {Array.from({ length: missing }, (_, index) => (
        <span key={`m${String(index)}`} className={styles.dot} />
      ))}
    </span>
  );
}

function Strip({ rating }: { rating: Rating }) {
  if (rating.rating !== null) {
    return (
      <div className={styles.strip}>
        <p className={styles.head}>
          <span>{`${RATING_TITLE} `}</span>
          <strong>{ratingValueText(rating.rating)}</strong>
        </p>
        <p className={styles.sub}>{countedText(rating.countedSeasons)}</p>
      </div>
    );
  }
  if (rating.seasonsUntilPublic > 0) {
    return (
      <div className={styles.strip}>
        <Dots
          counted={rating.countedSeasons}
          missing={rating.seasonsUntilPublic}
        />
        <div className={styles.lines}>
          <p className={styles.head}>{untilText(rating.seasonsUntilPublic)}</p>
          <p className={styles.sub}>
            {countedUntilText(rating.countedSeasons, rating.seasonsUntilPublic)}
          </p>
        </div>
      </div>
    );
  }
  return (
    <div className={styles.strip}>
      <p className={styles.head}>{RATING_NONE_TEXT}</p>
    </div>
  );
}

function factLines(rating: Rating): string[] {
  const lines: string[] = [];
  if (rating.notStartedCount > 0)
    lines.push(notStartedText(rating.notStartedCount));
  if (rating.noScoreCount > 0) lines.push(noScoreText(rating.noScoreCount));
  if (rating.pendingSeasons > 0) lines.push(pendingText(rating.pendingSeasons));
  return lines;
}

function Details({ rating }: { rating: Rating }) {
  const lines = factLines(rating);
  if (lines.length === 0 && rating.history.length === 0) return null;
  return (
    <details>
      <summary className={styles.summary}>{DETAILS_TITLE}</summary>
      {lines.length > 0 && (
        <ul className={styles.facts}>
          {lines.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      )}
      {rating.history.length > 0 && (
        <>
          <p className={styles.sub}>{HISTORY_TITLE}</p>
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
        </>
      )}
    </details>
  );
}

// Printed as the server sends it: the "three seasons" rule lives on the
// server, so a null rating is never filled in here and no promise about the
// next finished season is made.
export function RatingCard() {
  const query = useRating(true);
  return (
    <section aria-label={RATING_TITLE} className={styles.card}>
      {query.isFetching && (
        <p role="status" className={styles.sub}>
          {RATING_LOADING_TEXT}
        </p>
      )}
      {!query.isFetching && query.isError && (
        <>
          <p role="status" className={styles.head}>
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
          <Strip rating={query.data} />
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
          <Details rating={query.data} />
        </>
      )}
    </section>
  );
}
