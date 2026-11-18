-- Empties the regime columns that replay_macro.sql filled, so cleanup_season_start.sql still recognises the fake months.
UPDATE month_block SET rate_dir = NULL, growth_dir = NULL, fx_dir = NULL, sentiment_dir = NULL,
    rate_strength = NULL, growth_strength = NULL, fx_strength = NULL, sentiment_strength = NULL,
    prob_p1 = NULL, prob_p2 = NULL, prob_p3 = NULL, prob_p4 = NULL, regime_label = NULL, indicator_z = NULL
WHERE month_start BETWEEN DATE '2006-12-01' AND DATE '2026-01-01' AND event_summary IS NULL AND events_reviewed = TRUE;
