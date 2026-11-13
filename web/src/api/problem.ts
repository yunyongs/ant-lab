import type { components } from './schema';

export type ErrorCode = components['schemas']['ErrorCode'];

export class ProblemError extends Error {
  readonly status: number;
  readonly code: ErrorCode | 'UNKNOWN';

  constructor(status: number, code: ErrorCode | 'UNKNOWN') {
    super(`API problem ${code} (${String(status)})`);
    this.name = 'ProblemError';
    this.status = status;
    this.code = code;
  }
}

export class UnreachableError extends Error {
  constructor(message = 'API unreachable') {
    super(message);
    this.name = 'UnreachableError';
  }
}
