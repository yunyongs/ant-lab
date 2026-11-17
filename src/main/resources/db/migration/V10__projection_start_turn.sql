-- A START projection is always the team at the end of turn 0 (V9 left this to the service). The END link to
-- path_length stays a service rule: a CHECK sees one row and cannot read season.
ALTER TABLE projection
    ADD CONSTRAINT ck_projection_start_turn CHECK (phase <> 'START' OR turn_no = 0);
