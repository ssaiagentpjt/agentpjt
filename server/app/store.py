"""조회 계층. 시작할 때 시드를 SQLite 에 적재하고(app/db.py), 행을 응답 객체(full / compact)로 바꾼다."""

import json
import random
import secrets
import sqlite3
import threading
from collections.abc import Callable
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta
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
    """주문을 받을 수 없을 때. detail 은 앱 에이전트가 되묻는 데 쓸 수 있게 고를 수 있는 값까지 담는다.

    detail["code"] 는 앱이 문구를 보지 않고 갈래를 나누는 데 쓴다(MISSING_OPTION 이면 되묻기, OUT_OF_STOCK 이면 다른 상품 권하기).
    """

    def __init__(self, status: int, code: str, detail: dict):
        super().__init__(detail.get("message", ""))
        self.status = status
        self.detail = {"code": code, **detail}


def now_kst() -> datetime:
    return datetime.now(KST)


LOW_STOCK = 20  # validate.py 와 같은 기준: 1~20 이면 low(품절 임박), 21 이상이면 in_stock
TOKEN_TTL = timedelta(minutes=10)  # 확인 화면에서 금액을 듣고 답하기에 충분하고, 오래된 확인으로 주문되지 않을 만큼


def stock_status(quantity: int) -> str:
    return "sold_out" if quantity <= 0 else "low" if quantity <= LOW_STOCK else "in_stock"


@dataclass(frozen=True)
class Line:
    """검사를 통과한 주문 한 줄."""

    product_id: str
    name: str
    quantity: int
    options: dict[str, str]
    unit_price: int  # 가격 + 고른 옵션의 추가 금액
    shipping_fee: int
    delivery_days: int

    @property
    def total(self) -> int:
        return self.unit_price * self.quantity + self.shipping_fee


class Store:
    def __init__(self, data_dir: Path, db_path: str, clock: Callable[[], datetime] = now_kst):
        # FastAPI 는 동기 엔드포인트를 스레드 풀에서 돌리므로 같은 연결을 여러 스레드가 쓴다
        self.conn = sqlite3.connect(db_path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        # 주문 쓰기(검사 → 기록 → 커밋)는 한 번에 하나씩 한다. 연결 하나를 나눠 쓰므로 잠그지 않으면 트랜잭션이 섞이고,
        # 같은 요청이 동시에 두 번 와도 둘 다 검사를 통과해 버린다
        self.order_lock = threading.Lock()
        # 토큰 만료를 테스트에서 시간을 옮겨 가며 검사하려고 시계를 받는다
        self.clock = clock
        db.rebuild(self.conn, data_dir)
        for o in db.load_seed_orders(data_dir):
            row = self.conn.execute("select price, shipping_fee from products where id = ?", (o.productId,)).fetchone()
            if row is None:
                raise ValueError(f"seed_orders 의 없는 상품: {o.productId}")
            self.conn.execute(
                "insert or ignore into orders (orderId, userId, productId, quantity, totalPrice, orderedAt, status, unitPrice, shippingFee)"
                " values (?, ?, ?, ?, ?, ?, 'DELIVERED', ?, ?)",
                (o.orderId, o.userId, o.productId, o.quantity, row["price"] * o.quantity + row["shipping_fee"], o.orderedAt,
                 row["price"], row["shipping_fee"]),
            )
            # 시드의 지난 구매는 이미 받은 것으로 본다(취소할 수 없다). status 열이 생기기 전에 들어간 행도 고친다
            self.conn.execute("update orders set status = 'DELIVERED' where orderId = ? and status = 'CONFIRMED'", (o.orderId,))
        # 시드로 다시 만든 재고에 지금까지의 주문·취소를 반영한다. 시드에서 사라진 상품의 기록은 건너뛴다
        for r in self.conn.execute("select productId, sum(delta) as d from stock_ledger group by productId").fetchall():
            self._set_stock(r["productId"], r["d"])
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

    def _check_line(self, product_id: str, quantity: int, chosen: dict[str, str]) -> Line:
        """주문 한 줄의 상품·옵션을 검사한다. 받을 수 없으면 OrderError. 재고 수량은 _check_lines 가 줄을 모아서 본다."""
        row = self.conn.execute(
            "select name, price, shipping_fee, delivery_days, stock_status from products where id = ?", (product_id,)).fetchone()
        if row is None:
            raise OrderError(404, "PRODUCT_NOT_FOUND", {"message": f"없는 상품: {product_id}"})
        if row["stock_status"] == "sold_out":
            raise OrderError(409, "OUT_OF_STOCK", {"message": "품절된 상품이다"})
        axes = self._options([product_id], "?", {product_id: row}).get(product_id, [])

        # 축이 빠졌거나 없는 값이면 422. 앱 에이전트가 choices 로 되묻는다("사이즈는 M, L, XL 이 있어요")
        unknown_axes = sorted(set(chosen) - {a.name for a in axes})
        missing = [a for a in axes if a.name not in chosen]
        invalid = [a for a in axes if a.name in chosen and chosen[a.name] not in {v.value for v in a.values}]
        if unknown_axes or missing or invalid:
            raise OrderError(422, "MISSING_OPTION", {
                "message": "옵션을 골라야 한다",
                "unknownOptions": unknown_axes,
                "missing": [a.name for a in missing],
                "invalid": {a.name: chosen[a.name] for a in invalid},
                "choices": {a.name: [v.value for v in a.values if v.stock != "sold_out"] for a in missing + invalid},
            })
        picked = [next(v for v in a.values if v.value == chosen[a.name]) for a in axes]
        sold_out = [f"{a.name} {v.value}" for a, v in zip(axes, picked, strict=True) if v.stock == "sold_out"]
        if sold_out:
            raise OrderError(409, "OUT_OF_STOCK", {
                "message": "고른 옵션이 품절이다",
                "soldOut": sold_out,
                "choices": {a.name: [v.value for v in a.values if v.stock != "sold_out"] for a in axes},
            })
        return Line(product_id, row["name"], quantity, chosen, row["price"] + sum(v.priceAdd for v in picked),
                    row["shipping_fee"], row["delivery_days"])

    def _check_lines(self, items: list[tuple[str, int, dict[str, str]]], indexed: bool) -> list[Line]:
        """모든 줄을 검사하고, 같은 상품은 수량을 합쳐 재고와 견준다. indexed 면 실패한 줄의 index·productId 를 붙인다."""
        lines = []
        for i, (product_id, quantity, chosen) in enumerate(items):
            try:
                lines.append(self._check_line(product_id, quantity, chosen))
            except OrderError as e:
                if not indexed:
                    raise
                raise OrderError(e.status, e.detail["code"], {**e.detail, "index": i, "productId": product_id}) from e
        need: dict[str, int] = {}
        for line in lines:
            need[line.product_id] = need.get(line.product_id, 0) + line.quantity
        for i, line in enumerate(lines):
            have = self.conn.execute("select stock_qty from products where id = ?", (line.product_id,)).fetchone()[0]
            if need[line.product_id] > have:
                detail = {"message": "재고가 모자란다", "available": have}
                if indexed:
                    detail |= {"index": i, "productId": line.product_id}
                raise OrderError(409, "OUT_OF_STOCK", detail)
        return lines

    def _set_stock(self, product_id: str, delta: int) -> None:
        """재고 수량을 delta 만큼 바꾸고 상태(in_stock · low · sold_out)를 다시 정한다. 커밋은 부른 쪽이 한다."""
        row = self.conn.execute("select stock_qty from products where id = ?", (product_id,)).fetchone()
        if row is None:
            return
        qty = max(0, row[0] + delta)
        self.conn.execute("update products set stock_qty = ?, stock_status = ? where id = ?", (qty, stock_status(qty), product_id))

    def _move_stock(self, product_id: str, delta: int, order_id: str, reason: str, now: datetime) -> None:
        """재고를 바꾸고 기록을 남긴다. 주문은 음수, 취소는 양수."""
        self._set_stock(product_id, delta)
        self.conn.execute("insert into stock_ledger (productId, delta, orderId, reason, at) values (?, ?, ?, ?, ?)",
                          (product_id, delta, order_id, reason, now.isoformat(timespec="seconds")))

    def _insert_order(self, user_id: str, line: Line, now: datetime, token: str | None = None) -> str:
        """주문 한 줄을 넣고 재고를 줄인 뒤 주문번호를 돌려준다. 커밋은 부른 쪽이 한다."""
        opts = json.dumps(line.options, ensure_ascii=False)
        for _ in range(20):
            order_id = f"M-{now:%Y%m%d}-{random.randint(1000, 9999)}"
            try:
                self.conn.execute(
                    "insert into orders (orderId, userId, productId, quantity, totalPrice, orderedAt, options,"
                    " token, unitPrice, shippingFee) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (order_id, user_id, line.product_id, line.quantity, line.total, now.isoformat(timespec="seconds"), opts,
                     token, line.unit_price, line.shipping_fee))
                break
            except sqlite3.IntegrityError:
                continue  # 같은 날 주문번호가 겹치면 다시 뽑는다
        else:
            raise RuntimeError("주문번호를 만들지 못했다")
        self._move_stock(line.product_id, -line.quantity, order_id, "ORDER", now)
        return order_id

    def place_order(self, user_id: str, product_id: str, quantity: int, chosen: dict[str, str]) -> tuple[str, str, int, str]:
        """(주문번호, 상품명, 합계, 주문 시각). 받을 수 없으면 OrderError."""
        with self.order_lock:
            [line] = self._check_lines([(product_id, quantity, chosen)], indexed=False)
            now = self.clock()
            try:
                order_id = self._insert_order(user_id, line, now)
                self.conn.commit()
            except Exception:
                self.conn.rollback()
                raise
        return order_id, line.name, line.total, now.isoformat(timespec="seconds")

    def place_batch(self, user_id: str, items: list[tuple[str, int, dict[str, str]]]) -> list[tuple[str, str, int, str]]:
        """여러 줄을 한꺼번에 주문한다(장바구니 결제). 모든 줄을 먼저 검사하고, 하나라도 안 되면 아무것도 넣지 않는다.
        실패한 줄은 OrderError detail 의 index·productId 로 알린다. 성공하면 줄마다 (주문번호, 상품명, 합계, 주문 시각)."""
        with self.order_lock:
            lines = self._check_lines(items, indexed=True)
            now = self.clock()
            at = now.isoformat(timespec="seconds")
            try:
                out = [(self._insert_order(user_id, line, now), line.name, line.total, at) for line in lines]
                self.conn.commit()
            except Exception:
                self.conn.rollback()  # 일부만 들어가는 일이 없게 한다
                raise
        return out

    # ---- 주문 2단계: prepare → confirm ----

    def prepare(self, user_id: str, items: list[tuple[str, int, dict[str, str]]]) -> tuple[str, str, list[Line]]:
        """주문을 검사하고 확인 토큰을 만든다. 주문도 재고 차감도 하지 않는다. (토큰, 만료 시각, 줄)

        같은 사용자의 대기 중인 토큰은 무효로 한다. 확인 화면을 다시 열었으면 앞의 확인은 더 이상 유효하지 않다.
        """
        with self.order_lock:
            lines = self._check_lines(items, indexed=True)
            now = self.clock()
            token = secrets.token_urlsafe(16)  # 추측할 수 없게 한다. 지어낸 토큰으로 주문되지 않아야 한다
            expires = (now + TOKEN_TTL).isoformat(timespec="seconds")
            try:
                self.conn.execute("update order_tokens set status = 'REVOKED' where userId = ? and status = 'PENDING'", (user_id,))
                self.conn.execute(
                    "insert into order_tokens (token, userId, items, totalPrice, status, createdAt, expiresAt)"
                    " values (?, ?, ?, ?, 'PENDING', ?, ?)",
                    (token, user_id, json.dumps([asdict(x) for x in lines], ensure_ascii=False), sum(x.total for x in lines),
                     now.isoformat(timespec="seconds"), expires))
                self.conn.commit()
            except Exception:
                self.conn.rollback()
                raise
        return token, expires, lines

    def confirm(self, user_id: str, token: str) -> tuple[list[dict], bool]:
        """확인 토큰으로 주문을 확정한다. (주문 행들, 이미 확정됐었는지)

        같은 토큰이 다시 오면(LLM 재시도·네트워크 재전송) 새로 만들지 않고 그때 만든 주문을 돌려준다.
        """
        with self.order_lock:
            row = self.conn.execute("select * from order_tokens where token = ?", (token,)).fetchone()
            # 다른 사용자의 토큰도 "없다"로 답한다. 토큰이 있다는 것조차 알려 주지 않는다
            if row is None or row["userId"] != user_id:
                raise OrderError(404, "TOKEN_NOT_FOUND", {"message": "주문 확인 토큰이 없다. 주문 확인부터 다시 한다"})
            if row["status"] == "USED":
                return self._orders_where("o.token = ?", (token,)), True
            if row["status"] == "REVOKED":
                raise OrderError(409, "TOKEN_REVOKED", {"message": "더 새로운 주문 확인이 있어 이 토큰은 무효다"})
            now = self.clock()
            if now >= datetime.fromisoformat(row["expiresAt"]):
                raise OrderError(410, "TOKEN_EXPIRED", {"message": "주문 확인 시간(10분)이 지났다. 주문 확인부터 다시 한다"})

            lines = [Line(**x) for x in json.loads(row["items"])]
            # prepare 뒤에 품절되거나 다른 주문이 재고를 가져갔을 수 있어 다시 검사한다. 금액은 prepare 때 것을 쓴다
            self._check_lines([(x.product_id, x.quantity, x.options) for x in lines], indexed=True)
            try:
                # 잠금 안이라 경쟁은 없지만, 토큰 상태 전이를 한 번 더 조건으로 건다: PENDING 에서 바꾼 한 번만 주문을 만든다
                if self.conn.execute("update order_tokens set status = 'USED' where token = ? and status = 'PENDING'",
                                     (token,)).rowcount != 1:
                    self.conn.rollback()
                    return self._orders_where("o.token = ?", (token,)), True
                for x in lines:
                    self._insert_order(user_id, x, now, token)
                self.conn.commit()
            except Exception:
                self.conn.rollback()
                raise
        return self._orders_where("o.token = ?", (token,)), False

    # ---- 취소 ----

    def cancel(self, user_id: str, order_id: str) -> dict:
        """확정된 주문 한 줄을 취소하고 재고를 돌려놓는다. 취소한 주문 행을 돌려준다."""
        with self.order_lock:
            rows = self._orders_where("o.orderId = ?", (order_id,))
            # 다른 사용자의 주문도 "없다"로 답한다
            if not rows or rows[0]["userId"] != user_id:
                raise OrderError(404, "ORDER_NOT_FOUND", {"message": f"없는 주문: {order_id}"})
            row = rows[0]
            if row["status"] == "CANCELLED":
                raise OrderError(409, "ALREADY_CANCELLED", {"message": "이미 취소한 주문이다"})
            if row["status"] != "CONFIRMED":
                raise OrderError(409, "NOT_CANCELLABLE", {"message": "배송이 끝난 주문은 취소할 수 없다"})
            now = self.clock()
            try:
                self.conn.execute("update orders set status = 'CANCELLED', cancelledAt = ? where orderId = ?",
                                  (now.isoformat(timespec="seconds"), order_id))
                # 이 주문으로 줄어든 만큼만 돌려놓는다. 재고 기록이 생기기 전의 주문은 줄인 적이 없으므로 그대로 둔다
                taken = self.conn.execute("select coalesce(sum(delta), 0) from stock_ledger where orderId = ?", (order_id,)).fetchone()[0]
                if taken < 0:
                    self._move_stock(row["productId"], -taken, order_id, "CANCEL", now)
                self.conn.commit()
            except Exception:
                self.conn.rollback()
                raise
        return self._orders_where("o.orderId = ?", (order_id,))[0]

    # ---- 주문 조회 ----

    def _orders_where(self, where: str, params: tuple, limit: int = 100) -> list[dict]:
        rows = self.conn.execute(
            "select o.*, coalesce(p.name, '(단종된 상품)') as productName from orders o"
            f" left join products p on p.id = o.productId where {where} order by o.orderedAt desc, o.rowid limit ?",
            (*params, limit),
        ).fetchall()
        return [{**dict(r), "options": json.loads(r["options"] or "{}")} for r in rows]

    def orders_of(self, user_id: str, limit: int) -> list[dict]:
        return self._orders_where("o.userId = ?", (user_id,), limit)


def _num(x: float) -> int | float:
    """SQLite REAL 로 저장된 수량을 정수면 정수로 돌린다(12.0 -> 12)."""
    return int(x) if float(x).is_integer() else x
