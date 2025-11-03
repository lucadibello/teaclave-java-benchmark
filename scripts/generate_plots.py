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
    """
    Strong scaling:
      Throughput = totalSize / time
      Speedup S_strong(p) = throughput(p) / throughput(1)
      Efficiency E_strong(p) = S_strong(p) / p
    """
    entries = sorted(list(entries), key=lambda x: x["executedThreads"])
    threads = np.array([item["executedThreads"] for item in entries], dtype=int)
    total_work = np.array([item["totalSize"] for item in entries], dtype=float)
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


def _compute_weak_metrics(entries: Iterable[dict]) -> dict[str, np.ndarray]:
    """
    Weak scaling (FIXED):
      - In your JSON, 'dataSize' is the TOTAL work per run (it scales with p).
        So don't multiply by threads again, or you'll get ~p^2 growth.
      - Throughput = total_work / time = dataSize / time
      - Efficiency E_weak(p) = T1(N) / Tp(pN)   (uses durations only)
      - Scaled speedup S_scaled(p) ≈ p * E_weak(p)
    """
    entries = sorted(list(entries), key=lambda x: x["executedThreads"])
    threads = np.array([item["executedThreads"] for item in entries], dtype=int)

    total_work = np.array([item["dataSize"] for item in entries], dtype=float)  # already total per run
    duration_sec = np.array([item["avgTimeMillis"] for item in entries], dtype=float) / 1000.0

    # Compute throughput
    throughput = total_work / duration_sec

    # Weak efficiency & scaled speedup based on time
    T1N = duration_sec[0]                            # baseline time at p=1, size N
    weak_efficiency = T1N / duration_sec             # E_weak(p) = T1(N) / Tp(pN)
    scaled_speedup = threads * weak_efficiency       # S_scaled(p) ≈ p * E_weak(p)

    return {
        "threads": threads,
        "throughput": throughput,
        "speedup": scaled_speedup,       # expose scaled speedup under 'speedup' for plotting
        "efficiency": weak_efficiency,
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
    desired_ticks = np.array([1, 2, 4, 8, 16, 32])
    filtered_ticks = desired_ticks[desired_ticks <= threads.max()]
    if filtered_ticks.size:
        ax.set_xticks(filtered_ticks.tolist())
    ax.grid(True, linestyle="--", alpha=0.5)
    fig.tight_layout()
    fig.savefig(outfile, dpi=200)
    plt.close(fig)


def _plot_speedup_efficiency(metrics: dict[str, np.ndarray], title_prefix: str, outfile: Path) -> None:
    fig, axes = plt.subplots(nrows=1, ncols=2, figsize=(10, 4))

    is_weak = "Weak" in title_prefix  # naming heuristic

    axes[0].plot(metrics["threads"], metrics["speedup"], marker="o")
    axes[0].set_xlabel("Clients (threads)")
    axes[0].set_ylabel("Scaled speedup" if is_weak else "Throughput speedup")
    axes[0].set_title(f"{title_prefix} {'Scaled Speedup' if is_weak else 'Speedup'}")
    desired_ticks = np.array([1, 2, 4, 8, 16, 32])
    filtered_ticks = desired_ticks[desired_ticks <= metrics["threads"].max()]
    if filtered_ticks.size:
        axes[0].set_xticks(filtered_ticks.tolist())
    axes[0].grid(True, linestyle="--", alpha=0.5)

    axes[1].plot(metrics["threads"], metrics["efficiency"], marker="o")
    axes[1].set_xlabel("Clients (threads)")
    axes[1].set_ylabel("Parallel efficiency" + (" (weak)" if is_weak else ""))
    axes[1].set_title(f"{title_prefix} Efficiency")
    if filtered_ticks.size:
        axes[1].set_xticks(filtered_ticks.tolist())
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