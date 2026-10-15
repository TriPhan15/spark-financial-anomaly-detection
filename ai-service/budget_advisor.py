from __future__ import annotations
import math
import unicodedata
from collections import defaultdict
from datetime import date
from typing import Dict, List, Optional, Tuple
from wcm_spade import WCMSpade

ESSENTIAL = {
    "an uong", "di lai", "hoa don", "suc khoe", "giao duc",
    "nha o", "dien nuoc", "internet", "xang", "y te",
}
DISCRETIONARY = {
    "du lich", "giai tri", "mua sam", "ca phe", "an vat",
    "lam dep", "game", "shopping", "quan ao", "do choi",
}

def _norm(s: str) -> str:
    s = unicodedata.normalize("NFD", s)
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    s = s.replace("đ", "d").replace("Đ", "D")
    return s.strip().lower()

def classify(tag: str) -> str:
    n = _norm(tag)
    if n in ESSENTIAL:
        return "essential"
    if n in DISCRETIONARY:
        return "discretionary"
    return "neutral"

def _parse_date(s: str) -> Optional[date]:
    if not s:
        return None
    s = s.strip().replace("Z", "")
    try:
        return date.fromisoformat(s[:10])
    except ValueError:
        return None

def _build_sequences(exp: List[Tuple[date, str, float]]):
    by_month = defaultdict(lambda: defaultdict(set))
    price_sum = defaultdict(float)
    price_cnt = defaultdict(int)
    for d, tag, amt in exp:
        by_month[(d.year, d.month)][d.toordinal()].add(tag)
        price_sum[tag] += amt
        price_cnt[tag] += 1
    sequences = [
        [tuple(sorted(days[day])) for day in sorted(days.keys())]
        for days in by_month.values()
    ]
    prices = {t: price_sum[t] / price_cnt[t] for t in price_sum if price_cnt[t]}
    return sequences, prices, len(by_month)

def advise(
    transactions: List[dict],
    target: float = 0.0,
    current: float = 0.0,
    months_left: int = 0,
    monthly_income: Optional[float] = None,
    needed_monthly: Optional[float] = None,
    alpha: float = 0.5,
    min_sup_factor: float = 0.4,
    max_cut_ratio: float = 0.30,
) -> dict:
    exp = []
    income_total = 0.0
    n_income_months = set()
    for t in transactions:
        d = _parse_date(str(t.get("date", "")))
        amt = max(float(t.get("amount", 0) or 0), 0.0)
        if d is None:
            continue
        if t.get("isExpense", True):
            exp.append((d, str(t.get("category", "Khác")), amt))
        else:
            income_total += amt
            n_income_months.add((d.year, d.month))

    if not exp:
        return {"error": "Không có giao dịch chi để phân tích."}

    sequences, prices, n_months = _build_sequences(exp)
    engine = WCMSpade(min_sup=min_sup_factor, alpha=alpha, max_pattern_length=3)
    res = engine.mine(sequences, prices)
    trends = []
    for p in res.patterns:
        if len(p.itemsets) >= 2:
            human = " → ".join("+".join(it) for it in p.itemsets)
            trends.append({"pattern": human, "count": p.raw_sup,
                           "wsupport": round(p.wsup, 3)})
    trends = trends[:10]
    months_span = max(n_months, 1)
    tag_total = defaultdict(float)
    for _, tag, amt in exp:
        tag_total[tag] += amt
    tag_monthly = {t: v / months_span for t, v in tag_total.items()}
    total_monthly_exp = sum(tag_monthly.values()) or 1.0

    tag_stats = []
    for t, m in sorted(tag_monthly.items(), key=lambda kv: kv[1], reverse=True):
        tag_stats.append({
            "tag": t,
            "monthlyAvg": round(m),
            "share": round(m / total_monthly_exp * 100, 1),
            "weight": round(res.item_weights.get(t, 0.0), 3),
            "type": classify(t),
        })
    if needed_monthly is None:
        remaining = max(target - current, 0.0)
        needed_monthly = remaining / months_left if months_left > 0 else 0.0

    if monthly_income is None:
        monthly_income = (income_total / len(n_income_months)) if n_income_months else 0.0
    surplus = monthly_income - total_monthly_exp
    gap = needed_monthly - surplus

    remaining_gap = gap
    recommendations = []
    summary = ""

    def _fmt(val):
        return f"{int(val):,}".replace(",", ".")

    if gap <= 0:
        summary = (f"Thặng dư {_fmt(surplus)}đ/tháng ≥ cần {_fmt(needed_monthly)}đ/tháng. "
                   f"Đang đúng hướng — duy trì và đẩy phần dư vào tiết kiệm.")

        for s in tag_stats:
            if s["type"] == "discretionary" and s["share"] >= 15:
                cut = round(s["monthlyAvg"] * 0.1)
                recommendations.append({
                    "tag": s["tag"], "current": s["monthlyAvg"],
                    "suggested": s["monthlyAvg"] - cut, "delta": -cut,
                    "action": "giảm nhẹ",
                    "reason": f"Mục tùy ý chiếm {s['share']}% — cắt 10% để dư thêm.",
                })
    else:
        order = (["discretionary"], ["neutral"], ["essential"])
        for group in order:
            pool = [s for s in tag_stats if s["type"] in group and s["monthlyAvg"] > 0]
            pool_total = sum(s["monthlyAvg"] for s in pool) or 0.0
            if pool_total <= 0 or remaining_gap <= 0:
                continue

            ratio_cap = 0.10 if group == ["essential"] else max_cut_ratio
            for s in pool:
                if remaining_gap <= 0:
                    break
                max_cut = s["monthlyAvg"] * ratio_cap
                want = remaining_gap * (s["monthlyAvg"] / pool_total)
                cut = round(min(max_cut, want))
                if cut <= 0:
                    continue
                recommendations.append({
                    "tag": s["tag"], "current": s["monthlyAvg"],
                    "suggested": s["monthlyAvg"] - cut, "delta": -cut,
                    "action": "cắt giảm",
                    "reason": (f"{'Mục tùy ý' if s['type']=='discretionary' else 'Mục thiết yếu' if s['type']=='essential' else 'Mục linh hoạt'}"
                               f", chiếm {s['share']}% chi tiêu."),
                })
                remaining_gap -= cut
                
        total_cut = sum(-r["delta"] for r in recommendations)
        if remaining_gap > 0:
            summary = (f"Cần tiết kiệm {_fmt(needed_monthly)}đ/tháng, thiếu {_fmt(gap)}đ. "
                       f"Đề xuất cắt được {_fmt(total_cut)}đ, vẫn thiếu {_fmt(remaining_gap)}đ - "
                       f"cân nhắc tăng thu nhập hoặc giãn hạn mục tiêu.")
        else:
            summary = (f"Cần tiết kiệm {_fmt(needed_monthly)}đ/tháng, thiếu {_fmt(gap)}đ. "
                       f"Đề xuất cắt tổng {_fmt(total_cut)}đ từ các mục dưới → đủ đạt mục tiêu.")

    return {
        "trends": trends,
        "tagStats": tag_stats,
        "goal": {
            "neededMonthly": round(needed_monthly),
            "monthlyIncome": round(monthly_income),
            "monthlyExpense": round(total_monthly_exp),
            "surplus": round(surplus),
            "gap": round(gap),
        },
        "recommendations": recommendations,
        "summary": summary,
        "meta": {
            "nMonths": n_months,
            "effectiveMinSup": res.effective_min_sup,
            "meanW": round(res.mean_w, 4),
        },
    }