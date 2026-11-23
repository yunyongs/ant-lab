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
import { describe, expect, it, vi } from 'vitest';
import { problem } from '../../test/authHandlers.ts';
import {
  heldDefender,
  holdingsAfterBuy,
  holdingsFixture,
} from '../../test/fixtures/holdings.ts';
import {
  orderQuoteFixture,
  orderResultFixture,
} from '../../test/fixtures/orders.ts';
import { pricesFixture } from '../../test/fixtures/prices.ts';
import { seasonFixture } from '../../test/fixtures/seasons.ts';
import { renderRoute } from '../../test/renderWithProviders.tsx';
import { serveSeason } from '../../test/serve.ts';
import { server } from '../../test/server.ts';
import {
  QUOTE_DELAY_MS,
  holdingsKey,
  usePlaceOrder,
  type OrderRequest,
} from './queries.ts';

const ORDERS = '*/api/v1/seasons/7/orders';
const QUOTE = '*/api/v1/seasons/7/orders/quote';

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
    expect(
      (await within(dialog).findAllByText('187,400원')).length,
    ).toBeGreaterThan(0);
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

  it('sends no source key for an order that starts on the trade screen', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () =>
      HttpResponse.json(orderResultFixture(), { status: 201 }),
    );
    const { user, dialog } = await openSheet();
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    expect(spy.bodies).toHaveLength(1);
    expect('source' in (spy.bodies[0] as object)).toBe(false);
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
    expect(within(summary).getByText('96.81% / 목표 40%')).toBeInTheDocument();
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

// Records every quote body; `answer` picks the reply for each.
function serveQuotes(
  answer: (body: OrderRequest, call: number) => Response | Promise<Response>,
) {
  const bodies: OrderRequest[] = [];
  server.use(
    http.post(QUOTE, async ({ request }) => {
      const body = (await request.json()) as OrderRequest;
      bodies.push(body);
      return answer(body, bodies.length);
    }),
  );
  return bodies;
}

function quotePanel(dialog: HTMLElement) {
  return within(dialog).getByRole('region', { name: '주문 미리보기' });
}

async function quoteSettles() {
  await new Promise((resolve) => setTimeout(resolve, QUOTE_DELAY_MS + 100));
}

describe('OrderSheet quote', () => {
  it('shows the server quote in a live region before the order', async () => {
    serveSeason({});
    const spy = serveTeam();
    const quotes = serveQuotes(() => HttpResponse.json(orderQuoteFixture()));
    const { dialog } = await openSheet();
    const panel = quotePanel(dialog);
    const status = within(panel).getByRole('status');
    expect(await within(status).findByText('99,812,572원')).toBeInTheDocument();
    expect(within(status).getByText('예상 체결가')).toBeInTheDocument();
    expect(within(status).getByText('예상 대금')).toBeInTheDocument();
    // One share: the fill price and the amount are the same text.
    expect(within(status).getAllByText('187,400원')).toHaveLength(2);
    expect(within(status).getByText('28원')).toBeInTheDocument();
    expect(within(status).queryByText('그중 환전 비용')).toBeNull();
    expect(
      within(dialog).getByRole('button', { name: '최대 (533주)' }),
    ).toBeEnabled();
    expect(quotes).toEqual([
      {
        expectedTurnNo: 0,
        side: 'BUY',
        assetId: 101,
        quantity: 1,
        declaredPosition: 'MIDFIELDER',
      },
    ]);
    expect(spy.bodies).toHaveLength(0);
  });

  it('shows the FX part of the fee when the server sends one', async () => {
    serveSeason({});
    serveTeam();
    serveQuotes(() =>
      HttpResponse.json(
        orderQuoteFixture({
          fillPrice: '648000',
          notional: '1296000',
          fee: '8424',
          fxCost: '5184',
          cashAfter: '98695576',
          maxBuyQuantity: 45,
        }),
      ),
    );
    const { dialog } = await openSheet();
    const status = within(quotePanel(dialog)).getByRole('status');
    expect(await within(status).findByText('8,424원')).toBeInTheDocument();
    expect(within(status).getByText('그중 환전 비용')).toBeInTheDocument();
    expect(within(status).getByText('5,184원')).toBeInTheDocument();
  });

  it('waits for typing to pause and asks once for the final quantity', async () => {
    serveSeason({});
    serveTeam();
    const quotes = serveQuotes(({ quantity }) =>
      HttpResponse.json(
        orderQuoteFixture({ cashAfter: `9${String(quantity)}` }),
      ),
    );
    const { user, dialog } = await openSheet();
    const status = within(quotePanel(dialog)).getByRole('status');
    await within(status).findByText('91원');
    await setQuantity(user, dialog, '123');
    expect(
      within(status).getByText('미리보기를 계산하는 중…'),
    ).toBeInTheDocument();
    expect(await within(status).findByText('9,123원')).toBeInTheDocument();
    await quoteSettles();
    expect(quotes.map((body) => body.quantity)).toEqual([1, 123]);
  });

  it('ignores a slow answer for a quantity that is no longer on screen', async () => {
    serveSeason({});
    serveTeam();
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    serveQuotes(async ({ quantity }) => {
      if (quantity === 2) await gate;
      return HttpResponse.json(
        orderQuoteFixture({ cashAfter: `7${String(quantity)}` }),
      );
    });
    const { user, dialog } = await openSheet();
    const status = within(quotePanel(dialog)).getByRole('status');
    await within(status).findByText('71원');
    await setQuantity(user, dialog, '2');
    await quoteSettles();
    await setQuantity(user, dialog, '3');
    expect(await within(status).findByText('73원')).toBeInTheDocument();
    release();
    await quoteSettles();
    expect(within(status).getByText('73원')).toBeInTheDocument();
    expect(within(status).queryByText('72원')).toBeNull();
  });

  it('shows a cash refusal inline with the last max and keeps the order button', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () =>
      HttpResponse.json(orderResultFixture(), { status: 201 }),
    );
    const quotes = serveQuotes(({ quantity }) =>
      quantity > 533
        ? problem(422, {
            code: 'INSUFFICIENT_CASH',
            status: 422,
            detail: '12,345원',
          })
        : HttpResponse.json(orderQuoteFixture()),
    );
    const { user, dialog } = await openSheet();
    await within(dialog).findByRole('button', { name: '최대 (533주)' });
    await setQuantity(user, dialog, '999');
    const status = within(quotePanel(dialog)).getByRole('status');
    expect(
      await within(status).findByText(
        '현금이 모자랍니다(대금과 수수료). 지금은 최대 533주까지 살 수 있습니다.',
      ),
    ).toBeInTheDocument();
    expect(status).not.toHaveTextContent('12,345');
    expect(within(dialog).queryByRole('alert')).toBeNull();
    expect(
      within(dialog).getByRole('button', { name: '999주 매수' }),
    ).toBeEnabled();
    await quoteSettles();
    // No retry and no probe: the max was already known.
    expect(quotes.map((body) => body.quantity)).toEqual([1, 999]);
    expect(spy.bodies).toHaveLength(0);
  });

  it('asks a one-share quote for the max when none is known yet', async () => {
    serveSeason({});
    serveTeam();
    const quotes = serveQuotes(({ quantity }, call) => {
      if (call === 1) return HttpResponse.error();
      return quantity > 533
        ? problem(422, { code: 'INSUFFICIENT_CASH', status: 422 })
        : HttpResponse.json(orderQuoteFixture());
    });
    const { user, dialog } = await openSheet();
    const status = within(quotePanel(dialog)).getByRole('status');
    expect(
      await within(status).findByText(
        '미리보기를 불러오지 못했습니다. 주문은 그대로 할 수 있습니다.',
      ),
    ).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '최대' })).toBeDisabled();
    await setQuantity(user, dialog, '999');
    expect(
      await within(dialog).findByRole('button', { name: '최대 (533주)' }),
    ).toBeEnabled();
    await quoteSettles();
    expect(quotes.map((body) => body.quantity)).toEqual([1, 999, 1]);
  });

  it('reads a refused one-share quote as a max of zero', async () => {
    serveSeason({});
    serveTeam();
    const quotes = serveQuotes(() =>
      problem(422, { code: 'INSUFFICIENT_CASH', status: 422 }),
    );
    const { dialog } = await openSheet();
    expect(
      await within(dialog).findByRole('button', { name: '최대 (0주)' }),
    ).toBeDisabled();
    expect(
      within(quotePanel(dialog)).getByText(
        '현금이 모자랍니다(대금과 수수료). 지금 현금으로는 1주도 살 수 없습니다.',
      ),
    ).toBeInTheDocument();
    await quoteSettles();
    expect(quotes).toHaveLength(1);
  });

  it('sets the quantity to the max and quotes it', async () => {
    serveSeason({});
    serveTeam();
    const quotes = serveQuotes(({ quantity }) =>
      HttpResponse.json(
        orderQuoteFixture({ cashAfter: `5${String(quantity)}` }),
      ),
    );
    const { user, dialog } = await openSheet();
    await user.click(
      await within(dialog).findByRole('button', { name: '최대 (533주)' }),
    );
    expect(within(dialog).getByRole('textbox', { name: '수량' })).toHaveValue(
      '533',
    );
    const status = within(quotePanel(dialog)).getByRole('status');
    expect(await within(status).findByText('5,533원')).toBeInTheDocument();
    expect(
      within(dialog).getByRole('button', { name: '533주 매수' }),
    ).toBeEnabled();
    expect(quotes.map((body) => body.quantity)).toEqual([1, 533]);
  });

  it('sends the order exactly as before and shows the fill, not the quote', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () =>
      HttpResponse.json(orderResultFixture(), { status: 201 }),
    );
    const quotes = serveQuotes(() =>
      HttpResponse.json(orderQuoteFixture({ cashAfter: '11111111' })),
    );
    const { user, dialog } = await openSheet();
    await setQuantity(user, dialog, '13');
    await within(quotePanel(dialog)).findByText('11,111,111원');
    await user.click(within(dialog).getByRole('button', { name: '13주 매수' }));
    expect(await within(dialog).findByText('97,563,435원')).toBeInTheDocument();
    expect(within(dialog).queryByText('11,111,111원')).toBeNull();
    expect(
      within(dialog).queryByRole('region', { name: '주문 미리보기' }),
    ).toBeNull();
    expect(spy.bodies).toEqual([
      {
        expectedTurnNo: 0,
        side: 'BUY',
        assetId: 101,
        quantity: 13,
        declaredPosition: 'MIDFIELDER',
      },
    ]);
    expect(quotes.map((body) => body.quantity)).toEqual([1, 13]);
  });

  it('lets the order go out while the quote is refused', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () =>
      HttpResponse.json(orderResultFixture(), { status: 201 }),
    );
    serveQuotes(() => problem(409, { code: 'LEDGER_MISMATCH', status: 409 }));
    const { user, dialog } = await openSheet();
    await within(quotePanel(dialog)).findByText(
      '계좌 기록이 맞지 않아 주문을 멈췄습니다.',
    );
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    expect(spy.bodies).toHaveLength(1);
  });

  it('asks a fresh quote after buying the same asset again', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () =>
      HttpResponse.json(orderResultFixture(), { status: 201 }),
    );
    const quotes = serveQuotes((_body, call) =>
      HttpResponse.json(
        orderQuoteFixture(
          call === 1 ? {} : { cashAfter: '97376007', maxBuyQuantity: 519 },
        ),
      ),
    );
    const { user, dialog } = await openSheet();
    await within(dialog).findByRole('button', { name: '최대 (533주)' });
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    await user.click(
      within(dialog).getByRole('button', { name: '이 종목 더 사기' }),
    );
    expect(
      await within(quotePanel(dialog)).findByText('97,376,007원'),
    ).toBeInTheDocument();
    expect(
      within(dialog).getByRole('button', { name: '최대 (519주)' }),
    ).toBeEnabled();
    expect(quotes.length).toBeGreaterThanOrEqual(2);
    // The held asset keeps its position: the new quote sends none.
    expect(quotes[quotes.length - 1]).toEqual({
      expectedTurnNo: 0,
      side: 'BUY',
      assetId: 101,
      quantity: 1,
    });
  });

  it('asks no quote while the fill is being shown', async () => {
    serveSeason({});
    const spy = serveTeam();
    serveOrder(spy, () =>
      HttpResponse.json(orderResultFixture(), { status: 201 }),
    );
    const quotes = serveQuotes(() => HttpResponse.json(orderQuoteFixture()));
    const { user, dialog } = await openSheet();
    await within(quotePanel(dialog)).findByText('99,812,572원');
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    await waitFor(() => {
      expect(spy.holdingsCalls).toBe(2);
    });
    await quoteSettles();
    expect(quotes).toHaveLength(1);
  });

  it('marks the live region busy while a quote is computed', async () => {
    serveSeason({});
    serveTeam();
    serveQuotes(() => HttpResponse.json(orderQuoteFixture()));
    const { user, dialog } = await openSheet();
    const status = within(quotePanel(dialog)).getByRole('status');
    await within(status).findByText('99,812,572원');
    expect(status).toHaveAttribute('aria-busy', 'false');
    await setQuantity(user, dialog, '2');
    expect(status).toHaveAttribute('aria-busy', 'true');
    expect(within(status).getByText('미리보기를 계산하는 중…')).toHaveAttribute(
      'aria-hidden',
      'true',
    );
    await waitFor(() => {
      expect(status).toHaveAttribute('aria-busy', 'false');
    });
    expect(within(status).getByText('99,812,572원')).toBeInTheDocument();
  });

  it('groups the digits of a large max', async () => {
    serveSeason({});
    serveTeam();
    serveQuotes(({ quantity }) =>
      quantity > 99_985
        ? problem(422, { code: 'INSUFFICIENT_CASH', status: 422 })
        : HttpResponse.json(orderQuoteFixture({ maxBuyQuantity: 99_985 })),
    );
    const { user, dialog } = await openSheet();
    await within(dialog).findByRole('button', { name: '최대 (99,985주)' });
    await setQuantity(user, dialog, '100000');
    expect(
      await within(quotePanel(dialog)).findByText(
        '현금이 모자랍니다(대금과 수수료). 지금은 최대 99,985주까지 살 수 있습니다.',
      ),
    ).toBeInTheDocument();
  });

  it('forgets the max and quotes again when the cash on screen changes', async () => {
    serveSeason({});
    let holdingsCalls = 0;
    server.use(
      http.get('*/api/v1/seasons/7/holdings', () => {
        holdingsCalls += 1;
        return HttpResponse.json(
          holdingsCalls === 1
            ? holdingsFixture()
            : holdingsFixture({ cashBalance: '50000000' }),
        );
      }),
    );
    const orders: unknown[] = [];
    server.use(
      http.post(ORDERS, async ({ request }) => {
        orders.push(await request.json());
        return HttpResponse.error();
      }),
    );
    const quotes = serveQuotes((_body, call) =>
      HttpResponse.json(
        orderQuoteFixture(
          call === 1 ? {} : { cashAfter: '49812572', maxBuyQuantity: 266 },
        ),
      ),
    );
    const { user, dialog } = await openSheet();
    await within(dialog).findByRole('button', { name: '최대 (533주)' });
    // An unreadable answer refreshes the season state; the sheet stays open.
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByRole('alert');
    expect(
      await within(dialog).findByRole('button', { name: '최대 (266주)' }),
    ).toBeEnabled();
    expect(
      within(quotePanel(dialog)).getByText('49,812,572원'),
    ).toBeInTheDocument();
    expect(quotes).toHaveLength(2);
    expect(quotes[0]).toEqual(quotes[1]);
    expect(orders).toHaveLength(1);
  });

  it('hides an older answer that lands while the next quantity waits', async () => {
    serveSeason({});
    serveTeam();
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    serveQuotes(async ({ quantity }) => {
      if (quantity === 2) await gate;
      return HttpResponse.json(
        orderQuoteFixture({ cashAfter: `7${String(quantity)}` }),
      );
    });
    const { user, dialog } = await openSheet();
    const status = within(quotePanel(dialog)).getByRole('status');
    await within(status).findByText('71원');
    await setQuantity(user, dialog, '2');
    await quoteSettles();
    await setQuantity(user, dialog, '3');
    release();
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(within(status).queryByText('72원')).toBeNull();
    expect(
      within(status).getByText('미리보기를 계산하는 중…'),
    ).toBeInTheDocument();
    expect(await within(status).findByText('73원')).toBeInTheDocument();
    expect(within(status).queryByText('72원')).toBeNull();
  });

  it('re-quotes a new asset with the changed position after the pause', async () => {
    serveSeason({});
    serveTeam();
    const quotes = serveQuotes(() => HttpResponse.json(orderQuoteFixture()));
    const { user, dialog } = await openSheet();
    const status = within(quotePanel(dialog)).getByRole('status');
    await within(status).findByText('99,812,572원');
    await user.selectOptions(
      within(dialog).getByRole('combobox', { name: /선언 포지션/ }),
      'FORWARD_TARGET',
    );
    expect(
      within(status).getByText('미리보기를 계산하는 중…'),
    ).toBeInTheDocument();
    expect(quotes).toHaveLength(1);
    await within(status).findByText('99,812,572원');
    expect(quotes.map((body) => body.declaredPosition)).toEqual([
      'MIDFIELDER',
      'FORWARD_TARGET',
    ]);
  });

  it('sends nothing more and warns nothing when closed during a quote', async () => {
    serveSeason({});
    serveTeam();
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {
      // Collected below.
    });
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const quotes = serveQuotes(async () => {
      await gate;
      return HttpResponse.json(orderQuoteFixture());
    });
    try {
      const { user, dialog } = await openSheet();
      await waitFor(() => {
        expect(quotes).toHaveLength(1);
      });
      await user.click(within(dialog).getByRole('button', { name: '취소' }));
      await waitFor(() => {
        expect(screen.queryByRole('dialog')).toBeNull();
      });
      release();
      await quoteSettles();
      expect(quotes).toHaveLength(1);
      expect(errors).not.toHaveBeenCalled();
    } finally {
      errors.mockRestore();
    }
  });

  it('asks no quote for an invalid quantity', async () => {
    serveSeason({});
    serveTeam();
    const quotes = serveQuotes(() => HttpResponse.json(orderQuoteFixture()));
    const { user, dialog } = await openSheet();
    await within(quotePanel(dialog)).findByText('99,812,572원');
    await setQuantity(user, dialog, '0');
    expect(
      within(quotePanel(dialog)).getByText(
        '수량을 정하면 미리보기가 나옵니다.',
      ),
    ).toBeInTheDocument();
    await quoteSettles();
    expect(quotes).toHaveLength(1);
  });
});
