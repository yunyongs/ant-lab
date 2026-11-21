-- Compares attn_calc (db/13_calc_attention_def.sql) with the reference copy of the original definition, every column.
\ir ../db/13_calc_attention_def.sql
\ir attn_calc_reference.sql
CREATE TEMP TABLE new_rows AS SELECT * FROM attn_calc;
CREATE TEMP TABLE ref_rows AS SELECT * FROM attn_calc_ref;
SELECT 'EQUIV'
    || ' new=' || (SELECT count(*) FROM new_rows)
    || ' ref=' || (SELECT count(*) FROM ref_rows)
    || ' new_minus_ref=' || (SELECT count(*) FROM (SELECT * FROM new_rows EXCEPT SELECT * FROM ref_rows) x)
    || ' ref_minus_new=' || (SELECT count(*) FROM (SELECT * FROM ref_rows EXCEPT SELECT * FROM new_rows) x)
    || ' text_diff=' || (SELECT count(*) FROM new_rows n JOIN ref_rows r USING (asset_id, month_block_id) WHERE n::text IS DISTINCT FROM r::text)
    || ' z_rows=' || (SELECT count(*) FROM new_rows WHERE attention_z IS NOT NULL)
    || ' clipped=' || (SELECT count(*) FROM new_rows WHERE abs(attention_z) = 3)
    || ' seg_starts=' || (SELECT count(*) FROM new_rows WHERE window_months = 0)
    || ' max_streak=' || (SELECT max(streak) FROM new_rows)
    || ' min_streak=' || (SELECT min(streak) FROM new_rows)
    || ' zero_streak=' || (SELECT count(*) FROM new_rows WHERE streak = 0);
