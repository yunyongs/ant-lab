// Bottom menu. A flag flips to true when its screen exists (WEB-07..09).
export interface NavConfig {
  key: 'seasons' | 'turn' | 'trade' | 'report' | 'review';
  label: string;
  enabled: boolean;
}

export const NAV_CONFIG: readonly NavConfig[] = [
  { key: 'seasons', label: '시즌', enabled: true },
  { key: 'turn', label: '턴', enabled: true },
  { key: 'trade', label: '매매', enabled: false },
  { key: 'report', label: '성적표', enabled: false },
  { key: 'review', label: '복기', enabled: false },
];
