// Bottom menu. A flag flips to true when its screen exists (WEB-07..09).
export interface NavConfig {
  key: 'seasons' | 'turn' | 'trade' | 'report' | 'review';
  label: string;
  enabled: boolean;
}

export const NAV_CONFIG: readonly NavConfig[] = [
  { key: 'seasons', label: '시즌', enabled: true },
  { key: 'turn', label: '턴', enabled: true },
  { key: 'trade', label: '매매', enabled: true },
  { key: 'report', label: '성적표', enabled: true },
  { key: 'review', label: '복기', enabled: true },
];

export function isNavEnabled(key: NavConfig['key']): boolean {
  return NAV_CONFIG.some((item) => item.key === key && item.enabled);
}
