"""검색 속도 측정. 실제 categories.json 위에 합성 상품 N개(기본 5,000)를 만들어 검색 + compact 변환 시간을 잰다.

실행: python scripts/bench_search.py [--n 5000] [--repeat 20]   (server/ 에서)
목표: p95 50ms 이하(로컬 PC). 넘기면 검색용 바이그램 역색인을 검토한다(app/search.py 주석 참고).
"""

import argparse
import json
import random
import statistics
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from app.store import Store  # noqa: E402
from app.validate import DEMO_QUERIES  # noqa: E402

WORDS = ["순한", "대용량", "국산", "부드러운", "가벼운", "따뜻한", "시원한", "튼튼한", "작은", "큰", "프리미엄", "실속"]


def synth(n: int, out: Path) -> None:
    mains = json.loads((ROOT / "data" / "categories.json").read_text(encoding="utf-8"))
    subs = [(m, d, s) for m in mains for d in m["mids"] for s in d["subs"]]
    rng = random.Random(7)
    files: dict[str, list] = {m["id"]: [] for m in mains}
    for i in range(n):
        m, d, s = subs[i % len(subs)]
        items = files[m["id"]]
        price = rng.randrange(d["priceRange"][0], d["priceRange"][1], 100)
        rocket = rng.random() < 0.5
        tags = list({s["name"], d["name"].split("·")[0], *rng.sample(WORDS, 4), s["name"][:2]})
        options = []
        if rng.random() < 0.3:
            options = [{"name": "색상", "values": [{"value": v} for v in rng.sample(["검정", "회색", "남색", "갈색", "흰색"], 3)]}]
        items.append({
            "productId": f"p{m['idPrefix']}{len(items) + 1:03d}",
            "productName": f"{rng.choice(WORDS)} {s['name']} {i}",
            "brand": rng.choice(m["brands"]),
            "category": {"main": m["id"], "mid": d["id"], "sub": s["id"]},
            "origin": "국산", "audience": rng.choice(["senior", "general"]), "isGift": rng.random() < 0.2,
            "pricing": {"price": price, "originalPrice": None},
            "spec": {"text": "1개", "quantity": 1, "unit": "개"},
            "delivery": {"isRocket": rocket, "isFreeShipping": False,
                         "shippingFee": 0 if rocket else 3000, "deliveryDays": 1 if rocket else 3},
            "stats": {"salesCount30d": rng.randrange(10, 5000), "salesCountTotal": 60000, "rating": 4.5, "reviewCount": 500,
                      "repurchaseRate": 30},
            "content": {"description": "합성 데이터예요.", "reviewSummary": None,
                        "tags": (tags + [w for w in WORDS if w not in tags])[:8]},  # 중복 없이 8개
            "stock": {"status": "in_stock", "quantity": 100},
            "options": options,
        })
    (out / "products").mkdir(parents=True)
    for main_id, items in files.items():
        (out / "products" / f"{main_id}.json").write_text(json.dumps(items, ensure_ascii=False), encoding="utf-8")
    (out / "categories.json").write_text((ROOT / "data" / "categories.json").read_text(encoding="utf-8"), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=5000)
    ap.add_argument("--repeat", type=int, default=20)
    args = ap.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        data = Path(tmp) / "data"
        synth(args.n, data)
        t0 = time.perf_counter()
        store = Store(data, ":memory:")  # 파일 DB 는 Windows 에서 임시 폴더 정리 때 잠김 오류가 난다
        load_ms = (time.perf_counter() - t0) * 1000
        queries = list(DEMO_QUERIES) + ["", "검정 장갑 2만원", "선물"]
        times = []
        for _ in range(args.repeat):
            for q in queries:
                t = time.perf_counter()
                store.search("http://x", q=q, limit=5, compact=True)
                times.append((time.perf_counter() - t) * 1000)
        store.conn.close()
    times.sort()
    p95 = times[int(len(times) * 0.95) - 1]
    print(f"상품 {args.n}개  적재 {load_ms:.0f}ms  검색 {len(times)}회")
    print(f"평균 {statistics.mean(times):.1f}ms  중앙값 {statistics.median(times):.1f}ms  p95 {p95:.1f}ms  최대 {times[-1]:.1f}ms")
    print("목표(p95 50ms 이하): " + ("달성" if p95 <= 50 else "미달"))
    return 0 if p95 <= 50 else 1


if __name__ == "__main__":
    sys.exit(main())
