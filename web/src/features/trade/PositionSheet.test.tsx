import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { expectNoDatePattern } from '../../test/expectNoDatePattern.ts';
import {
  heldMidfielder,
  holdingsFixture,
  holdingsWithMidfielder,
} from '../../test/fixtures/holdings.ts';
import { lineupChangeFixture } from '../../test/fixtures/lineup.ts';
import { pricesFixture } from '../../test/fixtures/prices.ts';
import { seasonFixture } from '../../test/fixtures/seasons.ts';
import { renderRoute } from '../../test/renderWithProviders.tsx';
import { serveSeason } from '../../test/serve.ts';
import { server } from '../../test/server.ts';
import { failWithProblem, serveTurnWorld } from '../../test/serveTurns.ts';

const LINEUP = '*/api/v1/seasons/7/lineup-changes';

interface Spy {
  bodies: unknown[];
  holdingsCalls: number;
}

// Holdings: the first answer is the midfielder, later ones are the same
// holding declared as a target forward (what the server stores afterwards).
function serveTrade(first = holdingsWithMidfielder): Spy {
  const spy: Spy = { bodies: [], holdingsCalls: 0 };
  server.use(
    http.get('*/api/v1/seasons/7/holdings', () => {
      spy.holdingsCalls += 1;
      return HttpResponse.json(
        spy.holdingsCalls === 1
          ? first
          : holdingsFixture({
              ...holdingsWithMidfielder,
              holdings: [
                { ...heldMidfielder, declaredPosition: 'FORWARD_TARGET' },
              ],
            }),
      );
    }),
    http.get('*/api/v1/seasons/7/prices', () =>
      HttpResponse.json(pricesFixture(1)),
    ),
  );
  return spy;
}

function serveLineup(spy: Spy, answer: () => Response | Promise<Response>) {
  server.use(
    http.post(LINEUP, async ({ request }) => {
      spy.bodies.push(await request.json());
      return answer();
    }),
  );
}

// Counts season GETs; after the first answer they never resolve when `hang`.
function countSeasonGets(hang = false) {
  const counter = { gets: 0 };
  server.use(
    http.get('*/api/v1/seasons/7', async () => {
      counter.gets += 1;
      if (hang && counter.gets > 1) await delay('infinite');
      return HttpResponse.json(seasonFixture({ id: 7, currentTurnNo: 1 }));
    }),
  );
  return counter;
}

const ADVICE = /추천|조언|횟수|제한/;

const changed = (overrides = {}) =>
  HttpResponse.json(lineupChangeFixture(overrides));

async function openSheet() {
  const user = userEvent.setup();
  const rendered = renderRoute('/seasons/7/trade');
  const opener = await screen.findByRole('button', {
    name: '종목 가 포지션 변경',
  });
  await user.click(opener);
  const dialog = await screen.findByRole('dialog', {
    name: '종목 가 포지션 변경',
  });
  return { user, dialog, opener, ...rendered };
}

describe('position change entry', () => {
  it('is offered on a holding card from turn 1 on', async () => {
    serveSeason({ currentTurnNo: 1 });
    serveTrade();
    renderRoute('/seasons/7/trade');
    expect(
      await screen.findByRole('button', { name: '종목 가 포지션 변경' }),
    ).toBeEnabled();
  });

  it('is absent at turn 0, while the team is being built', async () => {
    serveSeason({ currentTurnNo: 0 });
    serveTrade(holdingsFixture({ holdings: [heldMidfielder] }));
    renderRoute('/seasons/7/trade');
    await screen.findByRole('button', { name: '종목 가 매도' });
    expect(screen.queryByRole('button', { name: /포지션 변경/ })).toBeNull();
  });

  it.each(['FINISHED', 'HALTED', 'ABANDONING'] as const)(
    'is absent in a %s season',
    async (status) => {
      serveSeason({ status, currentTurnNo: 3 });
      serveTrade();
      renderRoute('/seasons/7/trade');
      await delay(30);
      expect(screen.queryByRole('button', { name: /포지션 변경/ })).toBeNull();
    },
  );

  it('is disabled while a turn advance is in flight', async () => {
    const world = serveTurnWorld({ current: 1 });
    world.postGate = new Promise<void>(() => undefined);
    serveTrade();
    const user = userEvent.setup();
    const { router } = renderRoute('/seasons/7');
    await user.click(
      await screen.findByRole('button', { name: '다음 턴 (2턴으로)' }),
    );
    await waitFor(() => {
      expect(world.posts).toHaveLength(1);
    });
    await router.navigate('/seasons/7/trade');
    expect(
      await screen.findByRole('button', { name: '종목 가 포지션 변경' }),
    ).toBeDisabled();
  });
});

describe('PositionSheet', () => {
  it('lists six positions, checks the current one and needs a different choice', async () => {
    serveSeason({ currentTurnNo: 1 });
    serveTrade();
    const { user, dialog } = await openSheet();
    const radios = within(dialog).getAllByRole('radio');
    expect(radios).toHaveLength(6);
    expect(
      within(dialog).getByRole('radio', { name: '미드필더 (지금)' }),
    ).toBeChecked();
    expect(within(dialog).getByText('시스템 판정: 미드필더')).toBeVisible();
    const submit = within(dialog).getByRole('button', { name: '바꾸기' });
    expect(submit).toBeDisabled();
    await user.click(within(dialog).getByRole('radio', { name: '수비수' }));
    expect(submit).toBeEnabled();
    await user.click(
      within(dialog).getByRole('radio', { name: '미드필더 (지금)' }),
    );
    expect(submit).toBeDisabled();
    expect(dialog.textContent).not.toMatch(ADVICE);
  });

  it('checks the declared position, not the system judgement, when they differ', async () => {
    serveSeason({ currentTurnNo: 1 });
    serveTrade(
      holdingsFixture({
        ...holdingsWithMidfielder,
        holdings: [{ ...heldMidfielder, declaredPosition: 'FORWARD_BREAKOUT' }],
      }),
    );
    const { dialog } = await openSheet();
    expect(
      within(dialog).getByRole('radio', { name: '공격수·돌파형 (지금)' }),
    ).toBeChecked();
    expect(within(dialog).getByText('시스템 판정: 미드필더')).toBeVisible();
    expect(
      within(dialog).getByRole('button', { name: '바꾸기' }),
    ).toBeDisabled();
  });

  it('sends exactly the request body and refreshes the holding card', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    serveLineup(spy, () => changed());
    const { user, dialog } = await openSheet();
    await user.click(
      within(dialog).getByRole('radio', { name: '공격수·타겟형' }),
    );
    await user.click(within(dialog).getByRole('button', { name: '바꾸기' }));
    expect(await within(dialog).findByRole('status')).toHaveTextContent(
      '선언 포지션: 공격수·타겟형. 다음 턴부터 새 포지션으로 평가합니다.',
    );
    expect(dialog.textContent).not.toContain('(으)로');
    expect(dialog.textContent).not.toMatch(ADVICE);
    expect(spy.bodies).toEqual([
      { expectedTurnNo: 1, assetId: 101, declaredPosition: 'FORWARD_TARGET' },
    ]);
    await waitFor(() => {
      expect(spy.holdingsCalls).toBe(2);
    });
    await user.click(within(dialog).getByRole('button', { name: '닫기' }));
    const list = screen.getByRole('region', { name: '보유 종목' });
    expect(await within(list).findByText(/공격수·타겟형/)).toBeVisible();
  });

  it('says the position was already that one when nothing changed', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    serveLineup(spy, () =>
      changed({ declaredPosition: 'DEFENDER', changed: false }),
    );
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('radio', { name: '수비수' }));
    await user.click(within(dialog).getByRole('button', { name: '바꾸기' }));
    expect(await within(dialog).findByRole('status')).toHaveTextContent(
      '이미 그 포지션입니다',
    );
    expect(within(dialog).queryByRole('alert')).toBeNull();
    expect(dialog).not.toHaveTextContent('선언 포지션: 수비수.');
    expect(dialog.textContent).not.toMatch(ADVICE);
  });

  it('sends one request even when the button is pressed twice quickly', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    serveLineup(spy, async () => {
      await delay(50);
      return changed();
    });
    const { user, dialog } = await openSheet();
    await user.click(
      within(dialog).getByRole('radio', { name: '공격수·타겟형' }),
    );
    const submit = within(dialog).getByRole('button', { name: '바꾸기' });
    await user.dblClick(submit);
    await within(dialog).findByRole('status');
    expect(spy.bodies).toHaveLength(1);
  });

  it.each([
    [
      422,
      'INSUFFICIENT_HOLDING',
      '이 종목은 지금 보유하고 있지 않아 포지션을 바꿀 수 없습니다.',
    ],
    [
      409,
      'LINEUP_MISMATCH',
      '보유와 라인업 기록이 맞지 않아 포지션 변경을 멈췄습니다.',
    ],
    [400, 'INVALID_ARGUMENT', '입력값을 다시 확인해 주세요.'],
    [
      500,
      'INTERNAL_ERROR',
      '서버에 문제가 생겼습니다. 잠시 뒤 다시 시도해 주세요.',
    ],
  ] as const)(
    'answers %s %s with a local sentence',
    async (status, code, text) => {
      serveSeason({ currentTurnNo: 1 });
      const spy = serveTrade();
      serveLineup(spy, failWithProblem(status, code));
      const { user, dialog } = await openSheet();
      await user.click(within(dialog).getByRole('radio', { name: '수비수' }));
      await user.click(within(dialog).getByRole('button', { name: '바꾸기' }));
      const alert = await within(dialog).findByRole('alert');
      expect(alert.textContent).toBe(text);
      expect(dialog.textContent).not.toMatch(ADVICE);
      expect(spy.bodies).toHaveLength(1);
      if (code === 'INSUFFICIENT_HOLDING') {
        await waitFor(() => {
          expect(spy.holdingsCalls).toBe(2);
        });
      }
      expect(screen.getByRole('dialog')).toBeVisible();
    },
  );

  it.each([
    [
      'TURN_MISMATCH',
      '화면의 턴이 지난 턴입니다. 새로 고친 뒤 다시 시도해 주세요.',
    ],
    [
      'SEASON_NOT_ACTIVE',
      '진행 중인 시즌이 아니어서 포지션을 바꿀 수 없습니다.',
    ],
  ] as const)(
    'closes the sheet and notes a stale screen on %s',
    async (code, text) => {
      serveSeason({ currentTurnNo: 1 });
      const spy = serveTrade();
      const seasons = countSeasonGets();
      serveLineup(spy, failWithProblem(409, code));
      const { user, dialog } = await openSheet();
      await user.click(within(dialog).getByRole('radio', { name: '수비수' }));
      await user.click(within(dialog).getByRole('button', { name: '바꾸기' }));
      await waitFor(() => {
        expect(screen.queryByRole('dialog')).toBeNull();
      });
      expect(screen.getByRole('alert').textContent).toBe(text);
      await waitFor(() => {
        expect(seasons.gets).toBeGreaterThanOrEqual(2);
      });
    },
  );

  it('says the outcome is unknown after a network error, refetches and does not resend', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    serveLineup(spy, () => HttpResponse.error());
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('radio', { name: '수비수' }));
    await user.click(within(dialog).getByRole('button', { name: '바꾸기' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      '결과를 확인하지 못했습니다.',
    );
    await waitFor(() => {
      expect(spy.holdingsCalls).toBe(2);
    });
    await delay(50);
    expect(spy.bodies).toHaveLength(1);
  });

  it('returns focus to the opener, never prints the asset id and holds no date', async () => {
    serveSeason({ currentTurnNo: 1 });
    serveTrade();
    const { user, dialog, opener, container } = await openSheet();
    expect(container.innerHTML).not.toMatch(/\b101\b/);
    expect(dialog.innerHTML).not.toMatch(/\b101\b/);
    expectNoDatePattern(dialog);
    await user.click(within(dialog).getByRole('button', { name: '닫기' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(opener).toHaveFocus();
  });

  it('closes with Escape and sends nothing', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    serveLineup(spy, () => changed());
    const { user, opener } = await openSheet();
    await user.keyboard('{Escape}');
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(opener).toHaveFocus();
    expect(spy.bodies).toHaveLength(0);
  });
});

describe('PositionSheet failure paths', () => {
  it('counts a season refetch after an unreadable answer', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    const seasons = countSeasonGets();
    serveLineup(spy, () => HttpResponse.error());
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('radio', { name: '수비수' }));
    await user.click(within(dialog).getByRole('button', { name: '바꾸기' }));
    await within(dialog).findByRole('alert');
    await waitFor(() => {
      expect(seasons.gets).toBeGreaterThanOrEqual(2);
    });
  });

  it('shows the server position after an unknown outcome that was committed', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    serveLineup(spy, () => HttpResponse.error());
    const { user, dialog } = await openSheet();
    await user.click(
      within(dialog).getByRole('radio', { name: '공격수·타겟형' }),
    );
    await user.click(within(dialog).getByRole('button', { name: '바꾸기' }));
    await within(dialog).findByRole('alert');
    expect(
      await within(dialog).findByRole('radio', {
        name: '공격수·타겟형 (지금)',
      }),
    ).toBeChecked();
    expect(
      within(dialog).getByRole('button', { name: '바꾸기' }),
    ).toBeDisabled();
    expect(dialog.textContent).not.toMatch(ADVICE);
  });

  it('is not trapped when the refresh after a failure never resolves', async () => {
    serveSeason({ currentTurnNo: 1 });
    let holdingsGets = 0;
    server.use(
      http.get('*/api/v1/seasons/7/holdings', async () => {
        holdingsGets += 1;
        if (holdingsGets > 1) await delay('infinite');
        return HttpResponse.json(holdingsWithMidfielder);
      }),
      http.get('*/api/v1/seasons/7/prices', () =>
        HttpResponse.json(pricesFixture(1)),
      ),
    );
    const spy: Spy = { bodies: [], holdingsCalls: 0 };
    serveLineup(spy, () => HttpResponse.error());
    countSeasonGets(true);
    const { user, dialog, opener } = await openSheet();
    await user.click(within(dialog).getByRole('radio', { name: '수비수' }));
    await user.click(within(dialog).getByRole('button', { name: '바꾸기' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      '결과를 확인하지 못했습니다.',
    );
    expect(within(dialog).getByRole('button', { name: '닫기' })).toBeEnabled();
    expect(
      within(dialog).getByRole('button', { name: '바꾸기' }),
    ).toBeEnabled();
    await user.keyboard('{Escape}');
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(opener).toHaveFocus();
  });

  it('cannot be closed with Escape or the close button while the request is pending', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    let release = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    serveLineup(spy, async () => {
      await gate;
      return changed();
    });
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('radio', { name: '수비수' }));
    await user.click(within(dialog).getByRole('button', { name: '바꾸기' }));
    expect(
      await within(dialog).findByRole('button', { name: '바꾸는 중…' }),
    ).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: '닫기' })).toBeDisabled();
    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog')).toBeVisible();
    release();
    expect(await within(dialog).findByRole('status')).toBeVisible();
  });

  it('names the radio group', async () => {
    serveSeason({ currentTurnNo: 1 });
    serveTrade();
    const { dialog } = await openSheet();
    expect(
      within(dialog).getByRole('group', { name: '선언 포지션' }),
    ).toBeVisible();
  });
});
