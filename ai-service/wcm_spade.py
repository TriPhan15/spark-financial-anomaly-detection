from __future__ import annotations
import math
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Dict, List, Tuple, Hashable, Optional
Item = Hashable
IDList = Dict[int, List[int]]

@dataclass
class Pattern:
    itemsets: List[List[Item]]
    idlist: IDList
    raw_sup: int
    wsup: float = 0.0

    def last_item(self) -> Item:
        return self.itemsets[-1][-1]

    def to_str(self) -> str:
        return " → ".join("(" + ",".join(str(i) for i in it) + ")" for it in self.itemsets)

    def flat_items(self) -> List[List[Item]]:
        return self.itemsets

@dataclass
class WCMSpadeResult:
    patterns: List[Pattern]
    n_sequences: int
    min_sup_abs: int
    effective_min_sup: int
    mean_w: float
    item_weights: Dict[Item, float] = field(default_factory=dict)

class WCMSpade:
    def __init__(
        self,
        min_sup: float = 0.02,
        alpha: float = 0.5,
        max_pattern_length: int = 5,
        max_items: Optional[int] = None,
        max_patterns: int = 50000,
    ): 
        self.min_sup = min_sup
        self.alpha = alpha
        self.max_pattern_length = max_pattern_length
        self.max_items = max_items
        self.max_patterns = max_patterns

        self.N = 0
        self.min_sup_abs = 0
        self.mean_w = 1.0
        self.effective_min_sup = 0
        self.item_weights: Dict[Item, float] = {}
        self.cooc_after: Dict[Item, Dict[Item, int]] = defaultdict(lambda: defaultdict(int))
        self.cooc_equals: Dict[Item, Dict[Item, int]] = defaultdict(lambda: defaultdict(int))
        self.item_idlists: Dict[Item, IDList] = {}

    def _build_item_idlists(
        self, sequences: List[List[Tuple[Item, ...]]], item_prices: Dict[Item, float]
    ):
        idlists: Dict[Item, IDList] = defaultdict(lambda: defaultdict(list))
        for sid, seq in enumerate(sequences):
            for eid, itemset in enumerate(seq):
                for item in itemset:
                    idlists[item][sid].append(eid)

        support = {item: len(d) for item, d in idlists.items()}
        frequent = {i: s for i, s in support.items() if s >= self.min_sup_abs}
        if self.max_items is not None and len(frequent) > self.max_items:
            top = sorted(frequent.items(), key=lambda kv: kv[1], reverse=True)[: self.max_items]
            frequent = dict(top)

        self.item_idlists = {i: dict(idlists[i]) for i in frequent}
        return frequent, support

    def _compute_weights(self, frequent: Dict[Item, int], item_prices: Dict[Item, float]):
        max_sup = max(frequent.values())
        mean_price = {i: item_prices.get(i, 0.0) for i in frequent}
        max_mean_price = max(mean_price.values()) if mean_price else 0.0
        if max_mean_price <= 0:
            max_mean_price = 1.0

        weights = {}
        for i, sup in frequent.items():
            w_freq = sup / max_sup
            w_value = mean_price[i] / max_mean_price
            weights[i] = self.alpha * w_freq + (1 - self.alpha) * w_value

        self.item_weights = weights
        min_w = min(weights.values())
        max_w = max(weights.values())
        self.mean_w = (max_w + min_w) / 2.0
        self.effective_min_sup = max(1, math.ceil(self.min_sup_abs * self.mean_w))

    def _build_cooc_maps(self, sequences: List[List[Tuple[Item, ...]]]):
        valid = set(self.item_idlists.keys())
        for seq in sequences:
            seen_after: set = set()
            counted_after: set = set()
            counted_equals: set = set()
            for itemset in seq:
                items = [it for it in itemset if it in valid]

                for a in items:
                    for b in items:
                        if a != b and (a, b) not in counted_equals:
                            self.cooc_equals[a][b] += 1
                            counted_equals.add((a, b))

                for a in seen_after:
                    for b in items:
                        if a != b and (a, b) not in counted_after:
                            self.cooc_after[a][b] += 1
                            counted_after.add((a, b))
                seen_after.update(items)

    @staticmethod
    def _s_extend(p_idlist: IDList, x_idlist: IDList) -> IDList:
        res: IDList = {}
        for sid, p_eids in p_idlist.items():
            x_eids = x_idlist.get(sid)
            if not x_eids:
                continue
            min_p = p_eids[0]
            kept = [e for e in x_eids if e > min_p]
            if kept:
                res[sid] = kept
        return res

    @staticmethod
    def _i_extend(p_idlist: IDList, x_idlist: IDList) -> IDList:
        res: IDList = {}
        for sid, p_eids in p_idlist.items():
            x_eids = x_idlist.get(sid)
            if not x_eids:
                continue
            xs = set(x_eids)
            kept = [e for e in p_eids if e in xs]
            if kept:
                res[sid] = kept
        return res

    def _extend(self, pattern: Pattern, out: List[Pattern]):
        if len(out) >= self.max_patterns:
            return
        if sum(len(it) for it in pattern.itemsets) >= self.max_pattern_length:
            return
        last = pattern.last_item()
        last_itemset = pattern.itemsets[-1]

        for y, y_idlist in self.item_idlists.items():
            if self.cooc_after[last].get(y, 0) < self.effective_min_sup:
                continue
            new_idlist = self._s_extend(pattern.idlist, y_idlist)
            sup = len(new_idlist)
            if sup >= self.effective_min_sup:
                child = Pattern(
                    itemsets=[list(it) for it in pattern.itemsets] + [[y]],
                    idlist=new_idlist,
                    raw_sup=sup,
                    wsup=sup * self.mean_w,
                )
                out.append(child)
                self._extend(child, out)
                if len(out) >= self.max_patterns:
                    return

        for y, y_idlist in self.item_idlists.items():
            if y <= last:
                continue
            if self.cooc_equals[last].get(y, 0) < self.effective_min_sup:
                continue
            new_idlist = self._i_extend(pattern.idlist, y_idlist)
            sup = len(new_idlist)
            if sup >= self.effective_min_sup:
                new_itemsets = [list(it) for it in pattern.itemsets]
                new_itemsets[-1] = new_itemsets[-1] + [y]
                child = Pattern(
                    itemsets=new_itemsets,
                    idlist=new_idlist,
                    raw_sup=sup,
                    wsup=sup * self.mean_w,
                )
                out.append(child)
                self._extend(child, out)

    def mine(
        self,
        sequences: List[List[Tuple[Item, ...]]],
        item_prices: Optional[Dict[Item, float]] = None,
    ) -> WCMSpadeResult:
        item_prices = item_prices or {}
        self.N = len(sequences)
        self.min_sup_abs = max(1, math.ceil(self.min_sup * self.N))
        frequent, _ = self._build_item_idlists(sequences, item_prices)
        if not frequent:
            return WCMSpadeResult([], self.N, self.min_sup_abs, 0, 1.0, {})

        self._compute_weights(frequent, item_prices)
        self._build_cooc_maps(sequences)

        results: List[Pattern] = []

        for item, idlist in self.item_idlists.items():
            sup = len(idlist)
            p = Pattern([[item]], idlist, sup, sup * self.mean_w)
            results.append(p)
            self._extend(p, results)
            if len(results) >= self.max_patterns:
                print(f"  [WARN] đạt max_patterns={self.max_patterns}, dừng sớm")
                break

        results.sort(key=lambda p: p.wsup, reverse=True)
        return WCMSpadeResult(
            patterns=results,
            n_sequences=self.N,
            min_sup_abs=self.min_sup_abs,
            effective_min_sup=self.effective_min_sup,
            mean_w=self.mean_w,
            item_weights=self.item_weights,
        )


class NextBasketPredictor:
    def __init__(self, patterns: List[Pattern],
                 item_weights: Optional[Dict[Item, float]] = None):
        self.item_weights = item_weights or {}
        self.index: Dict[frozenset, List[Tuple[List[Item], float]]] = defaultdict(list)
        self.global_top: List[Tuple[Item, float]] = []
        self._build(patterns)

    def _w(self, item: Item) -> float:
        return self.item_weights.get(item, 1.0) if self.item_weights else 1.0
    def _build(self, patterns: List[Pattern]):
        item_w: Dict[Item, float] = defaultdict(float)
        for p in patterns:
            if len(p.itemsets) >= 2:
                last_ante = frozenset(p.itemsets[-2])
                consequent = p.itemsets[-1]
                self.index[last_ante].append((consequent, p.wsup))
            for it in p.itemsets:
                for item in it:
                    item_w[item] += p.wsup * self._w(item)
        self.global_top = sorted(item_w.items(), key=lambda kv: kv[1], reverse=True)

    def predict(self, history: List[Tuple[Item, ...]], top_k: int = 5) -> List[Tuple[Item, float]]:
        score: Dict[Item, float] = defaultdict(float)
        for basket in reversed(history):
            bset = frozenset(basket)
            for last_ante, entries in self.index.items():
                if last_ante.issubset(bset):
                    for consequent, wsup in entries:
                        for item in consequent:
                            score[item] += wsup * self._w(item)
            if len([s for s in score.values() if s > 0]) >= 3 * top_k:
                break
        if not score:
            return [(i, w) for i, w in self.global_top[:top_k]]
        ranked = sorted(score.items(), key=lambda kv: kv[1], reverse=True)
        return ranked[:top_k]
