import { groupThousands } from '../format/decimal.ts';
import { floorToManWon, positionGap } from '../format/derive.ts';
import type { Holdings } from './queries.ts';

export interface Slice {
  key: 'goalkeeper' | 'defender' | 'midfielder' | 'forward';
  label: string;
  weight: string;
  target: number;
  value: string;
}

export function slicesOf(holdings: Holdings): Slice[] {
  const formation = holdings.currentFormation;
  return [
    {
      key: 'goalkeeper',
      label: '골키퍼',
      weight: holdings.goalkeeperWeight,
      target: formation.goalkeeper,
      value: holdings.goalkeeperValue,
    },
    {
      key: 'defender',
      label: '수비수',
      weight: holdings.defenderWeight,
      target: formation.defender,
      value: holdings.defenderValue,
    },
    {
      key: 'midfielder',
      label: '미드필더',
      weight: holdings.midfielderWeight,
      target: formation.midfielder,
      value: holdings.midfielderValue,
    },
    {
      key: 'forward',
      label: '공격수',
      weight: holdings.forwardWeight,
      target: formation.forward,
      value: holdings.forwardValue,
    },
  ];
}

// "약 2,500만 원" per field position that is below its target; null when
// there is nothing to advise (cash is the goalkeeper, so it is never listed).
export function buyHint(holdings: Holdings): string | null {
  const gaps: { label: string; man: string }[] = [];
  for (const slice of slicesOf(holdings).slice(1)) {
    const gap = positionGap(holdings.totalValue, slice.target, slice.value);
    const won = gap === null ? null : floorToManWon(gap);
    if (won === null || won === '0') continue;
    gaps.push({ label: slice.label, man: won.slice(0, -4) });
  }
  const [first] = gaps;
  if (first === undefined) return null;
  const same = gaps.every((gap) => gap.man === first.man);
  const money = (man: string) => `약 ${groupThousands(man)}만 원`;
  if (same) {
    const names = gaps.map((gap) => gap.label).join('·');
    return `${names}에 각각 ${money(first.man)}씩 사면 목표와 맞습니다.`;
  }
  const parts = gaps.map((gap) => `${gap.label} ${money(gap.man)}`);
  return `${parts.join(', ')}을 사면 목표와 맞습니다.`;
}
