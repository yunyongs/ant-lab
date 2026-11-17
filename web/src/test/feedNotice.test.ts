// @vitest-environment node
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { SEASON_START_DISCLOSURE } from '../features/feed/feedMessages.ts';

// Minimal CSV reader: quoted fields, doubled quotes, newlines inside quotes.
function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < text.length; i += 1) {
    const ch = text.charAt(i);
    if (quoted) {
      if (ch === '"' && text.charAt(i + 1) === '"') {
        field += '"';
        i += 1;
      } else if (ch === '"') quoted = false;
      else field += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ',') {
      row.push(field);
      field = '';
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && text.charAt(i + 1) === '\n') i += 1;
      row.push(field);
      field = '';
      if (row.some((cell) => cell !== '')) rows.push(row);
      row = [];
    } else field += ch;
  }
  if (field !== '' || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

describe('season start disclosure', () => {
  const path = fileURLToPath(
    new URL('../../../feed/notices.csv', import.meta.url),
  );
  const [header, ...rows] = parseCsv(readFileSync(path, 'utf8'));

  it('equals the one ACTIVE season_start.disclosure row of feed/notices.csv', () => {
    expect(header).toEqual(['notice_id', 'status', 'text']);
    const active = rows.filter(
      (row) =>
        (row[0] ?? '').startsWith('season_start.disclosure.') &&
        row[1] === 'ACTIVE',
    );
    expect(active).toHaveLength(1);
    expect(SEASON_START_DISCLOSURE).toBe(active[0]?.[2]);
  });
});
