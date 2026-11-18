import { useId } from 'react';
import { formatWon } from '../format/money.ts';
import { bandGeometry, VIEW_HEIGHT, VIEW_WIDTH } from './bandGeometry.ts';
import type { Projection } from './phases.ts';
import { CHART_TITLE } from './projectionMessages.ts';
import styles from './Projection.module.css';

// A picture of numbers that are all listed as text below it. Draws nothing
// when the geometry cannot be computed exactly.
export function BandChart({ projection }: { projection: Projection }) {
  const titleId = useId();
  const geometry = bandGeometry(projection.bands, projection.totalValue);
  if (geometry === null) return null;
  const lastTurn = projection.bands[projection.bands.length - 1]?.turn;
  return (
    <figure className={styles.figure}>
      <svg
        className={styles.chart}
        viewBox={`0 0 ${String(VIEW_WIDTH)} ${String(VIEW_HEIGHT)}`}
        role="img"
        aria-labelledby={titleId}
      >
        <title id={titleId}>{CHART_TITLE}</title>
        <polygon className={styles.outer} points={geometry.outer} />
        <polygon className={styles.inner} points={geometry.inner} />
        <polyline className={styles.median} points={geometry.median} />
        <line
          className={styles.now}
          x1="0"
          x2={String(VIEW_WIDTH)}
          y1={geometry.nowY}
          y2={geometry.nowY}
        />
      </svg>
      <figcaption className={styles.legend}>
        <p className={styles.axis}>
          <span>지금</span>
          <span>
            {lastTurn === undefined ? '' : `${String(lastTurn)}턴 뒤`}
          </span>
        </p>
        <ul>
          <li>옅은 띠: 하위 5%부터 상위 5%까지</li>
          <li>진한 띠: 하위 25%부터 상위 25%까지</li>
          <li>실선: 중앙값 (띠와 함께 읽어 주세요)</li>
          <li>점선: 지금 총 평가액 {formatWon(projection.totalValue)}</li>
          <li>
            세로 범위: {formatWon(geometry.min)} ~ {formatWon(geometry.max)}
          </li>
        </ul>
      </figcaption>
    </figure>
  );
}
