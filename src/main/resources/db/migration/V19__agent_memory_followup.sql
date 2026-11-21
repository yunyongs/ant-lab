-- AG-05 PR C: two follow-ups of V18. A season's memory pin is read by (agent, version), and a sibling league must
-- really play the seed of its group root (the service sets seed_league_id; the database now refuses a different seed).

CREATE INDEX idx_season_agent_config_memory ON season_agent_config (member_id, memory_version);

-- the composite FK needs a unique key that holds the seed; NULL seed_league_id (a root or a plain league) is not checked
ALTER TABLE league ADD CONSTRAINT uq_league_id_seed UNIQUE (id, seed);
ALTER TABLE league ADD CONSTRAINT fk_league_seed_group FOREIGN KEY (seed_league_id, seed) REFERENCES league (id, seed)
    ON DELETE RESTRICT ON UPDATE RESTRICT;
