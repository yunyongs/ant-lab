import { setConvention, useConvention } from './convention.ts';
import styles from './ConventionToggle.module.css';

export function ConventionToggle() {
  const convention = useConvention();
  const western = convention === 'western';
  const current = western ? '상승 초록 · 하락 빨강' : '상승 빨강 · 하락 파랑';
  const other = western ? '상승 빨강 · 하락 파랑' : '상승 초록 · 하락 빨강';
  return (
    <button
      type="button"
      className={styles.toggle}
      aria-label={`등락 색: ${current}. 누르면 ${other}(으)로 바꿉니다`}
      onClick={() => {
        setConvention(western ? 'korean' : 'western');
      }}
    >
      {current}
    </button>
  );
}
