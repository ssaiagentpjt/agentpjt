"""조회 계층. 시작할 때 시드를 SQLite 에 적재하고(app/db.py), 행을 응답 객체(full / compact)로 바꾼다."""

import json
import random
import sqlite3
from datetime import datetime
from pathlib import Path

from . import db
from .delivery import KST, arrive, today_kst
from .metrics import TIER_LABEL
from .models import (
    CategoryOut,
    CategoryRefOut,
    Content,
    DeliveryOut,
    MidTreeOut,
    Named,
    OptionAxisOut,
    OptionBrief,
    OptionValueOut,
    PriceTier,
    PricingOut,
    ProductCompact,
    ProductOut,
    Spec,
    StatsOut,
    Stock,
    SubTreeOut,
    UnitPrice,
)
from .search import search
from .speech import read_won

DEFAULT_DATA_DIR = Path(__file__).resolve().parent.parent / "data"
STATIC_DIR = Path(__file__).resolve().parent / "static"

_PRODUCT_SELECT = """
  select p.*, mc.name as main_name, dc.name as mid_name, sc.name as sub_name,
         st.sales_30d, st.sales_total, st.rating, st.review_count, st.repurchase_rate,
         m.discount_rate, m.unit_price, m.unit_per, m.price_percentile, m.price_tier,
         m.rank_in_sub, m.rank_in_mid, m.rank_in_main, m.rank_overall, m.badges
  from products p
  join main_categories mc on mc.id = p.main_id
  join mid_categories dc on dc.main_id = p.main_id and dc.id = p.mid_id
  join sub_categories sc on sc.main_id = p.main_id and sc.mid_id = p.mid_id and sc.id = p.sub_id
  join product_stats st on st.product_id = p.id
  join product_metrics m on m.product_id = p.id
"""


class OrderError(Exception):
    """주문을 받을 수 없을 때. detail 은 앱 에이전트가 되묻는 데 쓸 수 있게 고를 수 있는 값까지 담는다."""

    def __init__(self, status: int, detail: dict):
        super().__init__(detail.get("message", ""))
        self.status = status
        self.detail = detail


class Store:
    def __init__(self, data_dir: Path, db_path: str):
        # FastAPI 는 동기 엔드포인트를 스레드 풀에서 돌리므로 같은 연결을 여러 스레드가 쓴다
        self.conn = sqlite3.connect(db_path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        db.rebuild(self.conn, data_dir)
        for o in db.load_seed_orders(data_dir):
            row = self.conn.execute("select price, shipping_fee from products where id = ?", (o.productId,)).fetchone()
            if row is None:
                raise ValueError(f"seed_orders 의 없는 상품: {o.productId}")
            self.conn.execute(
                "insert or ignore into orders (orderId, userId, productId, quantity, totalPrice, orderedAt) values (?, ?, ?, ?, ?, ?)",
                (o.orderId, o.userId, o.productId, o.quantity, row["price"] * o.quantity + row["shipping_fee"], o.orderedAt),
            )
        self.conn.commit()

    # ---- 카테고리 ----

    def main_exists(self, main_id: str) -> bool:
        return self.conn.execute("select 1 from main_categories where id = ?", (main_id,)).fetchone() is not None

    def categories(self, base: str) -> list[CategoryOut]:
        """대/중/소 트리. 단계마다 상품 수와 목표 수(소분류 target 합)를 함께 준다."""
        counts = {
            (r["main_id"], r["mid_id"], r["sub_id"]): r["n"]
            for r in self.conn.execute("select main_id, mid_id, sub_id, count(*) n from products group by 1, 2, 3")
        }
        out = []
        for m in self.conn.execute("select * from main_categories order by sort_order").fetchall():
            mids = []
            for d in self.conn.execute("select * from mid_categories where main_id = ? order by sort_order", (m["id"],)).fetchall():
                subs = [
                    SubTreeOut(id=s["id"], name=s["name"], target=s["target"], productCount=counts.get((m["id"], d["id"], s["id"]), 0))
                    for s in self.conn.execute(
                        "select * from sub_categories where main_id = ? and mid_id = ? order by sort_order", (m["id"], d["id"]))
                ]
                mids.append(MidTreeOut(id=d["id"], name=d["name"], priceRange=(d["price_lo"], d["price_hi"]), subs=subs,
                                       productCount=sum(s.productCount for s in subs), target=sum(s.target for s in subs)))
            out.append(CategoryOut(id=m["id"], name=m["name"], iconUrl=f"{base}/static/icons/{m['id']}.svg", mids=mids,
                                   productCount=sum(x.productCount for x in mids), target=sum(x.target for x in mids)))
        return out

    # ---- 상품 ----

    def product_count(self) -> int:
        return self.conn.execute("select count(*) from products").fetchone()[0]

    def product(self, product_id: str, base: str, compact: bool = False) -> ProductOut | ProductCompact | None:
        found = self.products([product_id], base, compact)
        return found[0] if found else None

    def products(self, ids: list[str], base: str, compact: bool = False) -> list:
        """ids 순서를 지켜 돌려준다."""
        if not ids:
            return []
        marks = ",".join("?" * len(ids))
        rows = {r["id"]: r for r in self.conn.execute(f"{_PRODUCT_SELECT} where p.id in ({marks})", ids)}
        options = self._options(ids, marks, rows)
        if compact:
            return [self._to_compact(rows[i], options.get(i, []), base) for i in ids if i in rows]
        tags: dict[str, list[str]] = {}
        for r in self.conn.execute(f"select product_id, tag from product_tags where product_id in ({marks}) order by rowid", ids):
            tags.setdefault(r["product_id"], []).append(r["tag"])
        return [self._to_out(rows[i], tags.get(i, []), options.get(i, []), base) for i in ids if i in rows]

    def search(self, base: str, compact: bool = True, **kwargs) -> tuple[int, list]:
        total, ids = search(self.conn, **kwargs)
        return total, self.products(ids, base, compact)

    def _options(self, ids: list[str], marks: str, rows: dict) -> dict[str, list[OptionAxisOut]]:
        """{상품 id: 옵션 축 목록}. 값 재고가 비어 있으면 상품 재고를 따른다."""
        out: dict[str, dict[str, list[OptionValueOut]]] = {}
        for r in self.conn.execute(
            f"select * from product_options where product_id in ({marks}) order by product_id, axis_order, value_order", ids
        ):
            stock = r["stock"] or rows[r["product_id"]]["stock_status"]
            out.setdefault(r["product_id"], {}).setdefault(r["axis"], []).append(
                OptionValueOut(value=r["value"], priceAdd=r["price_add"], stock=stock))
        return {pid: [OptionAxisOut(name=a, values=v) for a, v in axes.items()] for pid, axes in out.items()}

    def _image(self, r: sqlite3.Row, base: str) -> str:
        if r["image"]:
            return r["image"] if r["image"].startswith("http") else base + r["image"]
        if (STATIC_DIR / "products" / f"{r['id']}.webp").exists():
            return f"{base}/static/products/{r['id']}.webp"
        return f"{base}/static/icons/{r['main_id']}.svg"  # 상품 사진이 생기기 전까지 대분류 그림

    def _to_compact(self, r: sqlite3.Row, options: list[OptionAxisOut], base: str) -> ProductCompact:
        _, spoken = arrive(today_kst(), r["delivery_days"])
        return ProductCompact(
            id=r["id"],
            name=r["name"],
            brand=r["brand"],
            sub=r["sub_name"],
            price=r["price"],
            priceSpoken=read_won(r["price"]),
            tier=TIER_LABEL[r["price_tier"]],
            discount=r["discount_rate"],
            rating=r["rating"],
            reviews=r["review_count"],
            rankInMid=r["rank_in_mid"],
            badges=json.loads(r["badges"]),
            arrive=spoken,
            stock=r["stock_status"],
            gift=bool(r["is_gift"]),
            image=self._image(r, base),
            options=[OptionBrief(name=a.name, values=[v.value for v in a.values if v.stock != "sold_out"]) for a in options],
        )

    def _to_out(self, r: sqlite3.Row, tags: list[str], options: list[OptionAxisOut], base: str) -> ProductOut:
        label, spoken = arrive(today_kst(), r["delivery_days"])
        image = self._image(r, base)
        return ProductOut(
            productId=r["id"],
            productName=r["name"],
            brand=r["brand"],
            category=CategoryRefOut(
                main=Named(id=r["main_id"], name=r["main_name"]),
                mid=Named(id=r["mid_id"], name=r["mid_name"]),
                sub=Named(id=r["sub_id"], name=r["sub_name"]),
            ),
            origin=r["origin"],
            audience=r["audience"],
            isGift=bool(r["is_gift"]),
            pricing=PricingOut(
                price=r["price"],
                originalPrice=r["original_price"],
                discountRate=r["discount_rate"],
                unitPrice=UnitPrice(value=r["unit_price"], per=r["unit_per"]),
                priceTier=PriceTier(level=r["price_tier"], label=TIER_LABEL[r["price_tier"]], percentile=r["price_percentile"]),
            ),
            spec=Spec(text=r["spec_text"], quantity=_num(r["spec_qty"]), unit=r["spec_unit"]),
            delivery=DeliveryOut(
                isRocket=bool(r["is_rocket"]),
                isFreeShipping=bool(r["is_free_shipping"]),
                shippingFee=r["shipping_fee"],
                deliveryDays=r["delivery_days"],
                arriveLabel=label,
                arriveSpoken=spoken,
            ),
            stats=StatsOut(
                salesCount30d=r["sales_30d"],
                salesCountTotal=r["sales_total"],
                rating=r["rating"],
                reviewCount=r["review_count"],
                repurchaseRate=r["repurchase_rate"],
                rankInSub=r["rank_in_sub"],
                rankInMid=r["rank_in_mid"],
                rankInMain=r["rank_in_main"],
                rankOverall=r["rank_overall"],
                badges=json.loads(r["badges"]),
            ),
            content=Content(description=r["description"], reviewSummary=r["review_summary"], tags=tags),
            stock=Stock(status=r["stock_status"], quantity=r["stock_qty"]),
            options=options,
            productImage=image,
            source=r["source"],
        )

    # ---- 주문 ----

    def place_order(self, user_id: str, product_id: str, quantity: int, chosen: dict[str, str]) -> tuple[str, str, int, str]:
        """(주문번호, 상품명, 합계, 주문 시각). 받을 수 없으면 OrderError."""
        row = self.conn.execute("select name, price, shipping_fee, stock_status from products where id = ?", (product_id,)).fetchone()
        if row is None:
            raise OrderError(404, {"message": f"없는 상품: {product_id}"})
        if row["stock_status"] == "sold_out":
            raise OrderError(409, {"message": "품절된 상품이다"})
        axes = self._options([product_id], "?", {product_id: row}).get(product_id, [])

        # 축이 빠졌거나 없는 값이면 422. 앱 에이전트가 choices 로 되묻는다("사이즈는 M, L, XL 이 있어요")
        unknown_axes = sorted(set(chosen) - {a.name for a in axes})
        missing = [a for a in axes if a.name not in chosen]
        invalid = [a for a in axes if a.name in chosen and chosen[a.name] not in {v.value for v in a.values}]
        if unknown_axes or missing or invalid:
            raise OrderError(422, {
                "message": "옵션을 골라야 한다",
                "unknownOptions": unknown_axes,
                "missing": [a.name for a in missing],
                "invalid": {a.name: chosen[a.name] for a in invalid},
                "choices": {a.name: [v.value for v in a.values if v.stock != "sold_out"] for a in missing + invalid},
            })
        picked = [next(v for v in a.values if v.value == chosen[a.name]) for a in axes]
        sold_out = [f"{a.name} {v.value}" for a, v in zip(axes, picked, strict=True) if v.stock == "sold_out"]
        if sold_out:
            raise OrderError(409, {
                "message": "고른 옵션이 품절이다",
                "soldOut": sold_out,
                "choices": {a.name: [v.value for v in a.values if v.stock != "sold_out"] for a in axes},
            })

        now = datetime.now(KST)
        total = (row["price"] + sum(v.priceAdd for v in picked)) * quantity + row["shipping_fee"]
        ordered_at = now.isoformat(timespec="seconds")
        opts = json.dumps(chosen, ensure_ascii=False)
        for _ in range(20):
            order_id = f"M-{now:%Y%m%d}-{random.randint(1000, 9999)}"
            try:
                self.conn.execute(
                    "insert into orders (orderId, userId, productId, quantity, totalPrice, orderedAt, options)"
                    " values (?, ?, ?, ?, ?, ?, ?)",
                    (order_id, user_id, product_id, quantity, total, ordered_at, opts))
                self.conn.commit()
                return order_id, row["name"], total, ordered_at
            except sqlite3.IntegrityError:
                continue  # 같은 날 주문번호가 겹치면 다시 뽑는다
        raise RuntimeError("주문번호를 만들지 못했다")

    def orders_of(self, user_id: str, limit: int) -> list[dict]:
        rows = self.conn.execute(
            "select o.*, coalesce(p.name, '(단종된 상품)') as productName from orders o"
            " left join products p on p.id = o.productId where o.userId = ? order by o.orderedAt desc limit ?",
            (user_id, limit),
        ).fetchall()
        return [{**dict(r), "options": json.loads(r["options"] or "{}")} for r in rows]


def _num(x: float) -> int | float:
    """SQLite REAL 로 저장된 수량을 정수면 정수로 돌린다(12.0 -> 12)."""
    return int(x) if float(x).is_integer() else x
