import { screen } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { describe, expect, it } from 'vitest';
import { poolFixture } from '../../test/fixtures/pool.ts';
import { server } from '../../test/server.ts';
import { renderWithProviders } from '../../test/renderWithProviders.tsx';
import { ApiStatus } from './ApiStatus.tsx';

describe('ApiStatus', () => {
  it('shows a checking message in a status region first', async () => {
    server.use(
      http.get('*/api/v1/pool', async () => {
        await delay(50);
        return HttpResponse.json({ assets: [] });
      }),
    );
    renderWithProviders(<ApiStatus />);
    expect(screen.getByRole('status')).toHaveTextContent('API 연결 확인 중…');
    await screen.findByText('API 연결됨 · 종목 0개');
  });

  it('shows the asset count on success', async () => {
    renderWithProviders(<ApiStatus />);
    await screen.findByText(
      `API 연결됨 · 종목 ${String(poolFixture.assets.length)}개`,
    );
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('shows the Problem code when the API answers an error', async () => {
    server.use(
      http.get('*/api/v1/pool', () =>
        HttpResponse.json(
          { code: 'POOL_NOT_LOADED', status: 409 },
          {
            status: 409,
            headers: { 'Content-Type': 'application/problem+json' },
          },
        ),
      ),
    );
    renderWithProviders(<ApiStatus />);
    expect(
      await screen.findByText('API 연결됨 · 응답 오류 POOL_NOT_LOADED'),
    ).toBeInTheDocument();
  });

  it('reports unreachable on a network error', async () => {
    server.use(http.get('*/api/v1/pool', () => HttpResponse.error()));
    renderWithProviders(<ApiStatus />);
    expect(
      await screen.findByText('API에 연결할 수 없습니다'),
    ).toBeInTheDocument();
  });

  it.each([['{"assets":"oops"}'], ['{}']])(
    'reports unreachable when a 200 JSON body is %s',
    async (json) => {
      server.use(
        http.get(
          '*/api/v1/pool',
          () =>
            new HttpResponse(json, {
              headers: { 'Content-Type': 'application/json' },
            }),
        ),
      );
      renderWithProviders(<ApiStatus />);
      expect(
        await screen.findByText('API에 연결할 수 없습니다'),
      ).toBeInTheDocument();
    },
  );

  it('reports unreachable on a proxy-style HTML 500', async () => {
    server.use(
      http.get('*/api/v1/pool', () =>
        HttpResponse.text('<html>proxy error</html>', {
          status: 500,
          headers: { 'Content-Type': 'text/html' },
        }),
      ),
    );
    renderWithProviders(<ApiStatus />);
    expect(
      await screen.findByText('API에 연결할 수 없습니다'),
    ).toBeInTheDocument();
  });
});
