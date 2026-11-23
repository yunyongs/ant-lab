import inspect
import re

import yaml
from conftest import CONTRACT_PATH, ENGINE_DIR

from antlab_engine import engine_errors
from antlab_engine.engine_errors import EngineError
from antlab_engine.errors import _DOMAIN_STATUS, ErrorCode

JAVA_ROOT = ENGINE_DIR.parent / "src" / "main" / "java" / "antlab"
JAVA_CODES = JAVA_ROOT / "common" / "error" / "ErrorCode.java"
JAVA_MAPPING = JAVA_ROOT / "api" / "error" / "ErrorHttpMapping.java"

# Engine problem codes that the public Java ErrorCode does not have: Spring's EngineClient (M2-06)
# turns them into its own codes, so they must stay out of the public enum until it is decided.
ENGINE_ONLY = {
    "POOL_NOT_FOUND",
    "HISTORY_UNAVAILABLE",
    "ENGINE_BUSY",
    "REGIME_DATA_INVALID",
    "REGIME_LABELS_MISSING",
    "REFERENCE_MISMATCH",
    "FEED_INPUT",
    "NO_REFERENCE",
}
# A domain failure that is answered as INTERNAL_ERROR so that no role fact reaches the caller.
HIDDEN = {"UNSAFE_DB_ROLE"}
STATUS_NUMBERS = {
    "BAD_REQUEST": 400,
    "NOT_FOUND": 404,
    "UNPROCESSABLE_ENTITY": 422,
    "SERVICE_UNAVAILABLE": 503,
}


def enum_constants(source: str) -> set[str]:
    """Constant names of a Java enum: comments are removed, a name may carry arguments or a tail."""
    text = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    body = text[text.index("{") + 1 : text.rindex("}")]
    names = set()
    for line in body.splitlines():
        match = re.match(r"^\s*([A-Z][A-Z0-9_]*)\s*(?:[,;(]|$)", line)
        if match:
            names.add(match.group(1))
    return names


def test_the_enum_reader_survives_comments_arguments_and_digits():
    source = """
    public enum Sample {
        /** doc */
        PLAIN,
        TAILED, // note
        WITH_ARGS(1, "x"),
        V2_CODE, /* inline */ LAST;
        private final int n = 0;
    }
    """
    assert enum_constants(source) >= {"PLAIN", "TAILED", "WITH_ARGS", "V2_CODE"}


def java_codes() -> set[str]:
    assert JAVA_CODES.is_file(), f"the Java error codes are missing: {JAVA_CODES}"
    return enum_constants(JAVA_CODES.read_text(encoding="utf-8"))


def java_statuses() -> dict[str, str]:
    assert JAVA_MAPPING.is_file(), f"the Java status mapping is missing: {JAVA_MAPPING}"
    return dict(
        re.findall(r"case ([A-Z_]+) -> HttpStatus\.([A-Z_]+);", JAVA_MAPPING.read_text("utf-8"))
    )


def domain_codes() -> set[str]:
    return {
        cls.code
        for _, cls in inspect.getmembers(engine_errors, inspect.isclass)
        if issubclass(cls, EngineError)
    }


def test_the_java_reader_finds_the_enum():
    codes = java_codes()
    assert {"INVALID_ARGUMENT", "PATH_TOO_LONG", "INTERNAL_ERROR", "AGENT_RETIRED"} <= codes
    assert java_statuses()["PATH_TOO_LONG"] == "UNPROCESSABLE_ENTITY"


def test_every_domain_failure_is_a_contract_code_or_hidden():
    contract = set(
        yaml.safe_load(CONTRACT_PATH.read_text("utf-8"))["components"]["schemas"]["ErrorCode"][
            "enum"
        ]
    )
    assert contract == {c.value for c in ErrorCode}
    for code in domain_codes() - {EngineError.code}:
        assert code in contract or code in HIDDEN, code
    assert domain_codes() >= HIDDEN
    assert not HIDDEN & contract


def test_domain_status_covers_exactly_the_contract_domain_codes():
    domain = domain_codes() - HIDDEN - {EngineError.code}
    assert set(_DOMAIN_STATUS) == domain


def test_shared_codes_exist_in_java_and_use_its_status():
    java = java_codes()
    statuses = java_statuses()
    for code, status in _DOMAIN_STATUS.items():
        if code in ENGINE_ONLY:
            continue
        assert code in java, code
        assert STATUS_NUMBERS[statuses[code]] == status, code


def test_engine_only_codes_stay_out_of_the_public_enum():
    assert not ENGINE_ONLY & java_codes()
    assert {c.value for c in ErrorCode} >= ENGINE_ONLY
    assert {"UNAUTHENTICATED", "NOT_FOUND", "METHOD_NOT_ALLOWED", "INTERNAL_ERROR"} <= java_codes()
