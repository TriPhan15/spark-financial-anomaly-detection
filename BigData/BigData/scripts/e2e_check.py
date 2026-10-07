"""End-to-end checks of the BigData API (Model A and Model B) against a running server.

    python scripts/e2e_check.py --base-url http://127.0.0.1:8001

Besides contract checks, it replays the 30 test transactions exported by train_behavior.py
(artifacts/behavior/parity_sample.json): the API receives the raw history, recomputes the features
in Python and must reproduce the features and the probability that Spark computed during training.
Uses only the standard library.
"""

import argparse
import csv
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
results: list[tuple[str, bool, str]] = []


def call(base: str, method: str, path: str, body: dict | None = None) -> tuple[int, dict, float]:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(
        base + path, data=data, method=method, headers={"Content-Type": "application/json"}
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.loads(response.read().decode("utf-8"))
            status = response.status
    except urllib.error.HTTPError as error:
        payload = json.loads(error.read().decode("utf-8") or "{}")
        status = error.code
    return status, payload, time.perf_counter() - started


def check(name: str, ok: bool, detail: str = "") -> None:
    results.append((name, ok, detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}{' - ' + detail if detail else ''}")


def iso(value: datetime) -> str:
    return value.strftime("%Y-%m-%dT%H:%M:%S")


def habit_history(now: datetime, days: int = 30) -> list[dict]:
    """A user who buys lunch (~50k VND) every day and groceries (~300k VND) every weekend."""
    history = []
    for day in range(1, days + 1):
        moment = now - timedelta(days=day)
        history.append({"amount": 45000 + (day % 5) * 3000, "occurredAt": iso(moment.replace(hour=12, minute=10)), "category": "Ăn uống"})
        if moment.weekday() >= 5:
            history.append({"amount": 280000 + (day % 3) * 20000, "occurredAt": iso(moment.replace(hour=17, minute=30)), "category": "Đi chợ"})
    return history


def model_a_checks(base: str) -> None:
    csv_path = ROOT / "creditcard.csv"
    if not csv_path.is_file():
        check("Model A real fraud row", False, "creditcard.csv not found")
        return
    with csv_path.open(encoding="utf-8") as handle:
        fraud_rows = [row for row in csv.DictReader(handle) if row["Class"] == "1"][:20]
    flagged = 0
    for row in fraud_rows:
        status, body, _ = call(base, "POST", "/predict-fraud", {
            "time": float(row["Time"]),
            "amount": float(row["Amount"]),
            "vFeatures": [float(row[f"V{i}"]) for i in range(1, 29)],
        })
        flagged += status == 200 and body.get("is_fraud") is True
    check("Model A flags real Kaggle fraud rows", flagged >= 15, f"{flagged}/{len(fraud_rows)} of the first fraud rows flagged")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8001")
    parser.add_argument("--parity", default=str(ROOT / "artifacts" / "behavior" / "parity_sample.json"))
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    parity_path = Path(args.parity)

    status, health, _ = call(base, "GET", "/health")
    check("health", status == 200 and health.get("modelLoaded") and health.get("behaviorModelLoaded"), json.dumps(health))

    model_a_checks(base)

    now = datetime(2026, 10, 6, 12, 15)
    history = habit_history(now)

    status, _, _ = call(base, "POST", "/v2/predict-anomaly", {"current": {"amount": 50000, "occurredAt": iso(now), "category": "Ăn uống"}, "history": history, "debug": True})
    check("422 on unknown field", status == 422)
    status, _, _ = call(base, "POST", "/v2/predict-anomaly", {"current": {"amount": -5, "occurredAt": iso(now), "category": "Ăn uống"}, "history": history})
    check("422 on negative amount", status == 422)
    status, _, _ = call(base, "POST", "/v2/predict-anomaly", {"current": {"amount": 50000, "occurredAt": iso(now), "category": "Ăn uống"}, "history": (history * 6)[:201]})
    check("422 on more than 200 history items", status == 422)

    status, body, _ = call(base, "POST", "/v2/predict-anomaly", {"current": {"amount": 50000, "occurredAt": iso(now), "category": "Ăn uống"}, "history": history[:4]})
    check("cold start (4 earlier transactions) is not scored", status == 200 and body["scored"] is False and body["reason"] == "insufficient_history", json.dumps(body, ensure_ascii=False))

    future_only = [{**item, "occurredAt": iso(now + timedelta(days=1))} for item in history]
    status, body, _ = call(base, "POST", "/v2/predict-anomaly", {"current": {"amount": 50000, "occurredAt": iso(now), "category": "Ăn uống"}, "history": future_only})
    check("history after the receipt date is ignored", status == 200 and body["scored"] is False)

    status, body, elapsed = call(base, "POST", "/v2/predict-anomaly", {"current": {"amount": 52000, "occurredAt": iso(now), "category": "Ăn uống"}, "history": history})
    check("usual lunch is not an anomaly", status == 200 and body["scored"] and body["isAnomaly"] is False, f"risk={body.get('riskScore')} threshold={body.get('threshold')}")
    check("latency under the app's 3 s timeout", elapsed < 3, f"{elapsed * 1000:.0f} ms")
    check("Vietnamese category mapped (Ăn uống -> food_dining = 1)", body.get("features", {}).get("category_idx") == 1.0)

    late_night = now.replace(hour=23, minute=40)
    status, body, _ = call(base, "POST", "/v2/predict-anomaly", {"current": {"amount": 1_200_000, "occurredAt": iso(late_night), "category": "Mua sắm online"}, "history": history})
    check(
        "late-night online purchase ~20x the usual spend is an anomaly with an explanation",
        status == 200 and body["isAnomaly"] is True and bool(body["explanation"]),
        f"risk={body.get('riskScore')} explanation={body.get('explanation')}",
    )

    if not parity_path.is_file():
        check("parity replay", False, f"{parity_path} not found")
    else:
        samples = json.loads(parity_path.read_text(encoding="utf-8"))
        feature_mismatch, score_mismatch, matrix = [], [], {"tp": 0, "fp": 0, "fn": 0, "tn": 0}
        for sample in samples:
            status, body, _ = call(base, "POST", "/v2/predict-anomaly", {"current": sample["current"], "history": sample["history"]})
            if status != 200 or not body.get("scored"):
                feature_mismatch.append(sample["transNum"])
                continue
            for name, expected in sample["expectedFeatures"].items():
                actual = body["features"][name]
                if abs(actual - expected) > 1e-6 * max(1.0, abs(expected)):
                    feature_mismatch.append(f"{sample['transNum']}:{name} {actual} != {expected}")
            if abs(body["riskScore"] - sample["expectedScore"]) > 2e-6:
                score_mismatch.append(f"{sample['transNum']}: {body['riskScore']} != {sample['expectedScore']:.6f}")
            key = ("t" if body["isAnomaly"] == bool(sample["label"]) else "f") + ("p" if body["isAnomaly"] else "n")
            matrix[key] += 1
        check(f"parity: Spark and API features match on {len(samples)} test transactions", not feature_mismatch, "; ".join(feature_mismatch[:5]))
        check(f"parity: Spark and API probabilities match on {len(samples)} test transactions", not score_mismatch, "; ".join(score_mismatch[:5]))
        print(f"       replayed sample (15 fraud + 15 normal): {matrix}")

    failed = [name for name, ok, _ in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
