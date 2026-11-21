-- Table rights of the API role antlab_app (MVP-20). Flyway runs this after every migrate, as the Flyway user (the table
-- owner), so it needs no superuser and converges the rights on every start.
-- It does nothing when the role antlab_app does not exist (tests and tools that migrate as a superuser, the engine test
-- harness). A table in public that is not listed below stops the start: a new table is added to one class in the same
-- change that creates it. The roles themselves come from db/roles/antlab_owner_app.sql.
--   READ    SELECT                  history and pool
--   APPEND  SELECT, INSERT          ledger and every other append-only table: never UPDATE, DELETE or TRUNCATE
--   MUTABLE SELECT, INSERT, UPDATE  state rows the services change (SELECT ... FOR UPDATE needs UPDATE)
-- One exception to the class of a table: month_block stays READ and gets a column-level UPDATE on its regime columns
-- (M2-08, the regime label job fills them from NULL once). Nothing else of the table can be updated and nobody can
-- INSERT or DELETE there.
-- A second exception (AG-04 C2): league is a state row, but its seed, dates and money settings never change, so it is
-- classed APPEND (SELECT, INSERT) and gets a column-level UPDATE on the columns the close changes only. SELECT ... FOR
-- UPDATE (the league row lock of every entry and close) needs UPDATE on at least one column, which this gives.
-- Nobody but the owner gets DELETE, TRUNCATE, REFERENCES, TRIGGER or MAINTAIN. Sequences get no grant: identity columns
-- need none.

DO $grants$
DECLARE
    read_tables constant text[] := ARRAY['asset_group', 'asset', 'asset_position', 'month_block', 'asset_month_return',
        'fx_month', 'month_event', 'asset_month_attention'];
    append_tables constant text[] := ARRAY['season_path', 'turn', 'turn_price', 'formation_change', 'turn_feed',
        'reconciliation_log', 'orders', 'execution', 'cash_ledger', 'asset_daily_price', 'season_agent_config',
        'persona_version', 'league', 'league_entry', 'agent_call', 'agent_reflection', 'agent_memory', 'season_score', 'rating_history', 'projection', 'series', 'series_observation'];
    mutable_tables constant text[] := ARRAY['member', 'season', 'cash_account', 'holding', 'lineup', 'refresh_token',
        'agent_profile', 'job_run'];
    regime_table constant text := 'month_block';
    regime_columns constant text[] := ARRAY['rate_dir', 'growth_dir', 'fx_dir', 'sentiment_dir', 'rate_strength',
        'growth_strength', 'fx_strength', 'sentiment_strength', 'prob_p1', 'prob_p2', 'prob_p3', 'prob_p4', 'regime_label',
        'indicator_z'];
    league_table constant text := 'league';
    league_columns constant text[] := ARRAY['status', 'closed_at', 'version', 'updated_at'];
    privileges constant text[] := ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER',
        'MAINTAIN'];
    col text;
    column_rights boolean;
    unclassified text[];
    foreign_owned text[];
    routines text[];
    t text;
    p text;
    expected text[];
    problems text[] := '{}';
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'antlab_app') THEN
        RETURN;
    END IF;

    -- A migration run by a superuser (for example a local bootRun on the same database) leaves tables the owner role
    -- does not own; the grants below would then fail with a bare "permission denied".
    IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        SELECT coalesce(array_agg(c.relname::text ORDER BY c.relname), '{}') INTO foreign_owned
        FROM pg_class c
        WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
          AND c.relowner <> (SELECT oid FROM pg_roles WHERE rolname = current_user);
        IF cardinality(foreign_owned) > 0 THEN
            RAISE EXCEPTION 'afterMigrate: relation(s) in public not owned by %: %. Run db/roles/antlab_owner_app.sql again as a superuser',
                current_user, array_to_string(foreign_owned, ', ');
        END IF;
    END IF;

    -- Routines are outside the privilege classes (PUBLIC can EXECUTE by default, and a SECURITY DEFINER routine would
    -- run with the owner's rights), so none may exist until this file decides their rights.
    SELECT coalesce(array_agg(pr.oid::regprocedure::text ORDER BY pr.oid::regprocedure::text), '{}') INTO routines
    FROM pg_proc pr
    WHERE pr.pronamespace = 'public'::regnamespace;
    IF cardinality(routines) > 0 THEN
        RAISE EXCEPTION 'afterMigrate: routine(s) in public: %. Decide the rights of antlab_app on them in db/callback/afterMigrate.sql',
            array_to_string(routines, ', ');
    END IF;

    SELECT coalesce(array_agg(c.relname::text ORDER BY c.relname), '{}') INTO unclassified
    FROM pg_class c
    WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p')
      AND c.relname <> 'flyway_schema_history'
      AND c.relname::text <> ALL (read_tables || append_tables || mutable_tables);
    IF cardinality(unclassified) > 0 THEN
        RAISE EXCEPTION 'afterMigrate: table(s) not classified for antlab_app: %. Add each to one class in db/callback/afterMigrate.sql',
            array_to_string(unclassified, ', ');
    END IF;

    REVOKE ALL ON ALL TABLES IN SCHEMA public FROM antlab_app, PUBLIC;
    REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM antlab_app, PUBLIC;
    FOREACH t IN ARRAY read_tables LOOP
        EXECUTE format('GRANT SELECT ON TABLE public.%I TO antlab_app', t);
    END LOOP;
    FOREACH t IN ARRAY append_tables LOOP
        EXECUTE format('GRANT SELECT, INSERT ON TABLE public.%I TO antlab_app', t);
    END LOOP;
    FOREACH t IN ARRAY mutable_tables LOOP
        EXECUTE format('GRANT SELECT, INSERT, UPDATE ON TABLE public.%I TO antlab_app', t);
    END LOOP;
    EXECUTE format('GRANT UPDATE (%s) ON TABLE public.%I TO antlab_app',
        (SELECT string_agg(quote_ident(c), ', ') FROM unnest(regime_columns) AS c), regime_table);
    EXECUTE format('GRANT UPDATE (%s) ON TABLE public.%I TO antlab_app',
        (SELECT string_agg(quote_ident(c), ', ') FROM unnest(league_columns) AS c), league_table);

    -- Verify the effective result for every table, whatever the source of a right.
    FOR t IN
        SELECT c.relname::text FROM pg_class c
        WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'f') ORDER BY c.relname
    LOOP
        expected := CASE
            WHEN t = ANY (read_tables) THEN ARRAY['SELECT']
            WHEN t = ANY (append_tables) THEN ARRAY['SELECT', 'INSERT']
            WHEN t = ANY (mutable_tables) THEN ARRAY['SELECT', 'INSERT', 'UPDATE']
            ELSE ARRAY[]::text[] END;
        FOREACH p IN ARRAY privileges LOOP
            -- table level: the regime table has no table-level UPDATE either
            IF has_table_privilege('antlab_app', format('public.%I', t)::regclass, p) <> (p = ANY (expected)) THEN
                problems := problems || format('%s on %s should be %s', p, t, CASE WHEN p = ANY (expected) THEN 'granted' ELSE 'denied' END);
            END IF;
            -- column level: any column holding the right counts, so the one allowed exception is named here
            column_rights := p = ANY (expected) OR (t IN (regime_table, league_table) AND p = 'UPDATE');
            IF p IN ('SELECT', 'INSERT', 'UPDATE', 'REFERENCES')
               AND has_any_column_privilege('antlab_app', format('public.%I', t)::regclass, p) <> column_rights THEN
                problems := problems || format('%s on a column of %s should be %s', p, t, CASE WHEN column_rights THEN 'granted' ELSE 'denied' END);
            END IF;
        END LOOP;
    END LOOP;
    -- exactly the listed columns can be updated, and no other column carries any right of its own
    FOREACH t IN ARRAY ARRAY[regime_table, league_table] LOOP
        FOR col IN
            SELECT a.attname::text FROM pg_attribute a
            WHERE a.attrelid = format('public.%I', t)::regclass AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
        LOOP
            IF has_column_privilege('antlab_app', format('public.%I', t)::regclass, col, 'UPDATE')
               <> (col = ANY (CASE WHEN t = regime_table THEN regime_columns ELSE league_columns END)) THEN
                problems := problems || format('UPDATE on %s.%s should be %s', t, col,
                    CASE WHEN col = ANY (CASE WHEN t = regime_table THEN regime_columns ELSE league_columns END)
                         THEN 'granted' ELSE 'denied' END);
            END IF;
        END LOOP;
    END LOOP;
    IF cardinality(problems) > 0 THEN
        RAISE EXCEPTION 'afterMigrate: antlab_app rights differ from the classes: %', array_to_string(problems, '; ');
    END IF;
END
$grants$;
