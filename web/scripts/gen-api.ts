import { writeFileSync } from 'node:fs';
import openapiTS, { astToString } from 'openapi-typescript';

export const CONTRACT = new URL('../../docs/api/openapi.yaml', import.meta.url);
export const OUTPUT = new URL('../src/api/schema.d.ts', import.meta.url);

export async function renderSchema(input: URL = CONTRACT): Promise<string> {
  return astToString(await openapiTS(input));
}

if (import.meta.main) {
  writeFileSync(OUTPUT, await renderSchema());
}
