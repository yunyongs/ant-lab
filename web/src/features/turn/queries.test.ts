import { describe, expect, it } from 'vitest';
import { turnKey, turnQueryOptions } from './queries.ts';

describe('turnQueryOptions', () => {
  it('uses the shared turn key and keeps a stored turn forever', () => {
    const options = turnQueryOptions(7, 3);
    expect(options.queryKey).toEqual(turnKey(7, 3));
    expect(options.staleTime).toBe(Infinity);
  });
});
