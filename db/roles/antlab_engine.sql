-- Read-only role for the simulation engine (M2-03).
-- Run after Flyway has created the schema, as a superuser, in the target database. Idempotent.
-- No password here; set it outside the repository. Plain SQL (no psql meta-commands) so it can also be
-- executed through a driver.
--
-- The target database must be named explicitly, in the same session and before the script runs:
--     SET antlab.expected_database = 'antlab_api';
-- The script refuses to run when it does not equal current_database(). See db/README.md for the command.
--
-- The whole script is ONE statement, so it is atomic: any refusal or error rolls back every change, including
-- a role created by this run. The role is converged to: CONNECT on this database, USAGE on schema public,
-- SELECT on five history tables, no role memberships, no owned objects. Anything that cannot be converged
-- safely (the role owns objects; PUBLIC or a default privilege hands out table/schema rights; an extension or
-- a SECURITY DEFINER function is reachable) is refused with a message instead of being rewritten.

DO $role$
DECLARE
    history_tables constant text[] :=
        ARRAY['asset', 'asset_position', 'month_block', 'asset_month_return', 'fx_month'];
    expected text := current_setting('antlab.expected_database', true);
    role_oid oid;
    problems text[] := '{}';
    r record;
    p text;
BEGIN
    -- 1. Target database and caller.
    IF expected IS NULL OR expected = '' THEN
        RAISE EXCEPTION 'antlab_engine: name the target database first: SET antlab.expected_database = ''<database>''';
    END IF;
    IF expected <> current_database() THEN
        RAISE EXCEPTION 'antlab_engine: connected to database "%" but antlab.expected_database is "%"; nothing was changed',
            current_database(), expected;
    END IF;
    IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION 'antlab_engine: run this script as a superuser (current user: %)', current_user;
    END IF;
    FOREACH p IN ARRAY history_tables LOOP
        IF to_regclass(format('public.%I', p)) IS NULL THEN
            RAISE EXCEPTION 'antlab_engine: table public.% does not exist; run the Flyway migrations first', p;
        END IF;
    END LOOP;

    -- 2. An existing role must not own anything (DROP OWNED below would drop owned objects). pg_shdepend is
    -- cluster-wide, so objects owned in other databases count too.
    SELECT oid INTO role_oid FROM pg_roles WHERE rolname = 'antlab_engine';
    IF role_oid IS NOT NULL THEN
        FOR r IN
            SELECT d.dbid, d.classid::regclass AS catalog, count(*) AS n
            FROM pg_shdepend d
            WHERE d.refclassid = 'pg_authid'::regclass AND d.refobjid = role_oid AND d.deptype = 'o'
            GROUP BY d.dbid, d.classid
        LOOP
            problems := problems || format('antlab_engine owns %s object(s) in %s (database oid %s)', r.n, r.catalog, r.dbid);
        END LOOP;
        IF cardinality(problems) > 0 THEN
            RAISE EXCEPTION 'antlab_engine: refusing to apply, nothing was changed: %. Transfer or drop them first (REASSIGN OWNED BY / DROP OWNED BY).',
                array_to_string(problems, '; ');
        END IF;
    ELSE
        CREATE ROLE antlab_engine LOGIN;
        SELECT oid INTO role_oid FROM pg_roles WHERE rolname = 'antlab_engine';
    END IF;

    -- 3. Attributes and per-role settings, including settings stored per database (they beat the global ones).
    ALTER ROLE antlab_engine WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
        CONNECTION LIMIT 5;
    FOR r IN
        SELECT db.datname
        FROM pg_db_role_setting s JOIN pg_database db ON db.oid = s.setdatabase
        WHERE s.setrole = role_oid AND s.setdatabase <> 0
    LOOP
        EXECUTE format('ALTER ROLE antlab_engine IN DATABASE %I RESET ALL', r.datname);
    END LOOP;
    ALTER ROLE antlab_engine RESET ALL;
    ALTER ROLE antlab_engine SET default_transaction_read_only = on;
    ALTER ROLE antlab_engine SET statement_timeout = '30s';
    ALTER ROLE antlab_engine SET search_path = public;

    -- 4. Role memberships: a member of nothing (no pg_read_all_data, pg_write_all_data, pg_read_server_files,
    -- pg_write_server_files, pg_execute_server_program, ...).
    FOR r IN
        SELECT roleid::regrole AS parent, grantor::regrole AS grantor
        FROM pg_auth_members
        WHERE member = role_oid
    LOOP
        EXECUTE format('REVOKE %s FROM antlab_engine GRANTED BY %s', r.parent, r.grantor);
    END LOOP;

    -- 5. Direct privileges: drop every privilege granted to the role in this database and on shared objects
    -- (tables, columns, sequences, functions, schemas, types, languages, tablespaces, parameters, ...). The
    -- role owns nothing here (step 2), so this only revokes. Then grant the allowed set below.
    DROP OWNED BY antlab_engine;

    -- 6. Large-object and WAL-message functions are executable by PUBLIC and need no table privilege, so a
    -- session that switches default_transaction_read_only off could still commit data with them. Revoke from
    -- PUBLIC, database-wide. The application uses no large objects; superusers are unaffected.
    FOR r IN
        SELECT pr.oid::regprocedure AS signature
        FROM pg_proc pr
        WHERE pr.pronamespace = 'pg_catalog'::regnamespace
          AND (pr.proname LIKE 'lo\_%' OR pr.proname IN ('loread', 'lowrite', 'pg_logical_emit_message'))
    LOOP
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC, antlab_engine', r.signature);
    END LOOP;

    -- 7. Allowed grants: connect, look up schema public, SELECT on the history tables. No temporary tables
    -- for anyone but superusers.
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO antlab_engine', current_database());
    EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC', current_database());
    GRANT USAGE ON SCHEMA public TO antlab_engine;
    GRANT SELECT ON asset, asset_position, month_block, asset_month_return, fx_month TO antlab_engine;

    -- 8. Verify the effective result (direct, PUBLIC and inherited grants together). Anything that cannot be
    -- converged safely is reported and the whole run is rolled back.
    FOR r IN
        SELECT d.defaclrole::regrole AS creator, d.defaclobjtype AS objtype, x.grantee
        FROM pg_default_acl d CROSS JOIN LATERAL aclexplode(d.defaclacl) x
        WHERE x.grantee = role_oid OR (x.grantee = 0 AND d.defaclobjtype IN ('r', 'S', 'n'))
    LOOP
        problems := problems || format('default privileges of %s grant on object type "%s" to %s (new objects would be exposed)',
            r.creator, r.objtype, CASE WHEN r.grantee = 0 THEN 'PUBLIC' ELSE 'antlab_engine' END);
    END LOOP;

    FOR r IN SELECT extname FROM pg_extension WHERE extname <> 'plpgsql' LOOP
        problems := problems || format('extension "%s" is installed (its functions may be executable by PUBLIC)', r.extname);
    END LOOP;

    FOR r IN
        SELECT pr.oid::regprocedure AS signature
        FROM pg_proc pr
        WHERE pr.prosecdef AND has_function_privilege('antlab_engine', pr.oid, 'EXECUTE')
    LOOP
        problems := problems || format('SECURITY DEFINER function %s is executable by antlab_engine', r.signature);
    END LOOP;

    FOR r IN
        SELECT pr.oid::regprocedure AS signature
        FROM pg_proc pr
        WHERE pr.pronamespace = 'pg_catalog'::regnamespace
          AND pr.proname IN ('pg_read_file', 'pg_read_binary_file', 'pg_ls_dir', 'pg_stat_file', 'pg_reload_conf')
          AND has_function_privilege('antlab_engine', pr.oid, 'EXECUTE')
    LOOP
        problems := problems || format('server-file function %s is executable by antlab_engine', r.signature);
    END LOOP;

    IF has_database_privilege('antlab_engine', current_database(), 'CREATE')
       OR has_database_privilege('antlab_engine', current_database(), 'TEMPORARY') THEN
        problems := array_append(problems, 'antlab_engine can create schemas or temporary tables in this database');
    END IF;

    FOR r IN SELECT spcname FROM pg_tablespace WHERE has_tablespace_privilege('antlab_engine', oid, 'CREATE') LOOP
        problems := problems || format('antlab_engine can create objects in tablespace "%s"', r.spcname);
    END LOOP;
    FOR r IN SELECT fdwname FROM pg_foreign_data_wrapper WHERE has_foreign_data_wrapper_privilege('antlab_engine', oid, 'USAGE') LOOP
        problems := problems || format('antlab_engine can use foreign data wrapper "%s"', r.fdwname);
    END LOOP;
    FOR r IN SELECT srvname FROM pg_foreign_server WHERE has_server_privilege('antlab_engine', oid, 'USAGE') LOOP
        problems := problems || format('antlab_engine can use foreign server "%s"', r.srvname);
    END LOOP;
    FOR r IN SELECT lanname FROM pg_language WHERE NOT lanpltrusted AND lanacl IS NOT NULL AND has_language_privilege('antlab_engine', oid, 'USAGE') LOOP
        problems := problems || format('antlab_engine can use untrusted language "%s"', r.lanname);
    END LOOP;
    FOR r IN
        SELECT parname FROM pg_parameter_acl
        WHERE has_parameter_privilege('antlab_engine', parname, 'SET')
           OR has_parameter_privilege('antlab_engine', parname, 'ALTER SYSTEM')
    LOOP
        problems := problems || format('antlab_engine may change server parameter "%s"', r.parname);
    END LOOP;

    FOR r IN
        SELECT n.nspname FROM pg_namespace n
        WHERE n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema'
          AND has_schema_privilege('antlab_engine', n.oid, 'CREATE')
    LOOP
        problems := problems || format('antlab_engine can create objects in schema "%s" (check GRANT CREATE ... TO PUBLIC)', r.nspname);
    END LOOP;

    FOR r IN
        SELECT c.oid, c.relkind, n.nspname, c.relname,
               (n.nspname = 'public' AND c.relname = ANY (history_tables) AND c.relkind IN ('r', 'p')) AS allowed
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
          AND n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema'
    LOOP
        IF r.relkind = 'S' THEN
            FOREACH p IN ARRAY ARRAY['USAGE', 'SELECT', 'UPDATE'] LOOP
                IF has_sequence_privilege('antlab_engine', r.oid, p) THEN
                    problems := problems || format('antlab_engine has %s on sequence %I.%I', p, r.nspname, r.relname);
                END IF;
            END LOOP;
        ELSE
            FOREACH p IN ARRAY ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER', 'MAINTAIN'] LOOP
                IF (has_table_privilege('antlab_engine', r.oid, p)
                    OR (p IN ('SELECT', 'INSERT', 'UPDATE', 'REFERENCES')
                        AND has_any_column_privilege('antlab_engine', r.oid, p)))
                   AND NOT (r.allowed AND p = 'SELECT') THEN
                    problems := problems || format('antlab_engine has %s on %I.%I (table, view or column grant, possibly from PUBLIC)', p, r.nspname, r.relname);
                END IF;
            END LOOP;
        END IF;
    END LOOP;

    IF cardinality(problems) > 0 THEN
        RAISE EXCEPTION E'antlab_engine: refusing to apply, everything was rolled back:\n  - %\nRemove these grants or objects (or run the script on a clean database) and run it again.',
            array_to_string(problems, E'\n  - ');
    END IF;
END
$role$;
