-- M2-08: the regime label job fills the label and the indicator values of month_block from NULL, once.
-- Both columns start NULL for every existing row; the new CHECKs only constrain rows the job writes.
ALTER TABLE month_block
    ADD COLUMN regime_label VARCHAR(2),
    ADD COLUMN indicator_z  JSONB;

ALTER TABLE month_block
    ADD CONSTRAINT ck_month_block_regime_label CHECK (regime_label IN ('P1','P2','P3','P4')),
    ADD CONSTRAINT ck_month_block_label_with_prob CHECK ((regime_label IS NULL) = (prob_p1 IS NULL)),
    ADD CONSTRAINT ck_month_block_indicator_z CHECK (jsonb_typeof(indicator_z) = 'object');

COMMENT ON COLUMN month_block.regime_label IS '[내부] 국면 라벨(히스테리시스, P1~P4)';
COMMENT ON COLUMN month_block.indicator_z IS '[노출] 지표 변환값(키 "1"~"14", "14US", "14KR")';
