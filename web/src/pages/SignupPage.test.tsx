import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { describe, expect, it } from 'vitest';
import { accessToken, problem } from '../test/authHandlers.ts';
import { renderRoute } from '../test/renderWithProviders.tsx';
import { server } from '../test/server.ts';

async function fill(values: { email: string; name: string; password: string }) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('이메일'), values.email);
  await user.type(screen.getByLabelText('표시 이름'), values.name);
  await user.type(screen.getByLabelText('비밀번호'), values.password);
  await user.click(screen.getByRole('button', { name: '가입하고 로그인' }));
}

const INPUT = {
  email: 'new@example.com',
  name: '새 회원',
  password: 'long-enough-pw',
};

describe('SignupPage', () => {
  it('signs up, then logs in with the same credentials', async () => {
    const order: string[] = [];
    let signupBody: unknown;
    let loginBody: unknown;
    server.use(
      http.post('*/api/v1/auth/signup', async ({ request }) => {
        order.push('signup');
        signupBody = await request.json();
        return new Response(JSON.stringify({ id: 1, name: INPUT.name }), {
          status: 201,
          headers: { 'content-type': 'application/json' },
        });
      }),
      http.post('*/api/v1/auth/login', async ({ request }) => {
        order.push('login');
        loginBody = await request.json();
        return accessToken('test-access-1');
      }),
    );
    renderRoute('/signup', { session: 'anonymous' });
    await fill(INPUT);
    expect(
      await screen.findByRole('heading', { name: '내 시즌' }),
    ).toBeInTheDocument();
    expect(order).toEqual(['signup', 'login']);
    expect(signupBody).toEqual(INPUT);
    expect(loginBody).toEqual({ email: INPUT.email, password: INPUT.password });
  });

  it('explains a taken email in the alert and under the field', async () => {
    renderRoute('/signup', { session: 'anonymous' });
    await fill(INPUT);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('가입하지 못했습니다');
    expect(alert).toHaveTextContent(
      '이미 가입한 이메일입니다. 로그인하거나 다른 이메일을 써 주세요.',
    );
    expect(alert).toHaveTextContent('EMAIL_TAKEN · 409');
    expect(screen.getByText('이미 가입한 이메일입니다')).toBeInTheDocument();
    expect(screen.getByLabelText('이메일')).toHaveAttribute(
      'aria-invalid',
      'true',
    );
    await waitFor(() => {
      expect(screen.getByLabelText('이메일')).toHaveFocus();
    });
  });

  it('keeps the submitted length in the message while the player types on', async () => {
    server.use(
      http.post('*/api/v1/auth/signup', () =>
        problem(400, {
          code: 'INVALID_ARGUMENT',
          errors: [{ field: 'password', reason: 'Size' }],
        }),
      ),
    );
    renderRoute('/signup', { session: 'anonymous' });
    await fill({ email: INPUT.email, name: INPUT.name, password: '12345' });
    expect(
      await screen.findByText('12–64자로 입력해 주세요 (지금 5자)'),
    ).toBeInTheDocument();
    await userEvent.setup().type(screen.getByLabelText('비밀번호'), '12345678');
    expect(
      screen.getByText('12–64자로 입력해 주세요 (지금 5자)'),
    ).toBeInTheDocument();
  });

  it('puts each violation under its own field', async () => {
    server.use(
      http.post('*/api/v1/auth/signup', () =>
        problem(400, {
          code: 'INVALID_ARGUMENT',
          errors: [
            { field: 'email', reason: 'Email' },
            { field: 'name', reason: 'Size' },
            { field: 'password', reason: 'Size' },
          ],
        }),
      ),
    );
    renderRoute('/signup', { session: 'anonymous' });
    await fill({ email: 'bad', name: 'x'.repeat(51), password: '12345678' });
    expect(
      await screen.findByText('이메일 형식이 아닙니다'),
    ).toBeInTheDocument();
    expect(screen.getByText('표시 이름은 50자까지입니다')).toBeInTheDocument();
    expect(
      screen.getByText('12–64자로 입력해 주세요 (지금 8자)'),
    ).toBeInTheDocument();
    for (const label of ['이메일', '표시 이름', '비밀번호']) {
      const input = screen.getByLabelText(label);
      expect(input).toHaveAttribute('aria-invalid', 'true');
      expect(input.getAttribute('aria-describedby')).toMatch(/-error /);
    }
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('falls back to the form-level text for an unknown field', async () => {
    server.use(
      http.post('*/api/v1/auth/signup', () =>
        problem(400, {
          code: 'INVALID_ARGUMENT',
          errors: [{ field: 'unknownField', reason: 'X' }],
        }),
      ),
    );
    renderRoute('/signup', { session: 'anonymous' });
    await fill(INPUT);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('입력값을 다시 확인해 주세요.');
    expect(alert).toHaveTextContent('INVALID_ARGUMENT · 400');
    expect(screen.getByLabelText('이메일')).not.toHaveAttribute('aria-invalid');
  });

  it('sends the visitor to the login screen when only the login failed', async () => {
    server.use(
      http.post(
        '*/api/v1/auth/signup',
        () =>
          new Response(JSON.stringify({ id: 1, name: INPUT.name }), {
            status: 201,
            headers: { 'content-type': 'application/json' },
          }),
      ),
    );
    renderRoute('/signup', { session: 'anonymous' });
    await fill(INPUT);
    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('가입했습니다. 로그인해 주세요.'),
    ).toBeInTheDocument();
  });

  it('shows the password rule in the hint', () => {
    renderRoute('/signup', { session: 'anonymous' });
    expect(
      screen.getByText(/12–64자, 문자 조합 규칙 없음/),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: '이미 계정이 있어요' }),
    ).toHaveAttribute('href', '/login');
  });
});
