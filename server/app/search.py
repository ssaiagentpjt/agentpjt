"""상품 검색(SQL). 실제 쇼핑 API 의 검색 엔진 역할을 흉내 낸다.

검색어는 앱의 Gemma 가 만든다. 여기서는 낱말 단위 부분 일치로 점수를 매길 뿐, 사용자 말을 해석하지 않는다.
- SQLite FTS5 trigram 은 2글자 한국어("파스", "핫팩")를 못 찾아서 instr() 부분 일치를 쓴다.
  LIKE 대신 instr 를 쓰는 이유: 검색어에 % 나 _ 가 들어와도 와일드카드로 해석되지 않는다.
- 적재할 때 상품마다 소문자로 합쳐 둔 product_search 한 행만 본다. 태그 테이블을 낱말마다 훑지 않아 수천 개에서도 빠르다.
"""

import sqlite3
from typing import Literal

Sort = Literal["relevance", "price_asc", "price_desc", "rating", "sales", "discount", "unit_price"]
Tier = Literal["low", "mid", "high"]
Audience = Literal["senior", "general", "kids"]

# 낱말이 어디서 맞았는지에 따른 가중치
W_NAME, W_TAG, W_TAG_EXACT, W_SUB, W_MID, W_MAIN, W_BRAND, W_OPTION = 3, 2, 1, 2, 1, 1, 1, 1
# 검색어의 모든 낱말이 어딘가에 맞은 상품에 더하는 점수.
# "검정 장갑"에서 검정색 장갑을, "아침에 마실 거"에서 '거'만 맞은 상품보다 맞는 상품을 위로 올린다
W_ALL_TOKENS = 4

_FIELDS = ("s.name_l", "s.tags_l", "s.sub_l", "s.mid_l", "s.main_l", "s.brand_l", "s.opts_l")


def _token_score(k: str) -> str:
    return f"""(
        (instr(s.name_l, :{k}) > 0) * {W_NAME}
      + (instr(s.tags_l, :{k}) > 0) * {W_TAG}
      + (instr('|' || s.tags_l || '|', '|' || :{k} || '|') > 0) * {W_TAG_EXACT}
      + (instr(s.sub_l, :{k}) > 0) * {W_SUB}
      + (instr(s.mid_l, :{k}) > 0) * {W_MID}
      + (instr(s.main_l, :{k}) > 0) * {W_MAIN}
      + (instr(s.brand_l, :{k}) > 0) * {W_BRAND}
      + (instr(s.opts_l, :{k}) > 0) * {W_OPTION})"""


def _token_hit(k: str) -> str:
    return "((" + " + ".join(f"instr({f}, :{k})" for f in _FIELDS) + ") > 0)"


# 정렬은 화이트리스트. 사용자 입력을 SQL 에 이어 붙이지 않는다.
_ORDER_BY: dict[str, str] = {
    "relevance": "score desc, sales_30d desc, rating desc",
    "price_asc": "price asc, score desc",
    "price_desc": "price desc, score desc",
    "rating": "rating desc, review_count desc",
    "sales": "sales_30d desc, rating desc",
    "discount": "discount_rate desc, sales_30d desc",
    "unit_price": "unit_price asc, sales_30d desc",
}


def search(
    conn: sqlite3.Connection,
    q: str = "",
    main: str | None = None,
    mid: str | None = None,
    sub: str | None = None,
    min_price: int | None = None,
    max_price: int | None = None,
    price_tier: Tier | None = None,
    audience: Audience | None = None,
    gift: bool | None = None,
    need: str | None = None,
    include_sold_out: bool = False,
    sort: Sort = "relevance",
    limit: int = 5,
) -> tuple[int, list[str]]:
    """(조건에 맞는 전체 개수, 상위 limit 개 상품 id). q 가 비면 필터·정렬만 적용한다."""
    tokens = [t for t in q.lower().replace("|", " ").split() if t]
    params: dict = {f"t{i}": tok for i, tok in enumerate(tokens)}
    if tokens:
        keys = list(params)
        all_hit_sql = f"(({' + '.join(_token_hit(k) for k in keys)}) = {len(keys)})"
        score_sql = " + ".join(_token_score(k) for k in keys) + f" + {all_hit_sql} * {W_ALL_TOKENS}"
    else:
        all_hit_sql = score_sql = "0"

    where = []
    for i, (col, val) in enumerate((("p.main_id", main), ("p.mid_id", mid), ("p.sub_id", sub), ("p.audience", audience))):
        if val:
            where.append(f"{col} = :f{i}")
            params[f"f{i}"] = val
    if gift is not None:
        where.append("p.is_gift = :gift")
        params["gift"] = int(gift)
    if need:
        where.append("exists (select 1 from product_needs n where n.product_id = p.id and n.need = :need)")
        params["need"] = need
    if min_price is not None:
        where.append("p.price >= :min_price")
        params["min_price"] = min_price
    if max_price is not None:
        where.append("p.price <= :max_price")
        params["max_price"] = max_price
    if price_tier:
        where.append("m.price_tier = :tier")
        params["tier"] = price_tier
    if not include_sold_out:
        where.append("p.stock_status != 'sold_out'")

    inner = f"""
      select p.id, ({score_sql}) as score, ({all_hit_sql}) as all_hit, p.price, st.sales_30d, st.rating, st.review_count,
             m.discount_rate, m.unit_price
      from products p
      join product_search s on s.product_id = p.id
      join product_stats st on st.product_id = p.id
      join product_metrics m on m.product_id = p.id
      {"where " + " and ".join(where) if where else ""}
    """
    sql = f"select id, all_hit from ({inner}) {'where score > 0' if tokens else ''} order by {_ORDER_BY[sort]}, id"
    rows = conn.execute(sql, params).fetchall()
    # relevance 가 아닌 정렬은 점수를 보지 않아서 "무릎 파스"를 평점순으로 찾으면 '무릎'만 맞은 영양제가 위로 온다.
    # 모든 낱말이 맞은 상품이 있으면 그것만 남긴다(AND). 없으면 하나라도 맞은 상품으로 물러난다(OR).
    if tokens and sort != "relevance" and any(r[1] for r in rows):
        rows = [r for r in rows if r[1]]
    ids = [r[0] for r in rows]
    return len(ids), ids[:limit]
