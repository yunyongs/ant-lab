import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import { holdingsWithMidfielder } from '../test/fixtures/holdings.ts';
import { pricesFixture } from '../test/fixtures/prices.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveAbandonWorld, type AbandonWorld } from '../test/serveAbandon.ts';
import { server } from '../test/server.ts';
import { gate } from '../test/serveTurns.ts';

const OPEN = '시즌 포기…';
const KEEP = '포기하지 않기';
const CONFIRM = '포기하고 끝까지 진행';
const RESUME = '이어서 정리';

async function openSeason(start: Parameters<typeof serveAbandonWorld>[0] = {}) {
  const world = serveAbandonWorld(start);
  const view = renderRoute('/seasons/7');
  // The header line shows for every status once the season is read.
  await within(screen.getByRole('banner')).findByText(/\d+ ?\/ ?\d+턴/);
  return { world, view };
}

type User = ReturnType<typeof userEvent.setup>;

async function menuButton(user: User) {
  const button = screen.getByRole('button', { name: '메뉴' });
  if (button.getAttribute('aria-expanded') !== 'true') await user.click(button);
  return button;
}

// The abandon item of the open header menu; null when it is not offered.
async function abandonItem(user: User) {
  await menuButton(user);
  return screen.queryByRole('button', { name: OPEN });
}

async function openDialog(user: User) {
  await menuButton(user);
  await user.click(await screen.findByRole('button', { name: OPEN }));
  return screen.findByRole('dialog', { name: '시즌을 포기할까요?' });
}

// The number of turns the server has been asked to advance by an abandon.
function bodies(world: AbandonWorld) {
  return world.posts;
}

describe('abandon button (A1)', () => {
  it.each([0, 5])(
    'is offered for an in-progress season at turn %i',
    async (turn) => {
      await openSeason({ current: turn });
      expect(await abandonItem(userEvent.setup())).toBeEnabled();
      expect(screen.queryByRole('button', { name: RESUME })).toBeNull();
      // Nothing is asked of the server by merely showing it.
    },
  );

  it.each(['FINISHED', 'HALTED'] as const)(
    'is not offered for a %s season',
    async (status) => {
      await openSeason({ current: status === 'FINISHED' ? 12 : 5, status });
      expect(await abandonItem(userEvent.setup())).toBeNull();
      expect(screen.queryByRole('button', { name: RESUME })).toBeNull();
    },
  );

  it('is disabled while a turn advance is in flight', async () => {
    const { world } = await openSeason({ current: 5 });
    const hold = gate();
    world.advanceGate = hold.promise;
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '6턴으로 넘기기' }));
    await menuButton(user);
    await waitFor(() => {
      expect(screen.getByRole('button', { name: OPEN })).toBeDisabled();
    });
    hold.release();
    await waitFor(() => {
      expect(screen.getByRole('button', { name: OPEN })).toBeEnabled();
    });
  });

  it('has no abandon button left on the page; the item sits in the menu', async () => {
    await openSeason({ current: 5 });
    expect(screen.queryByRole('button', { name: '시즌 포기' })).toBeNull();
    expect(screen.queryByRole('region', { name: '시즌 포기' })).toBeNull();
    const user = userEvent.setup();
    await menuButton(user);
    const menu = screen.getByRole('button', { name: OPEN }).parentElement;
    expect(menu).toContainElement(
      screen.getByRole('button', { name: '로그아웃' }),
    );
  });

  it('closes the menu when the item is pressed', async () => {
    await openSeason({ current: 5 });
    const user = userEvent.setup();
    await openDialog(user);
    expect(screen.getByRole('button', { name: '메뉴' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
  });
});

describe('confirm step (A2, A12)', () => {
  it('is not in the page before the button is pressed', async () => {
    await openSeason();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('names the consequences with the remaining turns and no date', async () => {
    await openSeason({ current: 5, pathLength: 12 });
    const user = userEvent.setup();
    const dialog = await openDialog(user);
    expect(
      within(dialog).getByText('남은 7턴을 매매 없이 자동으로 진행합니다.'),
    ).toBeInTheDocument();
    expect(dialog).toHaveTextContent('성적표');
    expect(dialog).toHaveTextContent('레이팅');
    expect(dialog).toHaveTextContent('미시작');
    expect(dialog).toHaveTextContent('되돌릴 수 없습니다');
    expect(within(dialog).getByRole('button', { name: KEEP })).toHaveFocus();
    expectNoDatePattern(dialog);
  });

  it('cancel button closes, sends nothing and returns focus to the opener', async () => {
    const { world } = await openSeason();
    const user = userEvent.setup();
    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('button', { name: KEEP }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('button', { name: '메뉴' })).toHaveFocus();
    expect(bodies(world)).toEqual([]);
  });

  it('Escape closes, sends nothing and returns focus to the opener', async () => {
    const { world } = await openSeason();
    const user = userEvent.setup();
    await openDialog(user);
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('button', { name: '메뉴' })).toHaveFocus();
    expect(bodies(world)).toEqual([]);
  });

  it('can be opened again after a cancel', async () => {
    await openSeason();
    const user = userEvent.setup();
    await user.click(
      within(await openDialog(user)).getByRole('button', { name: KEEP }),
    );
    expect(await openDialog(user)).toBeInTheDocument();
  });
});

describe('confirm and finish (A3)', () => {
  it('sends exactly the shown turn, refetches the season and lands on the finished screen', async () => {
    const { world, view } = await openSeason({ current: 5 });
    const user = userEvent.setup();
    const dialog = await openDialog(user);
    expect(world.seasonGets).toBe(1);
    await user.click(within(dialog).getByRole('button', { name: CONFIRM }));
    const heading = await screen.findByRole('heading', {
      name: '12턴 결과 (마지막)',
    });
    expect(bodies(world)).toEqual([{ expectedTurnNo: 5 }]);
    expect(
      typeof (world.posts[0] as { expectedTurnNo: unknown }).expectedTurnNo,
    ).toBe('number');
    expect(world.seasonGets).toBe(2);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getAllByText('5턴에서 포기')).not.toHaveLength(0);
    // The last turn is read once the season is finished; focus follows it.
    await waitFor(() => {
      expect(heading).toHaveFocus();
    });
    expect(await abandonItem(userEvent.setup())).toBeNull();
    expect(screen.getByText('시즌이 끝났습니다')).toBeInTheDocument();
    expectNoDatePattern(view.container);
  });

  it('shows "미시작" from the server flag for an early abandon', async () => {
    await openSeason({ current: 1 });
    const user = userEvent.setup();
    await user.click(
      within(await openDialog(user)).getByRole('button', { name: CONFIRM }),
    );
    await screen.findByText('시즌이 끝났습니다');
    expect(screen.getAllByText('1턴에서 포기 · 미시작')).not.toHaveLength(0);
  });

  it('abandons at turn 0 with expectedTurnNo 0', async () => {
    const { world } = await openSeason({ current: 0 });
    const user = userEvent.setup();
    await user.click(
      within(await openDialog(user)).getByRole('button', { name: CONFIRM }),
    );
    await screen.findByText('시즌이 끝났습니다');
    expect(bodies(world)).toEqual([{ expectedTurnNo: 0 }]);
  });

  it('sends one request for a double tap on the confirm button', async () => {
    const { world } = await openSeason({ current: 5 });
    const hold = gate();
    world.postGates.push(hold.promise);
    const user = userEvent.setup();
    const dialog = await openDialog(user);
    const confirm = within(dialog).getByRole('button', { name: CONFIRM });
    act(() => {
      confirm.click();
      confirm.click();
    });
    await waitFor(() => {
      expect(bodies(world)).toHaveLength(1);
    });
    // The sheet cannot be dismissed or confirmed again while it runs.
    expect(within(dialog).getByRole('button', { name: KEEP })).toBeDisabled();
    expect(
      within(dialog).getByRole('button', { name: CONFIRM }),
    ).toBeDisabled();
    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    hold.release();
    await screen.findByText('시즌이 끝났습니다');
    expect(bodies(world)).toHaveLength(1);
  });
});

describe('continuation (A4, A5)', () => {
  it('repeats while the server says ABANDONING with progress, and shows it', async () => {
    const { world } = await openSeason({ current: 5 });
    const second = gate();
    world.answers.push({ kind: 'abandoning', advanced: 4 });
    world.postGates.push(Promise.resolve(), second.promise);
    const user = userEvent.setup();
    await user.click(
      within(await openDialog(user)).getByRole('button', { name: CONFIRM }),
    );
    const status = await screen.findByText(
      '9/12턴까지 정리했습니다. 남은 3턴을 이어서 진행합니다.',
    );
    expect(status).toHaveAttribute('role', 'status');
    expect(bodies(world)).toEqual([
      { expectedTurnNo: 5 },
      { expectedTurnNo: 9 },
    ]);
    // The season is not read between the two requests, and no turn beyond
    // the one shown is asked for before the season has ended.
    expect(world.seasonGets).toBe(1);
    expect(world.turnGets).toEqual([5]);
    second.release();
    await screen.findByText('시즌이 끝났습니다');
    await waitFor(() => {
      expect(world.turnGets).toEqual([5, 12]);
    });
    expect(bodies(world)).toHaveLength(2);
    expect(world.seasonGets).toBe(2);
  });

  it('stops when the server answers ABANDONING without progress and offers the resume button', async () => {
    const { world } = await openSeason({ current: 5 });
    world.answers.push({ kind: 'abandoning', advanced: 0 });
    const user = userEvent.setup();
    await user.click(
      within(await openDialog(user)).getByRole('button', { name: CONFIRM }),
    );
    const resume = await screen.findByRole('button', { name: RESUME });
    expect(bodies(world)).toEqual([{ expectedTurnNo: 5 }]);
    expect(
      screen.getByText('아직 7턴이 남았습니다. 이어서 정리해 주세요.'),
    ).toBeInTheDocument();
    expect(await abandonItem(userEvent.setup())).toBeNull();
    expect(resume).toBeEnabled();
    // Nothing more is sent by itself.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(bodies(world)).toHaveLength(1);
  });
});

describe('failures', () => {
  it('does not repeat after a network failure, says the outcome is unknown and reads the season once', async () => {
    const { world } = await openSeason({ current: 5 });
    world.answers.push({ kind: 'network' });
    const user = userEvent.setup();
    await user.click(
      within(await openDialog(user)).getByRole('button', { name: CONFIRM }),
    );
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(
      '결과를 확인하지 못했습니다. 서버의 현재 상태를 다시 불러왔습니다.',
    );
    await waitFor(() => {
      expect(world.seasonGets).toBe(2);
    });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(bodies(world)).toHaveLength(1);
    expect(world.seasonGets).toBe(2);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(alert).toHaveFocus();
  });

  it('does not repeat after a network failure on the second request', async () => {
    const { world } = await openSeason({ current: 5 });
    world.answers.push(
      { kind: 'abandoning', advanced: 4 },
      { kind: 'network' },
    );
    const user = userEvent.setup();
    await user.click(
      within(await openDialog(user)).getByRole('button', { name: CONFIRM }),
    );
    await screen.findByRole('alert');
    // The first answer marked the season, so the refetch shows the resume step.
    await screen.findByRole('button', { name: RESUME });
    expect(bodies(world)).toHaveLength(2);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(bodies(world)).toHaveLength(2);
  });

  it('shows its own sentence for a failed turn and offers to continue', async () => {
    const { world } = await openSeason({ current: 5 });
    world.answers.push({
      kind: 'refuse',
      httpStatus: 409,
      code: 'RECONCILIATION_FAILED',
    });
    const user = userEvent.setup();
    await user.click(
      within(await openDialog(user)).getByRole('button', { name: CONFIRM }),
    );
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('이어서 정리해 주세요.');
    expect(alert).toHaveTextContent(
      '포기 표시와 그때까지 진행한 턴은 남아 있습니다',
    );
    expect(await screen.findByRole('button', { name: RESUME })).toBeEnabled();
    expect(bodies(world)).toHaveLength(1);
    expect(world.seasonGets).toBe(2);
    // The resume continues; it is a new, explicit request.
    const user2 = userEvent.setup();
    await user2.click(screen.getByRole('button', { name: RESUME }));
    await screen.findByText('시즌이 끝났습니다');
    expect(bodies(world)).toEqual([
      { expectedTurnNo: 5 },
      { expectedTurnNo: 5 },
    ]);
  });

  it('shows a stale-screen refusal as a page notice, closes the sheet and refetches', async () => {
    const { world } = await openSeason({ current: 5 });
    world.answers.push({
      kind: 'refuse',
      httpStatus: 409,
      code: 'TURN_MISMATCH',
    });
    const user = userEvent.setup();
    await user.click(
      within(await openDialog(user)).getByRole('button', { name: CONFIRM }),
    );
    const notice = await screen.findByRole('alert');
    expect(notice).toHaveTextContent(
      '화면의 턴이 지난 턴입니다. 새로 고친 뒤 다시 시도해 주세요.',
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    await waitFor(() => {
      expect(world.seasonGets).toBe(2);
    });
    expect(bodies(world)).toHaveLength(1);
    // Focus is not lost to the document: it is back on the menu button.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '메뉴' })).toHaveFocus();
    });
    // A new request clears the old page notice before it is answered.
    const hold = gate();
    world.postGates.push(hold.promise);
    await user.click(
      within(await openDialog(user)).getByRole('button', { name: CONFIRM }),
    );
    await waitFor(() => {
      expect(bodies(world)).toHaveLength(2);
    });
    expect(screen.queryByText(/화면의 턴이 지난 턴입니다/)).toBeNull();
    hold.release();
    await screen.findByText('시즌이 끝났습니다');
  });

  it('says so when the season is no longer in progress', async () => {
    const { world } = await openSeason({ current: 5 });
    world.answers.push({
      kind: 'refuse',
      httpStatus: 409,
      code: 'SEASON_NOT_ACTIVE',
    });
    const user = userEvent.setup();
    await user.click(
      within(await openDialog(user)).getByRole('button', { name: CONFIRM }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '진행 중인 시즌이 아니어서 포기할 수 없습니다.',
    );
    await waitFor(() => {
      expect(world.seasonGets).toBe(2);
    });
  });

  it('keeps the halted sentence on screen after the season turns out halted', async () => {
    const { world } = await openSeason({ current: 5 });
    world.answers.push({
      kind: 'refuse',
      httpStatus: 409,
      code: 'SEASON_HALTED',
      leaves: 'HALTED',
    });
    const user = userEvent.setup();
    await user.click(
      within(await openDialog(user)).getByRole('button', { name: CONFIRM }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '대사가 거듭 맞지 않아 시즌이 멈췄습니다. 관리자 확인이 필요합니다.',
    );
    await waitFor(() => {
      expect(world.seasonGets).toBe(2);
    });
    await screen.findByText(/^점검 중인 시즌입니다/);
    expect(screen.getByRole('alert')).toHaveTextContent(
      '관리자 확인이 필요합니다',
    );
    expect(await abandonItem(userEvent.setup())).toBeNull();
    expect(screen.queryByRole('button', { name: RESUME })).toBeNull();
  });

  it('shows the catalog sentence for another refusal and sends once', async () => {
    const { world } = await openSeason({ current: 5 });
    world.answers.push({
      kind: 'refuse',
      httpStatus: 500,
      code: 'INTERNAL_ERROR',
    });
    const user = userEvent.setup();
    await user.click(
      within(await openDialog(user)).getByRole('button', { name: CONFIRM }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '서버에 문제가 생겼습니다.',
    );
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(bodies(world)).toHaveLength(1);
  });
});

describe('resume of a season found abandoning (A8)', () => {
  it('shows the note and the resume button, and continues with the current turn', async () => {
    const { world } = await openSeason({
      current: 8,
      status: 'ABANDONING',
      abandonedTurnNo: 5,
    });
    expect(screen.getByText('시즌을 정리하는 중입니다.')).toBeInTheDocument();
    expect(await abandonItem(userEvent.setup())).toBeNull();
    expect(screen.getAllByText('5턴에서 포기')).not.toHaveLength(0);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: RESUME }));
    await screen.findByText('시즌이 끝났습니다');
    expect(bodies(world)).toEqual([{ expectedTurnNo: 8 }]);
    expect(world.seasonGets).toBe(2);
  });

  it('shows the working sentence while it runs and keeps the button off', async () => {
    const { world } = await openSeason({
      current: 8,
      status: 'ABANDONING',
      abandonedTurnNo: 5,
    });
    const hold = gate();
    world.postGates.push(hold.promise);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: RESUME }));
    const working = await screen.findByText(
      '시즌을 정리하는 중입니다. 남은 턴을 매매 없이 진행합니다.',
    );
    expect(working).toHaveAttribute('role', 'status');
    expect(screen.getByRole('button', { name: '정리하는 중…' })).toBeDisabled();
    hold.release();
    await screen.findByText('시즌이 끝났습니다');
  });
});

describe('entry from the trade page and by keyboard (C3)', () => {
  function serveTradeData() {
    server.use(
      http.get('*/api/v1/seasons/7/holdings', () =>
        HttpResponse.json(holdingsWithMidfielder),
      ),
      http.get('*/api/v1/seasons/7/prices', () =>
        HttpResponse.json(pricesFixture(1)),
      ),
    );
  }

  it('cancels without a request, returns focus to ☰ and stays closed on back and forward', async () => {
    const world = serveAbandonWorld({ current: 5 });
    serveTradeData();
    const { router } = renderRoute('/seasons/7/trade');
    await within(screen.getByRole('banner')).findByText(/\d+ ?\/ ?\d+턴/);
    const user = userEvent.setup();
    const dialog = await openDialog(user);
    expect(router.state.location.pathname).toBe('/seasons/7');
    await user.click(within(dialog).getByRole('button', { name: KEEP }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('button', { name: '메뉴' })).toHaveFocus();
    expect(bodies(world)).toEqual([]);
    await act(async () => {
      await router.navigate(-1);
    });
    expect(router.state.location.pathname).toBe('/seasons/7/trade');
    await act(async () => {
      await router.navigate(1);
    });
    await screen.findByRole('heading', { name: '5턴 결과' });
    expect(router.state.location.pathname).toBe('/seasons/7');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(bodies(world)).toEqual([]);
  });

  it('opens by Tab and Enter, and the safe button takes the first focus', async () => {
    const world = serveAbandonWorld({ current: 5 });
    serveTradeData();
    renderRoute('/seasons/7/trade');
    await within(screen.getByRole('banner')).findByText(/\d+ ?\/ ?\d+턴/);
    const user = userEvent.setup();
    screen.getByRole('button', { name: '메뉴' }).focus();
    await user.keyboard('{Enter}');
    const item = await screen.findByRole('button', { name: OPEN });
    for (let step = 0; step < 6 && document.activeElement !== item; step += 1) {
      await user.tab();
    }
    expect(item).toHaveFocus();
    await user.keyboard('{Enter}');
    const dialog = await screen.findByRole('dialog', {
      name: '시즌을 포기할까요?',
    });
    expect(within(dialog).getByRole('button', { name: KEEP })).toHaveFocus();
    expect(bodies(world)).toEqual([]);
  });
});
