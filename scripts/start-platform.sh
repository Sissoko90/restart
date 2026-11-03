#!/usr/bin/env bash
# ==========================================================
# Démarrage de la plateforme, dans l'ordre
# ==========================================================
#   ./scripts/start-platform.sh --check     n'affiche que l'état
#   ./scripts/start-platform.sh             vérifie puis démarre
#
# L'ordre n'est pas arbitraire. Deux dépendances le contraignent :
#
#   1. config-service (8888) sert la configuration de tous les autres.
#      Sans lui, chaque service démarre sur ses valeurs par défaut —
#      sans erreur visible, ce qui est pire qu'un échec franc.
#
#   2. interface-service (8088) est l'AUTORITÉ JWT : il expose les clés
#      publiques sur /dev/.well-known/jwks.json. Les autres services
#      démarrent sans lui (le chargement des clés est non bloquant et
#      réessayé), mais tant qu'il est absent ils REFUSENT tous les
#      jetons. Un 401 systématique sur une plateforme en apparence
#      démarrée vient presque toujours de là.
#
# Les services suivants sont indépendants les uns des autres.
# ==========================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CHECK_ONLY=false
[ "${1:-}" = "--check" ] && CHECK_ONLY=true

# ----------------------------------------------------------
# Infrastructure attendue avant tout service
# ----------------------------------------------------------
# Format : port|nom|services qui en dépendent
INFRA=(
    "8500|Consul|découverte de services (tous)"
    "6379|Redis|débit et révocation (gateway, interface)"
    "9092|Kafka|événements et effacement RGPD (tous)"
    "5432|PostgreSQL|auth, interface, user, setting"
    "27017|MongoDB|media"
    "9042|Cassandra|quotas"
    "9000|MinIO|media (stockage objet)"
)

port_ouvert() {
    nc -z localhost "$1" >/dev/null 2>&1
}

echo "Infrastructure"
echo "──────────────────────────────────────────────────────────────"
MANQUANTS=0
for entree in "${INFRA[@]}"; do
    IFS='|' read -r port nom usage <<<"$entree"
    if port_ouvert "$port"; then
        printf "  ✅ %-12s %-6s %s\n" "$nom" "$port" "$usage"
    else
        printf "  ❌ %-12s %-6s %s\n" "$nom" "$port" "$usage"
        MANQUANTS=$((MANQUANTS + 1))
    fi
done

if [ "$MANQUANTS" -gt 0 ]; then
    echo
    echo "  $MANQUANTS brique(s) d'infrastructure absente(s). Démarrer :"
    echo "      docker compose -f docker-compose-minimal.yml up -d"
    echo
    echo "  Toute l'infrastructure vient de Docker, PostgreSQL et MongoDB compris."
    echo "  Si le conteneur postgres refuse de démarrer, un PostgreSQL local"
    echo "  occupe déjà le port 5432 — l'arrêter, ou changer DB_PORT dans .env."
fi

# ----------------------------------------------------------
# Services, dans l'ordre de démarrage
# ----------------------------------------------------------
# Format : port|module|rôle
SERVICES=(
    "8888|config-service|configuration centralisée — DOIT être premier"
    "8088|interface-service|AUTORITÉ JWT — sans elle, tout jeton est refusé"
    "8081|gateway-service|point d'entrée unique"
    "8082|auth-service|connexion, inscription, OTP, OAuth2"
    "8083|user-service|profils"
    "8087|media-service|pièces justificatives"
    "8089|quotas-service|plafonds d'usage"
    "8090|setting-service|paramètres"
)

echo
echo "Services"
echo "──────────────────────────────────────────────────────────────"
for entree in "${SERVICES[@]}"; do
    IFS='|' read -r port module role <<<"$entree"
    if port_ouvert "$port"; then
        printf "  ✅ %-18s %-6s %s\n" "$module" "$port" "$role"
    else
        printf "  ⬜ %-18s %-6s %s\n" "$module" "$port" "$role"
    fi
done

# ----------------------------------------------------------
# L'autorité JWT répond-elle vraiment ?
# ----------------------------------------------------------
echo
echo "Autorité JWT"
echo "──────────────────────────────────────────────────────────────"
JWKS_PATH="/dev/.well-known/jwks.json"
if port_ouvert 8088; then
    CLES=$(curl -s --max-time 3 "http://localhost:8088${JWKS_PATH}" 2>/dev/null \
        | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("keys",[])))' 2>/dev/null)
    if [ -n "${CLES:-}" ] && [ "${CLES:-0}" -gt 0 ] 2>/dev/null; then
        echo "  ✅ ${CLES} clé(s) publique(s) publiée(s)"
    else
        echo "  ⚠️  interface-service répond mais ne publie aucune clé."
        echo "      Les autres services refuseront tous les jetons."
    fi
else
    echo "  ❌ interface-service absent."
    echo "      Les autres services démarreront, mais tout jeton sera refusé :"
    echo "      c'est la cause habituelle d'un 401 systématique."
fi

if [ "$CHECK_ONLY" = true ]; then
    exit 0
fi

# ----------------------------------------------------------
# Démarrage
# ----------------------------------------------------------
if [ "$MANQUANTS" -gt 0 ]; then
    echo
    echo "❌ Infrastructure incomplète — démarrage des services interrompu."
    echo "   Les lancer maintenant produirait des pannes difficiles à lire."
    exit 1
fi

echo
echo "Démarrage des services dans l'ordre..."
echo "   Chaque service tourne au premier plan dans son propre terminal."
echo "   Ce script n'en lance aucun à votre place : un service démarré en"
echo "   arrière-plan cache ses journaux, qui sont précisément ce qu'il faut"
echo "   lire pendant une mise en route."
echo
for entree in "${SERVICES[@]}"; do
    IFS='|' read -r port module role <<<"$entree"
    port_ouvert "$port" && continue
    echo "   mvn -pl $module spring-boot:run"
done
echo
echo "Après chaque démarrage, relancer ./scripts/start-platform.sh --check"
