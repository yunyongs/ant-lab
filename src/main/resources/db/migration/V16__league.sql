-- AG-04 PR C1: the manual same-seed group. A league holds one server-drawn seed and the season settings every entry must
-- share; a league_entry ties one season to the league and gives each member one seat. The seed never leaves the server.
-- Entry through the season start (C2) is not part of this migration; only the tables and the create/get/close rules are.
CREATE TABLE league (
    id                 BIGINT        GENERATED ALWAYS AS IDENTITY,
    owner_member_id    BIGINT        NOT NULL,
    owner_kind         VARCHAR(5)    NOT NULL DEFAULT 'HUMAN',
    name               VARCHAR(50)   NOT NULL,
    status             VARCHAR(6)    NOT NULL DEFAULT 'OPEN',
    seed               BIGINT        NOT NULL,
    path_length        SMALLINT      NOT NULL,
    base_date          DATE          NOT NULL,
    engine_version     VARCHAR(20)   NOT NULL,
    data_snapshot_date DATE          NOT NULL,
    initial_cash       NUMERIC(19,4) NOT NULL,
    fee_rate_kr        NUMERIC(12,8) NOT NULL,
    fee_rate_us        NUMERIC(12,8) NOT NULL,
    fx_spread_rate     NUMERIC(12,8) NOT NULL,
    max_entries        SMALLINT      NOT NULL,
    closed_at          TIMESTAMPTZ,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_league PRIMARY KEY (id),
    CONSTRAINT fk_league_owner FOREIGN KEY (owner_member_id, owner_kind) REFERENCES member (id, kind)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_league_owner_human_only CHECK (owner_kind = 'HUMAN'),
    CONSTRAINT ck_league_name CHECK (name <> '' AND name = btrim(name)),
    CONSTRAINT ck_league_status CHECK (status IN ('OPEN','CLOSED')),
    CONSTRAINT ck_league_path_length CHECK (path_length > 0),
    CONSTRAINT ck_league_engine_version CHECK (engine_version <> ''),
    CONSTRAINT ck_league_initial_cash_pos CHECK (initial_cash > 0),
    CONSTRAINT ck_league_initial_cash_won CHECK (initial_cash = ROUND(initial_cash, 0) AND initial_cash <> 'NaN'::numeric),
    CONSTRAINT ck_league_fee_rate_kr CHECK (fee_rate_kr >= 0 AND fee_rate_kr < 1),
    CONSTRAINT ck_league_fee_rate_us CHECK (fee_rate_us >= 0 AND fee_rate_us < 1),
    CONSTRAINT ck_league_fx_spread_rate CHECK (fx_spread_rate >= 0 AND fx_spread_rate < 1),
    CONSTRAINT ck_league_max_entries CHECK (max_entries BETWEEN 2 AND 20),
    CONSTRAINT ck_league_closed_at CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);
CREATE INDEX idx_league_owner ON league (owner_member_id);
COMMENT ON TABLE league IS '리그';
COMMENT ON COLUMN league.id IS '[내부] 리그번호';
COMMENT ON COLUMN league.owner_member_id IS '[내부] 소유자 회원번호';
COMMENT ON COLUMN league.owner_kind IS '[내부] 소유자 회원 종류(HUMAN 고정)';
COMMENT ON COLUMN league.name IS '[노출] 리그 이름';
COMMENT ON COLUMN league.status IS '[노출] 리그 상태';
COMMENT ON COLUMN league.seed IS '[내부] 시드';
COMMENT ON COLUMN league.path_length IS '[노출] 경로 길이';
COMMENT ON COLUMN league.base_date IS '[내부] 기준일';
COMMENT ON COLUMN league.engine_version IS '[내부] 엔진 버전';
COMMENT ON COLUMN league.data_snapshot_date IS '[내부] 데이터 스냅샷 날짜';
COMMENT ON COLUMN league.initial_cash IS '[내부] 초기 자금';
COMMENT ON COLUMN league.fee_rate_kr IS '[내부] 국내 수수료율';
COMMENT ON COLUMN league.fee_rate_us IS '[내부] 미국 수수료율';
COMMENT ON COLUMN league.fx_spread_rate IS '[내부] 환전 비용률';
COMMENT ON COLUMN league.max_entries IS '[노출] 최대 참가 수';
COMMENT ON COLUMN league.closed_at IS '[내부] 마감일시';
COMMENT ON COLUMN league.version IS '[내부] 낙관적 락 버전';
COMMENT ON COLUMN league.created_at IS '[내부] 생성일시';
COMMENT ON COLUMN league.updated_at IS '[내부] 수정일시';

-- One row per season in a league. A member holds one seat per league (uq_league_entry_member); a season is in at most one
-- league (uq_league_entry_season). The composite foreign key to season pins member_id to the season's own member.
CREATE TABLE league_entry (
    league_id  BIGINT      NOT NULL,
    season_id  BIGINT      NOT NULL,
    member_id  BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_league_entry PRIMARY KEY (league_id, season_id),
    CONSTRAINT uq_league_entry_season UNIQUE (season_id),
    CONSTRAINT uq_league_entry_member UNIQUE (league_id, member_id),
    CONSTRAINT fk_league_entry_league FOREIGN KEY (league_id) REFERENCES league (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_league_entry_season FOREIGN KEY (season_id, member_id) REFERENCES season (id, member_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);
COMMENT ON TABLE league_entry IS '리그 참가';
COMMENT ON COLUMN league_entry.league_id IS '[내부] 리그번호';
COMMENT ON COLUMN league_entry.season_id IS '[내부] 시즌번호';
COMMENT ON COLUMN league_entry.member_id IS '[내부] 회원번호';
COMMENT ON COLUMN league_entry.created_at IS '[내부] 참가일시';
