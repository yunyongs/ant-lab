-- Owner and application roles for the API database (MVP-20).
-- antlab_owner owns schema public and every relation in it and runs Flyway. antlab_app is the API's login: no DDL, no
-- ownership, no temporary tables. Its per-table rights are applied by the Flyway callback db/callback/afterMigrate.sql
-- after every migrate (src/main/resources/db/callback).
-- Run as a superuser in the target database BEFORE the app starts for the first time on that database (a fresh compose
-- volume does it through /docker-entrypoint-initdb.d), and once on an existing database to move ownership. Idempotent.
-- No password here; set it outside the repository. Plain SQL (no psql meta-commands) so it can also run through a driver.
--
-- The target database must be named explicitly, in the same session and before the script runs:
--     SET antlab.expected_database = 'antlab_api';
-- The practice database "antlab" is refused. See db/README.md for the command.
--
-- The whole script is ONE statement, so it is atomic: any refusal or error rolls back every change, including a role
-- created by this run. Anything that cannot be converged safely (antlab_app owns objects; public holds functions, types
-- or other non-relation objects the owner role does not own; PUBLIC hands out table rights) is refused with a message
-- instead of being rewritten. Rights of antlab_app on tables are not touched here: the callback converges them.

DO $roles$
DECLARE
    expected text := current_setting('antlab.expected_database', true);
    owner_oid oid;
    app_oid oid;
    problems text[] := '{}';
    r record;
    p text;
BEGIN
    -- 1. Target database and caller.
    IF expected IS NULL OR expected = '' THEN
        RAISE EXCEPTION 'antlab_owner_app: name the target database first: SET antlab.expected_database = ''<database>''';
    END IF;
    IF expected <> current_database() THEN
        RAISE EXCEPTION 'antlab_owner_app: connected to database "%" but antlab.expected_database is "%"; nothing was changed',
            current_database(), expected;
    END IF;
    IF current_database() = 'antlab' THEN
        RAISE EXCEPTION 'antlab_owner_app: refusing the practice database "antlab"; it stays superuser-only (db/README.md)';
    END IF;
    IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION 'antlab_owner_app: run this script as a superuser (current user: %)', current_user;
    END IF;

    -- 2. The app role must not own anything anywhere (pg_shdepend is cluster-wide): ownership is never moved to or
    -- from it silently, and the owner role must not be confused with it.
    SELECT oid INTO app_oid FROM pg_roles WHERE rolname = 'antlab_app';
    IF app_oid IS NOT NULL THEN
        FOR r IN
            SELECT d.dbid, d.classid::regclass AS catalog, count(*) AS n
            FROM pg_shdepend d
            WHERE d.refclassid = 'pg_authid'::regclass AND d.refobjid = app_oid AND d.deptype = 'o'
            GROUP BY d.dbid, d.classid
        LOOP
            problems := problems || format('antlab_app owns %s object(s) in %s (database oid %s)', r.n, r.catalog, r.dbid);
        END LOOP;
    END IF;

    -- 3. Objects in public that cannot be handed to the owner role safely (relations are moved in step 6).
    FOR r IN
        SELECT 'function ' || pr.oid::regprocedure::text AS what
        FROM pg_proc pr
        WHERE pr.pronamespace = 'public'::regnamespace
          AND pr.proowner <> coalesce((SELECT oid FROM pg_roles WHERE rolname = 'antlab_owner'), 0)
        UNION ALL
        SELECT 'type ' || t.oid::regtype::text
        FROM pg_type t
        WHERE t.typnamespace = 'public'::regnamespace
          AND t.typrelid = 0 AND NOT (t.typcategory = 'A' AND t.typelem <> 0)
          AND t.typowner <> coalesce((SELECT oid FROM pg_roles WHERE rolname = 'antlab_owner'), 0)
        UNION ALL
        SELECT 'type ' || t.oid::regtype::text
        FROM pg_type t JOIN pg_class c ON c.oid = t.typrelid
        WHERE t.typnamespace = 'public'::regnamespace AND c.relkind = 'c'
          AND t.typowner <> coalesce((SELECT oid FROM pg_roles WHERE rolname = 'antlab_owner'), 0)
        UNION ALL
        SELECT 'operator ' || o.oid::regoperator::text
        FROM pg_operator o
        WHERE o.oprnamespace = 'public'::regnamespace
          AND o.oprowner <> coalesce((SELECT oid FROM pg_roles WHERE rolname = 'antlab_owner'), 0)
        UNION ALL
        SELECT 'collation ' || c.collname
        FROM pg_collation c
        WHERE c.collnamespace = 'public'::regnamespace
          AND c.collowner <> coalesce((SELECT oid FROM pg_roles WHERE rolname = 'antlab_owner'), 0)
        UNION ALL
        SELECT 'conversion ' || c.conname
        FROM pg_conversion c
        WHERE c.connamespace = 'public'::regnamespace
          AND c.conowner <> coalesce((SELECT oid FROM pg_roles WHERE rolname = 'antlab_owner'), 0)
        UNION ALL
        SELECT 'statistics object ' || s.stxname
        FROM pg_statistic_ext s
        WHERE s.stxnamespace = 'public'::regnamespace
          AND s.stxowner <> coalesce((SELECT oid FROM pg_roles WHERE rolname = 'antlab_owner'), 0)
        UNION ALL
        SELECT 'text search configuration ' || c.cfgname
        FROM pg_ts_config c
        WHERE c.cfgnamespace = 'public'::regnamespace
          AND c.cfgowner <> coalesce((SELECT oid FROM pg_roles WHERE rolname = 'antlab_owner'), 0)
        UNION ALL
        SELECT 'text search dictionary ' || d.dictname
        FROM pg_ts_dict d
        WHERE d.dictnamespace = 'public'::regnamespace
          AND d.dictowner <> coalesce((SELECT oid FROM pg_roles WHERE rolname = 'antlab_owner'), 0)
    LOOP
        problems := problems || format('public holds %s, which is not owned by antlab_owner and is not moved by this script', r.what);
    END LOOP;
    FOR r IN SELECT extname FROM pg_extension WHERE extname <> 'plpgsql' LOOP
        problems := problems || format('extension "%s" is installed (its objects cannot be re-owned one by one)', r.extname);
    END LOOP;
    IF cardinality(problems) > 0 THEN
        RAISE EXCEPTION E'antlab_owner_app: refusing to apply, nothing was changed:\n  - %\nTransfer or drop them first (REASSIGN OWNED BY / DROP OWNED BY / ALTER ... OWNER TO).',
            array_to_string(problems, E'\n  - ');
    END IF;

    -- 4. Create or converge both roles: attributes, per-role settings (also those stored per database, which beat the
    -- global ones) and no memberships in either direction.
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'antlab_owner') THEN
        CREATE ROLE antlab_owner LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'antlab_app') THEN
        CREATE ROLE antlab_app LOGIN;
    END IF;
    SELECT oid INTO owner_oid FROM pg_roles WHERE rolname = 'antlab_owner';
    SELECT oid INTO app_oid FROM pg_roles WHERE rolname = 'antlab_app';

    ALTER ROLE antlab_owner WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 5;
    ALTER ROLE antlab_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 20;
    FOR r IN
        SELECT db.datname, s.setrole
        FROM pg_db_role_setting s JOIN pg_database db ON db.oid = s.setdatabase
        WHERE s.setrole IN (owner_oid, app_oid) AND s.setdatabase <> 0
    LOOP
        EXECUTE format('ALTER ROLE %I IN DATABASE %I RESET ALL', (SELECT rolname FROM pg_roles WHERE oid = r.setrole), r.datname);
    END LOOP;
    ALTER ROLE antlab_owner RESET ALL;
    ALTER ROLE antlab_app RESET ALL;
    ALTER ROLE antlab_owner SET search_path = public;
    ALTER ROLE antlab_app SET search_path = public;

    FOR r IN
        SELECT m.roleid::regrole AS parent, m.member::regrole AS member, m.grantor::regrole AS grantor
        FROM pg_auth_members m
        WHERE m.member IN (owner_oid, app_oid)
    LOOP
        EXECUTE format('REVOKE %s FROM %s GRANTED BY %s', r.parent, r.member, r.grantor);
    END LOOP;
    FOR r IN
        SELECT m.roleid::regrole AS parent, m.member::regrole AS member
        FROM pg_auth_members m
        WHERE m.roleid IN (owner_oid, app_oid)
    LOOP
        problems := problems || format('role %s is a member of %s (it would inherit its rights)', r.member, r.parent);
    END LOOP;

    -- 5. Direct rights on the database and on schema public: converged to connect/usage only. Nothing else is revoked
    -- from antlab_app here (table rights belong to the callback).
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM antlab_owner, antlab_app', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO antlab_owner, antlab_app', current_database());
    EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC', current_database());
    REVOKE ALL ON SCHEMA public FROM antlab_app;

    -- 6. Ownership: the schema, then every relation in it. Tables carry their indexes and identity sequences along;
    -- other sequences are moved one by one.
    ALTER SCHEMA public OWNER TO antlab_owner;
    REVOKE CREATE ON SCHEMA public FROM PUBLIC;
    GRANT USAGE ON SCHEMA public TO antlab_app;
    FOR r IN
        SELECT c.relname, c.relkind
        FROM pg_class c
        WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'f') AND c.relowner <> owner_oid
        ORDER BY c.relname
    LOOP
        EXECUTE format('ALTER %s public.%I OWNER TO antlab_owner',
            CASE r.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' WHEN 'f' THEN 'FOREIGN TABLE' ELSE 'TABLE' END,
            r.relname);
    END LOOP;
    FOR r IN
        SELECT c.relname
        FROM pg_class c
        WHERE c.relnamespace = 'public'::regnamespace AND c.relkind = 'S' AND c.relowner <> owner_oid
        ORDER BY c.relname
    LOOP
        EXECUTE format('ALTER SEQUENCE public.%I OWNER TO antlab_owner', r.relname);
    END LOOP;

    -- 7. Verify the effective result (direct, PUBLIC and inherited rights together). Anything that cannot be
    -- converged safely is reported and the whole run is rolled back.
    FOR r IN
        SELECT c.relname, c.relkind, c.relowner
        FROM pg_class c
        WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S', 'c')
          AND c.relowner <> owner_oid
    LOOP
        problems := problems || format('public.%s is not owned by antlab_owner', r.relname);
    END LOOP;
    IF (SELECT nspowner FROM pg_namespace WHERE nspname = 'public') <> owner_oid THEN
        problems := problems || 'schema public is not owned by antlab_owner';
    END IF;

    FOR r IN
        SELECT c.relname, c.relkind
        FROM pg_class c
        WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
          AND EXISTS (SELECT 1 FROM aclexplode(coalesce(c.relacl, acldefault((CASE WHEN c.relkind = 'S' THEN 's' ELSE 'r' END)::"char", c.relowner))) x
                      WHERE x.grantee = 0)
    LOOP
        problems := problems || format('PUBLIC holds rights on public.%s', r.relname);
    END LOOP;
    FOR r IN
        SELECT c.relname
        FROM pg_class c
        WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
          AND EXISTS (SELECT 1 FROM pg_attribute a, aclexplode(a.attacl) x
                      WHERE a.attrelid = c.oid AND a.attacl IS NOT NULL AND x.grantee IN (0, owner_oid, app_oid) AND x.grantee <> c.relowner
                        -- the one column-level right the callback grants: the regime label job fills these columns of month_block
                        AND NOT (c.relname = 'month_block' AND x.grantee = app_oid AND x.privilege_type = 'UPDATE'
                                 AND a.attname::text = ANY (ARRAY['rate_dir', 'growth_dir', 'fx_dir', 'sentiment_dir', 'rate_strength',
                                     'growth_strength', 'fx_strength', 'sentiment_strength', 'prob_p1', 'prob_p2', 'prob_p3', 'prob_p4',
                                     'regime_label', 'indicator_z'])))
    LOOP
        problems := problems || format('column-level rights are granted on public.%s', r.relname);
    END LOOP;

    FOR r IN
        SELECT d.defaclrole::regrole AS creator, d.defaclobjtype AS objtype, x.grantee
        FROM pg_default_acl d CROSS JOIN LATERAL aclexplode(d.defaclacl) x
        WHERE x.grantee IN (0, owner_oid, app_oid) AND d.defaclobjtype IN ('r', 'S', 'n')
    LOOP
        problems := problems || format('default privileges of %s grant on object type "%s" to %s (new objects would be exposed)',
            r.creator, r.objtype,
            CASE WHEN r.grantee = 0 THEN 'PUBLIC' ELSE (SELECT rolname FROM pg_roles WHERE oid = r.grantee) END);
    END LOOP;

    FOREACH p IN ARRAY ARRAY['CREATE', 'TEMPORARY'] LOOP
        IF has_database_privilege('antlab_app', current_database(), p) THEN
            problems := problems || format('antlab_app has %s on this database', p);
        END IF;
        IF has_database_privilege('antlab_owner', current_database(), p) THEN
            problems := problems || format('antlab_owner has %s on this database', p);
        END IF;
    END LOOP;
    FOR r IN
        SELECT n.nspname FROM pg_namespace n
        WHERE n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema'
          AND has_schema_privilege('antlab_app', n.oid, 'CREATE')
    LOOP
        problems := problems || format('antlab_app can create objects in schema "%s"', r.nspname);
    END LOOP;
    FOR r IN
        SELECT n.nspname FROM pg_namespace n
        WHERE n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema' AND n.nspname <> 'public'
          AND has_schema_privilege('antlab_owner', n.oid, 'CREATE')
    LOOP
        problems := problems || format('antlab_owner can create objects in schema "%s"', r.nspname);
    END LOOP;

    IF cardinality(problems) > 0 THEN
        RAISE EXCEPTION E'antlab_owner_app: refusing to apply, everything was rolled back:\n  - %\nRemove these grants or objects (or run the script on a clean database) and run it again.',
            array_to_string(problems, E'\n  - ');
    END IF;
END
$roles$;
