export type TabKey = 'change' | 'attention' | 'streak' | 'group';

export const TABS: readonly { key: TabKey; label: string }[] = [
  { key: 'change', label: '등락' },
  { key: 'attention', label: '관심' },
  { key: 'streak', label: '연속' },
  { key: 'group', label: '그룹' },
];

export const EMPTY_ASSETS_TEXT = '해당하는 종목이 없습니다';
export const EMPTY_GROUPS_TEXT = '해당하는 그룹이 없습니다';
export const UNKNOWN_GROUP_NAME = '이름을 찾지 못한 그룹';

// 3 = top 10 %, 2 = top half, 1 = the rest. The index value is not in the
// DTO and is never rebuilt.
export function gradeLabel(grade: number): string {
  switch (grade) {
    case 3:
      return '매우 높음';
    case 2:
      return '높음';
    case 1:
      return '보통';
    default:
      return '알 수 없음';
  }
}
