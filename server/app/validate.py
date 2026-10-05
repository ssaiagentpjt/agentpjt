"""data/ 시드 검사. 실행 (server/ 에서):

    python -m app.validate                    # 전체
    python -m app.validate --main health-food # 맡은 대분류만 (여러 번 줄 수 있다). 빈 소분류는 오류
    python -m app.validate --strict           # 최종 전체 검사. 빈 소분류는 오류

기본 전체 검사는 생성이 진행 중인 상태를 보는 용도라 빈 소분류·파일 없는 대분류를 경고로만 낸다.

GENERATION_GUIDE.md 로 데이터를 만든 세션이 마지막에 돌린다. 오류가 하나라도 있으면 종료 코드 1.
스키마(필드·타입·범위)는 models.py 의 pydantic 모델이 보고, 여기서는 파일 사이의 규칙과 분포를 본다.
부분 검사에서는 다른 대분류 파일을 읽지 않으므로, 병렬 생성 중인 다른 세션의 미완성 파일 때문에 실패하지 않는다.
"""

import argparse
import difflib
import sqlite3
import sys
from collections import Counter
from pathlib import Path

from pydantic import ValidationError

from . import db
from .search import search
from .store import DEFAULT_DATA_DIR

# 실제 상표·회사명 일부. 가상 브랜드만 쓰도록 생성 지침에서 금지한 이름들이다. 완전한 목록이 아니다.
# 일반 단어에도 들어가는 말("매일", "동아", "유한")은 오탐이 나서 넣지 않았다.
BANNED = [
    "신신", "케토톱", "쿠팡", "곰곰", "탐사", "오뚜기", "농심", "CJ", "비비고", "풀무원", "하기스", "디펜드",
    "종근당", "대웅", "광동", "정관장", "센트룸", "고려은단", "뉴트리원", "삼성", "LG", "오므론", "필립스", "다이슨",
    "남양", "서울우유", "베지밀", "동원", "사조", "롯데", "해태", "크리넥스", "깨끗한나라", "유한킴벌리", "쿠쿠", "쿠첸",
    "나이키", "아디다스", "노스페이스", "블랙야크", "K2", "아모레", "설화수", "로얄캐닌", "레고", "바디프랜드",
]

# 시연 검색어와 정답. 상위 3개 중 하나는 정답 (대분류, 중분류) 에 속해야 한다. 전체 검사에서만 보고,
# 정답 중분류에 아직 상품이 없으면(다른 세션이 생성 중) 건너뛴다. 대분류 16개를 고르게 덮는다.
DEMO_QUERIES: dict[str, list[tuple[str, ...]]] = {  # (대분류, 중분류) 또는 (대분류, 중분류, 소분류)
    "무릎 파스": [("medical", "pain-relief")],
    "눈 침침": [("medical", "eye-care"), ("health-food", "eye-joint")],
    "돋보기": [("medical", "eye-care")],
    "아침에 마실 거": [("food", "drink")],
    "쌀": [("food", "rice-grain")],
    "기저귀": [("hygiene", "adult-diaper"), ("hygiene", "incontinence")],
    "혈압계": [("medical", "measure")],
    "보청기 건전지": [("medical", "hearing")],
    "홍삼": [("health-food", "red-ginseng")],
    "물티슈": [("living", "tissue")],
    "전기밥솥": [("kitchen", "kitchen-appliance")],
    "지팡이": [("silver", "walking")],
    "효도폰": [("silver", "easy-device", "easy-phone")],
    "전기요": [("digital", "seasonal")],
    "내복": [("fashion", "innerwear")],
    "효도화": [("fashion-acc", "shoes")],
    "염색약": [("beauty", "hair")],
    "등산 스틱": [("sports", "hiking")],
    "온수매트": [("interior", "bedding")],
    "강아지 간식": [("pet", "dog-food")],
    "손주 선물": [("kids", "kids-gift"), ("kids", "toy")],
    "큰 글씨 책": [("hobby", "books")],
}
# 시연 고정 상품. seed_orders.json 과 앱 시연이 이 id 를 쓴다
FIXED = {"p01001": "햅쌀 10kg", "p01002": "무가당 두유 190ml 24팩", "p03001": "보청기용 건전지 312 30개"}

NEAR_DUP = 0.9  # 같은 소분류 안 상품명 유사도 경고 기준
BRAND_SHARE = 0.2  # 한 브랜드가 대분류 상품의 이 비율을 넘으면 경고
TARGET_WARN = 0.7  # 소분류 target 대비 이 비율 미만이면 경고


def check(data_dir: Path, only: set[str] | None, strict: bool = False) -> tuple[list[str], list[str]]:
    strict = strict or bool(only)  # 맡은 대분류를 검사할 때는 완성 기준으로 본다
    errors: list[str] = []
    warnings: list[str] = []
    mains = db.load_categories(data_dir)
    by_main = {m.id: m for m in mains}
    if only and (unknown := only - by_main.keys()):
        return [f"--main 에 없는 대분류: {sorted(unknown)}"], []
    files = db.load_product_files(data_dir, only)
    seeds = db.load_seed_orders(data_dir)
    products = [p for items in files.values() for p in items]

    errors += db.check_references(mains, files)
    for m in mains:
        mid_ids = [d.id for d in m.mids]
        errors += [f"categories.json: {m.id} 중분류 id 중복" for _ in [0] if len(set(mid_ids)) != len(mid_ids)]
        for d in m.mids:
            if len({s.id for s in d.subs}) != len(d.subs):
                errors.append(f"categories.json: {m.id} > {d.id} 소분류 id 중복")
    prefixes = Counter(m.idPrefix for m in mains)
    errors += [f"categories.json: idPrefix {p} 중복" for p, n in prefixes.items() if n > 1]
    brand_owner = Counter(b for m in mains for b in m.brands)
    errors += [f"categories.json: 브랜드 '{b}' 가 여러 대분류 풀에 있다" for b, n in brand_owner.items() if n > 1]

    ids = Counter(p.productId for p in products)
    errors += [f"productId 중복: {i} ({n}번)" for i, n in ids.items() if n > 1]
    names = Counter(p.productName for p in products)
    errors += [f"productName 중복: {n}" for n, k in names.items() if k > 1]

    for p in products:
        where = f"{p.productId} {p.productName}"
        m = by_main.get(p.category.main)
        pr, dv, st, sk = p.pricing, p.delivery, p.stats, p.stock
        if m and p.brand not in m.brands:
            errors.append(f"{where}: 브랜드 '{p.brand}' 는 {m.id} 브랜드 풀에 없다")
        mid = next((d for d in m.mids if d.id == p.category.mid), None) if m else None
        if mid and not mid.priceRange[0] <= pr.price <= mid.priceRange[1]:
            warnings.append(f"{where}: 가격 {pr.price} 이 {mid.name} 가격대 {mid.priceRange} 밖")
        if pr.originalPrice is not None and pr.originalPrice <= pr.price:
            errors.append(f"{where}: originalPrice 는 price 보다 커야 한다")
        if pr.price % 10 != 0:
            errors.append(f"{where}: price 는 10원 단위")
        if dv.isRocket and dv.isFreeShipping:
            errors.append(f"{where}: isRocket 과 isFreeShipping 은 함께 true 일 수 없다")
        if (dv.isRocket or dv.isFreeShipping) and dv.shippingFee != 0:
            errors.append(f"{where}: 로켓·무료배송이면 shippingFee 는 0")
        if not (dv.isRocket or dv.isFreeShipping) and dv.shippingFee == 0:
            errors.append(f"{where}: 유료배송이면 shippingFee 가 있어야 한다")
        if dv.isRocket and dv.deliveryDays != 1:
            errors.append(f"{where}: 로켓배송은 deliveryDays 1")
        if st.salesCountTotal < st.salesCount30d:
            errors.append(f"{where}: salesCountTotal 은 salesCount30d 이상")
        if st.reviewCount > st.salesCountTotal:
            errors.append(f"{where}: reviewCount 는 salesCountTotal 이하")
        if sk.status == "sold_out" and sk.quantity != 0:
            errors.append(f"{where}: sold_out 이면 stock.quantity 0")
        if sk.status == "low" and not 1 <= sk.quantity <= 20:
            errors.append(f"{where}: low 면 stock.quantity 1~20")
        if sk.status == "in_stock" and sk.quantity <= 20:
            errors.append(f"{where}: in_stock 이면 stock.quantity 21 이상")
        tags = p.content.tags
        if len(set(tags)) != len(tags) or any(not t.strip() or len(t) > 15 for t in tags):
            errors.append(f"{where}: tags 는 중복 없이 1~15자")
        if len(tags) < 6:
            errors.append(f"{where}: tags 는 6개 이상")
        # 검색은 부분 일치라 짧은 질의는 긴 태그에도 걸린다. 짧은 핵심어는 정확 일치 보너스를 받기 위한 권장 사항
        if not any(len(t) <= 3 for t in tags):
            warnings.append(f"{where}: tags 에 3글자 이하 핵심어(예: 파스, 쌀, 건전지)를 하나 넣으면 정확 일치 점수를 받는다")
        axis_names = [a.name for a in p.options]
        if len(set(axis_names)) != len(axis_names):
            errors.append(f"{where}: 옵션 축 이름 중복")
        for a in p.options:
            vals = [v.value for v in a.values]
            if len(set(vals)) != len(vals):
                errors.append(f"{where}: 옵션 {a.name} 값 중복")
            if any(v.priceAdd > pr.price * 0.5 for v in a.values):
                errors.append(f"{where}: 옵션 {a.name} 추가 금액이 가격의 50% 를 넘는다")
            if any(v.priceAdd % 10 for v in a.values):
                errors.append(f"{where}: 옵션 {a.name} 추가 금액은 10원 단위")
            if sk.status != "sold_out" and all((v.stock or sk.status) == "sold_out" for v in a.values):
                errors.append(f"{where}: 옵션 {a.name} 값이 모두 품절인데 상품은 판매 중")
        text = " ".join([p.productName, p.brand, p.content.description, p.content.reviewSummary or "", *tags])
        errors += [f"{where}: 실제 상표로 보이는 말 '{b}'" for b in BANNED if b in text]

    # 같은 소분류 안 거의 같은 이름 (규격만 다른 상품은 허용하므로 경고)
    by_sub: dict[tuple, list] = {}
    for p in products:
        by_sub.setdefault((p.category.main, p.category.mid, p.category.sub), []).append(p)
    for items in by_sub.values():
        for i, a in enumerate(items):
            for b in items[i + 1:]:
                if difflib.SequenceMatcher(None, a.productName, b.productName).ratio() >= NEAR_DUP:
                    warnings.append(f"이름이 거의 같다: {a.productId} '{a.productName}' / {b.productId} '{b.productName}'")

    # 브랜드 쏠림
    for stem, items in files.items():
        if len(items) >= 10:
            for brand, n in Counter(p.brand for p in items).items():
                if n / len(items) > BRAND_SHARE:
                    warnings.append(f"{stem}: 브랜드 '{brand}' 가 {n}/{len(items)}개 ({n / len(items):.0%})")

    pids = set(ids)
    if not only:
        errors += [f"seed {s.orderId}: 없는 상품 {s.productId}" for s in seeds if s.productId not in pids]
        for pid, name in FIXED.items():
            fixed = next((p for p in products if p.productId == pid), None)
            if fixed is None or fixed.productName != name or fixed.stock.status != "in_stock":
                errors.append(f"시연 고정 상품 {pid} '{name}' 가 없거나 이름·재고가 다르다")

    # 할당 달성률 표
    print(f"{'대분류':<12} {'상품':>5} {'목표':>5} {'달성':>5}  {'상위3 판매 비중(중분류 평균)':>14}")
    count_sub = Counter((p.category.main, p.category.mid, p.category.sub) for p in products)
    for m in mains:
        if only and m.id not in only:
            continue
        n = sum(1 for p in products if p.category.main == m.id)
        target = sum(s.target for d in m.mids for s in d.subs)
        shares = []
        for d in m.mids:
            sales = sorted((p.stats.salesCount30d for p in products if p.category.main == m.id and p.category.mid == d.id), reverse=True)
            if sum(sales):
                shares.append(sum(sales[:3]) / sum(sales))
            for s in d.subs:
                got = count_sub.get((m.id, d.id, s.id), 0)
                if got == 0:
                    (errors if strict else warnings).append(f"비어 있는 소분류: {m.name} > {d.name} > {s.name} (목표 {s.target})")
                elif got < s.target * TARGET_WARN:
                    warnings.append(f"목표 미달: {m.name} > {d.name} > {s.name} {got}/{s.target}")
        share = f"{sum(shares) / len(shares):.0%}" if shares else "-"
        print(f"{('★' if m.seniorWeighted else ' ') + m.name:<12} {n:>5} {target:>5} {n / target:>5.0%}  {share:>14}")

    if products:
        dvs = [p.delivery for p in products]
        aud = Counter(p.audience for p in products)
        senior_main = sum(1 for p in products if by_main[p.category.main].seniorWeighted)
        print(f"합계 {len(products)}  로켓 {sum(d.isRocket for d in dvs) / len(products):.0%}"
              f" · 무료 {sum(d.isFreeShipping for d in dvs) / len(products):.0%}"
              f" · 품절 {sum(p.stock.status == 'sold_out' for p in products)}"
              f" · 품절임박 {sum(p.stock.status == 'low' for p in products)}"
              f" · 선물용 {sum(p.isGift for p in products) / len(products):.0%}"
              f" · 옵션 있음 {sum(bool(p.options) for p in products) / len(products):.0%}")
        print(f"대상 senior {aud['senior']} · general {aud['general']} · kids {aud['kids']}"
              f" · 어르신 가중 대분류 비중 {senior_main / len(products):.0%}")

    # 상황 태그(needs): 어휘 안의 이름만, 상품당 3개까지, 있는 상품에만
    vocab, needs = db.load_needs(data_dir)
    known = {v["name"] for v in vocab}
    ids = {p.productId for p in products}
    for pid, names in needs.items():
        if pid not in ids and not only:
            errors.append(f"needs: 없는 상품 {pid}")
        if bad := [n for n in names if n not in known]:
            errors.append(f"needs: {pid} 의 어휘 밖 이름 {bad}")
        if len(names) > 3 or len(set(names)) != len(names):
            errors.append(f"needs: {pid} 는 중복 없이 3개까지")
    if vocab and products:
        covered = sum(1 for p in products if needs.get(p.productId))
        print(f"상황 태그: 어휘 {len(vocab)}개 · 붙은 상품 {covered}/{len(products)}")

    if not only and not errors:
        conn = sqlite3.connect(":memory:")
        db.rebuild(conn, data_dir)
        present = {(c.main, c.mid) for c in (p.category for p in products)}
        present |= {(c.main, c.mid, c.sub) for c in (p.category for p in products)}
        print("시연 검색어 상위 3개 (품절 제외, ✓ = 정답 분류가 상위 3개 안)")
        for q, answers in DEMO_QUERIES.items():
            if not any(a in present for a in answers):
                print(f"  {q:<10}  (정답 분류에 아직 상품 없음 — 건너뜀)")
                continue
            total, top = search(conn, q, limit=3)
            rows = {r[0]: r for r in conn.execute(
                f"select id, name, main_id, mid_id, sub_id from products where id in ({','.join('?' * len(top))})", top)} if top else {}
            ok = any(tuple(rows[i][2:2 + len(a)]) == a for i in top for a in answers)
            print(f"  {q:<10} {'✓' if ok else '✗'} {total:>4}건  " + " / ".join(rows[i][1] for i in top))
            if total == 0:
                errors.append(f"시연 검색어 '{q}' 결과가 없다 — 태그를 보강할 것")
            elif not ok:
                warnings.append(f"시연 검색어 '{q}' 상위 3개에 정답 분류 {answers} 가 없다 — 태그·이름을 확인할 것")
    return errors, warnings


def main(argv: list[str] | None = None, data_dir: Path = DEFAULT_DATA_DIR) -> int:
    ap = argparse.ArgumentParser(description="시드 데이터 검사")
    ap.add_argument("--main", action="append", help="이 대분류만 검사한다(여러 번 줄 수 있다)")
    ap.add_argument("--strict", action="store_true", help="최종 검사: 빈 소분류를 오류로 본다")
    ap.add_argument("--max-warnings", type=int, default=40, help="경고를 몇 줄까지 보일지")
    args = ap.parse_args(argv)
    try:
        errors, warnings = check(data_dir, set(args.main) if args.main else None, args.strict)
    except ValidationError as e:
        print(e)
        return 1

    for w in warnings[: args.max_warnings]:
        print("  경고: " + w)
    if len(warnings) > args.max_warnings:
        print(f"  … 경고 {len(warnings) - args.max_warnings}건 더 (--max-warnings 로 늘릴 수 있다)")
    if errors:
        print(f"\n오류 {len(errors)}건")
        for e in errors:
            print("  - " + e)
        return 1
    print("\n통과" + (f" (경고 {len(warnings)}건)" if warnings else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
