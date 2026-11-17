from ..errors import Problem

RETRY_AFTER_HEADER = {
    "Retry-After": {"description": "Seconds to wait", "schema": {"type": "string"}}
}

PROBLEM_RESPONSES = {
    401: {"model": Problem, "description": "Unauthenticated"},
    500: {"model": Problem, "description": "Internal error"},
}
