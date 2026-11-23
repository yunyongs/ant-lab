import { useId, useState } from 'react';
import { SectionHeading, type Collapse } from '../layout/SectionHeading.tsx';
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
  collapse,
}: {
  items: FeedItem[];
  season: Season;
  onNotice?: (text: string | null) => void;
  collapse?: Collapse;
}) {
  const panelId = useId();
  const [open, setOpen] = useState(collapse?.defaultOpen ?? true);
  return (
    <section aria-labelledby="feed-title" className={styles.section}>
      <SectionHeading
        id="feed-title"
        className={styles.title}
        collapsible={collapse !== undefined}
        open={open}
        panelId={panelId}
        onToggle={() => {
          setOpen((value) => !value);
        }}
      >
        소식
      </SectionHeading>
      {open && (
        <div id={panelId} className={styles.panel}>
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
        </div>
      )}
    </section>
  );
}
