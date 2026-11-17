import { describe, expect, it } from 'vitest';
import { ProblemError, type ErrorCode } from '../../api/problem.ts';
import { abandonMode } from './abandonMode.ts';
import { failureText, progressText, rulesText } from './abandonMessages.ts';
import { runAbandon, type AbandonResult, type AbandonSend } from './queries.ts';

function result(overrides: Partial<AbandonResult> = {}): AbandonResult {
  return {
    seasonId: 7,
    status: 'ABANDONING',
    currentTurnNo: 6,
    pathLength: 12,
    abandonedTurnNo: 5,
    notStarted: false,
    advancedTurns: 1,
    remainingTurns: 6,
    ...overrides,
  };
}

function problem(code: ErrorCode, status = 409): ProblemError {
  return new ProblemError(status, code);
}

describe('abandonMode', () => {
  it('starts from in progress, resumes from abandoning, nothing else', () => {
    expect(abandonMode({ status: 'IN_PROGRESS' })).toBe('start');
    expect(abandonMode({ status: 'ABANDONING' })).toBe('resume');
    expect(abandonMode({ status: 'HALTED' })).toBeNull();
    expect(abandonMode({ status: 'FINISHED' })).toBeNull();
  });
});

describe('runAbandon (A11)', () => {
  it('sends once when the first answer is FINISHED', async () => {
    const sent: number[] = [];
    const send: AbandonSend = (turn) => {
      sent.push(turn);
      return Promise.resolve(result({ status: 'FINISHED' }));
    };
    const done = await runAbandon(send, 5, () => {});
    expect(done.status).toBe('FINISHED');
    expect(sent).toEqual([5]);
  });

  it('repeats with the turn the server reports, and reports each in-between answer', async () => {
    const sent: number[] = [];
    const seen: number[] = [];
    const answers = [
      result({ currentTurnNo: 9, advancedTurns: 4 }),
      result({ status: 'FINISHED', currentTurnNo: 12, advancedTurns: 3 }),
    ];
    const send: AbandonSend = (turn) => {
      sent.push(turn);
      return Promise.resolve(answers.shift() ?? result());
    };
    await runAbandon(send, 5, (r) => seen.push(r.currentTurnNo));
    expect(sent).toEqual([5, 9]);
    expect(seen).toEqual([9]);
  });

  it('stops without progress', async () => {
    let calls = 0;
    const send: AbandonSend = () => {
      calls += 1;
      return Promise.resolve(result({ advancedTurns: 0 }));
    };
    const last = await runAbandon(send, 5, () => {});
    expect(calls).toBe(1);
    expect(last.status).toBe('ABANDONING');
  });

  it('never sends more than pathLength requests for one confirm', async () => {
    let calls = 0;
    const send: AbandonSend = () => {
      calls += 1;
      return Promise.resolve(result({ advancedTurns: 1, pathLength: 12 }));
    };
    await runAbandon(send, 0, () => {});
    expect(calls).toBe(12);
  });

  it('does not catch an error: the loop ends and nothing is resent', async () => {
    let calls = 0;
    const send: AbandonSend = () => {
      calls += 1;
      return calls === 1
        ? Promise.resolve(result({ advancedTurns: 2 }))
        : Promise.reject(new Error('network'));
    };
    await expect(runAbandon(send, 5, () => {})).rejects.toThrow('network');
    expect(calls).toBe(2);
  });
});

describe('messages', () => {
  it('prints the remaining turns in the first rule and keeps the others fixed', () => {
    const lines = rulesText(7);
    expect(lines[0]).toBe('남은 7턴을 매매 없이 자동으로 진행합니다.');
    expect(lines.join(' ')).toContain('성적표');
    expect(lines.join(' ')).toContain('레이팅');
    expect(lines.join(' ')).toContain('미시작');
  });

  it('prints progress from the server numbers', () => {
    expect(progressText(9, 12, 3)).toBe(
      '9/12턴까지 정리했습니다. 남은 3턴을 이어서 진행합니다.',
    );
  });

  it('maps failures: unknown outcome, own sentence, catalog sentence, nothing for stale or login', () => {
    expect(failureText(null)).toBeNull();
    expect(failureText(new Error('network'))).toContain(
      '결과를 확인하지 못했습니다',
    );
    expect(failureText(problem('RECONCILIATION_FAILED'))).toContain(
      '이어서 정리해 주세요',
    );
    expect(failureText(problem('SEASON_HALTED'))).toContain('관리자 확인');
    expect(failureText(problem('TURN_MISMATCH'))).toBeNull();
    expect(failureText(problem('SEASON_NOT_ACTIVE'))).toBeNull();
    expect(failureText(problem('UNAUTHENTICATED', 401))).toBeNull();
  });
});
