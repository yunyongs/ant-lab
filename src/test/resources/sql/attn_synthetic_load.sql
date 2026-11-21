-- Synthetic, not market data. Loads :n_assets assets x :n_months months of random returns (with gaps, zero returns and streaks)
-- into a database that has the db/01 schema. Usage: psql -v n_assets=180 -v n_months=360 -f attn_synthetic_load.sql
SELECT setseed(0.2026);
INSERT INTO asset_group (code, name, kind) VALUES ('SYN_GROUP', 'synthetic group', 'SECTOR');
INSERT INTO asset (market, ticker, name, asset_type, currency, asset_group_id, listed_on, return_basis)
SELECT 'US', 'S' || lpad(i::text, 5, '0'), 'synthetic', 'STOCK', 'USD', g.id, DATE '1990-01-01', 'PRICE'
FROM generate_series(1, :n_assets) i CROSS JOIN asset_group g WHERE g.code = 'SYN_GROUP';
INSERT INTO month_block (month_start)
SELECT (DATE '1996-01-01' + make_interval(months => m))::date FROM generate_series(0, :n_months - 1) m;
INSERT INTO asset_month_return (asset_id, month_block_id, local_return)
SELECT a.id, b.id,
       CASE WHEN (a.id * 5 + b.id) % 23 = 0 THEN 0
            WHEN (a.id + b.id / 9) % 4 = 0 THEN round((random() * 6000000 + 1000000)::numeric / 100000000, 8)
            ELSE round((random() * 24000000 - 12000000)::numeric / 100000000, 8) END
FROM asset a CROSS JOIN month_block b
WHERE a.ticker LIKE 'S_____' AND (a.id * 7 + b.id) % 97 <> 0;
ANALYZE;
