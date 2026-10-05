"""금액을 읽는 말로 바꾼다. 앱 KoreanSpeech.readWon 과 같은 규칙이다.

1단계 실측에서 Gemma 가 9,900원을 "만구천 원"으로 읽는 오류가 있어 숫자는 모델에 맡기지 않는다.
compact 응답의 priceSpoken 에 쓴다. 규칙을 바꾸면 앱 쪽(app/src/main/java/.../shop/KoreanSpeech.kt)도 함께 바꾼다.
"""

_DIGITS = ["", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구"]
_UNITS = ["천", "백", "십", ""]


def _read_four(n: int) -> str:
    s = str(n).zfill(4)
    out = []
    for i, ch in enumerate(s):
        v = int(ch)
        if v == 0:
            continue
        if not (v == 1 and _UNITS[i]):  # 천·백·십 앞의 "일"은 생략한다(일천 -> 천)
            out.append(_DIGITS[v])
        out.append(_UNITS[i])
    return "".join(out)


def read_won(amount: int) -> str:
    """9900 -> "구천구백 원", 21500 -> "이만 천오백 원". 1억 이상은 다루지 않는다."""
    if not 0 <= amount < 100_000_000:
        raise ValueError(f"범위 밖 금액: {amount}")
    man, rest = divmod(amount, 10_000)
    parts = []
    if man:
        parts.append(("" if man == 1 else _read_four(man)) + "만")
    if rest:
        parts.append(_read_four(rest))
    return (" ".join(parts) or "영") + " 원"


_COUNTS = ["", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉"]


def ko_count(n: int) -> str:
    """개수를 세는 말. 1 -> "한", 2 -> "두". 10 이상은 숫자 그대로. 앱 KoreanSpeech.koCount 와 같다."""
    return _COUNTS[n] if 1 <= n < len(_COUNTS) else str(n)
