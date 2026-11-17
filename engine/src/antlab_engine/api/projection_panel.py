"""History snapshot to return panel for `POST /v1/projections`.

US assets take the month's USD change as `fx_change`; KR assets take 0, as `MoneyMath.priceAt`
does for won prices. A pool asset whose key is not `MARKET:TICKER` is left out, so a request for
it is an unknown asset. Month starts stay inside the panel and never reach a message.
"""

from decimal import Decimal

from antlab_engine.engine_errors import InvalidArgument
from antlab_engine.history.model import HistorySnapshot
from antlab_engine.projection import ReturnPanel, ReturnRow
from antlab_engine.projection.model import ASSET_KEY

NO_FX = Decimal(0)
INVALID_HISTORY = "the stored history is not a valid return panel"


def panel_from_snapshot(snapshot: HistorySnapshot) -> ReturnPanel:
    """A panel of every pool asset over every month of the snapshot (missing cells stay empty)."""
    month_of = {m.month_block_id: m.month_start for m in snapshot.months}
    key_of = {
        a.asset_id: f"{a.market}:{a.ticker}"
        for a in snapshot.pool
        if ASSET_KEY.fullmatch(f"{a.market}:{a.ticker}")
    }
    currency_of = {a.asset_id: a.currency for a in snapshot.pool}
    usd = {c.month_block_id: c.fx_change for c in snapshot.fx if c.currency == "USD"}
    try:
        rows = []
        for r in snapshot.returns:
            key = key_of.get(r.asset_id)
            if key is None:
                continue
            month = month_of.get(r.month_block_id)
            if month is None:
                raise _invalid()
            if currency_of[r.asset_id] == "USD":
                fx = usd.get(r.month_block_id)
                if fx is None:
                    continue  # no currency move for the month: the cell stays empty
            else:
                fx = NO_FX
            rows.append(ReturnRow(key, month, r.local_return, fx))
        return ReturnPanel.from_rows(sorted(month_of.values()), list(key_of.values()), rows)
    except InvalidArgument:
        raise _invalid() from None


def _invalid() -> RuntimeError:
    # A stored history that breaks the panel rules is a server fault, not a bad request.
    return RuntimeError(INVALID_HISTORY)
