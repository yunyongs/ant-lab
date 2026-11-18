"""File-level checks of events/*.csv that need no database.

The loader (db/11_load_events.sql) and its check repeat these on a database; this test is the
pre-load gate for drafts whose months are not in month_block yet.
"""
import csv
import re
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
EVENTS = ROOT / "events"
CATEGORIES = {"GEOPOLITICS", "TRADE", "POLITICS", "SUPPLY_SHOCK", "FINANCIAL", "POLICY", "HEALTH"}
TYPE_COLUMNS = ["event_type", "category", "name_ko", "outcomes", "direction_rule", "reaction_refs"]
TAG_COLUMNS = [
    "event_key", "month_start", "category", "event_type", "phase", "outcome", "direction",
    "reaction_ref", "title", "hindsight", "source", "checked_on",
]
CODE = re.compile(r"[A-Z][A-Z_]*")
REF = re.compile(r"KR:[0-9]{6}|US:[A-Z]+|FX:USD")
QUOTES = re.compile("[\"“”'‘’「」]")


def _read(name, columns):
    with open(EVENTS / name, encoding="utf-8", newline="") as f:
        raw = f.read()
    assert not raw.startswith("﻿") and "\r" not in raw and raw.endswith("\n") and not raw.endswith("\n\n")
    reader = csv.DictReader(raw.splitlines())
    assert reader.fieldnames == columns
    return list(reader)


def _types():
    return _read("types.csv", TYPE_COLUMNS)


def _tags():
    return _read("tags.csv", TAG_COLUMNS)


def _deny_tokens():
    sql = (ROOT / "db" / "11_load_events_check.sql").read_text(encoding="utf-8")
    block = sql[sql.index("INSERT INTO zz_deny"):]
    block = block[:block.index(";\n")]
    tokens = set(re.findall(r"\('([A-Z]+)', '", block))
    assert len(tokens) > 50
    return tokens


def _pool_refs():
    refs = set()
    for name in ("db/sample/asset.csv", "db/full/pool_proposal.csv"):
        with open(ROOT / name, encoding="utf-8", newline="") as f:
            refs |= {f"{r['market']}:{r['ticker']}" for r in csv.DictReader(f)}
    return refs


def _exposed_codes():
    for t in _types():
        yield t["event_type"]
        yield from filter(None, t["outcomes"].split(";"))
    for g in _tags():
        yield g["event_type"]
        if g["outcome"]:
            yield g["outcome"]


def test_types_format_and_order():
    rows = _types()
    keys = [(r["category"], r["event_type"]) for r in rows]
    assert keys == sorted(keys, key=lambda k: (k[0].encode(), k[1].encode()))
    assert len({r["event_type"] for r in rows}) == len(rows)
    for r in rows:
        assert CODE.fullmatch(r["event_type"]) and len(r["event_type"]) <= 30, r
        assert r["category"] in CATEGORIES, r
        assert r["name_ko"] and r["name_ko"] == r["name_ko"].strip(), r
        assert r["direction_rule"] in {"NONE", "UP_DOWN"}, r
        for o in filter(None, r["outcomes"].split(";")):
            assert CODE.fullmatch(o) and len(o) <= 20, r
        refs = r["reaction_refs"].split(";")
        assert all(REF.fullmatch(x) for x in refs) and len(refs) == len(set(refs)), r


def test_reaction_refs_are_p0_08_assets():
    # The loader checks refs against asset; the sample database holds only the P0-08 20.
    with open(ROOT / "db" / "sample" / "asset.csv", encoding="utf-8", newline="") as f:
        sample = {f"{r['market']}:{r['ticker']}" for r in csv.DictReader(f)}
    pool = _pool_refs()
    for r in _types():
        for ref in r["reaction_refs"].split(";"):
            if not ref.startswith("FX:"):
                assert ref in sample and ref in pool, (r["event_type"], ref)


def test_h5_exposed_codes():
    deny = _deny_tokens()
    for code in _exposed_codes():
        assert not re.search(r"[0-9]", code), code
        for tok in code.split("_"):
            assert len(tok) > 2 or tok in {"US", "KR"}, code
            assert tok not in deny, code


def test_h6_tags_follow_types():
    types = {r["event_type"]: r for r in _types()}
    for g in _tags():
        t = types.get(g["event_type"])
        assert t is not None, g["event_key"]
        assert g["category"] == t["category"], g["event_key"]
        allowed = set(filter(None, t["outcomes"].split(";")))
        assert (g["outcome"] in allowed) if g["outcome"] else True, g["event_key"]
        if t["direction_rule"] == "UP_DOWN":
            assert g["direction"] in {"UP", "DOWN"}, g["event_key"]
        else:
            assert g["direction"] == "", g["event_key"]
        assert g["reaction_ref"] in t["reaction_refs"].split(";"), g["event_key"]


def test_tags_format_episodes_and_month_cap():
    tags = _tags()
    keys = [g["event_key"] for g in tags]
    assert keys == sorted(keys, key=str.encode) and len(keys) == len(set(keys))
    episodes = defaultdict(list)
    for g in tags:
        assert re.fullmatch(r"ev[0-9]{4}\.[0-9]{2}", g["event_key"]), g
        assert re.fullmatch(r"\d{4}-(0[1-9]|1[0-2])-01", g["month_start"]), g
        assert g["phase"] in {"START", "ONGOING", "END"}, g
        assert 1 <= len(g["title"]) <= 100 and g["title"] == g["title"].strip() and not QUOTES.search(g["title"]), g
        if g["hindsight"]:
            h = g["hindsight"]
            assert len(h) <= 300 and h == h.strip() and not QUOTES.search(h), g
        assert len(g["source"]) <= 500 and "utm_" not in g["source"].lower(), g
        assert re.fullmatch(r"https://\S+( ; https://\S+){0,2}", g["source"]), g
        assert re.fullmatch(r"\d{4}-\d{2}-\d{2}", g["checked_on"]), g
        episodes[g["event_key"][:6]].append(g)
    for ep, rows in episodes.items():
        assert len({(r["category"], r["event_type"]) for r in rows}) == 1, ep
        assert [int(r["event_key"][7:]) for r in rows] == list(range(1, len(rows) + 1)), ep
        months = [r["month_start"] for r in rows]
        assert all(a < b for a, b in zip(months, months[1:])), ep
        assert all(r["phase"] != "START" for r in rows[1:]), ep
        assert all(r["phase"] != "END" for r in rows[:-1]), ep
    per_month = Counter(g["month_start"] for g in tags)
    assert max(per_month.values()) <= 3


def test_reviewed_covers_tagged_months():
    with open(EVENTS / "reviewed.csv", encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    months = [r["month_start"] for r in rows]
    assert months == sorted(months) and len(months) == len(set(months))
    result = {r["month_start"]: r["result"] for r in rows}
    assert set(result.values()) <= {"TAGGED", "NONE"}
    tagged = {g["month_start"] for g in _tags()}
    assert {m for m, v in result.items() if v == "TAGGED"} == tagged


def test_readme_type_table_mirrors_types_csv():
    text = (EVENTS / "README.md").read_text(encoding="utf-8")
    section = text[text.index("### 유형 표"):]
    section = section[:section.index("\n\n", section.index("|---"))]
    mirrored = []
    for line in section.splitlines()[4:]:
        cells = [c.strip().strip("`") for c in line.strip("|").split("|")]
        outcomes = "" if cells[3] == "(빈 칸)" else cells[3]
        mirrored.append([cells[0], cells[1], cells[2], outcomes, cells[4], cells[5]])
    assert mirrored == [[r[c] for c in TYPE_COLUMNS] for r in _types()]
