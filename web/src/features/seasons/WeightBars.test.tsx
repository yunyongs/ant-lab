import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { WeightBars } from './WeightBars.tsx';
import { weightRowsOf } from './weightRows.ts';

const formation = { goalkeeper: 40, defender: 30, midfielder: 20, forward: 10 };
const source = {
  goalkeeperWeight: '0.40000000',
  defenderWeight: '0.30000000',
  midfielderWeight: '0.20000000',
  forwardWeight: '0.10000000',
};

describe('WeightBars', () => {
  it('shows four rows with labels, fills, target marks and weight text', () => {
    const { container } = render(
      <WeightBars rows={weightRowsOf(source, formation)} />,
    );
    for (const label of ['골키퍼', '수비수', '미드필더', '공격수']) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
    expect(screen.getByText('40% / 목표 40%')).toBeInTheDocument();
    expect(screen.getByText('10% / 목표 10%')).toBeInTheDocument();
    expect(screen.getByTestId('fill-defender').style.width).toBe('30%');
    const marks = container.querySelectorAll<HTMLElement>('[class*="target"]');
    expect(Array.from(marks).map((m) => m.style.left)).toEqual([
      '40%',
      '30%',
      '20%',
      '10%',
    ]);
  });

  it('adds the gap in points and the value only when they are given', () => {
    render(
      <WeightBars
        rows={weightRowsOf(
          {
            ...source,
            goalkeeperGap: '0.00000000',
            defenderGap: '-0.03100000',
            midfielderGap: '0.15880000',
            forwardGap: '0.00000001',
            goalkeeperValue: '40000000',
            defenderValue: '30000000',
            midfielderValue: '20000000',
            forwardValue: '10000000',
          },
          formation,
        )}
      />,
    );
    expect(
      screen.getByText('40% / 목표 40% (0%p) · 40,000,000원'),
    ).toBeInTheDocument();
    expect(
      screen.getByText('30% / 목표 30% (-3.1%p) · 30,000,000원'),
    ).toBeInTheDocument();
    expect(screen.getByText(/\(15.88%p\)/)).toBeInTheDocument();
  });
});
