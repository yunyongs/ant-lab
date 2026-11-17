import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import { rankingFixture } from '../test/fixtures/ranking.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveTurnWorld } from '../test/serveTurns.ts';

describe('ranking on a past-turn page', () => {
  it('shows that turn board with no order button', async () => {
    const world = serveTurnWorld({ current: 5 });
    world.ranking = (turnNo) => rankingFixture(turnNo);
    const { container } = renderRoute('/seasons/7/turns/3');
    const region = await screen.findByRole('region', { name: '순위' });
    await within(region).findByText('종목 나');
    expect(world.rankingGets).toEqual([3]);
    expect(screen.queryByRole('button', { name: /매수|매도/ })).toBeNull();
    // Nothing in the section acts but its four tabs.
    expect(within(region).queryAllByRole('button')).toHaveLength(0);
    expect(within(region).getAllByRole('tab')).toHaveLength(4);
    expectNoDatePattern(container);
  });

  it('shows no board for an out-of-range turn', async () => {
    const world = serveTurnWorld({ current: 5 });
    renderRoute('/seasons/7/turns/6');
    await screen.findByText('아직 진행하지 않은 턴입니다.');
    expect(screen.queryByRole('region', { name: '순위' })).toBeNull();
    expect(world.rankingGets).toEqual([]);
  });
});
