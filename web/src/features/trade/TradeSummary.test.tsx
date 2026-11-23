import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  holdingsAllSold,
  holdingsFixture,
  holdingsWithMidfielder,
} from '../../test/fixtures/holdings.ts';
import { PNL_BASIS_NOTE, TradeSummary } from './TradeSummary.tsx';

describe('TradeSummary total unrealised P&L', () => {
  it.each([
    ['342000', '+342,000원', 'up'],
    ['-87654', '-87,654원', 'down'],
    ['0', '0원', 'flat'],
  ] as const)('shows the total %s with its sign', (total, text, direction) => {
    render(
      <TradeSummary
        holdings={{ ...holdingsWithMidfielder, unrealizedPnl: total }}
      />,
    );
    expect(screen.getByText('평가손익 (보유 합계)')).toBeInTheDocument();
    expect(screen.getByText(text)).toHaveAttribute('data-direction', direction);
    expect(screen.getByText(PNL_BASIS_NOTE)).toBeInTheDocument();
  });

  it('uses the server total even when it differs from the sum of the rows', () => {
    render(
      <TradeSummary
        holdings={{ ...holdingsWithMidfielder, unrealizedPnl: '342001' }}
      />,
    );
    expect(screen.getByText('+342,001원')).toBeInTheDocument();
  });

  it.each([
    ['turn 0 before buying', holdingsFixture()],
    ['everything sold', holdingsAllSold],
  ])('hides the total and the note with no holdings (%s)', (_, holdings) => {
    render(<TradeSummary holdings={holdings} />);
    expect(screen.queryByText('평가손익 (보유 합계)')).not.toBeInTheDocument();
    expect(screen.queryByText(PNL_BASIS_NOTE)).not.toBeInTheDocument();
  });
});
