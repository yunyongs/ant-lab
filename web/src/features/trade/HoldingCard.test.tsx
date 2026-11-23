import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { heldMidfielder } from '../../test/fixtures/holdings.ts';
import { HoldingCard } from './HoldingCard.tsx';

function renderCard(unrealizedPnl: string, unrealizedReturn: string) {
  render(
    <ul>
      <HoldingCard
        holding={{ ...heldMidfielder, unrealizedPnl, unrealizedReturn }}
        name="가상전자"
        disabled={false}
        canChangePosition={false}
        onTrade={() => undefined}
        onChangePosition={() => undefined}
      />
    </ul>,
  );
}

function factValue(label: string) {
  const term = screen.getByText(label, { selector: 'dt' });
  const row = term.parentElement;
  if (row === null) throw new Error(`no row for ${label}`);
  return within(row).getByRole('definition');
}

describe('HoldingCard unrealised P&L', () => {
  it.each([
    ['342000', '0.03994394', '+342,000원', '+3.99%', 'up'],
    ['-1250431', '-0.12345678', '-1,250,431원', '-12.35%', 'down'],
    ['0', '0.00000000', '0원', '0.00%', 'flat'],
  ] as const)(
    'shows %s and %s from the server',
    (pnl, ratio, pnlText, ratioText, direction) => {
      renderCard(pnl, ratio);
      expect(within(factValue('평가손익')).getByText(pnlText)).toHaveAttribute(
        'data-direction',
        direction,
      );
      expect(within(factValue('수익률')).getByText(ratioText)).toHaveAttribute(
        'data-direction',
        direction,
      );
    },
  );

  it('prints the server strings, not a value derived from price and cost', () => {
    // Inconsistent on purpose: quantity × (price − avgPrice) would be 342,000.
    renderCard('341987', '0.03994242');
    expect(screen.getByText('+341,987원')).toBeInTheDocument();
    expect(screen.queryByText('+342,000원')).not.toBeInTheDocument();
  });
});
