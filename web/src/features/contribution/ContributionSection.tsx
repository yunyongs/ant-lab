import { useId, useState } from 'react';
import { Change } from '../format/Change.tsx';
import { formatWon } from '../format/money.ts';
import { SectionHeading, type Collapse } from '../layout/SectionHeading.tsx';
import { QueryError } from '../seasons/QueryError.tsx';
import { usePool } from '../team/queries.ts';
import { contributionLists, type ContributionRow } from './contributionRows.ts';
import {
  useTurnContribution,
  type PositionContribution,
  type TurnContribution,
} from './queries.ts';
import styles from './Contribution.module.css';

// Where one played turn's change came from, and the season report's benchmark
// up to that turn. The caller passes a turn that is at most the season's
// current turn; turn 0 and anything later are not requested. A failure stays
// inside this section.
export function ContributionSection({
  seasonId,
  turnNo,
  currentTurnNo,
  title,
  collapse,
}: {
  seasonId: number;
  turnNo: number;
  currentTurnNo: number;
  title: string;
  collapse?: Collapse;
}) {
  const titleId = useId();
  const panelId = useId();
  const [open, setOpen] = useState(collapse?.defaultOpen ?? true);
  const inRange = turnNo >= 1 && turnNo <= currentTurnNo;
  const contribution = useTurnContribution(seasonId, turnNo, inRange && open);
  const pool = usePool();
  if (!inRange) return null;
  const error = contribution.isError
    ? contribution.error
    : pool.isError
      ? pool.error
      : null;
  return (
    <section aria-labelledby={titleId} className={styles.section}>
      <SectionHeading
        id={titleId}
        className={styles.title}
        collapsible={collapse !== undefined}
        open={open}
        panelId={panelId}
        onToggle={() => {
          setOpen((value) => !value);
        }}
      >
        {title}
      </SectionHeading>
      {open && (
        <div id={panelId} className={styles.panel}>
          {(contribution.isPending || pool.isPending) && error === null && (
            <p role="status" className={styles.note}>
              기여를 불러오는 중입니다…
            </p>
          )}
          {error !== null && (
            <QueryError
              error={error}
              onRetry={() => {
                if (contribution.isError) void contribution.refetch();
                if (pool.isError) void pool.refetch();
              }}
            />
          )}
          {contribution.data !== undefined && pool.data !== undefined && (
            <ContributionBody
              data={contribution.data}
              lists={contributionLists(contribution.data.holdings, pool.data)}
            />
          )}
        </div>
      )}
    </section>
  );
}

function ContributionBody({
  data,
  lists,
}: {
  data: TurnContribution;
  lists: ReturnType<typeof contributionLists>;
}) {
  const [showAll, setShowAll] = useState(false);
  const allId = useId();
  return (
    <>
      <dl className={styles.rows}>
        <div className={styles.row}>
          <dt>가격 변화 기여 합</dt>
          <dd>
            <Change value={data.holdingsContribution} kind="won" />
          </dd>
        </div>
        <div className={styles.row}>
          <dt>직전 턴 매매 비용</dt>
          <dd>{formatWon(data.tradingCost)}</dd>
        </div>
      </dl>
      <p className={styles.hint}>
        직전 턴 대비 변화 = 가격 변화 기여 합 − 직전 턴 매매 비용(수수료·환전
        비용·세금)
      </p>

      <div className={styles.group}>
        <h4 className={styles.subtitle}>기준 대비</h4>
        <dl className={styles.rows}>
          <div className={styles.row}>
            <dt>팀 누적 수익률</dt>
            <dd>
              <Change value={data.totalReturn} kind="percent" />
            </dd>
          </div>
          <div className={styles.row}>
            <dt>기준선 평가액</dt>
            <dd>{formatWon(data.benchmark.value)}</dd>
          </div>
          <div className={styles.row}>
            <dt>기준선 누적 수익률</dt>
            <dd>
              <Change value={data.benchmark.totalReturn} kind="percent" />
            </dd>
          </div>
          <div className={styles.row}>
            <dt>초과 수익률</dt>
            <dd>
              <Change value={data.benchmark.excessReturn} kind="percent" />
            </dd>
          </div>
        </dl>
        <p className={styles.hint}>
          기준선은 성적표와 같은 시장 지수 기준선입니다. 초과 수익률 = (팀
          평가액 − 기준선 평가액) ÷ 초기 자금
        </p>
      </div>

      {data.holdings.length === 0 ? (
        <p className={styles.note}>
          이 턴으로 넘어오는 동안 보유한 종목이 없습니다.
        </p>
      ) : (
        <>
          <MoverGroup
            title="많이 더한 종목"
            rows={lists.up}
            empty="값을 더한 종목이 없습니다."
          />
          <MoverGroup
            title="많이 뺀 종목"
            rows={lists.down}
            empty="값을 뺀 종목이 없습니다."
          />
          {lists.hasMore && (
            <div className={styles.group}>
              <button
                type="button"
                className={styles.more}
                aria-expanded={showAll}
                aria-controls={allId}
                onClick={() => {
                  setShowAll((value) => !value);
                }}
              >
                {showAll
                  ? '전체 접기'
                  : `전체 보기 (${String(lists.all.length)}종목)`}
              </button>
              {showAll && (
                <div id={allId} className={styles.group}>
                  <h4 className={styles.subtitle}>전체 종목 기여</h4>
                  <HoldingRows rows={lists.all} />
                </div>
              )}
            </div>
          )}
        </>
      )}

      <div className={styles.group}>
        <h4 className={styles.subtitle}>포지션별 기여</h4>
        <ul className={styles.list}>
          <PositionRow
            label="골키퍼 (현금 포함)"
            row={data.positions.goalkeeper}
          />
          <PositionRow label="수비수" row={data.positions.defender} />
          <PositionRow label="미드필더" row={data.positions.midfielder} />
          <PositionRow label="공격수" row={data.positions.forward} />
        </ul>
        <p className={styles.hint}>
          포지션 기여는 가격 변화만 셉니다. 매매로 현금과 포지션 사이를 옮긴
          돈은 들어가지 않습니다.
        </p>
      </div>
    </>
  );
}

function MoverGroup({
  title,
  rows,
  empty,
}: {
  title: string;
  rows: ContributionRow[];
  empty: string;
}) {
  return (
    <div className={styles.group}>
      <h4 className={styles.subtitle}>{title}</h4>
      {rows.length === 0 ? (
        <p className={styles.note}>{empty}</p>
      ) : (
        <HoldingRows rows={rows} />
      )}
    </div>
  );
}

function HoldingRows({ rows }: { rows: ContributionRow[] }) {
  return (
    <ul className={styles.list}>
      {rows.map((row) => (
        <li key={row.assetId} className={styles.item}>
          <span className={styles.name}>{row.name}</span>
          <span className={styles.amount}>
            <Change value={row.contribution} kind="won" />
          </span>
          <span className={styles.meta}>
            {`${row.position} · ${String(row.quantity)}주 · 가격 `}
            <Change value={row.priceReturn} kind="percent" />
          </span>
        </li>
      ))}
    </ul>
  );
}

function PositionRow({
  label,
  row,
}: {
  label: string;
  row: PositionContribution;
}) {
  return (
    <li className={styles.item}>
      <span className={styles.name}>{label}</span>
      <span className={styles.amount}>
        <Change value={row.contribution} kind="won" />
      </span>
      <span className={styles.meta}>
        {'턴 수익률 몫 '}
        <Change value={row.contributionReturn} kind="percent" />
      </span>
    </li>
  );
}
