import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { seasonFixture } from '../test/fixtures/seasons.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';

function serveSeasons(items: ReturnType<typeof seasonFixture>[]) {
  server.use(http.get('*/api/v1/seasons', () => HttpResponse.json({ items })));
}

function failSeasons() {
  server.use(http.get('*/api/v1/seasons', () => HttpResponse.error()));
}

const navOf = () => screen.getByRole('navigation', { name: '주 메뉴' });
const turnLink = () => within(navOf()).queryByRole('link', { name: '턴' });
const tradeLink = () => within(navOf()).queryByRole('link', { name: '매매' });

const menuButton = () => screen.getByRole('button', { name: '메뉴' });

describe('AppShell header menu', () => {
  it('shows a one-row header: title, status text for readers, menu button', async () => {
    renderRoute('/');
    const header = screen.getByRole('banner');
    expect(within(header).getByRole('status')).toBeInTheDocument();
    expect(await within(header).findByText(/API 연결됨/)).toBeInTheDocument();
    expect(within(header).getByText('✓ 연결됨')).toBeInTheDocument();
    expect(menuButton()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: '로그아웃' })).toBeNull();
  });

  it('opens with the colour toggle and logout, and the toggle still switches', async () => {
    const user = userEvent.setup();
    renderRoute('/');
    await user.click(menuButton());
    expect(menuButton()).toHaveAttribute('aria-expanded', 'true');
    const toggle = screen.getByRole('button', { name: /^등락 색:/ });
    expect(toggle).toHaveTextContent('색: 상승 빨강 · 하락 파랑');
    await user.click(toggle);
    expect(screen.getByRole('button', { name: /^등락 색:/ })).toHaveTextContent(
      '색: 상승 초록 · 하락 빨강',
    );
    await user.click(screen.getByRole('button', { name: /^등락 색:/ }));
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeVisible();
  });

  it.each([['{Enter}'], [' ']])(
    'closes on Escape from inside the menu and returns focus (open with %j)',
    async (key) => {
      const user = userEvent.setup();
      renderRoute('/');
      menuButton().focus();
      await user.keyboard(key);
      expect(menuButton()).toHaveAttribute('aria-expanded', 'true');
      await user.tab();
      expect(screen.getByRole('button', { name: /^등락 색:/ })).toHaveFocus();
      await user.tab();
      expect(screen.getByRole('button', { name: '로그아웃' })).toHaveFocus();
      await user.keyboard('{Escape}');
      expect(menuButton()).toHaveAttribute('aria-expanded', 'false');
      expect(menuButton()).toHaveFocus();
      expect(screen.queryByRole('button', { name: '로그아웃' })).toBeNull();
    },
  );

  it('closes on a tap outside, returns focus from inside the menu, and toggles', async () => {
    const user = userEvent.setup();
    renderRoute('/');
    await user.click(menuButton());
    screen.getByRole('button', { name: /^등락 색:/ }).focus();
    await user.click(screen.getByRole('main'));
    expect(menuButton()).toHaveAttribute('aria-expanded', 'false');
    await waitFor(() => {
      expect(menuButton()).toHaveFocus();
    });
    await user.click(menuButton());
    await user.click(menuButton());
    expect(menuButton()).toHaveAttribute('aria-expanded', 'false');
  });
});

describe('ApiStatus in the header', () => {
  it('shows a visible text for the ok state', async () => {
    renderRoute('/');
    expect(await screen.findByText('✓ 연결됨')).toBeVisible();
  });

  it('shows a visible text while checking', () => {
    renderRoute('/');
    expect(screen.getByText('… 확인 중')).toBeVisible();
  });

  it('shows a visible text for a server error', async () => {
    server.use(
      http.get('*/api/v1/pool', () =>
        HttpResponse.json(
          { code: 'INTERNAL_ERROR', title: 'x', status: 500 },
          {
            status: 500,
            headers: { 'content-type': 'application/problem+json' },
          },
        ),
      ),
    );
    renderRoute('/');
    expect(await screen.findByText('! 응답 오류')).toBeVisible();
    expect(
      within(screen.getByRole('banner')).getByRole('status'),
    ).toHaveTextContent(/INTERNAL_ERROR/);
  });

  it('shows a visible text when the API is unreachable', async () => {
    server.use(http.get('*/api/v1/pool', () => HttpResponse.error()));
    renderRoute('/');
    expect(await screen.findByText('✕ 연결 불가')).toBeVisible();
  });
});

describe('AppShell nav rule outside a season', () => {
  it('links turn and trade to the only season in progress', async () => {
    serveSeasons([
      seasonFixture({ id: 12 }),
      seasonFixture({ id: 4, status: 'FINISHED', currentTurnNo: 12 }),
    ]);
    renderRoute('/');
    const nav = navOf();
    expect(
      await within(nav).findByRole('link', { name: '턴' }),
    ).toHaveAttribute('href', '/seasons/12');
    expect(tradeLink()).toHaveAttribute('href', '/seasons/12/trade');
    expect(turnLink()).not.toHaveAttribute('aria-current');
    for (const label of ['성적표', '복기']) {
      expect(within(nav).getByText(label)).toHaveAttribute(
        'aria-disabled',
        'true',
      );
    }
    expect(within(nav).getByText('시즌을 열면 쓸 수 있습니다')).toBeVisible();
  });

  it('keeps both disabled with no season in progress', async () => {
    serveSeasons([seasonFixture({ id: 4, status: 'FINISHED' })]);
    renderRoute('/');
    await screen.findByRole('heading', { name: '시즌 4' });
    expect(turnLink()).toBeNull();
    expect(tradeLink()).toBeNull();
  });

  it('keeps both disabled with two seasons in progress', async () => {
    serveSeasons([seasonFixture({ id: 12 }), seasonFixture({ id: 13 })]);
    renderRoute('/');
    await screen.findByRole('heading', { name: '시즌 13' });
    expect(turnLink()).toBeNull();
    expect(tradeLink()).toBeNull();
  });

  it('keeps both disabled for an empty list', async () => {
    serveSeasons([]);
    renderRoute('/');
    await screen.findByText('아직 시즌이 없습니다');
    expect(turnLink()).toBeNull();
    expect(tradeLink()).toBeNull();
  });

  it('does not count a halted season as in progress', async () => {
    serveSeasons([
      seasonFixture({ id: 12 }),
      seasonFixture({ id: 13, status: 'HALTED' }),
    ]);
    renderRoute('/');
    expect(
      await within(navOf()).findByRole('link', { name: '턴' }),
    ).toHaveAttribute('href', '/seasons/12');
    expect(tradeLink()).toHaveAttribute('href', '/seasons/12/trade');
  });

  it('stays disabled while the list is slow and when it fails at first', async () => {
    failSeasons();
    const { queryClient } = renderRoute('/');
    expect(turnLink()).toBeNull();
    expect(tradeLink()).toBeNull();
    await waitFor(() => {
      expect(queryClient.getQueryState(['seasons'])?.status).toBe('error');
    });
    expect(turnLink()).toBeNull();
    expect(tradeLink()).toBeNull();
  });

  it('follows a refetch from one season to none, then to two', async () => {
    serveSeasons([seasonFixture({ id: 12 })]);
    const { queryClient } = renderRoute('/');
    await within(navOf()).findByRole('link', { name: '턴' });
    serveSeasons([seasonFixture({ id: 12, status: 'FINISHED' })]);
    await queryClient.invalidateQueries({ queryKey: ['seasons'] });
    await waitFor(() => {
      expect(turnLink()).toBeNull();
    });
    expect(tradeLink()).toBeNull();
    serveSeasons([seasonFixture({ id: 12 }), seasonFixture({ id: 13 })]);
    await queryClient.invalidateQueries({ queryKey: ['seasons'] });
    await screen.findByRole('heading', { name: '시즌 13' });
    expect(turnLink()).toBeNull();
    expect(tradeLink()).toBeNull();
  });

  it('disables the links when a refetch fails after one cached season', async () => {
    serveSeasons([seasonFixture({ id: 12 })]);
    const { queryClient } = renderRoute('/');
    await within(navOf()).findByRole('link', { name: '턴' });
    failSeasons();
    await queryClient.invalidateQueries({ queryKey: ['seasons'] });
    await waitFor(() => {
      expect(turnLink()).toBeNull();
    });
    expect(tradeLink()).toBeNull();
  });

  it('asks for no season list on the new-season form', () => {
    let calls = 0;
    server.use(
      http.get('*/api/v1/seasons', () => {
        calls += 1;
        return HttpResponse.json({ items: [] });
      }),
    );
    renderRoute('/seasons/new');
    expect(calls).toBe(0);
  });
});
