import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveTurnWorld } from '../test/serveTurns.ts';

describe('TurnPage', () => {
  it('shows one past turn read-only with links to its neighbours', async () => {
    const world = serveTurnWorld({ current: 5 });
    const { container } = renderRoute('/seasons/7/turns/3');
    expect(
      await screen.findByRole('heading', { name: '3턴 결과' }),
    ).toBeInTheDocument();
    await screen.findByText('104,820,000원');
    expect(screen.getByRole('link', { name: '← 2턴' })).toHaveAttribute(
      'href',
      '/seasons/7/turns/2',
    );
    expect(screen.getByRole('link', { name: '4턴 →' })).toHaveAttribute(
      'href',
      '/seasons/7/turns/4',
    );
    expect(screen.getByRole('link', { name: '지금 턴으로' })).toHaveAttribute(
      'href',
      '/seasons/7',
    );
    expect(screen.queryByRole('button', { name: /턴/ })).toBeNull();
    expect(world.turnGets).toEqual([3]);
    expectNoDatePattern(container);
  });

  it('has no previous link at turn 1 and no next link at the current turn', async () => {
    serveTurnWorld({ current: 5 });
    const first = renderRoute('/seasons/7/turns/1');
    await screen.findByRole('heading', { name: '1턴 결과' });
    expect(screen.queryByRole('link', { name: /←/ })).toBeNull();
    first.unmount();
    renderRoute('/seasons/7/turns/5');
    await screen.findByRole('heading', { name: '5턴 결과' });
    expect(screen.queryByRole('link', { name: /→/ })).toBeNull();
  });

  it.each(['0', '6', '99'])(
    'says the turn is not reached for %s and sends no turn request',
    async (turnNo) => {
      const world = serveTurnWorld({ current: 5 });
      renderRoute(`/seasons/7/turns/${turnNo}`);
      expect(
        await screen.findByText('아직 진행하지 않은 턴입니다.'),
      ).toBeInTheDocument();
      expect(
        screen.getByRole('link', { name: '지금 턴으로' }),
      ).toBeInTheDocument();
      expect(world.turnGets).toEqual([]);
    },
  );

  it.each(['x', '1.5', '-1', '03'])(
    'renders not-found for turn %j and sends no turn request',
    async (turnNo) => {
      const world = serveTurnWorld({ current: 5 });
      renderRoute(`/seasons/7/turns/${turnNo}`);
      expect(await screen.findByText(/찾을 수 없/)).toBeInTheDocument();
      expect(world.turnGets).toEqual([]);
    },
  );
});
