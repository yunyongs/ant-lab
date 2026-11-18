import type { components } from '../../api/schema';
import { EventTagContent } from './EventTag.tsx';
import { FEED_KIND_LABEL } from './reviewLabels.ts';
import { FEED_NOISE_TEXT, FEED_SIGNAL_TEXT } from './reviewMessages.ts';
import styles from './Review.module.css';

type FeedItem = components['schemas']['ReviewFeedItemDto'];

// Each field is followed on its own: the badge reads `signal`, the event block
// reads `event`, and no rule between them is applied. `assetId` and `groupId`
// are never read.
export function ReviewFeedItem({ item }: { item: FeedItem }) {
  return (
    <li className={styles.feedItem}>
      <div className={styles.badges}>
        <span className={styles.pill}>{FEED_KIND_LABEL[item.kind]}</span>
        {item.signal && (
          <span className={`${styles.pill} ${styles.signal}`}>
            {FEED_SIGNAL_TEXT}
          </span>
        )}
        {!item.signal && item.kind !== 'NUDGE' && (
          <span className={styles.pill}>{FEED_NOISE_TEXT}</span>
        )}
      </div>
      <p className={styles.feedBody}>{item.body}</p>
      {item.event !== null && (
        <div className={styles.event}>
          <EventTagContent event={item.event} />
        </div>
      )}
      {item.ordersFromItem >= 1 && (
        <p className={styles.note}>
          {`이 항목에서 낸 주문 ${String(item.ordersFromItem)}건`}
        </p>
      )}
    </li>
  );
}
