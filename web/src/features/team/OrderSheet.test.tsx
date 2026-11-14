import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  act,
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { problem } from '../../test/authHandlers.ts';
import {
  heldDefender,
  holdingsAfterBuy,
  holdingsFixture,
} from '../../test/fixtures/holdings.ts';
import { orderResultFixture } from '../../test/fixtures/orders.ts';
import { pricesFixture } from '../../test/fixtures/prices.ts';
import { seasonFixture } from '../../test/fixtures/seasons.ts';
import { renderRoute } from '../../test/renderWithProviders.tsx';
import { serveSeason } from '../../test/serve.ts';
import { server } from '../../test/server.ts';
import { holdingsKey, usePlaceOrder } from './queries.ts';

const ORDERS = '*/api/v1/seasons/7/orders';

interface Spy {
  bodies: unknown[];
  holdingsCalls: number;
  pricesCalls: number;
}

// Serves holdings: the first answer is the empty team, later ones the
// refetched state after a fill.
function serveTeam(options: { held?: boolean } = {}): Spy {
  const spy: Spy = { bodies: [], holdingsCalls: 0, pricesCalls: 0 };
  server.use(
    http.get('*/api/v1/seasons/7/holdings', () => {
      spy.holdingsCalls += 1;
      if (options.held) {
        return HttpResponse.json(holdingsFixture({ holdings: [heldDefender] }));
      }
      return HttpResponse.json(
        spy.holdingsCalls === 1 ? holdingsFixture() : holdingsAfterBuy,
      );
    }),
    http.get('*/api/v1/seasons/7/prices', () => {
      spy.pricesCalls += 1;
      return HttpResponse.json(pricesFixture(0));
    }),
  );
  return spy;
}

function serveOrder(spy: Spy, answer: () => Response | Promise<Response>) {
  server.use(
    http.post(ORDERS, async ({ request }) => {
      spy.bodies.push(await request.json());
      return answer();
    }),
  );
}

async function openSheet(name = '종목 가') {
  const user = userEvent.setup();
  const rendered = renderRoute('/seasons/7');
  await user.click(await screen.findByRole('button', { name: `${name} 선택` }));
  const dialog = await screen.findByRole('dialog', { name });
  return { user, dialog, ...rendered };
}

async function setQuantity(
  user: ReturnType<typeof userEvent.setup>,
  dialog: HTMLElement,
  text: string,
) {
  const input = within(dialog).getByRole('textbox', { name: '수량' });
  await user.clear(input);
  if (text !== '') await user.type(input, text);
}

describe('OrderSheet', () => {
  it('opens as a dialog with price, preselected position and Escape returns focus', async () => {
    serveSeason({});
    serveTeam();
    const { user, dialog } = await openSheet();
    expect(
      within(dialog).getByText('시스템 판정: 미드필더'),
    ).toBeInTheDocument();
    expect(await within(dialog).findByText('187,400원')).toBeInTheDocument();
    expect(
      within(dialog).getByRole('combobox', { name: /선언 포지션/ }),
    ).toHaveValue('MIDFIELDER');
    expect(
      within(dialog).getByRole('option', {
        name: '미드필더 (시스템 판정과 같음)',
      }),
    ).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(screen.getByRole('button', { name: '종목 가 선택' })).toHaveFocus();
  });

  it('returns focus to the card after the sheet is closed with a button', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () =>
      HttpResponse.json(orderResultFixture(), { status: 201 }),
    );
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('button', { name: '취소' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(screen.getByRole('button', { name: '종목 가 선택' })).toHaveFocus();
    await user.click(screen.getByRole('button', { name: '종목 가 선택' }));
    const again = await screen.findByRole('dialog', { name: '종목 가' });
    await user.click(within(again).getByRole('button', { name: '1주 매수' }));
    await within(again).findByText('체결가');
    await user.click(within(again).getByRole('button', { name: '닫기' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(screen.getByRole('button', { name: '종목 가 선택' })).toHaveFocus();
  });

  it('cannot be closed while the order is pending and refreshes holdings after the fill', async () => {
    serveSeason({});
    const spy = serveTeam();
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    serveOrder(spy, async () => {
      await gate;
      return HttpResponse.json(orderResultFixture(), { status: 201 });
    });
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    expect(
      await within(dialog).findByRole('button', { name: '매수 중…' }),
    ).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: '취소' })).toBeDisabled();
    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog', { name: '종목 가' })).toBeInTheDocument();
    release();
    await within(dialog).findByText('체결가');
    await waitFor(() => {
      expect(spy.holdingsCalls).toBe(2);
    });
    expect(spy.bodies).toHaveLength(1);
  });

  it('describes invalid fields by their messages', async () => {
    serveSeason({});
    serveTeam();
    const { user, dialog } = await openSheet();
    await setQuantity(user, dialog, 'abc');
    const input = within(dialog).getByRole('textbox', { name: '수량' });
    expect(input).toHaveAccessibleDescription(
      '수량은 1 이상의 정수로 적어 주세요',
    );
  });

  it('stays open under StrictMode', async () => {
    serveSeason({});
    serveTeam();
    const user = userEvent.setup();
    renderRoute('/seasons/7', { strict: true });
    await user.click(
      await screen.findByRole('button', { name: '종목 가 선택' }),
    );
    expect(
      await screen.findByRole('dialog', { name: '종목 가' }),
    ).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.getByRole('dialog', { name: '종목 가' })).toBeInTheDocument();
  });

  it('keeps a held asset read-only and sends no declared position', async () => {
    serveSeason({});
    const spy = serveTeam({ held: true });
    serveOrder(spy, () =>
      HttpResponse.json(
        orderResultFixture({ assetId: 103, declaredPosition: 'DEFENDER' }),
        { status: 201 },
      ),
    );
    const { user, dialog } = await openSheet('채권 ETF 다');
    expect(
      within(dialog).getByText(
        '선언 포지션: 수비수 (보유 중이라 바꿀 수 없습니다)',
      ),
    ).toBeInTheDocument();
    expect(within(dialog).queryByRole('combobox')).toBeNull();
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    expect(spy.bodies).toHaveLength(1);
    expect(spy.bodies[0]).toEqual({
      expectedTurnNo: 0,
      side: 'BUY',
      assetId: 103,
      quantity: 1,
    });
    expect(Object.keys(spy.bodies[0] as object)).not.toContain(
      'declaredPosition',
    );
  });

  it.each(['', '0', '-1', '1.5', 'abc', '1e3', '1000000000'])(
    'disables the buy button for quantity %j',
    async (text) => {
      serveSeason({});
      const spy = serveTeam();
      const { user, dialog } = await openSheet();
      await setQuantity(user, dialog, text);
      expect(
        within(dialog).getByRole('button', { name: /주 매수$/ }),
      ).toBeDisabled();
      expect(
        within(dialog).getByText('수량은 1 이상의 정수로 적어 주세요'),
      ).toBeInTheDocument();
      expect(
        within(dialog).getByRole('textbox', { name: '수량' }),
      ).toHaveAttribute('aria-invalid', 'true');
      expect(spy.bodies).toHaveLength(0);
    },
  );

  it('steps the quantity down to 1 and up', async () => {
    serveSeason({});
    serveTeam();
    const { user, dialog } = await openSheet();
    const input = within(dialog).getByRole('textbox', { name: '수량' });
    await user.click(
      within(dialog).getByRole('button', { name: '수량 줄이기' }),
    );
    expect(input).toHaveValue('1');
    await user.click(
      within(dialog).getByRole('button', { name: '수량 늘리기' }),
    );
    await user.click(
      within(dialog).getByRole('button', { name: '수량 늘리기' }),
    );
    expect(input).toHaveValue('3');
  });

  it('sends the exact request for a new holding', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () =>
      HttpResponse.json(orderResultFixture(), { status: 201 }),
    );
    const { user, dialog } = await openSheet();
    await user.selectOptions(
      within(dialog).getByRole('combobox', { name: /선언 포지션/ }),
      'FORWARD_TARGET',
    );
    await setQuantity(user, dialog, '13');
    await user.click(within(dialog).getByRole('button', { name: '13주 매수' }));
    await within(dialog).findByText('체결가');
    expect(spy.bodies).toEqual([
      {
        expectedTurnNo: 0,
        side: 'BUY',
        assetId: 101,
        quantity: 13,
        declaredPosition: 'FORWARD_TARGET',
      },
    ]);
  });

  it('uses the season turn as expectedTurnNo', async () => {
    serveSeason({ currentTurnNo: 0 });
    const spy = serveTeam();
    serveOrder(spy, () =>
      HttpResponse.json(orderResultFixture(), { status: 201 }),
    );
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    expect((spy.bodies[0] as { expectedTurnNo: number }).expectedTurnNo).toBe(
      seasonFixture().currentTurnNo,
    );
  });

  it('shows the fill from the response and the refetched holdings', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () =>
      HttpResponse.json(orderResultFixture(), { status: 201 }),
    );
    const { user, dialog } = await openSheet();
    await setQuantity(user, dialog, '13');
    await user.click(within(dialog).getByRole('button', { name: '13주 매수' }));
    expect(await within(dialog).findByText('체결가')).toBeInTheDocument();
    expect(within(dialog).getByText('2,436,200원')).toBeInTheDocument();
    expect(within(dialog).getByText('365원')).toBeInTheDocument();
    expect(within(dialog).getByText('97,563,435원')).toBeInTheDocument();
    expect(within(dialog).getByText('13주')).toBeInTheDocument();
    expect(within(dialog).getByText('미드필더')).toBeInTheDocument();
    expect(
      within(dialog).getByRole('button', { name: '이 종목 더 사기' }),
    ).toBeInTheDocument();
    // The summary shows the second holdings answer, not a computed value.
    const summary = screen.getByRole('region', { name: '팀 요약' });
    await waitFor(() => {
      expect(within(summary).getAllByText('97,112,345원')).not.toHaveLength(0);
    });
    expect(spy.holdingsCalls).toBe(2);
    expect(
      within(summary).getByText('96.812345% / 목표 40%'),
    ).toBeInTheDocument();
    expect(
      await screen.findByText('보유 13주 · 미드필더 선언'),
    ).toBeInTheDocument();
  });

  it('closes the result and lets the player buy the same asset again', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () =>
      HttpResponse.json(orderResultFixture(), { status: 201 }),
    );
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    await user.click(
      within(dialog).getByRole('button', { name: '이 종목 더 사기' }),
    );
    expect(
      await within(dialog).findByText(
        '선언 포지션: 미드필더 (보유 중이라 바꿀 수 없습니다)',
      ),
    ).toBeInTheDocument();
  });

  it.each([
    ['INSUFFICIENT_CASH', 422, '현금이 모자랍니다(대금과 수수료).'],
    ['POSITION_REQUIRED', 422, '새로 사는 종목은 포지션을 정해 주세요.'],
    ['ASSET_NOT_IN_POOL', 422, '이번 턴에 거래할 수 없는 종목입니다.'],
  ])(
    'shows %s in the sheet and keeps the input',
    async (code, status, text) => {
      serveSeason({});
      const spy = serveTeam();
      serveOrder(spy, () =>
        problem(status, { code, status, detail: '12,345원' }),
      );
      const { user, dialog } = await openSheet();
      await setQuantity(user, dialog, '999');
      await user.click(
        within(dialog).getByRole('button', { name: '999주 매수' }),
      );
      const alert = await within(dialog).findByRole('alert');
      expect(alert).toHaveTextContent(text);
      expect(alert).not.toHaveTextContent('12,345');
      expect(alert).toHaveFocus();
      expect(within(dialog).getByRole('textbox', { name: '수량' })).toHaveValue(
        '999',
      );
      expect(spy.bodies).toHaveLength(1);
      expect(spy.holdingsCalls).toBe(1);
    },
  );

  it('maps a 400 on quantity to a message under the input', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () =>
      problem(400, {
        code: 'INVALID_ARGUMENT',
        status: 400,
        errors: [{ field: 'quantity', reason: 'Min' }],
      }),
    );
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await waitFor(() => {
      expect(spy.bodies).toHaveLength(1);
    });
    expect(
      await within(dialog).findByText('수량은 1 이상의 정수로 적어 주세요'),
    ).toBeInTheDocument();
    expect(within(dialog).queryByRole('alert')).toBeNull();
  });

  it('refetches holdings on POSITION_CONFLICT and turns the position read-only', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () =>
      problem(422, { code: 'POSITION_CONFLICT', status: 422 }),
    );
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      '이미 보유 중인 종목의 포지션과 다릅니다.',
    );
    expect(
      await within(dialog).findByText(
        '선언 포지션: 미드필더 (보유 중이라 바꿀 수 없습니다)',
      ),
    ).toBeInTheDocument();
    expect(spy.holdingsCalls).toBe(2);
  });

  it('closes the sheet on TURN_MISMATCH, shows a banner and follows the new turn', async () => {
    let seasonCalls = 0;
    server.use(
      http.get('*/api/v1/seasons/7', () => {
        seasonCalls += 1;
        return HttpResponse.json(
          seasonFixture({ id: 7, currentTurnNo: seasonCalls === 1 ? 0 : 1 }),
        );
      }),
    );
    const spy = serveTeam();
    serveOrder(spy, () => problem(409, { code: 'TURN_MISMATCH', status: 409 }));
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '화면의 턴이 지난 턴입니다.',
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(
      await screen.findByRole('heading', { name: '1턴 결과' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '0턴 팀 구성' })).toBeNull();
    expect(seasonCalls).toBeGreaterThanOrEqual(2);
    expect(spy.holdingsCalls).toBeGreaterThanOrEqual(2);
    expect(spy.pricesCalls).toBeGreaterThanOrEqual(2);
  });

  it('shows LEDGER_MISMATCH once, without refetching', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () =>
      problem(409, { code: 'LEDGER_MISMATCH', status: 409 }),
    );
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      '계좌 기록이 맞지 않아 주문을 멈췄습니다.',
    );
    expect(spy.bodies).toHaveLength(1);
    expect(spy.holdingsCalls).toBe(1);
  });

  it('sends one request for a double click and disables the button while pending', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, async () => {
      await delay(50);
      return HttpResponse.json(orderResultFixture(), { status: 201 });
    });
    const { user, dialog } = await openSheet();
    await user.dblClick(
      within(dialog).getByRole('button', { name: '1주 매수' }),
    );
    expect(
      within(dialog).getByRole('button', { name: '매수 중…' }),
    ).toBeDisabled();
    await within(dialog).findByText('체결가');
    expect(spy.bodies).toHaveLength(1);
  });

  it('does not retry a failed order', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () => HttpResponse.error());
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      '결과를 확인하지 못했습니다. 서버의 현재 상태를 다시 불러왔습니다.',
    );
    // The server may have committed: state is asked again, the order is not.
    await waitFor(() => {
      expect(spy.holdingsCalls).toBe(2);
    });
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(spy.bodies).toHaveLength(1);
  });
});

describe('usePlaceOrder', () => {
  it('refreshes season state after a fill even when the caller has unmounted', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    queryClient.setQueryData(holdingsKey(7), holdingsFixture());
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.post(ORDERS, async () => {
        await gate;
        return HttpResponse.json(orderResultFixture(), { status: 201 });
      }),
    );
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    const { result, unmount } = renderHook(() => usePlaceOrder(7), { wrapper });
    act(() => {
      result.current.mutate({
        expectedTurnNo: 0,
        side: 'BUY',
        assetId: 101,
        quantity: 1,
        declaredPosition: 'MIDFIELDER',
      });
    });
    unmount();
    release();
    await waitFor(() => {
      expect(queryClient.getQueryState(holdingsKey(7))?.isInvalidated).toBe(
        true,
      );
    });
  });
});
