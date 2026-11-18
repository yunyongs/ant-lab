import { describe, expect, it } from 'vitest';
import { fromAlert, fromFeed, fromRanking } from './orderSource.ts';

describe('order sources', () => {
  it('builds a FEED source from the item reference only', () => {
    const item = { turnNo: 3, seq: 2, assetId: 101, body: '문장' };
    expect(fromFeed(item)).toStrictEqual({
      kind: 'FEED',
      feedTurnNo: 3,
      feedSeq: 2,
    });
  });

  it('builds a RANKING source with no reference keys', () => {
    expect(fromRanking()).toStrictEqual({ kind: 'RANKING' });
  });
});

describe('alert source', () => {
  it('builds an ALERT source from the item reference only', () => {
    const item = { turnNo: 3, seq: 4, assetId: 101, body: '문장' };
    expect(fromAlert(item)).toStrictEqual({
      kind: 'ALERT',
      feedTurnNo: 3,
      feedSeq: 4,
    });
  });
});
