import type { components } from '../../api/schema';

type LineupChangeDto = components['schemas']['LineupChangeDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented.
export function lineupChangeFixture(
  overrides: Partial<LineupChangeDto> = {},
): LineupChangeDto {
  return {
    assetId: 101,
    declaredPosition: 'FORWARD_TARGET',
    startTurn: 1,
    changed: true,
    ...overrides,
  };
}
