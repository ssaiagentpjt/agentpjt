import sqlite3
from pathlib import Path

import pytest

from app import db
from app.search import search

# 실제 data/ 는 생성 데이터로 바뀔 수 있어서 테스트는 고정된 28개(fixtures)를 쓴다
FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture(scope="module")
def conn():
    c = sqlite3.connect(":memory:")
    db.rebuild(c, FIXTURES)
    return c


def col(conn, pid, column):
    return conn.execute(f"select {column} from products where id = ?", (pid,)).fetchone()[0]


def test_name_match_outranks_others(conn):
    assert search(conn, "무릎 파스")[1][0] == "p03005"  # 이름에 '무릎'과 '파스'가 모두 있다


def test_two_letter_korean_words_are_found(conn):
    assert search(conn, "파스")[0] >= 4


def test_category_names_are_searchable(conn):
    assert search(conn, "핫파스")[1][0] in {"p03004", "p03005"}  # 소분류 '핫파스'
    total, ids = search(conn, "보행")  # 중분류 '보행 보조'
    assert "p07001" in ids


def test_all_tokens_bonus_beats_partial_match(conn):
    # "무릎 보호대" 는 이름에 둘 다 있는 무릎 보호대가, 이름에 "무릎"만 있는 핫팩 파스보다 위
    assert search(conn, "무릎 보호대")[1][0] == "p07002"


def test_exact_tag_match_bonus(conn):
    # 태그 "노안" 과 정확히 같은 돋보기가 맨 위
    assert search(conn, "노안")[1][0] == "p03006"


def test_option_values_are_searchable(conn):
    assert "p07001" in search(conn, "갈색 지팡이")[1]
    assert search(conn, "갈색 지팡이")[1][0] == "p07001"


def test_wildcards_are_literal(conn):
    assert search(conn, "%") == (0, [])


def test_category_filters(conn):
    assert search(conn, "", main="medical")[0] == 10
    assert search(conn, "", main="medical", mid="pain-relief")[0] == 4
    assert search(conn, "", main="medical", mid="pain-relief", sub="hot-patch")[0] == 2


def test_price_filters(conn):
    total, ids = search(conn, "파스", max_price=13_000)
    assert total > 0 and all(col(conn, i, "price") <= 13_000 for i in ids)
    _, ids = search(conn, "", main="medical", price_tier="low")
    assert ids and all(col(conn, i, "price") <= 12_000 for i in ids)


def test_audience_and_gift_filters(conn):
    _, ids = search(conn, "", audience="senior", limit=30)
    assert ids and all(col(conn, i, "audience") == "senior" for i in ids)
    _, ids = search(conn, "", gift=True, limit=30)
    assert ids and all(col(conn, i, "is_gift") == 1 for i in ids)


def test_sold_out_hidden_by_default(conn):
    assert "p07003" not in search(conn, "매트")[1]
    assert "p07003" in search(conn, "매트", include_sold_out=True)[1]


def test_sorts(conn):
    _, ids = search(conn, "파스", sort="price_asc")
    prices = [col(conn, i, "price") for i in ids]
    assert prices == sorted(prices)
    _, ids = search(conn, "", sort="sales", limit=3)
    sales = [conn.execute("select sales_30d from product_stats where product_id = ?", (i,)).fetchone()[0] for i in ids]
    assert sales == sorted(sales, reverse=True)


def test_unmatched_query_and_limit(conn):
    assert search(conn, "자동차 타이어") == (0, [])
    total, ids = search(conn, "", limit=5)
    assert total == 27 and len(ids) == 5  # 품절 1개 제외
