#!/usr/bin/env bash
# ==========================================================
# Rapport de couverture agrégé, tous modules confondus.
# ==========================================================
#   ./scripts/coverage-report.sh          # utilise les rapports existants
#   ./scripts/coverage-report.sh --run    # relance les tests d'abord
#
# Les rapports HTML détaillés (ligne par ligne) sont dans :
#   <module>/target/site/jacoco/index.html
# ==========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ "${1:-}" = "--run" ]; then
    echo "🧪 Exécution des tests..."
    mvn -o clean test -q
fi

python3 - <<'PY'
import csv
import glob

rows = []
for path in sorted(glob.glob("*/target/site/jacoco/jacoco.csv")):
    service = path.split("/")[0]
    covered = missed = branch_covered = branch_missed = 0
    worst = []
    with open(path, encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            ic, im = int(row["INSTRUCTION_COVERED"]), int(row["INSTRUCTION_MISSED"])
            covered += ic
            missed += im
            branch_covered += int(row["BRANCH_COVERED"])
            branch_missed += int(row["BRANCH_MISSED"])
            if im > 200:  # classes volumineuses et peu couvertes
                total = ic + im
                worst.append((row["CLASS"], ic, total, 100 * ic / total if total else 0))

    total = covered + missed
    btotal = branch_covered + branch_missed
    rows.append({
        "service": service,
        "covered": covered, "total": total,
        "pct": 100 * covered / total if total else 0,
        "bcovered": branch_covered, "btotal": btotal,
        "bpct": 100 * branch_covered / btotal if btotal else 0,
        "worst": sorted(worst, key=lambda w: w[3])[:3],
    })

print()
print(f"{'SERVICE':22} {'INSTRUCTIONS':>18} {'%':>8}   {'BRANCHES':>13} {'%':>8}")
print("-" * 78)

tc = tt = tbc = tbt = 0
for r in sorted(rows, key=lambda r: r["pct"]):
    flag = " ⚠️" if r["pct"] < 20 else ""
    print(f"{r['service']:22} {r['covered']:8}/{r['total']:<8} {r['pct']:7.1f}%   "
          f"{r['bcovered']:5}/{r['btotal']:<6} {r['bpct']:7.1f}%{flag}")
    tc += r["covered"]; tt += r["total"]
    tbc += r["bcovered"]; tbt += r["btotal"]

print("-" * 78)
print(f"{'TOTAL':22} {tc:8}/{tt:<8} {100 * tc / tt if tt else 0:7.1f}%   "
      f"{tbc:5}/{tbt:<6} {100 * tbc / tbt if tbt else 0:7.1f}%")

print("\n\nClasses les moins couvertes (plus de 200 instructions manquées) :")
print("-" * 78)
for r in sorted(rows, key=lambda r: r["pct"]):
    if not r["worst"]:
        continue
    print(f"\n  {r['service']}")
    for cls, cov, tot, pct in r["worst"]:
        print(f"    {pct:5.1f}%  {cls.split('/')[-1]:45} ({cov}/{tot})")
print()
PY
