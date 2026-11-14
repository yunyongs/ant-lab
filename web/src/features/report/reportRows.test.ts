import { describe, expect, it } from 'vitest';
import { reportFixture } from '../../test/fixtures/report.ts';
import { SHOWN_KEYS } from './reportRows.ts';

describe('reportRows', () => {
  it('shows exactly the 37 contract keys, none twice', () => {
    const contract = Object.keys(reportFixture()).sort();
    expect(contract).toHaveLength(37);
    expect([...SHOWN_KEYS].sort()).toEqual(contract);
  });
});
