import type { Season } from '../seasons/labels.ts';
import { hasOrderable } from './hasOrderable.ts';
import { ItemList } from './ItemList.tsx';
import { fromAlert, type FeedItem } from './orderSource.ts';
import styles from './Feed.module.css';

// The nudge alerts of the turn as a plain labelled list: not an ARIA alert,
// no toast, no badge. With none, nothing is rendered at all.
export function AlertList({
  items,
  feed,
  season,
  onNotice,
}: {
  items: FeedItem[];
  // The turn's feed: when it reports a failed load itself, this list stays quiet.
  feed: FeedItem[];
  season: Season;
  onNotice?: (text: string | null) => void;
}) {
  if (items.length === 0) return null;
  return (
    <section aria-labelledby="alerts-title" className={styles.section}>
      <h3 id="alerts-title" className={styles.title}>
        알림
      </h3>
      <ItemList
        items={items}
        season={season}
        sourceOf={fromAlert}
        showLoadError={!hasOrderable(feed, season)}
        onNotice={onNotice}
      />
    </section>
  );
}
