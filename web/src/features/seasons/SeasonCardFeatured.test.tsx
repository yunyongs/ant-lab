import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { expectNoDatePattern } from '../../test/expectNoDatePattern.ts';
import { seasonFixture } from '../../test/fixtures/seasons.ts';
import { problem } from '../../test/authHandlers.ts';
import { renderWithProviders } from '../../test/renderWithProviders.tsx';
import { server } from '../../test/server.ts';
import { settle } from '../../test/settle.ts';
import type { Season } from './labels.ts';
import { SeasonCardFeatured } from './SeasonCardFeatured.tsx';

function renderCard(season: Season) {
  return renderWithProviders(
    <ul>
      <SeasonCardFeatured season={season} />
    </ul>,
  );
}

const played = {
  currentTurnNo: 3,
  cashBalance: '42670',
  latestTotalValue: '118451663',
  totalReturn: '0.18451663',
} as const;

describe('featured season card', () => {
  it('shows the value, the exact gain and the share, and the way back in', async () => {
    const { container, queryClient } = renderCard(seasonFixture(played));
    const card = screen.getByRole('article', { name: '진행 중인 시즌 7' });
    expect(within(card).getByText('118,451,663원')).toBeInTheDocument();
    const gain = within(card).getByText('+18,451,663원');
    expect(gain).toHaveAttribute('data-direction', 'up');
    expect(within(card).getByText('(+18.45%)')).toHaveAttribute(
      'data-direction',
      'up',
    );
    expect(within(card).getByText('42,670원')).toBeInTheDocument();
    expect(within(card).getByText('-20.00%')).toBeInTheDocument();
    expect(
      within(card).getByRole('link', { name: '4턴으로 이어하기' }),
    ).toHaveAttribute('href', '/seasons/7');
    await settle(queryClient);
    expectNoDatePattern(container);
  });

  it('shows a loss with the down direction', async () => {
    const { queryClient } = renderCard(
      seasonFixture({
        ...played,
        latestTotalValue: '98750000',
        totalReturn: '-0.01250000',
      }),
    );
    expect(screen.getByText('-1,250,000원')).toHaveAttribute(
      'data-direction',
      'down',
    );
    expect(screen.getByText('(-1.25%)')).toHaveAttribute(
      'data-direction',
      'down',
    );
    await settle(queryClient);
  });

  it('hides the gain line when the pair is not a number', async () => {
    const { queryClient } = renderCard(
      seasonFixture({ ...played, latestTotalValue: 'abc' }),
    );
    expect(screen.queryByText(/원 *$/, { selector: '[data-direction]' })).toBe(
      null,
    );
    expect(screen.queryByText(/^\(/)).toBeNull();
    await settle(queryClient);
  });

  it('shows one segment per turn up to 24 turns with the progress bar semantics', async () => {
    const { queryClient } = renderCard(seasonFixture(played));
    const bar = screen.getByRole('progressbar', { name: '현재 턴' });
    expect(bar).toHaveAttribute('aria-valuemin', '0');
    expect(bar).toHaveAttribute('aria-valuemax', '12');
    expect(bar).toHaveAttribute('aria-valuenow', '3');
    expect(bar).toHaveAttribute('aria-valuetext', '3 / 12턴');
    expect(bar.children).toHaveLength(12);
    const done = Array.from(bar.children).filter((c) =>
      /segmentDone/.test(c.className),
    );
    expect(done).toHaveLength(3);
    await settle(queryClient);
  });

  it('falls back to the bar above 24 turns', async () => {
    const { queryClient } = renderCard(
      seasonFixture({ ...played, pathLength: 30, currentTurnNo: 3 }),
    );
    const bar = screen.getByRole('progressbar', { name: '현재 턴' });
    expect(bar.children).toHaveLength(1);
    expect(bar.firstElementChild).toHaveStyle({ width: '10%' });
    expect(bar).toHaveAttribute('aria-valuetext', '3 / 30턴');
    await settle(queryClient);
  });

  it('shows 기준 대비 from the turn contribution after turn 0', async () => {
    renderCard(seasonFixture(played));
    expect(await screen.findByText('기준 대비')).toBeInTheDocument();
    expect(screen.getByText('+2.02%')).toBeInTheDocument();
  });

  it('omits 기준 대비 when the contribution fails', async () => {
    server.use(
      http.get('*/api/v1/seasons/:seasonId/turns/:turnNo/contribution', () =>
        problem(500, { code: 'INTERNAL_ERROR', status: 500 }),
      ),
    );
    const { queryClient } = renderCard(seasonFixture(played));
    await settle(queryClient);
    expect(screen.queryByText('기준 대비')).toBeNull();
    expect(screen.getByText('위험 선언')).toBeInTheDocument();
    expect(screen.getByText('현금')).toBeInTheDocument();
  });

  it('at turn 0 asks for no contribution and keeps the team-build entry', async () => {
    let calls = 0;
    server.use(
      http.get('*/api/v1/seasons/:seasonId/turns/:turnNo/contribution', () => {
        calls += 1;
        return HttpResponse.error();
      }),
    );
    const { queryClient } = renderCard(seasonFixture());
    await settle(queryClient);
    expect(calls).toBe(0);
    expect(screen.getByText('시작 전')).toBeInTheDocument();
    expect(screen.queryByText('기준 대비')).toBeNull();
    expect(
      screen.getByRole('link', { name: '팀 구성 이어하기' }),
    ).toBeInTheDocument();
  });
});
