import type { ReactNode } from 'react';
import styles from './SectionHeading.module.css';

export interface Collapse {
  defaultOpen: boolean;
}

// A section title that is a plain heading, or a heading holding the toggle
// button of a collapsible section.
export function SectionHeading({
  id,
  className,
  collapsible,
  open,
  panelId,
  onToggle,
  children,
}: {
  id: string;
  className?: string;
  collapsible: boolean;
  open: boolean;
  panelId: string;
  onToggle: () => void;
  children: ReactNode;
}) {
  if (!collapsible) {
    return (
      <h3 id={id} className={className}>
        {children}
      </h3>
    );
  }
  return (
    <h3 id={id} className={className}>
      <button
        type="button"
        className={styles.toggle}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={onToggle}
      >
        <span>{children}</span>
        <span aria-hidden="true" className={styles.chevron}>
          {open ? '접기' : '펼치기'}
        </span>
      </button>
    </h3>
  );
}
