import sys
import time
from pathlib import Path

from app.store import Store

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
from bench_search import synth  # noqa: E402


def test_search_stays_fast_with_thousands_of_products(tmp_path):
    # 목표는 p95 50ms(scripts/bench_search.py). 테스트는 기계 편차를 감안해 느슨하게 500ms 로 본다
    synth(3000, tmp_path / "data")
    store = Store(tmp_path / "data", ":memory:")
    worst = 0.0
    for q in ["무릎 파스", "검정 장갑", "아침에 마실 거", "선물", ""]:
        t = time.perf_counter()
        total, found = store.search("http://x", q=q, limit=5)
        worst = max(worst, time.perf_counter() - t)
    assert worst < 0.5
