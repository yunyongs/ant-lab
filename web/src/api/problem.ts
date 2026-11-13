import type { components } from './schema';

export type ErrorCode = components['schemas']['ErrorCode'];
export type FieldViolation = components['schemas']['FieldViolation'];

export class ProblemError extends Error {
  readonly status: number;
  readonly code: ErrorCode | 'UNKNOWN';
  readonly errors: readonly FieldViolation[];

  constructor(
    status: number,
    code: ErrorCode | 'UNKNOWN',
    errors: readonly FieldViolation[] = [],
  ) {
    super(`API problem ${code} (${String(status)})`);
    this.name = 'ProblemError';
    this.status = status;
    this.code = code;
    this.errors = errors;
  }
}

export class UnreachableError extends Error {
  constructor(message = 'API unreachable') {
    super(message);
    this.name = 'UnreachableError';
  }
}
