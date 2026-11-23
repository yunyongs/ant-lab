import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  analysisFixture,
  poisonedAnalysis,
  poisonedAnalysisStrings,
} from '../test/fixtures/analysis.ts';
import { problem } from '../test/authHandlers.ts';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { server } from '../test/server.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveAnalysis } from '../test/serveAnalysis.ts';
import { serveReview } from '../test/serveReview.ts';

function block(title: string): HTMLElement {
  const heading = screen.getByRole('heading', { level: 3, name: title });
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

async function openReview() {
  const view = renderRoute('/seasons/7/review');
  await screen.findByRole('heading', { level: 3, name: '관심 추종' });
  return view;
}

describe('review facts', () => {
  it('A6 shows attention following and defender goals with the not-scored sentence', async () => {
    serveReview();
    serveAnalysis();
    await openReview();
    expect(
      screen.getByRole('heading', {
        level: 2,
        name: '복기 사실 (점수에는 들어가지 않습니다)',
      }),
    ).toBeInTheDocument();

    const attention = block('관심 추종');
    expect(attention).toHaveTextContent('점수에는 들어가지 않습니다.');
    expect(valueOf(attention, '관심 상위 종목 매수')).toBe('4건 · 8,100,000원');
    expect(valueOf(attention, '관심 상위 매수의 6턴 뒤 수익률')).toBe('+4.50%');
    expect(valueOf(attention, '그 밖의 매수')).toBe('11건 · 9,200,000원');
    expect(valueOf(attention, '그 밖의 매수의 6턴 뒤 수익률')).toBe('+1.75%');
    expect(
      valueOf(attention, '창이 끝나지 않아 어느 쪽에도 넣지 않은 매수'),
    ).toBe('2건');

    const defender = block('수비수 득점');
    expect(defender).toHaveTextContent('점수에는 들어가지 않습니다.');
    expect(valueOf(defender, '시장이 내린 턴')).toBe('5턴');
    expect(valueOf(defender, '득점')).toBe('8회');
    expect(valueOf(defender, '득점 금액')).toBe('+777,000원');
    expect(valueOf(defender, '득점 수익률')).toBe('+0.78%');
  });

  it('A6 takes the window length from windowTurns and gives reasons for null returns', async () => {
    serveReview();
    const base = analysisFixture();
    serveAnalysis({
      analysis: analysisFixture({
        attentionFollowing: {
          ...base.attentionFollowing,
          windowTurns: 5,
          attentionReturn: null,
          otherReturn: null,
        },
      }),
    });
    await openReview();
    const attention = block('관심 추종');
    expect(valueOf(attention, '관심 상위 매수의 5턴 뒤 수익률')).toBe(
      '해당 없음 (해당 매수가 없습니다)',
    );
    expect(valueOf(attention, '그 밖의 매수의 5턴 뒤 수익률')).toBe(
      '해당 없음 (해당 매수가 없습니다)',
    );
    expect(attention).not.toHaveTextContent('6턴');
  });

  it('A7 prints no difference of the two returns', async () => {
    serveReview();
    // 0.04500000 - 0.01750000 = 0.02750000
    serveAnalysis();
    const { container } = await openReview();
    const text = block('관심 추종').textContent ?? '';
    expect(text).not.toContain('0.0275');
    expect(text).not.toContain('2.75');
    expect(container.innerHTML).not.toContain('0.02750000');
  });

  it('A10 prints named fields only on the review as well', async () => {
    serveReview();
    serveAnalysis({ analysis: poisonedAnalysis() });
    const { container } = await openReview();
    const facts = block('관심 추종').parentElement;
    for (const leaked of poisonedAnalysisStrings) {
      expect(facts?.innerHTML).not.toContain(leaked);
    }
    expect(container.textContent).toContain('2001-01');
  });

  it('C6 keeps the review rows when the analysis fails, and retries with one more GET', async () => {
    serveReview();
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
    renderRoute('/seasons/7/review');
    const facts = (
      await screen.findByRole('heading', { level: 2, name: /복기 사실/ })
    ).closest('section') as HTMLElement;
    expect(await within(facts).findByRole('alert')).toHaveTextContent(
      '서버에 문제가 생겼습니다.',
    );
    expect(
      screen.getByRole('heading', { level: 2, name: '턴별 복기' }),
    ).toBeInTheDocument();
    expect(calls).toBe(1);
    await user.click(
      within(facts).getByRole('button', { name: '다시 불러오기' }),
    );
    expect(
      await screen.findByRole('heading', { level: 3, name: '관심 추종' }),
    ).toBeInTheDocument();
    expect(calls).toBe(2);
  });

  it('C6 shows the pending view, never an alert, when a stale cache meets an analysis 409', async () => {
    let seasonGets = 0;
    serveReview();
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
    serveAnalysis({
      analysis: () =>
        problem(409, { code: 'SEASON_NOT_FINISHED', status: 409 }),
    });
    let alertSeen = false;
    const observer = new MutationObserver(() => {
      if (document.querySelector('[role="alert"]') !== null) alertSeen = true;
    });
    observer.observe(document.body, { childList: true, subtree: true });
    renderRoute('/seasons/7/review');
    expect(
      await screen.findByText('시즌이 끝나면 복기가 열립니다'),
    ).toBeInTheDocument();
    await waitFor(() => {
      expect(seasonGets).toBe(2);
    });
    observer.disconnect();
    expect(alertSeen).toBe(false);
  });
});
