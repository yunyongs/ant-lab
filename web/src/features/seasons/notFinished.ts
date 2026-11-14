import { ProblemError } from '../../api/problem.ts';

// 409 SEASON_NOT_FINISHED is an expected state ("not yet"), not a failure.
export function isNotFinishedProblem(error: unknown): boolean {
  return error instanceof ProblemError && error.code === 'SEASON_NOT_FINISHED';
}
