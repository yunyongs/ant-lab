import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http } from 'msw';
import { describe, expect, it } from 'vitest';
import { invalidateSeasonState } from '../features/seasons/queries.ts';
import { problem } from '../test/authHandlers.ts';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import { bandRows, projectionFixture } from '../test/fixtures/projection.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';
import {
  engineUnavailable503,
  pathTooLong422,
  serveProjection,
} from '../test/serveProjection.ts';
import { HttpResponse } from 'msw';

const ROUTE = '/seasons/7/projection';

function block(title: string): HTMLElement {
  const section = screen
    .getByRole('heading', { level: 2, name: title })
    .closest('section');
  if (section === null) throw new Error(`no block for ${title}`);
  return section;
}

async function settle() {
  await new Promise((resolve) => setTimeout(resolve, 60));
}

describe('ProjectionPage', () => {
  it('P2: requests CURRENT once and shows the chart and every number as text', async () => {
    const world = serveProjection();
    renderRoute(ROUTE);
    const chart = await screen.findByRole('img', { name: /예상 분포 띠 그림/ });
    expect(chart.tagName.toLowerCase()).toBe('svg');
    expect(world.phases).toEqual(['CURRENT']);
    expect(
      screen.getByRole('heading', { level: 1, name: '예상 분포' }),
    ).toBeInTheDocument();

    const first = block('12턴 뒤');
    for (const text of [
      '81,234,500원',
      '92,345,600원',
      '103,456,700원',
      '114,567,800원',
      '125,678,900원',
      '-31.00%',
      '-24.00%',
      '-15.00%',
      '-8.00%',
      '-4.00%',
      '-1.00%',
    ]) {
      expect(within(first).getByText(text)).toBeInTheDocument();
    }
    expect(
      within(first).getByText('10% 확률로 이보다 나쁨'),
    ).toBeInTheDocument();
    expect(
      within(first).getByText('위험 선언(-20.00%)을 넘길 확률 12.34%'),
    ).toBeInTheDocument();
    const second = block('60턴 뒤 (5년)');
    expect(
      within(second).getByText('위험 선언(-20.00%)을 넘길 확률 38.76%'),
    ).toBeInTheDocument();
    expect(
      screen.getByText('지금 팀 · 4턴 · 총 평가액 100,000,000원'),
    ).toBeInTheDocument();
  });

  it('P3: the caption and the survivorship sentence stay on every state', async () => {
    const caption =
      '과거의 실제 달들을 재조합해 2,000번 돌린 결과의 범위입니다. 예측이 아닙니다.';
    const survivorship =
      '지금 상장된 종목만 쓰기 때문에 과거 결과는 실제보다 좋게 보일 수 있습니다.';

    serveProjection();
    const ok = renderRoute(ROUTE);
    await screen.findByRole('img');
    expect(screen.getByText(caption)).toBeInTheDocument();
    expect(screen.getByText(survivorship)).toBeInTheDocument();
    ok.unmount();

    serveProjection();
    server.use(
      http.get('*/api/v1/seasons/7/projection', async () => {
        await delay('infinite');
        return HttpResponse.json({});
      }),
    );
    const loading = renderRoute(ROUTE);
    await screen.findByText(/계산하는 중/);
    expect(screen.getByText(/예측이 아닙니다/)).toBeInTheDocument();
    expect(screen.getByText(survivorship)).toBeInTheDocument();
    loading.unmount();

    for (const answer of [engineUnavailable503, pathTooLong422]) {
      serveProjection({ answer });
      const failed = renderRoute(ROUTE);
      await screen.findByRole('alert');
      expect(screen.getByText(/예측이 아닙니다/)).toBeInTheDocument();
      expect(screen.getByText(survivorship)).toBeInTheDocument();
      failed.unmount();
    }
  });

  it('P4: never speaks of an expected return, and the median sits between its tails', async () => {
    serveProjection();
    const { container } = renderRoute(ROUTE);
    await screen.findByRole('img');
    expect(container.textContent).not.toMatch(/기대 수익|예상 수익률/);
    for (const title of ['12턴 뒤', '60턴 뒤 (5년)']) {
      const text = block(title).textContent ?? '';
      const low = text.indexOf('하위 5%');
      const mid = text.indexOf('중앙');
      const high = text.indexOf('상위 5%');
      expect(low).toBeGreaterThanOrEqual(0);
      expect(mid).toBeGreaterThan(low);
      expect(high).toBeGreaterThan(mid);
    }
    const first = block('12턴 뒤').textContent ?? '';
    const p05 = first.indexOf('81,234,500원');
    const p50 = first.indexOf('103,456,700원');
    const p95 = first.indexOf('125,678,900원');
    expect(p05).toBeLessThan(p50);
    expect(p50).toBeLessThan(p95);
  });

  it('P5: turn 0 asks only CURRENT and shows no phase control', async () => {
    const world = serveProjection({ season: { currentTurnNo: 0 } });
    renderRoute(ROUTE);
    await screen.findByRole('img');
    expect(
      screen.queryByRole('group', { name: '어느 팀을 볼까요?' }),
    ).toBeNull();
    expect(world.phases).toEqual(['CURRENT']);
  });

  it('P5: a finished season asks END, then START on demand, never CURRENT', async () => {
    const world = serveProjection({
      season: { status: 'FINISHED', currentTurnNo: 12 },
    });
    renderRoute(ROUTE);
    await screen.findByRole('img');
    expect(world.phases).toEqual(['END']);
    expect(screen.queryByRole('radio', { name: '지금 팀' })).toBeNull();
    expect(screen.getByRole('radio', { name: '마지막 팀' })).toBeChecked();
    await userEvent.click(screen.getByRole('radio', { name: '시작 팀' }));
    await waitFor(() => {
      expect(world.phases).toEqual(['END', 'START']);
    });
    await screen.findByText(/시작 팀 · 4턴/);
    expect(world.phases).not.toContain('CURRENT');
    expect(world.phases).not.toContain(null);
  });

  it('P6: a 503 shows its own sentence and retries only on the button', async () => {
    let calls = 0;
    const world = serveProjection({
      answer: () => {
        calls += 1;
        return calls === 1
          ? engineUnavailable503()
          : HttpResponse.json(projectionFixture());
      },
    });
    renderRoute(ROUTE);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('계산 서비스가 지금 바쁘거나');
    await settle();
    expect(world.phases).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await screen.findByRole('img');
    expect(world.phases).toHaveLength(2);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('P6: a 422 shows its own sentence and no retry button', async () => {
    const world = serveProjection({ answer: pathTooLong422 });
    renderRoute(ROUTE);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('과거 이력이 짧습니다');
    await settle();
    expect(screen.queryByRole('button', { name: /다시/ })).toBeNull();
    expect(world.phases).toHaveLength(1);
  });

  it('the query is keyed by the team state and kept for the session', async () => {
    serveProjection();
    const { queryClient } = renderRoute(ROUTE);
    await screen.findByRole('img');
    const query = queryClient
      .getQueryCache()
      .find({ queryKey: ['projection', 7, 'CURRENT', 4] });
    expect(query).toBeDefined();
    expect(query?.isStale()).toBe(false);
  });

  it('P7: a malformed band value draws no chart but keeps the numbers', async () => {
    serveProjection({
      answer: () =>
        HttpResponse.json(
          projectionFixture({
            bands: bandRows.map((row, i) =>
              i === 2 ? { ...row, p50: '12.5' } : row,
            ),
          }),
        ),
    });
    renderRoute(ROUTE);
    await screen.findByRole('heading', { level: 2, name: '12턴 뒤' });
    expect(screen.queryByRole('img')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getByText(/예측이 아닙니다/)).toBeInTheDocument();
    expect(
      within(block('12턴 뒤')).getByText('103,456,700원'),
    ).toBeInTheDocument();
  });

  it('P8: an order refreshes the current team only; the stored start team is kept', async () => {
    const world = serveProjection();
    const { queryClient } = renderRoute(ROUTE);
    await screen.findByRole('img');
    await userEvent.click(screen.getByRole('radio', { name: '시작 팀' }));
    await waitFor(() => {
      expect(world.phases).toEqual(['CURRENT', 'START']);
    });
    await screen.findByText(/시작 팀 · 4턴/);
    await act(async () => {
      await invalidateSeasonState(queryClient, 7);
    });
    await settle();
    expect(world.phases).toEqual(['CURRENT', 'START']);
    await userEvent.click(screen.getByRole('radio', { name: '지금 팀' }));
    await waitFor(() => {
      expect(world.phases).toEqual(['CURRENT', 'START', 'CURRENT']);
    });
  });

  it('P9: nothing on the page can read as a year-month', async () => {
    const tricky = projectionFixture({
      totalValue: '20261100',
      riskLimit: '-0.19970000',
      bands: bandRows.map((row) => ({
        turn: row.turn,
        p05: '19971100',
        p25: '20261100',
        p50: '20261101',
        p75: '20261102',
        p95: '20261103',
      })),
    });
    tricky.summaries[0] = {
      turn: 12,
      end: {
        p05: '20261100',
        p25: '20271100',
        p50: '19971100',
        p75: '20261199',
        p95: '20260000',
      },
      mdd: {
        p05: '-0.19970000',
        p10: '-0.20260000',
        p25: '-0.20110000',
        p50: '-0.19990000',
        p75: '-0.20120000',
        p95: '-0.19980000',
      },
      breachProbability: '0.20260000',
    };
    serveProjection({ answer: () => HttpResponse.json(tricky) });
    const { container } = renderRoute(ROUTE);
    await screen.findByRole('img');
    expectNoDatePattern(container);
  });

  it('P11: a bad season id is the not-found page', async () => {
    renderRoute('/seasons/abc/projection');
    await screen.findByRole('heading', { name: '페이지를 찾을 수 없습니다' });
  });

  it('P11: a missing season says so and requests no projection', async () => {
    const world = serveProjection();
    server.use(
      http.get('*/api/v1/seasons/7', () =>
        problem(404, { code: 'NOT_FOUND', status: 404 }),
      ),
    );
    renderRoute(ROUTE);
    await screen.findByText('시즌을 찾을 수 없습니다.');
    expect(world.phases).toEqual([]);
  });

  it('a 409 from the server refreshes the season state and shows the catalog sentence', async () => {
    const world = serveProjection({
      answer: () => problem(409, { code: 'SEASON_NOT_ACTIVE', status: 409 }),
    });
    renderRoute(ROUTE);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('진행 중인 시즌이 아니어서');
    await waitFor(() => {
      expect(world.seasonGets).toBeGreaterThanOrEqual(2);
    });
  });
});
