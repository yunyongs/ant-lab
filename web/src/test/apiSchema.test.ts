// @vitest-environment node
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { CONTRACT, OUTPUT, renderSchema } from '../../scripts/gen-api.ts';

describe('generated API types', () => {
  it('matches a fresh generation of docs/api/openapi.yaml', async () => {
    expect(readFileSync(OUTPUT, 'utf8')).toBe(await renderSchema());
  });

  it('has no floating-point number schemas in the contract', () => {
    expect(readFileSync(CONTRACT, 'utf8')).not.toMatch(
      /type:\s*(?:['"]?number\b|\[[^\]]*\bnumber\b)/,
    );
  });
});
