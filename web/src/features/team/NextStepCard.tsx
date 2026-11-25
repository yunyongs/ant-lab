import type { ReactNode } from 'react';
import styles from './NextStepCard.module.css';

// A bar that sticks above the bottom menu; `action` is the slot the
// turn-advance button fills.
export function NextStepCard({
  holdingCount,
  action,
}: {
  holdingCount: number;
  action?: ReactNode;
}) {
  return (
    <section className={styles.bar} aria-label="다음 단계">
      {action}
      <p className={styles.hint}>
        {holdingCount >= 1
          ? '남은 현금은 골키퍼로 1턴을 맞습니다'
          : '아직 산 종목이 없습니다. 이대로 넘기면 전부 현금(골키퍼)으로 시작합니다.'}
      </p>
    </section>
  );
}
