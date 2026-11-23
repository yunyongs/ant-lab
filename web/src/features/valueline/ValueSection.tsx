import { lazy, Suspense, useId, useMemo, useState } from 'react';
import { valueChartData } from '../chart/adapter.ts';
import { ChartErrorBoundary } from '../chart/ChartErrorBoundary.tsx';
import { ratioToPercentTrimmed } from '../format/ratio.ts';
import { SectionHeading, type Collapse } from '../layout/SectionHeading.tsx';
import { QueryError } from '../seasons/QueryError.tsx';
import type { Season } from '../seasons/labels.ts';
import { useTurnSummaries } from './queries.ts';
import { ValueTable } from './ValueTable.tsx';
import {
  ATTRIBUTION_TEXT,
  ATTRIBUTION_URL,
  chartLabel,
  guideTitle,
  LEGEND_AXIS,
  LEGEND_LINE,
  legendGuide,
  CHART_FAILED,
  VALUE_LOADING,
  VALUE_TITLE,
  VALUE_TOO_FEW,
} from './valueLineMessages.ts';
import styles from './ValueLine.module.css';

// The chart library is fetched only when a chart is drawn.
const ValueLineChart = lazy(() => import('../chart/ValueLineChart.tsx'));

// Total value per turn, 0 up to the current turn: a line chart with the risk
// limit as a guide, and the same numbers as a table below it.
export function ValueSection({
  season,
  collapse,
}: {
  season: Season;
  collapse?: Collapse;
}) {
  const titleId = useId();
  const panelId = useId();
  const [open, setOpen] = useState(collapse?.defaultOpen ?? true);
  const list = useTurnSummaries(season.id, season.currentTurnNo, open);
  const items = list.data;
  const chart = useMemo(
    () =>
      items === undefined ? null : valueChartData(items, season.riskLimit),
    [items, season.riskLimit],
  );
  const limit = ratioToPercentTrimmed(season.riskLimit);
  const lastTurn = items?.[items.length - 1]?.turnNo ?? 0;
  return (
    <section aria-labelledby={titleId} className={styles.section}>
      <SectionHeading
        id={titleId}
        className={styles.title}
        collapsible={collapse !== undefined}
        open={open}
        panelId={panelId}
        onToggle={() => {
          setOpen((value) => !value);
        }}
      >
        {VALUE_TITLE}
      </SectionHeading>
      {open && (
        <div id={panelId} className={styles.panel}>
          {list.isPending && (
            <p role="status" className={styles.note}>
              {VALUE_LOADING}
            </p>
          )}
          {list.isError && (
            <QueryError
              error={list.error}
              onRetry={() => {
                void list.refetch();
              }}
            />
          )}
          {items !== undefined && items.length < 2 && (
            <p className={styles.note}>{VALUE_TOO_FEW}</p>
          )}
          {chart !== null && (
            <figure className={styles.figure}>
              <ChartErrorBoundary
                key={lastTurn}
                fallback={
                  <p role="status" className={styles.note}>
                    {CHART_FAILED}
                  </p>
                }
              >
                <Suspense fallback={<div className={styles.placeholder} />}>
                  <ValueLineChart
                    data={chart}
                    guideTitle={guideTitle(limit)}
                    label={chartLabel(lastTurn)}
                  />
                </Suspense>
              </ChartErrorBoundary>
              <figcaption className={styles.legend}>
                <ul>
                  <li>{LEGEND_LINE}</li>
                  {chart.guide !== null && <li>{legendGuide(limit)}</li>}
                  <li>{LEGEND_AXIS}</li>
                </ul>
                <p className={styles.attribution}>
                  {ATTRIBUTION_TEXT}{' '}
                  <a href={ATTRIBUTION_URL} rel="noopener noreferrer">
                    {ATTRIBUTION_URL}
                  </a>
                </p>
              </figcaption>
            </figure>
          )}
          {items !== undefined && items.length > 0 && (
            <ValueTable items={items} />
          )}
        </div>
      )}
    </section>
  );
}
