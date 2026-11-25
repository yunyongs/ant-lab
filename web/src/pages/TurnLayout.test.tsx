import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { alertsOfTurn, feedOfTurn } from '../test/fixtures/feed.ts';
import { rankingFixture } from '../test/fixtures/ranking.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { gate, serveTurnWorld } from '../test/serveTurns.ts';

const NEXT = '5턴으로 넘기기';

async function openNow() {
  const world = serveTurnWorld({ current: 4 });
  world.feed = (turnNo) => feedOfTurn(turnNo);
  world.ranking = (turnNo) => rankingFixture(turnNo);
  renderRoute('/seasons/7');
  await screen.findByRole('heading', { name: '4턴 결과' });
  return world;
}

describe('turn screen layout', () => {
  it('opens with the summary right after the heading, before the details', async () => {
    await openNow();
    const summary = await screen.findByRole('region', { name: '4턴 요약' });
    const heading = screen.getByRole('heading', { name: '4턴 결과' });
    const detail = screen.getByRole('region', { name: '턴 결과' });
    expect(
      heading.compareDocumentPosition(summary) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      summary.compareDocumentPosition(detail) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(within(summary).getByText(/누적 낙폭/)).toBeInTheDocument();
    expect(within(summary).getByText(/위험 선언/)).toBeInTheDocument();
  });

  it('keeps the advance and trade actions in one group after all content', async () => {
    await openNow();
    const bar = await screen.findByRole('group', { name: '턴 행동' });
    const advance = within(bar).getByRole('button', { name: NEXT });
    const trade = within(bar).getByRole('link', { name: '매매' });
    expect(trade).toHaveAttribute('href', '/seasons/7/trade');
    // Focus order: trade (left) before advance (right), both after the ranking toggle.
    const rankingToggle = screen.getByRole('button', { name: /^순위/ });
    expect(
      rankingToggle.compareDocumentPosition(trade) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      trade.compareDocumentPosition(advance) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    // Tab walks the real order: ranking toggle, the links, then the bar.
    const user = userEvent.setup();
    rankingToggle.focus();
    await user.tab();
    expect(screen.getByRole('link', { name: '지난 턴 보기' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: '예상 분포 보기' })).toHaveFocus();
    await user.tab();
    expect(trade).toHaveFocus();
    await user.tab();
    expect(advance).toHaveFocus();
  });

  it('shows the feed open and the ranking collapsed, and toggles both', async () => {
    const world = await openNow();
    const user = userEvent.setup();
    const feedToggle = await screen.findByRole('button', { name: /^소식/ });
    const rankingToggle = screen.getByRole('button', { name: /^순위/ });
    expect(feedToggle).toHaveAttribute('aria-expanded', 'true');
    expect(rankingToggle).toHaveAttribute('aria-expanded', 'false');
    // A collapsed board is not requested.
    expect(world.rankingGets).toEqual([]);
    expect(screen.queryByRole('tablist')).toBeNull();
    await user.click(rankingToggle);
    expect(rankingToggle).toHaveAttribute('aria-expanded', 'true');
    expect(await screen.findByRole('tablist')).toBeInTheDocument();
    await waitFor(() => {
      expect(world.rankingGets).toEqual([4]);
    });
    await user.click(rankingToggle);
    expect(screen.queryByRole('tablist')).toBeNull();
    await user.click(feedToggle);
    expect(feedToggle).toHaveAttribute('aria-expanded', 'false');
    expect(
      document.getElementById(feedToggle.getAttribute('aria-controls') ?? ''),
    ).toBeNull();
  });

  it('keeps alerts open without a toggle', async () => {
    const world = serveTurnWorld({ current: 4 });
    world.alerts = (turnNo) => alertsOfTurn(turnNo);
    renderRoute('/seasons/7');
    await screen.findByRole('heading', { name: '4턴 결과' });
    expect(
      await screen.findByRole('region', { name: '알림' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^알림/ })).toBeNull();
  });

  it('sends one advance request for a double tap on the bar button', async () => {
    const world = await openNow();
    const post = gate();
    world.postGate = post.promise;
    const bar = await screen.findByRole('group', { name: '턴 행동' });
    const button = within(bar).getByRole('button', { name: NEXT });
    act(() => {
      button.click();
      button.click();
    });
    await screen.findByRole('button', { name: '턴을 넘기는 중…' });
    post.release();
    await screen.findByRole('heading', { name: '5턴 결과' });
    expect(world.posts).toEqual([{ expectedTurnNo: 4 }]);
  });

  it('shows no action bar when the season is not in progress', async () => {
    const world = serveTurnWorld({ current: 4 });
    world.status = 'HALTED';
    renderRoute('/seasons/7');
    await screen.findByRole('heading', { name: '4턴 결과' });
    expect(screen.queryByRole('group', { name: '턴 행동' })).toBeNull();
  });

  describe('guard after an advance', () => {
    afterEach(() => {
      vi.useRealTimers();
    });

    it('ignores a re-tap on the next turn button for 800 ms, then sends exactly one more POST', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const world = await openNow();
      const bar = await screen.findByRole('group', { name: '턴 행동' });
      const button = within(bar).getByRole('button', { name: NEXT });
      act(() => {
        button.click();
        button.click();
      });
      await screen.findByRole('heading', { name: '5턴 결과' });
      expect(world.posts).toEqual([{ expectedTurnNo: 4 }]);
      const next = await screen.findByRole('button', {
        name: '6턴으로 넘기기',
      });
      // A tap inside the window reaches no request.
      expect(next).toBeDisabled();
      act(() => {
        next.click();
      });
      expect(world.posts).toHaveLength(1);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(900);
      });
      expect(
        screen.getByRole('button', { name: '6턴으로 넘기기' }),
      ).toBeEnabled();
      await userEvent
        .setup({ advanceTimers: vi.advanceTimersByTime })
        .click(screen.getByRole('button', { name: '6턴으로 넘기기' }));
      await screen.findByRole('heading', { name: '6턴 결과' });
      expect(world.posts).toEqual([
        { expectedTurnNo: 4 },
        { expectedTurnNo: 5 },
      ]);
    });
  });
});
