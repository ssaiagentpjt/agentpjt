"""PC 에서 앱과 같은 Gemma 모델을 돌리는 작은 HTTP 다리.

앱의 에이전트 코드(행동·스키마·루프·실행기·서버 호출)는 순수 Kotlin 이라 PC(JVM)에서 그대로 돈다.
모델만 PC 에 없으므로, 같은 LiteRT-LM(0.17.1, PyPI)과 같은 모델 파일로 이 다리를 띄우고
Kotlin 쪽 BridgeDecider 가 여기에 묻는다. 폰 없이 에이전트 루프를 시험하려는 개발 도구다.

  POST /reset {"system": "..."}               대화를 새로 연다(앱의 LiteRtDecider.reset 과 같다)
  POST /next  {"message": "...", "schema": {}} 메시지 하나 → 스키마로 묶인 행동 JSON 하나
             → {"text": "...", "ms": 1234}

실행: .venv\\Scripts\\python bridge.py --model C:\\dev\\models\\gemma-4-E2B-it.litertlm
"""

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import litert_lm as lm

_lock = threading.Lock()  # 대화는 동시에 쓰면 안 된다(앱의 AgentLoop lock 과 같다)
_engine: lm.Engine | None = None
_conv = None


def _reset(system: str) -> None:
    global _conv
    if _conv is not None:
        _conv.close()
    # 앱과 같게 생각 모드는 넣지 않는다(thinking budget + ResponseFormat 은 실패, LiteRT-LM #3463)
    _conv = _engine.create_conversation(
        system_message=system,
        constrained_decoding_config=lm.ConstrainedDecodingConfig(enable=True, provider=lm.LiteRtLmConstraintProviderType.LL_GUIDANCE),
    )


def _text(resp) -> str:
    # send_message 는 {"role": ..., "content": [{"type": "text", "text": ...}]} 모양의 dict 를 돌려준다
    content = resp.get("content", []) if isinstance(resp, dict) else []
    if isinstance(content, str):
        return content.strip()
    return "".join(c.get("text", "") for c in content if isinstance(c, dict)).strip()


class Handler(BaseHTTPRequestHandler):
    def _send(self, code: int, body: dict) -> None:
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):  # noqa: N802 (http.server 규약)
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        with _lock:
            try:
                if self.path == "/reset":
                    _reset(body["system"])
                    return self._send(200, {"ok": True})
                if self.path == "/next":
                    if _conv is None:
                        return self._send(400, {"error": "reset 을 먼저 불러야 한다"})
                    t0 = time.perf_counter()
                    resp = _conv.send_message(body["message"], response_format=lm.ResponseFormat.json(body["schema"]))
                    return self._send(200, {"text": _text(resp), "ms": int((time.perf_counter() - t0) * 1000)})
                return self._send(404, {"error": self.path})
            except Exception as e:  # 오류는 Kotlin 쪽 로그로 넘긴다
                return self._send(500, {"error": f"{type(e).__name__}: {e}"})

    def log_message(self, *args):  # 요청마다 찍히는 기본 로그는 끈다
        pass


def main() -> None:
    global _engine
    p = argparse.ArgumentParser()
    p.add_argument("--model", required=True)
    p.add_argument("--port", type=int, default=8765)
    a = p.parse_args()
    t0 = time.perf_counter()
    _engine = lm.Engine(a.model, backend=lm.Backend.CPU())
    print(f"model ready {int((time.perf_counter() - t0) * 1000)}ms, http://127.0.0.1:{a.port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
