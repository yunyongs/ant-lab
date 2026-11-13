import { expectTypeOf, it } from 'vitest';
import type { components } from './schema';

type Schemas = components['schemas'];

it('keeps decimal fields as strings', () => {
  expectTypeOf<Schemas['PoolAssetDto']['volatility']>().toEqualTypeOf<string>();
  expectTypeOf<Schemas['HoldingDto']['value']>().toEqualTypeOf<string>();
  expectTypeOf<
    Schemas['SeasonReportDto']['finalValue']
  >().toEqualTypeOf<string>();
});
