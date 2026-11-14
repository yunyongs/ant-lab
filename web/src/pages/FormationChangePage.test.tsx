import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { invalidateSeasonState } from '../features/seasons/queries.ts';
import { problem } from '../test/authHandlers.ts';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import { formationChangeFixture } from '../test/fixtures/formationChanges.ts';
import { holdingsWithMidfielder } from '../test/fixtures/holdings.ts';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';

const POST = '*/api/v1/seasons/7/formation-changes';
const NOW = { goalkeeper: 40, defender: 30, midfielder: 20, forward: 10 };
const NEXT = { goalkeeper: 30, defender: 30, midfielder: 25, forward: 15 };

interface World {
  turn: number;
  status: 'IN_PROGRESS' | 'FINISHED' | 'ABANDONING';
  formation: typeof NOW;
  bodies: unknown[];
  seasonGets: number;
  holdingsGets: number;
  // The season answer after the POST; the POST itself answers with `answer`.
  answer: (body: Record<string, unknown>) => Response | Promise<Response>;
}

function serveWorld(start: Partial<World> = {}): World {
  const world: World = {
    turn: 4,
    status: 'IN_PROGRESS',
    formation: NOW,
    bodies: [],
    seasonGets: 0,
    holdingsGets: 0,
    answer: (body) => {
      world.formation = body.formation as typeof NOW;
      return HttpResponse.json(
        formationChangeFixture({
          turnNo: world.turn,
          before: NOW,
          after: body.formation as typeof NOW,
          reason: typeof body.reason === 'string' ? body.reason : null,
        }),
        { status: 201 },
      );
    },
    ...start,
  };
  server.use(
    http.get('*/api/v1/seasons/7', () => {
      world.seasonGets += 1;
      return HttpResponse.json(
        seasonFixture({
          id: 7,
          currentTurnNo: world.turn,
          status: world.status,
          currentFormation: world.formation,
        }),
      );
    }),
    http.get('*/api/v1/seasons/7/holdings', () => {
      world.holdingsGets += 1;
      return HttpResponse.json(holdingsWithMidfielder);
    }),
    http.post(POST, async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      world.bodies.push(body);
      return world.answer(body);
    }),
  );
  return world;
}

async function openForm() {
  const user = userEvent.setup();
  const rendered = renderRoute('/seasons/7/formation');
  await screen.findByRole('button', { name: '전술 변경 적용' });
  return { user, ...rendered };
}

async function type(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
  text: string,
) {
  const input = screen.getByLabelText(label);
  await user.clear(input);
  if (text !== '') await user.type(input, text);
}

async function fillNext(user: ReturnType<typeof userEvent.setup>) {
  await type(user, '골키퍼', '30');
  await type(user, '미드필더', '25');
  await type(user, '공격수', '15');
}

const submit = () => screen.getByRole('button', { name: '전술 변경 적용' });

describe('FormationChangePage', () => {
  it('starts from the current formation and blocks the same one', async () => {
    serveWorld();
    await openForm();
    expect(
      screen.getByRole('heading', { name: '4턴 전술 변경' }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('골키퍼')).toHaveValue('40');
    expect(screen.getByLabelText('수비수')).toHaveValue('30');
    expect(screen.getByLabelText('미드필더')).toHaveValue('20');
    expect(screen.getByLabelText('공격수')).toHaveValue('10');
    expect(screen.getByText('지금 40')).toBeInTheDocument();
    expect(screen.getByText('합계 100 / 100')).toBeInTheDocument();
    expect(submit()).toBeDisabled();
    expect(screen.getByText('지금과 같은 포메이션입니다')).toBeVisible();
  });

  it('checks sum, goalkeeper and whole numbers before sending', async () => {
    const world = serveWorld();
    const { user } = await openForm();
    await type(user, '골키퍼', '37');
    expect(screen.getByText(/합계 97 \/ 100/)).toBeVisible();
    expect(screen.getByText(/합계가 100이 되어야 합니다/)).toBeVisible();
    expect(submit()).toBeDisabled();
    expect(screen.getByLabelText('골키퍼')).toHaveAttribute(
      'aria-invalid',
      'true',
    );

    await type(user, '골키퍼', '0');
    await type(user, '수비수', '40');
    await type(user, '미드필더', '30');
    await type(user, '공격수', '30');
    expect(screen.getByText(/골키퍼는 1 이상이어야 합니다/)).toBeVisible();
    expect(submit()).toBeDisabled();

    await type(user, '골키퍼', '1.5');
    expect(screen.getByText(/0–100 사이의 정수로 적어 주세요/)).toBeVisible();
    expect(submit()).toBeDisabled();

    await type(user, '골키퍼', '10');
    await type(user, '수비수', '30');
    await type(user, '미드필더', '30');
    await type(user, '공격수', '30');
    expect(screen.getByText('합계 100 / 100')).toBeVisible();
    expect(submit()).toBeEnabled();
    expect(world.bodies).toHaveLength(0);
  });

  it('counts the reason in characters, not UTF-16 units', async () => {
    serveWorld();
    const { user } = await openForm();
    await fillNext(user);
    const reason = screen.getByLabelText('사유 (선택, 한 줄)');
    await user.click(reason);
    await user.paste('가'.repeat(200));
    expect(screen.getByText(/200 \/ 200자/)).toBeVisible();
    expect(submit()).toBeEnabled();

    await user.paste('나');
    expect(screen.getByText(/201 \/ 200자/)).toBeVisible();
    expect(submit()).toBeDisabled();
    expect(screen.getByText('사유는 200자까지 적을 수 있습니다')).toBeVisible();
    expect(reason).toHaveAttribute('aria-invalid', 'true');

    // 200 emoji are 400 UTF-16 units but 200 characters.
    await user.clear(reason);
    await user.paste('😀'.repeat(200));
    expect(screen.getByText(/200 \/ 200자/)).toBeVisible();
    expect(submit()).toBeEnabled();
  });

  it('sends the exact body, leaves a blank reason out and keeps typed text', async () => {
    const world = serveWorld();
    const { user } = await openForm();
    await fillNext(user);
    const reason = screen.getByLabelText('사유 (선택, 한 줄)');
    await user.click(reason);
    await user.paste('   ');
    await user.click(submit());
    await screen.findByText('4턴 전술을 바꿨습니다');
    expect(world.bodies[0]).toEqual({ expectedTurnNo: 4, formation: NEXT });
    expect('reason' in (world.bodies[0] as object)).toBe(false);

    await user.click(screen.getByRole('button', { name: '한 번 더 바꾸기' }));
    await type(user, '골키퍼', '20');
    await type(user, '수비수', '40');
    await user.click(screen.getByLabelText('사유 (선택, 한 줄)'));
    await user.paste('  방어 강화 ');
    await user.click(submit());
    await screen.findByText('사유:   방어 강화 ', {
      normalizer: (text) => text,
    });
    expect(world.bodies[1]).toEqual({
      expectedTurnNo: 4,
      formation: { goalkeeper: 20, defender: 40, midfielder: 25, forward: 15 },
      reason: '  방어 강화 ',
    });
  });

  it('shows the server answer, moves focus to it and refetches in the hook', async () => {
    const world = serveWorld();
    const { user, container } = await openForm();
    await fillNext(user);
    await user.click(screen.getByLabelText('사유 (선택, 한 줄)'));
    await user.paste('방어 강화');
    const before = { seasons: world.seasonGets, holdings: world.holdingsGets };
    await user.click(submit());
    const card = await screen.findByRole('status', {
      name: '4턴 전술을 바꿨습니다',
    });
    await waitFor(() => {
      expect(card).toHaveFocus();
    });
    expect(
      within(card).getByText('골키퍼 40 · 수비수 30 · 미드필더 20 · 공격수 10'),
    ).toBeVisible();
    expect(
      within(card).getByText('골키퍼 30 · 수비수 30 · 미드필더 25 · 공격수 15'),
    ).toBeVisible();
    expect(within(card).getByText('사유: 방어 강화')).toBeVisible();
    expect(
      within(card).getByText(/이번 턴에 이어서 내는 주문은 이 변경에 묶입니다/),
    ).toBeVisible();
    expect(
      within(card).getByRole('link', { name: '매매로 돌아가기' }),
    ).toHaveAttribute('href', '/seasons/7/trade');
    expect(world.seasonGets).toBeGreaterThan(before.seasons);
    expectNoDatePattern(container);

    // The form restarts from the refetched formation, not from what was typed.
    await user.click(screen.getByRole('button', { name: '한 번 더 바꾸기' }));
    expect(screen.getByLabelText('골키퍼')).toHaveValue('30');
    expect(screen.getByLabelText('사유 (선택, 한 줄)')).toHaveValue('');
    expect(screen.getAllByText('지금 30')).toHaveLength(2);
    expect(submit()).toBeDisabled();
  });

  it('says "no reason" when the server stored none', async () => {
    serveWorld();
    const { user } = await openForm();
    await fillNext(user);
    await user.click(submit());
    expect(await screen.findByText('사유 없음')).toBeVisible();
  });

  it('puts server field errors under the field they belong to', async () => {
    const world = serveWorld();
    world.answer = () =>
      problem(400, {
        code: 'INVALID_ARGUMENT',
        status: 400,
        errors: [
          { field: 'formation', reason: 'FormationSum' },
          { field: 'reason', reason: 'Size' },
        ],
      });
    const { user } = await openForm();
    await fillNext(user);
    await user.click(submit());
    expect(
      await screen.findByText(
        '포메이션을 확인해 주세요(합 100, 골키퍼 1 이상).',
      ),
    ).toBeVisible();
    expect(screen.getByText('사유는 200자까지 적을 수 있습니다')).toBeVisible();
    expect(screen.getAllByRole('alert')).toHaveLength(2);
    expect(screen.getByLabelText('골키퍼')).toHaveValue('30');
  });

  it('shows a form-level text for a 400 without field errors', async () => {
    const world = serveWorld();
    world.answer = () =>
      problem(400, { code: 'INVALID_ARGUMENT', status: 400 });
    const { user } = await openForm();
    await fillNext(user);
    await user.click(submit());
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '입력값을 다시 확인해 주세요.',
    );
  });

  it('tells the player a stale turn, refetches and keeps the inputs', async () => {
    const world = serveWorld();
    world.answer = () => problem(409, { code: 'TURN_MISMATCH', status: 409 });
    const { user } = await openForm();
    await fillNext(user);
    world.turn = 5;
    await user.click(submit());
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '화면의 턴이 지난 턴입니다',
    );
    expect(
      await screen.findByRole('heading', { name: '5턴 전술 변경' }),
    ).toBeVisible();
    expect(screen.getByLabelText('골키퍼')).toHaveValue('30');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(world.bodies).toHaveLength(1);
  });

  it('uses the next expected turn on the next submit after a stale turn', async () => {
    const world = serveWorld();
    const { user } = await openForm();
    await fillNext(user);
    let first = true;
    const original = world.answer;
    world.answer = (body) => {
      if (first) {
        first = false;
        return problem(409, { code: 'TURN_MISMATCH', status: 409 });
      }
      return original(body);
    };
    world.turn = 5;
    await user.click(submit());
    await screen.findByRole('heading', { name: '5턴 전술 변경' });
    await user.click(submit());
    await screen.findByText('5턴 전술을 바꿨습니다');
    expect(screen.queryByRole('alert')).toBeNull();
    expect((world.bodies[1] as { expectedTurnNo: number }).expectedTurnNo).toBe(
      5,
    );
  });

  it('answers a season that ended with the formation-specific text', async () => {
    const world = serveWorld();
    world.answer = () => {
      world.status = 'FINISHED';
      return problem(409, { code: 'SEASON_NOT_ACTIVE', status: 409 });
    };
    const { user } = await openForm();
    await fillNext(user);
    await user.click(submit());
    expect(
      await screen.findByText(
        '진행 중인 시즌이 아니어서 전술을 바꿀 수 없습니다.',
      ),
    ).toBeVisible();
    expect(
      await screen.findByText('끝난 시즌은 전술을 바꿀 수 없습니다.'),
    ).toBeVisible();
    expect(screen.queryByRole('button', { name: '전술 변경 적용' })).toBeNull();
  });

  it('shows the catalog text for INVALID_FORMATION without a refetch', async () => {
    const world = serveWorld();
    world.answer = () =>
      problem(409, { code: 'INVALID_FORMATION', status: 409 });
    const { user } = await openForm();
    await fillNext(user);
    const seasonGets = world.seasonGets;
    await user.click(submit());
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '포메이션 기록이 맞지 않아 변경을 멈췄습니다.',
    );
    expect(world.seasonGets).toBe(seasonGets);
  });

  it('treats a lost answer as an unknown outcome and asks the server again', async () => {
    const world = serveWorld();
    world.answer = () => HttpResponse.error();
    const { user } = await openForm();
    await fillNext(user);
    const seasonGets = world.seasonGets;
    await user.click(submit());
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '결과를 확인하지 못했습니다. 서버의 현재 상태를 다시 불러왔습니다.',
    );
    await waitFor(() => {
      expect(world.seasonGets).toBeGreaterThan(seasonGets);
    });
  });

  it('sends one request for a double submit', async () => {
    const world = serveWorld();
    let release = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const answer = world.answer;
    world.answer = async (body) => {
      await gate;
      return answer(body);
    };
    const { user } = await openForm();
    await fillNext(user);
    await user.dblClick(submit());
    release();
    await screen.findByText('4턴 전술을 바꿨습니다');
    expect(world.bodies).toHaveLength(1);
  });

  it('refreshes the season even when the page was left while pending', async () => {
    const world = serveWorld();
    let release = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const answer = world.answer;
    world.answer = async (body) => {
      await gate;
      return answer(body);
    };
    const { user, router } = await openForm();
    await fillNext(user);
    await user.click(submit());
    await waitFor(() => {
      expect(world.bodies).toHaveLength(1);
    });
    await router.navigate('/');
    const seasonGets = world.seasonGets;
    release();
    await waitFor(() => {
      expect(world.seasonGets).toBeGreaterThan(seasonGets);
    });
  });

  it('is closed at turn 0 and shows no form', async () => {
    const world = serveWorld({ turn: 0 });
    renderRoute('/seasons/7/formation');
    expect(
      await screen.findByText(
        '전술은 1턴부터 바꿀 수 있습니다. 0턴에는 팀 구성만 합니다.',
      ),
    ).toBeVisible();
    expect(screen.queryByRole('button', { name: '전술 변경 적용' })).toBeNull();
    expect(world.bodies).toHaveLength(0);
  });

  it('is closed for a finished and an abandoning season', async () => {
    serveWorld({ status: 'FINISHED' });
    const first = renderRoute('/seasons/7/formation');
    expect(
      await screen.findByText('끝난 시즌은 전술을 바꿀 수 없습니다.'),
    ).toBeVisible();
    first.unmount();
    serveWorld({ status: 'ABANDONING' });
    renderRoute('/seasons/7/formation');
    expect(
      await screen.findByText('정리 중인 시즌은 전술을 바꿀 수 없습니다.'),
    ).toBeVisible();
  });

  it('does not request anything for a bad season id', async () => {
    const world = serveWorld();
    renderRoute('/seasons/x/formation');
    expect(await screen.findByRole('heading')).toBeInTheDocument();
    expect(world.seasonGets).toBe(0);
    expect(world.bodies).toHaveLength(0);
  });

  it('leaks no date or seed from poisoned server answers', async () => {
    const world = serveWorld();
    world.answer = (body) =>
      HttpResponse.json(
        {
          ...formationChangeFixture({
            turnNo: 4,
            after: body.formation as typeof NOW,
          }),
          yearMonth: '2008-09',
          monthBlock: 987654,
          seed: 'SEED-XYZ',
        },
        { status: 201 },
      );
    const { user, container } = await openForm();
    expectNoDatePattern(container);
    await fillNext(user);
    await user.click(submit());
    await screen.findByText('4턴 전술을 바꿨습니다');
    expectNoDatePattern(container);
    expect(container.innerHTML).not.toContain('987654');
    expect(container.innerHTML).not.toContain('SEED-XYZ');
  });

  it('keeps the form locked while a turn advance is in flight', async () => {
    serveWorld();
    server.use(
      http.post('*/api/v1/seasons/7/turns', () => new Promise(() => undefined)),
    );
    const user = userEvent.setup();
    const { router, queryClient } = renderRoute('/seasons/7/formation');
    await screen.findByRole('button', { name: '전술 변경 적용' });
    // Start an advance through the same mutation the turn page uses.
    await router.navigate('/seasons/7');
    await user.click(
      await screen.findByRole('button', { name: '다음 턴 (5턴으로)' }),
    );
    await router.navigate('/seasons/7/formation');
    await screen.findByRole('button', { name: '전술 변경 적용' });
    expect(screen.getByLabelText('골키퍼')).toBeDisabled();
    expect(submit()).toBeDisabled();
    await act(async () => {
      await invalidateSeasonState(queryClient, 7);
    });
  });
});
