#!/usr/bin/env bash
# ==========================================================
# Protection de la branche main
# ==========================================================
# Une règle de protection ne peut PAS être posée par un workflow : le
# GITHUB_TOKEN d'Actions n'a pas les droits d'administration du dépôt,
# et c'est voulu — sinon un workflow modifiable par une PR pourrait
# lever ses propres garde-fous.
#
# Ce script s'exécute donc à la main, une fois, par un administrateur :
#
#   gh auth login                      # avec le droit `repo` (admin)
#   ./scripts/github/protect-main.sh
#
# Il est idempotent : le relancer réaligne la configuration sans dégât.
# ==========================================================
set -euo pipefail

REPO="${1:-Sissoko90/restart}"
BRANCH="${2:-main}"

if ! command -v gh >/dev/null 2>&1; then
    echo "❌ La CLI GitHub (gh) est requise : https://cli.github.com"
    exit 1
fi

echo "Dépôt  : $REPO"
echo "Branche: $BRANCH"
echo

# ------------------------------------------------------------------
# Contrôles exigés avant fusion.
# Les noms doivent correspondre EXACTEMENT au champ `name:` des jobs
# dans .github/workflows/ — une faute de frappe donne une règle qui
# n'attend rien et laisse tout passer, sans jamais le signaler.
# ------------------------------------------------------------------
read -r -d '' PROTECTION <<'JSON' || true
{
  "required_status_checks": {
    "strict": true,
    "contexts": [
      "Compilation et tests",
      "Conventions",
      "Fuite de secrets",
      "CodeQL (SAST)",
      "Vulnérabilités des dépendances",
      "Conformité des licences",
      "Dépendances ajoutées"
    ]
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "required_approving_review_count": 1,
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": true,
    "require_last_push_approval": true
  },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "block_creations": false,
  "required_conversation_resolution": true,
  "lock_branch": false,
  "allow_fork_syncing": false
}
JSON

echo "$PROTECTION" | gh api \
    --method PUT \
    -H "Accept: application/vnd.github+json" \
    "/repos/$REPO/branches/$BRANCH/protection" \
    --input - >/dev/null

echo "✅ Protection appliquée sur $BRANCH :"
echo "   • fusion interdite sans PR approuvée par un CODEOWNER"
echo "   • approbation invalidée si de nouveaux commits sont poussés"
echo "   • les 7 contrôles de CI doivent passer, sur une branche à jour"
echo "   • historique linéaire ; force-push et suppression interdits"
echo "   • règles applicables AUX ADMINISTRATEURS aussi"
echo "   • conversations de revue à résoudre avant fusion"
echo
echo "⚠️  enforce_admins=true signifie que vous ne pourrez plus pousser"
echo "    directement sur $BRANCH. C'est l'intérêt de la mesure."

# ------------------------------------------------------------------
# L'analyse de sécurité de GitHub, activable par API.
# ------------------------------------------------------------------
echo
echo "Activation des analyses GitHub..."
gh api --method PATCH "/repos/$REPO" \
    -f security_and_analysis[secret_scanning][status]=enabled \
    -f security_and_analysis[secret_scanning_push_protection][status]=enabled \
    >/dev/null 2>&1 && echo "   ✅ Analyse de secrets + blocage au push" \
    || echo "   ⚠️  Analyse de secrets indisponible (dépôt privé sans GitHub Advanced Security)"

gh api --method PUT "/repos/$REPO/vulnerability-alerts" >/dev/null 2>&1 \
    && echo "   ✅ Alertes de vulnérabilité (Dependabot)" \
    || echo "   ⚠️  Alertes de vulnérabilité non activées"

gh api --method PUT "/repos/$REPO/automated-security-fixes" >/dev/null 2>&1 \
    && echo "   ✅ Correctifs de sécurité automatiques" \
    || echo "   ⚠️  Correctifs automatiques non activés"
