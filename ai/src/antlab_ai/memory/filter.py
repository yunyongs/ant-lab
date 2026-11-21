"""Leak filter `memory-filter/1` for agent memory text.

Deterministic and pure: the same rules, normalisation and dictionary run in Java (authoritative)
and here (pre-check). The shared corpus under `src/test/resources/agent/memory-filter/` is the
contract between the two. A verdict carries pointers and codes only, never the offending text.
"""

from __future__ import annotations

import csv
import hashlib
import re
import unicodedata
from collections.abc import Iterable, Iterator, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

FILTER_ID = "memory-filter/1"
REPO_ROOT = Path(__file__).resolve().parents[4]
TERMS_PATH = REPO_ROOT / "src/main/resources/agent/leak-terms.txt"
TAGS_PATH = REPO_ROOT / "events/tags.csv"

REFLECTION_SCHEMA = "season-reflection/1"
NOTE_SCHEMA = "operating-note/1"
REFLECTION_TOTAL_MAX = 600
NOTE_TOTAL_MAX = 2000
FINGERPRINT_MIN = 3
# Shorter titles are generic phrases ("국제 유가 급락") that ordinary sentences also contain.
TITLE_MIN = 7

CODES = ("YEAR", "YEAR_MONTH", "EVENT_TERM", "FINGERPRINT", "TURN_RETURN", "PRICE")

_MONTH = (
    r"(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?"
    r"|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
)
# Only explicit ASCII classes below: no \d, \w, \b or \s, so a Java port with default
# (ASCII) classes behaves the same. normalize() has already folded every Nd digit to 0-9.
_SP = r"[ \t\r\n]"
_YEAR4 = r"(?:19|20)[0-9]{2}"
# Korean durations and bond tenors that are not years ("10년 이상", "10년물").
_DURATION = (
    r"(?!" + _SP + r"*(?:이상|이하|미만|초과|동안|간|째|물|만기|넘게|가까이|가량|정도|만에|마다"
    r"|주기|짜리|차))"
)

_YEAR_PATTERNS = (
    re.compile(rf"(?<![0-9]){_YEAR4}(?![0-9])"),
    re.compile(r"(?<![0-9])0[0-9]년(?!차)"),
    re.compile(rf"(?<![0-9])[1-9][0-9]년(?!차){_DURATION}"),
    re.compile(r"(?<![a-z0-9])['’‘][0-9]{2}(?![0-9])"),
    re.compile(rf"(?<![a-z])fy{_SP}?[0-9]{{2}}"),
    re.compile(r"(?<![a-z0-9.])[0-9]0s(?![a-z])"),
    re.compile(rf"(?<![a-z0-9])(?:[1-4]q|q[1-4]|[12]h|h[12]){_SP}?['’]?[0-9]{{2}}(?![0-9])"),
)
_YEAR_MONTH_PATTERNS = (
    re.compile(rf"(?<![0-9]){_YEAR4}{_SP}*[-/.]{_SP}*(?:0?[1-9]|1[0-2])(?![0-9])"),
    re.compile(rf"(?<![0-9]){_YEAR4}{_SP}*년{_SP}*[0-9]{{1,2}}{_SP}*월"),
    re.compile(rf"(?<![0-9])[0-9]{{2}}년{_SP}*[0-9]{{1,2}}{_SP}*월"),
    re.compile(rf"(?<![a-z]){_MONTH}(?![a-z]){_SP}*,?{_SP}*{_YEAR4}(?![0-9])"),
    re.compile(rf"(?<![0-9]){_YEAR4}{_SP}*,?{_SP}*(?<![a-z]){_MONTH}(?![a-z])"),
    re.compile(rf"(?<![a-z]){_MONTH}(?![a-z]){_SP}*[0-9]{{1,2}}{_SP}*,?{_SP}*{_YEAR4}(?![0-9])"),
    re.compile(rf"(?<![0-9/])(?:0?[1-9]|1[0-2]){_SP}*/{_SP}*{_YEAR4}(?![0-9])"),
    re.compile(rf"(?<![0-9]){_YEAR4}{_SP}*년?{_SP}*[1-4]{_SP}*분기"),
    re.compile(rf"(?<![a-z0-9])(?:[1-4]q|q[1-4]){_SP}*,?{_SP}*{_YEAR4}(?![0-9])"),
    re.compile(rf"(?<![0-9]){_YEAR4}{_SP}*,?{_SP}*q[1-4](?![0-9])"),
)

_NUM = r"[0-9]+(?:\.[0-9]+)?"
# A return-like number: signed percent, a percentage-point figure, a signed decimal, or an
# unsigned percent with a fraction (the AG-01 renderer prints "3.40%"). Only a digit or a dot
# before the number blocks a match, so "A사1.20%" and "에-12%" count while "6-8" and "5-10%" do not.
_RETURN_LIKE = re.compile(
    rf"(?<![0-9.])[+-]{_SP}?{_NUM}{_SP}?%p?"
    rf"|(?<![0-9.]){_NUM}{_SP}?%p"
    r"|(?<![0-9.])[0-9]+\.[0-9]+" + _SP + r"?%"
    r"|(?<![0-9.])[+-][0-9]+\.[0-9]+(?![0-9])"
)
# With a movement word in the sentence, an unsigned integer percent is a return too
# ("20% 하락, 8% 반등"); without one it is a weight ("현금 10%") and passes.
_DIRECTION = re.compile(
    r"상승|하락|올랐|오른|오르|떨어|내렸|내린|반등|급락|급등|폭락|폭등|뛰었|빠졌|rose|fell|gained|dropped|rallied|plunged|jumped"
)
_RETURN_OR_MOVE = re.compile(_RETURN_LIKE.pattern + rf"|(?<![0-9.])[0-9]+{_SP}?%(?!p)")
_ANY_PERCENT = re.compile(rf"(?<![0-9.])[+-]?{_NUM}{_SP}?%p?")
_TURN_LABEL = re.compile(
    rf"[0-9]+{_SP}*년차(?:{_SP}*[0-9]+{_SP}*월)?|[0-9]+{_SP}*턴|[0-9]+{_SP}*번째{_SP}*턴"
    rf"|(?<![a-z])turn{_SP}*[0-9]+|(?<![가-힣])턴{_SP}*[0-9]+"
)

_PRICE_PATTERNS = (
    re.compile(
        rf"[0-9][0-9,.]*{_SP}*(?:[만억천조]{_SP}*(?:[0-9][0-9,.]*{_SP}*)?)*"
        r"(?:원(?!칙|인|금|천|래|리|본|가|하)|달러|불(?![가-힣]))"
    ),
    re.compile(rf"[₩$€£¥]{_SP}*[0-9]"),
    re.compile(rf"[0-9][0-9,.]*{_SP}*(?:krw|usd|dollars?)(?![a-z])"),
    re.compile(rf"(?<![a-z])(?:krw|usd){_SP}*[0-9]"),
    re.compile(
        rf"[0-9][0-9,.]*{_SP}*(?:엔(?![진터지딩트])|유로(?!존|화)|위안|euros?|yen|yuan|eur|jpy|cny|gbp"
        r"|pounds?)(?![a-z])"
    ),
    re.compile(rf"[0-9]{{3,}}{_SP}*(?:포인트|pts?|points?)(?![a-z])"),
    re.compile(rf"(?:코스피|코스닥|지수|s&p|나스닥|다우|dow|nasdaq){_SP}*[0-9]{{4,}}"),
    re.compile(rf"배럴당{_SP}*[0-9]"),
    re.compile(r"(?<![0-9])[0-9]{1,3}(?:,[0-9]{3})+(?![0-9])"),
)

_ABBREVIATION_DOT = re.compile(r"(?<![a-z])(jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\.")
_SENTENCE_SPLIT = re.compile(r"[!?。\n]|(?<![0-9])\.|\.(?![0-9])")
_BLANK = " \t\r\n"


@dataclass(frozen=True)
class Hit:
    pointer: str
    code: str


@dataclass(frozen=True)
class Terms:
    """Normalised dictionary entries and the version label derived from the terms file."""

    terms: tuple[str, ...]
    titles: tuple[str, ...]
    version: str


@dataclass(frozen=True)
class Verdict:
    hits: tuple[Hit, ...]
    oversize: bool = False

    @property
    def ok(self) -> bool:
        return not self.hits and not self.oversize


def _is_alnum(ch: str) -> bool:
    """Letter (general category L*) or ASCII digit; the same predicate must hold in Java."""
    return unicodedata.category(ch)[0] == "L" or "0" <= ch <= "9"


def normalize(text: str) -> str:
    """NFKC, lower case, format (Cf) characters removed, Nd digits folded to 0-9, dashes to '-'."""
    folded = unicodedata.normalize("NFKC", text).lower()
    out = []
    for ch in folded:
        category = unicodedata.category(ch)
        if category == "Cf":
            continue
        if category == "Nd":
            ch = chr(48 + unicodedata.decimal(ch))
        elif ch in "−–‒":
            ch = "-"
        out.append(ch)
    return "".join(out)


def compact(text: str) -> str:
    """Letters and digits only, for dictionary matching that ignores spacing and punctuation."""
    return "".join(ch for ch in normalize(text) if _is_alnum(ch))


def split_sentences(text: str) -> list[str]:
    cleaned = _ABBREVIATION_DOT.sub(r"\1", normalize(text))
    return [part for part in _SENTENCE_SPLIT.split(cleaned) if part.strip(_BLANK)]


def _compact_with_flags(sentence: str) -> tuple[str, list[bool], list[bool]]:
    """Compact text plus, per kept character, 'starts a word' and 'next raw char is not a-z'."""
    chars: list[str] = []
    starts: list[bool] = []
    clear: list[bool] = []
    previous_kept = False
    for index, ch in enumerate(sentence):
        kept = _is_alnum(ch)
        if kept:
            chars.append(ch)
            starts.append(not previous_kept)
            following = sentence[index + 1] if index + 1 < len(sentence) else ""
            clear.append(not ("a" <= following <= "z"))
        previous_kept = kept
    return "".join(chars), starts, clear


def _term_in(term: str, squeezed: str, starts: list[bool], clear: list[bool]) -> bool:
    """A term matches from a word start; a term ending in a Latin letter also needs a word end."""
    latin_end = "a" <= term[-1] <= "z"
    position = squeezed.find(term)
    while position >= 0:
        if starts[position] and (not latin_end or clear[position + len(term) - 1]):
            return True
        position = squeezed.find(term, position + 1)
    return False


def load_terms(
    terms_path: Path = TERMS_PATH,
    tags_path: Path | None = TAGS_PATH,
    extra_titles: Iterable[str] = (),
) -> Terms:
    raw = terms_path.read_bytes()
    entries = []
    for line in raw.decode("utf-8-sig").splitlines():
        entry = line.strip()
        if entry and not entry.startswith("#") and (key := compact(entry)):
            entries.append(key)
    titles = list(extra_titles)
    if tags_path is not None and tags_path.is_file():
        with tags_path.open(encoding="utf-8", newline="") as handle:
            titles.extend((row.get("title") or "") for row in csv.DictReader(handle))
    keys = tuple(dict.fromkeys(key for title in titles if len(key := compact(title)) >= TITLE_MIN))
    return Terms(
        terms=tuple(dict.fromkeys(entries)),
        titles=keys,
        version="mf1-" + hashlib.sha256(raw).hexdigest()[:12],
    )


def _any(patterns: Iterable[re.Pattern[str]], sentence: str) -> bool:
    return any(pattern.search(sentence) for pattern in patterns)


def sentence_codes(sentence: str, terms: Terms) -> set[str]:
    """Codes for one already normalised sentence, FINGERPRINT excluded (it counts per string)."""
    found: set[str] = set()
    if _any(_YEAR_MONTH_PATTERNS, sentence):
        found.update(("YEAR", "YEAR_MONTH"))
    if _any(_YEAR_PATTERNS, sentence):
        found.add("YEAR")
    squeezed, starts, clear = _compact_with_flags(sentence)
    if any(_term_in(term, squeezed, starts, clear) for term in terms.terms) or any(
        title in squeezed for title in terms.titles
    ):
        found.add("EVENT_TERM")
    if _TURN_LABEL.search(sentence) and (
        _ANY_PERCENT.search(sentence) or _RETURN_LIKE.search(sentence)
    ):
        found.add("TURN_RETURN")
    if _any(_PRICE_PATTERNS, sentence):
        found.add("PRICE")
    return found


def scan_text(text: str, pointer: str, terms: Terms) -> list[Hit]:
    """Hits of one string. FINGERPRINT is counted over the whole string and reported once, at the
    sentence where the running count of return-like numbers reaches the threshold."""
    hits = []
    returns = 0
    reported = False
    for index, sentence in enumerate(split_sentences(text), start=1):
        found = sentence_codes(sentence, terms)
        counter = _RETURN_OR_MOVE if _DIRECTION.search(sentence) else _RETURN_LIKE
        returns += len(counter.findall(sentence))
        if returns >= FINGERPRINT_MIN and not reported:
            found.add("FINGERPRINT")
            reported = True
        hits.extend(Hit(f"{pointer}#s{index}", code) for code in CODES if code in found)
    return hits


def _nfc(text: str) -> str:
    return unicodedata.normalize("NFC", text)


def reflection_strings(doc: Mapping[str, Any]) -> Iterator[tuple[str, str]]:
    for index, text in enumerate(doc.get("good", ())):
        yield f"/good/{index}", _nfc(text)
    for index, item in enumerate(doc.get("mistakes", ())):
        yield f"/mistakes/{index}/text", _nfc(item["text"])
    for index, text in enumerate(doc.get("ruleCandidates", ())):
        yield f"/ruleCandidates/{index}", _nfc(text)


def note_strings(doc: Mapping[str, Any]) -> Iterator[tuple[str, str]]:
    for index, text in enumerate(doc.get("rules", ())):
        yield f"/rules/{index}", _nfc(text)


def total_chars(strings: Iterable[tuple[str, str]]) -> int:
    return sum(len(_nfc(text)) for _, text in strings)


def _verdict(strings: list[tuple[str, str]], cap: int, terms: Terms) -> Verdict:
    hits = tuple(hit for pointer, text in strings for hit in scan_text(text, pointer, terms))
    return Verdict(hits, total_chars(strings) > cap)


def check_reflection(doc: Mapping[str, Any], terms: Terms) -> Verdict:
    return _verdict(list(reflection_strings(doc)), REFLECTION_TOTAL_MAX, terms)


def check_note(doc: Mapping[str, Any], terms: Terms) -> Verdict:
    return _verdict(list(note_strings(doc)), NOTE_TOTAL_MAX, terms)
