import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { feedItem } from '../../test/fixtures/feed.ts';
import { seasonFixture } from '../../test/fixtures/seasons.ts';
import { renderWithProviders } from '../../test/renderWithProviders.tsx';
import { serveFeedWorld } from '../../test/serveFeed.ts';
import { AlertList } from './AlertList.tsx';
import { FeedSection } from './FeedSection.tsx';

const tradable = seasonFixture({ currentTurnNo: 5 });
const halted = seasonFixture({ currentTurnNo: 5, status: 'HALTED' });

function blanked(row: HTMLElement): string {
  const copy = row.cloneNode(true) as HTMLElement;
  copy.querySelectorAll('[aria-expanded]').forEach((p) => {
    p.textContent = '';
  });
  return copy.outerHTML;
}

describe('AlertList', () => {
  it('lists the alerts in the order given inside a region named 알림, not as an ARIA alert', () => {
    renderWithProviders(
      <AlertList
        feed={[]}
        season={halted}
        items={[
          feedItem({ seq: 4, assetId: null, body: '다 알림' }),
          feedItem({ seq: 5, assetId: null, body: '가 알림' }),
        ]}
      />,
    );
    const region = screen.getByRole('region', { name: '알림' });
    expect(
      within(region)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['다 알림', '가 알림']);
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
    expect(
      screen.getByRole('heading', { level: 3, name: '알림' }),
    ).toBeVisible();
  });

  it('renders nothing at all for no alerts', () => {
    const { container } = renderWithProviders(
      <AlertList feed={[]} season={halted} items={[]} />,
    );
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByRole('region')).toBeNull();
    expect(screen.queryByText(/알림/)).toBeNull();
  });

  it('shows two banners and puts the rest behind a disclosure', async () => {
    const items = [1, 2, 3, 4].map((seq) =>
      feedItem({ seq, assetId: null, body: `알림 ${String(seq)}` }),
    );
    renderWithProviders(<AlertList feed={[]} season={halted} items={items} />);
    const region = screen.getByRole('region', { name: '알림' });
    expect(within(region).getAllByRole('listitem')).toHaveLength(2);
    const more = within(region).getByRole('button', { name: '외 2건' });
    expect(more).toHaveAttribute('aria-expanded', 'false');
    await userEvent.setup().click(more);
    expect(within(region).getAllByRole('listitem')).toHaveLength(4);
    expect(
      within(region).getByRole('button', { name: '접기' }),
    ).toHaveAttribute('aria-expanded', 'true');
  });

  it('cuts a long alert to one line and shows the whole text on tap', async () => {
    const body = '아주 긴 알림 문장 '.repeat(12).trim();
    renderWithProviders(
      <AlertList
        feed={[]}
        season={halted}
        items={[feedItem({ seq: 1, assetId: null, body })]}
      />,
    );
    const toggle = screen.getByRole('button', { name: body });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.setup().click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
  });

  it('orders from an expanded third alert with its own ALERT source and returns focus', async () => {
    const world = serveFeedWorld({ current: 5 });
    renderWithProviders(
      <AlertList
        feed={[]}
        season={tradable}
        items={[
          feedItem({ seq: 1, assetId: null, body: '가' }),
          feedItem({ seq: 2, assetId: null, body: '나' }),
          feedItem({ seq: 9, assetId: 102, body: '다 알림' }),
        ]}
      />,
    );
    const user = userEvent.setup();
    const region = screen.getByRole('region', { name: '알림' });
    expect(within(region).getAllByRole('listitem')).toHaveLength(2);
    await user.click(within(region).getByRole('button', { name: '외 1건' }));
    expect(within(region).getAllByRole('listitem')).toHaveLength(3);
    const buy = await within(region).findByRole('button', {
      name: '종목 나 매수',
    });
    await user.click(buy);
    const dialog = await screen.findByRole('dialog', { name: '종목 나' });
    await user.click(within(dialog).getByRole('button', { name: '1주 매수' }));
    await within(dialog).findByText('체결가');
    expect(world.orders).toHaveLength(1);
    expect(world.orders[0]).toMatchObject({
      assetId: 102,
      side: 'BUY',
      source: { kind: 'ALERT', feedTurnNo: 5, feedSeq: 9 },
    });
    await user.click(within(dialog).getByRole('button', { name: '닫기' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(buy).toHaveFocus();
    await user.click(within(region).getByRole('button', { name: '접기' }));
    expect(within(region).getAllByRole('listitem')).toHaveLength(2);
  });

  it('has no disclosure for two alerts or fewer', () => {
    renderWithProviders(
      <AlertList
        feed={[]}
        season={halted}
        items={[
          feedItem({ seq: 1, assetId: null, body: '가' }),
          feedItem({ seq: 2, assetId: null, body: '나' }),
        ]}
      />,
    );
    expect(
      screen.queryByRole('button', { name: /^(외 \d+건|접기)$/ }),
    ).toBeNull();
  });

  it('keeps alerts out of the feed list and the feed out of the alert region', () => {
    renderWithProviders(
      <>
        <AlertList
          feed={[]}
          season={halted}
          items={[feedItem({ seq: 4, assetId: null, body: '알림 문장' })]}
        />
        <FeedSection
          season={halted}
          items={[feedItem({ seq: 1, assetId: null, body: '소식 문장' })]}
        />
      </>,
    );
    const alerts = screen.getByRole('region', { name: '알림' });
    const news = screen.getByRole('region', { name: '소식' });
    expect(within(alerts).queryByText('소식 문장')).toBeNull();
    expect(within(news).queryByText('알림 문장')).toBeNull();
  });

  it('dresses alerts like feed items and exposes no identifier', async () => {
    serveFeedWorld({ current: 5 });
    const { container } = renderWithProviders(
      <AlertList
        feed={[]}
        season={tradable}
        items={[
          feedItem({
            turnNo: 4171,
            seq: 7731,
            assetId: 101,
            body: '급등! +35% 지금 사세요',
          }),
          feedItem({
            turnNo: 4171,
            seq: 7732,
            assetId: 101,
            body: '조용한 하루였습니다',
          }),
          feedItem({
            turnNo: 4171,
            seq: 7733,
            assetId: 987654,
            body: '모르는 종목',
          }),
        ]}
      />,
    );
    await screen.findAllByRole('button', { name: '종목 가 매수' });
    const rows = screen.getAllByRole('listitem');
    expect(blanked(rows[0] as HTMLElement)).toBe(
      blanked(rows[1] as HTMLElement),
    );
    const html = container.innerHTML;
    for (const id of ['4171', '7731', '7732', '987654']) {
      expect(html).not.toContain(id);
    }
    expect(html).not.toMatch(/\sdata-/);
  });
});
