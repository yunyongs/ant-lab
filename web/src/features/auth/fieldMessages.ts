import type { FieldViolation } from '../../api/problem.ts';

export type AuthForm = 'login' | 'signup';
export type FieldName = 'email' | 'name' | 'password';

export interface FormValues {
  email: string;
  name: string;
  password: string;
}

const FIELDS: readonly string[] = ['email', 'name', 'password'];

export function isFieldName(value: string): value is FieldName {
  return FIELDS.includes(value);
}

// Fixed Korean sentences per (field, reason); the server's own text is never
// shown. Returns null for a pair the table does not know.
export function fieldMessage(
  form: AuthForm,
  violation: FieldViolation,
  values: FormValues,
): string | null {
  const { field, reason } = violation;
  if (field === 'email') {
    if (reason === 'NotBlank') return '이메일을 입력해 주세요';
    if (reason === 'Email') return '이메일 형식이 아닙니다';
    if (reason === 'Size') return '이메일은 255자까지입니다';
  }
  if (field === 'name') {
    if (reason === 'NotBlank') return '표시 이름을 입력해 주세요';
    if (reason === 'Size') return '표시 이름은 50자까지입니다';
  }
  if (field === 'password') {
    if (reason === 'NotBlank') return '비밀번호를 입력해 주세요';
    if (reason === 'MaxUtf8Bytes') {
      return 'UTF-8로 72바이트 이하여야 합니다 (한글만 쓰면 24자까지)';
    }
    if (reason === 'Size') {
      return form === 'login'
        ? '비밀번호는 64자까지입니다'
        : `12–64자로 입력해 주세요 (지금 ${String(values.password.length)}자)`;
    }
  }
  return null;
}

export interface MappedViolations {
  byField: Partial<Record<FieldName, string>>;
  hasUnmapped: boolean;
}

export function mapViolations(
  form: AuthForm,
  violations: readonly FieldViolation[],
  values: FormValues,
): MappedViolations {
  const byField: Partial<Record<FieldName, string>> = {};
  let hasUnmapped = false;
  for (const violation of violations) {
    const text = fieldMessage(form, violation, values);
    if (text === null || !isFieldName(violation.field)) {
      hasUnmapped = true;
    } else {
      byField[violation.field] ??= text;
    }
  }
  return { byField, hasUnmapped };
}
