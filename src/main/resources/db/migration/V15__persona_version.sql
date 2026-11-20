-- AG-04 PR B: the server-side record of released persona versions. A season start pins (persona_key, version) to one
-- sha256; the runner supplies the three values and the server refuses a second sha for a version or a version below the
-- newest one. Append-only: a row is never updated or deleted, so a past season's (version, sha) stays recognisable.
CREATE TABLE persona_version (
    persona_key  VARCHAR(64)  NOT NULL,
    version      INTEGER      NOT NULL,
    sha256       VARCHAR(64)  NOT NULL,
    display_name VARCHAR(50)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_persona_version PRIMARY KEY (persona_key, version),
    CONSTRAINT uq_persona_version_sha UNIQUE (persona_key, sha256),
    CONSTRAINT uq_persona_version_key_version_sha UNIQUE (persona_key, version, sha256),
    CONSTRAINT ck_persona_version_key CHECK (persona_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_persona_version_version CHECK (version >= 1),
    CONSTRAINT ck_persona_version_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_persona_version_display_name CHECK (display_name <> '' AND display_name = btrim(display_name))
);
COMMENT ON TABLE persona_version IS '페르소나 버전';
COMMENT ON COLUMN persona_version.persona_key IS '[내부] 페르소나 키';
COMMENT ON COLUMN persona_version.version IS '[내부] 페르소나 버전';
COMMENT ON COLUMN persona_version.sha256 IS '[내부] 페르소나 파일 해시';
COMMENT ON COLUMN persona_version.display_name IS '[내부] 페르소나 표시 이름';
COMMENT ON COLUMN persona_version.created_at IS '[내부] 기록일시';

-- Backfill from the seasons already recorded. Two different hashes for one version, or one hash under two versions, is
-- history this table cannot represent: stop instead of choosing one.
DO $backfill$
DECLARE
    two_hashes text;
    two_versions text;
BEGIN
    SELECT string_agg(persona_key || ' v' || persona_version, ', ') INTO two_hashes FROM (
        SELECT persona_key, persona_version FROM season_agent_config
        GROUP BY persona_key, persona_version HAVING count(DISTINCT persona_sha256) > 1) d;
    IF two_hashes IS NOT NULL THEN
        RAISE EXCEPTION 'V15: season_agent_config has two hashes for one persona version: %', two_hashes;
    END IF;
    SELECT string_agg(persona_key || ' ' || persona_sha256, ', ') INTO two_versions FROM (
        SELECT persona_key, persona_sha256 FROM season_agent_config
        GROUP BY persona_key, persona_sha256 HAVING count(DISTINCT persona_version) > 1) d;
    IF two_versions IS NOT NULL THEN
        RAISE EXCEPTION 'V15: season_agent_config has one hash under two persona versions: %', two_versions;
    END IF;
END
$backfill$;

INSERT INTO persona_version (persona_key, version, sha256, display_name, created_at)
SELECT c.persona_key, c.persona_version, min(c.persona_sha256), min(m.name), min(c.created_at)
FROM season_agent_config c
JOIN member m ON m.id = c.member_id
GROUP BY c.persona_key, c.persona_version;

ALTER TABLE season_agent_config ADD CONSTRAINT fk_season_agent_config_persona
    FOREIGN KEY (persona_key, persona_version, persona_sha256) REFERENCES persona_version (persona_key, version, sha256)
    ON DELETE RESTRICT ON UPDATE RESTRICT;
