import type { components } from '../../api/schema';

type FormationChangeDto = components['schemas']['FormationChangeDto'];

// Plan-approved mock shaped like docs/api/openapi.yaml; values are invented.
export function formationChangeFixture(
  overrides: Partial<FormationChangeDto> = {},
): FormationChangeDto {
  return {
    turnNo: 4,
    before: { goalkeeper: 40, defender: 30, midfielder: 20, forward: 10 },
    after: { goalkeeper: 30, defender: 30, midfielder: 25, forward: 15 },
    reason: null,
    ...overrides,
  };
}
