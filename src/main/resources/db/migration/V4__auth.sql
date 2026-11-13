-- Authentication schema: member role and refresh-token families (rotation, reuse detection).
ALTER TABLE member ADD COLUMN role VARCHAR(5) NOT NULL DEFAULT 'USER';
ALTER TABLE member ADD CONSTRAINT ck_member_role CHECK (role IN ('USER','ADMIN'));
COMMENT ON COLUMN member.role IS '[내부] 역할';

CREATE TABLE refresh_token (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY,
    member_id      BIGINT      NOT NULL,
    family_id      UUID        NOT NULL,
    parent_id      BIGINT,
    token_hash     VARCHAR(64) NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(14),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_refresh_token PRIMARY KEY (id),
    CONSTRAINT uq_refresh_token_token_hash UNIQUE (token_hash),
    CONSTRAINT uq_refresh_token_parent_id UNIQUE (parent_id),
    CONSTRAINT uq_refresh_token_id_member_family UNIQUE (id, member_id, family_id),
    CONSTRAINT fk_refresh_token_member FOREIGN KEY (member_id) REFERENCES member (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_refresh_token_parent FOREIGN KEY (parent_id, member_id, family_id)
        REFERENCES refresh_token (id, member_id, family_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_refresh_token_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_token_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_refresh_token_revoked_at CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CONSTRAINT ck_refresh_token_revoked_reason CHECK (revoked_reason IN ('ROTATED','LOGOUT','REUSE_DETECTED')),
    CONSTRAINT ck_refresh_token_revoked_pair CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
    CONSTRAINT ck_refresh_token_parent_before CHECK (parent_id IS NULL OR parent_id < id)
);
CREATE UNIQUE INDEX uq_refresh_token_family_live ON refresh_token (family_id) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX uq_refresh_token_family_root ON refresh_token (family_id) WHERE parent_id IS NULL;
CREATE INDEX idx_refresh_token_member ON refresh_token (member_id);
COMMENT ON TABLE refresh_token IS '갱신 토큰';
COMMENT ON COLUMN refresh_token.id IS '[내부] 토큰번호';
COMMENT ON COLUMN refresh_token.member_id IS '[내부] 회원번호';
COMMENT ON COLUMN refresh_token.family_id IS '[내부] 토큰 묶음';
COMMENT ON COLUMN refresh_token.parent_id IS '[내부] 이전 토큰번호';
COMMENT ON COLUMN refresh_token.token_hash IS '[내부] 토큰 해시';
COMMENT ON COLUMN refresh_token.expires_at IS '[내부] 만료일시';
COMMENT ON COLUMN refresh_token.revoked_at IS '[내부] 폐기일시';
COMMENT ON COLUMN refresh_token.revoked_reason IS '[내부] 폐기 사유';
COMMENT ON COLUMN refresh_token.created_at IS '[내부] 발급일시';
COMMENT ON COLUMN refresh_token.updated_at IS '[내부] 수정일시';
