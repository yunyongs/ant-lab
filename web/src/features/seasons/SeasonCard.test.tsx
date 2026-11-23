import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { seasonFixture } from '../../test/fixtures/seasons.ts';
import { renderWithProviders } from '../../test/renderWithProviders.tsx';
import type { Season } from './labels.ts';
import { turnProgressWidth } from './labels.ts';
import { SeasonCard } from './SeasonCard.tsx';

function renderCard(season: Season) {
  return renderWithProviders(
    <ul>
      <SeasonCard season={season} />
    </ul>,
  );
}

describe('season card value and return', () => {
  it('shows a neutral not-started state at turn 0 (null values)', () => {
    renderCard(seasonFixture());
    expect(screen.getByText('시작 전')).toBeInTheDocument();
    expect(screen.getByText('평가액')).toBeInTheDocument();
    expect(document.querySelector('[data-direction]')).toBeNull();
    expect(screen.queryByText('총 수익률')).toBeNull();
    expect(screen.getByText('100,000,000원')).toBeInTheDocument();
    expect(screen.getByText('현금 잔액')).toBeInTheDocument();
  });

  it('shows the server value in whole won and a positive return', () => {
    renderCard(
      seasonFixture({
        currentTurnNo: 5,
        cashBalance: '1200000',
        latestTotalValue: '104567890',
        totalReturn: '0.04567890',
      }),
    );
    expect(screen.getByText('104,567,890원')).toBeInTheDocument();
    const change = screen.getByText('+4.57%');
    expect(change).toHaveAttribute('data-direction', 'up');
    expect(change.parentElement).toHaveTextContent('총 수익률+4.57%');
    expect(screen.queryByText('시작 전')).toBeNull();
    expect(screen.getByText('1,200,000원')).toBeInTheDocument();
  });

  it('shows a negative return with the down direction', () => {
    renderCard(
      seasonFixture({
        currentTurnNo: 3,
        latestTotalValue: '98750000',
        totalReturn: '-0.01250000',
      }),
    );
    expect(screen.getByText('98,750,000원')).toBeInTheDocument();
    expect(screen.getByText('-1.25%')).toHaveAttribute(
      'data-direction',
      'down',
    );
  });

  it('shows a zero return as flat and unsigned', () => {
    renderCard(
      seasonFixture({
        currentTurnNo: 1,
        latestTotalValue: '100000000',
        totalReturn: '0.00000000',
      }),
    );
    expect(screen.getByText('0.00%')).toHaveAttribute('data-direction', 'flat');
  });

  it('keeps the finished status label and names the final value', () => {
    renderCard(
      seasonFixture({
        status: 'FINISHED',
        currentTurnNo: 12,
        latestTotalValue: '104500000',
        totalReturn: '0.04500000',
      }),
    );
    expect(screen.getByText('끝남')).toBeInTheDocument();
    expect(screen.getByText('최종 평가액')).toBeInTheDocument();
    expect(screen.getByText('+4.50%')).toBeInTheDocument();
  });

  it('keeps the abandoned label next to the value', () => {
    renderCard(
      seasonFixture({
        status: 'FINISHED',
        currentTurnNo: 12,
        abandonedTurnNo: 5,
        latestTotalValue: '91000000',
        totalReturn: '-0.09000000',
      }),
    );
    expect(screen.getByText('끝남')).toBeInTheDocument();
    expect(screen.getByText('5턴에서 포기')).toBeInTheDocument();
    expect(screen.getByText('-9.00%')).toBeInTheDocument();
  });
});

describe('season card turn progress', () => {
  it('exposes the turn integers on an accessible progress bar', () => {
    renderCard(
      seasonFixture({
        currentTurnNo: 3,
        pathLength: 24,
        latestTotalValue: '98750000',
        totalReturn: '-0.01250000',
      }),
    );
    const bar = screen.getByRole('progressbar', { name: '현재 턴' });
    expect(bar).toHaveAttribute('aria-valuemin', '0');
    expect(bar).toHaveAttribute('aria-valuemax', '24');
    expect(bar).toHaveAttribute('aria-valuenow', '3');
    expect(bar).toHaveAttribute('aria-valuetext', '3 / 24턴');
    expect(bar).not.toHaveAttribute('aria-label');
    // Shown once on screen, read once through the bar's value text.
    expect(screen.getByText('3 / 24턴')).toHaveAttribute('aria-hidden', 'true');
    expect(bar.firstElementChild).toHaveStyle({ width: '12.5%' });
  });

  it('starts empty at turn 0 and is full at the last turn', () => {
    expect(turnProgressWidth({ currentTurnNo: 0, pathLength: 12 })).toBe('0%');
    expect(turnProgressWidth({ currentTurnNo: 12, pathLength: 12 })).toBe(
      '100%',
    );
    expect(turnProgressWidth({ currentTurnNo: 13, pathLength: 12 })).toBe(
      '100%',
    );
    expect(turnProgressWidth({ currentTurnNo: 1, pathLength: 0 })).toBe('0%');
  });
});

describe('season card league badge', () => {
  it('shows the league badge when the season entered a league', () => {
    renderCard(seasonFixture({ leagueId: 31 }));
    expect(screen.getByText('리그 31')).toBeInTheDocument();
  });

  it('shows no league badge outside a league', () => {
    renderCard(seasonFixture());
    expect(screen.queryByText(/리그/)).toBeNull();
  });
});
