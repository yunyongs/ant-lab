class EngineError(Exception):
    """Domain failure of pure engine code; code names match the Java ErrorCode."""

    code = "INTERNAL_ERROR"


class PathTooLong(EngineError):  # noqa: N818 - the name mirrors the Java error code
    code = "PATH_TOO_LONG"


class InvalidArgument(EngineError):  # noqa: N818
    code = "INVALID_ARGUMENT"


class HistoryUnavailable(EngineError):  # noqa: N818
    """The history database could not be reached or queried; the cause is never echoed."""

    code = "HISTORY_UNAVAILABLE"


class PoolNotFound(EngineError):  # noqa: N818
    code = "POOL_NOT_FOUND"


class EngineBusy(EngineError):  # noqa: N818
    """Too many projections are running; the caller may retry later."""

    code = "ENGINE_BUSY"


class RegimeDataInvalid(EngineError):  # noqa: N818
    """The collected observations cannot be used; the text names a series, never a date or value."""

    code = "REGIME_DATA_INVALID"


class RegimeLabelsMissing(EngineError):  # noqa: N818
    """Too few stored regime labels to condition a path; the text carries counts only."""

    code = "REGIME_LABELS_MISSING"


class ReferenceMismatch(EngineError):  # noqa: N818
    """The engine's own benchmark or Sharpe for the sent season differs from the Java values."""

    code = "REFERENCE_MISMATCH"


class FeedInputUnusable(EngineError):  # noqa: N818
    """The stored history of a path month cannot build the season's world."""

    code = "FEED_INPUT"


class NoReference(EngineError):  # noqa: N818
    """The reference bots cannot play the season's world; the same request fails the same way."""

    code = "NO_REFERENCE"


class UnsafeDatabaseRole(EngineError):  # noqa: N818
    code = "UNSAFE_DB_ROLE"
