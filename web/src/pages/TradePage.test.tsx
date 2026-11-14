import {
  act,
  fireEvent,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../test/authHandlers.ts';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import {
  heldMidfielder,
  holdingsAfterPartSell,
  holdingsAllSold,
  holdingsFixture,
  holdingsWithMidfielder,
} from '../test/fixtures/holdings.ts';
import { orderResultFixture } from '../test/fixtures/orders.ts';
import { pricesFixture } from '../test/fixtures/prices.ts';
import { invalidateSeasonState } from '../features/seasons/queries.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveSeason } from '../test/serve.ts';
import { server } from '../test/server.ts';
import { failWithProblem, serveTurnWorld } from '../test/serveTurns.ts';

const ORDERS = '*/api/v1/seasons/7/orders';

interface Spy {
  bodies: unknown[];
  holdingsCalls: number;
  seasonCalls: number;
}

// Holdings: the first answer is `first`, later ones `later`.
function serveTrade(
  first = holdingsWithMidfielder,
  later = holdingsAfterPartSell,
): Spy {
  const spy: Spy = { bodies: [], holdingsCalls: 0, seasonCalls: 0 };
  server.use(
    http.get('*/api/v1/seasons/7/holdings', () => {
      spy.holdingsCalls += 1;
      return HttpResponse.json(spy.holdingsCalls === 1 ? first : later);
    }),
    http.get('*/api/v1/seasons/7/prices', () =>
      HttpResponse.json(pricesFixture(1)),
    ),
  );
  return spy;
}

function serveSell(spy: Spy, answer: () => Response | Promise<Response>) {
  server.use(
    http.post(ORDERS, async ({ request }) => {
      spy.bodies.push(await request.json());
      return answer();
    }),
  );
}

const sold = (overrides = {}) =>
  HttpResponse.json(
    orderResultFixture({
      turnNo: 1,
      side: 'SELL',
      quantity: 20,
      fillPrice: '74200',
      avgPrice: '71350.0000',
      realizedPnl: '137000.0000',
      holdingQuantity: 100,
      ...overrides,
    }),
    { status: 201 },
  );

async function openSell() {
  const user = userEvent.setup();
  const rendered = renderRoute('/seasons/7/trade');
  await user.click(await screen.findByRole('button', { name: '종목 가 매도' }));
  const dialog = await screen.findByRole('dialog', { name: '종목 가 매도' });
  return { user, dialog, ...rendered };
}

describe('TradePage', () => {
  it('shows the live summary and the holding cards from server values', async () => {
    serveSeason({ currentTurnNo: 1 });
    serveTrade();
    const { container } = renderRoute('/seasons/7/trade');
    expect(
      await screen.findByRole('heading', { name: '1턴 매매' }),
    ).toBeInTheDocument();
    const summary = await screen.findByRole('region', { name: '매매 요약' });
    expect(within(summary).getByText('91,096,000원')).toBeInTheDocument();
    expect(
      within(summary).getByText('총 평가액 (1턴 가격 기준)'),
    ).toBeInTheDocument();
    expect(
      within(summary).getByText(
        '골키퍼 40 · 수비수 30 · 미드필더 20 · 공격수 10',
      ),
    ).toBeInTheDocument();
    expect(within(summary).getByText('1.20000000 (120%p)')).toBeInTheDocument();
    const list = screen.getByRole('region', { name: '보유 종목' });
    expect(within(list).getByText('보유 종목 (1)')).toBeInTheDocument();
    expect(within(list).getByText('종목 가')).toBeInTheDocument();
    expect(within(list).getByText('71,350.0000원')).toBeInTheDocument();
    expect(within(list).getByText('8.904%')).toBeInTheDocument();
    expect(within(list).getByText('74,200원')).toBeInTheDocument();
    expect(within(list).getByText('8,904,000원')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '종목 가 매수' }),
    ).toBeInTheDocument();
    expectNoDatePattern(container);
  });

  it('names an unknown asset without printing its id', async () => {
    serveSeason({ currentTurnNo: 1 });
    serveTrade(
      holdingsFixture({
        turnNo: 1,
        holdings: [{ ...heldMidfielder, assetId: 99123 }],
      }),
    );
    const { container } = renderRoute('/seasons/7/trade');
    expect(await screen.findByText('이름을 찾지 못한 종목')).toBeVisible();
    expect(container.innerHTML).not.toContain('99123');
  });

  it('shows the empty state with the pool section open', async () => {
    serveSeason({ currentTurnNo: 1 });
    serveTrade(holdingsAllSold);
    renderRoute('/seasons/7/trade');
    expect(await screen.findByText('보유 종목이 없습니다')).toBeVisible();
    const summary = screen.getByText('종목 풀에서 새로 사기');
    expect(summary.closest('details')).toHaveAttribute('open');
  });

  it('opens the pool section on demand when something is held', async () => {
    serveSeason({ currentTurnNo: 1 });
    serveTrade();
    const user = userEvent.setup();
    renderRoute('/seasons/7/trade');
    const summary = await screen.findByText('종목 풀에서 새로 사기');
    const details = summary.closest('details');
    expect(details).not.toHaveAttribute('open');
    await user.click(summary);
    expect(details).toHaveAttribute('open');
  });

  it('blocks a finished season and requests no holdings or prices', async () => {
    serveSeason({ status: 'FINISHED', currentTurnNo: 12 });
    let requests = 0;
    server.use(
      http.get('*/api/v1/seasons/7/holdings', () => {
        requests += 1;
        return HttpResponse.json(holdingsFixture());
      }),
      http.get('*/api/v1/seasons/7/prices', () => {
        requests += 1;
        return HttpResponse.json(pricesFixture(12));
      }),
    );
    renderRoute('/seasons/7/trade');
    expect(
      await screen.findByText('끝난 시즌은 매매할 수 없습니다.'),
    ).toBeVisible();
    expect(
      screen.getByRole('link', { name: '시즌으로 돌아가기' }),
    ).toBeVisible();
    await delay(20);
    expect(requests).toBe(0);
  });

  it('shows not found for a bad season id without any request', async () => {
    let requests = 0;
    server.use(
      http.get('*/api/v1/seasons/*', () => {
        requests += 1;
        return HttpResponse.json({});
      }),
    );
    renderRoute('/seasons/x/trade');
    expect(
      await screen.findByText(/찾을 수 없습니다|없는 페이지/),
    ).toBeVisible();
    expect(requests).toBe(0);
  });

  it('allows trading at turn 0', async () => {
    serveSeason({ currentTurnNo: 0 });
    const spy = serveTrade(holdingsFixture({ holdings: [heldMidfielder] }));
    serveSell(spy, () => sold({ turnNo: 0 }));
    const user = userEvent.setup();
    renderRoute('/seasons/7/trade');
    expect(
      await screen.findByRole('heading', { name: '0턴 매매 (팀 구성 중)' }),
    ).toBeVisible();
    await user.click(
      await screen.findByRole('button', { name: '종목 가 매도' }),
    );
    const dialog = await screen.findByRole('dialog', { name: '종목 가 매도' });
    await user.click(within(dialog).getByRole('button', { name: '1주 매도' }));
    await within(dialog).findByText('체결가');
    expect(spy.bodies[0]).toEqual({
      expectedTurnNo: 0,
      side: 'SELL',
      assetId: 101,
      quantity: 1,
    });
  });

  it('disables every trade control while a turn advance is in flight', async () => {
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
      await screen.findByRole('button', { name: '종목 가 매수' }),
    ).toBeDisabled();
    expect(screen.getByRole('button', { name: '종목 가 매도' })).toBeDisabled();
    expect(
      screen.queryByRole('link', { name: '전술 변경' }),
    ).not.toBeInTheDocument();
    expect(screen.getByText('전술 변경')).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    await user.click(screen.getByText('종목 풀에서 새로 사기'));
    expect(
      await screen.findByRole('button', { name: '종목 나 선택' }),
    ).toBeDisabled();
  });
});

describe('formation entry', () => {
  it('links to the formation page from turn 1 on', async () => {
    serveSeason({ currentTurnNo: 1 });
    serveTrade();
    renderRoute('/seasons/7/trade');
    expect(
      await screen.findByRole('link', { name: '전술 변경' }),
    ).toHaveAttribute('href', '/seasons/7/formation');
  });

  it('is closed text at turn 0 with the reason, not a link', async () => {
    serveSeason({ currentTurnNo: 0 });
    serveTrade(holdingsFixture());
    renderRoute('/seasons/7/trade');
    const entry = await screen.findByText('전술 변경 (1턴부터)');
    expect(entry).toHaveAttribute('aria-disabled', 'true');
    expect(screen.queryByRole('link', { name: /전술 변경/ })).toBeNull();
    expect(
      screen.getByText(
        '전술은 1턴부터 바꿀 수 있습니다. 0턴에는 팀 구성만 합니다.',
      ),
    ).toBeVisible();
  });
});

describe('turn change', () => {
  it('waits for the new turn instead of showing the cached earlier turn', async () => {
    serveSeason({ currentTurnNo: 1 });
    serveTrade();
    const { queryClient, router } = renderRoute('/seasons/7/trade');
    expect(await screen.findByText('91,096,000원')).toBeVisible();
    // The turn advances while another screen is open: nothing refetches here.
    await act(async () => {
      await router.navigate('/nowhere');
    });
    serveSeason({ currentTurnNo: 2 });
    server.use(
      http.get('*/api/v1/seasons/7/holdings', async () => {
        await delay(100);
        return HttpResponse.json(
          holdingsFixture({
            ...holdingsWithMidfielder,
            turnNo: 2,
            cashBalance: '95000000',
          }),
        );
      }),
      http.get('*/api/v1/seasons/7/prices', () =>
        HttpResponse.json(pricesFixture(2)),
      ),
    );
    await act(async () => {
      await invalidateSeasonState(queryClient, 7);
    });
    await act(async () => {
      await router.navigate('/seasons/7/trade');
    });
    expect(
      await screen.findByText('보유 종목을 불러오는 중입니다…'),
    ).toBeVisible();
    expect(screen.queryByText('91,096,000원')).toBeNull();
    expect(await screen.findByText('95,000,000원')).toBeVisible();
  });
});

describe('sell sheet', () => {
  it('has no position control and limits the quantity to the holding', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    const { user, dialog } = await openSell();
    expect(within(dialog).queryByRole('combobox')).toBeNull();
    expect(within(dialog).getByText('보유 120주')).toBeVisible();
    const input = within(dialog).getByRole('textbox', { name: '수량' });
    const sell = () => within(dialog).getByRole('button', { name: /주 매도$/ });
    for (const text of ['', '0', '1.5', '121']) {
      await user.clear(input);
      if (text !== '') await user.type(input, text);
      expect(sell()).toBeDisabled();
      expect(
        within(dialog).getByText('보유 수량(120주) 이하의 정수로 적어 주세요'),
      ).toBeVisible();
    }
    await user.click(
      within(dialog).getByRole('button', { name: '전량 (120주)' }),
    );
    expect(input).toHaveValue('120');
    expect(sell()).toBeEnabled();
    expect(spy.bodies).toHaveLength(0);
  });

  it('sends the exact sell body and shows the fill rows from the server', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    serveSell(spy, () => sold({ formationChangeId: 41 }));
    const { user, dialog } = await openSell();
    await user.clear(within(dialog).getByRole('textbox', { name: '수량' }));
    await user.type(
      within(dialog).getByRole('textbox', { name: '수량' }),
      '20',
    );
    await user.click(within(dialog).getByRole('button', { name: '20주 매도' }));
    expect(await within(dialog).findByText('137,000.0000원')).toBeVisible();
    expect(within(dialog).getByText('71,350.0000원')).toBeVisible();
    expect(
      within(dialog).getByText(
        '이번 턴 전술 변경에 묶인 주문입니다 (변경 번호 41)',
      ),
    ).toBeVisible();
    expect(spy.bodies).toEqual([
      { expectedTurnNo: 1, side: 'SELL', assetId: 101, quantity: 20 },
    ]);
    expect('declaredPosition' in (spy.bodies[0] as object)).toBe(false);
    // The list shows the refetched numbers, not anything the browser derived.
    await waitFor(() => {
      expect(spy.holdingsCalls).toBe(2);
    });
    await user.click(within(dialog).getByRole('button', { name: '닫기' }));
    expect(await screen.findByText('7.442223%')).toBeVisible();
    expect(screen.getByText('100주')).toBeVisible();
  });

  it('omits the tied-order row when there is no formation change and hides a null P&L', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    serveSell(spy, () => sold({ realizedPnl: null }));
    const { user, dialog } = await openSell();
    await user.click(within(dialog).getByRole('button', { name: '1주 매도' }));
    await within(dialog).findByText('체결가');
    expect(within(dialog).queryByText(/전술 변경에 묶인/)).toBeNull();
    expect(within(dialog).queryByText('실현 손익')).toBeNull();
  });

  it('shows the closed span after selling everything and offers only close', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade(holdingsWithMidfielder, holdingsAllSold);
    serveSell(spy, () => sold({ quantity: 120, holdingQuantity: 0 }));
    const { user, dialog } = await openSell();
    await user.click(
      within(dialog).getByRole('button', { name: '전량 (120주)' }),
    );
    await user.click(
      within(dialog).getByRole('button', { name: '120주 매도' }),
    );
    expect(
      await within(dialog).findByText('보유 0주 · 이 종목의 구간이 닫혔습니다'),
    ).toBeVisible();
    expect(within(dialog).getByRole('button', { name: '닫기' })).toBeVisible();
    expect(
      within(dialog).queryByRole('button', { name: /더 팔기/ }),
    ).toBeNull();
    // The card is gone after the refetch: focus falls back to the heading.
    await waitFor(() => {
      expect(spy.holdingsCalls).toBe(2);
    });
    await user.click(within(dialog).getByRole('button', { name: '닫기' }));
    await waitFor(() => {
      expect(screen.getByText('보유 종목 (0)')).toHaveFocus();
    });
  });

  it('returns focus to the opening button when the sheet is closed', async () => {
    serveSeason({ currentTurnNo: 1 });
    serveTrade();
    const { user, dialog } = await openSell();
    await user.click(within(dialog).getByRole('button', { name: '취소' }));
    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: '종목 가 매도' }),
      ).toHaveFocus();
    });
  });

  it('sends one order when the form is submitted again while one is pending', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    serveSell(spy, async () => {
      await delay(100);
      return sold();
    });
    const { user, dialog } = await openSell();
    await user.type(
      within(dialog).getByRole('textbox', { name: '수량' }),
      '{Enter}',
    );
    // The submit button is disabled now; submit the form directly anyway.
    await within(dialog).findByRole('button', { name: /매도 중/ });
    fireEvent.submit(dialog.querySelector('form') as HTMLFormElement);
    await within(dialog).findByText('체결가');
    expect(spy.bodies).toHaveLength(1);
  });

  it('explains INSUFFICIENT_HOLDING, keeps the input and refetches holdings', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    serveSell(spy, () =>
      problem(422, { code: 'INSUFFICIENT_HOLDING', status: 422 }),
    );
    const { user, dialog } = await openSell();
    await user.click(
      within(dialog).getByRole('button', { name: '전량 (120주)' }),
    );
    await user.click(
      within(dialog).getByRole('button', { name: '120주 매도' }),
    );
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      '보유 수량보다 많이 팔 수 없습니다.',
    );
    expect(within(dialog).getByRole('textbox', { name: '수량' })).toHaveValue(
      '120',
    );
    await waitFor(() => {
      expect(spy.holdingsCalls).toBe(2);
    });
    expect(spy.bodies).toHaveLength(1);
  });

  it('keeps the fill result when the holdings refetch after an order fails', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    server.use(
      http.get('*/api/v1/seasons/7/holdings', () => {
        spy.holdingsCalls += 1;
        return spy.holdingsCalls === 1
          ? HttpResponse.json(holdingsWithMidfielder)
          : failWithProblem(500, 'INTERNAL')();
      }),
    );
    serveSell(spy, () => sold());
    const { user, dialog } = await openSell();
    await user.click(within(dialog).getByRole('button', { name: '1주 매도' }));
    await within(dialog).findByText('체결가');
    await waitFor(() => {
      expect(spy.holdingsCalls).toBe(2);
    });
    expect(await screen.findByText('다시 불러오기')).toBeVisible();
    expect(dialog).toBeVisible();
    expect(within(dialog).getByText('체결가')).toBeVisible();
    expect(
      within(screen.getByRole('region', { name: '보유 종목' })).getByText(
        '종목 가',
      ),
    ).toBeVisible();
  });

  it('closes the sheet and notes a stale screen on TURN_MISMATCH', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    serveSell(spy, failWithProblem(409, 'TURN_MISMATCH'));
    const { user, dialog } = await openSell();
    await user.click(within(dialog).getByRole('button', { name: '1주 매도' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(screen.getByRole('alert')).toHaveTextContent(
      '화면의 턴이 지난 턴입니다.',
    );
  });

  it('says the outcome is unknown after a network error and refetches the state', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    const counter = { seasons: 0 };
    server.use(
      http.get('*/api/v1/seasons/7', () => {
        counter.seasons += 1;
        return HttpResponse.json({
          id: 7,
          status: 'IN_PROGRESS',
          currentTurnNo: 1,
          pathLength: 12,
          riskLimit: '-0.20000000',
          startFormation: {
            goalkeeper: 40,
            defender: 30,
            midfielder: 20,
            forward: 10,
          },
          currentFormation: {
            goalkeeper: 40,
            defender: 30,
            midfielder: 20,
            forward: 10,
          },
          initialCash: '100000000',
          cashBalance: '91096000',
          feeRateKr: '0.00015000',
          feeRateUs: '0.00025000',
          fxSpreadRate: '0.00400000',
        });
      }),
    );
    serveSell(spy, () => HttpResponse.error());
    const { user, dialog } = await openSell();
    await user.click(within(dialog).getByRole('button', { name: '1주 매도' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      '결과를 확인하지 못했습니다. 서버의 현재 상태를 다시 불러왔습니다.',
    );
    await waitFor(() => {
      expect(spy.holdingsCalls).toBe(2);
    });
    await waitFor(() => {
      expect(counter.seasons).toBeGreaterThanOrEqual(2);
    });
    expect(spy.bodies).toHaveLength(1);
  });

  it('keeps ordering possible when the price list fails and says why', async () => {
    serveSeason({ currentTurnNo: 1 });
    const spy = serveTrade();
    server.use(
      http.get('*/api/v1/seasons/7/prices', () =>
        problem(500, { code: 'INTERNAL_ERROR', status: 500 }),
      ),
    );
    serveSell(spy, () => sold());
    const { user, dialog } = await openSell();
    expect(
      await within(dialog).findByText(
        '가격을 불러오지 못했습니다. 체결가는 서버가 정합니다.',
      ),
    ).toBeVisible();
    await user.click(within(dialog).getByRole('button', { name: '1주 매도' }));
    await within(dialog).findByText('체결가');
    expect(spy.bodies).toHaveLength(1);
  });

  it('leaks no date or block number from poisoned order and holdings data', async () => {
    serveSeason({ currentTurnNo: 1 });
    const poisoned = {
      ...holdingsWithMidfielder,
      yearMonth: '2008-09',
      monthBlock: 987654,
      seed: 'SEED-XYZ',
    };
    const spy = serveTrade(poisoned);
    serveSell(spy, () =>
      sold({
        yearMonth: '2008-09',
        monthBlock: 987654,
        seed: 'SEED-XYZ',
      }),
    );
    const { user, dialog, container } = await openSell();
    await user.click(within(dialog).getByRole('button', { name: '1주 매도' }));
    await within(dialog).findByText('체결가');
    for (const root of [container, dialog]) {
      expectNoDatePattern(root);
      for (const leak of ['2008-09', '987654', 'SEED-XYZ']) {
        expect(root.innerHTML).not.toContain(leak);
      }
    }
  });
});
