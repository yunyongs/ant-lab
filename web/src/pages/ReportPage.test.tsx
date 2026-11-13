import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../test/authHandlers.ts';
import { expectNoDatePattern } from '../test/expectNoDatePattern.ts';
import {
  poisonedReport,
  reportFixture,
  reportWithNulls,
} from '../test/fixtures/report.ts';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';
import { notFinished409, serveReport } from '../test/serveReport.ts';

function group(title: string): HTMLElement {
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

const ROWS: [string, string, string][] = [
  ['결과', '총 수익률', '0.12345600 (12.3456%)'],
  ['결과', '초기 자금', '100,000,000원'],
  ['결과', '최종 평가액', '112,345,600원'],
  ['결과', '기준선 최종 평가액', '105,432,100원'],
  ['결과', '기준선 수익률', '0.05432100 (5.4321%)'],
  ['결과', '초과 수익률', '0.06913500 (6.9135%)'],
  ['결과', '월 변동성', '0.03210000 (3.21%)'],
  ['결과', '최대 낙폭', '-0.08760000 (-8.76%)'],
  ['위험 준수', '위험 선언', '-0.20000000 (-20%)'],
  ['위험 준수', '선언을 넘은 턴', '2턴'],
  ['위험 준수', '가장 깊이 넘은 정도', '0.01230000 (1.23%p)'],
  ['위험 준수', '최대 단일 종목 비중', '0.31500000 (31.5%)'],
  ['과정', '연 환산 회전율', '0.48765400 (48.7654%)'],
  ['과정', '포메이션 이탈 평균', '0.21098000 (21.098%p)'],
  ['과정', '역할 수행', '0.66670000 (66.67%) (판정 5 · 미판정 3)'],
  ['과정', '독려 비중', '0.25000000 (25%)'],
  ['과정', '같은 턴 독려 비중', '0.12500000 (12.5%)'],
  ['과정', '전술 변경', '3회 (연결 주문 4건 · 주문 없는 변경 1회)'],
  ['비용', '매매 대금 합 (0턴 제외)', '48,765,400원'],
  ['비용', '총 수수료 (0턴 포함)', '71,234원'],
  ['비용', '0턴 팀 구성 수수료', '15,432원'],
  ['비용', '전술 변경에 묶인 수수료', '3,210원'],
  ['비용', '플랫폼 독려 몫 수수료', '1,876원'],
  ['대사', '기록된 불일치 행 (복구된 시도 포함)', '6행'],
  ['대사', '턴 넘기기 검산 묶음', '11묶음'],
  ['대사', '수동 검산 묶음', '7묶음'],
  ['대사', '경로 밖 턴의 검산 행', '8행'],
];

describe('ReportPage', () => {
  it('shows the finished report: title, meta line and every row in its group', async () => {
    serveReport();
    const { container } = renderRoute('/seasons/7/report');
    expect(
      await screen.findByRole('heading', { name: '시즌 7 성적표 (확정)' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('12/12턴 보고 · 보류 주문 0건 · 보류 전술 변경 0건'),
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole('banner')).getByText('시즌 7 · 12/12턴 · 끝남'),
    ).toBeInTheDocument();
    for (const title of ['결과', '위험 준수', '과정', '비용', '대사']) {
      expect(group(title)).toBeInTheDocument();
    }
    for (const [title, label, expected] of ROWS) {
      expect(valueOf(group(title), label)).toBe(expected);
    }
    expect(
      within(group('결과')).getByText(/기준선: 국내·미국 시장 지수 대용 ETF/),
    ).toBeInTheDocument();
    expectNoDatePattern(container);
  });

  it('marks a not-final report as provisional', async () => {
    serveReport({ report: reportFixture({ isFinal: false }) });
    renderRoute('/seasons/7/report');
    expect(
      await screen.findByRole('heading', { name: '시즌 7 성적표 (잠정)' }),
    ).toBeInTheDocument();
  });

  it('says why each null cell cannot be computed and keeps the other cells', async () => {
    serveReport({ report: reportWithNulls });
    renderRoute('/seasons/7/report');
    await screen.findByRole('heading', { name: /성적표 \(/ });
    expect(valueOf(group('결과'), '월 변동성')).toBe(
      '해당 없음 (한 턴뿐이라 계산하지 않습니다)',
    );
    expect(valueOf(group('과정'), '역할 수행')).toBe(
      '해당 없음 (실측된 선수가 없습니다) (판정 0 · 미판정 3)',
    );
    expect(valueOf(group('과정'), '독려 비중')).toBe(
      '해당 없음 (매매 대금이 0입니다)',
    );
    expect(valueOf(group('과정'), '같은 턴 독려 비중')).toBe(
      '해당 없음 (매매 대금이 0입니다)',
    );
    expect(valueOf(group('과정'), '연 환산 회전율')).toBe(
      '0.48765400 (48.7654%)',
    );
    expect(screen.queryByText('null')).toBeNull();
  });

  it('folds cost and reconciliation, with their summary values, until opened', async () => {
    serveReport();
    const user = userEvent.setup();
    renderRoute('/seasons/7/report');
    await screen.findByRole('heading', { name: /성적표 \(/ });
    for (const [title, summary] of [
      ['비용', '총 수수료 71,234원'],
      ['대사', '불일치 6행'],
    ] as const) {
      const section = group(title);
      const details = section.querySelector('details');
      expect(details?.open).toBe(false);
      expect(within(section).getByText(summary)).toBeInTheDocument();
      await user.click(within(section).getByText(summary));
      expect(details?.open).toBe(true);
    }
    expect(
      screen.getByText(/턴을 넘길 때마다 계좌를 맞춰 본 기록입니다/),
    ).toBeInTheDocument();
    const limits = screen.getByText('이 성적표의 한계').closest('details');
    expect(limits?.open).toBe(false);
    expect(within(limits as HTMLElement).getAllByRole('listitem')).toHaveLength(
      4,
    );
  });

  it('keeps 18-digit money and tiny ratios exact', async () => {
    serveReport({
      report: reportFixture({
        finalValue: '123456789012345678',
        maxDrawdown: '-0.00000001',
      }),
    });
    renderRoute('/seasons/7/report');
    await screen.findByRole('heading', { name: /성적표 \(/ });
    expect(valueOf(group('결과'), '최종 평가액')).toBe(
      '123,456,789,012,345,678원',
    );
    expect(valueOf(group('결과'), '최대 낙폭')).toBe(
      '-0.00000001 (-0.000001%)',
    );
  });

  it('links the review entry and links back to the turn', async () => {
    serveReport();
    renderRoute('/seasons/7/report');
    await screen.findByRole('heading', { name: /성적표 \(/ });
    expect(screen.getByRole('link', { name: '복기 보기' })).toHaveAttribute(
      'href',
      '/seasons/7/review',
    );
    expect(
      screen.getAllByRole('link', { name: '턴으로 돌아가기' })[0],
    ).toHaveAttribute('href', '/seasons/7');
  });

  it.each([
    ['IN_PROGRESS', 4, '지금은 4/12턴입니다.'],
    ['ABANDONING', 4, '정리 중인 시즌이라 아직 성적표를 볼 수 없습니다.'],
  ] as const)(
    'shows the pending view for %s and sends no report request',
    async (status, currentTurnNo, text) => {
      const world = serveReport({ season: { status, currentTurnNo } });
      const { container } = renderRoute('/seasons/7/report');
      const box = (await screen.findByText(new RegExp(text))).closest(
        '[role="status"]',
      );
      expect(box).not.toBeNull();
      expect(
        screen.getByRole('link', { name: '턴으로 돌아가기' }),
      ).toHaveAttribute('href', '/seasons/7');
      expect(screen.queryByRole('alert')).toBeNull();
      expectNoDatePattern(container);
      expect(world.reportGets).toBe(0);
    },
  );

  it('shows the pending view, not an alert, when a stale cache meets a 409, and refetches the season', async () => {
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
      http.get('*/api/v1/seasons/7/report', () => notFinished409()),
    );
    renderRoute('/seasons/7/report');
    expect(
      await screen.findByText('시즌이 끝나면 성적표가 열립니다'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
    await waitFor(() => {
      expect(seasonGets).toBe(2);
    });
    expect(await screen.findByText(/지금은 4\/12턴입니다/)).toBeInTheDocument();
  });

  it('keeps the pending view (no alert at any time) while a 409 contradicts the season status', async () => {
    serveReport({ report: notFinished409 });
    renderRoute('/seasons/7/report');
    expect(
      await screen.findByText('시즌이 끝나면 성적표가 열립니다'),
    ).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByRole('alert')).toBeNull();
    });
  });

  it('shows a Problem as a catalog message and retries with one more GET', async () => {
    let calls = 0;
    server.use(
      http.get('*/api/v1/seasons/7', () =>
        HttpResponse.json(
          seasonFixture({ id: 7, status: 'FINISHED', currentTurnNo: 12 }),
        ),
      ),
      http.get('*/api/v1/seasons/7/report', () => {
        calls += 1;
        return calls === 1
          ? problem(500, { code: 'INTERNAL_ERROR', status: 500 })
          : HttpResponse.json(reportFixture());
      }),
    );
    const user = userEvent.setup();
    renderRoute('/seasons/7/report');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '서버에 문제가 생겼습니다.',
    );
    await user.click(screen.getByRole('button', { name: '다시 불러오기' }));
    await screen.findByRole('heading', { name: '시즌 7 성적표 (확정)' });
    expect(calls).toBe(2);
  });

  it('answers a 404 and a network error with their messages', async () => {
    serveReport({
      report: () => problem(404, { code: 'NOT_FOUND', status: 404 }),
    });
    const first = renderRoute('/seasons/7/report');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '찾는 항목이 없습니다.',
    );
    first.unmount();
    serveReport({ report: () => HttpResponse.error() });
    renderRoute('/seasons/7/report');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'API에 연결할 수 없습니다.',
    );
  });

  it('is not found, with no request, for a route id that is not a number', () => {
    let requests = 0;
    server.use(
      http.get('*/api/v1/seasons/*', () => {
        requests += 1;
        return HttpResponse.json(seasonFixture());
      }),
    );
    renderRoute('/seasons/x/report');
    expect(
      screen.getByRole('heading', { name: '페이지를 찾을 수 없습니다' }),
    ).toBeInTheDocument();
    expect(requests).toBe(0);
  });

  it('never prints a date, year-month, seed or unknown field of the payload', async () => {
    serveReport({ report: poisonedReport(reportFixture()) });
    const user = userEvent.setup();
    const { container } = renderRoute('/seasons/7/report');
    await screen.findByRole('heading', { name: /성적표 \(/ });
    await user.click(screen.getByText('총 수수료 71,234원'));
    await user.click(screen.getByText('불일치 6행'));
    expectNoDatePattern(container);
    for (const leaked of ['2008-09', '-8123456789012345678', '2026-11-12']) {
      expect(container.innerHTML).not.toContain(leaked);
    }
  });

  it('keys the page by season: another season shows its own pending view, nothing of season 7', async () => {
    serveReport();
    server.use(
      http.get('*/api/v1/seasons/8', () =>
        HttpResponse.json(
          seasonFixture({ id: 8, status: 'IN_PROGRESS', currentTurnNo: 2 }),
        ),
      ),
    );
    const { router, container } = renderRoute('/seasons/7/report');
    await screen.findByRole('heading', { name: '시즌 7 성적표 (확정)' });
    await act(async () => {
      await router.navigate('/seasons/8/report');
    });
    expect(await screen.findByText(/지금은 2\/12턴입니다/)).toBeInTheDocument();
    expect(container.textContent).not.toContain('112,345,600원');
  });
});
