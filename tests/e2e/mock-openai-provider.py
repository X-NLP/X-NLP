#!/usr/bin/env python3
"""Deterministic OpenAI-compatible provider used by local X-NLP E2E tests."""

from __future__ import annotations

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


class Handler(BaseHTTPRequestHandler):
    server_version = "XNLPMockOpenAI/1.0"

    def log_message(self, fmt: str, *args: Any) -> None:
        print("mock-openai:", fmt % args, flush=True)

    def _json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path.rstrip("/") in {"", "/health"}:
            self._json(200, {"status": "ok"})
            return
        if self.path.rstrip("/") in {"/models", "/v1/models"}:
            self._json(
                200,
                {
                    "object": "list",
                    "data": [
                        {
                            "id": "xnlp-e2e-chat",
                            "object": "model",
                            "created": 0,
                            "owned_by": "xnlp-e2e",
                        }
                    ],
                },
            )
            return
        self._json(404, {"error": {"message": "not found", "type": "not_found"}})

    def do_POST(self) -> None:  # noqa: N802
        if self.path.rstrip("/") not in {"/chat/completions", "/v1/chat/completions"}:
            self._json(404, {"error": {"message": "not found", "type": "not_found"}})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            request = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            self._json(400, {"error": {"message": "invalid JSON", "type": "invalid_request_error"}})
            return

        messages = request.get("messages") or []
        text = ""
        if messages and isinstance(messages[-1], dict):
            content = messages[-1].get("content", "")
            if isinstance(content, str):
                text = content
            elif isinstance(content, list):
                text = " ".join(
                    part.get("text", "")
                    for part in content
                    if isinstance(part, dict) and isinstance(part.get("text"), str)
                )
        output = f"mock-response: {text}".strip()
        self._json(
            200,
            {
                "id": "chatcmpl-xnlp-e2e",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": request.get("model") or "xnlp-e2e-chat",
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": output},
                        "finish_reason": "stop",
                    }
                ],
                "usage": {
                    "prompt_tokens": max(len(text.split()), 1),
                    "completion_tokens": max(len(output.split()), 1),
                    "total_tokens": max(len(text.split()), 1) + max(len(output.split()), 1),
                },
            },
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18880)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"mock-openai: listening on http://{args.host}:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
