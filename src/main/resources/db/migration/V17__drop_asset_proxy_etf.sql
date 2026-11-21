-- MVP-03: the full history gives stocks proxy months too, so proxy_index_name is no longer ETF-only.
-- Rows are untouched; return_kind keeps ACTUAL and PROXY_INDEX.
ALTER TABLE asset DROP CONSTRAINT ck_asset_proxy_etf;
