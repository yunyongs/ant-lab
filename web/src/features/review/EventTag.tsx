import type { components } from '../../api/schema';
import { CATEGORY_LABEL, PHASE_LABEL } from './reviewLabels.ts';
import { sourceParts } from './sourceParts.ts';
import styles from './Review.module.css';

type ReviewEvent = components['schemas']['ReviewEventDto'];

// Whitelisted fields only: `eventType` codes stay out of the screen.
export function EventTag({ event }: { event: ReviewEvent }) {
  const parts = sourceParts(event.source);
  const linkCount = parts.filter((part) => part.kind === 'link').length;
  return (
    <li className={styles.event}>
      <span className={styles.pill}>
        {`${CATEGORY_LABEL[event.category]} · ${PHASE_LABEL[event.phase]}`}
      </span>
      <span className={styles.eventTitle}>{event.title}</span>
      {event.hindsight !== null && (
        <span className={styles.hindsight}>{`사후: ${event.hindsight}`}</span>
      )}
      {parts.length > 0 && (
        <span className={styles.sources}>
          {parts.map((part) => {
            if (part.kind === 'link') {
              return (
                <a
                  key={`${part.href}-${String(part.n)}`}
                  className={styles.sourceLink}
                  href={part.href}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {linkCount > 1 ? `출처 ${String(part.n)}` : '출처'}
                </a>
              );
            }
            return (
              <span key={part.text} className={styles.sourceText}>
                {`출처: ${part.text}`}
              </span>
            );
          })}
        </span>
      )}
    </li>
  );
}
