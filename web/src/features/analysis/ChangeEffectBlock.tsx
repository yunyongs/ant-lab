import { Change } from '../format/Change.tsx';
import type { Analysis } from './queries.ts';
import {
  CHANGE_EFFECTS_EMPTY,
  CHANGE_EFFECTS_UNAVAILABLE,
  NOT_SCORED_NOTE,
} from './analysisMessages.ts';
import styles from './Analysis.module.css';

export function ChangeEffectBlock({
  effects,
}: {
  effects: Analysis['changeEffects'];
}) {
  return (
    <section className={styles.card} aria-labelledby="analysis-change">
      <h2 id="analysis-change" className={styles.cardHeading}>
        전술 변경 뒤 6턴 효과
      </h2>
      <p className={styles.note}>
        {`${NOT_SCORED_NOTE} 시즌 끝 가까이에서 바꾸면 6턴보다 짧게 잽니다.`}
      </p>
      {effects === null && (
        <p className={styles.note}>{CHANGE_EFFECTS_UNAVAILABLE}</p>
      )}
      {effects?.length === 0 && (
        <p className={styles.note}>{CHANGE_EFFECTS_EMPTY}</p>
      )}
      {effects !== null && effects.length > 0 && (
        <ul className={styles.list}>
          {effects.map((effect, index) => (
            <li key={index}>
              {`${String(effect.turnNo)}턴 변경 · ${String(effect.windowTurns)}턴 뒤 · `}
              <Change value={effect.effectWon} kind="won" />
              {' ('}
              <Change value={effect.effectReturn} kind="percent" />
              {')'}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
