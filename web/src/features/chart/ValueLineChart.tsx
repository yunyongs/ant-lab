import { useEffect, useRef } from 'react';
import {
  BaselineSeries,
  ColorType,
  createOptionsChart,
  LineStyle,
  type AutoscaleInfoProvider,
  type IChartApiBase,
  type ISeriesApi,
} from 'lightweight-charts';
import { useConvention } from '../format/convention.ts';
import {
  formatAxisTurn,
  formatAxisWon,
  type ValueChartData,
} from './adapter.ts';
import styles from './Chart.module.css';

function token(name: string): string {
  return getComputedStyle(document.documentElement)
    .getPropertyValue(name)
    .trim();
}

function lineColours() {
  return {
    topLineColor: token('--color-up'),
    bottomLineColor: token('--color-down'),
  };
}

// The guide line stays inside the visible range.
function keepInRange(guide: number | null): AutoscaleInfoProvider {
  return (original) => {
    const info = original();
    if (info?.priceRange == null || guide === null) return info;
    return {
      ...info,
      priceRange: {
        minValue: Math.min(info.priceRange.minValue, guide),
        maxValue: Math.max(info.priceRange.maxValue, guide),
      },
    };
  };
}

// Canvas drawing of the value line; loaded on demand so the library stays
// out of the main bundle. Assistive technology reads it as one labelled image;
// the same numbers are in the table next to it. The horizontal scale is the
// turn number itself (a price-type scale), so no date can appear on it.
export default function ValueLineChart({
  data,
  guideTitle,
  label,
}: {
  data: ValueChartData;
  guideTitle: string;
  label: string;
}) {
  const container = useRef<HTMLDivElement>(null);
  const series = useRef<ISeriesApi<'Baseline', number> | null>(null);
  const convention = useConvention();

  useEffect(() => {
    const element = container.current;
    // autoSize needs ResizeObserver; without it (test DOM) only the table shows.
    if (element === null || typeof ResizeObserver === 'undefined') return;
    const chart: IChartApiBase<number> = createOptionsChart(element, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: token('--color-surface') },
        textColor: token('--color-text-muted'),
        // The licence link to the maker is the text line under the chart; the
        // logo link would be a second, unnamed link.
        attributionLogo: false,
      },
      grid: {
        vertLines: { visible: false },
        horzLines: { color: token('--color-border') },
      },
      localization: {
        locale: 'ko-KR',
        precision: 0,
        priceFormatter: formatAxisWon,
        timeFormatter: formatAxisTurn,
      },
      // The page scrolls under a finger; the chart does not pan or zoom.
      handleScroll: false,
      handleScale: false,
      timeScale: { fixLeftEdge: true, fixRightEdge: true },
    });
    const line = chart.addSeries(BaselineSeries, {
      ...lineColours(),
      baseValue: { type: 'price', price: data.base },
      topFillColor1: 'transparent',
      topFillColor2: 'transparent',
      bottomFillColor1: 'transparent',
      bottomFillColor2: 'transparent',
      lineWidth: 2,
      priceLineVisible: false,
      lastValueVisible: false,
      autoscaleInfoProvider: keepInRange(data.guide),
    });
    line.setData(data.points);
    if (data.guide !== null) {
      line.createPriceLine({
        price: data.guide,
        color: token('--color-error'),
        lineWidth: 1,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: false,
        title: guideTitle,
      });
    }
    chart.timeScale().fitContent();
    series.current = line;
    return () => {
      series.current = null;
      chart.remove();
    };
  }, [data, guideTitle]);

  // The up/down colours follow the viewer's convention toggle.
  useEffect(() => {
    series.current?.applyOptions(lineColours());
  }, [convention]);

  return (
    <div
      ref={container}
      className={styles.canvas}
      role="img"
      aria-label={label}
      data-testid="value-chart-canvas"
    />
  );
}
