import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { expectNoDatePattern } from '../../test/expectNoDatePattern.ts';
import {
  poisoned,
  turnBreached,
  turnResultFixture,
} from '../../test/fixtures/turns.ts';
import { TurnResultCard } from './TurnResultCard.tsx';

describe('TurnResultCard', () => {
  it('prints every server string verbatim', () => {
    render(<TurnResultCard turn={turnResultFixture()} />);
    expect(screen.getByText('104,820,000원')).toBeInTheDocument();
    expect(screen.getByText('1,520,000원')).toBeInTheDocument();
    expect(screen.getByText('0.01471442 (1.471442%)')).toBeInTheDocument();
    expect(screen.getByText('12,000,000원')).toBeInTheDocument();
    expect(screen.getByText('92,820,000원')).toBeInTheDocument();
    const risk = screen.getByRole('region', { name: '위험 한도' });
    expect(within(risk).getByText('선언 안')).toBeInTheDocument();
    expect(within(risk).getByText('-0.04120000 (-4.12%)')).toBeInTheDocument();
    expect(within(risk).getByText('-0.20000000 (-20%)')).toBeInTheDocument();
    expect(within(risk).getByText('0.15880000 (15.88%p)')).toBeInTheDocument();
    expect(risk).toHaveAttribute('data-breached', 'false');
    expect(screen.getByText('0.59864000')).toBeInTheDocument();
    // The weights add to 0.99999998 and are printed as sent.
    expect(
      screen.getByText('21.771998% / 목표 10% (11.771998%p) · 22,820,000원'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/위험 선언을 넘었습니다/)).toBeNull();
  });

  it('marks a breached turn by text, not by colour alone', () => {
    render(<TurnResultCard turn={turnBreached} />);
    const risk = screen.getByRole('region', { name: '위험 한도' });
    expect(within(risk).getByText('초과')).toBeInTheDocument();
    expect(risk).toHaveAttribute('data-breached', 'true');
    expect(within(risk).getByText('-0.02000000 (-2%p)')).toBeInTheDocument();
    expect(screen.getByText('위험 선언을 넘었습니다')).toBeInTheDocument();
    expect(
      screen.getByText(
        '시즌은 계속됩니다. 넘은 턴 수와 깊이는 성적표에 기록됩니다.',
      ),
    ).toBeInTheDocument();
  });

  it('does not say the season continues once it is finished', () => {
    render(
      <TurnResultCard
        turn={turnResultFixture({
          status: 'FINISHED',
          riskBreached: true,
          riskHeadroom: '-0.02000000',
        })}
      />,
    );
    expect(screen.getByText('위험 선언을 넘었습니다')).toBeInTheDocument();
    expect(
      screen.getByText('넘은 턴 수와 깊이는 성적표에 기록됩니다.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/시즌은 계속됩니다/)).toBeNull();
  });

  it('prints server ratios even when they disagree with the totals', () => {
    // 1,520,000 / 103,300,000 is about 0.0147; a client recompute would show that.
    render(
      <TurnResultCard
        turn={turnResultFixture({
          periodReturn: '0.12345678',
          drawdown: '-0.31000000',
          riskHeadroom: '0.07654321',
        })}
      />,
    );
    expect(screen.getByText('0.12345678 (12.345678%)')).toBeInTheDocument();
    expect(screen.getByText('-0.31000000 (-31%)')).toBeInTheDocument();
    expect(screen.getByText('0.07654321 (7.654321%p)')).toBeInTheDocument();
    expect(screen.queryByText(/1\.471442/)).toBeNull();
  });

  it('shows no date, month block or seed even if the payload carries them', () => {
    const { container } = render(
      <TurnResultCard turn={poisoned(turnBreached)} />,
    );
    expectNoDatePattern(container);
    for (const leaked of ['2008-09', '987654', 'SEED-XYZ']) {
      expect(container.innerHTML).not.toContain(leaked);
    }
  });
});
