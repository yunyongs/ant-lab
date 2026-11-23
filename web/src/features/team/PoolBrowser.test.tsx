import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { poolAssetFixture, poolFixture } from '../../test/fixtures/pool.ts';
import { renderRoute } from '../../test/renderWithProviders.tsx';
import { serveSeason } from '../../test/serve.ts';
import { server } from '../../test/server.ts';

function servePool(assets = poolFixture.assets) {
  serveSeason({});
  server.use(http.get('*/api/v1/pool', () => HttpResponse.json({ assets })));
}

async function count(): Promise<HTMLElement> {
  const list = await screen.findByRole('region', { name: '종목 풀' });
  return within(list).getByRole('status');
}

async function cardNames(): Promise<string[]> {
  const list = await screen.findByRole('region', { name: '종목 풀' });
  return within(list)
    .queryAllByRole('heading', { level: 3 })
    .map((h) => (h.textContent ?? '').replace(/ · .*$/, ''))
    .filter((name) => name !== '종목 풀');
}

describe('pool browser', () => {
  it('shows the count and every control', async () => {
    servePool();
    renderRoute('/seasons/7');
    expect(await count()).toHaveTextContent('3개');
    for (const label of [
      '종목 검색',
      '시장',
      '종류',
      '포지션',
      '묶음',
      '정렬',
    ]) {
      expect(screen.getByLabelText(label)).toBeInTheDocument();
    }
    expect(screen.queryByRole('button', { name: '조건 지우기' })).toBeNull();
  });

  it('searches by name or ticker and keeps it in the address', async () => {
    servePool();
    const user = userEvent.setup();
    const { router } = renderRoute('/seasons/7');
    await user.type(await screen.findByLabelText('종목 검색'), 'fix-bbb');
    expect(await cardNames()).toEqual(['종목 나']);
    expect(await count()).toHaveTextContent('전체 3개 중 1개');
    expect(router.state.location.search).toBe('?q=fix-bbb');
  });

  it('filters by market, type and position', async () => {
    servePool();
    const user = userEvent.setup();
    renderRoute('/seasons/7');
    await user.selectOptions(await screen.findByLabelText('시장'), 'KR');
    expect(await cardNames()).toEqual(['종목 가', '채권 ETF 다']);
    await user.selectOptions(screen.getByLabelText('종류'), 'ETF');
    expect(await cardNames()).toEqual(['채권 ETF 다']);
    await user.selectOptions(screen.getByLabelText('포지션'), 'MIDFIELDER');
    expect(await cardNames()).toEqual([]);
    expect(
      screen.getByText('조건에 맞는 종목이 없습니다.'),
    ).toBeInTheDocument();
  });

  it('filters by group', async () => {
    servePool();
    const user = userEvent.setup();
    renderRoute('/seasons/7');
    await user.selectOptions(await screen.findByLabelText('묶음'), '시험 채권');
    expect(await cardNames()).toEqual(['채권 ETF 다']);
  });

  it('sorts by volatility and drawdown', async () => {
    servePool();
    const user = userEvent.setup();
    renderRoute('/seasons/7');
    await user.selectOptions(
      await screen.findByLabelText('정렬'),
      '변동성 낮은 순',
    );
    expect(await cardNames()).toEqual(['채권 ETF 다', '종목 가', '종목 나']);
    await user.selectOptions(screen.getByLabelText('정렬'), '최대 낙폭 큰 순');
    expect(await cardNames()).toEqual(['종목 나', '종목 가', '채권 ETF 다']);
  });

  it('starts from the address and the empty state clears it', async () => {
    servePool();
    const user = userEvent.setup();
    const { router } = renderRoute('/seasons/7?q=없는이름&sort=vol-asc');
    expect(
      await screen.findByText('조건에 맞는 종목이 없습니다.'),
    ).toBeInTheDocument();
    expect(await count()).toHaveTextContent('전체 3개 중 0개');
    const empty = screen
      .getByText('조건에 맞는 종목이 없습니다.')
      .closest('div') as HTMLElement;
    await user.click(
      within(empty).getByRole('button', { name: '조건 지우기' }),
    );
    expect(await cardNames()).toHaveLength(3);
    expect(router.state.location.search).toBe('');
  });

  it('opens the order sheet for a filtered card', async () => {
    servePool();
    const user = userEvent.setup();
    renderRoute('/seasons/7?market=US');
    await user.click(
      await screen.findByRole('button', { name: '종목 나 선택' }),
    );
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it('handles a 180-asset pool', async () => {
    const many = Array.from({ length: 180 }, (_, i) =>
      poolAssetFixture({
        assetId: 1000 + i,
        ticker: `T${String(i)}`,
        name: `종목 ${String(i)}`,
        market: i < 50 ? 'KR' : 'US',
        assetType: i >= 150 ? 'ETF' : 'STOCK',
        groupId: (i % 12) + 1,
        groupName: `묶음 ${String((i % 12) + 1)}`,
        volatility: `0.0${String(i % 10)}${String(i)}0000`,
      }),
    );
    servePool(many);
    const user = userEvent.setup();
    renderRoute('/seasons/7');
    expect(await count()).toHaveTextContent('180개');
    await user.selectOptions(screen.getByLabelText('시장'), 'KR');
    expect(await count()).toHaveTextContent('전체 180개 중 50개');
  });

  it('shows the ticker next to the name', async () => {
    servePool();
    renderRoute('/seasons/7');
    const list = await screen.findByRole('region', { name: '종목 풀' });
    expect(await within(list).findByText('· FIX-AAA')).toBeInTheDocument();
  });

  it('keeps typed text, caret and order while the address catches up', async () => {
    servePool();
    const user = userEvent.setup();
    renderRoute('/seasons/7');
    const input = await screen.findByLabelText('종목 검색');
    // native events outside act: the value must be the new text at once
    fireEvent.input(input, { target: { value: 'a' } });
    expect((input as HTMLInputElement).value).toBe('a');
    fireEvent.input(input, { target: { value: 'ab' } });
    expect((input as HTMLInputElement).value).toBe('ab');
    await user.clear(input);
    const seen: string[] = [];
    for (const key of 'abcd') {
      await user.type(input, key);
      seen.push((input as HTMLInputElement).value);
    }
    expect(seen).toEqual(['a', 'ab', 'abc', 'abcd']);
    await user.clear(input);
    await user.type(input, '14');
    await user.type(input, '2', {
      initialSelectionStart: 1,
      initialSelectionEnd: 1,
    });
    expect((input as HTMLInputElement).value).toBe('124');
  });

  it('does not write the address during composition', async () => {
    servePool();
    const { router } = renderRoute('/seasons/7');
    const input = await screen.findByLabelText('종목 검색');
    fireEvent.compositionStart(input);
    fireEvent.input(input, { target: { value: '채' } });
    expect((input as HTMLInputElement).value).toBe('채');
    expect(router.state.location.search).toBe('');
    fireEvent.compositionEnd(input);
    await screen.findByText('전체 3개 중 1개', {}, { timeout: 2000 });
    expect(router.state.location.search).toBe('?q=%EC%B1%84');
  });

  it('matches decomposed Hangul and ignores spaces', async () => {
    servePool();
    const user = userEvent.setup();
    renderRoute('/seasons/7');
    const input = await screen.findByLabelText('종목 검색');
    await user.type(input, '채권'.normalize('NFD'));
    expect(await cardNames()).toEqual(['채권 ETF 다']);
    await user.clear(input);
    await user.type(input, '채권etf');
    expect(await cardNames()).toEqual(['채권 ETF 다']);
  });

  it('ignores a group id the pool does not have', async () => {
    servePool();
    renderRoute('/seasons/7?group=99');
    expect(await cardNames()).toHaveLength(3);
    expect(screen.getByLabelText('묶음')).toHaveValue('');
  });

  it('clears the search text and focuses it on reset', async () => {
    servePool();
    const user = userEvent.setup();
    renderRoute('/seasons/7');
    const input = await screen.findByLabelText('종목 검색');
    await user.type(input, '나');
    await user.click(screen.getByRole('button', { name: '조건 지우기' }));
    expect(input).toHaveValue('');
    expect(input).toHaveFocus();
    expect(await cardNames()).toHaveLength(3);
  });

  it('shows one reset button when nothing matches', async () => {
    servePool();
    renderRoute('/seasons/7?q=없는이름');
    await screen.findByText('조건에 맞는 종목이 없습니다.');
    expect(screen.getAllByRole('button', { name: '조건 지우기' })).toHaveLength(
      1,
    );
  });
});
