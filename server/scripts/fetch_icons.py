"""categories.json 대분류의 아이콘을 Iconify 에서 받아 app/static/icons/<대분류 id>.svg 로 저장한다.

실행: python scripts/fetch_icons.py   (server/ 에서)

받은 SVG 는 저장소에 커밋한다. 서버가 런타임에 외부(Iconify)를 부르지 않게 하기 위해서다.
세트는 Fluent Emoji Flat(MIT). 크기는 256px 로 받는다 — Android 의 SVG 디코더가 1em 같은 상대 크기를 못 읽을 수 있다.
"""

import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ICON_SET = "fluent-emoji-flat"
OUT = ROOT / "app" / "static" / "icons"

LICENSE = """\
Category icons in this folder are from Fluent Emoji Flat by Microsoft,
downloaded through the Iconify API (https://api.iconify.design/fluent-emoji-flat/<name>.svg).
License: MIT — https://github.com/microsoft/fluentui-emoji/blob/main/LICENSE

file -> icon name
"""


def main() -> int:
    categories = json.loads((ROOT / "data" / "categories.json").read_text(encoding="utf-8"))
    OUT.mkdir(parents=True, exist_ok=True)
    lines, failed = [], []
    for c in categories:
        url = f"https://api.iconify.design/{ICON_SET}/{c['icon']}.svg?height=256"
        # Iconify 는 urllib 기본 User-Agent(Python-urllib)를 403 으로 막는다
        req = urllib.request.Request(url, headers={"User-Agent": "agentpjt-mock-server/0.1 (icon fetch)"})
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                svg = r.read()
        except urllib.error.HTTPError as e:
            failed.append(f"{c['id']}: {c['icon']} ({e.code})")
            continue
        (OUT / f"{c['id']}.svg").write_bytes(svg)
        lines.append(f"{c['id']}.svg -> {c['icon']}")
        print(f"ok   {c['id']:<16} {c['icon']}")
    (OUT / "LICENSE.txt").write_text(LICENSE + "\n".join(lines) + "\n", encoding="utf-8")
    for f in failed:
        print(f"FAIL {f}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
