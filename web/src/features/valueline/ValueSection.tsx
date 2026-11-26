import { lazy, Suspense, useMemo } from 'react';
import { valueChartData } from '../chart/adapter.ts';
import { ChartErrorBoundary } from '../chart/ChartErrorBoundary.tsx';
import { ratioToPercentTrimmed } from '../format/ratio.ts';
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
  LEGEND_BENCHMARK,
  LEGEND_LINE,
  legendGuide,
  CHART_FAILED,
  CHART_UNDRAWABLE,
  VALUE_LOADING,
  VALUE_TOO_FEW,
} from './valueLineMessages.ts';
import styles from './ValueLine.module.css';

// The chart library is fetched only when a chart is drawn.
const ValueLineChart = lazy(() => import('../chart/ValueLineChart.tsx'));

// Total value per turn, 0 up to the turn shown. Past turns never show later
// ones: the list is cut at `throughTurn`.
function useValueItems(season: Season, throughTurn: number) {
  const list = useTurnSummaries(season.id, season.currentTurnNo, true);
  const items = useMemo(
    () => list.data?.filter((item) => item.turnNo <= throughTurn),
    [list.data, throughTurn],
  );
  return { list, items };
}

function LoadState({
  list,
}: {
  list: ReturnType<typeof useValueItems>['list'];
}) {
  return (
    <>
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
    </>
  );
}

// The line chart with the risk limit as a guide.
export function ValueChartPanel({
  season,
  throughTurn,
}: {
  season: Season;
  throughTurn: number;
}) {
  const { list, items } = useValueItems(season, throughTurn);
  const chart = useMemo(
    () =>
      items === undefined ? null : valueChartData(items, season.riskLimit),
    [items, season.riskLimit],
  );
  const limit = ratioToPercentTrimmed(season.riskLimit);
  const lastTurn = items?.[items.length - 1]?.turnNo ?? 0;
  return (
    <div className={styles.panel}>
      <LoadState list={list} />
      {items !== undefined && items.length < 2 && (
        <p className={styles.note}>{VALUE_TOO_FEW}</p>
      )}
      {items !== undefined && items.length >= 2 && chart === null && (
        <p role="status" className={styles.note}>
          {CHART_UNDRAWABLE}
        </p>
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
                label={chartLabel(lastTurn, chart.benchmark !== null)}
              />
            </Suspense>
          </ChartErrorBoundary>
          <figcaption className={styles.legend}>
            <ul>
              <li>{LEGEND_LINE}</li>
              {chart.benchmark !== null && <li>{LEGEND_BENCHMARK}</li>}
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
    </div>
  );
}

// The same numbers as text, one row per turn.
export function ValueTablePanel({
  season,
  throughTurn,
}: {
  season: Season;
  throughTurn: number;
}) {
  const { list, items } = useValueItems(season, throughTurn);
  return (
    <div className={styles.tableCard}>
      <LoadState list={list} />
      {items !== undefined && items.length > 0 && <ValueTable items={items} />}
    </div>
  );
}
