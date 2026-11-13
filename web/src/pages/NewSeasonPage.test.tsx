import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../test/authHandlers.ts';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';

const startButton = () => screen.getByRole('button', { name: /^시즌 시작/ });
const share = (name: RegExp) => screen.getByRole('textbox', { name });

async function setShares(
  user: ReturnType<typeof userEvent.setup>,
  values: [string, string, string, string],
) {
  const names = [/골키퍼/, /수비수/, /미드필더/, /공격수/];
  for (const [i, name] of names.entries()) {
    const input = share(name);
    await user.clear(input);
    const value = values[i];
    if (value !== undefined && value !== '') await user.type(input, value);
  }
}

function captureStart(status = 201) {
  const seen: { bodies: unknown[] } = { bodies: [] };
  server.use(
    http.post('*/api/v1/seasons', async ({ request }) => {
      seen.bodies.push(await request.json());
      return HttpResponse.json(
        seasonFixture({ id: 21, riskLimit: '-0.30000000' }),
        {
          status,
        },
      );
    }),
  );
  return seen;
}

describe('NewSeasonPage', () => {
  it('starts with no risk limit, 25/25/25/25 and 12 turns; submit is off', () => {
    renderRoute('/seasons/new');
    for (const name of ['-10%', '-20%', '-30%', '-40%']) {
      expect(screen.getByRole('radio', { name })).not.toBeChecked();
    }
    expect(share(/골키퍼/)).toHaveValue('25');
    expect(share(/공격수/)).toHaveValue('25');
    expect(screen.getByText('합계 100 / 100')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: '12턴' })).toBeChecked();
    expect(startButton()).toBeDisabled();
  });

  it('enables submit once a risk limit is chosen', async () => {
    renderRoute('/seasons/new');
    await userEvent.setup().click(screen.getByRole('radio', { name: '-30%' }));
    expect(startButton()).toBeEnabled();
  });

  it.each([
    [['25', '25', '25', '22'], '합계가 100이 되어야 합니다', 'sum'],
    [['0', '40', '30', '30'], '골키퍼는 1 이상이어야 합니다', 'goalkeeper'],
    [['25', '1.5', '25', '25'], '0–100 사이의 정수로 적어 주세요', 'whole'],
    [['25', '', '25', '25'], '0–100 사이의 정수로 적어 주세요', 'whole'],
    [['25', '101', '25', '25'], '0–100 사이의 정수로 적어 주세요', 'whole'],
  ] as const)(
    'blocks submit for %j with a reason',
    async (values, reason, kind) => {
      const user = userEvent.setup();
      renderRoute('/seasons/new');
      await user.click(screen.getByRole('radio', { name: '-20%' }));
      await setShares(user, [...values]);
      expect(startButton()).toBeDisabled();
      expect(document.getElementById('formation-sum')).toHaveTextContent(
        reason,
      );
      if (kind === 'goalkeeper') {
        expect(share(/골키퍼/)).toHaveAttribute('aria-invalid', 'true');
      }
      if (kind === 'whole') {
        expect(share(/수비수/)).toHaveAttribute('aria-invalid', 'true');
      }
    },
  );

  it('re-enables submit after the formation is fixed', async () => {
    const user = userEvent.setup();
    renderRoute('/seasons/new');
    await user.click(screen.getByRole('radio', { name: '-20%' }));
    await setShares(user, ['25', '25', '25', '22']);
    expect(startButton()).toBeDisabled();
    expect(
      screen.getByRole('button', { name: '시즌 시작 (합 100이 되면 켜짐)' }),
    ).toBeDisabled();
    await setShares(user, ['25', '25', '25', '25']);
    expect(startButton()).toBeEnabled();
  });

  it('sends the exact request body: riskLimit as a string, integers elsewhere', async () => {
    const seen = captureStart();
    const user = userEvent.setup();
    renderRoute('/seasons/new');
    await user.click(screen.getByRole('radio', { name: '-30%' }));
    await setShares(user, ['40', '30', '20', '10']);
    await user.click(screen.getByRole('radio', { name: '24턴' }));
    await user.click(startButton());
    await waitFor(() => {
      expect(seen.bodies).toHaveLength(1);
    });
    const body = seen.bodies[0] as { riskLimit: unknown };
    expect(typeof body.riskLimit).toBe('string');
    expect(body).toEqual({
      riskLimit: '-0.30',
      formation: { goalkeeper: 40, defender: 30, midfielder: 20, forward: 10 },
      pathLength: 24,
    });
  });

  it('sends 60 for a 60-turn season', async () => {
    const seen = captureStart();
    const user = userEvent.setup();
    renderRoute('/seasons/new');
    await user.click(screen.getByRole('radio', { name: '-10%' }));
    await user.click(screen.getByRole('radio', { name: '60턴' }));
    await user.click(startButton());
    await waitFor(() => {
      expect(seen.bodies).toHaveLength(1);
    });
    expect(seen.bodies[0]).toMatchObject({
      riskLimit: '-0.10',
      pathLength: 60,
    });
  });

  it('goes to the new season page and refreshes the list afterwards', async () => {
    captureStart();
    let seasonGets = 0;
    let listGets = 0;
    server.use(
      http.get('*/api/v1/seasons/21', () => {
        seasonGets += 1;
        return HttpResponse.json(seasonFixture({ id: 21 }));
      }),
      http.get('*/api/v1/seasons', () => {
        listGets += 1;
        return HttpResponse.json({ items: [seasonFixture({ id: 21 })] });
      }),
    );
    const user = userEvent.setup();
    const { router, queryClient } = renderRoute('/seasons/new');
    await user.click(screen.getByRole('radio', { name: '-30%' }));
    await user.click(startButton());
    expect(
      await screen.findByRole('heading', { name: '시즌 21' }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/seasons/21');
    // The page renders from the seeded cache; the cache holds the response.
    expect(queryClient.getQueryData(['season', 21])).toMatchObject({ id: 21 });
    expect(seasonGets).toBeLessThanOrEqual(1);

    await user.click(screen.getByRole('link', { name: '시즌' }));
    await screen.findByRole('heading', { name: '내 시즌' });
    await waitFor(() => {
      expect(listGets).toBe(1);
    });
  });

  it.each([
    ['SEASON_LIMIT', 409],
    ['POOL_NOT_LOADED', 409],
    ['POOL_NOT_JUDGED', 409],
    ['PATH_TOO_LONG', 422],
    ['INTERNAL_ERROR', 500],
  ] as const)(
    'shows the catalog text for %s and keeps the values',
    async (code, status) => {
      server.use(
        http.post('*/api/v1/seasons', () => problem(status, { code, status })),
      );
      const user = userEvent.setup();
      renderRoute('/seasons/new');
      await user.click(screen.getByRole('radio', { name: '-40%' }));
      await setShares(user, ['40', '30', '20', '10']);
      await user.click(startButton());
      const alert = await screen.findByRole('alert');
      const text = {
        SEASON_LIMIT:
          '진행 중인 시즌은 3개까지입니다. 하나를 끝낸 뒤 시작해 주세요.',
        POOL_NOT_LOADED: '종목 풀이 아직 준비되지 않았습니다.',
        POOL_NOT_JUDGED: '종목 풀의 포지션 판정이 끝나지 않았습니다.',
        PATH_TOO_LONG:
          '이 길이의 시즌은 만들 수 없습니다. 더 짧은 시즌을 골라 주세요.',
        INTERNAL_ERROR: '서버에 문제가 생겼습니다. 잠시 뒤 다시 시도해 주세요.',
      }[code];
      expect(alert).toHaveTextContent(text);
      expect(screen.getByRole('radio', { name: '-40%' })).toBeChecked();
      expect(share(/골키퍼/)).toHaveValue('40');
      expect(startButton()).toBeEnabled();
    },
  );

  it('shows the unreachable text when the API cannot be reached', async () => {
    server.use(http.post('*/api/v1/seasons', () => HttpResponse.error()));
    const user = userEvent.setup();
    renderRoute('/seasons/new');
    await user.click(screen.getByRole('radio', { name: '-20%' }));
    await user.click(startButton());
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'API에 연결할 수 없습니다.',
    );
  });

  it('maps 400 field violations to the matching group', async () => {
    server.use(
      http.post('*/api/v1/seasons', () =>
        problem(400, {
          code: 'INVALID_ARGUMENT',
          status: 400,
          errors: [
            { field: 'formation', reason: 'FormationSum' },
            { field: 'riskLimit', reason: 'AllowedRiskLimit' },
            { field: 'pathLength', reason: 'Min' },
          ],
        }),
      ),
    );
    const user = userEvent.setup();
    renderRoute('/seasons/new');
    await user.click(screen.getByRole('radio', { name: '-20%' }));
    await user.click(startButton());
    expect(
      await screen.findByText(
        '포메이션을 확인해 주세요(합 100, 골키퍼 1 이상).',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText('위험 선언을 −10%~−40% 중에서 골라 주세요.'),
    ).toBeInTheDocument();
    expect(
      screen.getByText('시즌 길이는 12·24·60턴 중 하나입니다.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('maps a nested formation field to the formation group', async () => {
    server.use(
      http.post('*/api/v1/seasons', () =>
        problem(400, {
          code: 'INVALID_ARGUMENT',
          status: 400,
          errors: [{ field: 'formation.goalkeeper', reason: 'Min' }],
        }),
      ),
    );
    const user = userEvent.setup();
    renderRoute('/seasons/new');
    await user.click(screen.getByRole('radio', { name: '-20%' }));
    await user.click(startButton());
    expect(
      await screen.findByText(
        '포메이션을 확인해 주세요(합 100, 골키퍼 1 이상).',
      ),
    ).toBeInTheDocument();
  });

  it('falls back to the form-level message for an unknown field', async () => {
    server.use(
      http.post('*/api/v1/seasons', () =>
        problem(400, {
          code: 'INVALID_ARGUMENT',
          status: 400,
          errors: [{ field: 'somethingElse', reason: 'NotNull' }],
        }),
      ),
    );
    const user = userEvent.setup();
    renderRoute('/seasons/new');
    await user.click(screen.getByRole('radio', { name: '-20%' }));
    await user.click(startButton());
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '입력값을 다시 확인해 주세요.',
    );
  });

  it('sends one POST for a double click and disables the button meanwhile', async () => {
    let posts = 0;
    server.use(
      http.post('*/api/v1/seasons', async () => {
        posts += 1;
        await delay(60);
        return HttpResponse.json(seasonFixture({ id: 21 }), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderRoute('/seasons/new');
    await user.click(screen.getByRole('radio', { name: '-20%' }));
    await user.dblClick(startButton());
    expect(startButton()).toBeDisabled();
    await screen.findByRole('heading', { name: '시즌 21' });
    expect(posts).toBe(1);
  });

  it('refetches the list after a start even when cached data is fresh', async () => {
    captureStart();
    let listGets = 0;
    server.use(
      http.get('*/api/v1/seasons/21', () =>
        HttpResponse.json(seasonFixture({ id: 21 })),
      ),
      http.get('*/api/v1/seasons', () => {
        listGets += 1;
        return HttpResponse.json({ items: [seasonFixture({ id: 21 })] });
      }),
    );
    const user = userEvent.setup();
    const { router, queryClient } = renderRoute('/');
    await screen.findByRole('heading', { name: '시즌 21' });
    expect(listGets).toBe(1);
    // Fresh forever: only an explicit invalidation can trigger a refetch.
    queryClient.setDefaultOptions({
      queries: { retry: false, staleTime: Infinity },
    });
    await act(() => router.navigate('/seasons/new'));
    await user.click(screen.getByRole('radio', { name: '-30%' }));
    await user.click(startButton());
    await screen.findByRole('heading', { name: '시즌 21' });
    await act(() => router.navigate('/'));
    await screen.findByRole('heading', { name: '내 시즌' });
    await waitFor(() => {
      expect(listGets).toBe(2);
    });
  });

  it('renders the new season page from the seeded cache before its own GET answers', async () => {
    captureStart();
    let releaseGet: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      releaseGet = resolve;
    });
    server.use(
      http.get('*/api/v1/seasons/21', async () => {
        await held;
        return HttpResponse.json(seasonFixture({ id: 21 }));
      }),
    );
    // Keep the seeded data fresh so the page does not refetch on mount.
    const user = userEvent.setup();
    const { queryClient } = renderRoute('/seasons/new');
    queryClient.setDefaultOptions({
      queries: { retry: false, staleTime: Infinity },
    });
    await user.click(screen.getByRole('radio', { name: '-30%' }));
    await user.click(startButton());
    // The heading comes from the route; the placeholder needs season data.
    expect(
      await screen.findByText(/팀 구성 화면이 이 자리에 들어옵니다/),
    ).toBeInTheDocument();
    expect(screen.queryByText('시즌을 불러오는 중입니다…')).toBeNull();
    releaseGet();
  });

  it('lets only the in-flight ref stop a second submit before the re-render', async () => {
    let posts = 0;
    server.use(
      http.post('*/api/v1/seasons', async () => {
        posts += 1;
        await delay(60);
        return HttpResponse.json(seasonFixture({ id: 21 }), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderRoute('/seasons/new');
    await user.click(screen.getByRole('radio', { name: '-20%' }));
    const form = startButton().closest('form');
    if (!form) throw new Error('missing form');
    // Two submit events in one batch: state has not re-rendered in between.
    act(() => {
      fireEvent.submit(form);
      fireEvent.submit(form);
    });
    await screen.findByRole('heading', { name: '시즌 21' });
    expect(posts).toBe(1);
  });
});
