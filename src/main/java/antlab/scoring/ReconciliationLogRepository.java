package antlab.scoring;

import antlab.common.persistence.AppendOnlyRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 대사 기록 추가 전용 저장소 + 대사 규칙 쿼리 15개(P0-32).
 *
 * <p>규칙 쿼리는 {@code db/22_reconcile_season_def.sql} 의 규칙 함수 본문을 기계적 치환(매개변수 → {@code CAST(:seasonId AS bigint)}·
 * {@code CAST(:turnNo AS smallint)}, 도우미 함수 인라인, 바깥 껍데기 별칭)만 거쳐 그대로 옮긴 사본이다.
 * 계산식을 다시 만들지 않는다 — 22 를 고치면 {@code ReconciliationSqlCopyTest} 가 깨진다. 각 쿼리는 정확히 1행을 돌려준다.
 */
public interface ReconciliationLogRepository extends AppendOnlyRepository<ReconciliationLog, Long> {

    /** 그 (시즌, 턴)의 가장 큰 시도 번호. 기록이 없으면 0. */
    @Query("select coalesce(max(l.attemptNo), 0) from ReconciliationLog l where l.seasonId = :seasonId and l.turnNo = :turnNo")
    int findMaxAttemptNo(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 지금 트랜잭션의 격리 수준(소문자 문장, 예: {@code read committed}). 읽기 일관성 가드가 쓴다. */
    @Query(nativeQuery = true, value = "SELECT current_setting('transaction_isolation')")
    String findTransactionIsolation();

    /** 규칙 1 AccountOpening — db/22 recon_r01_account_opening 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH f AS (
                        SELECT (SELECT l.type   FROM cash_ledger l WHERE l.season_id = CAST(:seasonId AS bigint) ORDER BY l.id LIMIT 1) AS ftype,
                               (SELECT l.amount FROM cash_ledger l WHERE l.season_id = CAST(:seasonId AS bigint) ORDER BY l.id LIMIT 1) AS famt,
                               EXISTS (SELECT 1 FROM cash_account a WHERE a.season_id = CAST(:seasonId AS bigint)) AS has_acct,
                               EXISTS (SELECT 1 FROM cash_ledger l WHERE l.season_id = CAST(:seasonId AS bigint)) AS has_led,
                               (SELECT s.initial_cash FROM season s WHERE s.id = CAST(:seasonId AS bigint)) AS init
                    ), bad AS (
                        SELECT k.ord::bigint AS k1, jsonb_build_object('kind', k.kind) AS item
                        FROM f
                        CROSS JOIN LATERAL (VALUES (1, 'NO_ACCOUNT',        NOT f.has_acct),
                                                   (2, 'NO_LEDGER',         NOT f.has_led),
                                                   (3, 'FIRST_NOT_DEPOSIT', f.ftype IS DISTINCT FROM 'DEPOSIT'),
                                                   (4, 'DEPOSIT_AMOUNT',    f.famt IS DISTINCT FROM f.init)) AS k(ord, kind, bad)
                        WHERE k.bad
                    )
                    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)
                    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule01AccountOpening(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 2 CashSum — db/22 recon_r02_cash_sum 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH x AS (
                        SELECT a.season_id IS NOT NULL AS has_acct,
                               COALESCE((SELECT sum(l.amount) FROM cash_ledger l WHERE l.season_id = CAST(:seasonId AS bigint)), 0) AS s,
                               a.balance AS bal
                        FROM (SELECT 1) one
                        LEFT JOIN cash_account a ON a.season_id = CAST(:seasonId AS bigint)
                    ), bad AS (
                        SELECT 1::bigint AS k1,
                               jsonb_build_object('kind', CASE WHEN NOT x.has_acct THEN 'NO_ACCOUNT' ELSE 'SUM' END) AS item
                        FROM x
                        WHERE NOT x.has_acct OR x.s <> x.bal
                    )
                    SELECT CASE WHEN x.has_acct THEN x.s END, x.bal,
                           COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)

                    FROM x, (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule02CashSum(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 3 LedgerChain — db/22 recon_r03_ledger_chain 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH ch AS (
                        SELECT l.id, l.balance_after, l.amount,
                               COALESCE(lag(l.balance_after) OVER (ORDER BY l.id), 0) AS prev
                        FROM cash_ledger l
                        WHERE l.season_id = CAST(:seasonId AS bigint)
                    ), bad AS (
                        SELECT c.id AS k1, jsonb_build_object('kind', 'CHAIN', 'ledger_id', c.id) AS item
                        FROM ch c
                        WHERE c.balance_after <> c.prev + c.amount
                        UNION ALL
                        SELECT 9223372036854775807::bigint, jsonb_build_object('kind', 'LATEST')
                        FROM (SELECT c.balance_after FROM ch c ORDER BY c.id DESC LIMIT 1) lst
                        JOIN cash_account a ON a.season_id = CAST(:seasonId AS bigint)
                        WHERE a.balance <> lst.balance_after
                    )
                    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)
                    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule03LedgerChain(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 4 OrderLedger — db/22 recon_r04_order_ledger 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH ex AS (
                        SELECT o.id AS order_id, o.side, sum(e.fill_price * e.quantity) AS gross, sum(e.fee) AS fee, sum(e.tax) AS tax
                        FROM orders o
                        JOIN execution e ON e.order_id = o.id
                        WHERE o.season_id = CAST(:seasonId AS bigint)
                        GROUP BY o.id, o.side
                    ), led AS (
                        SELECT l.ref_order_id AS order_id,
                               sum(l.amount) AS total,
                               COALESCE(sum(l.amount) FILTER (WHERE l.type IN ('BUY', 'SELL')), 0) AS trade_sum,
                               COALESCE(sum(l.amount) FILTER (WHERE l.type = 'FEE'), 0) AS fee_sum,
                               COALESCE(sum(l.amount) FILTER (WHERE l.type = 'TAX'), 0) AS tax_sum,
                               COALESCE(bool_or(l.type IN ('BUY', 'SELL') AND l.type <> o.side), false) AS side_bad
                        FROM cash_ledger l
                        JOIN orders o ON o.id = l.ref_order_id
                        WHERE l.season_id = CAST(:seasonId AS bigint) AND l.ref_order_id IS NOT NULL
                        GROUP BY l.ref_order_id
                    ), j AS (
                        SELECT COALESCE(ex.order_id, led.order_id) AS order_id,
                               ex.order_id IS NULL AS no_exec,
                               led.order_id IS NULL AS no_led,
                               CASE WHEN ex.order_id IS NULL THEN 0
                                    ELSE (CASE ex.side WHEN 'BUY' THEN -1 ELSE 1 END) * ex.gross - ex.fee - ex.tax END AS exp_total,
                               COALESCE(led.total, 0) AS act_total,
                               ex.order_id IS NOT NULL AND led.order_id IS NOT NULL
                                   AND (led.trade_sum <> (CASE ex.side WHEN 'BUY' THEN -1 ELSE 1 END) * ex.gross
                                        OR led.fee_sum <> -ex.fee OR led.tax_sum <> -ex.tax) AS type_bad,
                               COALESCE(led.side_bad, false) AS side_bad
                        FROM ex
                        FULL JOIN led ON led.order_id = ex.order_id
                    ), k AS (
                        SELECT j.order_id,
                               array_remove(ARRAY[CASE WHEN j.exp_total <> j.act_total THEN 'TOTAL' END,
                                                  CASE WHEN j.type_bad  THEN 'TYPE' END,
                                                  CASE WHEN j.side_bad  THEN 'SIDE' END,
                                                  CASE WHEN j.no_exec   THEN 'ORPHAN_LEDGER' END,
                                                  CASE WHEN j.no_led    THEN 'NO_LEDGER' END], NULL) AS kinds
                        FROM j
                    )
                    SELECT (SELECT COALESCE(sum(j.exp_total), 0) FROM j)::numeric(19,4),
                           (SELECT COALESCE(sum(j.act_total), 0) FROM j)::numeric(19,4),
                           COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)
                    FROM (SELECT jsonb_agg(jsonb_build_object('kind', k.kinds[1], 'order_id', k.order_id, 'kinds', to_jsonb(k.kinds)) ORDER BY k.order_id) AS items
                          FROM k WHERE cardinality(k.kinds) > 0) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule04OrderLedger(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 6 HoldingQty — db/22 recon_r06_holding_qty 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH h AS (
                        SELECT hd.asset_id, hd.quantity FROM holding hd WHERE hd.season_id = CAST(:seasonId AS bigint)
                    ), x AS (
                        SELECT o.asset_id, sum(CASE o.side WHEN 'BUY' THEN e.quantity ELSE -e.quantity END) AS q
                        FROM orders o
                        JOIN execution e ON e.order_id = o.id
                        WHERE o.season_id = CAST(:seasonId AS bigint)
                        GROUP BY o.asset_id
                    ), j AS (
                        SELECT COALESCE(h.asset_id, x.asset_id) AS asset_id, h.asset_id IS NULL AS no_h, x.asset_id IS NULL AS no_x,
                               h.quantity AS hq, x.q AS xq
                        FROM h
                        FULL JOIN x ON x.asset_id = h.asset_id
                    ), bad AS (
                        SELECT j.asset_id AS k1,
                               jsonb_build_object('kind', CASE WHEN j.no_h THEN 'NO_HOLDING' WHEN j.no_x THEN 'NO_EXECUTION' ELSE 'QTY' END,
                                                  'asset_id', j.asset_id) AS item
                        FROM j
                        WHERE j.no_h OR j.no_x OR j.hq <> j.xq
                    )
                    SELECT (SELECT COALESCE(sum(j.xq), 0) FROM j)::numeric(19,4),
                           (SELECT COALESCE(sum(j.hq), 0) FROM j)::numeric(19,4),
                           COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)
                    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule06HoldingQty(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 7 OrderFill — db/22 recon_r07_order_fill 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH k AS (
                        SELECT o.id AS order_id,
                               array_remove(ARRAY[
                                   CASE WHEN o.status <> 'FILLED' THEN 'STATUS' END,
                                   CASE WHEN o.status = 'FILLED'
                                             AND COALESCE((SELECT sum(e.quantity) FROM execution e WHERE e.order_id = o.id), 0) <> o.quantity THEN 'FILL_QTY' END,
                                   CASE WHEN o.type <> 'MARKET' OR o.limit_price IS NOT NULL THEN 'TYPE' END], NULL) AS kinds
                        FROM orders o
                        WHERE o.season_id = CAST(:seasonId AS bigint)
                    )
                    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)
                    FROM (SELECT jsonb_agg(jsonb_build_object('kind', k.kinds[1], 'order_id', k.order_id, 'kinds', to_jsonb(k.kinds)) ORDER BY k.order_id) AS items
                          FROM k WHERE cardinality(k.kinds) > 0) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule07OrderFill(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 8 ExecValues — db/22 recon_r08_exec_values 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH k AS (
                        SELECT e.id AS execution_id,
                               array_remove(ARRAY[
                                   CASE WHEN o.side = 'BUY'  AND e.realized_pnl IS NOT NULL THEN 'BUY_PNL' END,
                                   CASE WHEN o.side = 'SELL' AND e.realized_pnl IS NULL     THEN 'SELL_PNL' END,
                                   CASE WHEN e.tax <> 0 THEN 'TAX' END], NULL) AS kinds
                        FROM execution e
                        JOIN orders o ON o.id = e.order_id
                        WHERE o.season_id = CAST(:seasonId AS bigint)
                    )
                    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)
                    FROM (SELECT jsonb_agg(jsonb_build_object('kind', k.kinds[1], 'execution_id', k.execution_id, 'kinds', to_jsonb(k.kinds)) ORDER BY k.execution_id) AS items
                          FROM k WHERE cardinality(k.kinds) > 0) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule08ExecValues(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 9 ReservedZero — db/22 recon_r09_reserved_zero 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH x AS (
                        SELECT a.season_id IS NOT NULL AS has_acct, a.reserved AS rsv
                        FROM (SELECT 1) one
                        LEFT JOIN cash_account a ON a.season_id = CAST(:seasonId AS bigint)
                    ), bad AS (
                        SELECT 1::bigint AS k1,
                               jsonb_build_object('kind', CASE WHEN NOT x.has_acct THEN 'NO_ACCOUNT' ELSE 'RESERVED' END) AS item
                        FROM x
                        WHERE NOT x.has_acct OR x.rsv <> 0
                    )
                    SELECT CASE WHEN x.has_acct THEN 0::numeric(19,4) END, x.rsv,
                           COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)

                    FROM x, (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule09ReservedZero(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 12 TurnPrice — db/22 recon_r12_turn_price 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH e AS (
                        SELECT x.turn_no, x.asset_id, x.price FROM
                        (WITH pool AS ( SELECT ap.asset_id, a.currency, ROUND(ap.base_price * ap.base_fx_rate, 0) AS p0 FROM season s JOIN asset_position
                        ap ON ap.base_date = s.base_date JOIN asset a ON a.id = ap.asset_id WHERE s.id = CAST(:seasonId AS bigint) ), f AS ( SELECT pl.asset_id, t.k,
                        pl.p0, array_agg((1 + amr.local_return) * CASE WHEN pl.currency = 'KRW' THEN 1 ELSE 1 + fx.fx_change END) OVER (PARTITION BY
                        pl.asset_id ORDER BY t.k) AS arr FROM pool pl CROSS JOIN generate_series(1, CAST(:turnNo AS smallint)) AS t(k) LEFT JOIN season_path sp ON
                        sp.season_id = CAST(:seasonId AS bigint) AND sp.turn_no = t.k LEFT JOIN asset_month_return amr ON amr.asset_id = pl.asset_id AND
                        amr.month_block_id = sp.month_block_id LEFT JOIN fx_month fx ON fx.month_block_id = sp.month_block_id AND fx.currency =
                        pl.currency ) SELECT 0::smallint, pl.asset_id, (WITH RECURSIVE r(i, acc) AS ( SELECT 0, (pl.p0) UNION ALL SELECT r.i + 1, r.acc *
                        (ARRAY[]::numeric[])[r.i + 1] FROM r WHERE r.i < COALESCE(cardinality((ARRAY[]::numeric[])), 0) ) SELECT ROUND(r.acc, 0) FROM r
                        ORDER BY r.i DESC LIMIT 1) FROM pool pl UNION ALL SELECT f.k::smallint, f.asset_id, (WITH RECURSIVE r(i, acc) AS ( SELECT 0,
                        (f.p0) UNION ALL SELECT r.i + 1, r.acc * (f.arr)[r.i + 1] FROM r WHERE r.i < COALESCE(cardinality((f.arr)), 0) ) SELECT
                        ROUND(r.acc, 0) FROM r ORDER BY r.i DESC LIMIT 1) FROM f) x(turn_no, asset_id, price)

                    ), s AS (
                        SELECT tp.turn_no, tp.asset_id, tp.price FROM turn_price tp WHERE tp.season_id = CAST(:seasonId AS bigint)
                    ), j AS (
                        SELECT COALESCE(e.turn_no, s.turn_no) AS t, COALESCE(e.asset_id, s.asset_id) AS a,
                               e.turn_no IS NULL AS no_e, s.turn_no IS NULL AS no_s, e.price AS ep, s.price AS sp
                        FROM e
                        FULL JOIN s ON s.turn_no = e.turn_no AND s.asset_id = e.asset_id
                    ), bad AS (
                        SELECT q.t, q.a, q.kind
                        FROM (SELECT j.t, j.a,
                                     CASE WHEN j.no_e AND j.t > CAST(:turnNo AS smallint) THEN 'FUTURE'
                                          WHEN j.no_e THEN 'EXTRA'
                                          WHEN j.ep IS NULL THEN 'NO_RETURN'
                                          WHEN j.no_s THEN 'MISSING'
                                          WHEN j.sp <> j.ep THEN 'PRICE' END AS kind
                              FROM j) q
                        WHERE q.kind IS NOT NULL
                    )
                    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)
                    FROM (SELECT jsonb_agg(jsonb_build_object('kind', bad.kind, 'turn_no', bad.t, 'asset_id', bad.a) ORDER BY bad.t, bad.a) AS items FROM bad) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule12TurnPrice(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 13 FillPrice — db/22 recon_r13_fill_price 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH bad AS (
                        SELECT e.id AS execution_id, o.turn_no,
                               CASE WHEN tp.id IS NULL THEN 'NO_PRICE' ELSE 'PRICE' END AS kind
                        FROM execution e
                        JOIN orders o ON o.id = e.order_id
                        LEFT JOIN turn_price tp ON tp.season_id = o.season_id AND tp.turn_no = o.turn_no AND tp.asset_id = o.asset_id
                        WHERE o.season_id = CAST(:seasonId AS bigint) AND tp.price IS DISTINCT FROM e.fill_price
                    )
                    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)
                    FROM (SELECT jsonb_agg(jsonb_build_object('kind', bad.kind, 'execution_id', bad.execution_id, 'turn_no', bad.turn_no) ORDER BY bad.execution_id) AS items FROM bad) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule13FillPrice(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 14 FeeRule — db/22 recon_r14_fee_rule 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH x AS (
                        SELECT e.id AS execution_id, e.fee,
                               div(2 * e.fill_price * e.quantity * r.r + 100000000, 200000000) AS exp_fee
                        FROM execution e
                        JOIN orders o ON o.id = e.order_id
                        JOIN asset a ON a.id = o.asset_id
                        JOIN season s ON s.id = o.season_id
                        CROSS JOIN LATERAL (SELECT CASE a.market WHEN 'KR' THEN s.fee_rate_kr WHEN 'US' THEN s.fee_rate_us + s.fx_spread_rate END * 100000000 AS r) r
                        WHERE o.season_id = CAST(:seasonId AS bigint)
                    ), bad AS (
                        SELECT x.execution_id AS k1, jsonb_build_object('kind', 'FEE', 'execution_id', x.execution_id) AS item
                        FROM x
                        WHERE x.fee IS DISTINCT FROM x.exp_fee
                    )
                    SELECT (SELECT COALESCE(sum(x.exp_fee), 0) FROM x)::numeric(19,4),
                           (SELECT COALESCE(sum(x.fee), 0) FROM x)::numeric(19,4),
                           COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)
                    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule14FeeRule(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 15 Formation — db/22 recon_r15_formation 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH s AS (
                        SELECT ARRAY[se.start_goalkeeper, se.start_defender, se.start_midfielder, se.start_forward] AS st,
                               ARRAY[se.current_goalkeeper, se.current_defender, se.current_midfielder, se.current_forward] AS cur
                        FROM season se
                        WHERE se.id = CAST(:seasonId AS bigint)
                    ), chg AS (
                        SELECT fc.id,
                               ARRAY[fc.before_goalkeeper, fc.before_defender, fc.before_midfielder, fc.before_forward] AS b,
                               ARRAY[fc.after_goalkeeper, fc.after_defender, fc.after_midfielder, fc.after_forward] AS a,
                               lag(ARRAY[fc.after_goalkeeper, fc.after_defender, fc.after_midfielder, fc.after_forward]) OVER (ORDER BY fc.id) AS prev_a
                        FROM formation_change fc
                        WHERE fc.season_id = CAST(:seasonId AS bigint)
                    ), bad AS (
                        SELECT 0::bigint AS k1, jsonb_build_object('kind', 'CURRENT') AS item
                        FROM s
                        WHERE s.cur IS DISTINCT FROM COALESCE((SELECT c.a FROM chg c ORDER BY c.id DESC LIMIT 1), s.st)
                        UNION ALL
                        SELECT c.id, jsonb_build_object('kind', 'CHAIN', 'formation_change_id', c.id)
                        FROM chg c
                        CROSS JOIN s
                        WHERE c.b IS DISTINCT FROM COALESCE(c.prev_a, s.st)
                    )
                    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)
                    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1) AS items FROM bad) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule15Formation(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 16 FormationBind — db/22 recon_r16_formation_bind 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH k AS (
                        SELECT o.id AS order_id, o.turn_no,
                               CASE WHEN EXISTS (SELECT 1 FROM formation_change fc
                                                 WHERE fc.season_id = o.season_id AND fc.turn_no = o.turn_no AND fc.created_at = o.created_at) THEN 'TIE'
                                    WHEN o.formation_change_id IS DISTINCT FROM
                                         (SELECT fc.id FROM formation_change fc
                                          WHERE fc.season_id = o.season_id AND fc.turn_no = o.turn_no AND fc.created_at < o.created_at
                                          ORDER BY fc.created_at DESC, fc.id DESC LIMIT 1)
                                    THEN CASE WHEN o.formation_change_id IS NULL THEN 'UNBOUND' ELSE 'WRONG' END
                               END AS kind
                        FROM orders o
                        WHERE o.season_id = CAST(:seasonId AS bigint)
                    )
                    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)
                    FROM (SELECT jsonb_agg(jsonb_build_object('kind', k.kind, 'order_id', k.order_id, 'turn_no', k.turn_no) ORDER BY k.order_id) AS items
                          FROM k WHERE k.kind IS NOT NULL) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule16FormationBind(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 17 LineupHolding — db/22 recon_r17_lineup_holding 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH o AS (
                        SELECT DISTINCT lu.asset_id FROM lineup lu WHERE lu.season_id = CAST(:seasonId AS bigint) AND lu.end_turn IS NULL
                    ), h AS (
                        SELECT hd.asset_id FROM holding hd WHERE hd.season_id = CAST(:seasonId AS bigint) AND hd.quantity > 0
                    ), bad AS (
                        SELECT COALESCE(o.asset_id, h.asset_id) AS asset_id,
                               CASE WHEN h.asset_id IS NULL THEN 'OPEN_NO_HOLDING' ELSE 'HOLDING_NO_OPEN' END AS kind
                        FROM o
                        FULL JOIN h ON h.asset_id = o.asset_id
                        WHERE o.asset_id IS NULL OR h.asset_id IS NULL
                    )
                    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)
                    FROM (SELECT jsonb_agg(jsonb_build_object('kind', bad.kind, 'asset_id', bad.asset_id) ORDER BY bad.asset_id) AS items FROM bad) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule17LineupHolding(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);

    /** 규칙 18 LineupOverlap — db/22 recon_r18_lineup_overlap 의 사본. */
    @Query(nativeQuery = true, value = """
            SELECT r.expected_value AS "expectedValue", r.actual_value AS "actualValue",
                   r.mismatch_count AS "mismatchCount", CAST(r.detail AS text) AS "detail"
            FROM (
                    WITH bad AS (
                        SELECT a.id AS k1, b.id AS k2,
                               jsonb_build_object('kind', 'OVERLAP', 'lineup_id', a.id, 'other_lineup_id', b.id) AS item
                        FROM lineup a
                        JOIN lineup b ON b.season_id = a.season_id AND b.asset_id = a.asset_id AND b.id > a.id
                        WHERE a.season_id = CAST(:seasonId AS bigint)
                          AND a.start_turn < COALESCE(b.end_turn, 32767) AND b.start_turn < COALESCE(a.end_turn, 32767)
                        UNION ALL
                        SELECT lu.id, 0::bigint,
                               jsonb_build_object('kind', CASE WHEN lu.start_turn > CAST(:turnNo AS smallint) THEN 'START_AFTER' ELSE 'END_AFTER' END, 'lineup_id', lu.id)
                        FROM lineup lu
                        WHERE lu.season_id = CAST(:seasonId AS bigint) AND (lu.start_turn > CAST(:turnNo AS smallint) OR lu.end_turn > CAST(:turnNo AS smallint))
                    )
                    SELECT NULL::numeric(19,4), NULL::numeric(19,4), COALESCE(jsonb_array_length(a.items), 0),
                        (CASE WHEN a.items IS NULL OR jsonb_array_length(a.items) = 0 THEN NULL ELSE jsonb_build_object('items',
                        jsonb_path_query_array(a.items, '$[0 to 99]'), 'total', jsonb_array_length(a.items), 'truncated', jsonb_array_length(a.items) >
                        100) END)
                    FROM (SELECT jsonb_agg(bad.item ORDER BY bad.k1, bad.k2) AS items FROM bad) a
            ) AS r(expected_value, actual_value, mismatch_count, detail)
            """)
    RuleRow findRule18LineupOverlap(@Param("seasonId") long seasonId, @Param("turnNo") int turnNo);
}
