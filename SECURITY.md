# Politique de sécurité

## Signaler une faille

**N'ouvrez pas d'issue publique.** Une issue est visible de tous, y compris de
quelqu'un qui exploiterait la faille avant qu'elle ne soit corrigée.

Utilisez un [avis de sécurité privé](https://github.com/Sissoko90/micro-service-restart/security/advisories/new),
ou à défaut écrivez à **security@abdatech.ml**.

Ce qui aide à traiter vite :

- le service et l'endpoint concernés ;
- ce qu'un attaquant obtient concrètement (lecture de données d'autrui, élévation
  de privilèges, déni de service…) ;
- les étapes de reproduction, requêtes comprises ;
- la version ou le commit testé.

### Ce à quoi vous pouvez vous attendre

| Étape | Délai visé |
|---|---|
| Accusé de réception | 48 heures |
| Première évaluation (gravité, périmètre) | 5 jours ouvrés |
| Correctif — gravité critique ou élevée | 30 jours |
| Correctif — gravité modérée ou faible | prochain cycle |

Nous vous tenons informé de l'avancement, et vous créditons dans l'avis publié
si vous le souhaitez.

## Périmètre

Sont concernés les neuf modules du dépôt. Ne le sont pas les déploiements de
tiers, ni les dépendances amont — pour celles-ci, signalez la faille à leur
éditeur ; nous suivons de notre côté les avis via Dependabot et Trivy.

## Ce qui est déjà connu

Par souci d'honnêteté envers ceux qui prennent le temps de chercher, les points
suivants sont **déjà identifiés** et documentés dans
[docs/GATEWAY-SECURITY.md](docs/GATEWAY-SECURITY.md) §5 :

| Point | État |
|---|---|
| Secrets présents dans l'historique Git (commit `71af837`) | À faire tourner — les retirer du suivi ne les rend pas secrets |
| Signature HMAC Gateway→services ne couvrant pas le corps de requête | Limite assumée : authentifie l'origine, pas l'intégrité |
| Absence de limitation par compte à la connexion | Le plafond est par IP ; une attaque distribuée sur un seul compte passe |
| `BiometricAuthServiceImpl.computeExpectedSignature` | Substitut, pas une vérification WebAuthn réelle |
| Les deux comptes administrateur partagent les mêmes identifiants par défaut | À séparer avant production — voir [docs/COMPLIANCE.md §1bis](docs/COMPLIANCE.md) |

Les signaler à nouveau est inutile ; les **approfondir** est utile.

## Corrigé le 2026-09-30

| Faille | Gravité | Correction |
|---|---|---|
| **La clé privée de signature JWT était empaquetée dans le JAR livré** (`BOOT-INF/classes/keys/dev_private.pem`). Quiconque disposait de l'artefact pouvait forger n'importe quel jeton, pour n'importe quel rôle, `SUPER_ADMIN` compris. Absente de l'historique Git | **Critique** | Chargement depuis le classpath refusé ; la clé vient de l'environnement ou d'un secret monté ; sans configuration le service refuse de démarrer |
| `RecoverySecretService` chiffrait en `RSA/ECB/PKCS1Padding`, vulnérable aux attaques par oracle de remplissage | Élevée | Remplacé par OAEP avec SHA-256 |
| Huit emplacements lisaient `X-Forwarded-For` directement : l'IP portant les décisions de blocage, de plafonnement et d'audit était fournie par l'appelant | Élevée | Centralisé dans `ClientIpResolver` (Gateway) et `GatewayClientIp` (services) |
| IDOR sur `quotas-service` et `setting-service` : épuiser le quota d'un client payant, lire et modifier les préférences d'autrui | Élevée | `ResourceOwnershipGuard` partagé, refus en 404 |
| Comparaison du code TOTP non constante en temps | Faible | `SecretComparisonUtil` |
| `GET /admin/totp/qr-code` était `permitAll()` et renvoyait le **secret TOTP en clair**. L'adresse de l'administrateur étant écrite en dur dans le dépôt, la graine du second facteur était publiquement récupérable — le second facteur ne protégeait rien | **Critique** | Remplacé par `POST /admin/totp/enroll`, qui exige le mot de passe et ne sert le secret qu'une fois, avant activation |
| Adresse, téléphone et nom réels en valeurs par défaut de `@Value` dans `AdminSeeder` : sur tout environnement mal configuré, un compte administrateur était créé avec un identifiant lisible dans le code | Élevée | Défauts retirés ; le service refuse de démarrer sans `ADMIN_DEFAULT_*` |
| 20 secrets avec une valeur par défaut dans la configuration, dont `GATEWAY_SECRET_KEY` — **avec deux valeurs différentes selon les services** | Élevée | Tous les défauts retirés : l'absence fait désormais échouer le démarrage |
| Fragments de secrets journalisés (4 caractères du mot de passe admin, 8 du secret TOTP) et envoyés vers Loki, où ils sont conservés | Modérée | Retirés |
| `GET /admin/info` exposait 8 caractères du secret TOTP | Modérée | Seul l'état du second facteur est exposé |

## Contrôles automatiques

Chaque modification passe par :

| Contrôle | Outil | Bloquant |
|---|---|---|
| Secrets dans le code et l'historique | Gitleaks | ✅ |
| Analyse statique (CWE / OWASP / MITRE) | CodeQL | signalé |
| Vulnérabilités des dépendances | Trivy | ✅ CVSS ≥ 7 |
| Second avis sur les dépendances | OWASP Dependency-Check | hebdomadaire |
| Licences incompatibles | `scripts/license-check.sh` | ✅ |
| Dépendances ajoutées par une PR | dependency-review | ✅ |

Le détail est dans [.github/workflows/security.yml](.github/workflows/security.yml).

## Divulgation

Nous appliquons une divulgation coordonnée : un avis public est publié une fois
le correctif disponible, ou au bout de 90 jours si le correctif prend plus de
temps — sauf si la faille est déjà exploitée, auquel cas nous publions plus tôt
avec les mesures de contournement.
