import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { expectNoDatePattern } from '../../test/expectNoDatePattern.ts';
import { macroFixture } from '../../test/fixtures/turns.ts';
import { DIRECTION_LABELS, REGIME_ROWS } from './macroLabels.ts';
import { strengthText } from './strengthText.ts';
import { MacroPanel } from './MacroPanel.tsx';

const NO_DATA = {
  axes: { rate: null, growth: null, fx: null, sentiment: null },
  probabilities: null,
};

describe('macro labels', () => {
  it('names three directions and four regimes', () => {
    expect(DIRECTION_LABELS).toEqual({
      UP: '상승',
      DOWN: '하락',
      NEUTRAL: '중립',
    });
    expect(REGIME_ROWS.map((r) => r.key)).toEqual(['p1', 'p2', 'p3', 'p4']);
  });
});

describe('MacroPanel', () => {
  it('shows four axes with direction word and the server strength', () => {
    render(<MacroPanel macro={macroFixture()} />);
    const region = screen.getByRole('region', { name: '거시 환경' });
    const rows = within(region)
      .getAllByRole('term')
      .map((t) => [t.textContent, t.nextElementSibling?.textContent]);
    expect(rows).toEqual([
      ['금리', '상승 · 강도 0.41'],
      ['성장', '하락 · 강도 1.205'],
      ['환율', '중립 · 강도 0.05'],
      ['심리', '상승 · 강도 0.77'],
    ]);
  });

  it('shows regime probabilities as percent with bars', () => {
    const { container } = render(<MacroPanel macro={macroFixture()} />);
    expect(screen.getByRole('heading', { name: '국면 확률' })).toBeVisible();
    const items = screen.getAllByRole('listitem').map((i) => i.textContent);
    expect(items).toEqual([
      '금리 하락 · 성장 둔화10%',
      '금리 하락 · 성장 확장25%',
      '금리 상승 · 성장 확장60%',
      '금리 상승 · 성장 둔화5%',
    ]);
    const widths = Array.from(
      container.querySelectorAll<HTMLElement>('li span[aria-hidden] > span'),
    ).map((s) => s.style.width);
    expect(widths).toEqual(['10%', '25%', '60%', '5%']);
  });

  it('says no data for a null axis, never neutral', () => {
    render(
      <MacroPanel
        macro={macroFixture({
          axes: {
            rate: null,
            growth: { direction: 'UP', strength: '0.10000000' },
            fx: null,
            sentiment: null,
          },
        })}
      />,
    );
    const rate = screen.getByText('금리').nextElementSibling;
    expect(rate?.textContent).toBe('자료 없음');
    expect(screen.queryByText(/중립/)).toBeNull();
    expect(screen.getAllByText('자료 없음')).toHaveLength(3);
  });

  it('omits the regime block when probabilities are null', () => {
    render(<MacroPanel macro={macroFixture({ probabilities: null })} />);
    expect(screen.queryByRole('heading', { name: '국면 확률' })).toBeNull();
    expect(screen.queryByRole('listitem')).toBeNull();
    expect(screen.getByText('금리')).toBeInTheDocument();
  });

  it('prints one sentence when everything is null', () => {
    render(<MacroPanel macro={NO_DATA} />);
    expect(
      screen.getByText('이번 턴의 거시 환경 자료가 없습니다'),
    ).toBeInTheDocument();
    expect(screen.queryByText('자료 없음')).toBeNull();
    expect(screen.queryByRole('term')).toBeNull();
  });

  it('prints none of the keys a fixture adds beyond the contract', () => {
    const base = macroFixture();
    const extra = {
      ...base,
      label: 'P3',
      indicatorZ: { cpi: '9.87654321' },
      month: '2008-09',
      level: '5.25',
      axes: {
        ...base.axes,
        rate: {
          direction: 'UP',
          strength: '0.41000000',
          level: '5.25',
          month: '2008-09',
        },
        extraAxis: { direction: 'UP', strength: '3.33333333' },
      },
      probabilities: { ...base.probabilities, label: 'P3', p5: '0.7777' },
    } as unknown as typeof base;
    const { container } = render(<MacroPanel macro={extra} />);
    for (const leaked of [
      'P3',
      '9.87654321',
      '2008',
      '5.25',
      '3.33333333',
      '0.7777',
      'indicatorZ',
      'extraAxis',
    ]) {
      expect(container.textContent).not.toContain(leaked);
      expect(container.innerHTML).not.toContain(leaked);
    }
    expectNoDatePattern(container);
  });

  it('treats all axes null with probabilities present as data', () => {
    render(<MacroPanel macro={macroFixture({ axes: NO_DATA.axes })} />);
    expect(screen.queryByText(/거시 환경 자료가 없습니다/)).toBeNull();
    expect(screen.getAllByText('자료 없음')).toHaveLength(4);
    expect(screen.getByText('60%')).toBeInTheDocument();
  });

  it('shows no data for a missing macro, axis key or unknown direction', () => {
    const { container, rerender } = render(<MacroPanel macro={undefined} />);
    expect(
      screen.getByText('이번 턴의 거시 환경 자료가 없습니다'),
    ).toBeInTheDocument();
    const partial = {
      axes: {
        rate: { direction: 'SIDEWAYS', strength: '1.00000000' },
        growth: { direction: 'UP', strength: '2.50000000' },
      },
      probabilities: null,
    } as unknown as ReturnType<typeof macroFixture>;
    rerender(<MacroPanel macro={partial} />);
    expect(screen.getByText('금리').nextElementSibling?.textContent).toBe(
      '자료 없음',
    );
    expect(screen.getByText('성장').nextElementSibling?.textContent).toBe(
      '상승 · 강도 2.5',
    );
    expect(screen.getByText('환율').nextElementSibling?.textContent).toBe(
      '자료 없음',
    );
    expect(container.textContent).not.toContain('undefined');
    expect(container.textContent).not.toContain('SIDEWAYS');
  });

  it('trims trailing zeros as text without rounding', () => {
    expect(strengthText('0.41000000')).toBe('강도 0.41');
    expect(strengthText('5.00000000')).toBe('강도 5');
    expect(strengthText('0.00000000')).toBe('강도 0');
    expect(strengthText('10.00000100')).toBe('강도 10.000001');
    expect(strengthText('100')).toBe('강도 100');
  });
});
