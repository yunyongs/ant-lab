"""The only SQL the engine runs: SELECT statements with bound parameters."""

MONTHS_SQL = """
SELECT id, month_start
FROM month_block
WHERE month_start < %(upper)s
ORDER BY month_start, id
"""

POOL_SQL = """
SELECT a.id, a.market, a.ticker, a.currency, a.asset_type
FROM asset_position p
JOIN asset a ON a.id = p.asset_id
WHERE p.base_date = %(base_date)s
ORDER BY a.market, a.ticker, a.id
"""

RETURNS_SQL = """
SELECT r.asset_id, r.month_block_id, r.local_return, r.return_kind
FROM asset_month_return r
JOIN month_block m ON m.id = r.month_block_id
WHERE m.month_start < %(upper)s
  AND r.asset_id IN (SELECT p.asset_id FROM asset_position p WHERE p.base_date = %(base_date)s)
ORDER BY r.asset_id, m.month_start
"""

FX_SQL = """
SELECT f.month_block_id, f.currency, f.fx_change
FROM fx_month f
JOIN month_block m ON m.id = f.month_block_id
WHERE m.month_start < %(upper)s
ORDER BY m.month_start, f.currency
"""

SESSION_SQL = """
SELECT current_user,
       current_setting('transaction_read_only'),
       current_setting('transaction_isolation')
"""

UNSAFE_ROLE_SQL = """
SELECT (SELECT r.rolsuper FROM pg_roles r WHERE r.rolname = current_user)
       OR EXISTS (
           SELECT 1
           FROM unnest(%(tables)s::text[]) AS t(name)
           CROSS JOIN unnest(%(privileges)s::text[]) AS p(name)
           WHERE has_table_privilege(current_user, 'public.' || t.name, p.name)
       )
"""
