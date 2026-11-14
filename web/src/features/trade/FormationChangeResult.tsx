import { useEffect, useRef } from 'react';
import { Link } from 'react-router';
import { formationText } from '../seasons/labels.ts';
import type { FormationChange } from './queries.ts';
import styles from './Trade.module.css';

// The server's answer, shown as typed; focus lands here so it is announced.
export function FormationChangeResult({
  result,
  seasonId,
  onAgain,
}: {
  result: FormationChange;
  seasonId: number;
  onAgain: () => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    box.current?.focus();
  }, []);
  return (
    <div
      ref={box}
      role="status"
      tabIndex={-1}
      className={styles.card}
      aria-labelledby="formation-result-title"
    >
      <h2 id="formation-result-title" className={styles.cardHeading}>
        {`${String(result.turnNo)}턴 전술을 바꿨습니다`}
      </h2>
      <dl className={styles.rows}>
        <div className={styles.rowBetween}>
          <dt>이전</dt>
          <dd>{formationText(result.before)}</dd>
        </div>
        <div className={styles.rowBetween}>
          <dt>이후</dt>
          <dd>{formationText(result.after)}</dd>
        </div>
      </dl>
      <p className={styles.reasonLine}>
        {result.reason === null ? '사유 없음' : `사유: ${result.reason}`}
      </p>
      <p className={styles.hint}>
        이번 턴에 이어서 내는 주문은 이 변경에 묶입니다. 비중은 매매 화면에서
        직접 사고팔아 맞춥니다.
      </p>
      <div className={styles.formActions}>
        <Link
          to={`/seasons/${String(seasonId)}/trade`}
          className={styles.secondaryLink}
        >
          매매로 돌아가기
        </Link>
        <button type="button" className={styles.primary} onClick={onAgain}>
          한 번 더 바꾸기
        </button>
      </div>
    </div>
  );
}
