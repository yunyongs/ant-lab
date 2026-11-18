import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../test/authHandlers.ts';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import {
  analysisFixture,
  poisonedAnalysis,
  poisonedAnalysisStrings,
  sourceRows,
} from '../test/fixtures/analysis.ts';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveAnalysis } from '../test/serveAnalysis.ts';
import { server } from '../test/server.ts';
import { notFinished409, serveReport } from '../test/serveReport.ts';
import { serveReview } from '../test/serveReview.ts';
import { settle } from '../test/settle.ts';

function block(title: string): HTMLElement {
  const heading = screen.getByRole('heading', { level: 2, name: title });
  const section = heading.closest('section');
  if (section === null) throw new Error(`no section for ${title}`);
  return section;
}

function valueOf(section: HTMLElement, label: string): string {
  const row = within(section).getByText(label).closest('div');
  const value = row?.querySelector('dd')?.textContent;
  if (value === undefined) throw new Error(`no value for ${label}`);
  return value;
}

async function openReport() {
  const view = renderRoute('/seasons/7/report');
  await screen.findByRole('heading', { level: 2, name: '소음 반응' });
  return view;
}

describe('report analysis blocks', () => {
  it('A1 shows every field of the noise, platform and change blocks', async () => {
    serveReport();
    serveAnalysis();
    await openReport();

    const noise = block('소음 반응');
    expect(valueOf(noise, '소음을 따른 비중')).toBe('0.31250000 (31.25%)');
    expect(valueOf(noise, '매매한 턴')).toBe('9턴');
    expect(valueOf(noise, '따라 산 금액')).toBe('1,234,000원');
    expect(valueOf(noise, '따라 판 금액')).toBe('567,000원');
    expect(valueOf(noise, '매매 대금')).toBe('21,500,000원');

    const platform = block('플랫폼의 수익');
    expect(
      within(platform).getByText(
        '이 시즌에 낸 수수료 30,101원 — 플랫폼에는 수익입니다.',
      ),
    ).toBeInTheDocument();
    expect(valueOf(platform, '총 수수료')).toBe('30,101원');
    expect(valueOf(platform, '0턴 팀 구성 수수료')).toBe('6,701원');
    const rows = within(platform).getAllByRole('row').slice(1);
    expect(rows.map((row) => row.textContent)).toEqual([
      '랭킹3건3,100,000원4,100원',
      '피드5건5,200,000원5,200원',
      '알림2건2,300,000원2,300원',
      '벤치4건4,400,000원4,400원',
      '직접7건7,500,000원7,500원',
    ]);
    expect(valueOf(platform, '랭킹·알림에서 시작된 수수료')).toBe('6,400원');
    expect(valueOf(platform, '그 몫의 비중')).toBe('0.21262000 (21.262%)');

    const change = block('전술 변경 뒤 6턴 효과');
    const items = within(change).getAllByRole('listitem');
    expect(items.map((li) => li.textContent)).toEqual([
      '3턴 변경 · 6턴 뒤 · 151,000원 (0.151%)',
      '8턴 변경 · 4턴 뒤 · -262,000원 (-0.262%)',
    ]);
  });

  it('A2 prints a reason for null shares and keeps the other rows', async () => {
    serveReport();
    const fixture = analysisFixture();
    serveAnalysis({
      analysis: analysisFixture({
        noise: { ...fixture.noise, noiseShare: null },
        platform: { ...fixture.platform, nudgedFeeShare: null },
      }),
    });
    await openReport();
    const noise = block('소음 반응');
    expect(valueOf(noise, '소음을 따른 비중')).toBe(
      '해당 없음 (매매한 턴이 없습니다)',
    );
    expect(valueOf(noise, '매매한 턴')).toBe('9턴');
    const platform = block('플랫폼의 수익');
    expect(valueOf(platform, '그 몫의 비중')).toBe(
      '해당 없음 (총 수수료가 0입니다)',
    );
    expect(valueOf(platform, '랭킹·알림에서 시작된 수수료')).toBe('6,400원');
    expect(within(platform).queryByText(/0%/)).toBeNull();
  });

  it('A3 tells null effects from an empty list, keeps equal turns and a minus sign', async () => {
    serveReport();
    serveAnalysis({ analysis: analysisFixture({ changeEffects: null }) });
    const first = await openReport();
    const unavailable = block('전술 변경 뒤 6턴 효과');
    expect(
      within(unavailable).getByText(
        '포메이션 기준선을 만들 수 없어 계산하지 않았습니다',
      ),
    ).toBeInTheDocument();
    expect(
      within(unavailable).queryByText('이 시즌에는 전술을 바꾸지 않았습니다.'),
    ).toBeNull();
    first.unmount();

    serveAnalysis({ analysis: analysisFixture({ changeEffects: [] }) });
    const second = await openReport();
    const empty = block('전술 변경 뒤 6턴 효과');
    expect(
      within(empty).getByText('이 시즌에는 전술을 바꾸지 않았습니다.'),
    ).toBeInTheDocument();
    expect(within(empty).queryByText(/계산하지 않았습니다/)).toBeNull();
    second.unmount();

    serveAnalysis({
      analysis: analysisFixture({
        changeEffects: [
          {
            turnNo: 4,
            windowTurns: 6,
            effectWon: '-5000',
            effectReturn: '-0.00005000',
          },
          {
            turnNo: 4,
            windowTurns: 2,
            effectWon: '7000',
            effectReturn: '0.00007000',
          },
        ],
      }),
    });
    await openReport();
    const items = within(block('전술 변경 뒤 6턴 효과')).getAllByRole(
      'listitem',
    );
    expect(items.map((li) => li.textContent)).toEqual([
      '4턴 변경 · 6턴 뒤 · -5,000원 (-0.005%)',
      '4턴 변경 · 2턴 뒤 · 7,000원 (0.007%)',
    ]);
    expect(block('전술 변경 뒤 6턴 효과')).not.toHaveTextContent(/손실|이익/);
  });

  it('A4 prints broken identities as sent and computes no other number', async () => {
    serveReport();
    const base = analysisFixture();
    const platform = {
      ...base.platform,
      totalFee: '99999',
      nudgedFee: '1111',
    };
    serveAnalysis({
      analysis: analysisFixture({
        platform,
        noise: { ...base.noise, tradeNotional: '88888' },
      }),
    });
    await openReport();
    const section = block('플랫폼의 수익');
    const text = (section.textContent ?? '').replaceAll(',', '');
    expect(text).toContain('99999원');
    expect(text).toContain('1111원');
    expect(block('소음 반응')).toHaveTextContent('88,888원');
    // Digit runs on screen are exactly digit runs of server strings (or of
    // their percent form); the sum of the rows (23500) is not among them.
    const allowed = new Set(
      [
        ...JSON.stringify(platform).matchAll(/\d+/g),
        ...['21', '262'].map((digits) => [digits]),
        ...['0', '1'].map((digits) => [digits]),
      ].map((match) => match[0]),
    );
    const runs = text.match(/\d+/g) ?? [];
    expect(runs.length).toBeGreaterThan(0);
    for (const run of runs) expect(allowed.has(run)).toBe(true);
    expect(text).not.toContain('23500');
    expect(within(section).queryByText(/합계/)).toBeNull();
  });

  it('A5 renders source rows in the order and count received', async () => {
    serveReport();
    const base = analysisFixture();
    serveAnalysis({
      analysis: analysisFixture({
        platform: { ...base.platform, bySource: [...sourceRows()].reverse() },
      }),
    });
    const first = await openReport();
    let rows = within(block('플랫폼의 수익')).getAllByRole('row').slice(1);
    expect(rows.map((row) => row.querySelector('th')?.textContent)).toEqual([
      '직접',
      '벤치',
      '알림',
      '피드',
      '랭킹',
    ]);
    first.unmount();

    serveAnalysis({
      analysis: analysisFixture({
        platform: { ...base.platform, bySource: sourceRows().slice(0, 4) },
      }),
    });
    const second = await openReport();
    rows = within(block('플랫폼의 수익')).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(4);
    second.unmount();

    serveAnalysis({
      analysis: analysisFixture({
        platform: {
          ...base.platform,
          bySource: [
            ...sourceRows(),
            { source: 'DIRECT', orders: 1, tradeNotional: '1', fee: '1' },
          ],
        },
      }),
    });
    await openReport();
    rows = within(block('플랫폼의 수익')).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(6);
  });

  it('A9 keeps the report rows when the analysis fails, and retries with one more GET', async () => {
    serveReport();
    let calls = 0;
    server.use(
      http.get('*/api/v1/seasons/7/report/analysis', () => {
        calls += 1;
        return calls === 1
          ? problem(500, { code: 'INTERNAL_ERROR', status: 500 })
          : HttpResponse.json(analysisFixture());
      }),
    );
    const user = userEvent.setup();
    renderRoute('/seasons/7/report');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '서버에 문제가 생겼습니다.',
    );
    expect(
      screen.getByRole('heading', { level: 2, name: '결과' }),
    ).toBeInTheDocument();
    expect(calls).toBe(1);
    await user.click(screen.getByRole('button', { name: '다시 불러오기' }));
    expect(
      await screen.findByRole('heading', { level: 2, name: '소음 반응' }),
    ).toBeInTheDocument();
    expect(calls).toBe(2);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('A9 shows the pending view, no alert, when a stale cache meets an analysis 409', async () => {
    let seasonGets = 0;
    serveReport();
    server.use(
      http.get('*/api/v1/seasons/7', () => {
        seasonGets += 1;
        return HttpResponse.json(
          seasonGets === 1
            ? seasonFixture({ id: 7, status: 'FINISHED', currentTurnNo: 12 })
            : seasonFixture({ id: 7, status: 'IN_PROGRESS', currentTurnNo: 4 }),
        );
      }),
    );
    serveAnalysis({ analysis: notFinished409 });
    let alertSeen = false;
    const observer = new MutationObserver(() => {
      if (document.querySelector('[role="alert"]') !== null) alertSeen = true;
    });
    observer.observe(document.body, { childList: true, subtree: true });
    renderRoute('/seasons/7/report');
    expect(
      await screen.findByText('시즌이 끝나면 성적표가 열립니다'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    await waitFor(() => {
      expect(seasonGets).toBe(2);
    });
    expect(screen.queryByRole('alert')).toBeNull();
    observer.disconnect();
    expect(alertSeen).toBe(false);
  });

  it('C5 prints an unknown order source as sent', async () => {
    serveReport();
    const base = analysisFixture();
    serveAnalysis({
      analysis: analysisFixture({
        platform: {
          ...base.platform,
          bySource: [
            {
              source: 'NEWSRC' as 'DIRECT',
              orders: 1,
              tradeNotional: '1000',
              fee: '10',
            },
          ],
        },
      }),
    });
    await openReport();
    const rows = within(block('플랫폼의 수익')).getAllByRole('row').slice(1);
    expect(rows[0]?.querySelector('th')?.textContent).toBe('NEWSRC');
  });

  it('C2 labels the scroll box and the not-scored lines', async () => {
    serveReport();
    serveAnalysis();
    await openReport();
    const region = screen.getByRole('region', { name: '출발점별 주문 표' });
    expect(region).toHaveAttribute('tabindex', '0');
    expect(block('플랫폼의 수익')).toHaveTextContent(
      '점수에는 들어가지 않습니다.',
    );
    expect(block('전술 변경 뒤 6턴 효과')).toHaveTextContent(
      '점수에는 들어가지 않습니다. 시즌 끝 가까이에서 바꾸면 6턴보다 짧게 잽니다.',
    );
    expect(block('소음 반응')).not.toHaveTextContent('점수에는');
  });

  it('A10 prints named fields only: nothing of the unrendered blocks or extra keys', async () => {
    serveReport();
    serveAnalysis({ analysis: poisonedAnalysis() });
    const { container } = await openReport();
    for (const leaked of poisonedAnalysisStrings) {
      expect(container.innerHTML).not.toContain(leaked);
    }
  });

  it('A12 prints no date pattern next to year-like digits', async () => {
    serveReport();
    const base = analysisFixture();
    serveAnalysis({
      analysis: analysisFixture({
        noise: {
          ...base.noise,
          followedBuyNotional: '20261100',
          noiseShare: '0.19970000',
        },
        changeEffects: [
          {
            turnNo: 11,
            windowTurns: 6,
            effectWon: '20261100',
            effectReturn: '0.19970000',
          },
        ],
      }),
    });
    const { container } = await openReport();
    expect(container).toHaveTextContent('20,261,100원');
    expectNoDatePattern(container);
  });
});

describe('analysis request boundary', () => {
  it('A8 sends one analysis GET for the report and then the review of a season', async () => {
    serveReport();
    serveReview();
    const world = serveAnalysis();
    const { router, queryClient } = await openReport();
    expect(world.analysisGets).toBe(1);
    await act(async () => {
      await router.navigate('/seasons/7/review');
    });
    await screen.findByRole('heading', { name: '관심 추종' });
    await settle(queryClient);
    expect(world.analysisGets).toBe(1);
  });

  it('A11 sends no analysis request for an in-progress season', async () => {
    const world = serveAnalysis();
    server.use(
      http.get('*/api/v1/seasons/7', () =>
        HttpResponse.json(seasonFixture({ id: 7, currentTurnNo: 3 })),
      ),
    );
    const { router, queryClient } = renderRoute('/seasons/7');
    await screen.findByRole('heading', { name: '시즌 7' });
    for (const path of ['/seasons/7/report', '/seasons/7/review']) {
      await act(async () => {
        await router.navigate(path);
      });
      await settle(queryClient);
      await new Promise((resolve) => setTimeout(resolve, 60));
    }
    expect(world.analysisGets).toBe(0);
  });
});
