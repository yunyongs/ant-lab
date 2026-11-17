import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { feedItem } from '../../test/fixtures/feed.ts';
import { seasonFixture } from '../../test/fixtures/seasons.ts';
import { renderWithProviders } from '../../test/renderWithProviders.tsx';
import { serveFeedWorld } from '../../test/serveFeed.ts';
import { FeedSection } from './FeedSection.tsx';

const tradable = seasonFixture({ currentTurnNo: 5 });
const halted = seasonFixture({ currentTurnNo: 5, status: 'HALTED' });

// The row without its sentence: what is left must not depend on the text.
function blanked(row: HTMLElement): string {
  const copy = row.cloneNode(true) as HTMLElement;
  copy.querySelectorAll('p').forEach((p) => {
    p.textContent = '';
  });
  return copy.outerHTML;
}

describe('FeedSection', () => {
  it('lists the items in the order given, not sorted by sentence or asset', () => {
    renderWithProviders(
      <FeedSection
        season={halted}
        items={[
          feedItem({ seq: 1, assetId: 103, body: '다 문장' }),
          feedItem({ seq: 2, assetId: null, body: '가 문장' }),
          feedItem({ seq: 3, assetId: 101, body: '나 문장' }),
        ]}
      />,
    );
    const region = screen.getByRole('region', { name: '소식' });
    const texts = within(region)
      .getAllByRole('listitem')
      .map((li) => li.textContent);
    expect(texts).toEqual(['다 문장', '가 문장', '나 문장']);
  });

  it('says there is no news for an empty feed and only then', () => {
    const empty = renderWithProviders(
      <FeedSection season={halted} items={[]} />,
    );
    expect(screen.getByText('이번 턴은 소식이 없습니다')).toBeInTheDocument();
    empty.unmount();
    renderWithProviders(
      <FeedSection season={halted} items={[feedItem({ assetId: null })]} />,
    );
    expect(screen.queryByText('이번 턴은 소식이 없습니다')).toBeNull();
  });

  it('dresses an event-shaped and a noise sentence alike when no asset is named', () => {
    renderWithProviders(
      <FeedSection
        season={tradable}
        items={[
          feedItem({
            seq: 1,
            assetId: null,
            body: '급등! +35% 사건이 터졌습니다',
          }),
          feedItem({ seq: 2, assetId: null, body: '조용한 하루였습니다' }),
        ]}
      />,
    );
    const [first, second] = screen.getAllByRole('listitem');
    expect(first).toBeDefined();
    expect(blanked(first as HTMLElement)).toBe(blanked(second as HTMLElement));
  });

  it('dresses two sentences alike when they name the same asset, buttons included', async () => {
    serveFeedWorld({ current: 5 });
    renderWithProviders(
      <FeedSection
        season={tradable}
        items={[
          feedItem({
            seq: 1,
            assetId: 101,
            body: '급등! +35% 사건이 터졌습니다',
          }),
          feedItem({ seq: 2, assetId: 101, body: '조용한 하루였습니다' }),
        ]}
      />,
    );
    await screen.findAllByRole('button', { name: '종목 가 매수' });
    const rows = screen.getAllByRole('listitem');
    expect(within(rows[0] as HTMLElement).getAllByRole('button')).toHaveLength(
      1,
    );
    expect(blanked(rows[0] as HTMLElement)).toBe(
      blanked(rows[1] as HTMLElement),
    );
  });

  it('prints a sentence literally and builds no element from it', () => {
    const body = '<img src=x onerror=alert(1)><b>굵게</b>';
    const { container } = renderWithProviders(
      <FeedSection
        season={halted}
        items={[feedItem({ assetId: null, body })]}
      />,
    );
    expect(screen.getByText(body)).toBeInTheDocument();
    expect(container.querySelector('img, b')).toBeNull();
  });

  it('prints and attaches no identifier of an item', async () => {
    serveFeedWorld({ current: 5 });
    const { container } = renderWithProviders(
      <FeedSection
        season={tradable}
        items={[
          feedItem({ turnNo: 4171, seq: 7731, assetId: 101, body: '가 문장' }),
          feedItem({
            turnNo: 4171,
            seq: 7732,
            assetId: 987654,
            body: '나 문장',
          }),
        ]}
      />,
    );
    await screen.findByRole('button', { name: '종목 가 매수' });
    const html = container.innerHTML;
    for (const id of ['4171', '7731', '7732', '987654']) {
      expect(html).not.toContain(id);
    }
    expect(html).not.toMatch(/\sdata-/);
    // The pool does not know asset 987654: no button for it.
    expect(screen.getAllByRole('button')).toHaveLength(1);
  });
});
