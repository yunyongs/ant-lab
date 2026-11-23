import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { expectNoDatePattern } from '../../test/expectNoDatePattern.ts';
import { seasonFixture } from '../../test/fixtures/seasons.ts';
import { turnSummariesFixture } from '../../test/fixtures/turnSummaries.ts';
import { renderWithProviders } from '../../test/renderWithProviders.tsx';
import { server } from '../../test/server.ts';
import { formatWon } from '../format/money.ts';
import { ratioToPercent } from '../format/ratio.ts';
import { signedChange } from '../format/signedChange.ts';
import { ValueSection } from './ValueSection.tsx';

const lib = vi.hoisted(() => {
  const series = {
    setData: vi.fn(),
    createPriceLine: vi.fn(),
    applyOptions: vi.fn(),
  };
  const chart = {
    addSeries: vi.fn(() => series),
    timeScale: vi.fn(() => ({ fitContent: vi.fn() })),
    remove: vi.fn(),
  };
  return { series, chart, createOptionsChart: vi.fn(() => chart) };
});

vi.mock('lightweight-charts', () => ({
  BaselineSeries: 'Baseline',
  ColorType: { Solid: 'solid' },
  LineStyle: { Dashed: 2 },
  createOptionsChart: lib.createOptionsChart,
}));

function serveSummaries(currentTurnNo: number) {
  const body = turnSummariesFixture(currentTurnNo);
  server.use(
    http.get('*/api/v1/seasons/7/turns', () => HttpResponse.json(body)),
  );
  return body.items;
}

function renderSection(currentTurnNo: number) {
  return renderWithProviders(
    <ValueSection season={seasonFixture({ id: 7, currentTurnNo })} />,
  );
}

beforeEach(() => {
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
      name: '0턴부터 4턴까지 총 평가액 선 그래프. 같은 숫자는 아래 표에 있습니다.',
    });
    expect(chart).toBeInTheDocument();
    await waitFor(() => {
      expect(lib.createOptionsChart).toHaveBeenCalledTimes(1);
    });
    expect(lib.series.setData).toHaveBeenCalledWith([
      { time: 0, value: 100000000 },
      { time: 1, value: 103300000 },
      { time: 2, value: 101250000 },
      { time: 3, value: 104820000 },
      { time: 4, value: 97000000 },
    ]);
    // Peak 104,820,000 x (1 - 0.20) = 83,856,000, without an axis number.
    expect(lib.series.createPriceLine).toHaveBeenCalledWith(
      expect.objectContaining({
        price: 83856000,
        axisLabelVisible: false,
        title: '위험 선언 -20%',
      }),
    );
    expect(
      screen.getByText(
        '점선: 위험 선언 -20%. 지금까지 가장 높았던 총 평가액에서 그만큼 내려간 높이입니다.',
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
    renderSection(4);
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
      expect(cells[0]?.textContent).toBe(formatWon(item.totalValue));
      expect(cells[1]?.textContent).toBe(
        signedChange(item.periodReturn, 'percent').text,
      );
      expect(cells[2]?.textContent).toBe(ratioToPercent(item.drawdown));
    });
    expect(within(table).getByText('104,820,000원')).toBeInTheDocument();
    expect(within(table).getAllByText('-7.46%')).toHaveLength(2);
  });

  it('shows a short message instead of a chart for one point', async () => {
    serveSummaries(0);
    renderSection(0);
    expect(
      await screen.findByText(
        '턴을 하나 넘기면 총 평가액 그래프가 그려집니다. 지금은 아래 표만 봐 주세요.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(screen.getByRole('table')).toBeInTheDocument();
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
    renderSection(1);
    expect(
      await screen.findByText('9,007,199,254,740,993원'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('follows the up/down convention when it changes', async () => {
    serveSummaries(2);
    renderSection(2);
    await waitFor(() => {
      expect(lib.chart.addSeries).toHaveBeenCalledTimes(1);
    });
    const { setConvention, resetConventionForTests } =
      await import('../format/convention.ts');
    lib.series.applyOptions.mockClear();
    setConvention('western');
    await waitFor(() => {
      expect(lib.series.applyOptions).toHaveBeenCalledTimes(1);
    });
    resetConventionForTests();
  });

  it('asks for the list only while the section is open', () => {
    let gets = 0;
    server.use(
      http.get('*/api/v1/seasons/7/turns', () => {
        gets += 1;
        return HttpResponse.json(turnSummariesFixture(2));
      }),
    );
    renderWithProviders(
      <ValueSection
        season={seasonFixture({ id: 7, currentTurnNo: 2 })}
        collapse={{ defaultOpen: false }}
      />,
    );
    expect(
      screen.getByRole('button', { name: /총 평가액 흐름/ }),
    ).toHaveAttribute('aria-expanded', 'false');
    expect(gets).toBe(0);
  });
});
