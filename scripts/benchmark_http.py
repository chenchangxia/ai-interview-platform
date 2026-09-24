#!/usr/bin/env python3
"""Small dependency-free HTTP latency benchmark for local reproducible checks."""

from __future__ import annotations

import argparse
import json
import math
import statistics
import time
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed


def request_once(url: str, timeout: float) -> tuple[int, float]:
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            response.read()
            status = response.status
    except Exception:
        status = 0
    return status, (time.perf_counter() - started) * 1000


def percentile(values: list[float], percentile_value: float) -> float:
    if not values:
        return math.nan
    ordered = sorted(values)
    index = max(0, math.ceil(percentile_value * len(ordered)) - 1)
    return ordered[index]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--requests", type=int, default=1000)
    parser.add_argument("--concurrency", type=int, default=50)
    parser.add_argument("--warmup", type=int, default=20)
    parser.add_argument("--timeout", type=float, default=10.0)
    args = parser.parse_args()

    for _ in range(args.warmup):
        request_once(args.url, args.timeout)

    started = time.perf_counter()
    results: list[tuple[int, float]] = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [
            executor.submit(request_once, args.url, args.timeout)
            for _ in range(args.requests)
        ]
        for future in as_completed(futures):
            results.append(future.result())
    elapsed = time.perf_counter() - started

    statuses = Counter(status for status, _ in results)
    successful = [latency for status, latency in results if 200 <= status < 300]
    report = {
        "url": args.url,
        "requests": args.requests,
        "concurrency": args.concurrency,
        "warmup": args.warmup,
        "successful": len(successful),
        "errors": args.requests - len(successful),
        "status_counts": dict(sorted(statuses.items())),
        "wall_time_seconds": round(elapsed, 3),
        "throughput_requests_per_second": round(args.requests / elapsed, 2),
        "latency_ms": {
            "mean": round(statistics.fmean(successful), 3) if successful else None,
            "p50": round(percentile(successful, 0.50), 3),
            "p95": round(percentile(successful, 0.95), 3),
            "p99": round(percentile(successful, 0.99), 3),
            "max": round(max(successful), 3) if successful else None,
        },
        "notes": "Local HTTP benchmark; results depend on hardware, data volume, and client connection behavior.",
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
