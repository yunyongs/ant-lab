import { useId } from 'react';
import type { components } from '../../api/schema';
import { ratioToCssPercent, ratioToPercent } from '../format/ratio.ts';
import {
  AXIS_ROWS,
  directionWord,
  NO_AXIS_TEXT,
  NO_MACRO_TEXT,
  REGIME_ROWS,
} from './macroLabels.ts';
import { strengthText } from './strengthText.ts';
import styles from './Macro.module.css';

type MacroDto = components['schemas']['MacroDto'];
type MacroAxis = components['schemas']['MacroAxisDto'];

function axisText(axis: MacroAxis | null): string {
  const word = axis === null ? undefined : directionWord(axis.direction);
  if (axis === null || word === undefined) return NO_AXIS_TEXT;
  return `${word} · ${strengthText(axis.strength)}`;
}

// Whitelisted keys only: the DTO is never spread, and an unknown key is
// never read. A null axis means no data, not neutral.
// An absent macro, axis or probabilities, or an unknown direction, is no data.
export function MacroPanel({ macro }: { macro?: MacroDto | null }) {
  const titleId = useId();
  const axes = AXIS_ROWS.map(({ key, label }) => ({
    key,
    label,
    axis: macro?.axes?.[key] ?? null,
  }));
  const probabilities = macro?.probabilities ?? null;
  const empty =
    probabilities === null &&
    axes.every(({ axis }) => axisText(axis) === NO_AXIS_TEXT);
  return (
    <section aria-labelledby={titleId} className={styles.panel}>
      <h3 id={titleId} className={styles.title}>
        거시 환경
      </h3>
      {empty ? (
        <p className={styles.note}>{NO_MACRO_TEXT}</p>
      ) : (
        <>
          <dl className={styles.axes}>
            {axes.map(({ key, label, axis }) => (
              <div key={key} className={styles.axisRow}>
                <dt>{label}</dt>
                <dd>{axisText(axis)}</dd>
              </div>
            ))}
          </dl>
          {probabilities !== null && (
            <div className={styles.regimes}>
              <h4 className={styles.subtitle}>국면 확률</h4>
              <ul className={styles.list}>
                {REGIME_ROWS.map(({ key, label }) => (
                  <li key={key} className={styles.regimeRow}>
                    <span>{label}</span>
                    <span className={styles.bar} aria-hidden="true">
                      <span
                        className={styles.fill}
                        style={{ width: ratioToCssPercent(probabilities[key]) }}
                      />
                    </span>
                    <span className={styles.percent}>
                      {ratioToPercent(probabilities[key])}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
    </section>
  );
}
