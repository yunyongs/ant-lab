-- MVP-15: a season whose turn advance keeps failing reconciliation stops in the status HALTED.
-- Same constraint name, one more value; rows are untouched.
ALTER TABLE season DROP CONSTRAINT ck_season_status;
ALTER TABLE season ADD CONSTRAINT ck_season_status CHECK (status IN ('IN_PROGRESS','ABANDONING','FINISHED','HALTED'));
