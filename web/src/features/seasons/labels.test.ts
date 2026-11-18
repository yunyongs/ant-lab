import { describe, expect, it } from 'vitest';
import {
  POSITIONS,
  abandonText,
  openButtonLabel,
  positionLabel,
  statusLabel,
} from './labels.ts';

describe('positionLabel', () => {
  it('has a distinct Korean label for each of the six positions', () => {
    expect(POSITIONS).toHaveLength(6);
    const texts = POSITIONS.map((p) => positionLabel[p]);
    expect(new Set(texts).size).toBe(6);
    expect(texts).toContain('공격수·타겟형');
    expect(texts).toContain('공격수·돌파형');
    expect(texts).toContain('공격수·수비적');
  });
});

describe('halted season', () => {
  it('shows the stop label and the open button, never the in-progress text', () => {
    const halted = { status: 'HALTED', currentTurnNo: 3 } as const;
    expect(statusLabel(halted)).toBe('점검 중');
    expect(openButtonLabel(halted)).toBe('시즌 보기');
    expect(statusLabel({ status: 'IN_PROGRESS', currentTurnNo: 3 })).toBe(
      '진행 중',
    );
  });
});

describe('abandonText (A9)', () => {
  it('is null for a season that was not abandoned', () => {
    expect(
      abandonText({ abandonedTurnNo: null, notStarted: false }),
    ).toBeNull();
  });

  it('names the turn of the abandon', () => {
    expect(abandonText({ abandonedTurnNo: 5, notStarted: false })).toBe(
      '5턴에서 포기',
    );
  });

  it('prints an abandon at turn 0 (0 is a turn, not "none")', () => {
    expect(abandonText({ abandonedTurnNo: 0, notStarted: true })).toBe(
      '0턴에서 포기 · 미시작',
    );
    expect(abandonText({ abandonedTurnNo: 0, notStarted: false })).toBe(
      '0턴에서 포기',
    );
  });

  it('adds the not-started mark only when the server says so', () => {
    expect(abandonText({ abandonedTurnNo: 1, notStarted: true })).toBe(
      '1턴에서 포기 · 미시작',
    );
    // An abandon at turn 1 is not judged by the web: no flag, no mark.
    expect(abandonText({ abandonedTurnNo: 1, notStarted: false })).toBe(
      '1턴에서 포기',
    );
    // The reverse is not judged either: the server's flag is printed as sent.
    expect(abandonText({ abandonedTurnNo: 5, notStarted: true })).toBe(
      '5턴에서 포기 · 미시작',
    );
  });
});
