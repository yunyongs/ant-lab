import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  feedOfReviewTurn,
  reviewEvent,
  reviewFeedItem,
  reviewFixture,
  reviewTurn,
} from '../test/fixtures/review.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveReview } from '../test/serveReview.ts';

const NEVER_SHOWN = '포기 뒤 자동 진행 — 화면에 보이지 않았습니다';

function turnRow(turnNo: number): HTMLElement {
  const heading = screen.getByRole('heading', { level: 2, name: '턴별 복기' });
  const section = heading.closest('section');
  if (section === null) throw new Error('no turn section');
  const label = within(section).getByText(`${String(turnNo)}턴`);
  const row = label.closest('li');
  if (row === null) throw new Error('no turn row');
  return row;
}

async function renderReview(
  turns: ReturnType<typeof reviewTurn>[],
  overrides: Parameters<typeof reviewFixture>[0] = {},
  season: Parameters<typeof serveReview>[0] extends infer O
    ? O extends { season?: infer S }
      ? S
      : never
    : never = {},
) {
  const world = serveReview({
    season,
    review: reviewFixture({ pathLength: turns.length, turns, ...overrides }),
  });
  const view = renderRoute('/seasons/7/review');
  await screen.findByRole('heading', { name: '시즌 7 복기' });
  return { world, ...view };
}

describe('review feed reveal', () => {
  it('R2/R3: splits news and alerts in server order and shows the event card', async () => {
    await renderReview([reviewTurn(1, { feed: feedOfReviewTurn() })]);
    const row = turnRow(1);
    const news = within(row)
      .getByRole('heading', { name: '소식' })
      .closest('section') as HTMLElement;
    const items = within(news).getAllByRole('listitem');
    expect(items.map((li) => li.querySelector('p')?.textContent)).toEqual([
      'Invented rise body',
      'Invented echo body',
      'Invented spread body',
      'Invented event body',
    ]);
    const event = items[3] as HTMLElement;
    expect(within(event).getByText('실제 사건(신호)')).toBeInTheDocument();
    expect(within(event).getByText('사건 카드')).toBeInTheDocument();
    expect(within(event).getByText('Invented feed event')).toBeInTheDocument();
    expect(within(event).getByText('금융 · 시작')).toBeInTheDocument();
    expect(within(event).getByRole('link', { name: '출처' })).toHaveAttribute(
      'href',
      'https://example.test/a',
    );
    expect(
      within(items[0] as HTMLElement).getByText('생성된 소음'),
    ).toBeInTheDocument();
    const alerts = within(row)
      .getByRole('heading', { name: '알림' })
      .closest('section') as HTMLElement;
    expect(within(alerts).getAllByRole('listitem')).toHaveLength(1);
    expect(within(alerts).getByText('Invented nudge body')).toBeInTheDocument();
    expect(
      within(alerts).getByText('진행 중에는 알림 목록에 보였습니다.'),
    ).toBeInTheDocument();
    expect(within(alerts).getByText('독려 알림')).toBeInTheDocument();
    expect(within(alerts).queryByText('생성된 소음')).toBeNull();
    expect(within(alerts).queryByText('실제 사건(신호)')).toBeNull();
  });

  it('R3: only an http(s) source becomes a link inside a feed event', async () => {
    await renderReview([
      reviewTurn(1, {
        feed: [
          reviewFeedItem({
            kind: 'EVENT',
            signal: true,
            event: reviewEvent({ source: 'javascript:alert(1)' }),
          }),
        ],
      }),
    ]);
    const row = turnRow(1);
    expect(within(row).queryByRole('link')).toBeNull();
    expect(
      within(row).getByText('출처: javascript:alert(1)'),
    ).toBeInTheDocument();
  });

  it('R4: follows signal, kind and event independently', async () => {
    await renderReview([
      reviewTurn(1, {
        feed: [
          reviewFeedItem({
            seq: 1,
            kind: 'RISE',
            signal: true,
            event: null,
            body: 'Signal without event',
          }),
          reviewFeedItem({
            seq: 2,
            kind: 'EVENT',
            signal: false,
            body: 'Event without signal',
            event: reviewEvent({ title: 'Event shown anyway' }),
          }),
          reviewFeedItem({
            seq: 3,
            kind: 'RISE',
            signal: false,
            body: 'Rise carrying an event',
            event: reviewEvent({ title: 'Event on a rise' }),
          }),
          reviewFeedItem({
            seq: 4,
            kind: 'EVENT',
            signal: true,
            body: 'Event kind without event',
            event: null,
          }),
        ],
      }),
    ]);
    const row = turnRow(1);
    const [first, second, third, fourth] = within(row).getAllByRole('listitem');
    expect(
      within(first as HTMLElement).getByText('실제 사건(신호)'),
    ).toBeTruthy();
    expect(within(first as HTMLElement).queryByText('생성된 소음')).toBeNull();
    expect(
      within(second as HTMLElement).queryByText('실제 사건(신호)'),
    ).toBeNull();
    expect(
      within(second as HTMLElement).getByText('Event shown anyway'),
    ).toBeTruthy();
    expect(
      within(third as HTMLElement).getByText('Event on a rise'),
    ).toBeTruthy();
    expect(fourth?.querySelector('[class*="event_"]')).toBeNull();
  });

  it('R5: prints the order count only from one up', async () => {
    await renderReview([
      reviewTurn(1, {
        feed: [
          reviewFeedItem({ seq: 1, body: 'No orders', ordersFromItem: 0 }),
          reviewFeedItem({ seq: 2, body: 'Two orders', ordersFromItem: 2 }),
        ],
      }),
    ]);
    const row = turnRow(1);
    expect(within(row).getAllByText(/이 항목에서 낸 주문/)).toHaveLength(1);
    expect(
      within(row).getByText('이 항목에서 낸 주문 2건'),
    ).toBeInTheDocument();
  });

  it('R6: mutes only turns after the abandoned turn and keeps their order counts', async () => {
    const feed = [reviewFeedItem({ ordersFromItem: 1 })];
    const turns = Array.from({ length: 12 }, (_, i) =>
      reviewTurn(i + 1, { feed }),
    );
    const { container } = await renderReview(turns, { abandonedTurnNo: 5 });
    for (let n = 1; n <= 12; n += 1) {
      const row = turnRow(n);
      const details = row.querySelector('details');
      expect(details?.className.includes('muted'), `turn ${String(n)}`).toBe(
        n > 5,
      );
      expect(within(row).queryAllByText(NEVER_SHOWN)).toHaveLength(
        n > 5 ? 1 : 0,
      );
      expect(
        within(row).getByText('이 항목에서 낸 주문 1건'),
      ).toBeInTheDocument();
    }
    expect(container.textContent?.split(NEVER_SHOWN)).toHaveLength(8);
  });

  it('R6: a season that was not abandoned mutes nothing', async () => {
    const feed = [reviewFeedItem()];
    await renderReview(
      Array.from({ length: 3 }, (_, i) => reviewTurn(i + 1, { feed })),
    );
    expect(screen.queryByText(NEVER_SHOWN)).toBeNull();
  });

  it('R7: an empty feed prints one sentence and no list headings', async () => {
    await renderReview([reviewTurn(1)]);
    const row = turnRow(1);
    expect(
      within(row).getByText('저장된 소식이 없는 턴입니다'),
    ).toBeInTheDocument();
    expect(within(row).queryByRole('heading')).toBeNull();
    expect(row.querySelector('details')).toBeNull();
  });

  it('R8: ids never reach the DOM and bodies stay text', async () => {
    const { container } = await renderReview([
      reviewTurn(1, {
        feed: [
          reviewFeedItem({
            seq: 1,
            assetId: 987654321,
            groupId: 246813579,
            body: 'Body with <b>markup</b>',
          }),
          ...feedOfReviewTurn().map((item) => ({ ...item, seq: item.seq + 1 })),
        ],
      }),
    ]);
    expect(container.innerHTML).not.toContain('987654321');
    expect(container.innerHTML).not.toContain('246813579');
    expect(
      within(turnRow(1)).getByText('Body with <b>markup</b>'),
    ).toBeInTheDocument();
    expect(container.querySelector('b')).toBeNull();
  });

  it('R9: a 60-turn review with 15 items per turn still asks once per resource', async () => {
    const feed = Array.from({ length: 15 }, (_, i) =>
      reviewFeedItem({ seq: i + 1, body: `Body ${String(i + 1)}` }),
    );
    const turns = Array.from({ length: 60 }, (_, i) =>
      reviewTurn(i + 1, { feed }),
    );
    const { world } = await renderReview(turns, {}, { currentTurnNo: 60 });
    expect(screen.getAllByText('Body 15')).toHaveLength(60);
    await waitFor(() => {
      expect(world.turnGets).toHaveLength(60);
    });
    expect(new Set(world.turnGets).size).toBe(60);
    expect(world.reviewGets).toBe(1);
  });

  it('C1: the never-shown note sits outside the closed fold and the summary does not claim "보인"', async () => {
    const feed = [reviewFeedItem()];
    const turns = Array.from({ length: 6 }, (_, i) =>
      reviewTurn(i + 1, { feed }),
    );
    await renderReview(turns, { abandonedTurnNo: 3 });
    for (let n = 1; n <= 6; n += 1) {
      const row = turnRow(n);
      const details = row.querySelector('details');
      expect(details).not.toBeNull();
      expect(details?.hasAttribute('open'), `turn ${String(n)}`).toBe(false);
      const note = within(row).queryByText(NEVER_SHOWN);
      const summary = details?.querySelector('summary');
      if (n > 3) {
        expect(note).toBeInTheDocument();
        expect(details?.contains(note)).toBe(false);
        expect(summary?.textContent).toBe(
          `${String(n)}턴에 만들어진 소식과 알림 (보이지 않음)`,
        );
      } else {
        expect(note).toBeNull();
        expect(summary?.textContent).toBe(`${String(n)}턴에 보인 소식과 알림`);
      }
    }
  });

  it('keeps the alert list in server order', async () => {
    await renderReview([
      reviewTurn(1, {
        feed: [
          reviewFeedItem({ seq: 1, kind: 'RISE', body: 'News one' }),
          reviewFeedItem({ seq: 2, kind: 'NUDGE', body: 'Alert one' }),
          reviewFeedItem({ seq: 3, kind: 'NUDGE', body: 'Alert two' }),
          reviewFeedItem({ seq: 4, kind: 'NUDGE', body: 'Alert three' }),
        ],
      }),
    ]);
    const alerts = within(turnRow(1))
      .getByRole('heading', { name: '알림' })
      .closest('section') as HTMLElement;
    expect(
      within(alerts)
        .getAllByRole('listitem')
        .map((li) => li.querySelector('p')?.textContent),
    ).toEqual(['Alert one', 'Alert two', 'Alert three']);
  });

  it('C2: every non-nudge item without signal carries the noise badge, a nudge never does', async () => {
    const kinds = ['RISE', 'FALL', 'ECHO', 'SPREAD', 'FADE', 'EVENT'] as const;
    await renderReview([
      reviewTurn(1, {
        feed: [
          ...kinds.map((kind, i) =>
            reviewFeedItem({ seq: i + 1, kind, body: `Item ${kind}` }),
          ),
          reviewFeedItem({ seq: 7, kind: 'NUDGE', body: 'Item NUDGE' }),
        ],
      }),
    ]);
    const row = turnRow(1);
    expect(within(row).getAllByText('생성된 소음')).toHaveLength(6);
    const nudge = within(row).getByText('Item NUDGE').closest('li');
    expect(within(nudge as HTMLElement).queryByText('생성된 소음')).toBeNull();
  });
});
