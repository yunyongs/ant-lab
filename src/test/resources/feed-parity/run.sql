-- Runs the SQL reference generator (db/24) over every turn of every season in the database and keeps, per turn, the input that
-- tf_input assembled and the rows tf_append stored. Session-local objects only; the test reads parity_out in the same session.
\i db/24_turn_feed_def.sql

CREATE TEMP TABLE parity_out (season_id bigint, turn_no integer, input jsonb, stored jsonb) ON COMMIT PRESERVE ROWS;

DO $$
DECLARE
    s record;
    t record;
BEGIN
    FOR s IN SELECT id FROM season ORDER BY id LOOP
        FOR t IN SELECT turn_no FROM season_path WHERE season_id = s.id ORDER BY turn_no LOOP
            INSERT INTO pg_temp.parity_out (season_id, turn_no, input)
            VALUES (s.id, t.turn_no, pg_temp.tf_input(s.id, t.turn_no::smallint));
            PERFORM pg_temp.tf_append(s.id, t.turn_no::smallint);
            UPDATE pg_temp.parity_out o
            SET stored = (SELECT coalesce(jsonb_agg(jsonb_build_object(
                                'seq', f.seq, 'kind', f.kind, 'asset_id', f.asset_id, 'asset_group_id', f.asset_group_id,
                                'month_event_id', f.month_event_id, 'category_hidden', f.category_hidden, 'direction', f.direction,
                                'strength', f.strength, 'repeat_count', f.repeat_count, 'template_id', f.template_id,
                                'template_vars_text', f.template_vars::text, 'body', f.body) ORDER BY f.seq), '[]'::jsonb)
                          FROM turn_feed f WHERE f.season_id = s.id AND f.turn_no = t.turn_no)
            WHERE o.season_id = s.id AND o.turn_no = t.turn_no;
        END LOOP;
    END LOOP;
END $$;
