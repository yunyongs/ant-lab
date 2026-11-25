import { screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveTurnWorld } from '../test/serveTurns.ts';

vi.mock('lightweight-charts', () => ({
  BaselineSeries: 'Baseline',
  ColorType: { Solid: 'solid' },
  LineStyle: { Dashed: 2 },
  createOptionsChart: () => {
    throw new Error('canvas unavailable');
  },
}));

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('turn screen when the chart fails', () => {
  it('keeps the screen and the table, with a note in the value section', async () => {
    serveTurnWorld({ current: 4 });
    renderRoute('/seasons/7');
    await screen.findByRole('heading', { name: '4턴 결과' });
    const section = await screen.findByRole('region', {
      name: '총 평가액 흐름',
    });
    expect(
      await within(section).findByText(
        '그래프를 불러오지 못했습니다. 같은 숫자는 아래 표에 있습니다.',
      ),
    ).toBeInTheDocument();
    const table = within(section).getByRole('table', {
      name: '턴별 총 평가액 (그래프와 같은 숫자)',
    });
    expect(within(table).getAllByRole('row')).toHaveLength(6);
    expect(within(section).queryByRole('img')).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '5턴으로 넘기기' }),
    ).toBeInTheDocument();
    expect(screen.queryByText('문제가 발생했습니다')).not.toBeInTheDocument();
  });
});
