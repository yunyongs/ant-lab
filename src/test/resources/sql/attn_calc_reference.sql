-- Reference copy of the original (quadratic, correlated-subquery) db/13 definition, view renamed attn_calc_ref. Test only: AttentionCalcEquivalenceTest. Do not delete: it is the only independent formulation of the definition; when the definition changes, update both or re-derive this one.
DO $$ BEGIN
    IF to_regclass('pg_temp.attn_calc_ref') IS NOT NULL THEN DROP VIEW pg_temp.attn_calc_ref; END IF;
END $$;
CREATE TEMP VIEW attn_calc_ref AS
WITH k AS (
    SELECT 12              AS win_months,
           0.0001::numeric AS sd_floor,
           3::numeric      AS z_cap,
           0.25::numeric   AS bonus_step,
           5               AS streak_cap
),
r AS (
    SELECT ar.asset_id, ar.month_block_id, mb.month_start,
           abs(ar.local_return)     AS x,
           sign(ar.local_return)::integer AS sg
    FROM asset_month_return ar
    JOIN month_block mb ON mb.id = ar.month_block_id
),
starts AS MATERIALIZED (
    SELECT s.asset_id, s.month_start
    FROM r s
    WHERE NOT EXISTS (SELECT 1 FROM r p
                      WHERE p.asset_id = s.asset_id
                        AND p.month_start = (s.month_start - INTERVAL '1 month')::date)
),
seg AS (
    SELECT r.*,
           (SELECT max(s.month_start) FROM starts s
            WHERE s.asset_id = r.asset_id AND s.month_start <= r.month_start) AS seg_start
    FROM r
),
win AS (
    SELECT c.asset_id, c.month_block_id, c.month_start, c.x, c.sg, c.seg_start,
           count(p.x)       AS window_months,
           avg(p.x)         AS mu,
           stddev_samp(p.x) AS sd_raw
    FROM seg c
    CROSS JOIN k
    LEFT JOIN r p
           ON p.asset_id = c.asset_id
          AND p.month_start BETWEEN GREATEST(c.seg_start, (c.month_start - make_interval(months => k.win_months))::date)
                                AND (c.month_start - INTERVAL '1 month')::date
    GROUP BY c.asset_id, c.month_block_id, c.month_start, c.x, c.sg, c.seg_start
),
st AS (
    SELECT w.*,
           CASE WHEN w.sg = 0 THEN 0
                ELSE w.sg * (SELECT count(*)
                             FROM r q
                             WHERE q.asset_id = w.asset_id
                               AND q.month_start <= w.month_start
                               AND q.month_start > COALESCE((SELECT max(b.month_start)
                                                             FROM r b
                                                             WHERE b.asset_id = w.asset_id
                                                               AND b.month_start >= w.seg_start
                                                               AND b.month_start < w.month_start
                                                               AND b.sg <> w.sg),
                                                            (w.seg_start - INTERVAL '1 month')::date))
           END::integer AS streak
    FROM win w
),
zr AS (
    SELECT s.*,
           CASE WHEN s.window_months = k.win_months AND s.sd_raw >= k.sd_floor
                THEN (s.x - s.mu) / s.sd_raw
           END AS z_raw
    FROM st s CROSS JOIN k
),
zc AS (
    SELECT z.*,
           CASE WHEN z.z_raw IS NOT NULL
                THEN ROUND(GREATEST(-k.z_cap, LEAST(k.z_cap, z.z_raw)), 8)
           END AS attention_z
    FROM zr z CROSS JOIN k
)
SELECT c.asset_id, c.month_block_id, c.month_start, c.window_months::smallint AS window_months,
       c.sd_raw, c.z_raw, c.attention_z, c.streak::smallint AS streak,
       c.attention_z + CASE WHEN c.streak = 0 THEN 0
                            ELSE k.bonus_step * (LEAST(abs(c.streak), k.streak_cap) - 1)
                       END AS attention_index
FROM zc c CROSS JOIN k;
