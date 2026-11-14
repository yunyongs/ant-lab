import type { Formation } from './labels.ts';

export interface WeightRow {
  key: string;
  label: string;
  weight: string;
  target: number;
  gap?: string;
  value?: string;
}

interface WeightSource {
  goalkeeperWeight: string;
  defenderWeight: string;
  midfielderWeight: string;
  forwardWeight: string;
  goalkeeperGap?: string;
  defenderGap?: string;
  midfielderGap?: string;
  forwardGap?: string;
  goalkeeperValue?: string;
  defenderValue?: string;
  midfielderValue?: string;
  forwardValue?: string;
}

// The four rows from any DTO that carries the weight (and optional gap and
// value) fields; the targets come from the formation passed in.
export function weightRowsOf(
  source: WeightSource,
  formation: Formation,
): WeightRow[] {
  return [
    {
      key: 'goalkeeper',
      label: '골키퍼',
      weight: source.goalkeeperWeight,
      target: formation.goalkeeper,
      gap: source.goalkeeperGap,
      value: source.goalkeeperValue,
    },
    {
      key: 'defender',
      label: '수비수',
      weight: source.defenderWeight,
      target: formation.defender,
      gap: source.defenderGap,
      value: source.defenderValue,
    },
    {
      key: 'midfielder',
      label: '미드필더',
      weight: source.midfielderWeight,
      target: formation.midfielder,
      gap: source.midfielderGap,
      value: source.midfielderValue,
    },
    {
      key: 'forward',
      label: '공격수',
      weight: source.forwardWeight,
      target: formation.forward,
      gap: source.forwardGap,
      value: source.forwardValue,
    },
  ];
}
