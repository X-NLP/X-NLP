#!/usr/bin/env python3
"""Deterministic OpenAI-compatible chat/embedding and rerank provider for X-NLP E2E."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


class Handler(BaseHTTPRequestHandler):
    server_version = "XNLPMockOpenAI/2.0"

    def log_message(self, fmt: str, *args: Any) -> None:
        print("mock-openai:", fmt % args, flush=True)

    def _json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _request_json(self) -> dict[str, Any] | None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
            value = json.loads(self.rfile.read(length) or b"{}")
            return value if isinstance(value, dict) else None
        except (ValueError, json.JSONDecodeError):
            return None

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
                        {"id": "xnlp-e2e-chat", "object": "model", "created": 0, "owned_by": "xnlp-e2e"},
                        {"id": "xnlp-e2e-embedding", "object": "model", "created": 0, "owned_by": "xnlp-e2e"},
                        {"id": "xnlp-e2e-reranker", "object": "model", "created": 0, "owned_by": "xnlp-e2e"},
                    ],
                },
            )
            return
        self._json(404, {"error": {"message": "not found", "type": "not_found"}})

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.rstrip("/")
        request = self._request_json()
        if request is None:
            self._json(400, {"error": {"message": "invalid JSON", "type": "invalid_request_error"}})
            return
        if path in {"/chat/completions", "/v1/chat/completions"}:
            self._chat(request)
            return
        if path in {"/embeddings", "/v1/embeddings"}:
            self._embeddings(request)
            return
        if path in {"/rerank", "/v1/rerank"}:
            self._rerank(request)
            return
        self._json(404, {"error": {"message": "not found", "type": "not_found"}})

    def _chat(self, request: dict[str, Any]) -> None:
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

    def _embeddings(self, request: dict[str, Any]) -> None:
        raw_input = request.get("input", "")
        inputs = raw_input if isinstance(raw_input, list) else [raw_input]
        vectors = []
        total_tokens = 0
        for index, value in enumerate(inputs):
            text = value if isinstance(value, str) else json.dumps(value, sort_keys=True)
            total_tokens += max(len(text.split()), 1)
            vectors.append(
                {"object": "embedding", "index": index, "embedding": deterministic_embedding(text)}
            )
        self._json(
            200,
            {
                "object": "list",
                "model": request.get("model") or "xnlp-e2e-embedding",
                "data": vectors,
                "usage": {"prompt_tokens": total_tokens, "total_tokens": total_tokens},
            },
        )

    def _rerank(self, request: dict[str, Any]) -> None:
        documents = request.get("documents") or []
        top_n = min(int(request.get("top_n") or len(documents)), len(documents))
        results = []
        for rank, index in enumerate(reversed(range(len(documents)))):
            if len(results) >= top_n:
                break
            results.append({"index": index, "relevance_score": 0.99 - rank * 0.05})
        self._json(200, {"id": "rerank-xnlp-e2e", "results": results})


def deterministic_embedding(text: str) -> list[float]:
    """Stable normalized vector with useful lexical separation and no external dependency."""
    vector = [0.0] * 8
    normalized = text.lower()
    keywords = ["database", "spring", "retrieval", "rerank"]
    for index, keyword in enumerate(keywords):
        if keyword in normalized:
            vector[index] += 2.0
    digest = hashlib.sha256(normalized.encode("utf-8")).digest()
    for index in range(4, 8):
        vector[index] = 0.25 + digest[index] / 1020.0
    if not any(vector[:4]):
        vector[digest[0] % 4] = 1.0
    magnitude = math.sqrt(sum(value * value for value in vector)) or 1.0
    return [round(value / magnitude, 8) for value in vector]


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
