import type { Season } from '../seasons/labels.ts';
import { NO_NEWS_TEXT } from './feedMessages.ts';
import { ItemList } from './ItemList.tsx';
import { fromFeed, type FeedItem } from './orderSource.ts';
import styles from './Feed.module.css';

// Every stored item of the turn, in the stored order, as plain text. Nothing
// is sorted, merged, cut or styled from the sentence.
export function FeedSection({
  items,
  season,
  onNotice,
}: {
  items: FeedItem[];
  season: Season;
  onNotice?: (text: string | null) => void;
}) {
  return (
    <section aria-labelledby="feed-title" className={styles.section}>
      <h3 id="feed-title" className={styles.title}>
        소식
      </h3>
      {items.length === 0 ? (
        <p className={styles.note}>{NO_NEWS_TEXT}</p>
      ) : (
        <ItemList
          items={items}
          season={season}
          sourceOf={fromFeed}
          onNotice={onNotice}
        />
      )}
    </section>
  );
}
