import { formatWon } from '../format/money.ts';
import { ratioWithPercent } from '../format/ratio.ts';
import type { Analysis } from './queries.ts';
import { NOISE_SHARE_NONE } from './analysisMessages.ts';
import { Rows } from './Rows.tsx';
import styles from './Analysis.module.css';

export function NoiseBlock({ noise }: { noise: Analysis['noise'] }) {
  return (
    <section className={styles.card} aria-labelledby="analysis-noise">
      <h2 id="analysis-noise" className={styles.cardHeading}>
        소음 반응
      </h2>
      <Rows
        rows={[
          {
            label: '소음을 따른 비중',
            value:
              noise.noiseShare === null
                ? NOISE_SHARE_NONE
                : ratioWithPercent(noise.noiseShare),
          },
          { label: '매매한 턴', value: `${String(noise.tradingTurns)}턴` },
          {
            label: '따라 산 금액',
            value: formatWon(noise.followedBuyNotional),
          },
          {
            label: '따라 판 금액',
            value: formatWon(noise.followedSellNotional),
          },
          { label: '매매 대금', value: formatWon(noise.tradeNotional) },
        ]}
      />
    </section>
  );
}
