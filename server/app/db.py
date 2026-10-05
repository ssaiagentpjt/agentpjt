"""SQLite 스키마와 시드 적재.

data/ 의 JSON 이 원본이다.
- categories.json: 대/중/소 3단계 분류, 소분류 목표 수, 중분류 가격대, 대분류 브랜드 풀
- products/<대분류 id>.json: 대분류마다 파일 하나. 여러 세션이 대분류를 나눠 만들 수 있게 나눴다
- seed_orders.json: 시연 사용자의 지난 구매

상품 쪽 테이블은 서버가 시작할 때마다 지우고 시드로 다시 만든다. 주문 테이블만 계속 남는다.
"""

import json
import sqlite3
from pathlib import Path

from pydantic import TypeAdapter

from .metrics import compute
from .models import MainSeed, ProductSeed, SeedOrder

PRODUCT_SCHEMA = """
drop table if exists product_search;
drop table if exists product_options;
drop table if exists product_metrics;
drop table if exists product_stats;
drop table if exists product_tags;
drop table if exists products;
drop table if exists sub_categories;
drop table if exists mid_categories;
drop table if exists main_categories;
drop table if exists subcategories;  -- 4-2단계 이름. 남아 있으면 지운다
drop table if exists categories;

create table main_categories (
  id text primary key, name text not null, icon text not null, id_prefix text not null,
  senior_weighted integer not null, sort_order integer not null);
create table mid_categories (
  main_id text not null references main_categories(id), id text not null, name text not null,
  price_lo integer not null, price_hi integer not null, sort_order integer not null,
  primary key (main_id, id));
create table sub_categories (
  main_id text not null, mid_id text not null, id text not null, name text not null,
  target integer not null, sort_order integer not null,
  primary key (main_id, mid_id, id),
  foreign key (main_id, mid_id) references mid_categories(main_id, id));
create table products (
  id text primary key, name text not null, brand text not null,
  main_id text not null, mid_id text not null, sub_id text not null,
  origin text not null, audience text not null, is_gift integer not null,
  price integer not null, original_price integer,
  spec_text text not null, spec_qty real not null, spec_unit text not null,
  is_rocket integer not null, is_free_shipping integer not null, shipping_fee integer not null, delivery_days integer not null,
  description text not null, review_summary text,
  stock_status text not null, stock_qty integer not null,
  image text, source text not null,
  foreign key (main_id, mid_id, sub_id) references sub_categories(main_id, mid_id, id));
create table product_tags (product_id text not null references products(id), tag text not null);
create index product_tags_pid on product_tags(product_id);
create table product_stats (
  product_id text primary key references products(id),
  sales_30d integer not null, sales_total integer not null, rating real not null,
  review_count integer not null, repurchase_rate integer not null);
create table product_metrics (
  product_id text primary key references products(id),
  discount_rate integer not null, unit_price integer not null, unit_per text not null,
  price_percentile integer not null, price_tier text not null,
  rank_in_sub integer not null, rank_in_mid integer not null, rank_in_main integer not null, rank_overall integer not null,
  badges text not null);  -- JSON 배열 문자열
create table product_options (
  product_id text not null references products(id), axis text not null, axis_order integer not null,
  value text not null, value_order integer not null, price_add integer not null,
  stock text,  -- null 이면 상품 재고를 따른다
  primary key (product_id, axis, value));
-- 검색용으로 미리 소문자로 합친 문자열. 검색 한 번에 상품 한 행만 보면 되게 한다(app/search.py)
create table product_search (
  product_id text primary key references products(id),
  name_l text not null, tags_l text not null, sub_l text not null, mid_l text not null, main_l text not null,
  brand_l text not null, opts_l text not null);
"""

ORDER_SCHEMA = """
create table if not exists orders (
  orderId text primary key, userId text not null, productId text not null,
  quantity integer not null, totalPrice integer not null, orderedAt text not null,
  options text not null default '{}');  -- JSON 객체 {"사이즈": "L"}
"""


def migrate_orders(conn: sqlite3.Connection) -> None:
    """주문 테이블은 재시작해도 남으므로, 옛 DB 파일에 없는 컬럼을 덧붙인다."""
    cols = {r[1] for r in conn.execute("pragma table_info(orders)")}
    if "options" not in cols:
        conn.execute("alter table orders add column options text not null default '{}'")


def load_categories(data_dir: Path) -> list[MainSeed]:
    raw = json.loads((data_dir / "categories.json").read_text(encoding="utf-8"))
    return TypeAdapter(list[MainSeed]).validate_python(raw)


def load_product_files(data_dir: Path, only: set[str] | None = None) -> dict[str, list[ProductSeed]]:
    """{파일 이름(=대분류 id): 상품 목록}. only 를 주면 그 대분류 파일만 읽는다."""
    out: dict[str, list[ProductSeed]] = {}
    adapter = TypeAdapter(list[ProductSeed])
    for path in sorted((data_dir / "products").glob("*.json")):
        if only is not None and path.stem not in only:
            continue
        out[path.stem] = adapter.validate_python(json.loads(path.read_text(encoding="utf-8")))
    return out


def load_products(data_dir: Path) -> list[ProductSeed]:
    return [p for items in load_product_files(data_dir).values() for p in items]


def load_seed_orders(data_dir: Path) -> list[SeedOrder]:
    path = data_dir / "seed_orders.json"
    if not path.exists():
        return []
    return TypeAdapter(list[SeedOrder]).validate_python(json.loads(path.read_text(encoding="utf-8")))


def check_references(mains: list[MainSeed], files: dict[str, list[ProductSeed]]) -> list[str]:
    """분류 3단계 소속, 파일 이름과 대분류, id 접두어. 어긋나면 적재할 수 없으므로 오류 목록을 돌려준다."""
    tree = {m.id: {mid.id: {s.id for s in mid.subs} for mid in m.mids} for m in mains}
    prefix = {m.id: m.idPrefix for m in mains}
    errors = []
    for stem, items in files.items():
        if stem not in tree:
            errors.append(f"products/{stem}.json: categories.json 에 없는 대분류 이름이다")
            continue
        for p in items:
            c = p.category
            if c.main != stem:
                errors.append(f"{p.productId}: products/{stem}.json 에 있는데 category.main 이 {c.main}")
            elif c.mid not in tree[c.main]:
                errors.append(f"{p.productId}: {c.main} 에 없는 중분류 {c.mid}")
            elif c.sub not in tree[c.main][c.mid]:
                errors.append(f"{p.productId}: {c.main} > {c.mid} 에 없는 소분류 {c.sub}")
            if not p.productId.startswith("p" + prefix[stem]):
                errors.append(f"{p.productId}: {stem} 의 id 는 p{prefix[stem]} 로 시작해야 한다")
    return errors


def rebuild(conn: sqlite3.Connection, data_dir: Path) -> None:
    """상품 쪽 테이블을 시드로 다시 만들고 계산 지표를 채운다."""
    mains = load_categories(data_dir)
    files = load_product_files(data_dir)
    if errors := check_references(mains, files):
        raise ValueError("시드 참조 오류:\n" + "\n".join(errors))
    products = [p for items in files.values() for p in items]

    conn.executescript(PRODUCT_SCHEMA)
    conn.executescript(ORDER_SCHEMA)
    migrate_orders(conn)
    names = {}  # (main, mid, sub) -> (대, 중, 소분류 이름)
    for mi, m in enumerate(mains):
        conn.execute("insert into main_categories values (?, ?, ?, ?, ?, ?)",
                     (m.id, m.name, m.icon, m.idPrefix, m.seniorWeighted, mi))
        for di, mid in enumerate(m.mids):
            conn.execute("insert into mid_categories values (?, ?, ?, ?, ?, ?)",
                         (m.id, mid.id, mid.name, mid.priceRange[0], mid.priceRange[1], di))
            conn.executemany("insert into sub_categories values (?, ?, ?, ?, ?, ?)",
                             [(m.id, mid.id, s.id, s.name, s.target, si) for si, s in enumerate(mid.subs)])
            for s in mid.subs:
                names[(m.id, mid.id, s.id)] = (m.name, mid.name, s.name)
    for p in products:
        c = p.category
        conn.execute(
            "insert into products values (?,?,?, ?,?,?, ?,?,?, ?,?, ?,?,?, ?,?,?,?, ?,?, ?,?, ?,?)",
            (p.productId, p.productName, p.brand,
             c.main, c.mid, c.sub,
             p.origin, p.audience, p.isGift,
             p.pricing.price, p.pricing.originalPrice,
             p.spec.text, p.spec.quantity, p.spec.unit,
             p.delivery.isRocket, p.delivery.isFreeShipping, p.delivery.shippingFee, p.delivery.deliveryDays,
             p.content.description, p.content.reviewSummary,
             p.stock.status, p.stock.quantity,
             p.productImage, p.source),
        )
        conn.executemany("insert into product_tags values (?, ?)", [(p.productId, t) for t in p.content.tags])
        s = p.stats
        conn.execute("insert into product_stats values (?, ?, ?, ?, ?, ?)",
                     (p.productId, s.salesCount30d, s.salesCountTotal, s.rating, s.reviewCount, s.repurchaseRate))
        conn.executemany(
            "insert into product_options values (?, ?, ?, ?, ?, ?, ?)",
            [(p.productId, ax.name, ai, v.value, vi, v.priceAdd, v.stock)
             for ai, ax in enumerate(p.options) for vi, v in enumerate(ax.values)],
        )
        main_n, mid_n, sub_n = names[(c.main, c.mid, c.sub)]
        conn.execute(
            "insert into product_search values (?, ?, ?, ?, ?, ?, ?, ?)",
            (p.productId, p.productName.lower(), "|".join(t.lower() for t in p.content.tags),
             sub_n.lower(), mid_n.lower(), main_n.lower(), p.brand.lower(),
             "|".join(v.value.lower() for ax in p.options for v in ax.values)),
        )
    for pid, m in compute(products).items():
        conn.execute(
            "insert into product_metrics values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (pid, m.discount_rate, m.unit_price, m.unit_per, m.price_percentile, m.price_tier,
             m.rank_in_sub, m.rank_in_mid, m.rank_in_main, m.rank_overall, json.dumps(m.badges, ensure_ascii=False)),
        )
    conn.commit()
