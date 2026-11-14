import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import { poisoned, turnResultFixture } from '../test/fixtures/turns.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { failWithProblem, gate, serveTurnWorld } from '../test/serveTurns.ts';
import { server } from '../test/server.ts';

const NEXT = '다음 턴 (5턴으로)';

async function openNow(current = 4) {
  const world = serveTurnWorld({ current });
  const view = renderRoute('/seasons/7');
  await screen.findByRole('heading', { name: `${String(current)}턴 결과` });
  return { world, view };
}

describe('turn advance on the now page', () => {
  it('sends only expectedTurnNo and shows the new turn from the response', async () => {
    const { world, view } = await openNow(4);
    expect(world.turnGets).toEqual([4]);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: NEXT }));
    const heading = await screen.findByRole('heading', { name: '5턴 결과' });
    expect(world.posts).toEqual([{ expectedTurnNo: 4 }]);
    expect(
      typeof (world.posts[0] as { expectedTurnNo: unknown }).expectedTurnNo,
    ).toBe('number');
    // The result came from the response: no extra GET for turn 5.
    expect(world.turnGets).toEqual([4]);
    expect(world.seasonGets).toBe(2);
    expect(heading).toHaveFocus();
    expect(screen.getByText('시즌 7 · 5/12턴 · 진행 중')).toBeInTheDocument();
    expectNoDatePattern(view.container);
  });

  it('labels the last step and the first turn', async () => {
    await openNow(11);
    expect(
      screen.getByRole('button', { name: '마지막 턴 넘기기 (12턴, 시즌 끝)' }),
    ).toBeInTheDocument();
  });

  it('sends one request for a double tap and stays pending until the season is refetched', async () => {
    const { world } = await openNow(4);
    const post = gate();
    const season = gate();
    world.postGate = post.promise;
    const button = screen.getByRole('button', { name: NEXT });
    act(() => {
      button.click();
      button.click();
    });
    const pending = await screen.findByRole('button', {
      name: '턴을 넘기는 중…',
    });
    expect(pending).toBeDisabled();
    expect(pending).toHaveAttribute('aria-busy', 'true');
    expect(
      screen.getByText(
        '주문 장부와 평가액을 맞춰 보는 중입니다. 한 번만 보냅니다.',
      ),
    ).toBeInTheDocument();
    world.seasonGate = season.promise;
    post.release();
    await waitFor(() => {
      expect(world.seasonGets).toBe(2);
    });
    // The POST has answered, the season refetch is held: still pending.
    expect(
      screen.getByRole('button', { name: '턴을 넘기는 중…' }),
    ).toBeDisabled();
    expect(screen.queryByRole('heading', { name: '5턴 결과' })).toBeNull();
    season.release();
    expect(
      await screen.findByRole('heading', { name: '5턴 결과' }),
    ).toBeInTheDocument();
    expect(world.posts).toHaveLength(1);
  });

  it('shows the finished view after the last turn', async () => {
    const { world, view } = await openNow(11);
    const user = userEvent.setup();
    await user.click(
      screen.getByRole('button', { name: '마지막 턴 넘기기 (12턴, 시즌 끝)' }),
    );
    expect(await screen.findByText('시즌이 끝났습니다')).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: '12턴 결과 (마지막)' }),
    ).toHaveFocus();
    expect(screen.queryByRole('button', { name: /턴/ })).toBeNull();
    expect(world.status).toBe('FINISHED');
    expectNoDatePattern(view.container);
  });

  it('keeps the same turn on RECONCILIATION_FAILED and retries only on request', async () => {
    const { world } = await openNow(4);
    world.failWith = failWithProblem(409, 'RECONCILIATION_FAILED');
    const user = userEvent.setup();
    const seasonBefore = world.seasonGets;
    const turnsBefore = world.turnGets.length;
    await user.click(screen.getByRole('button', { name: NEXT }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(
      '턴을 넘기는 중 계좌 검산이 맞지 않아 아무것도 바꾸지 않았습니다. 지금 턴 그대로입니다.',
    );
    expect(alert).toHaveFocus();
    expect(
      screen.getByRole('heading', { name: '4턴 결과' }),
    ).toBeInTheDocument();
    expect(world.seasonGets).toBe(seasonBefore);
    expect(world.turnGets.length).toBe(turnsBefore);
    expect(world.posts).toHaveLength(1);

    world.failWith = null;
    await user.click(within(alert).getByRole('button', { name: '다시 시도' }));
    expect(
      await screen.findByRole('heading', { name: '5턴 결과' }),
    ).toBeInTheDocument();
    expect(world.posts).toEqual([{ expectedTurnNo: 4 }, { expectedTurnNo: 4 }]);
  });

  it('follows the server after TURN_MISMATCH', async () => {
    const { world } = await openNow(4);
    world.failWith = () => {
      world.current = 5;
      return failWithProblem(409, 'TURN_MISMATCH')();
    };
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: NEXT }));
    expect(
      await screen.findByRole('heading', { name: '5턴 결과' }),
    ).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '화면의 턴이 지난 턴입니다.',
    );
    expect(world.posts).toHaveLength(1);
  });

  it('shows an advance-specific text for SEASON_NOT_ACTIVE and follows a finished season', async () => {
    const { world } = await openNow(11);
    world.failWith = () => {
      world.current = 12;
      world.status = 'FINISHED';
      return failWithProblem(409, 'SEASON_NOT_ACTIVE')();
    };
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /마지막 턴 넘기기/ }));
    expect(await screen.findByText('시즌이 끝났습니다')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent(
      '진행 중인 시즌이 아니어서 턴을 넘길 수 없습니다.',
    );
  });

  it('reloads the season after an unreachable answer', async () => {
    const { world } = await openNow(4);
    server.use(
      http.post('*/api/v1/seasons/7/turns', () => {
        world.posts.push({ expectedTurnNo: 4 });
        return HttpResponse.error();
      }),
    );
    const before = world.seasonGets;
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: NEXT }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '결과를 확인하지 못했습니다. 서버의 현재 상태를 다시 불러왔습니다.',
    );
    await waitFor(() => {
      expect(world.seasonGets).toBeGreaterThan(before);
    });
    expect(world.posts).toHaveLength(1);
  });

  it('shows a definite failure without refetching', async () => {
    const { world } = await openNow(4);
    world.failWith = failWithProblem(500, 'INTERNAL_ERROR');
    const before = world.seasonGets;
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: NEXT }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '서버에 문제가 생겼습니다.',
    );
    expect(world.seasonGets).toBe(before);
    expect(world.posts).toHaveLength(1);
  });

  it('refreshes the season even when the page is left while the advance is pending', async () => {
    const { world, view } = await openNow(4);
    const post = gate();
    world.postGate = post.promise;
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: NEXT }));
    await waitFor(() => {
      expect(world.posts).toHaveLength(1);
    });
    await act(async () => {
      await view.router.navigate('/');
    });
    post.release();
    await waitFor(() => {
      expect(view.queryClient.getQueryState(['season', 7])?.isInvalidated).toBe(
        true,
      );
    });
    expect(view.queryClient.getQueryData(['turn', 7, 5])).toBeDefined();
  });

  it('shows nothing from a payload that carries a date, block or seed', async () => {
    serveTurnWorld({ current: 4 });
    server.use(
      http.get('*/api/v1/seasons/7/turns/4', () =>
        HttpResponse.json(poisoned(turnResultFixture({ turnNo: 4 }))),
      ),
    );
    const { container } = renderRoute('/seasons/7');
    await screen.findByRole('heading', { name: '4턴 결과' });
    await screen.findByText('104,820,000원');
    expectNoDatePattern(container);
    for (const leaked of ['2008-09', '987654', 'SEED-XYZ']) {
      expect(container.innerHTML).not.toContain(leaked);
    }
  });
});

describe('restoring the now page', () => {
  it('loads the current turn once and shows an error with retry', async () => {
    serveTurnWorld({ current: 5 });
    let calls = 0;
    server.use(
      http.get('*/api/v1/seasons/7/turns/5', () => {
        calls += 1;
        return calls === 1
          ? HttpResponse.json(
              { code: 'INTERNAL_ERROR', status: 500 },
              {
                status: 500,
                headers: { 'content-type': 'application/problem+json' },
              },
            )
          : HttpResponse.json(turnResultFixture({ turnNo: 5 }));
      }),
    );
    const user = userEvent.setup();
    renderRoute('/seasons/7');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '서버에 문제가 생겼습니다.',
    );
    await user.click(screen.getByRole('button', { name: '다시 불러오기' }));
    expect(await screen.findByText('104,820,000원')).toBeInTheDocument();
    expect(calls).toBe(2);
  });

  it('shows a link to the previous turn from turn 2', async () => {
    serveTurnWorld({ current: 5 });
    renderRoute('/seasons/7');
    const link = await screen.findByRole('link', { name: '지난 턴 보기' });
    expect(link).toHaveAttribute('href', '/seasons/7/turns/4');
  });
});
