import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { heldMidfielder } from '../../test/fixtures/holdings.ts';
import { HoldingCard } from './HoldingCard.tsx';

function renderCard(
  unrealizedPnl: string,
  unrealizedReturn: string,
  open = false,
) {
  const onToggle = vi.fn();
  render(
    <ul>
      <HoldingCard
        holding={{ ...heldMidfielder, unrealizedPnl, unrealizedReturn }}
        name="가상전자"
        open={open}
        onToggle={onToggle}
        disabled={false}
        canChangePosition={false}
        onTrade={() => undefined}
        onChangePosition={() => undefined}
      />
    </ul>,
  );
  return onToggle;
}

describe('HoldingCard unrealised P&L', () => {
  it.each([
    ['342000', '0.03994394', '+342,000원', '+3.99%', 'up'],
    ['-1250431', '-0.12345678', '-1,250,431원', '-12.35%', 'down'],
    ['0', '0.00000000', '0원', '0.00%', 'flat'],
  ] as const)(
    'shows %s and %s from the server on the closed row',
    (pnl, ratio, pnlText, ratioText, direction) => {
      renderCard(pnl, ratio);
      const toggle = screen.getByRole('button', { expanded: false });
      expect(within(toggle).getByText(pnlText)).toHaveAttribute(
        'data-direction',
        direction,
      );
      expect(within(toggle).getByText(ratioText)).toHaveAttribute(
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

describe('HoldingCard disclosure', () => {
  it('shows name, position, value and weight on the closed row only', () => {
    renderCard('342000', '0.03994394');
    const toggle = screen.getByRole('button', { expanded: false });
    expect(toggle).toHaveTextContent('가상전자 · 미드필더');
    expect(toggle).toHaveTextContent('8,904,000원');
    expect(toggle).toHaveTextContent('비중 8.90%');
    expect(screen.queryByText('평균단가')).toBeNull();
    expect(screen.queryByRole('button', { name: '가상전자 매수' })).toBeNull();
  });

  it('shows quantity, average cost, price and the actions when open', () => {
    renderCard('342000', '0.03994394', true);
    expect(screen.getByRole('button', { expanded: true })).toBeInTheDocument();
    expect(screen.getByText('수량').nextSibling).toHaveTextContent('120주');
    expect(screen.getByText('평균단가').nextSibling).toHaveTextContent(
      '71,350원',
    );
    expect(screen.getByText('현재가').nextSibling).toHaveTextContent(
      '74,200원',
    );
    expect(screen.getByRole('button', { name: '가상전자 매수' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '가상전자 매도' })).toBeEnabled();
  });

  it('asks the list to toggle when the row is pressed', async () => {
    const onToggle = renderCard('0', '0.00000000');
    await userEvent.setup().click(screen.getByRole('button'));
    expect(onToggle).toHaveBeenCalledTimes(1);
  });
});
