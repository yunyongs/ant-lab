import type { components } from '../../api/schema';
import type { Season } from '../seasons/labels.ts';

export type ProjectionPhase = components['schemas']['ProjectionMoment'];
export type Projection = components['schemas']['ProjectionDto'];

export const phaseLabel = {
  CURRENT: '지금 팀',
  START: '시작 팀',
  END: '마지막 팀',
} as const satisfies Record<ProjectionPhase, string>;

// The phases the season's state allows, default first. The server answers 409
// for any other, so the page never asks for one: CURRENT only while the
// season runs, END only after it ended, START from turn 1 on.
export function phasesFor(
  season: Pick<Season, 'status' | 'currentTurnNo'>,
): ProjectionPhase[] {
  const first: ProjectionPhase =
    season.status === 'FINISHED' ? 'END' : 'CURRENT';
  return season.currentTurnNo >= 1 ? [first, 'START'] : [first];
}
