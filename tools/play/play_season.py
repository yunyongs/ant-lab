#!/usr/bin/env python3
"""Play one whole season against a locally running API (local profile) and print each step.

Standard library only. Usage: python3 tools/play/play_season.py [--base URL] [--turns 12] [--risk -0.20]
"""
import argparse
import json
import sys
import urllib.error
import urllib.request

# asset id -> share of its position bucket; ids refer to the sample pool (GET /api/v1/pool)
TEAM = {
    "GOALKEEPER": [(17, 1.0)],
    "DEFENDER": [(10, 0.4), (5, 0.3), (13, 0.3)],
    "MIDFIELDER": [(3, 0.5), (16, 0.5)],
    "FORWARD": [(1, 0.5), (7, 0.5)],
}
FORMATION = {"goalkeeper": 20, "defender": 30, "midfielder": 30, "forward": 20}
CHANGED = {"goalkeeper": 20, "defender": 20, "midfielder": 30, "forward": 30}
BUDGET_USE = 0.95  # leave room for fees and the FX spread


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, body=None, headers=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(
            self.base + path, data=data, method=method,
            headers={"Content-Type": "application/json", **(headers or {})})
        try:
            with urllib.request.urlopen(req) as resp:
                raw = resp.read()
                return resp.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as err:
            raw = err.read()
            return err.code, json.loads(raw) if raw else None


def step(title):
    print(f"\n== {title}")


def need(status, body, ok=(200, 201)):
    if status not in ok:
        sys.exit(f"예상 밖 응답 {status}: {json.dumps(body, ensure_ascii=False)}")
    return body


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8080")
    ap.add_argument("--turns", type=int, default=12, choices=[12, 24, 60])
    ap.add_argument("--risk", default="-0.20")
    args = ap.parse_args()
    api = Api(args.base)
    api_v1 = "/api/v1"

    step("풀 조회 (GET /pool)")
    pool = {a["assetId"]: a for a in need(*api.call("GET", f"{api_v1}/pool"))["assets"]}
    print(f"종목 {len(pool)}개, 예: {pool[1]['name']} → 시스템 판정 {pool[1]['systemPosition']}")

    step(f"시즌 시작 (POST /seasons) — 위험 선언 {args.risk}, {args.turns}턴")
    season = need(*api.call("POST", f"{api_v1}/seasons", {
        "riskLimit": args.risk, "formation": FORMATION, "pathLength": args.turns}))
    sid = season["id"]
    cash = int(season["initialCash"])
    print(f"시즌 {sid}, 초기 자금 {cash:,}원, 포메이션 {FORMATION}")

    step("0턴 팀 구성 (POST /seasons/{id}/orders, BUY)")
    prices = {p["assetId"]: int(p["price"])
              for p in need(*api.call("GET", f"{api_v1}/seasons/{sid}/prices?turnNo=0"))["prices"]}
    weight = {"GOALKEEPER": FORMATION["goalkeeper"], "DEFENDER": FORMATION["defender"],
              "MIDFIELDER": FORMATION["midfielder"], "FORWARD": FORMATION["forward"]}
    for bucket, assets in TEAM.items():
        for asset_id, share in assets:
            budget = cash * weight[bucket] / 100 * share * BUDGET_USE
            qty = max(1, int(budget // prices[asset_id]))
            res = need(*api.call("POST", f"{api_v1}/seasons/{sid}/orders", {
                "expectedTurnNo": 0, "side": "BUY", "assetId": asset_id, "quantity": qty,
                "declaredPosition": pool[asset_id]["systemPosition"]}))
            print(f"  {pool[asset_id]['name']:<28} {qty:>5}주 @ {int(res['fillPrice']):>9,} "
                  f"수수료 {int(res['fee']):>7,} 잔액 {int(res['cashBalance']):>12,}")

    step("진행 중 복기 요청 → 409여야 한다 (GET /review)")
    status, body = api.call("GET", f"{api_v1}/seasons/{sid}/review")
    print(f"  {status} {body.get('code')} — 본문에 날짜·사건 이름 없음: "
          f"{not any(ch.isdigit() for ch in json.dumps(body, ensure_ascii=False).split('instance')[0])}")

    step("턴 진행 (POST /turns)")
    for turn in range(args.turns):
        if turn == 4:
            hold = need(*api.call("GET", f"{api_v1}/seasons/{sid}/holdings"))["holdings"]
            top = max(hold, key=lambda h: int(h["price"]) * h["quantity"])
            sell = max(1, top["quantity"] // 2)
            res = need(*api.call("POST", f"{api_v1}/seasons/{sid}/orders", {
                "expectedTurnNo": turn, "side": "SELL", "assetId": top["assetId"], "quantity": sell}))
            print(f"  [{turn}턴] 매도 {pool[top['assetId']]['name']} {sell}주, 실현 손익 {res['realizedPnl']}")
        if turn == 6:
            res = need(*api.call("POST", f"{api_v1}/seasons/{sid}/formation-changes", {
                "expectedTurnNo": turn, "formation": CHANGED, "reason": "공격 비중을 높인다"}))
            print(f"  [{turn}턴] 전술 변경 {res['before']} → {res['after']}")
        res = need(*api.call("POST", f"{api_v1}/seasons/{sid}/turns", {"expectedTurnNo": turn}))
        print(f"  {res['turnNo']:>2}턴  총자산 {int(res['totalValue']):>12,}  수익률 {float(res['periodReturn']):+.2%}  "
              f"낙폭 {float(res['drawdown']):+.2%}  위험 초과 {res['riskBreached']}  상태 {res['status']}")

    step("대사 (개발용 POST /dev/seasons/{id}/reconciliation)")
    status, body = api.call("POST", f"{api_v1}/dev/seasons/{sid}/reconciliation",
                            headers={"X-Antlab-Dev": "true"})
    if status == 200:
        print("  " + json.dumps(body, ensure_ascii=False)[:300] + " …")
    else:
        print(f"  건너뜀 ({status}) — local 프로필·127.0.0.1에서만 열린다")

    step("성적표 (GET /report)")
    rep = need(*api.call("GET", f"{api_v1}/seasons/{sid}/report"))
    for key, value in rep.items():
        print(f"  {key}: {value}")

    step("복기 (GET /review) — 끝나서 날짜가 공개된다")
    rev = need(*api.call("GET", f"{api_v1}/seasons/{sid}/review"))
    print({k: v for k, v in rev.items() if k not in ("turns", "formationChanges")})
    for t in rev["turns"]:
        events = ", ".join(e["title"] for e in t["events"]) or "-"
        print(f"  {t['turnNo']:>2}턴 = {t['yearMonth']}  사건: {events}")
    print(f"  전술 변경 기록: {rev['formationChanges']}")
    print(f"\n끝. 시즌 번호 {sid}")


if __name__ == "__main__":
    main()
