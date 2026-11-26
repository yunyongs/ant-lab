import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { expectNoDatePattern } from '../../test/expectNoDatePattern.ts';
import { renderRoute } from '../../test/renderWithProviders.tsx';
import { serveTurnWorld } from '../../test/serveTurns.ts';

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
  return { series, chart };
});

vi.mock('lightweight-charts', () => {
  const { chart } = lib;
  return {
    BaselineSeries: 'Baseline',
    LineSeries: 'Line',
    ColorType: { Solid: 'solid' },
    LineStyle: { Dashed: 2 },
    createOptionsChart: vi.fn(() => chart),
  };
});

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
});

const tabNames = () => screen.getAllByRole('tab').map((tab) => tab.textContent);

describe('turn tabs', () => {
  it('opens on the chart with one panel mounted and the arrow keys moving on', async () => {
    serveTurnWorld({ current: 4 });
    const { container } = renderRoute('/seasons/7');
    await screen.findByRole('heading', { name: '4턴 결과' });
    const list = await screen.findByRole('tablist', { name: '4턴 자세히' });
    expect(tabNames()).toEqual(['그래프', '기여', '표']);
    const chart = within(list).getByRole('tab', { name: '그래프' });
    expect(chart).toHaveAttribute('aria-selected', 'true');
    expect(chart).toHaveAttribute('tabindex', '0');
    expect(within(list).getByRole('tab', { name: '기여' })).toHaveAttribute(
      'tabindex',
      '-1',
    );
    expect(screen.getAllByRole('tabpanel')).toHaveLength(1);
    expect(screen.getByRole('tabpanel')).toHaveAccessibleName('그래프');
    await screen.findByRole('img', {
      name: /총 평가액과 시장 기준선 선 그래프/,
    });

    const user = userEvent.setup();
    chart.focus();
    await user.keyboard('{ArrowRight}');
    expect(within(list).getByRole('tab', { name: '기여' })).toHaveFocus();
    expect(screen.getAllByRole('tabpanel')).toHaveLength(1);
    expect(screen.getByRole('tabpanel')).toHaveAccessibleName('기여');
    await user.keyboard('{End}');
    expect(within(list).getByRole('tab', { name: '표' })).toHaveFocus();
    await user.keyboard('{ArrowRight}');
    expect(within(list).getByRole('tab', { name: '그래프' })).toHaveFocus();
    await user.keyboard('{ArrowLeft}');
    expect(within(list).getByRole('tab', { name: '표' })).toHaveFocus();
    await user.keyboard('{Home}');
    expect(within(list).getByRole('tab', { name: '그래프' })).toHaveFocus();
    expectNoDatePattern(container);
  });

  it('wires every tab to the one shared panel on each switch', async () => {
    serveTurnWorld({ current: 4 });
    renderRoute('/seasons/7');
    const list = await screen.findByRole('tablist', { name: '4턴 자세히' });
    const user = userEvent.setup();
    for (const name of ['그래프', '기여', '표', '그래프']) {
      const tab = within(list).getByRole('tab', { name });
      await user.click(tab);
      const panel = screen.getByRole('tabpanel');
      const tabs = within(list).getAllByRole('tab');
      for (const each of tabs) {
        expect(each.getAttribute('aria-controls')).toBe(panel.id);
      }
      expect(panel.id).not.toBe('');
      expect(document.getElementById(panel.id)).toBe(panel);
      expect(panel.getAttribute('aria-labelledby')).toBe(tab.id);
      expect(tabs.filter((each) => each.tabIndex === 0)).toHaveLength(1);
      expect(tab.tabIndex).toBe(0);
    }
  });

  it('keeps cash, holdings value, weights and deviation in the table tab', async () => {
    serveTurnWorld({ current: 4 });
    renderRoute('/seasons/7');
    await userEvent
      .setup()
      .click(await screen.findByRole('tab', { name: '표' }));
    const panel = await screen.findByRole('tabpanel', { name: '표' });
    await within(panel).findByRole('table');
    expect(within(panel).getByText('12,000,000원')).toBeInTheDocument();
    expect(within(panel).getByText('92,820,000원')).toBeInTheDocument();
    expect(
      within(panel).getByRole('region', { name: '포지션 비중' }),
    ).toBeInTheDocument();
    expect(within(panel).getByText('59.86%p')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '턴 결과' })).toBeNull();
  });

  it('shows a past turn with the same tabs, no action bar and no later turn', async () => {
    serveTurnWorld({ current: 5 });
    renderRoute('/seasons/7/turns/2');
    await screen.findByRole('heading', { name: '2턴 결과' });
    expect(
      await screen.findByRole('region', { name: '2턴 요약' }),
    ).toBeInTheDocument();
    await userEvent
      .setup()
      .click(await screen.findByRole('tab', { name: '표' }));
    const panel = await screen.findByRole('tabpanel', { name: '표' });
    const table = await within(panel).findByRole('table');
    // Turn 0, 1 and 2 only.
    expect(within(table).getAllByRole('row')).toHaveLength(1 + 3);
    expect(screen.queryByRole('group', { name: '턴 행동' })).toBeNull();
  });

  it('draws no point after the viewed turn in the past chart', async () => {
    serveTurnWorld({ current: 5 });
    renderRoute('/seasons/7/turns/2');
    await screen.findByRole('img', { name: /0턴부터 2턴까지/ });
    const series = lib.series;
    await waitFor(() => {
      expect(series.setData).toHaveBeenCalled();
    });
    const points = series.setData.mock.calls.flatMap(
      (call) => call[0] as { time: number; value: number }[],
    );
    expect(points.length).toBeGreaterThan(0);
    expect(Math.max(...points.map((point) => point.time))).toBe(2);
    // Guide from the peak up to turn 2 (103,300,000 x 0.8), not a later peak.
    expect(series.createPriceLine).toHaveBeenCalledWith(
      expect.objectContaining({ price: 82640000 }),
    );
  });
});
