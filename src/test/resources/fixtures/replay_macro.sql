-- Fake data, not a real sample. Gives every fake replay month of replay_months.sql distinct regime values, so a read of
-- the wrong month or the wrong column shows. Pair with cleanup_macro.sql, which empties them again.
WITH numbered AS (
    SELECT id, row_number() OVER (ORDER BY month_start) AS rn
    FROM month_block
    WHERE month_start BETWEEN DATE '2006-12-01' AND DATE '2026-01-01'
      AND event_summary IS NULL AND events_reviewed = TRUE AND regime_label IS NULL
), vals AS (
    SELECT id, rn,
           0.0500 + (rn % 10) * 0.0100 AS p1,
           0.2000 + (rn % 7) * 0.0100 AS p2,
           0.2000 + (rn % 5) * 0.0100 AS p3
    FROM numbered
)
UPDATE month_block mb SET
    rate_dir = (ARRAY['UP','DOWN','NEUTRAL'])[1 + v.rn % 3],
    growth_dir = (ARRAY['DOWN','NEUTRAL','UP'])[1 + v.rn % 3],
    fx_dir = (ARRAY['NEUTRAL','UP','DOWN'])[1 + v.rn % 3],
    sentiment_dir = (ARRAY['UP','UP','DOWN'])[1 + v.rn % 3],
    rate_strength = round(0.10 + v.rn * 0.011, 8),
    growth_strength = round(0.20 + v.rn * 0.013, 8),
    fx_strength = round(0.30 + v.rn * 0.017, 8),
    sentiment_strength = round(0.40 + v.rn * 0.019, 8),
    prob_p1 = v.p1, prob_p2 = v.p2, prob_p3 = v.p3,
    prob_p4 = 1 - v.p1 - v.p2 - v.p3,
    regime_label = 'P' || (1 + v.rn % 4),
    indicator_z = jsonb_build_object('1', (v.rn * 0.5)::text::numeric)
FROM vals v WHERE mb.id = v.id;
