"""
Generate throughput, speedup, and parallel efficiency plots from benchmark JSON output.

Usage
-----
python scripts/generate_plots.py

The script expects the benchmark summary at data/benchmark_results.json and writes PNG figures
to the results/ directory. Run this inside an activated virtual environment with the required
dependencies installed (matplotlib, numpy).
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Iterable, Tuple

import matplotlib.pyplot as plt
import numpy as np

REPO_ROOT = Path(__file__).resolve().parents[1]
DATA_PATH = REPO_ROOT / "data" / "benchmark_results.json"
OUTPUT_DIR = REPO_ROOT / "results"


def _load_data() -> Tuple[list[dict], list[dict]]:
    with DATA_PATH.open() as handle:
        summary = json.load(handle)
    return summary["strongScaling"], summary["weakScaling"]


def _compute_strong_metrics(entries: Iterable[dict]) -> dict[str, np.ndarray]:
    entries = list(entries)
    threads = np.array([item["executedThreads"] for item in entries], dtype=int)
    workload = np.array([item["totalSize"] for item in entries], dtype=float)
    duration_sec = np.array([item["avgTimeMillis"] for item in entries], dtype=float) / 1000.0

    throughput = workload / duration_sec
    baseline = throughput[0]
    speedup = throughput / baseline
    efficiency = speedup / threads

    return {
        "threads": threads,
        "throughput": throughput,
        "speedup": speedup,
        "efficiency": efficiency,
    }


def _compute_weak_metrics(entries: Iterable[dict]) -> dict[str, np.ndarray]:
    entries = list(entries)
    threads = np.array([item["executedThreads"] for item in entries], dtype=int)
    per_thread_work = np.array([item["dataSize"] for item in entries], dtype=float)
    total_work = per_thread_work * threads
    duration_sec = np.array([item["avgTimeMillis"] for item in entries], dtype=float) / 1000.0

    throughput = total_work / duration_sec
    baseline = throughput[0]
    speedup = throughput / baseline
    efficiency = speedup / threads

    return {
        "threads": threads,
        "throughput": throughput,
        "speedup": speedup,
        "efficiency": efficiency,
    }


def _format_throughput(values: np.ndarray) -> np.ndarray:
    """Return throughput in thousand ops per second to keep axes readable."""
    return values / 1_000.0


def _plot_throughput(threads: np.ndarray, throughput: np.ndarray, title: str, outfile: Path) -> None:
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.plot(threads, _format_throughput(throughput), marker="o")
    ax.set_xlabel("Clients (threads)")
    ax.set_ylabel("Throughput [thousand ops/s]")
    ax.set_title(title)
    ax.grid(True, linestyle="--", alpha=0.5)
    fig.tight_layout()
    fig.savefig(outfile, dpi=200)
    plt.close(fig)


def _plot_speedup_efficiency(metrics: dict[str, np.ndarray], title_prefix: str, outfile: Path) -> None:
    fig, axes = plt.subplots(nrows=1, ncols=2, figsize=(10, 4))

    axes[0].plot(metrics["threads"], metrics["speedup"], marker="o")
    axes[0].set_xlabel("Clients (threads)")
    axes[0].set_ylabel("Throughput speedup")
    axes[0].set_title(f"{title_prefix} Speedup")
    axes[0].grid(True, linestyle="--", alpha=0.5)

    axes[1].plot(metrics["threads"], metrics["efficiency"], marker="o")
    axes[1].set_xlabel("Clients (threads)")
    axes[1].set_ylabel("Parallel efficiency")
    axes[1].set_title(f"{title_prefix} Efficiency")
    axes[1].grid(True, linestyle="--", alpha=0.5)

    fig.tight_layout()
    fig.savefig(outfile, dpi=200)
    plt.close(fig)


def main() -> None:
    strong_entries, weak_entries = _load_data()
    strong_metrics = _compute_strong_metrics(strong_entries)
    weak_metrics = _compute_weak_metrics(weak_entries)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    _plot_throughput(
        threads=strong_metrics["threads"],
        throughput=strong_metrics["throughput"],
        title="Strong Scaling Throughput",
        outfile=OUTPUT_DIR / "strong_throughput.png",
    )
    _plot_speedup_efficiency(
        metrics=strong_metrics,
        title_prefix="Strong Scaling",
        outfile=OUTPUT_DIR / "strong_speedup_efficiency.png",
    )

    _plot_throughput(
        threads=weak_metrics["threads"],
        throughput=weak_metrics["throughput"],
        title="Weak Scaling Throughput",
        outfile=OUTPUT_DIR / "weak_throughput.png",
    )
    _plot_speedup_efficiency(
        metrics=weak_metrics,
        title_prefix="Weak Scaling",
        outfile=OUTPUT_DIR / "weak_speedup_efficiency.png",
    )


if __name__ == "__main__":
    main()
