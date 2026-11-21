import io
import json
import zipfile
from datetime import date
from decimal import Decimal

import pytest

import proxy_sources as ps
from proxy_sources import (AuxBudgetExhausted, AuxClient, AuxError, ForbiddenRequest, check_ecos, check_fred, check_wb, read_ecos_csv, read_fred_csv,
                           read_pink_sheet)
from tests.fakes import FakeClock, FakeSession

KEY = "FAKEECOSKEY0123456789"  # shape only, not a real key


class R:
    def __init__(self, body, status=200):
        self.content = body.encode() if isinstance(body, str) else body
        self.status_code = status


def client(tmp_path, handler, **kw):
    s = FakeSession(lambda method, url, k: handler(url, k))
    return AuxClient(s, tmp_path, clock=FakeClock(), ecos_key=kw.pop("key", KEY), **kw), s


FRED_OK = "observation_date,DGS10\n1995-12-29,5.71\n1996-01-02,.\n1996-01-03,5.65\n"


def test_fred_csv_is_cached_and_dots_are_skipped(tmp_path):
    c, s = client(tmp_path, lambda url, k: R(FRED_OK))
    f = c.fred_csv("DGS10")
    assert read_fred_csv(f, "DGS10") == [(date(1995, 12, 29), Decimal("5.71")), (date(1996, 1, 3), Decimal("5.65"))]
    assert s.calls[0]["url"] == "https://fred.stlouisfed.org/graph/fredgraph.csv" and s.calls[0]["params"] == {"id": "DGS10"}
    assert s.calls[0]["allow_redirects"] is False
    c.fred_csv("DGS10")
    assert c.requests_made["FRED"] == 1 and c.cache_hits == 1


def test_fred_html_answer_is_not_cached_as_a_series(tmp_path):
    c, _ = client(tmp_path, lambda url, k: R("<html>maintenance</html>"))
    with pytest.raises(AuxError):
        c.fred_csv("DGS10")
    assert not (tmp_path / "fred" / "DGS10.csv").exists()


def test_redirect_and_error_status_stop(tmp_path):
    for status in (301, 404, 500):
        c, _ = client(tmp_path, lambda url, k, st=status: R("", st))
        with pytest.raises(AuxError, match=f"HTTP {status}"):
            c.fred_csv("DGS10")


def test_interval_between_calls_and_request_cap(tmp_path):
    c, _ = client(tmp_path, lambda url, k: R(FRED_OK.replace("DGS10", k["params"]["id"])), max_requests={"FRED": 2, "ECOS": 0, "WB": 0})
    c.fred_csv("DGS10")
    c.fred_csv("DGS2")
    assert c.clock.sleeps and c.clock.sleeps[0] == pytest.approx(2.0)
    with pytest.raises(AuxBudgetExhausted):
        c.fred_csv("DTB3")
    assert c.requests_made["FRED"] == 2  # the third request never left


def test_allow_lists_refuse_everything_else():
    check_fred("GET", "https://fred.stlouisfed.org/graph/fredgraph.csv", {"id": "DGS10"})
    for url, params in [("https://fred.stlouisfed.org/graph/fredgraph.csv", {"id": "dgs10"}), ("https://fred.stlouisfed.org/graph/fredgraph.csv", {"id": "A", "x": "1"}),
                        ("https://fred.stlouisfed.org/fred/series/observations", {"id": "A"}), ("https://evil.example/graph/fredgraph.csv", {"id": "A"})]:
        with pytest.raises(ForbiddenRequest):
            check_fred("GET", url, params)
    with pytest.raises(ForbiddenRequest):
        check_fred("POST", "https://fred.stlouisfed.org/graph/fredgraph.csv", {"id": "A"})
    base = f"https://ecos.bok.or.kr/api/StatisticSearch/{KEY}/json/kr/1/100/802Y001/D/19960101/19960131/0001000"
    check_ecos("GET", base)
    check_ecos("GET", f"https://ecos.bok.or.kr/api/StatisticItemList/{KEY}/json/kr/1/1000/802Y001")
    for bad in [base + "?x=1", base.replace("19960131", "199601"), base.replace("StatisticSearch", "StatisticSearchX"), base.replace("ecos.bok.or.kr", "ecos.bok.or.kr.evil.example"),
                f"https://ecos.bok.or.kr/api/KeyStatisticList/{KEY}/json/kr/1/10"]:
        with pytest.raises(ForbiddenRequest) as e:
            check_ecos("GET", bad)
        assert KEY not in str(e.value)
    with pytest.raises(ForbiddenRequest):
        check_ecos("POST", base)
    check_wb("GET", ps.WB_BASE + ps.WB_FILE_PATH)
    for bad in ["https://thedocs.worldbank.org/en/doc/abc/related/CMO-Historical-Data-Monthly.xlsx", "https://example.org" + ps.WB_FILE_PATH]:
        with pytest.raises(ForbiddenRequest):
            check_wb("GET", bad)


def _ecos_rows(stat, item, times, total=None):
    return json.dumps({"StatisticSearch": {"list_total_count": total if total is not None else len(times), "row": [
        {"STAT_CODE": stat, "ITEM_CODE1": item, "TIME": t, "DATA_VALUE": v} for t, v in times]}})


def test_ecos_series_pages_and_writes_only_when_complete(tmp_path, monkeypatch):
    monkeypatch.setattr(ps, "ECOS_PAGE_ROWS", 2)
    pages = {1: [("19960103", "1.5"), ("19960104", "1.6")], 3: [("19960105", "1.7")]}

    def handler(url, k):
        first = int(url.split("/json/kr/")[1].split("/")[0])
        return R(_ecos_rows("802Y001", "0001000", pages[first], total=3))

    c, s = client(tmp_path, handler)
    f = c.ecos_series("802Y001", "0001000", "D", "19960101", "19960131")
    assert read_ecos_csv(f) == [("19960103", Decimal("1.5")), ("19960104", Decimal("1.6")), ("19960105", Decimal("1.7"))]
    assert c.requests_made["ECOS"] == 2
    c.ecos_series("802Y001", "0001000", "D", "19960101", "19960131")
    assert c.requests_made["ECOS"] == 2


def test_ecos_failures_leave_no_file_and_never_show_the_key(tmp_path):
    def boom(url, k):
        raise RuntimeError(f"connection failed for {url}")

    c, _ = client(tmp_path, boom)
    with pytest.raises(AuxError) as e:
        c.ecos_series("802Y001", "0001000", "D", "19960101", "19960131")
    assert KEY not in str(e.value) and "http" not in str(e.value)

    for body in [json.dumps({"RESULT": {"CODE": "INFO-200", "MESSAGE": "no data"}}), json.dumps({"RESULT": {"CODE": "INFO-100", "MESSAGE": f"bad key {KEY}"}}),
                 _ecos_rows("802Y001", "9999999", [("19960103", "1")]), "not json", _ecos_rows("802Y001", "0001000", [("19960103", "abc")])]:
        c, _ = client(tmp_path, lambda url, k, b=body: R(b))
        with pytest.raises(AuxError) as e:
            c.ecos_series("802Y001", "0001000", "D", "19960101", "19960131")
        assert KEY not in str(e.value)
    assert not list((tmp_path / "ecos").glob("*.csv")) if (tmp_path / "ecos").exists() else True


def test_ecos_needs_a_well_formed_key_and_arguments(tmp_path):
    c, s = client(tmp_path, lambda url, k: R("{}"), key="short")
    with pytest.raises(AuxError, match="key"):
        c.ecos_items("802Y001")
    assert not s.calls
    c, s = client(tmp_path, lambda url, k: R("{}"))
    for args in [("802y001", "0001000", "D", "19960101", "19960131"), ("802Y001", "000/1", "D", "19960101", "19960131"),
                 ("802Y001", "0001000", "D", "199601", "19960131"), ("802Y001", "0001000", "Q", "19960101", "19960131"), ("802Y001", "0001000", "D", "19960201", "19960131")]:
        with pytest.raises(AuxError):
            c.ecos_series(*args)
    assert not s.calls


def test_ecos_item_list(tmp_path):
    body = json.dumps({"StatisticItemList": {"list_total_count": 1, "row": [{"ITEM_CODE": "0001000", "ITEM_NAME": "KOSPI", "CYCLE": "D", "START_TIME": "19950103", "END_TIME": "20261120", "DATA_CNT": "8060"}]}})
    c, _ = client(tmp_path, lambda url, k: R(body))
    items = ps.read_ecos_items(c.ecos_items("802Y001"))
    assert items[0]["START_TIME"] == "19950103"
    c.ecos_items("802Y001")
    assert c.requests_made["ECOS"] == 1


def _xlsx(path, header_gold="Gold", rows=(("1996M01", "399.0", "2616"), ("1996M02", "405", "…"))):
    ns = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"'
    strings = ["Gold", "Copper", "Crude oil, WTI", "1996M01", "1996M02", "…", "Other"]
    idx = {s: i for i, s in enumerate(strings)}

    def cell(ref, v):
        return f'<c r="{ref}" t="s"><v>{idx[v]}</v></c>' if v in idx else f'<c r="{ref}"><v>{v}</v></c>'

    sheet_rows = ['<row r="5">' + cell("B5", header_gold) + cell("C5", "Copper") + "</row>"]
    for n, (m, g, c) in enumerate(rows, start=7):
        sheet_rows.append(f'<row r="{n}">' + cell(f"A{n}", m) + cell(f"B{n}", g) + cell(f"C{n}", c) + "</row>")
    files = {
        "xl/workbook.xml": f'<workbook {ns} xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Description" sheetId="1" r:id="rId1"/><sheet name="Monthly Prices" sheetId="2" r:id="rId2"/></sheets></workbook>',
        "xl/_rels/workbook.xml.rels": '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Target="worksheets/sheet2.xml"/></Relationships>',
        "xl/sharedStrings.xml": f"<sst {ns}>" + "".join(f"<si><t>{s}</t></si>" for s in strings) + "</sst>",
        "xl/worksheets/sheet1.xml": f"<worksheet {ns}><sheetData/></worksheet>",
        "xl/worksheets/sheet2.xml": f"<worksheet {ns}><sheetData>" + "".join(sheet_rows) + "</sheetData></worksheet>",
    }
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for k, v in files.items():
            z.writestr(k, v)
    path.write_bytes(buf.getvalue())
    return path


def test_pink_sheet_reads_months_by_exact_title_and_rounds_float_text(tmp_path):
    f = _xlsx(tmp_path / "w.xlsx", rows=(("1996M01", "399.00000000000006", "2616"), ("1996M02", "405", "…")))
    out = read_pink_sheet(f, ["Gold", "Copper"])
    assert out["Gold"] == {"1996-01": Decimal("399.0000"), "1996-02": Decimal("405.0000")}
    assert out["Copper"] == {"1996-01": Decimal("2616.0000")}  # the dots-for-missing row is skipped, not zero
    with pytest.raises(AuxError):
        read_pink_sheet(f, ["Silver"])


def test_pink_sheet_rejects_a_workbook_without_the_sheet(tmp_path):
    f = tmp_path / "bad.xlsx"
    with zipfile.ZipFile(f, "w") as z:
        z.writestr("xl/workbook.xml", '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheets/></workbook>')
    c, _ = client(tmp_path, lambda url, k: R(f.read_bytes()))
    with pytest.raises(AuxError):
        c.wb_pink_sheet()
    assert not (tmp_path / "wb" / "CMO-Historical-Data-Monthly.xlsx").exists()


def test_wb_download_is_cached(tmp_path):
    src = _xlsx(tmp_path / "src.xlsx")
    c, s = client(tmp_path / "raw", lambda url, k: R(src.read_bytes()))
    c.wb_pink_sheet()
    c.wb_pink_sheet()
    assert len(s.calls) == 1 and s.calls[0]["url"].startswith("https://thedocs.worldbank.org/")


def test_periods_and_spans():
    assert ps.month_of("19960103") == "1996-01" and ps.month_of("199601") == "1996-01" and ps.month_of("1996-01-03") == "1996-01"
    assert ps.span(["1996-03", "1996-01"]) == ("1996-01", "1996-03")
    with pytest.raises(ValueError):
        ps.month_of("96-1")
