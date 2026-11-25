import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { problem } from '../test/authHandlers.ts';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import { alertsOfTurn, feedOfTurn } from '../test/fixtures/feed.ts';
import { turnResultFixture } from '../test/fixtures/turns.ts';
import { heldMidfielder, holdingsFixture } from '../test/fixtures/holdings.ts';
import { orderResultFixture } from '../test/fixtures/orders.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveFeedWorld } from '../test/serveFeed.ts';
import { server } from '../test/server.ts';
import { gate } from '../test/serveTurns.ts';
import { http, HttpResponse } from 'msw';

const BUY_FIRST = '종목 가 매수';

function openWorld(current = 5) {
  const world = serveFeedWorld({ current });
  world.feed = (turnNo) => feedOfTurn(turnNo);
  return world;
}

describe('feed on the now page', () => {
  it('lists the stored items in order and sends the tapped item as the source', async () => {
    const world = openWorld();
    const { container } = renderRoute('/seasons/7');
    const region = await screen.findByRole('region', { name: '소식' });
    expect(
      within(region)
        .getAllByRole('listitem')
        .map((li) => within(li).getByText(/소식:/).textContent),
    ).toEqual([
      '나 소식: 거래가 늘었습니다.',
      '가 소식: 시장이 조용했습니다.',
      '다 소식: 일부에서 급등이 보였습니다.',
    ]);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: BUY_FIRST }));
    const dialog = await screen.findByRole('dialog', { name: '종목 가' });
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    await user.click(within(dialog).getByRole('button', { name: '닫기' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(screen.getByRole('button', { name: BUY_FIRST })).toHaveFocus();
    expect(world.orders).toHaveLength(1);
    expect(world.orders[0]).toStrictEqual({
      expectedTurnNo: 5,
      side: 'BUY',
      assetId: 101,
      quantity: 1,
      declaredPosition: 'MIDFIELDER',
      source: { kind: 'FEED', feedTurnNo: 5, feedSeq: 3 },
    });
    expectNoDatePattern(container);
  });

  it('does not reopen a closed sheet when the turn moves on under it', async () => {
    const world = openWorld();
    const { queryClient } = renderRoute('/seasons/7');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: BUY_FIRST }));
    await screen.findByRole('dialog', { name: '종목 가' });
    const holdingsBefore = world.holdingsGets;
    // Another tab advanced the turn; this screen re-reads the state.
    world.current = 6;
    // The next turn is already cached, so the feed section stays mounted.
    queryClient.setQueryData(
      ['turn', 7, 6],
      turnResultFixture({ turnNo: 6, feed: feedOfTurn(6) }),
    );
    await queryClient.invalidateQueries({ queryKey: ['season', 7] });
    await queryClient.invalidateQueries({ queryKey: ['holdings', 7] });
    await waitFor(() => {
      expect(world.holdingsGets).toBeGreaterThan(holdingsBefore);
    });
    await screen.findByRole('heading', { name: '6턴 결과' });
    await screen.findByRole('button', { name: BUY_FIRST });
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('shows a retry for a failed holdings answer instead of silently dropping the buttons', async () => {
    openWorld();
    server.use(
      http.get('*/api/v1/seasons/7/holdings', () =>
        problem(500, { code: 'INTERNAL_ERROR', status: 500 }),
      ),
    );
    renderRoute('/seasons/7');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '서버에 문제가 생겼습니다.',
    );
    expect(
      screen.getByRole('button', { name: '다시 불러오기' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /매수|매도/ })).toBeNull();
  });

  it('requests no order data when no item names an asset', async () => {
    const world = openWorld();
    world.feed = (turnNo) =>
      feedOfTurn(turnNo).map((item) => ({ ...item, assetId: null }));
    renderRoute('/seasons/7');
    await screen.findByRole('region', { name: '소식' });
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(screen.queryByRole('button', { name: /매수|매도/ })).toBeNull();
    expect(world.holdingsGets).toBe(0);
    expect(world.pricesGets).toEqual([]);
  });

  it('gives no button to an item that names no asset', async () => {
    openWorld();
    renderRoute('/seasons/7');
    await screen.findByRole('button', { name: BUY_FIRST });
    const rows = screen.getAllByRole('listitem');
    const noAsset = rows.find((li) => li.textContent?.includes('가 소식'));
    expect(noAsset).toBeDefined();
    expect(within(noAsset as HTMLElement).queryByRole('button')).toBeNull();
  });

  it('repeats the same source for another purchase of the same asset', async () => {
    const world = openWorld();
    world.orderAnswer = () => {
      world.held = [heldMidfielder];
      return HttpResponse.json(orderResultFixture({ turnNo: 5 }), {
        status: 201,
      });
    };
    renderRoute('/seasons/7');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: BUY_FIRST }));
    const dialog = await screen.findByRole('dialog', { name: '종목 가' });
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    await user.click(
      within(dialog).getByRole('button', { name: '이 종목 더 사기' }),
    );
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await waitFor(() => {
      expect(world.orders).toHaveLength(2);
    });
    expect(world.orders[1]).toMatchObject({
      source: { kind: 'FEED', feedTurnNo: 5, feedSeq: 3 },
    });
  });

  it('shows the refusal in the sheet, keeps it open and never resends without a source', async () => {
    const world = openWorld();
    world.orderAnswer = () =>
      problem(422, { code: 'SOURCE_NOT_VISIBLE', status: 422 });
    renderRoute('/seasons/7');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: BUY_FIRST }));
    const dialog = await screen.findByRole('dialog', { name: '종목 가' });
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      '화면에 보인 항목에서 시작한 주문이 아닙니다.',
    );
    expect(screen.getByRole('dialog', { name: '종목 가' })).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(world.orders).toHaveLength(1);
    expect(world.orders[0]).toHaveProperty('source');
  });

  it('disables the feed order buttons while a turn advance is in flight', async () => {
    const world = openWorld();
    renderRoute('/seasons/7');
    const user = userEvent.setup();
    expect(
      await screen.findByRole('button', { name: BUY_FIRST }),
    ).toBeEnabled();
    const post = gate();
    world.postGate = post.promise;
    await user.click(screen.getByRole('button', { name: '다음 턴 (6턴으로)' }));
    await waitFor(() => {
      expect(screen.getByRole('button', { name: BUY_FIRST })).toBeDisabled();
    });
    post.release();
  });

  it('offers selling only for a held asset and sends the sell without a declared position', async () => {
    const world = openWorld();
    world.held = [heldMidfielder];
    renderRoute('/seasons/7');
    const user = userEvent.setup();
    expect(
      await screen.findByRole('button', { name: '종목 가 매도' }),
    ).toBeEnabled();
    // Asset 102 is not held: buy only.
    expect(screen.getByRole('button', { name: '종목 나 매수' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: '종목 나 매도' })).toBeNull();
    await user.click(screen.getByRole('button', { name: '종목 가 매도' }));
    const dialog = await screen.findByRole('dialog', { name: '종목 가 매도' });
    await user.click(within(dialog).getByRole('button', { name: '1주 매도' }));
    await within(dialog).findByText('체결가');
    expect(world.orders[0]).toStrictEqual({
      expectedTurnNo: 5,
      side: 'SELL',
      assetId: 101,
      quantity: 1,
      source: { kind: 'FEED', feedTurnNo: 5, feedSeq: 3 },
    });
  });

  it('closes the sheet and notes a stale screen on TURN_MISMATCH', async () => {
    const world = openWorld();
    world.orderAnswer = () =>
      problem(409, { code: 'TURN_MISMATCH', status: 409 });
    renderRoute('/seasons/7');
    await screen.findByRole('button', { name: BUY_FIRST });
    const seasonBefore = world.seasonGets;
    const holdingsBefore = world.holdingsGets;
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: BUY_FIRST }));
    const dialog = await screen.findByRole('dialog', { name: '종목 가' });
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(screen.getByRole('alert')).toHaveTextContent(
      '화면의 턴이 지난 턴입니다.',
    );
    // The stale screen re-reads the season and the order data.
    await waitFor(() => {
      expect(world.seasonGets).toBeGreaterThan(seasonBefore);
      expect(world.holdingsGets).toBeGreaterThan(holdingsBefore);
    });
  });

  it('offers no button while the holdings answer is from an earlier turn', async () => {
    openWorld();
    server.use(
      http.get('*/api/v1/seasons/7/holdings', () =>
        HttpResponse.json(holdingsFixture({ turnNo: 4 })),
      ),
    );
    renderRoute('/seasons/7');
    await screen.findByRole('region', { name: '소식' });
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(screen.queryByRole('button', { name: /매수|매도/ })).toBeNull();
  });

  it('shows the no-news sentence and requests no order data for an empty feed', async () => {
    const world = serveFeedWorld({ current: 5 });
    renderRoute('/seasons/7');
    expect(
      await screen.findByText('이번 턴은 소식이 없습니다'),
    ).toBeInTheDocument();
    expect(world.holdingsGets).toBe(0);
    expect(world.pricesGets).toEqual([]);
  });
});

describe.each([
  ['FINISHED', 12],
  ['ABANDONING', 5],
  ['HALTED', 5],
] as const)('feed of a %s season', (status, current) => {
  it('is read-only and requests no holdings or prices', async () => {
    const world = openWorld(current);
    world.status = status;
    const { container } = renderRoute('/seasons/7');
    const region = await screen.findByRole('region', { name: '소식' });
    expect(within(region).getAllByRole('listitem')).toHaveLength(3);
    expect(screen.queryByRole('button', { name: /매수|매도/ })).toBeNull();
    expect(
      screen.queryByRole('button', { name: /다음 턴|턴 넘기|턴을 넘기/ }),
    ).toBeNull();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(world.holdingsGets).toBe(0);
    expect(world.pricesGets).toEqual([]);
    expectNoDatePattern(container);
  });
});

describe('HALTED now page', () => {
  it('says the season is under maintenance and shows the stored turn', async () => {
    const world = openWorld(5);
    world.status = 'HALTED';
    renderRoute('/seasons/7');
    expect(await screen.findByText(/점검 중인 시즌입니다/)).toBeInTheDocument();
    expect(
      await screen.findByRole('heading', { name: '5턴 결과' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', {
        name: /다음 턴|턴 넘기|턴을 넘기|매수|매도/,
      }),
    ).toBeNull();
  });
});

describe('alerts on the now page', () => {
  it('sends the tapped alert as an ALERT source', async () => {
    const world = openWorld();
    world.alerts = (turnNo) => alertsOfTurn(turnNo);
    const { container } = renderRoute('/seasons/7');
    const region = await screen.findByRole('region', { name: '알림' });
    expect(within(region).getAllByRole('listitem')).toHaveLength(2);
    const user = userEvent.setup();
    await user.click(
      await within(region).findByRole('button', { name: '종목 나 매수' }),
    );
    const dialog = await screen.findByRole('dialog', { name: '종목 나' });
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    expect(world.orders).toHaveLength(1);
    expect(world.orders[0]).toStrictEqual({
      expectedTurnNo: 5,
      side: 'BUY',
      assetId: 102,
      quantity: 1,
      declaredPosition: 'FORWARD_BREAKOUT',
      source: { kind: 'ALERT', feedTurnNo: 5, feedSeq: 4 },
    });
    expectNoDatePattern(container);
  });

  it('gives an alert that names no asset no button, and no alert is an ARIA alert', async () => {
    const world = openWorld();
    world.alerts = (turnNo) => alertsOfTurn(turnNo);
    renderRoute('/seasons/7');
    const region = await screen.findByRole('region', { name: '알림' });
    await within(region).findByRole('button', { name: '종목 나 매수' });
    const rows = within(region).getAllByRole('listitem');
    expect(
      within(rows[1] as HTMLElement).queryByRole('button', {
        name: /매수|매도/,
      }),
    ).toBeNull();
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
  });

  it('keeps the no-news sentence for an empty feed with alerts', async () => {
    const world = serveFeedWorld({ current: 5 });
    world.alerts = (turnNo) => alertsOfTurn(turnNo);
    renderRoute('/seasons/7');
    expect(
      await screen.findByText('이번 턴은 소식이 없습니다'),
    ).toBeInTheDocument();
    const news = screen.getByRole('region', { name: '소식' });
    expect(within(news).queryByText(/알림:/)).toBeNull();
    expect(
      within(screen.getByRole('region', { name: '알림' })).getAllByRole(
        'listitem',
      ),
    ).toHaveLength(2);
  });

  it('shows no alert region when the turn has none', async () => {
    openWorld();
    renderRoute('/seasons/7');
    await screen.findByRole('region', { name: '소식' });
    expect(screen.queryByRole('region', { name: '알림' })).toBeNull();
  });

  it('refuses without resending when the alert is not visible', async () => {
    const world = openWorld();
    world.alerts = (turnNo) => alertsOfTurn(turnNo);
    world.orderAnswer = () =>
      problem(422, { code: 'SOURCE_NOT_VISIBLE', status: 422 });
    renderRoute('/seasons/7');
    const user = userEvent.setup();
    const region = await screen.findByRole('region', { name: '알림' });
    await user.click(
      await within(region).findByRole('button', { name: '종목 나 매수' }),
    );
    const dialog = await screen.findByRole('dialog', { name: '종목 나' });
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      '화면에 보인 항목에서 시작한 주문이 아닙니다.',
    );
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(world.orders).toHaveLength(1);
  });
});

describe.each([
  ['FINISHED', 12],
  ['ABANDONING', 5],
  ['HALTED', 5],
] as const)('alerts of a %s season', (status, current) => {
  it('are listed read-only with no order data requested', async () => {
    const world = openWorld(current);
    world.alerts = (turnNo) => alertsOfTurn(turnNo);
    world.status = status;
    renderRoute('/seasons/7');
    const region = await screen.findByRole('region', { name: '알림' });
    expect(within(region).getAllByRole('listitem')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: /매수|매도/ })).toBeNull();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(world.holdingsGets).toBe(0);
  });
});

describe('alert orders and page-level notices', () => {
  it('shows the stale-screen notice when an alert order meets TURN_MISMATCH (empty feed)', async () => {
    const world = serveFeedWorld({ current: 5 });
    world.alerts = (turnNo) => alertsOfTurn(turnNo);
    world.orderAnswer = () =>
      problem(409, { code: 'TURN_MISMATCH', status: 409 });
    renderRoute('/seasons/7');
    const user = userEvent.setup();
    const region = await screen.findByRole('region', { name: '알림' });
    await user.click(
      await within(region).findByRole('button', { name: '종목 나 매수' }),
    );
    const dialog = await screen.findByRole('dialog', { name: '종목 나' });
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(screen.getByRole('alert')).toHaveTextContent(
      '화면의 턴이 지난 턴입니다.',
    );
  });

  it('keeps FEED and ALERT sources apart when both lists are orderable', async () => {
    const world = openWorld();
    world.alerts = (turnNo) => alertsOfTurn(turnNo);
    renderRoute('/seasons/7');
    const user = userEvent.setup();
    const alerts = await screen.findByRole('region', { name: '알림' });
    const news = screen.getByRole('region', { name: '소식' });
    await user.click(
      await within(alerts).findByRole('button', { name: '종목 나 매수' }),
    );
    let dialog = await screen.findByRole('dialog', { name: '종목 나' });
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    await user.click(within(dialog).getByRole('button', { name: '닫기' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    await user.click(
      await within(news).findByRole('button', { name: '종목 가 매수' }),
    );
    dialog = await screen.findByRole('dialog', { name: '종목 가' });
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    expect(world.orders).toHaveLength(2);
    expect(world.orders[0]).toStrictEqual({
      expectedTurnNo: 5,
      side: 'BUY',
      assetId: 102,
      quantity: 1,
      declaredPosition: 'FORWARD_BREAKOUT',
      source: { kind: 'ALERT', feedTurnNo: 5, feedSeq: 4 },
    });
    expect(world.orders[1]).toStrictEqual({
      expectedTurnNo: 5,
      side: 'BUY',
      assetId: 101,
      quantity: 1,
      declaredPosition: 'MIDFIELDER',
      source: { kind: 'FEED', feedTurnNo: 5, feedSeq: 3 },
    });
  });
});

describe('alerts and feed together', () => {
  it('reports a failed order-data load once, outside the alert region', async () => {
    const world = openWorld();
    world.alerts = (turnNo) => alertsOfTurn(turnNo);
    server.use(
      http.get('*/api/v1/seasons/7/holdings', () =>
        problem(500, { code: 'INTERNAL_ERROR', status: 500 }),
      ),
    );
    renderRoute('/seasons/7');
    const alerts = await screen.findByRole('region', { name: '알림' });
    await screen.findByRole('alert');
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(
      screen.getAllByRole('button', { name: '다시 불러오기' }),
    ).toHaveLength(1);
    expect(within(alerts).queryByRole('alert')).toBeNull();
  });

  it('still reports it once when only the alerts can start orders', async () => {
    const world = serveFeedWorld({ current: 5 });
    world.alerts = (turnNo) => alertsOfTurn(turnNo);
    server.use(
      http.get('*/api/v1/seasons/7/holdings', () =>
        problem(500, { code: 'INTERNAL_ERROR', status: 500 }),
      ),
    );
    renderRoute('/seasons/7');
    await screen.findByRole('alert');
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });

  it('places the alerts above the turn result and the feed', async () => {
    const world = openWorld();
    world.alerts = (turnNo) => alertsOfTurn(turnNo);
    renderRoute('/seasons/7');
    const alerts = await screen.findByRole('region', { name: '알림' });
    const news = screen.getByRole('region', { name: '소식' });
    const card = screen.getByRole('region', { name: '턴 결과' });
    expect(
      alerts.compareDocumentPosition(news) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(
      alerts.compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });
});
