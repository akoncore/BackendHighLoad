"""Построение диаграмм из настоящего summary.csv; требуется matplotlib."""
import csv
import sys
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

source = Path(sys.argv[1] if len(sys.argv) > 1 else "benchmark/results/postgresql-10000-10/summary.csv")
output = Path(sys.argv[2] if len(sys.argv) > 2 else "docs/benchmark-chart.png")
with source.open(encoding="utf-8") as stream:
    rows = list(csv.DictReader(stream))

fig, axes = plt.subplots(1, 3, figsize=(13, 4.3))
colors = {"REST": "#2563eb", "gRPC": "#059669"}
metrics = [("success_rps", "Успешных запросов / с"), ("p99_ms", "p99, мс"), ("mean_payload_bytes", "Тело ответа, байт")]
for axis, (metric, label) in zip(axes, metrics):
    for protocol, color in colors.items():
        points = sorted((int(row["round"]), float(row[metric])) for row in rows if row["protocol"] == protocol)
        axis.plot([p[0] for p in points], [p[1] for p in points], marker="o", label=protocol, color=color, linewidth=2)
    axis.set_title(label)
    axis.set_xlabel("Повтор")
    axis.set_xticks(sorted({int(row["round"]) for row in rows}))
    axis.set_ylim(bottom=0)
    axis.grid(alpha=.2)
    axis.spines[["top", "right"]].set_visible(False)
axes[0].legend(frameon=False)
fig.suptitle("REST и gRPC · 10 000 запросов · 10 клиентов · PostgreSQL", fontsize=14, fontweight="bold")
fig.text(.5, .015, "Локальное измерение. Ускорение между повторами показывает влияние прогрева.", ha="center", fontsize=10)
fig.tight_layout(rect=(0, .045, 1, .93))
output.parent.mkdir(parents=True, exist_ok=True)
fig.savefig(output, dpi=160)
print(output)
