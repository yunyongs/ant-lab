import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import type { AuthFailure } from '../features/auth/authApi.ts';
import {
  mapViolations,
  type FieldName,
  type FormValues,
} from '../features/auth/fieldMessages.ts';
import { useAuth } from '../features/auth/useAuth.ts';
import { AuthAlert, TextField } from './AuthForm.tsx';
import styles from './AuthPage.module.css';

const FOCUS_ORDER: readonly FieldName[] = ['email', 'name', 'password'];

export function SignupPage() {
  const { signup } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
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
  const nameRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  const mapped =
    submitted?.failure.kind === 'problem' && submitted.failure.status === 400
      ? mapViolations('signup', submitted.failure.errors, submitted.values)
      : null;
  const emailTaken =
    failure?.kind === 'problem' && failure.code === 'EMAIL_TAKEN';
  const emailError = emailTaken
    ? '이미 가입한 이메일입니다'
    : mapped?.byField.email;
  const fieldErrors = { ...mapped?.byField, email: emailError };

  const focusTarget = FOCUS_ORDER.find((field) => fieldErrors[field]);

  useEffect(() => {
    if (focusTarget === 'email') emailRef.current?.focus();
    if (focusTarget === 'name') nameRef.current?.focus();
    if (focusTarget === 'password') passwordRef.current?.focus();
  }, [failure, focusTarget]);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (pending) return;
    setPending(true);
    setSubmitted(null);
    const result = await signup({ email, name, password });
    if (result.ok) return;
    if ('failure' in result) {
      setSubmitted({
        failure: result.failure,
        values: { email, name, password },
      });
      setPending(false);
    } else {
      void navigate('/login', { replace: true });
    }
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.heading}>계정 만들기</h1>
      <AuthAlert
        title="가입하지 못했습니다"
        failure={failure}
        show={mapped === null || mapped.hasUnmapped}
      />
      <form
        className={styles.form}
        onSubmit={(e) => void onSubmit(e)}
        noValidate
      >
        <TextField
          id="signup-email"
          label="이메일"
          hint="로그인에 쓰는 주소 (최대 255자, 대소문자 구분 없음)"
          type="email"
          autoComplete="email"
          inputMode="email"
          value={email}
          onChange={(e) => {
            setEmail(e.target.value);
          }}
          error={fieldErrors.email}
          inputRef={emailRef}
        />
        <TextField
          id="signup-name"
          label="표시 이름"
          hint="1–50자"
          type="text"
          autoComplete="nickname"
          value={name}
          onChange={(e) => {
            setName(e.target.value);
          }}
          error={fieldErrors.name}
          inputRef={nameRef}
        />
        <TextField
          id="signup-password"
          label="비밀번호"
          hint="12–64자, 문자 조합 규칙 없음. UTF-8로 72바이트 이하여야 하므로 한글만 쓰면 24자까지입니다."
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(e) => {
            setPassword(e.target.value);
          }}
          error={fieldErrors.password}
          inputRef={passwordRef}
        />
        <button type="submit" className={styles.button} disabled={pending}>
          가입하고 로그인
        </button>
      </form>
      <Link to="/login" className={styles.link}>
        이미 계정이 있어요
      </Link>
    </div>
  );
}
