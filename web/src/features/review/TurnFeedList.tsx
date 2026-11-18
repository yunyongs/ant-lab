import type { components } from '../../api/schema';
import { ReviewFeedItem } from './ReviewFeedItem.tsx';
import {
  FEED_ALERT_HEADING,
  FEED_ALERT_NOTE,
  FEED_EMPTY_TEXT,
  feedFoldNeverShownText,
  feedFoldText,
  FEED_NEVER_SHOWN_TEXT,
  FEED_NEWS_HEADING,
} from './reviewMessages.ts';
import styles from './Review.module.css';

type FeedItem = components['schemas']['ReviewFeedItemDto'];

// Two lists, each keeping server order: NUDGE items were shown in the alert
// list during play, the rest in the feed. `neverShown` is the contract's own
// rule (turns after the abandoned turn), decided by the caller.
export function TurnFeedList({
  turnNo,
  feed,
  neverShown,
}: {
  turnNo: number;
  feed: FeedItem[];
  neverShown: boolean;
}) {
  if (feed.length === 0)
    return <p className={styles.note}>{FEED_EMPTY_TEXT}</p>;
  const news = feed.filter((item) => item.kind !== 'NUDGE');
  const alerts = feed.filter((item) => item.kind === 'NUDGE');
  return (
    <>
      {neverShown && <p className={styles.note}>{FEED_NEVER_SHOWN_TEXT}</p>}
      <details className={neverShown ? styles.muted : styles.feedFold}>
        <summary className={styles.feedSummary}>
          {neverShown ? feedFoldNeverShownText(turnNo) : feedFoldText(turnNo)}
        </summary>
        {news.length > 0 && (
          <section className={styles.feedGroup}>
            <h3 className={styles.feedHeading}>{FEED_NEWS_HEADING}</h3>
            <ul className={styles.feedList}>
              {news.map((item) => (
                <ReviewFeedItem key={item.seq} item={item} />
              ))}
            </ul>
          </section>
        )}
        {alerts.length > 0 && (
          <section className={styles.feedGroup}>
            <h3 className={styles.feedHeading}>{FEED_ALERT_HEADING}</h3>
            <p className={styles.note}>{FEED_ALERT_NOTE}</p>
            <ul className={styles.feedList}>
              {alerts.map((item) => (
                <ReviewFeedItem key={item.seq} item={item} />
              ))}
            </ul>
          </section>
        )}
      </details>
    </>
  );
}
