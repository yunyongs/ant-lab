class EngineError(Exception):
    """Domain failure of pure engine code; code names match the Java ErrorCode."""

    code = "INTERNAL_ERROR"


class PathTooLong(EngineError):  # noqa: N818 - the name mirrors the Java error code
    code = "PATH_TOO_LONG"


class InvalidArgument(EngineError):  # noqa: N818
    code = "INVALID_ARGUMENT"
