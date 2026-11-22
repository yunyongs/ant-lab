-- Fake data, not real events: month events on top of history-v1.sql for the feed-event parity harness
-- (FeedEventsParityFixtureTest in Java, test_world_pg.py in Python). Load history-v1.sql first.
--
-- Hidden rule (Java FeedInputReader.EVENTS): a type seen in fewer than 3 distinct left(event_key, 6) families inside
-- [replay start, January 1 of the base-date year) is hidden. With the default replay start 2007-01-01 and base year 2026:
--   FAKE_SHOCK  4 families (fakea1, fakea2, fakea3, Fakez9) -> public; with replay start 2012-01-01 only fakea3 -> hidden
--   FAKE_DUP    3 events but 2 families (fakeb1 twice)    -> hidden (DISTINCT, not a count of events)
--   FAKE_EDGE   2 families in range; one event the month before the replay start and one in the upper-bound month
--               (2026-01) must not count -> hidden
--   FAKE_RARE   1 family                                  -> hidden
-- 2008-09 holds two events whose order differs between byte order and a locale ("Fakez9" sorts before "fakea1" in bytes).
-- 2009-06 is not reviewed.

INSERT INTO month_block (month_start, events_reviewed)
VALUES (DATE '2006-12-01', TRUE), (DATE '2026-01-01', TRUE);

UPDATE month_block SET events_reviewed = FALSE WHERE month_start = DATE '2009-06-01';

INSERT INTO month_event (event_key, month_block_id, category, event_type, phase, direction, strength, reaction_change,
                         title, source)
SELECT v.k, m.id, v.cat, v.typ, 'START', v.dir, v.str, v.rc, '가짜 사건 ' || v.k, 'fake'
FROM (VALUES
    ('fakea1-2008-09', DATE '2008-09-01', 'FINANCIAL',    'FAKE_SHOCK', 'DOWN', 3, -0.12000000),
    ('Fakez9-2008-09', DATE '2008-09-01', 'POLICY',       'FAKE_SHOCK', 'UP',   1,  0.01000000),
    ('fakea2-2011-08', DATE '2011-08-01', 'FINANCIAL',    'FAKE_SHOCK', 'DOWN', 2, -0.06000000),
    ('fakea3-2016-06', DATE '2016-06-01', 'POLITICS',     'FAKE_SHOCK', 'UP',   1,  0.02000000),
    ('fakeb1-2009-04', DATE '2009-04-01', 'TRADE',        'FAKE_DUP',   'UP',   2,  0.03000000),
    ('fakeb1-2010-04', DATE '2010-04-01', 'TRADE',        'FAKE_DUP',   'DOWN', 1, -0.01500000),
    ('fakeb2-2012-04', DATE '2012-04-01', 'TRADE',        'FAKE_DUP',   'UP',   3,  0.04000000),
    ('fakee0-2006-12', DATE '2006-12-01', 'SUPPLY_SHOCK', 'FAKE_EDGE',  'DOWN', 2, -0.02000000),
    ('fakee1-2018-05', DATE '2018-05-01', 'SUPPLY_SHOCK', 'FAKE_EDGE',  'UP',   1,  0.00500000),
    ('fakee2-2019-05', DATE '2019-05-01', 'SUPPLY_SHOCK', 'FAKE_EDGE',  'UP',   2,  0.01250000),
    ('fakee3-2026-01', DATE '2026-01-01', 'SUPPLY_SHOCK', 'FAKE_EDGE',  'DOWN', 3, -0.09000000),
    ('faked1-2020-03', DATE '2020-03-01', 'HEALTH',       'FAKE_RARE',  'DOWN', 3, -0.15000000)
) AS v(k, d, cat, typ, dir, str, rc)
JOIN month_block m ON m.month_start = v.d;
