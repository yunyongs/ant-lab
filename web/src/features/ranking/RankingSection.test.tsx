import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../../test/authHandlers.ts';
import { poolFixture } from '../../test/fixtures/pool.ts';
import { emptyBoard, rankingFixture } from '../../test/fixtures/ranking.ts';
import { renderWithProviders } from '../../test/renderWithProviders.tsx';
import { server } from '../../test/server.ts';
import { settle } from '../../test/settle.ts';
import { gate } from '../../test/serveTurns.ts';
import { rankingKey } from './queries.ts';
import { RankingSection } from './RankingSection.tsx';

function serveBoard(board: ReturnType<typeof rankingFixture>) {
  server.use(
    http.get('*/api/v1/seasons/7/turns/5/ranking', () =>
      HttpResponse.json(board),
    ),
  );
}

function renderSection() {
  return renderWithProviders(
    <RankingSection seasonId={7} turnNo={5} currentTurnNo={5} />,
  );
}

describe('RankingSection', () => {
  it('prints rank, name and the server change as percent text', async () => {
    serveBoard(rankingFixture());
    renderSection();
    const region = await screen.findByRole('region', { name: '순위' });
    const rows = await within(region).findAllByRole('listitem');
    expect(rows.map((r) => r.textContent)).toEqual([
      '1위종목 나12.34%',
      '2위종목 가5.00%',
      '1위채권 ETF 다-7.25%',
    ]);
  });

  it('adds "n턴 연속" on streak rows', async () => {
    serveBoard(rankingFixture());
    renderSection();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('tab', { name: '연속' }));
    const panel = screen.getByRole('tabpanel');
    expect(
      within(panel)
        .getAllByRole('listitem')
        .map((r) => r.textContent),
    ).toEqual(['1위종목 가4턴 연속5.00%']);
    expect(within(panel).getByText('해당하는 종목이 없습니다')).toBeVisible();
  });

  it('prints attention as rank and grade label only', async () => {
    serveBoard(rankingFixture());
    renderSection();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('tab', { name: '관심' }));
    const rows = within(screen.getByRole('tabpanel')).getAllByRole('listitem');
    expect(rows.map((r) => r.textContent)).toEqual([
      '1위채권 ETF 다매우 높음',
      '2위종목 가높음',
      '3위종목 나보통',
    ]);
    for (const row of rows) {
      // After the rank, no digit string is left: no index value, no ratio.
      expect(row.textContent?.replace(/^\d+위/, '')).not.toMatch(/\d/);
    }
  });

  it('shows a short sentence for an empty list and keeps the tabs when all are empty', async () => {
    serveBoard(emptyBoard(5));
    renderSection();
    const tabs = await screen.findAllByRole('tab');
    expect(tabs).toHaveLength(4);
    const user = userEvent.setup();
    expect(screen.getAllByText('해당하는 종목이 없습니다')).toHaveLength(2);
    await user.click(screen.getByRole('tab', { name: '그룹' }));
    expect(screen.getAllByText('해당하는 그룹이 없습니다')).toHaveLength(2);
  });

  it('has no order button on any row, group rows included', async () => {
    serveBoard(rankingFixture());
    renderSection();
    const user = userEvent.setup();
    await screen.findByRole('tablist');
    for (const name of ['등락', '관심', '연속', '그룹']) {
      await user.click(screen.getByRole('tab', { name }));
      const panel = screen.getByRole('tabpanel');
      expect(within(panel).queryAllByRole('button')).toHaveLength(0);
    }
    expect(screen.queryByRole('button', { name: /매수|매도/ })).toBeNull();
  });

  it('prints no identifier and sets no data attribute', async () => {
    serveBoard(
      rankingFixture(5, {
        gainers: [{ rank: 1, assetId: 987654, changeRate: '0.01000000' }],
        groupGainers: [{ rank: 1, groupId: 987655, changeRate: '0.01000000' }],
      }),
    );
    const { container } = renderSection();
    await screen.findByText('이름을 찾지 못한 종목');
    expect(container.innerHTML).not.toContain('987654');
    expect(container.innerHTML).not.toContain('987655');
    expect(container.innerHTML).not.toMatch(/data-/);
    await userEvent.setup().click(screen.getByRole('tab', { name: '그룹' }));
    expect(screen.getByText('이름을 찾지 못한 그룹')).toBeInTheDocument();
    expect(container.innerHTML).not.toContain('987655');
  });

  it('does not request a board for a turn after the current one', async () => {
    let gets = 0;
    server.use(
      http.get('*/api/v1/seasons/7/turns/6/ranking', () => {
        gets += 1;
        return HttpResponse.json(emptyBoard(6));
      }),
    );
    const { queryClient, container } = renderWithProviders(
      <RankingSection seasonId={7} turnNo={6} currentTurnNo={5} />,
    );
    await settle(queryClient);
    expect(queryClient.getQueryState(rankingKey(7, 6))?.fetchStatus).toBe(
      'idle',
    );
    expect(gets).toBe(0);
    // Out of range: nothing is shown, not even a loading line.
    expect(container).toBeEmptyDOMElement();
  });

  it('does not request a board for turn 0', async () => {
    let gets = 0;
    server.use(
      http.get('*/api/v1/seasons/7/turns/0/ranking', () => {
        gets += 1;
        return HttpResponse.json(emptyBoard(0));
      }),
    );
    const { queryClient } = renderWithProviders(
      <RankingSection seasonId={7} turnNo={0} currentTurnNo={5} />,
    );
    await settle(queryClient);
    expect(queryClient.getQueryState(rankingKey(7, 0))?.fetchStatus).toBe(
      'idle',
    );
    expect(gets).toBe(0);
  });

  it('shows a pool failure inside the section and retries the pool', async () => {
    // The board is still on its way while the pool has already failed.
    const hold = gate();
    server.use(
      http.get('*/api/v1/seasons/7/turns/5/ranking', async () => {
        await hold.promise;
        return HttpResponse.json(rankingFixture());
      }),
    );
    let poolGets = 0;
    let poolOk = false;
    server.use(
      http.get('*/api/v1/pool', () => {
        poolGets += 1;
        return poolOk
          ? HttpResponse.json(poolFixture)
          : problem(500, { code: 'INTERNAL_ERROR', status: 500 });
      }),
    );
    renderSection();
    const region = await screen.findByRole('region', { name: '순위' });
    const alert = await within(region).findByRole('alert');
    expect(within(region).queryByRole('tablist')).toBeNull();
    // A failure replaces the loading line.
    expect(within(region).queryByRole('status')).toBeNull();
    poolOk = true;
    hold.release();
    const before = poolGets;
    await userEvent
      .setup()
      .click(within(alert).getByRole('button', { name: '다시 불러오기' }));
    await within(region).findByRole('tablist');
    expect(poolGets).toBeGreaterThan(before);
  });

  it('shows a loading line until the board arrives, then only the tabs', async () => {
    const hold = gate();
    server.use(
      http.get('*/api/v1/seasons/7/turns/5/ranking', async () => {
        await hold.promise;
        return HttpResponse.json(rankingFixture());
      }),
    );
    renderSection();
    const region = await screen.findByRole('region', { name: '순위' });
    expect(within(region).getByRole('status')).toHaveTextContent(
      '순위를 불러오는 중입니다…',
    );
    expect(within(region).queryByRole('tablist')).toBeNull();
    hold.release();
    await within(region).findByRole('tablist');
    expect(within(region).queryByRole('status')).toBeNull();
  });
});
