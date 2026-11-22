"""Feed row selection of rule version `p0-18:v2` (Java `FeedGenerator`, without text rendering).

The same input gives the same rows in the same order. Candidates are events (all of them), asset
and group rises and falls, spreads, echoes and fades; a noise candidate without a usable template
is dropped, an event without one is an error. Row bodies are not rendered: the port returns the
template id and the canonical variable text, which is what scoring and the bots need.
"""

from dataclasses import dataclass
from decimal import Decimal

from .. import money
from .catalog import Catalog, Template
from .facts import DOWN, UP, AssetFacts, EventFacts, FeedInput
from .feedhash import byte_key, pick, utf16_key
from .rules import (
    FADE_TOP,
    MAX_ROWS,
    MIN_ROWS,
    ROW_SPAN,
    Failure,
    FeedGenerationError,
    grade,
)

_INT_MIN = -(2**31)
_INT_MAX = 2**31 - 1

Vars = dict[str, Decimal | str]


@dataclass(frozen=True, slots=True)
class GeneratedRow:
    seq: int
    kind: str
    asset_id: int | None
    asset_group_id: int | None
    month_event_id: int | None
    category_hidden: bool
    direction: str | None
    strength: int | None
    repeat_count: int
    template_id: str
    template_vars_json: str


@dataclass(frozen=True, slots=True)
class _Candidate:
    kind: str
    asset_id: int | None
    group_id: int | None
    event_id: int | None
    hidden: bool
    direction: str | None
    strength: int | None
    weight: int
    repeat: int
    key: str
    vars: Vars
    pool: list[Template]


@dataclass(frozen=True, slots=True)
class _Ticket:
    candidate: _Candidate
    round: int
    ticket: int


@dataclass(frozen=True, slots=True)
class _Built:
    sort_key: int
    candidate: _Candidate
    round: int
    template: Template
    vars_json: str


@dataclass(frozen=True, slots=True)
class GroupFacts:
    id: int
    code: str
    name: str
    ret: Decimal
    attention: Decimal | None
    prev_attention: Decimal | None
    rank: int | None
    ranked: int


def _render_error(reason: str) -> FeedGenerationError:
    return FeedGenerationError(Failure.FEED_RENDER, reason)


def _percent(ratio: Decimal) -> int:
    value = int(money.percent_points(ratio))
    if not _INT_MIN <= value <= _INT_MAX:
        raise _render_error("percent out of range")
    return value


def group_facts(assets: tuple[AssetFacts, ...]) -> list[GroupFacts]:
    """Group figures as `tf_input` derives them: mean return rounded once, mean attention, rank."""
    by_group: dict[int, list[AssetFacts]] = {}
    for a in assets:
        by_group.setdefault(a.group_id, []).append(a)
    raw: list[GroupFacts] = []
    for gid, members in by_group.items():
        attentions = [m.attention for m in members if m.attention is not None]
        previous = [m.prev_attention for m in members if m.prev_attention is not None]
        name = max((m.group_name for m in members), key=utf16_key)
        raw.append(
            GroupFacts(
                gid,
                members[0].group_code,
                name,
                money.round8(_avg([m.ret for m in members])),
                money.pg_avg(attentions),
                money.pg_avg(previous),
                None,
                0,
            )
        )
    ranked = sum(1 for g in raw if g.attention is not None)
    out: list[GroupFacts] = []
    for g in raw:
        rank = (
            None
            if g.attention is None
            else 1 + sum(1 for o in raw if o.attention is not None and o.attention > g.attention)
        )
        out.append(
            GroupFacts(g.id, g.code, g.name, g.ret, g.attention, g.prev_attention, rank, ranked)
        )
    return out


def _avg(values: list[Decimal]) -> Decimal:
    mean = money.pg_avg(values)
    assert mean is not None  # noqa: S101 - a group has at least one member
    return mean


class FeedGenerator:
    def __init__(self, catalog: Catalog) -> None:
        self._catalog = catalog

    def generate(self, feed_input: FeedInput) -> list[GeneratedRow]:
        seed, turn = feed_input.seed, feed_input.turn_no
        target = MIN_ROWS + pick(seed, turn, "count", "") % ROW_SPAN
        events = [self._event_candidate(e) for e in feed_input.events]
        noise = self._collect_noise(feed_input)
        if not events and not noise:
            return []
        events.sort(key=lambda c: byte_key(c.key))
        built = [self._build(feed_input, c, 1) for c in events]
        limit = max(0, target - len(events))
        for ticket in _pick_noise(feed_input, noise, target, limit):
            built.append(self._build(feed_input, ticket.candidate, ticket.round))
        built.sort(key=lambda b: (b.sort_key, b.candidate.kind, byte_key(b.candidate.key), b.round))
        if len(built) > MAX_ROWS:
            raise _render_error(f"more than {MAX_ROWS} rows")
        rows: list[GeneratedRow] = []
        for i, b in enumerate(built):
            c = b.candidate
            rows.append(
                GeneratedRow(
                    seq=i + 1,
                    kind=c.kind,
                    asset_id=c.asset_id,
                    asset_group_id=c.group_id,
                    month_event_id=c.event_id,
                    category_hidden=c.kind == "EVENT" and c.hidden,
                    direction=c.direction,
                    strength=c.strength,
                    repeat_count=c.repeat,
                    template_id=b.template.id,
                    template_vars_json=b.vars_json,
                )
            )
        return rows

    def _event_candidate(self, e: EventFacts) -> _Candidate:
        variables: Vars = {"reaction": e.reaction}
        pool = self._catalog.pool(
            "EVENT", "NONE", None, None, variables.keys(), e.hidden, e.category
        )
        return _Candidate(
            "EVENT",
            None,
            None,
            e.month_event_id,
            e.hidden,
            None,
            e.strength,
            1,
            0,
            "event:" + e.event_key,
            variables,
            pool,
        )

    def _collect_noise(self, feed_input: FeedInput) -> list[_Candidate]:
        noise: list[_Candidate] = []
        for a in feed_input.assets:
            g = grade(a.attention_rank, a.attention_n)
            if a.ret != 0:
                up = a.ret > 0
                direction = UP if up else DOWN
                self._add(
                    noise,
                    "RISE" if up else "FALL",
                    "ASSET",
                    a.asset_id,
                    None,
                    direction,
                    g,
                    0,
                    "asset:" + a.ref,
                    _asset_vars(a, False),
                    _percent(a.ret),
                )
                if (
                    a.prev_ret is not None
                    and _sign(a.prev_ret) == _sign(a.ret)
                    and a.prev_story_direction == direction
                    and not (
                        a.attention is not None
                        and a.prev_attention is not None
                        and a.attention < a.prev_attention
                    )
                ):
                    self._add(
                        noise,
                        "ECHO",
                        "ASSET",
                        a.asset_id,
                        None,
                        direction,
                        g,
                        a.prev_echo_repeat,
                        "echo:" + a.ref,
                        _asset_vars(a, True),
                        _percent(a.ret),
                    )
            if (
                a.attention is not None
                and a.prev_attention is not None
                and a.attention < a.prev_attention
                and a.prev_attention_rank is not None
                and a.prev_attention_rank <= FADE_TOP
                and a.focus_name is not None
                and a.focus_name != a.name
            ):
                variables: Vars = {"asset_name": a.name, "focus_name": a.focus_name}
                if a.attention_rank is not None:
                    variables["attention_rank"] = Decimal(a.attention_rank)
                self._add(
                    noise,
                    "FADE",
                    "ASSET",
                    a.asset_id,
                    None,
                    None,
                    g,
                    0,
                    "fade:" + a.ref,
                    variables,
                    None,
                )
        for grp in group_facts(feed_input.assets):
            if grp.ret == 0:
                continue
            up = grp.ret > 0
            direction = UP if up else DOWN
            g = grade(grp.rank, grp.ranked)
            self._add(
                noise,
                "RISE" if up else "FALL",
                "GROUP",
                None,
                grp.id,
                direction,
                g,
                0,
                "group:" + grp.code,
                _group_vars(grp),
                _percent(grp.ret),
            )
            if (
                grp.attention is not None
                and grp.prev_attention is not None
                and grp.attention > grp.prev_attention
            ):
                self._add(
                    noise,
                    "SPREAD",
                    "GROUP",
                    None,
                    grp.id,
                    direction,
                    g,
                    0,
                    "spread:" + grp.code,
                    _group_vars(grp),
                    _percent(grp.ret),
                )
        return noise

    def _add(
        self,
        noise: list[_Candidate],
        kind: str,
        target: str,
        asset_id: int | None,
        group_id: int | None,
        direction: str | None,
        strength: int | None,
        repeat: int,
        key: str,
        variables: Vars,
        r: int | None,
    ) -> None:
        pool = self._catalog.pool(kind, target, direction, r, variables.keys(), False, None)
        if pool:
            weight = max(1, 1 if strength is None else strength)
            noise.append(
                _Candidate(
                    kind,
                    asset_id,
                    group_id,
                    None,
                    False,
                    direction,
                    strength,
                    weight,
                    repeat,
                    key,
                    variables,
                    pool,
                )
            )

    def _build(self, feed_input: FeedInput, c: _Candidate, round_no: int) -> _Built:
        if not c.pool:
            raise _render_error("an event has no usable template")
        key = f"{c.key}:{round_no}"
        seed, turn = feed_input.seed, feed_input.turn_no
        template = c.pool[pick(seed, turn, "tpl", key) % len(c.pool)]
        variables: Vars = {slot: c.vars[slot] for slot in template.placeholders}
        if not variables:
            raise _render_error("a chosen template has no slot")
        return _Built(
            pick(seed, turn, "order", key), c, round_no, template, canonical_vars(variables)
        )


def _sign(value: Decimal) -> int:
    return (value > 0) - (value < 0)


def _asset_vars(a: AssetFacts, echo: bool) -> Vars:
    variables: Vars = {"asset_name": a.name, "group_name": a.group_name, "ret": a.ret}
    if a.prev_ret is not None:
        variables["prev_ret"] = a.prev_ret
    if a.streak >= 2:
        variables["streak"] = Decimal(a.streak)
    if not echo and a.attention_rank is not None:
        variables["attention_rank"] = Decimal(a.attention_rank)
    if echo:
        variables["repeat"] = Decimal(a.prev_echo_repeat)
    return variables


def _group_vars(g: GroupFacts) -> Vars:
    return {"group_name": g.name, "group_ret": g.ret}


def _pick_noise(
    feed_input: FeedInput, noise: list[_Candidate], rounds: int, limit: int
) -> list[_Ticket]:
    """Weighted draw without replacement: each candidate holds `weight` tickets per round."""
    picked: list[_Ticket] = []
    seed, turn = feed_input.seed, feed_input.turn_no
    round_no = 1
    while round_no <= rounds and len(picked) < limit and noise:
        tickets = [
            _Ticket(
                c,
                round_no,
                min(
                    pick(seed, turn, "pick", f"{c.key}:{round_no}:{i}")
                    for i in range(1, c.weight + 1)
                ),
            )
            for c in noise
        ]
        tickets.sort(key=lambda t: (t.ticket, byte_key(t.candidate.key)))
        for t in tickets:
            if len(picked) == limit:
                break
            picked.append(t)
        round_no += 1
    return picked


def canonical_vars(values: Vars) -> str:
    """The text PostgreSQL gives a jsonb object: keys by byte length then bytes, `": "`, `", "`."""
    keys = sorted(values, key=lambda k: (len(byte_key(k)), byte_key(k)))
    parts = []
    for k in keys:
        v = values[k]
        text = format(v, "f") if isinstance(v, Decimal) else _json_string(v)
        parts.append(f"{_json_string(k)}: {text}")
    return "{" + ", ".join(parts) + "}"


_ESCAPES = {
    '"': '\\"',
    "\\": "\\\\",
    "\b": "\\b",
    "\f": "\\f",
    "\n": "\\n",
    "\r": "\\r",
    "\t": "\\t",
}


def _json_string(text: str) -> str:
    out = ['"']
    for ch in text:
        if ch in _ESCAPES:
            out.append(_ESCAPES[ch])
        elif ord(ch) < 0x20:
            out.append(f"\\u{ord(ch):04x}")
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)
