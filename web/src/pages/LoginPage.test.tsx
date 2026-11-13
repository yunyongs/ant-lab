import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, delay } from 'msw';
import { describe, expect, it } from 'vitest';
import { accessToken, problem } from '../test/authHandlers.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';

async function fillAndSubmit(email = 'a@example.com', password = 'pw-123') {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('이메일'), email);
  await user.type(screen.getByLabelText('비밀번호'), password);
  await user.click(screen.getByRole('button', { name: '로그인' }));
  return user;
}

function counters() {
  const c = { login: 0, refresh: 0 };
  server.use(
    http.post('*/api/v1/auth/refresh', () => {
      c.refresh += 1;
      return problem(401, { code: 'UNAUTHENTICATED', status: 401 });
    }),
  );
  return c;
}

describe('LoginPage', () => {
  it('logs in and returns to the page that was first asked for', async () => {
    let body: unknown;
    server.use(
      http.post('*/api/v1/auth/login', async ({ request }) => {
        body = await request.json();
        return accessToken('test-access-1');
      }),
    );
    renderRoute('/nope', { session: 'anonymous' });
    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument();
    await fillAndSubmit();
    expect(
      await screen.findByRole('heading', { name: '페이지를 찾을 수 없습니다' }),
    ).toBeInTheDocument();
    expect(body).toEqual({ email: 'a@example.com', password: 'pw-123' });
    expect(
      screen.getByRole('button', { name: '로그아웃' }),
    ).toBeInTheDocument();
  });

  it('goes to the home page when there was no earlier page', async () => {
    server.use(
      http.post('*/api/v1/auth/login', () => accessToken('test-access-1')),
    );
    renderRoute('/login', { session: 'anonymous' });
    await fillAndSubmit();
    expect(
      await screen.findByRole('heading', { name: '홈' }),
    ).toBeInTheDocument();
  });

  it('explains wrong credentials, keeps the email and refocuses the password', async () => {
    const c = counters();
    renderRoute('/login', { session: 'anonymous' });
    await fillAndSubmit('a@example.com', 'wrong-password');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('로그인하지 못했습니다');
    expect(alert).toHaveTextContent(
      '이메일이나 비밀번호가 맞지 않습니다. 다시 확인해 주세요.',
    );
    expect(alert).toHaveTextContent('INVALID_CREDENTIALS · 401');
    const password = screen.getByLabelText('비밀번호');
    expect(password).toHaveValue('');
    await waitFor(() => {
      expect(password).toHaveFocus();
    });
    expect(screen.getByLabelText('이메일')).toHaveValue('a@example.com');
    expect(c.refresh).toBe(0);
  });

  it('shows a field message for a 400 violation', async () => {
    server.use(
      http.post('*/api/v1/auth/login', () =>
        problem(400, {
          code: 'INVALID_ARGUMENT',
          errors: [{ field: 'email', reason: 'NotBlank' }],
        }),
      ),
    );
    renderRoute('/login', { session: 'anonymous' });
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: '로그인' }));
    const message = await screen.findByText('이메일을 입력해 주세요');
    const email = screen.getByLabelText('이메일');
    expect(email).toHaveAttribute('aria-invalid', 'true');
    expect(email.getAttribute('aria-describedby')).toContain(message.id);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('says the API is unreachable on a network error', async () => {
    server.use(http.post('*/api/v1/auth/login', () => HttpResponse.error()));
    renderRoute('/login', { session: 'anonymous' });
    await fillAndSubmit();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'API에 연결할 수 없습니다. 잠시 뒤 다시 시도해 주세요.',
    );
  });

  it('disables the button while the request runs and sends it once', async () => {
    let calls = 0;
    server.use(
      http.post('*/api/v1/auth/login', async () => {
        calls += 1;
        await delay(60);
        return accessToken('test-access-1');
      }),
    );
    renderRoute('/login', { session: 'anonymous' });
    const user = await fillAndSubmit();
    const button = screen.getByRole('button', { name: '로그인' });
    expect(button).toBeDisabled();
    await user.click(button);
    await screen.findByRole('heading', { name: '홈' });
    expect(calls).toBe(1);
  });

  it('links to signup', () => {
    renderRoute('/login', { session: 'anonymous' });
    expect(
      screen.getByRole('link', { name: '처음이세요? 계정 만들기' }),
    ).toHaveAttribute('href', '/signup');
  });
});
