"""Turns artifacts/scale/results.jsonl (benchmark_scale.py) into the three scaling charts for the report.

  6_scale_volume.png    feature-stage time vs input size (CSV, 4 cores) with the ideal linear line
  7_csv_vs_parquet.png  total time and input size, CSV vs Parquet, per number of copies
  8_scale_cores.png     total time vs cores at 4 copies, with the speedup over 1 core

Only status "ok" lines are used; when a (format, copies, cores) run repeats, the last line wins.
Also prints a Markdown table of all runs for the report notes.
"""

import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "artifacts" / "scale" / "results.jsonl"
CHARTS = ROOT / "artifacts" / "charts"
CSV_COLOR, PARQUET_COLOR, IDEAL_COLOR = "#4C78A8", "#F58518", "#9D9D9D"
GB = 1e9

plt.rcParams.update({"figure.figsize": (9, 4), "axes.grid": True, "grid.alpha": 0.3,
                     "axes.spines.top": False, "axes.spines.right": False})


def load_runs(path: Path) -> dict[tuple[str, int, int], dict]:
    runs = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            run = json.loads(line)
            if run.get("status") == "ok":
                runs[(run["format"], run["copies"], run["cores"])] = run
    return runs


def plot_volume(runs: dict, out: Path) -> None:
    points = sorted((run for key, run in runs.items() if key[0] == "csv" and key[2] == 4), key=lambda r: r["copies"])
    sizes = [run["inputBytes"] / GB for run in points]
    seconds = [run["featureSeconds"] for run in points]
    fig, ax = plt.subplots()
    ax.plot(sizes, seconds, marker="o", color=CSV_COLOR, label="Thời gian thực tế")
    ax.plot(sizes, [seconds[0] * run["copies"] / points[0]["copies"] for run in points], linestyle="--",
            color=IDEAL_COLOR, label="Tuyến tính lý tưởng (từ mốc 1 bản)")
    for size, second, run in zip(sizes, seconds, points):
        ax.annotate(f"{run['rows'] / 1e6:.1f} triệu dòng", (size, second), textcoords="offset points",
                    xytext=(6, -14), fontsize=8)
    ax.set_xlabel("Dung lượng đầu vào trên HDFS (GB, CSV)")
    ax.set_ylabel("Thời gian tính đặc trưng (giây)")
    ax.set_title("Thời gian tính đặc trưng hành vi theo khối lượng dữ liệu (4 core)")
    ax.legend()
    fig.savefig(out, dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_formats(runs: dict, out: Path) -> None:
    copies = sorted({key[1] for key in runs if key[2] == 4 and ("csv", key[1], 4) in runs and ("parquet", key[1], 4) in runs})
    width = 0.38
    xs = range(len(copies))
    fig, (time_ax, size_ax) = plt.subplots(1, 2, figsize=(12, 4))
    for offset, fmt, color in ((-width / 2, "csv", CSV_COLOR), (width / 2, "parquet", PARQUET_COLOR)):
        time_ax.bar([x + offset for x in xs], [runs[(fmt, c, 4)]["totalSeconds"] for c in copies], width,
                    color=color, label=fmt.upper())
        size_ax.bar([x + offset for x in xs], [runs[(fmt, c, 4)]["inputBytes"] / GB for c in copies], width,
                    color=color, label=fmt.upper())
    for ax, title, ylabel in ((time_ax, "Tổng thời gian chạy", "Giây"), (size_ax, "Dung lượng trên HDFS", "GB")):
        ax.set_xticks(list(xs), [f"{c} bản" for c in copies])
        ax.set_title(title)
        ax.set_ylabel(ylabel)
        ax.legend()
    fig.suptitle("CSV so với Parquet (4 core)")
    fig.savefig(out, dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_cores(runs: dict, out: Path) -> None:
    points = sorted((run for key, run in runs.items() if key[0] == "csv" and key[1] == 4), key=lambda r: r["cores"])
    base = points[0]["totalSeconds"]
    fig, ax = plt.subplots()
    bars = ax.bar([str(run["cores"]) for run in points], [run["totalSeconds"] for run in points], color=CSV_COLOR)
    for bar, run in zip(bars, points):
        ax.annotate(f"x{base / run['totalSeconds']:.2f}", (bar.get_x() + bar.get_width() / 2, bar.get_height()),
                    textcoords="offset points", xytext=(0, 4), ha="center")
    ax.set_xlabel("Số core của executor")
    ax.set_ylabel("Tổng thời gian chạy (giây)")
    ax.set_title(f"Tăng tốc theo số core (CSV, 4 bản, {points[0]['rows'] / 1e6:.1f} triệu dòng)")
    fig.savefig(out, dpi=150, bbox_inches="tight")
    plt.close(fig)


def markdown_table(runs: dict) -> str:
    lines = ["| Định dạng | Số bản | Core | Số dòng | Dung lượng (GB) | Đọc (s) | Đặc trưng (s) | Tổng (s) | Dòng/giây |",
             "|---|---|---|---|---|---|---|---|---|"]
    for (fmt, copies, cores), run in sorted(runs.items(), key=lambda item: (item[0][0], item[0][2] != 4, item[0][1], item[0][2])):
        lines.append(f"| {fmt.upper()} | {copies} | {cores} | {run['rows']:,} | {run['inputBytes'] / GB:.2f} | "
                     f"{run['readSeconds']} | {run['featureSeconds']} | {run['totalSeconds']} | "
                     f"{run['rows'] / run['totalSeconds']:,.0f} |")
    return "\n".join(lines)


def main() -> int:
    runs = load_runs(RESULTS)
    if not runs:
        print(f"no ok runs in {RESULTS}", file=sys.stderr)
        return 1
    CHARTS.mkdir(parents=True, exist_ok=True)
    plot_volume(runs, CHARTS / "6_scale_volume.png")
    plot_formats(runs, CHARTS / "7_csv_vs_parquet.png")
    plot_cores(runs, CHARTS / "8_scale_cores.png")
    print(markdown_table(runs))
    return 0


if __name__ == "__main__":
    sys.exit(main())
