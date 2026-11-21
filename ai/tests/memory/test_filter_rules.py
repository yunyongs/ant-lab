import json
import unicodedata

import pytest

from antlab_ai.memory.filter import (
    NOTE_TOTAL_MAX,
    REFLECTION_TOTAL_MAX,
    Hit,
    check_note,
    check_reflection,
    compact,
    load_terms,
    normalize,
    reflection_strings,
    scan_text,
    split_sentences,
)


def codes(text, terms):
    return [hit.code for hit in scan_text(text, "/x", terms)]


def test_pointer_names_the_sentence(terms):
    hits = scan_text("분산을 지킨다. 2008년에 줄였다. 다시 지킨다", "/good/1", terms)
    assert hits == [Hit("/good/1#s2", "YEAR")]


def test_decimal_point_and_month_abbreviation_do_not_split():
    assert split_sentences("수익률 1.5%였다. 다음") == ["수익률 1.5%였다", " 다음"]
    assert split_sentences("Sep. 2008") == ["sep 2008"]


def test_codes_come_in_fixed_order(terms):
    assert codes("리먼 때 2008년 -20%, +8%, +6%에 54,000원", terms) == [
        "YEAR",
        "EVENT_TERM",
        "FINGERPRINT",
        "PRICE",
    ]


def test_fullwidth_digits_are_caught_only_through_nfkc(terms):
    text = "２００８년"
    assert normalize(text) == "2008년"
    assert codes(text, terms) == ["YEAR"]


def test_turn_label_without_return_passes_and_return_without_label_passes(terms):
    assert codes("1년차에는 현금을 늘린다", terms) == []
    assert codes("낙폭이 -12%를 넘으면 줄인다", terms) == []
    assert codes("1년차 7월에 -12%", terms) == ["TURN_RETURN"]


def test_fingerprint_counts_over_the_string_and_reports_once(terms):
    assert codes("-20%, +8%", terms) == []
    assert codes("-20%, +8%, +6%", terms) == ["FINGERPRINT"]
    hits = scan_text("-20%. +8%. +6%. -3%.", "/x", terms)
    assert hits == [Hit("/x#s3", "FINGERPRINT")]


def test_dictionary_terms_need_a_word_start(terms):
    assert codes("관리 먼저", terms) == []
    assert codes("리 먼 사태", terms) == ["EVENT_TERM"]
    assert codes("trumpet", terms) == []
    assert codes("trump", terms) == ["EVENT_TERM"]


def test_digits_are_folded_to_ascii_and_dashes_to_minus():
    assert normalize("٢٠٠٨ २००८ ２００８") == "2008 2008 2008"
    assert normalize("−1 –2 ‒3") == "-1 -2 -3"
    assert compact("〇 가") == "가"


def test_blank_segments_do_not_take_a_sentence_number(terms):
    assert scan_text("\n\n 2008년", "/x", terms) == [Hit("/x#s1", "YEAR")]
    assert scan_text("a.. 2008년", "/x", terms) == [Hit("/x#s2", "YEAR")]


def test_code_points_count_once_each(terms):
    line = "가" * 99 + "😀"
    doc = {"good": [line] * 3, "mistakes": [{"type": "OTHER", "text": line}] * 2}
    assert check_reflection(doc, terms).ok
    doc["ruleCandidates"] = [line]
    assert check_reflection(doc, terms).ok
    doc["ruleCandidates"] = [line, "가"]
    assert check_reflection(doc, terms).oversize


def test_strings_are_measured_after_nfc():
    decomposed = unicodedata.normalize("NFD", "가" * 100)
    assert len(decomposed) == 200
    (_, text), *_ = list(reflection_strings({"good": [decomposed]}))
    assert text == "가" * 100


def test_terms_file_bom_is_ignored(tmp_path):
    path = tmp_path / "t.txt"
    path.write_bytes("\ufeff# comment\nfoo\n".encode())
    assert load_terms(path, None).terms == ("foo",)


def test_verdict_never_contains_the_offending_text(terms):
    leak = "리먼 사태 2008년 54,000원 -20%, +8%, +6%"
    doc = {
        "schema": "season-reflection/1",
        "good": [leak, "정상", "정상"],
        "mistakes": [],
        "ruleCandidates": [],
    }
    verdict = check_reflection(doc, terms)
    assert verdict.hits
    rendered = repr(verdict) + str(verdict)
    for fragment in ("리먼", "2008", "54,000", "-20%"):
        assert fragment not in rendered
    assert {type(value) for hit in verdict.hits for value in (hit.pointer, hit.code)} == {str}


def test_reflection_cap_is_600_characters_after_nfc(terms):
    line = "가" * 100
    at_cap = {"good": [line] * 3, "mistakes": [{"type": "OTHER", "text": line}] * 3}
    assert check_reflection(at_cap, terms).ok
    over = {"good": [line] * 3, "mistakes": [{"type": "OTHER", "text": line}] * 3}
    over["ruleCandidates"] = ["가"]
    assert check_reflection(over, terms).oversize
    assert REFLECTION_TOTAL_MAX == 600


def test_note_cap_is_2000_characters(terms):
    rules = ["가" * 150] * 13 + ["가" * 50]
    assert check_note({"rules": rules}, terms).ok
    assert check_note({"rules": rules + ["가"]}, terms).oversize
    assert NOTE_TOTAL_MAX == 2000


def test_compact_ignores_spacing_punctuation_and_case():
    assert compact("Dot-Com  닷 컴!") == "dotcom닷컴"


def test_terms_file_edits_change_the_version_and_titles_need_four_characters(tmp_path):
    first = tmp_path / "a.txt"
    first.write_text("# header\n\nfoo bar\n", encoding="utf-8")
    second = tmp_path / "b.txt"
    second.write_text("# header\n\nfoo baz\n", encoding="utf-8")
    one = load_terms(first, None, extra_titles=["abcdef", "abcd efgh"])
    two = load_terms(second, None)
    assert one.terms == ("foobar",)
    assert one.titles == ("abcdefgh",)
    assert one.version != two.version and one.version.startswith("mf1-")
    assert len(one.version) == len("mf1-") + 12


def test_manifest_titles_are_matched_after_normalisation(terms, manifest):
    title = manifest["titles"][0]
    assert codes(title.upper().replace(" ", "  ") + "처럼", terms) == ["EVENT_TERM"]


@pytest.mark.parametrize(
    "text",
    ["", "   ", "\n"],
)
def test_empty_text_has_no_hits(text, terms):
    assert scan_text(text, "/x", terms) == []


def test_json_pointer_of_documents_matches_the_schema_shape(terms):
    doc = json.loads('{"good":["a","b","c"],"mistakes":[{"type":"OTHER","text":"2008"}]}')
    assert [h.pointer for h in check_reflection(doc, terms).hits] == ["/mistakes/0/text#s1"]
