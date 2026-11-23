import { formatWon } from '../format/money.ts';
import { signedChange } from '../format/signedChange.ts';
import type { Analysis } from './queries.ts';
import { ATTENTION_RETURN_NONE, NOT_SCORED_NOTE } from './analysisMessages.ts';
import { Rows } from './Rows.tsx';
import styles from './Analysis.module.css';

export function AttentionBlock({
  attention,
}: {
  attention: Analysis['attentionFollowing'];
}) {
  const windowText = `${String(attention.windowTurns)}턴`;
  return (
    <section className={styles.card} aria-labelledby="analysis-attention">
      <h3 id="analysis-attention" className={styles.cardHeading}>
        관심 추종
      </h3>
      <p className={styles.note}>
        {`관심이 높은 종목을 산 매수와 그 밖의 매수가 ${windowText} 뒤 어떻게 됐는지입니다. ${NOT_SCORED_NOTE}`}
      </p>
      <Rows
        rows={[
          {
            label: '관심 상위 종목 매수',
            value: `${String(attention.attentionBuys)}건 · ${formatWon(attention.attentionBuyNotional)}`,
          },
          {
            label: `관심 상위 매수의 ${windowText} 뒤 수익률`,
            value:
              attention.attentionReturn === null
                ? ATTENTION_RETURN_NONE
                : signedChange(attention.attentionReturn, 'percent'),
          },
          {
            label: '그 밖의 매수',
            value: `${String(attention.otherBuys)}건 · ${formatWon(attention.otherBuyNotional)}`,
          },
          {
            label: `그 밖의 매수의 ${windowText} 뒤 수익률`,
            value:
              attention.otherReturn === null
                ? ATTENTION_RETURN_NONE
                : signedChange(attention.otherReturn, 'percent'),
          },
          {
            label: '창이 끝나지 않아 어느 쪽에도 넣지 않은 매수',
            value: `${String(attention.incompleteBuys)}건`,
          },
        ]}
      />
    </section>
  );
}
