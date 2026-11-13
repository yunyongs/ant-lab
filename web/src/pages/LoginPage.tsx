import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { messageFor } from '../api/errorMessages.ts';
import type { AuthFailure } from '../features/auth/authApi.ts';
import {
  mapViolations,
  type FormValues,
} from '../features/auth/fieldMessages.ts';
import { useAuth } from '../features/auth/useAuth.ts';
import { AuthAlert, Notice, TextField } from './AuthForm.tsx';
import styles from './AuthPage.module.css';

const NOTICES = {
  expired: messageFor('UNAUTHENTICATED'),
  otherTab: '다른 창에서 로그아웃했습니다.',
  signedUp: '가입했습니다. 로그인해 주세요.',
} as const;

export function LoginPage() {
  const { login, notice } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  // The values are kept with the failure so the messages describe what was
  // submitted, not what is typed afterwards.
  const [submitted, setSubmitted] = useState<{
    failure: AuthFailure;
    values: FormValues;
  } | null>(null);
  const failure = submitted?.failure ?? null;
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  const mapped =
    submitted?.failure.kind === 'problem' && submitted.failure.status === 400
      ? mapViolations('login', submitted.failure.errors, submitted.values)
      : null;
  const wrongCredentials =
    failure?.kind === 'problem' && failure.code === 'INVALID_CREDENTIALS';

  const focusTarget = wrongCredentials
    ? 'password'
    : mapped?.byField.email
      ? 'email'
      : mapped?.byField.password
        ? 'password'
        : null;

  useEffect(() => {
    if (focusTarget === 'email') emailRef.current?.focus();
    if (focusTarget === 'password') passwordRef.current?.focus();
  }, [failure, focusTarget]);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (pending) return;
    setPending(true);
    setSubmitted(null);
    const result = await login(email, password);
    // On success the guard moves the visitor away from this page.
    if (!result.ok && 'failure' in result) {
      setSubmitted({
        failure: result.failure,
        values: { email, name: '', password },
      });
      if (
        result.failure.kind === 'problem' &&
        result.failure.code === 'INVALID_CREDENTIALS'
      ) {
        setPassword('');
      }
    }
    setPending(false);
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.heading}>로그인</h1>
      {notice && <Notice text={NOTICES[notice]} />}
      <AuthAlert
        title="로그인하지 못했습니다"
        failure={failure}
        show={mapped === null || mapped.hasUnmapped}
      />
      <form
        className={styles.form}
        onSubmit={(e) => void onSubmit(e)}
        noValidate
      >
        <TextField
          id="login-email"
          label="이메일"
          hint="가입할 때 쓴 이메일"
          type="email"
          autoComplete="username"
          inputMode="email"
          value={email}
          onChange={(e) => {
            setEmail(e.target.value);
          }}
          error={mapped?.byField.email}
          inputRef={emailRef}
        />
        <TextField
          id="login-password"
          label="비밀번호"
          hint="가입할 때 정한 비밀번호 (12–64자)"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => {
            setPassword(e.target.value);
          }}
          error={mapped?.byField.password}
          inputRef={passwordRef}
        />
        <button type="submit" className={styles.button} disabled={pending}>
          로그인
        </button>
      </form>
      <Link to="/signup" className={styles.link}>
        처음이세요? 계정 만들기
      </Link>
    </div>
  );
}
