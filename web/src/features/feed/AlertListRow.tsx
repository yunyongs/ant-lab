import { useState } from 'react';
import type { ItemActions } from './itemActions.ts';
import type { FeedItem } from './orderSource.ts';
import styles from './AlertList.module.css';

// One alert as a banner line: the stored sentence (one line, cut with an
// ellipsis; tapping it shows the whole text), then the
// same order buttons a feed item gets. No identifier is printed or put in an
// attribute.
export function AlertListRow({
  item,
  name,
  actions,
  onOrder,
}: {
  item: FeedItem;
  name: string | null;
  actions: ItemActions | null;
  onOrder: (side: 'BUY' | 'SELL', opener: HTMLButtonElement) => void;
}) {
  const [open, setOpen] = useState(false);
  return (
    <li className={styles.banner}>
      <button
        type="button"
        className={open ? styles.bodyOpen : styles.body}
        aria-expanded={open}
        onClick={() => {
          setOpen((value) => !value);
        }}
      >
        {item.body}
      </button>
      {actions !== null && name !== null && (
        <div className={styles.buttons}>
          <button
            type="button"
            className={styles.buy}
            aria-label={`${name} 매수`}
            disabled={actions.disabled}
            onClick={(event) => {
              onOrder('BUY', event.currentTarget);
            }}
          >
            매수
          </button>
          {actions.sell && (
            <button
              type="button"
              className={styles.sell}
              aria-label={`${name} 매도`}
              disabled={actions.disabled}
              onClick={(event) => {
                onOrder('SELL', event.currentTarget);
              }}
            >
              매도
            </button>
          )}
        </div>
      )}
    </li>
  );
}
