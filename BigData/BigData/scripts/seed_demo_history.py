"""Seed a DEMO account with ~30 habitual expenses and 3 unusual ones, so the app shows the
"Cảnh báo chi tiêu bất thường" screen and new receipts have enough history to be scored.

    python scripts/seed_demo_history.py --email demo@example.com --password 123456

Only for a demo account: it creates real transactions (and moves the wallet balance).
Uses only the standard library. Environment: CORE_API_URL, DEMO_EMAIL, DEMO_PASSWORD.
"""

import argparse
import json
import os
import random
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta


def post(base: str, path: str, body: dict, token: str | None = None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(base + path, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8") or "{}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.getenv("CORE_API_URL", "http://localhost:8080"))
    parser.add_argument("--email", default=os.getenv("DEMO_EMAIL", "demo@example.com"))
    parser.add_argument("--password", default=os.getenv("DEMO_PASSWORD", "123456"))
    args = parser.parse_args()
    base = args.base_url.rstrip("/")

    try:
        login = post(base, "/api/auth/login", {"email": args.email, "password": args.password})
    except urllib.error.URLError as error:
        print(f"Cannot log in to {base}: {error}")
        return 1
    data = login.get("data") or login
    token = data.get("token")
    if not token:
        print("Login response has no token")
        return 1

    rng = random.Random(7)
    now = datetime.now().replace(second=0, microsecond=0)
    items: list[tuple[datetime, str, float, str]] = []
    for day in range(1, 31):
        moment = now - timedelta(days=day)
        if day % 2 == 0 or moment.weekday() < 5:
            items.append((moment.replace(hour=12, minute=rng.randint(0, 40)), "Ăn uống", 40000 + rng.randint(0, 6) * 5000, "Ăn trưa"))
        if moment.weekday() >= 5:
            items.append((moment.replace(hour=17, minute=30), "Ăn uống", 280000 + rng.randint(0, 4) * 10000, "Đi chợ cuối tuần"))
        if day % 7 == 0:
            items.append((moment.replace(hour=8, minute=15), "Di chuyển", 70000 + rng.randint(0, 3) * 5000, "Đổ xăng"))
    # Unusual: large amount, late night, rarely used category.
    items.append(((now - timedelta(days=3)).replace(hour=23, minute=45), "Mua sắm", 2_400_000, "Mua sắm online đêm khuya"))
    items.append(((now - timedelta(days=9)).replace(hour=14, minute=5), "Ăn uống", 1_500_000, "Tiệc nhà hàng"))
    items.append(((now - timedelta(days=15)).replace(hour=2, minute=30), "Hóa đơn", 3_000_000, "Thanh toán lạ"))

    created = 0
    for moment, category, amount, note in sorted(items):
        body = {
            "amount": amount,
            "note": note,
            "location": note,
            "paymentMethod": "WALLET",
            "transactionDate": moment.isoformat(),
            "categoryName": category,
            "isExpense": True,
            "clientId": str(uuid.uuid4()),
        }
        try:
            post(base, "/api/transactions/checkout", body, token)
            created += 1
        except urllib.error.HTTPError as error:
            print(f"Skipped one transaction: HTTP {error.code}")
    print(f"Created {created}/{len(items)} transactions for {args.email} (3 are unusual).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
