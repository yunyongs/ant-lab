import type { Season } from '../seasons/labels.ts';
import { canTrade } from '../seasons/seasonGate.ts';
import { FeedItemRow } from './FeedItemRow.tsx';
import { NO_NEWS_TEXT } from './feedMessages.ts';
import { itemActions } from './itemActions.ts';
import { fromFeed, type FeedItem } from './orderSource.ts';
import { SourcedOrder } from './SourcedOrder.tsx';
import styles from './Feed.module.css';

const noop = () => {};

// Every stored item of the turn, in the stored order, as plain text. Nothing
// is sorted, merged, cut or styled from the sentence.
export function FeedSection({
  items,
  season,
  onNotice = noop,
}: {
  items: FeedItem[];
  season: Season;
  onNotice?: (text: string | null) => void;
}) {
  // No order data is requested unless an item can start an order.
  const orderable =
    canTrade(season) && items.some((item) => item.assetId !== null);
  return (
    <section aria-labelledby="feed-title" className={styles.section}>
      <h3 id="feed-title" className={styles.title}>
        소식
      </h3>
      {items.length === 0 ? (
        <p className={styles.note}>{NO_NEWS_TEXT}</p>
      ) : orderable ? (
        <SourcedOrder season={season} onNotice={onNotice}>
          {(order) => (
            <ul className={styles.list}>
              {items.map((item) => (
                <FeedItemRow
                  key={`${String(item.turnNo)}-${String(item.seq)}`}
                  item={item}
                  name={
                    item.assetId === null ? null : order.nameOf(item.assetId)
                  }
                  actions={itemActions(item, order.context)}
                  onOrder={(side, opener) => {
                    if (item.assetId === null) return;
                    order.start(side, item.assetId, fromFeed(item), opener);
                  }}
                />
              ))}
            </ul>
          )}
        </SourcedOrder>
      ) : (
        <ul className={styles.list}>
          {items.map((item) => (
            <FeedItemRow
              key={`${String(item.turnNo)}-${String(item.seq)}`}
              item={item}
              name={null}
              actions={null}
              onOrder={noop}
            />
          ))}
        </ul>
      )}
    </section>
  );
}
