import { describe, expect, it } from 'vitest';
import { itemActions, type ItemContext } from './itemActions.ts';

function context(overrides: Partial<ItemContext> = {}): ItemContext {
  return {
    tradable: true,
    advancing: false,
    inPool: (id) => id === 101 || id === 102,
    held: (id) => id === 102,
    ...overrides,
  };
}

describe('itemActions', () => {
  it('gives no button to an item that names no asset', () => {
    expect(itemActions({ assetId: null }, context())).toBeNull();
  });

  it('gives no button to an asset the pool does not know', () => {
    expect(itemActions({ assetId: 999 }, context())).toBeNull();
  });

  it('gives no button when the season is not tradable', () => {
    expect(
      itemActions({ assetId: 101 }, context({ tradable: false })),
    ).toBeNull();
  });

  it('disables the buttons while a turn advance is in flight', () => {
    expect(itemActions({ assetId: 101 }, context({ advancing: true }))).toEqual(
      { sell: false, disabled: true },
    );
  });

  it('offers buying for an asset that is not held', () => {
    expect(itemActions({ assetId: 101 }, context())).toEqual({
      sell: false,
      disabled: false,
    });
  });

  it('offers selling too for a held asset', () => {
    expect(itemActions({ assetId: 102 }, context())).toEqual({
      sell: true,
      disabled: false,
    });
  });
});
