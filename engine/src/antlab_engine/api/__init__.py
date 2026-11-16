from ..errors import Problem

PROBLEM_RESPONSES = {
    401: {"model": Problem, "description": "Unauthenticated"},
    500: {"model": Problem, "description": "Internal error"},
}
