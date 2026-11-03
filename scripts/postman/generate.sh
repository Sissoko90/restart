#!/usr/bin/env bash
# ==========================================================
# Régénère les collections Postman à partir du code source.
# ==========================================================
# Les collections sont DÉRIVÉES des contrôleurs : après un ajout ou un
# renommage d'endpoint, les régénérer plutôt que de les éditer à la main,
# sinon elles divergent silencieusement du code.
#
#   ./scripts/postman/generate.sh
# ==========================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "📖 Extraction des endpoints depuis les contrôleurs..."
python3 scripts/postman/extract_endpoints.py > "$TMP/endpoints.json"

UNRESOLVED=$(python3 -c "
import json
d = json.load(open('$TMP/endpoints.json'))
n = sum(1 for s in d.values() for c in s['controllers'] for e in c['endpoints'] if '{?' in e['path'])
print(n)
")
if [ "$UNRESOLVED" != "0" ]; then
    echo "⚠️  $UNRESOLVED chemin(s) non résolu(s) — constante introuvable, à vérifier."
fi

# Annotation dont les parenthèses existent mais dont le contenu n'a pas pu être
# lu. Ce cas est BLOQUANT, et non un simple avertissement : le chemin retombe
# alors sur la base du contrôleur, donc la collection pointe un endpoint FAUX
# sans que rien ne la contredise. C'est arrivé avec une annotation répartie sur
# deux lignes, qui produisait « POST /auth » au lieu du vrai chemin.
ILLISIBLES=$(python3 -c "
import json
d = json.load(open('$TMP/endpoints.json'))
mauvais = [(c['controller'], e['method'], e['handler'])
           for s in d.values() for c in s['controllers'] for e in c['endpoints']
           if e.get('annotationIllisible')]
for nom, methode, handler in mauvais:
    print(f'{nom}: {methode} {handler}')
print('TOTAL', len(mauvais))
")
if [ "$(echo "$ILLISIBLES" | tail -1)" != "TOTAL 0" ]; then
    echo "❌ Annotation(s) de mapping illisible(s) — le chemin serait FAUX :"
    echo "$ILLISIBLES" | sed '$d' | sed 's/^/     /'
    echo "   Les collections ne sont PAS régénérées."
    exit 1
fi

echo "📮 Génération des collections..."
python3 scripts/postman/gen_postman.py "$TMP/endpoints.json"

echo "✅ Collections à jour dans postman/"
