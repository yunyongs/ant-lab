import { describe, expect, it } from 'vitest';
import {
  POSITIONS,
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
