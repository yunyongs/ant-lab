import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import userEvent from '@testing-library/user-event';
import { feedOfTurn } from '../test/fixtures/feed.ts';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import { macroFixture } from '../test/fixtures/turns.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveTurnWorld } from '../test/serveTurns.ts';

function macroOfTurn(turnNo: number) {
  // The rate strength carries the turn number, so each page proves whose it is.
  return macroFixture({
    axes: {
      rate: { direction: 'UP', strength: `${String(turnNo)}.00000000` },
      growth: null,
      fx: null,
      sentiment: null,
    },
  });
}

describe('macro panel on turn screens', () => {
  it('shows the panel of the current turn, above the feed', async () => {
    const world = serveTurnWorld({ current: 4 });
    world.macro = macroOfTurn;
    const { container } = renderRoute('/seasons/7');
    const region = await screen.findByRole('region', { name: '거시 환경' });
    expect(within(region).getByText('상승 · 강도 4')).toBeVisible();
    expect(world.turnGets.every((n) => n <= 4)).toBe(true);
    expectNoDatePattern(container);
  });

  it('shows the panel of the past turn that was opened', async () => {
    const world = serveTurnWorld({ current: 4 });
    world.macro = macroOfTurn;
    const { container } = renderRoute('/seasons/7/turns/2');
    const region = await screen.findByRole('region', { name: '거시 환경' });
    expect(within(region).getByText('상승 · 강도 2')).toBeVisible();
    expect(world.turnGets).toEqual([2]);
    expectNoDatePattern(container);
  });

  it('shows the panel on the finished screen', async () => {
    const world = serveTurnWorld({ current: 12 });
    world.status = 'FINISHED';
    world.macro = macroOfTurn;
    const { container } = renderRoute('/seasons/7');
    const region = await screen.findByRole('region', { name: '거시 환경' });
    expect(within(region).getByText('상승 · 강도 12')).toBeVisible();
    expectNoDatePattern(container);
  });

  it('says there is no data on the default fixture', async () => {
    serveTurnWorld({ current: 4 });
    renderRoute('/seasons/7');
    expect(
      await screen.findByText('이번 턴의 거시 환경 자료가 없습니다'),
    ).toBeVisible();
  });

  it('requests no turn above the current one', async () => {
    const world = serveTurnWorld({ current: 3 });
    renderRoute('/seasons/7/turns/9');
    await screen.findByText(/아직/);
    expect(world.turnGets).toEqual([]);
  });

  it('sits below the result card and above the feed', async () => {
    const world = serveTurnWorld({ current: 4 });
    world.feed = (turnNo) => feedOfTurn(turnNo);
    world.macro = macroOfTurn;
    renderRoute('/seasons/7');
    const panel = await screen.findByRole('region', { name: '거시 환경' });
    const card = screen.getByRole('region', { name: '턴 결과' });
    const feed = await screen.findByRole('region', { name: '소식' });
    const follows = Node.DOCUMENT_POSITION_FOLLOWING;
    expect(card.compareDocumentPosition(panel) & follows).toBeTruthy();
    expect(panel.compareDocumentPosition(feed) & follows).toBeTruthy();
  });

  it('keeps the same order on a past-turn page', async () => {
    const world = serveTurnWorld({ current: 4 });
    world.feed = (turnNo) => feedOfTurn(turnNo);
    world.macro = macroOfTurn;
    renderRoute('/seasons/7/turns/2');
    const panel = await screen.findByRole('region', { name: '거시 환경' });
    const card = screen.getByRole('region', { name: '턴 결과' });
    const feed = await screen.findByRole('region', { name: '소식' });
    const follows = Node.DOCUMENT_POSITION_FOLLOWING;
    expect(card.compareDocumentPosition(panel) & follows).toBeTruthy();
    expect(panel.compareDocumentPosition(feed) & follows).toBeTruthy();
  });

  it('shows the next turn panel after an advance', async () => {
    const world = serveTurnWorld({ current: 4 });
    world.macro = macroOfTurn;
    renderRoute('/seasons/7');
    await screen.findByText('상승 · 강도 4');
    await userEvent.click(screen.getByRole('button', { name: /^다음 턴/ }));
    expect(await screen.findByText('상승 · 강도 5')).toBeVisible();
    expect(screen.queryByText('상승 · 강도 4')).toBeNull();
  });
});
