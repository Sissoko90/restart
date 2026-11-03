#!/usr/bin/env bash
# ==========================================================
# Conformité des licences des dépendances
# ==========================================================
# AbdaTech distribue Restart sous licence propriétaire (voir LICENSE).
# Certaines licences libres l'interdisent : les licences à réciprocité
# forte obligent à publier le code source de l'œuvre dérivée — donc de
# la plateforme entière.
#
# Le risque n'est pas théorique. Une dépendance transitive sous AGPL,
# tirée par une autre bibliothèque, suffit : personne ne l'a choisie,
# et l'obligation s'applique quand même.
#
#   mvn license:aggregate-add-third-party   # produit l'inventaire
#   ./scripts/license-check.sh              # le vérifie
# ==========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

INVENTORY="target/generated-sources/license/THIRD-PARTY.txt"

if [ ! -f "$INVENTORY" ]; then
    echo "❌ Inventaire des licences introuvable : $INVENTORY"
    echo "   Exécuter d'abord : mvn license:aggregate-add-third-party"
    exit 1
fi

python3 - "$INVENTORY" <<'PY'
import re
import sys
from collections import defaultdict

# ------------------------------------------------------------------
# Lecture de l'inventaire
# ------------------------------------------------------------------
# Chaque dépendance occupe une ligne de la forme :
#
#   (EPL 2.0) (GPL2 w/ CPE) Jakarta Annotations API (jakarta.annotation:...)
#
# Les groupes de tête énumèrent TOUTES les licences proposées. Quand il y
# en a plusieurs, c'est un choix offert au destinataire : il suffit qu'UNE
# soit acceptable. Une première version de ce script les traitait comme
# cumulatives et refusait des dépendances parfaitement utilisables.

LIGNE = re.compile(r"^\s+(\(.+)$")
GROUPE = re.compile(r"\(((?:[^()]|\([^()]*\))*)\)")


def licences_de(ligne):
    """Extrait les groupes parenthésés de TÊTE, avant le nom du projet."""
    reste = ligne.strip()
    trouvees = []
    while reste.startswith("("):
        m = GROUPE.match(reste)
        if not m:
            break
        trouvees.append(m.group(1).strip())
        reste = reste[m.end():].lstrip()
    return trouvees, reste


# ------------------------------------------------------------------
# Classement
# ------------------------------------------------------------------
PERMISSIVES = [
    "apache", "asf 2.0", "mit", "bsd", "isc", "public domain", "unlicense", "cc0",
    "edl", "eclipse distribution", "w3c", "wtfpl", "zlib", "python software",
    "bouncy castle", "go license", "indiana university",
]

# Réciprocité limitée au fichier ou au module : compatible avec un produit
# propriétaire tant que la bibliothèque n'est pas modifiée et reste liée
# dynamiquement. À connaître, pas à bloquer.
FAIBLES = [
    "lgpl", "lesser general public",
    "mozilla public", "mpl-", "mpl ",
    "eclipse public", "epl",
    "cddl", "common development",
]

# Réciprocité forte : incompatible avec une distribution propriétaire.
FORTES = [
    "affero", "agpl",
    "server side public", "sspl",
    "commons clause",
    "gpl", "general public",   # après exclusion de LGPL et de l'exception Classpath
]

# L'exception Classpath lève précisément l'obligation de réciprocité pour
# le code qui se contente de LIER la bibliothèque. C'est le régime de la
# plupart des artefacts Jakarta EE : les refuser reviendrait à refuser
# l'écosystème Java standard.
EXCEPTIONS = ["classpath exception", "w/ cpe", "with cpe", "cpe)"]


def classer(licence):
    l = licence.lower()

    if any(e in l for e in EXCEPTIONS):
        return "permissive"
    if any(p in l for p in PERMISSIVES):
        return "permissive"
    if any(f in l for f in FAIBLES):
        return "faible"
    # « GPL » ne doit pas être reconnu dans « LGPL » : le cas est déjà
    # capté par FAIBLES, atteint avant.
    if any(f in l for f in FORTES):
        return "forte"
    return "inconnue"


# ------------------------------------------------------------------
# Analyse
# ------------------------------------------------------------------
bloquantes = []
faibles = defaultdict(list)
inconnues = defaultdict(list)
total = 0

for ligne in open(sys.argv[1], encoding="utf-8", errors="replace"):
    if not LIGNE.match(ligne):
        continue
    licences, projet = licences_de(ligne)
    if not licences:
        continue
    total += 1

    classes = {classer(lic) for lic in licences}
    projet_court = projet[:90]

    # Le destinataire choisit : une seule licence acceptable suffit.
    if "permissive" in classes:
        continue
    if "faible" in classes:
        for lic in licences:
            if classer(lic) == "faible":
                faibles[lic].append(projet_court)
        continue
    if "forte" in classes:
        bloquantes.append((" / ".join(licences), projet_court))
        continue
    for lic in licences:
        inconnues[lic].append(projet_court)

# ------------------------------------------------------------------
# Rapport
# ------------------------------------------------------------------
print(f"{total} dépendances tierces inventoriées.\n")

if faibles:
    print("ℹ️  Réciprocité faible — utilisables en liaison dynamique sans modification :")
    for lic, projets in sorted(faibles.items()):
        print(f"    {lic} ({len(projets)})")
    print()

if inconnues:
    print("⚠️  Licences non reconnues — à qualifier à la main :")
    for lic, projets in sorted(inconnues.items()):
        print(f"    {lic}")
        for p in sorted(set(projets))[:3]:
            print(f"        {p}")
    print()

if bloquantes:
    print("❌ INCOMPATIBLES avec une distribution propriétaire :\n")
    for licences, projet in sorted(bloquantes):
        print(f"    [{licences}]")
        print(f"      {projet}")
    print()
    print("Trois issues possibles :")
    print("  1. remplacer la dépendance par un équivalent permissif ;")
    print("  2. acquérir une licence commerciale auprès de son éditeur ;")
    print("  3. si elle est transitive, l'exclure et fournir une alternative.")
    sys.exit(1)

print("✅ Aucune licence incompatible avec une distribution propriétaire.")
PY
