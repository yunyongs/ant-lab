import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { problem } from '../test/authHandlers.ts';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import { feedOfTurn } from '../test/fixtures/feed.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveFeedWorld } from '../test/serveFeed.ts';

describe('feed on a past-turn page', () => {
  it('orders from an earlier item at the current turn, with that item as the source', async () => {
    const world = serveFeedWorld({ current: 5 });
    world.feed = (turnNo) => feedOfTurn(turnNo);
    const { container } = renderRoute('/seasons/7/turns/3');
    const user = userEvent.setup();
    await user.click(
      await screen.findByRole('button', { name: '종목 가 매수' }),
    );
    const dialog = await screen.findByRole('dialog', { name: '종목 가' });
    // The sheet shows this turn's price, not the earlier turn's.
    expect(within(dialog).getByText('187,400원')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    expect(world.orders[0]).toStrictEqual({
      expectedTurnNo: 5,
      side: 'BUY',
      assetId: 101,
      quantity: 1,
      declaredPosition: 'MIDFIELDER',
      source: { kind: 'FEED', feedTurnNo: 3, feedSeq: 3 },
    });
    expect(world.pricesGets.every((n) => n === 5)).toBe(true);
    expect(world.turnGets).toEqual([3]);
    expectNoDatePattern(container);
  });

  it('is read-only in a halted season', async () => {
    const world = serveFeedWorld({ current: 5 });
    world.feed = (turnNo) => feedOfTurn(turnNo);
    world.status = 'HALTED';
    renderRoute('/seasons/7/turns/3');
    const region = await screen.findByRole('region', { name: '소식' });
    expect(within(region).getAllByRole('listitem')).toHaveLength(3);
    expect(screen.queryByRole('button', { name: /매수|매도/ })).toBeNull();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(world.holdingsGets).toBe(0);
  });

  it('notes a stale screen on TURN_MISMATCH, re-reads the season, and clears the note on the next start', async () => {
    const world = serveFeedWorld({ current: 5 });
    world.feed = (turnNo) => feedOfTurn(turnNo);
    world.orderAnswer = () =>
      problem(409, { code: 'TURN_MISMATCH', status: 409 });
    renderRoute('/seasons/7/turns/3');
    const user = userEvent.setup();
    await user.click(
      await screen.findByRole('button', { name: '종목 가 매수' }),
    );
    const dialog = await screen.findByRole('dialog', { name: '종목 가' });
    const before = world.seasonGets;
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '화면의 턴이 지난 턴입니다.',
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    await waitFor(() => {
      expect(world.seasonGets).toBeGreaterThan(before);
    });
    await user.click(screen.getByRole('button', { name: '종목 가 매수' }));
    await screen.findByRole('dialog', { name: '종목 가' });
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
