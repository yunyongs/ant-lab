import { expect, expectTypeOf, it } from 'vitest';
import { errorMessages, messageFor } from './errorMessages.ts';
import type { ErrorCode } from './problem.ts';

it('has a message for exactly the contract error codes', () => {
  expectTypeOf<keyof typeof errorMessages>().toEqualTypeOf<ErrorCode>();
});

it('maps codes and the unknown fallback to Korean text', () => {
  expect(messageFor('SEASON_LIMIT')).toBe(errorMessages.SEASON_LIMIT);
  expect(messageFor('UNKNOWN')).toBe('알 수 없는 오류가 생겼습니다.');
});
