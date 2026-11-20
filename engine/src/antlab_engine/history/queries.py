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
SELECT COALESCE(
           (SELECT r.rolsuper OR r.rolcreaterole OR r.rolcreatedb OR r.rolbypassrls
            FROM pg_roles r WHERE r.rolname = current_user),
           TRUE)
       OR EXISTS (
           SELECT 1
           FROM pg_class c
           JOIN pg_namespace n ON n.oid = c.relnamespace
           CROSS JOIN unnest(%(privileges)s::text[]) AS p(name)
           WHERE c.relkind IN ('r', 'p')
             AND n.nspname NOT IN ('pg_catalog', 'information_schema')
             AND n.nspname !~ '^pg_'
             AND has_table_privilege(current_user, c.oid, p.name)
       )
"""

# Macro series (regime source and coverage report). A row that cannot be known on or before
# :upper is left out; the adapter dates the rest.
REGIME_OBSERVATIONS_SQL = """
SELECT s.code, o.ref_date, o.value, o.published_on
FROM (
    SELECT series_id, ref_date, value,
           COALESCE(realtime_start, available_on,
                    CASE WHEN vintage_no >= 2 THEN (observed_at AT TIME ZONE 'UTC')::date END)
               AS published_on,
           vintage_no
    FROM series_observation
    WHERE ref_date <= %(upper)s
) o
JOIN series s ON s.id = o.series_id
WHERE s.code = ANY(%(codes)s::text[])
  AND (o.published_on IS NULL OR o.published_on <= %(upper)s)
  AND (o.vintage_no = 1 OR s.code = ANY(%(revisable)s::text[]))
ORDER BY s.code, o.ref_date, o.vintage_no
"""

REGIME_COVERAGE_SQL = """
WITH dates AS (
    SELECT s.code, s.frequency, o.ref_date,
           o.ref_date - lag(o.ref_date) OVER (PARTITION BY s.id ORDER BY o.ref_date) AS step
    FROM series s
    JOIN (SELECT DISTINCT series_id, ref_date FROM series_observation) o ON o.series_id = s.id
    WHERE s.code = ANY(%(codes)s::text[])
),
span AS (
    SELECT code,
           count(*) AS reference_dates,
           min(ref_date) AS first_date,
           max(ref_date) AS last_date,
           count(*) FILTER (
               WHERE step > CASE frequency WHEN 'DAILY' THEN %(daily)s
                                           WHEN 'WEEKLY' THEN %(weekly)s
                                           ELSE %(monthly)s END) AS gaps,
           max(step) AS longest_step
    FROM dates
    GROUP BY code
),
vint AS (
    SELECT s.code,
           count(*) AS observations,
           max(o.vintage_no) AS max_vintage,
           count(*) FILTER (WHERE o.vintage_no >= 2) AS later_vintages,
           count(*) FILTER (WHERE o.vintage_no = 1 AND o.realtime_start IS NULL
                            AND o.available_on IS NULL) AS rule_dated
    FROM series s
    JOIN series_observation o ON o.series_id = s.id
    WHERE s.code = ANY(%(codes)s::text[])
    GROUP BY s.code
)
SELECT s.code, s.frequency, s.revisable,
       coalesce(v.observations, 0), coalesce(p.reference_dates, 0),
       p.first_date, p.last_date, coalesce(p.gaps, 0), p.longest_step,
       coalesce(v.max_vintage, 0), coalesce(v.later_vintages, 0), coalesce(v.rule_dated, 0)
FROM series s
LEFT JOIN span p ON p.code = s.code
LEFT JOIN vint v ON v.code = s.code
WHERE s.code = ANY(%(codes)s::text[])
ORDER BY s.code
"""
