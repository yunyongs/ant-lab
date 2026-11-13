import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../test/authHandlers.ts';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { serveSeason } from '../test/serve.ts';
import { server } from '../test/server.ts';

describe('SeasonPage', () => {
  it('shows the summary and the team build at turn 0', async () => {
    serveSeason({});
    renderRoute('/seasons/7');
    const summary = await screen.findByRole('region', { name: '시즌 요약' });
    expect(within(summary).getByText('진행 중 · 팀 구성')).toBeInTheDocument();
    expect(within(summary).getByText('0 / 12턴')).toBeInTheDocument();
    expect(within(summary).getByText('-0.20000000 (-20%)')).toBeInTheDocument();
    expect(within(summary).getByText('100,000,000원')).toBeInTheDocument();
    expect(
      within(summary).getByText(
        '골키퍼 40 · 수비수 30 · 미드필더 20 · 공격수 10',
      ),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole('heading', { name: '0턴 팀 구성' }),
    ).toBeInTheDocument();
  });

  it('shows the placeholder for turn 1 or later', async () => {
    serveSeason({ currentTurnNo: 2 });
    renderRoute('/seasons/7');
    expect(
      await screen.findByText('턴 화면이 이 자리에 들어옵니다.'),
    ).toBeInTheDocument();
  });

  it('shows the finished text', async () => {
    serveSeason({ status: 'FINISHED', currentTurnNo: 12 });
    renderRoute('/seasons/7');
    expect(
      await screen.findByText(
        '시즌이 끝났습니다. 성적표와 복기는 준비 중입니다.',
      ),
    ).toBeInTheDocument();
  });

  it('shows a summary only for an abandoning season', async () => {
    serveSeason({ status: 'ABANDONING', currentTurnNo: 1 });
    renderRoute('/seasons/7');
    expect(await screen.findAllByText('정리 중')).not.toHaveLength(0);
    expect(screen.queryByText(/들어옵니다/)).toBeNull();
  });

  it('shows a not-found message with a link back for a 404', async () => {
    server.use(
      http.get('*/api/v1/seasons/7', () =>
        problem(404, { code: 'NOT_FOUND', status: 404 }),
      ),
    );
    renderRoute('/seasons/7');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '시즌을 찾을 수 없습니다.',
    );
    expect(screen.getByRole('link', { name: '내 시즌으로' })).toHaveAttribute(
      'href',
      '/',
    );
  });

  it.each(['abc', '1.5', '-3', '07'])(
    'renders the not-found page for id %j and sends no season request',
    async (id) => {
      let calls = 0;
      server.use(
        http.get('*/api/v1/seasons/:id', () => {
          calls += 1;
          return HttpResponse.json(seasonFixture());
        }),
      );
      renderRoute(`/seasons/${id}`);
      expect(
        await screen.findByRole('heading', {
          name: '페이지를 찾을 수 없습니다',
        }),
      ).toBeInTheDocument();
      await screen.findByText(/API 연결됨/);
      expect(calls).toBe(0);
    },
  );
});
