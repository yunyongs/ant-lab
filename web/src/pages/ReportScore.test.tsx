import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../test/authHandlers.ts';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import { reportFixture } from '../test/fixtures/report.ts';
import {
  scoreFixture,
  scoreWithNullItems,
  scoreWithoutDistribution,
} from '../test/fixtures/score.ts';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';
import { serveReport } from '../test/serveReport.ts';
import { serveScore } from '../test/serveScore.ts';
import { settle } from '../test/settle.ts';

function card(): HTMLElement {
  const heading = screen.getByRole('heading', { level: 2, name: '시즌 점수' });
  const section = heading.closest('section');
  if (section === null) throw new Error('no score section');
  return section;
}

function valueOf(label: string): string {
  const row = within(card()).getByText(label).closest('div');
  const value = row?.querySelector('dd')?.textContent;
  if (value === undefined) throw new Error(`no value for ${label}`);
  return value;
}

async function openReport(route = '/seasons/7/report') {
  const view = renderRoute(route);
  await screen.findByRole('heading', { name: /성적표 \(/ });
  return view;
}

describe('ReportPage score card', () => {
  it('S1: prints the score, result, process, nine items in order, the item count and the version', async () => {
    serveReport();
    const world = serveScore();
    const { container } = await openReport();
    await screen.findByText('58.30점');
    expect(valueOf('점수')).toBe('58.30점');
    expect(valueOf('결과 점수')).toBe('63.10점');
    expect(valueOf('과정 점수')).toBe('51.45점');
    const labels = [
      '위험 준수',
      '매매 규율',
      '분산',
      '회전율',
      '일관성',
      '포메이션 준수',
      '역할 수행',
      '소음 반응',
      '독려 반응',
    ];
    const values = [
      '71.10',
      '48.20',
      '55.30',
      '39.40',
      '62.50',
      '44.60',
      '66.70',
      '33.80',
      '41.90',
    ];
    labels.forEach((label, at) => {
      expect(valueOf(label)).toBe(`${values[at] ?? ''}점`);
    });
    const dts = Array.from(card().querySelectorAll('dt')).map(
      (dt) => dt.textContent,
    );
    expect(dts.slice(3, 12)).toEqual(labels);
    expect(
      within(card()).getByRole('heading', {
        level: 3,
        name: '과정 항목 (값이 있는 9개의 평균)',
      }),
    ).toBeInTheDocument();
    expect(within(card()).getByText('공식 s63-v1 · 초안')).toBeInTheDocument();
    expect(
      within(card()).getByText('레이팅에 집계됩니다.'),
    ).toBeInTheDocument();
    expect(world.scoreGets).toBe(1);
    expectNoDatePattern(container);
  });

  it('S2: a null item says "아직 없음", never 0', async () => {
    serveReport();
    serveScore({ score: scoreWithNullItems });
    await openReport();
    await screen.findByText('58.30점');
    expect(valueOf('일관성')).toBe('아직 없음');
    expect(valueOf('소음 반응')).toBe('아직 없음');
    expect(valueOf('위험 준수')).toBe('71.10점');
    expect(card().textContent).not.toMatch(/(^|[^\d.])0(\.00)?점/);
    expect(
      within(card()).getByRole('heading', {
        level: 3,
        name: '과정 항목 (값이 있는 7개의 평균)',
      }),
    ).toBeInTheDocument();
  });

  it('S3: no distribution gives "점수 없음" with the exclusion sentence and keeps the process score', async () => {
    serveReport();
    serveScore({ score: scoreWithoutDistribution });
    await openReport();
    await screen.findByText('점수 없음');
    expect(valueOf('점수')).toBe('점수 없음');
    expect(valueOf('결과 점수')).toBe('해당 없음 (경로 분포가 없습니다)');
    expect(valueOf('과정 점수')).toBe('51.45점');
    expect(valueOf('운 백분위')).toBe('해당 없음 (경로 분포가 없습니다)');
    expect(
      within(card()).getByText('경로 분포를 만들 수 없어 레이팅에서 빠집니다.'),
    ).toBeInTheDocument();
  });

  it.each([
    ['NOT_STARTED', '3턴 전에 포기해 레이팅에서 빠집니다(미시작).'],
    [null, '레이팅에 집계되지 않습니다.'],
  ] as const)('S3: not counted with %s', async (ratingExclusion, text) => {
    serveReport();
    serveScore({
      score: scoreFixture({ countedInRating: false, ratingExclusion }),
    });
    await openReport();
    expect(await within(card()).findByText(text)).toBeInTheDocument();
    expect(within(card()).queryByText('레이팅에 집계됩니다.')).toBeNull();
  });

  it('S4: prints the server score as sent and the percentile without a complement', async () => {
    serveReport();
    serveScore({
      score: scoreFixture({
        score: '99.99',
        resultScore: '10.00',
        processScore: '10.00',
      }),
    });
    await openReport();
    await screen.findByText('99.99점');
    expect(valueOf('결과 점수')).toBe('10.00점');
    expect(valueOf('과정 점수')).toBe('10.00점');
    expect(valueOf('운 백분위')).toBe('경로 백분위 63.125%');
    expect(valueOf('샤프 차이 백분위')).toBe('경로 백분위 41.25%');
    const text = card().textContent;
    expect(text).not.toContain('36.875');
    expect(text).not.toContain('58.75');
    expect(text).not.toContain('상위');
    expect(
      within(card()).getByText(
        '운 백분위는 보여 주기만 하고 점수에는 넣지 않습니다.',
      ),
    ).toBeInTheDocument();
  });

  it('S5: a 503 gives a calm status with a retry; the report stays; one GET per click, none on its own', async () => {
    serveReport();
    let calls = 0;
    const world = serveScore({
      score: () => {
        calls += 1;
        return calls === 1
          ? problem(503, { code: 'ENGINE_UNAVAILABLE', status: 503 })
          : HttpResponse.json(scoreFixture());
      },
    });
    const user = userEvent.setup();
    const { queryClient } = await openReport();
    const retry = await screen.findByRole('button', { name: '다시 시도' });
    expect(card()).toHaveTextContent('지금 바쁘거나 연결되지 않습니다');
    expect(screen.queryByRole('alert')).toBeNull();
    expect(
      screen.getByRole('heading', { level: 2, name: '결과' }),
    ).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 100));
    await settle(queryClient);
    expect(world.scoreGets).toBe(1);
    await user.click(retry);
    await screen.findByText('58.30점');
    expect(world.scoreGets).toBe(2);
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
  });

  it('S6: a 409 with a stale cache shows the pending view and refetches the season; a 500 stays inside the card', async () => {
    let seasonGets = 0;
    server.use(
      http.get('*/api/v1/seasons/7', () => {
        seasonGets += 1;
        return HttpResponse.json(
          seasonGets === 1
            ? seasonFixture({ id: 7, status: 'FINISHED', currentTurnNo: 12 })
            : seasonFixture({ id: 7, status: 'IN_PROGRESS', currentTurnNo: 4 }),
        );
      }),
      http.get('*/api/v1/seasons/7/report', () =>
        HttpResponse.json(reportFixture()),
      ),
      http.get('*/api/v1/seasons/7/score', () =>
        problem(409, { code: 'SEASON_NOT_FINISHED', status: 409 }),
      ),
    );
    renderRoute('/seasons/7/report');
    expect(
      await screen.findByText('시즌이 끝나면 성적표가 열립니다'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    await waitFor(() => {
      expect(seasonGets).toBe(2);
    });
  });

  it('S6: a score 500 shows a QueryError in the card only, with a retry', async () => {
    serveReport();
    let calls = 0;
    const world = serveScore({
      score: () => {
        calls += 1;
        return calls === 1
          ? problem(500, { code: 'INTERNAL_ERROR', status: 500 })
          : HttpResponse.json(scoreFixture());
      },
    });
    const user = userEvent.setup();
    await openReport();
    const alert = await within(card()).findByRole('alert');
    expect(alert).toHaveTextContent('서버에 문제가 생겼습니다.');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(
      screen.getByRole('heading', { level: 2, name: '비용' }),
    ).toBeInTheDocument();
    await user.click(
      within(alert).getByRole('button', { name: '다시 불러오기' }),
    );
    await screen.findByText('58.30점');
    expect(world.scoreGets).toBe(2);
  });

  it('S7: no season route of an in-progress season sends a score request', async () => {
    const world = serveScore({
      season: { status: 'IN_PROGRESS', currentTurnNo: 3 },
    });
    const { router, queryClient } = renderRoute('/seasons/7/report');
    await screen.findByText('시즌이 끝나면 성적표가 열립니다');
    for (const path of [
      '/seasons/7',
      '/seasons/7/review',
      '/seasons/7/projection',
    ]) {
      await act(async () => {
        await router.navigate(path);
      });
      await new Promise((resolve) => setTimeout(resolve, 60));
      await settle(queryClient);
    }
    expect(world.scoreGets).toBe(0);
  });

  it('S12b: leaving the report and coming back sends no second score request', async () => {
    serveReport();
    const world = serveScore();
    const { router, queryClient } = await openReport();
    await screen.findByText('58.30점');
    await act(async () => {
      await router.navigate('/seasons/7');
    });
    await new Promise((resolve) => setTimeout(resolve, 60));
    await act(async () => {
      await router.navigate('/seasons/7/report');
    });
    await screen.findByText('58.30점');
    await settle(queryClient);
    expect(world.scoreGets).toBe(1);
  });

  it('C7: a 409 that the season does not confirm leaves a neutral line, not an empty card', async () => {
    serveReport();
    serveScore({
      score: () => problem(409, { code: 'SEASON_NOT_FINISHED', status: 409 }),
    });
    await openReport();
    expect(
      await within(card()).findByText('점수를 아직 볼 수 없습니다.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('S13: the limits no longer say the score comes later', async () => {
    serveReport();
    serveScore();
    await openReport();
    const limits = screen.getByText('이 성적표의 한계').closest('details');
    const lines = within(limits as HTMLElement)
      .getAllByRole('listitem')
      .map((li) => li.textContent);
    expect(lines).toHaveLength(4);
    expect(lines[3]).toBe(
      '연평균 성장률, Sharpe 값, 일관성 항목은 다음 단계에서 더합니다. 시즌 점수 공식은 초안입니다.',
    );
    expect(limits?.textContent).not.toContain('시즌 점수·레이팅');
  });

  it('S14: no date pattern with a score card whose digits look like years', async () => {
    serveReport();
    serveScore({
      score: scoreFixture({
        score: '20.11',
        resultScore: '19.97',
        luckPercentile: '0.20111997',
      }),
    });
    const { container } = await openReport();
    await screen.findByText('20.11점');
    expectNoDatePattern(container);
  });
});
