#!/usr/bin/env python3
"""
Stress test a reranker model behind the X-NLP HTTP API.

Default target:
  POST {base_url}/api/v1/models/{model}/predict

The default payload includes both:
  - text: a reranking prompt, for current X-NLP ChatModel-backed predict APIs
  - parameters: structured rerank input, for reranker-aware backends
"""

from __future__ import annotations

import argparse
import json
import math
import os
import queue
import random
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


DEFAULT_QUERY = "Which document best explains how to benchmark reranker latency?"
DEFAULT_DOCUMENTS = [
    "Reranker models score query-document pairs and are often measured with latency percentiles under concurrent load.",
    "A text classifier assigns one label to each input text and is usually evaluated with accuracy or F1.",
    "Embedding models convert text into vectors that can be used for nearest-neighbor retrieval.",
    "A load test should include warmup traffic, concurrency, request timeout handling, and p95 or p99 latency reporting.",
]


@dataclass(frozen=True)
class RequestResult:
    ok: bool
    status: int
    latency_ms: float
    response_bytes: int
    error: str | None = None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Stress test an X-NLP reranker model endpoint.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get("XNLP_API_BASE", "http://127.0.0.1:8080"),
        help="X-NLP API base URL.",
    )
    parser.add_argument(
        "--model",
        default=os.environ.get("XNLP_RERANKER_MODEL"),
        help="Model name. Can also be set via XNLP_RERANKER_MODEL.",
    )
    parser.add_argument(
        "--endpoint",
        default="/api/v1/models/{model}/predict",
        help="Endpoint path or absolute URL. Supports {model}.",
    )
    parser.add_argument("--requests", type=int, default=100, help="Measured request count.")
    parser.add_argument("--duration-seconds", type=float, help="Run measured load for this many seconds.")
    parser.add_argument("--concurrency", type=int, default=8, help="Concurrent worker count.")
    parser.add_argument("--warmup", type=int, default=0, help="Warmup requests before measurement.")
    parser.add_argument("--timeout", type=float, default=30.0, help="Per-request timeout in seconds.")
    parser.add_argument("--query", default=DEFAULT_QUERY, help="Rerank query.")
    parser.add_argument(
        "--doc",
        action="append",
        dest="documents",
        help="Candidate document. Repeat to pass multiple documents.",
    )
    parser.add_argument(
        "--documents-file",
        type=Path,
        help="File containing documents: JSON array, JSONL, or plain text split by blank lines.",
    )
    parser.add_argument("--top-k", type=int, default=3, help="Requested top-k rerank results.")
    parser.add_argument(
        "--payload-file",
        type=Path,
        help=(
            "JSON payload template. May be an object or a list of objects. "
            "String values support {query}, {documents}, {documents_json}, {top_k}, {model}, {request_id}."
        ),
    )
    parser.add_argument("--header", action="append", default=[], help="Extra HTTP header, for example 'Authorization: Bearer ...'.")
    parser.add_argument("--output-json", type=Path, help="Write summary and raw latency data to a JSON file.")
    parser.add_argument("--fail-on-errors", action="store_true", help="Exit non-zero if any measured request fails.")
    args = parser.parse_args()

    if not args.model and "{model}" in args.endpoint:
        parser.error("--model is required when endpoint contains {model}")
    if args.requests <= 0:
        parser.error("--requests must be > 0")
    if args.duration_seconds is not None and args.duration_seconds <= 0:
        parser.error("--duration-seconds must be > 0")
    if args.concurrency <= 0:
        parser.error("--concurrency must be > 0")
    if args.warmup < 0:
        parser.error("--warmup must be >= 0")
    if args.timeout <= 0:
        parser.error("--timeout must be > 0")
    if args.top_k <= 0:
        parser.error("--top-k must be > 0")
    return args


def load_documents(args: argparse.Namespace) -> list[str]:
    if args.documents:
        return args.documents
    if not args.documents_file:
        return list(DEFAULT_DOCUMENTS)

    raw = args.documents_file.read_text(encoding="utf-8").strip()
    if not raw:
        raise ValueError(f"documents file is empty: {args.documents_file}")

    if args.documents_file.suffix.lower() == ".json":
        data = json.loads(raw)
        if not isinstance(data, list):
            raise ValueError("--documents-file JSON must be an array")
        return [document_to_text(item) for item in data]

    if args.documents_file.suffix.lower() == ".jsonl":
        docs = []
        for line in raw.splitlines():
            line = line.strip()
            if not line:
                continue
            docs.append(document_to_text(json.loads(line)))
        return docs

    blocks = [block.strip() for block in raw.split("\n\n") if block.strip()]
    if len(blocks) == 1:
        blocks = [line.strip() for line in raw.splitlines() if line.strip()]
    return blocks


def document_to_text(item: Any) -> str:
    if isinstance(item, str):
        return item
    if isinstance(item, dict):
        for key in ("text", "content", "document", "body"):
            value = item.get(key)
            if isinstance(value, str):
                return value
        return json.dumps(item, ensure_ascii=False, separators=(",", ":"))
    return str(item)


def load_payload_templates(path: Path | None) -> list[Any] | None:
    if path is None:
        return None
    data = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(data, list):
        if not data:
            raise ValueError("--payload-file list must not be empty")
        return data
    if isinstance(data, dict):
        return [data]
    raise ValueError("--payload-file must contain a JSON object or an array of objects")


def build_default_payload(query: str, documents: list[str], top_k: int) -> dict[str, Any]:
    rendered_documents = "\n".join(f"{idx + 1}. {doc}" for idx, doc in enumerate(documents))
    text = (
        "Rerank the candidate documents for the query. "
        "Return only a compact JSON array of objects with document_index and score.\n\n"
        f"Query: {query}\n\n"
        f"Documents:\n{rendered_documents}\n\n"
        f"TopK: {top_k}"
    )
    return {
        "text": text,
        "parameters": {
            "task": "rerank",
            "query": query,
            "documents": documents,
            "top_k": top_k,
        },
    }


def render_template(value: Any, replacements: dict[str, str]) -> Any:
    if isinstance(value, str):
        for key, replacement in replacements.items():
            value = value.replace("{" + key + "}", replacement)
        return value
    if isinstance(value, list):
        return [render_template(item, replacements) for item in value]
    if isinstance(value, dict):
        return {key: render_template(item, replacements) for key, item in value.items()}
    return value


def build_payload(
    templates: list[Any] | None,
    request_id: int,
    model: str | None,
    query: str,
    documents: list[str],
    top_k: int,
) -> dict[str, Any]:
    if templates is None:
        return build_default_payload(query, documents, top_k)

    template = templates[request_id % len(templates)]
    replacements = {
        "query": query,
        "documents": "\n".join(documents),
        "documents_json": json.dumps(documents, ensure_ascii=False),
        "top_k": str(top_k),
        "model": model or "",
        "request_id": str(request_id),
    }
    payload = render_template(template, replacements)
    if not isinstance(payload, dict):
        raise ValueError("rendered payload template must be a JSON object")
    return payload


def parse_headers(items: list[str]) -> dict[str, str]:
    headers = {"Content-Type": "application/json"}
    for item in items:
        name, sep, value = item.partition(":")
        if not sep or not name.strip():
            raise ValueError(f"invalid header: {item!r}")
        headers[name.strip()] = value.strip()
    return headers


def resolve_url(base_url: str, endpoint: str, model: str | None) -> str:
    model_value = model or ""
    endpoint = endpoint.replace("{model}", model_value)
    if endpoint.startswith("http://") or endpoint.startswith("https://"):
        return endpoint
    return base_url.rstrip("/") + "/" + endpoint.lstrip("/")


def post_json(url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float) -> RequestResult:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            response_body = resp.read()
            latency_ms = (time.perf_counter() - started) * 1000.0
            status = int(resp.status)
            return RequestResult(
                ok=200 <= status < 300,
                status=status,
                latency_ms=latency_ms,
                response_bytes=len(response_body),
            )
    except urllib.error.HTTPError as exc:
        response_body = exc.read()
        latency_ms = (time.perf_counter() - started) * 1000.0
        message = response_body.decode("utf-8", errors="replace")[:300]
        return RequestResult(False, int(exc.code), latency_ms, len(response_body), message)
    except Exception as exc:  # noqa: BLE001 - this is a CLI load-test boundary.
        latency_ms = (time.perf_counter() - started) * 1000.0
        return RequestResult(False, 0, latency_ms, 0, f"{type(exc).__name__}: {exc}")


def run_fixed_count(
    count: int,
    concurrency: int,
    send_one,
) -> list[RequestResult]:
    work: queue.Queue[int] = queue.Queue()
    for request_id in range(count):
        work.put(request_id)

    results: list[RequestResult] = []
    results_lock = threading.Lock()

    def worker() -> None:
        while True:
            try:
                request_id = work.get_nowait()
            except queue.Empty:
                return
            try:
                result = send_one(request_id)
                with results_lock:
                    results.append(result)
            finally:
                work.task_done()

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(concurrency)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return results


def run_duration(
    duration_seconds: float,
    concurrency: int,
    send_one,
) -> list[RequestResult]:
    deadline = time.monotonic() + duration_seconds
    next_id = 0
    id_lock = threading.Lock()
    results: list[RequestResult] = []
    results_lock = threading.Lock()

    def worker() -> None:
        nonlocal next_id
        while time.monotonic() < deadline:
            with id_lock:
                request_id = next_id
                next_id += 1
            result = send_one(request_id)
            with results_lock:
                results.append(result)

    threads = [threading.Thread(target=worker, daemon=True) for _ in range(concurrency)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return results


def percentile(sorted_values: list[float], pct: float) -> float:
    if not sorted_values:
        return 0.0
    idx = int(math.ceil((pct / 100.0) * len(sorted_values))) - 1
    return sorted_values[max(0, min(idx, len(sorted_values) - 1))]


def summarize(results: list[RequestResult], elapsed_seconds: float) -> dict[str, Any]:
    ok_results = [result for result in results if result.ok]
    failed_results = [result for result in results if not result.ok]
    all_latencies = [result.latency_ms for result in results]
    ok_latencies = sorted(result.latency_ms for result in ok_results)
    status_counts: dict[str, int] = {}
    for result in results:
        status_counts[str(result.status)] = status_counts.get(str(result.status), 0) + 1

    error_samples = []
    for result in failed_results:
        sample = {"status": result.status, "latency_ms": round(result.latency_ms, 2)}
        if result.error:
            sample["error"] = result.error
        error_samples.append(sample)
        if len(error_samples) >= 5:
            break

    return {
        "total_requests": len(results),
        "successes": len(ok_results),
        "failures": len(failed_results),
        "status_counts": status_counts,
        "elapsed_seconds": elapsed_seconds,
        "requests_per_second": len(results) / elapsed_seconds if elapsed_seconds > 0 else 0.0,
        "successful_requests_per_second": len(ok_results) / elapsed_seconds if elapsed_seconds > 0 else 0.0,
        "latency_ms": {
            "min": min(all_latencies) if all_latencies else 0.0,
            "avg": statistics.fmean(all_latencies) if all_latencies else 0.0,
            "max": max(all_latencies) if all_latencies else 0.0,
            "success_p50": percentile(ok_latencies, 50),
            "success_p90": percentile(ok_latencies, 90),
            "success_p95": percentile(ok_latencies, 95),
            "success_p99": percentile(ok_latencies, 99),
        },
        "response_bytes": {
            "total": sum(result.response_bytes for result in results),
            "avg": statistics.fmean(result.response_bytes for result in results) if results else 0.0,
        },
        "error_samples": error_samples,
        "successful_latencies_ms": ok_latencies,
    }


def print_summary(summary: dict[str, Any]) -> None:
    latency = summary["latency_ms"]
    print("")
    print("Reranker stress test summary")
    print(f"  total/success/fail: {summary['total_requests']}/{summary['successes']}/{summary['failures']}")
    print(f"  elapsed:            {summary['elapsed_seconds']:.2f}s")
    print(f"  rps:                {summary['requests_per_second']:.2f} total, {summary['successful_requests_per_second']:.2f} successful")
    print(f"  status counts:      {summary['status_counts']}")
    print(
        "  latency ms:         "
        f"min={latency['min']:.2f} avg={latency['avg']:.2f} "
        f"p50={latency['success_p50']:.2f} p90={latency['success_p90']:.2f} "
        f"p95={latency['success_p95']:.2f} p99={latency['success_p99']:.2f} "
        f"max={latency['max']:.2f}"
    )
    if summary["error_samples"]:
        print("  error samples:")
        for sample in summary["error_samples"]:
            print(f"    - status={sample['status']} latency_ms={sample['latency_ms']}: {sample.get('error', '')}")


def main() -> int:
    args = parse_args()
    try:
        documents = load_documents(args)
        templates = load_payload_templates(args.payload_file)
        headers = parse_headers(args.header)
    except Exception as exc:  # noqa: BLE001 - CLI input validation.
        print(f"error: {exc}", file=sys.stderr)
        return 2

    url = resolve_url(args.base_url, args.endpoint, args.model)

    def send_one(request_id: int) -> RequestResult:
        # Shuffle a copy so caches and positional bias do not make every request identical.
        request_documents = list(documents)
        random.Random(request_id).shuffle(request_documents)
        payload = build_payload(templates, request_id, args.model, args.query, request_documents, args.top_k)
        return post_json(url, headers, payload, args.timeout)

    print(f"Target:      POST {url}")
    print(f"Model:       {args.model or '(custom endpoint)'}")
    print(f"Concurrency: {args.concurrency}")
    print(f"Warmup:      {args.warmup}")

    if args.warmup:
        warmup_results = run_fixed_count(args.warmup, args.concurrency, send_one)
        warmup_failures = sum(1 for result in warmup_results if not result.ok)
        print(f"Warmup done: {len(warmup_results)} requests, {warmup_failures} failures")

    started = time.perf_counter()
    if args.duration_seconds is not None:
        print(f"Measured:    {args.duration_seconds:.2f}s duration")
        results = run_duration(args.duration_seconds, args.concurrency, send_one)
    else:
        print(f"Measured:    {args.requests} requests")
        results = run_fixed_count(args.requests, args.concurrency, send_one)
    elapsed = time.perf_counter() - started

    summary = summarize(results, elapsed)
    print_summary(summary)

    if args.output_json:
        output = dict(summary)
        args.output_json.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"  output json:        {args.output_json}")

    if args.fail_on_errors and summary["failures"] > 0:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
