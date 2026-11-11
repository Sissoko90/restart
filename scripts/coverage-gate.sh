#!/usr/bin/env bash
# ==========================================================
# Garde-fou de couverture : interdire la RÉGRESSION
# ==========================================================
# Pourquoi pas un seuil absolu. La couverture était à ~19 % quand ce
# script a été écrit (elle est à ~52 %, voir docs/TESTING.md). Exiger
# 80 % aurait fait échouer chaque PR sans rien améliorer : le seuil
# aurait été désactivé au bout de deux jours, et le projet se serait
# retrouvé sans garde-fou du tout.
#
# Ce qu'on refuse ici, c'est que la couverture DIMINUE. Le plancher
# monte à mesure que les tests s'ajoutent — c'est un cliquet.
#
# ENCORE FAUT-IL LE RELEVER. Il est resté à 21,41 % pendant que la
# couverture réelle montait à 52,36 % : trente points d'écart, donc un
# cliquet qui aurait laissé la couverture tomber de moitié sans rien
# dire. Un cliquet qu'on ne remonte pas n'est pas un cliquet.
# `--update` fait partie de la passe de test, pas d'une corvée annexe.
#
#   ./scripts/coverage-gate.sh              # vérifie
#   ./scripts/coverage-gate.sh --update     # relève le plancher
# ==========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BASELINE_FILE=".coverage-baseline"

# Marge en points de pourcentage. Sans elle, un arrondi ou un test non
# déterministe ferait échouer une PR qui n'a rien dégradé.
TOLERANCE="0.5"

read -r CURRENT_INSTRUCTIONS CURRENT_BRANCHES <<<"$(python3 - <<'PY'
import csv, glob

covered = missed = bcovered = bmissed = 0
for path in glob.glob("*/target/site/jacoco/jacoco.csv"):
    with open(path, encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            covered += int(row["INSTRUCTION_COVERED"])
            missed += int(row["INSTRUCTION_MISSED"])
            bcovered += int(row["BRANCH_COVERED"])
            bmissed += int(row["BRANCH_MISSED"])

total = covered + missed
btotal = bcovered + bmissed
print(f"{100 * covered / total if total else 0:.2f}",
      f"{100 * bcovered / btotal if btotal else 0:.2f}")
PY
)"

if [ "$CURRENT_INSTRUCTIONS" = "0.00" ]; then
    echo "❌ Aucune donnée de couverture trouvée."
    echo "   Les rapports JaCoCo sont-ils générés ? Exécuter 'mvn verify' d'abord."
    echo "   Piège connu : si @{argLine} disparaît du surefire parent, la couverture"
    echo "   tombe à zéro SANS erreur ni avertissement (voir docs/TESTING.md §8)."
    exit 1
fi

write_baseline() {
    cat > "$BASELINE_FILE" <<EOF
# Plancher de couverture — relevé par ./scripts/coverage-gate.sh --update
# Ne jamais l'abaisser à la main : ce serait masquer une régression.
INSTRUCTIONS_FLOOR=$CURRENT_INSTRUCTIONS
BRANCHES_FLOOR=$CURRENT_BRANCHES
EOF
}

if [ "${1:-}" = "--update" ]; then
    write_baseline
    echo "✅ Plancher relevé : ${CURRENT_INSTRUCTIONS}% instructions, ${CURRENT_BRANCHES}% branches"
    exit 0
fi

if [ ! -f "$BASELINE_FILE" ]; then
    echo "⚠️  Aucun plancher enregistré — création à partir de la mesure actuelle."
    write_baseline
    exit 0
fi

# shellcheck disable=SC1090
source "$BASELINE_FILE"

FAILED=0
# Trois issues, et non deux : au-dessus du plancher, sous le plancher mais dans
# la tolérance, et sous la tolérance.
#
# Le cas du milieu était auparavant affiché comme un succès ordinaire. Une coche
# verte à côté d'un chiffre inférieur au plancher est précisément ce qui apprend
# à ne plus lire le portillon : il faut dire que la mesure a baissé, et qu'elle
# passe uniquement parce que la tolérance l'absorbe.
check() {
    local label="$1" current="$2" floor="$3"
    local verdict
    verdict=$(python3 -c "
courant, plancher, tolerance = $current, $floor, $TOLERANCE
print('ok' if courant >= plancher else ('toleree' if courant >= plancher - tolerance else 'ko'))")

    case "$verdict" in
        ok)
            echo "✅ $label : ${current}% (plancher ${floor}%)"
            ;;
        toleree)
            echo "⚠️  $label : ${current}% — SOUS le plancher de ${floor}%, admis dans la"
            echo "    tolérance de ${TOLERANCE} point. Du code a été ajouté sans tests."
            ;;
        ko)
            echo "❌ $label : ${current}% — en baisse par rapport au plancher de ${floor}%"
            FAILED=1
            ;;
    esac
}

echo "Couverture — vérification de non-régression"
echo "-------------------------------------------"
check "Instructions" "$CURRENT_INSTRUCTIONS" "$INSTRUCTIONS_FLOOR"
check "Branches    " "$CURRENT_BRANCHES" "$BRANCHES_FLOOR"

if [ "$FAILED" -ne 0 ]; then
    echo
    echo "Cette modification retire de la couverture sans en rendre."
    echo "Soit les tests correspondants manquent, soit le plancher doit être"
    echo "relevé délibérément : ./scripts/coverage-gate.sh --update"
fi

exit $FAILED
