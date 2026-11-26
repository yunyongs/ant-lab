import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { describe, expect, it } from 'vitest';
import { problem } from '../test/authHandlers.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';
import type { components } from '../api/schema';

type Board = components['schemas']['ManagerLeaderboardDto'];

const base = (): Board => ({
  formula: {
    version: 's69-v1',
    status: 'PROVISIONAL',
    weights: {
      return: '0.30',
      riskAdjusted: '0.30',
      risk: '0.15',
      process: '0.25',
    },
  },
  referenceVersion: 'ref69-v1',
  processScoreVersions: ['s63-v3'],
  kind: 'ALL',
  pendingSeasons: 0,
  rows: [],
  unranked: { humans: 0, agents: 0 },
  me: null,
});

function serve(board: Board | ((kind: string) => Board)) {
  server.use(
    http.get('*/api/v1/leaderboards/manager', ({ request }) => {
      const kind = new URL(request.url).searchParams.get('kind') ?? 'ALL';
      return HttpResponse.json(
        typeof board === 'function' ? board(kind) : board,
      );
    }),
  );
}

function row(
  overrides: Partial<components['schemas']['ManagerLeaderboardRowDto']> = {},
) {
  return {
    rank: 1,
    kind: 'HUMAN' as const,
    displayName: '민수',
    me: false,
    ownedByMe: false,
    l: '-3.25',
    trimmedMean: '42.10',
    sampleSd: '5.00',
    seasonsUsed: 3,
    meanMultiple: '1.25000000',
    counts: {
      counted: 3,
      voided: 0,
      unfinished: 0,
      awaitingLeagueClose: 0,
      pending: 0,
      notStarted: 0,
      mismatch: 0,
      noReference: 0,
    },
    agent: null,
    ...overrides,
  } satisfies components['schemas']['ManagerLeaderboardRowDto'];
}

describe('LeaderboardPage', () => {
  it('sends kind for tabs and updates aria-pressed', async () => {
    const kinds: string[] = [];
    server.use(
      http.get('*/api/v1/leaderboards/manager', ({ request }) => {
        const kind = new URL(request.url).searchParams.get('kind') ?? '';
        kinds.push(kind);
        return HttpResponse.json({ ...base(), kind });
      }),
    );
    const user = userEvent.setup();
    renderRoute('/leaderboard');
    await screen.findByRole('heading', { name: '리더보드' });
    for (const [label, kind] of [
      ['전체', 'ALL'],
      ['사람', 'HUMAN'],
      ['에이전트', 'AGENT'],
    ] as const) {
      await user.click(screen.getByRole('button', { name: label }));
      await waitFor(() => expect(kinds).toContain(kind));
      expect(screen.getByRole('button', { name: label })).toHaveAttribute(
        'aria-pressed',
        'true',
      );
      for (const other of ['전체', '사람', '에이전트']) {
        if (other !== label) {
          expect(screen.getByRole('button', { name: other })).toHaveAttribute(
            'aria-pressed',
            'false',
          );
        }
      }
    }
  });

  it('shows provisional badge, decimals, and only non-zero counts', async () => {
    const board = base();
    board.rows = [
      row({ counts: { ...row().counts, pending: 2, unfinished: 0 } }),
    ];
    serve(board);
    renderRoute('/leaderboard');
    const list = await screen.findByRole('list', { name: '순위 목록' });
    const card = within(list).getByRole('listitem');
    expect(screen.getByText('잠정 공식 s69-v1')).toBeInTheDocument();
    expect(within(card).getByText('L -3.25')).toBeInTheDocument();
    expect(within(card).getByText('계산 대기 2')).toBeInTheDocument();
    expect(within(card).queryByText(/진행 중/)).toBeNull();
    expect(within(card).getByText('평균 평가 배수 1.25배')).toBeInTheDocument();
  });

  it('renders every non-zero count with its matching label', async () => {
    const board = base();
    board.rows = [
      row({
        counts: {
          counted: 3,
          voided: 1,
          unfinished: 2,
          awaitingLeagueClose: 3,
          pending: 4,
          notStarted: 5,
          mismatch: 6,
          noReference: 7,
        },
      }),
    ];
    serve(board);
    renderRoute('/leaderboard');
    const card = within(
      await screen.findByRole('list', { name: '순위 목록' }),
    ).getByRole('listitem');
    expect(
      within(card).getByText(
        '무효 1 · 진행 중 2 · 리그 종료 대기 3 · 계산 대기 4 · 미시작 5 · 기준 불일치 6 · 기준 없음 7',
      ),
    ).toBeInTheDocument();
  });

  it('hides the badge for a final formula and renders agent NOT_TESTED details', async () => {
    const board = base();
    board.formula.status = 'FINAL';
    board.rows = [
      row({
        kind: 'AGENT',
        displayName: '탐험가 · model-x',
        ownedByMe: true,
        agent: {
          personaName: '탐험가',
          personaVersion: 2,
          personaVersionsUsed: [1, 2],
          memoryVersion: null,
          modelId: 'model-x',
          failedTurns: 4,
          detection: {
            status: 'NOT_TESTED',
            testVersion: null,
            sampleLayer: null,
            runAt: null,
            windows: null,
          },
          outlook: null,
        },
      }),
    ];
    serve(board);
    renderRoute('/leaderboard');
    const list = await screen.findByRole('list', { name: '순위 목록' });
    const card = within(list).getByRole('listitem');
    expect(screen.queryByText(/잠정 공식/)).toBeNull();
    expect(within(card).getByText('에이전트')).toBeInTheDocument();
    expect(within(card).getByText('내 에이전트')).toBeInTheDocument();
    expect(within(card).getByText('탐지 시험 전')).toBeInTheDocument();
    expect(within(card).getByText(/기억 없음/)).toBeInTheDocument();
  });

  it('renders Korean detection labels, accuracy, and base rate', async () => {
    const board = base();
    board.rows = [
      row({
        kind: 'AGENT',
        agent: {
          personaName: '분석가',
          personaVersion: 1,
          personaVersionsUsed: [1],
          memoryVersion: 3,
          modelId: 'model-y',
          failedTurns: 0,
          detection: {
            status: 'TESTED',
            testVersion: 'detection-month/1',
            sampleLayer: 'P0-08',
            runAt: '2026-01-01T00:00:00Z',
            windows: [
              {
                months: 1,
                units: 8,
                runAt: '2026-01-01T00:00:00Z',
                metrics: [
                  {
                    metric: 'YM_EXACT',
                    accuracy: '0.12500000',
                    baseRate: '0.01000000',
                  },
                  {
                    metric: 'KR_NEXT',
                    accuracy: '0.25',
                    baseRate: '0.5',
                  },
                ],
              },
            ],
          },
          outlook: null,
        },
      }),
    ];
    serve(board);
    renderRoute('/leaderboard');
    const list = await screen.findByRole('list', { name: '순위 목록' });
    const card = within(list).getByRole('listitem');
    expect(within(card).getByText(/1개월 · 8단위/)).toBeInTheDocument();
    expect(
      within(card).getByText(
        '1개월 · 8단위 · 연월 정확 정확도 12.50% / 기준 1.00%, 다음 달 한국 방향 정확도 25.00% / 기준 50.00%',
      ),
    ).toBeInTheDocument();
  });

  it('renders me progress, excluded reasons, and ranked me values', async () => {
    const board = base();
    board.me = {
      kind: 'HUMAN',
      counted: 3,
      minimum: 3,
      ranked: true,
      rank: 2,
      l: '61.25',
      counts: row().counts,
      seasons: [
        {
          seasonNo: 4,
          excluded: 'PENDING',
          used: false,
          s: null,
          parts: null,
          processScoreVersion: null,
          multiple: null,
        },
        {
          seasonNo: 3,
          excluded: null,
          used: true,
          s: '58.1200',
          parts: {
            return: '50',
            riskAdjusted: '60',
            risk: '70',
            process: '55',
          },
          processScoreVersion: 's63-v3',
          multiple: '1.20000000',
        },
        {
          seasonNo: 2,
          excluded: null,
          used: false,
          s: '40',
          parts: null,
          processScoreVersion: 's63-v3',
          multiple: '1',
        },
      ],
    };
    serve(board);
    renderRoute('/leaderboard');
    expect(await screen.findByText('집계 3시즌')).toBeInTheDocument();
    expect(screen.getByText('순위 2위 · L 61.25')).toBeInTheDocument();
    expect(screen.getByText('점수 계산 대기 중')).toBeInTheDocument();
    expect(screen.getByText('시즌 3')).toBeInTheDocument();
    expect(screen.getByText('58.12 · 1.20배')).toBeInTheDocument();
    expect(
      screen.getByText(/40\.00 · 1\.00배 · L 제외\(최근 10시즌 밖\)/),
    ).toBeInTheDocument();
  });

  it('keeps L when a ranked human has no rank in the selected kind', async () => {
    const board = base();
    board.kind = 'AGENT';
    board.me = {
      kind: 'HUMAN',
      counted: 3,
      minimum: 3,
      ranked: true,
      rank: null,
      l: '61.25',
      counts: row().counts,
      seasons: [],
    };
    serve(board);
    renderRoute('/leaderboard');
    expect(await screen.findByText('L 61.25')).toBeInTheDocument();
    expect(screen.queryByText(/순위 \d+위/)).toBeNull();
  });

  it('shows the not-ranked progress text when ranked is false', async () => {
    const board = base();
    board.me = {
      kind: 'HUMAN',
      counted: 2,
      minimum: 3,
      ranked: false,
      rank: null,
      l: null,
      counts: row().counts,
      seasons: [],
    };
    serve(board);
    renderRoute('/leaderboard');
    expect(
      await screen.findByText('아직 순위에 들지 않았습니다'),
    ).toBeInTheDocument();
  });

  it('renders the unranked summary and every excluded reason', async () => {
    const board = base();
    board.unranked = { humans: 4, agents: 5 };
    board.me = {
      kind: 'HUMAN',
      counted: 0,
      minimum: 3,
      ranked: false,
      rank: null,
      l: null,
      counts: row().counts,
      seasons: [
        'NOT_STARTED',
        'REFERENCE_MISMATCH',
        'NO_REFERENCE',
        'PENDING',
        'AWAITING_LEAGUE_CLOSE',
      ].map((excluded, index) => ({
        seasonNo: index + 1,
        excluded: excluded as NonNullable<
          Board['me']
        >['seasons'][number]['excluded'],
        used: false,
        s: null,
        parts: null,
        processScoreVersion: null,
        multiple: null,
      })),
    };
    serve(board);
    renderRoute('/leaderboard');
    expect(
      await screen.findByText(
        '순위 밖 사람 4명 · 에이전트 5명 (최소 시즌 미달)',
      ),
    ).toBeInTheDocument();
    for (const text of [
      '3턴 전에 포기해 제외',
      '기준 계산 불일치로 제외',
      '기준 집합을 만들 수 없어 제외',
      '점수 계산 대기 중',
      '리그가 닫히면 반영',
    ]) {
      expect(screen.getByText(text)).toBeInTheDocument();
    }
  });

  it('renders a 50-character display name and agent identifiers', async () => {
    const longName = 'a'.repeat(50);
    const board = base();
    board.rows = [
      row({
        kind: 'AGENT',
        displayName: longName,
        agent: {
          personaName: longName,
          personaVersion: 1,
          personaVersionsUsed: [1],
          memoryVersion: null,
          modelId: longName,
          failedTurns: 0,
          detection: {
            status: 'NOT_TESTED',
            testVersion: null,
            sampleLayer: null,
            runAt: null,
            windows: null,
          },
          outlook: null,
        },
      }),
    ];
    serve(board);
    renderRoute('/leaderboard');
    expect(await screen.findAllByText(longName)).toHaveLength(3);
  });

  it('renders empty board pending text and null me', async () => {
    const board = base();
    board.pendingSeasons = 3;
    serve(board);
    renderRoute('/leaderboard');
    expect(
      await screen.findByText('아직 순위에 든 매니저가 없습니다'),
    ).toBeInTheDocument();
    expect(screen.getByText('점수 계산 대기 3시즌')).toBeInTheDocument();
    expect(screen.getByText('아직 시즌이 없습니다')).toBeInTheDocument();
  });

  it('navigates to the leaderboard from the header and seasons list links', async () => {
    const user = userEvent.setup();
    serve(base());
    const home = renderRoute('/');
    await screen.findByRole('heading', { name: '내 시즌' });
    expect(
      within(screen.getByRole('banner')).getByRole('link', {
        name: '리더보드',
      }),
    ).toHaveAttribute('href', '/leaderboard');
    await user.click(
      within(screen.getByRole('main')).getByRole('link', {
        name: '리더보드',
      }),
    );
    expect(
      await screen.findByRole('heading', { name: '리더보드' }),
    ).toBeInTheDocument();
    expect(home.router.state.location.pathname).toBe('/leaderboard');

    await home.router.navigate('/');
    await screen.findByRole('heading', { name: '내 시즌' });
    await user.click(
      within(screen.getByRole('banner')).getByRole('link', {
        name: '리더보드',
      }),
    );
    expect(home.router.state.location.pathname).toBe('/leaderboard');
  });

  it('keeps previous data while the next kind loads', async () => {
    const initial = base();
    initial.rows = [row({ displayName: '전체 결과' })];
    const next = base();
    next.kind = 'AGENT';
    next.rows = [row({ kind: 'AGENT', displayName: '에이전트 결과' })];
    server.use(
      http.get('*/api/v1/leaderboards/manager', async ({ request }) => {
        if (new URL(request.url).searchParams.get('kind') === 'AGENT') {
          await delay(100);
          return HttpResponse.json(next);
        }
        return HttpResponse.json(initial);
      }),
    );
    const user = userEvent.setup();
    renderRoute('/leaderboard');
    expect(await screen.findByText('전체 결과')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '에이전트' }));
    expect(screen.getByText('전체 결과')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: '순위 목록' })).toHaveAttribute(
      'aria-busy',
      'true',
    );
    expect(await screen.findByText('에이전트 결과')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: '순위 목록' })).toHaveAttribute(
      'aria-busy',
      'false',
    );
  });

  it('shows an API error and retries the leaderboard request', async () => {
    let calls = 0;
    server.use(
      http.get('*/api/v1/leaderboards/manager', () => {
        calls += 1;
        return HttpResponse.error();
      }),
    );
    renderRoute('/leaderboard');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'API에 연결할 수 없습니다. 잠시 뒤 다시 시도해 주세요.',
    );
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: '다시 불러오기' }));
    await screen.findByRole('alert');
    expect(calls).toBe(2);
  });

  it('shows the catalog text for a Problem error', async () => {
    server.use(
      http.get('*/api/v1/leaderboards/manager', () =>
        problem(500, { code: 'INTERNAL_ERROR', status: 500 }),
      ),
    );
    renderRoute('/leaderboard');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '서버에 문제가 생겼습니다. 잠시 뒤 다시 시도해 주세요.',
    );
  });

  it('goes to login on a 401 without showing a page alert', async () => {
    server.use(
      http.get('*/api/v1/leaderboards/manager', () =>
        problem(401, { code: 'UNAUTHENTICATED', status: 401 }),
      ),
    );
    renderRoute('/leaderboard');
    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('links the signup hint with aria-describedby', async () => {
    renderRoute('/signup', { session: 'anonymous' });
    const name = await screen.findByLabelText('표시 이름');
    expect(name).toHaveAttribute('aria-describedby', 'signup-name-hint');
    expect(screen.getByText('리더보드에 보입니다 · 1–50자')).toHaveAttribute(
      'id',
      'signup-name-hint',
    );
  });
});
