import type { ReactNode } from 'react';
import styles from './Team.module.css';

// `action` is the slot the turn-advance button fills.
export function NextStepCard({
  holdingCount,
  action,
}: {
  holdingCount: number;
  action?: ReactNode;
}) {
  return (
    <section className={styles.card} aria-label="다음 단계">
      <h3 className={styles.cardHeading}>다음 단계</h3>
      <p className={styles.noMargin}>
        {holdingCount >= 1
          ? '팀 구성을 마쳤으면 다음 턴으로 넘어갑니다. 넘긴 뒤에는 턴 사이에만 매매할 수 있습니다.'
          : '아직 산 종목이 없습니다. 이대로 넘기면 전부 현금(골키퍼)으로 시작합니다.'}
      </p>
      {action}
    </section>
  );
}
