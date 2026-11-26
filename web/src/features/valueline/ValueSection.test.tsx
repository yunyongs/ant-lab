import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { expectNoDatePattern } from '../../test/expectNoDatePattern.ts';
import { seasonFixture } from '../../test/fixtures/seasons.ts';
import {
  turnSummariesFixture,
  turnSummariesWithNullBenchmarkFixture,
} from '../../test/fixtures/turnSummaries.ts';
import { renderWithProviders } from '../../test/renderWithProviders.tsx';
import { server } from '../../test/server.ts';
import { formatWon } from '../format/money.ts';
import { ratioToPercent } from '../format/ratio.ts';
import { signedChange } from '../format/signedChange.ts';
import type { TurnSummary } from './queries.ts';
import { ValueChartPanel, ValueTablePanel } from './ValueSection.tsx';

const lib = vi.hoisted(() => {
  const benchmarkSeries = {
    setData: vi.fn(),
    createPriceLine: vi.fn(),
    applyOptions: vi.fn(),
  };
  const teamSeries = {
    setData: vi.fn(),
    createPriceLine: vi.fn(),
    applyOptions: vi.fn(),
  };
  const chart = {
    addSeries: vi.fn(),
    timeScale: vi.fn(() => ({ fitContent: vi.fn() })),
    remove: vi.fn(),
  };
  return {
    benchmarkSeries,
    teamSeries,
    chart,
    createOptionsChart: vi.fn(() => chart),
  };
});

vi.mock('lightweight-charts', () => ({
  BaselineSeries: 'Baseline',
  ColorType: { Solid: 'solid' },
  LineStyle: { Dashed: 2 },
  LineSeries: 'Line',
  createOptionsChart: lib.createOptionsChart,
}));

function serveSummaries(currentTurnNo: number) {
  const body = turnSummariesFixture(currentTurnNo);
  server.use(
    http.get('*/api/v1/seasons/7/turns', () => HttpResponse.json(body)),
  );
  return body.items;
}

function renderSection(currentTurnNo: number, throughTurn = currentTurnNo) {
  return renderWithProviders(
    <ValueChartPanel
      season={seasonFixture({ id: 7, currentTurnNo })}
      throughTurn={throughTurn}
    />,
  );
}

function renderTable(currentTurnNo: number, throughTurn = currentTurnNo) {
  return renderWithProviders(
    <ValueTablePanel
      season={seasonFixture({ id: 7, currentTurnNo })}
      throughTurn={throughTurn}
    />,
  );
}

beforeEach(() => {
  lib.chart.addSeries
    .mockReset()
    .mockImplementationOnce(() => lib.benchmarkSeries)
    .mockImplementationOnce(() => lib.teamSeries);
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe('value chart section', () => {
  it('loads the chart on demand and gives it an accessible label', async () => {
    serveSummaries(4);
    const view = renderSection(4);
    const chart = await screen.findByRole('img', {
      name: '0턴부터 4턴까지 총 평가액과 시장 기준선 선 그래프. 같은 숫자는 표 탭에 있습니다.',
    });
    expect(chart).toBeInTheDocument();
    await waitFor(() => {
      expect(lib.createOptionsChart).toHaveBeenCalledTimes(1);
    });
    expect(lib.chart.addSeries).toHaveBeenNthCalledWith(
      1,
      'Line',
      expect.objectContaining({
        lineWidth: 2,
        lineStyle: 2,
        priceLineVisible: false,
        lastValueVisible: false,
        crosshairMarkerVisible: false,
      }),
    );
    expect(lib.chart.addSeries).toHaveBeenNthCalledWith(
      2,
      'Baseline',
      expect.objectContaining({
        baseValue: { type: 'price', price: 100000000 },
        topFillColor1: 'transparent',
        topFillColor2: 'transparent',
        bottomFillColor1: 'transparent',
        bottomFillColor2: 'transparent',
        lineWidth: 2,
        priceLineVisible: false,
        lastValueVisible: false,
      }),
    );
    expect(lib.benchmarkSeries.setData).toHaveBeenCalledWith([
      { time: 0, value: 100000000 },
      { time: 1, value: 100512000 },
      { time: 2, value: 99870000 },
      { time: 3, value: 101230000 },
      { time: 4, value: 98650000 },
    ]);
    expect(lib.teamSeries.setData).toHaveBeenCalledWith([
      { time: 0, value: 100000000 },
      { time: 1, value: 103300000 },
      { time: 2, value: 101250000 },
      { time: 3, value: 104820000 },
      { time: 4, value: 97000000 },
    ]);
    expect(lib.chart.addSeries).toHaveBeenCalledTimes(2);
    // Peak 104,820,000 x (1 - 0.20) = 83,856,000, without an axis number.
    expect(lib.teamSeries.createPriceLine).toHaveBeenCalledWith(
      expect.objectContaining({
        price: 83856000,
        axisLabelVisible: false,
        title: '위험 선언 -20%',
      }),
    );
    expect(
      screen.getByText(
        '빨간 가로 점선: 위험 선언 -20%. 지금까지 가장 높았던 총 평가액에서 그만큼 내려간 높이입니다.',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'https://www.tradingview.com/' }),
    ).toBeInTheDocument();
    // Verbatim NOTICE text of the pinned library version (v5.2.1).
    expect(screen.getByText(/^그래프: /).textContent).toBe(
      '그래프: TradingView Lightweight Charts™ Copyright (c) 2025 TradingView, Inc. https://www.tradingview.com/',
    );
    expectNoDatePattern(view.container);
  });

  it('lists the same numbers as the API in a table', async () => {
    const items = serveSummaries(4);
    renderTable(4);
    const table = await screen.findByRole('table', {
      name: '턴별 총 평가액 (그래프와 같은 숫자)',
    });
    const body = within(table).getAllByRole('row').slice(1);
    expect(body).toHaveLength(items.length);
    items.forEach((item, index) => {
      const row = body[index];
      if (row === undefined) throw new Error('missing row');
      const cells = within(row).getAllByRole('cell');
      expect(within(row).getByRole('rowheader').textContent).toBe(
        `${String(item.turnNo)}턴`,
      );
      expect(cells[0]?.textContent).toContain(formatWon(item.totalValue));
      expect(
        within(row).getByText(`기준선 ${formatWon(item.benchmarkValue ?? '')}`),
      ).toBeInTheDocument();
      expect(cells[1]?.textContent).toBe(formatWon(item.benchmarkValue ?? ''));
      expect(cells[2]?.textContent).toBe(
        signedChange(item.periodReturn, 'percent').text,
      );
      expect(cells[3]?.textContent).toBe(ratioToPercent(item.drawdown));
    });
    expect(within(table).getByText('104,820,000원')).toBeInTheDocument();
    expect(within(table).getAllByText('-7.46%')).toHaveLength(2);
  });

  it('shows a short message instead of a chart for one point', async () => {
    serveSummaries(0);
    renderSection(0);
    expect(
      await screen.findByText(
        '턴을 하나 넘기면 총 평가액 그래프가 그려집니다. 지금은 표 탭만 봐 주세요.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(lib.createOptionsChart).not.toHaveBeenCalled();
  });

  it('keeps the table when a value is too large to draw', async () => {
    server.use(
      http.get('*/api/v1/seasons/7/turns', () =>
        HttpResponse.json({
          items: [
            {
              turnNo: 0,
              totalValue: '100000000',
              drawdown: '0.00000000',
              periodReturn: '0.00000000',
            },
            {
              turnNo: 1,
              totalValue: '9007199254740993',
              drawdown: '0.00000000',
              periodReturn: '90071991.54740993',
            },
          ],
        }),
      ),
    );
    renderTable(1);
    expect(
      await screen.findByText('9,007,199,254,740,993원'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('tells the chart tab it cannot draw a value that is too large', async () => {
    server.use(
      http.get('*/api/v1/seasons/7/turns', () =>
        HttpResponse.json({
          items: [
            {
              turnNo: 0,
              totalValue: '100000000',
              drawdown: '0.00000000',
              periodReturn: '0.00000000',
            },
            {
              turnNo: 1,
              totalValue: '9007199254740993',
              drawdown: '0.00000000',
              periodReturn: '90071991.54740993',
            },
          ],
        }),
      ),
    );
    renderSection(1);
    expect(
      await screen.findByText(
        '그래프로 그릴 수 없습니다 — 표 탭에서 확인하세요.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(lib.createOptionsChart).not.toHaveBeenCalled();
  });

  it('keeps a past turn out of the chart data and the risk guide', async () => {
    serveSummaries(4);
    renderSection(4, 2);
    await screen.findByRole('img', { name: /0턴부터 2턴까지/ });
    await waitFor(() => {
      expect(lib.benchmarkSeries.setData).toHaveBeenCalled();
    });
    expect(lib.benchmarkSeries.setData).toHaveBeenCalledWith([
      { time: 0, value: 100000000 },
      { time: 1, value: 100512000 },
      { time: 2, value: 99870000 },
    ]);
    expect(lib.teamSeries.setData).toHaveBeenCalledWith([
      { time: 0, value: 100000000 },
      { time: 1, value: 103300000 },
      { time: 2, value: 101250000 },
    ]);
    // Peak through turn 2 is 103,300,000; the later peak 104,820,000 is unseen.
    expect(lib.teamSeries.createPriceLine).toHaveBeenCalledWith(
      expect.objectContaining({ price: 82640000 }),
    );
  });

  it('follows the up/down convention when it changes', async () => {
    serveSummaries(2);
    renderSection(2);
    await waitFor(() => {
      expect(lib.chart.addSeries).toHaveBeenCalledTimes(2);
    });
    const { setConvention, resetConventionForTests } =
      await import('../format/convention.ts');
    lib.teamSeries.applyOptions.mockClear();
    setConvention('western');
    await waitFor(() => {
      expect(lib.teamSeries.applyOptions).toHaveBeenCalledTimes(1);
    });
    resetConventionForTests();
  });

  it('replaces an all-zero drawdown column with one sentence', async () => {
    serveSummaries(1);
    renderTable(1);
    const table = await screen.findByRole('table');
    expect(
      within(table).queryByRole('columnheader', { name: '누적 낙폭' }),
    ).toBeNull();
    expect(screen.getByText('누적 낙폭은 모든 턴 0.00%')).toBeInTheDocument();
  });

  it('shows the drawdown column once any turn has a drawdown', async () => {
    serveSummaries(2);
    renderTable(2);
    const table = await screen.findByRole('table');
    expect(
      within(table).getByRole('columnheader', { name: '누적 낙폭' }),
    ).toBeInTheDocument();
    expect(screen.queryByText('누적 낙폭은 모든 턴 0.00%')).toBeNull();
  });

  it('omits the benchmark line and column when one shown value is null', async () => {
    server.use(
      http.get('*/api/v1/seasons/7/turns', () =>
        HttpResponse.json(turnSummariesWithNullBenchmarkFixture(2)),
      ),
    );
    renderSection(2);
    expect(
      await screen.findByRole('img', {
        name: '0턴부터 2턴까지 총 평가액 선 그래프. 같은 숫자는 표 탭에 있습니다.',
      }),
    ).toBeInTheDocument();
    expect(lib.chart.addSeries).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/회색 점선: 시장 기준선/)).toBeNull();
    renderTable(2);
    const table = await screen.findByRole('table');
    expect(
      within(table).queryByRole('columnheader', { name: '시장 기준선' }),
    ).toBeNull();
    expect(within(table).queryByText(/기준선/)).toBeNull();
  });

  it.each([
    ['null', (item: TurnSummary) => ({ ...item, benchmarkValue: null })],
    ['malformed', (item: TurnSummary) => ({ ...item, benchmarkValue: '1.5' })],
    [
      'absent',
      (item: TurnSummary) => {
        const { benchmarkValue, ...withoutBenchmark } = item;
        void benchmarkValue;
        return withoutBenchmark;
      },
    ],
  ])('keeps total value cells when benchmark is %s', async (_name, change) => {
    const fixture = turnSummariesFixture(2);
    server.use(
      http.get('*/api/v1/seasons/7/turns', () =>
        HttpResponse.json({
          items: fixture.items.map((item, index) =>
            index === 1 ? change(item) : item,
          ),
        }),
      ),
    );
    renderTable(2);
    const table = await screen.findByRole('table');
    expect(
      within(table).queryByRole('columnheader', { name: '시장 기준선' }),
    ).toBeNull();
    expect(within(table).queryByText(/기준선/)).toBeNull();
    expect(within(table).getByText('100,000,000원')).toBeInTheDocument();
    expect(within(table).getByText('103,300,000원')).toBeInTheDocument();
    expect(within(table).getByText('101,250,000원')).toBeInTheDocument();
  });

  it('keeps a benchmark when only a later turn is null', async () => {
    const fixture = turnSummariesFixture(4);
    server.use(
      http.get('*/api/v1/seasons/7/turns', () =>
        HttpResponse.json({
          items: fixture.items.map((item) =>
            item.turnNo === 3 ? { ...item, benchmarkValue: null } : item,
          ),
        }),
      ),
    );
    renderSection(4, 2);
    await screen.findByRole('img', { name: /0턴부터 2턴까지/ });
    expect(lib.benchmarkSeries.setData).toHaveBeenCalledWith([
      { time: 0, value: 100000000 },
      { time: 1, value: 100512000 },
      { time: 2, value: 99870000 },
    ]);
  });

  it('never shows a turn after the one being viewed', async () => {
    serveSummaries(4);
    renderTable(4, 2);
    const table = await screen.findByRole('table');
    expect(within(table).getAllByRole('row')).toHaveLength(1 + 3);
    expect(within(table).queryByText('3턴')).toBeNull();
    expect(within(table).queryByText('4턴')).toBeNull();
  });
});
