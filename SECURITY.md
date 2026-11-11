# Politique de sécurité

## Signaler une faille

**N'ouvrez pas d'issue publique.** Une issue est visible de tous, y compris de
quelqu'un qui exploiterait la faille avant qu'elle ne soit corrigée.

Utilisez un [avis de sécurité privé](https://github.com/Sissoko90/restart/security/advisories/new).
C'est le canal recommandé : il est confidentiel, il reste attaché au dépôt, et il
permet de discuter du correctif avant toute publication.

> L'adresse de courriel qui figurait ici a été retirée avec le reste de l'ancien
> nommage. En ajouter une est utile le jour où le dépôt s'ouvrira à des
> rapporteurs externes — tant qu'il est privé, l'avis de sécurité suffit, et
> quiconque peut lire ce fichier peut en ouvrir un.

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
| Les liens « c'est moi » / « ce n'est pas moi » de l'alerte de connexion administrateur sont des `GET` | Un scanner de messagerie qui préfiltre les liens peut déclencher le blocage sans qu'on ait cliqué. Demande une page de confirmation côté frontend, qui n'existe pas encore |

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

## Corrigé le 2026-11-11

| Faille | Gravité | Correction |
|---|---|---|
| **Injection HTML dans l'alerte de sécurité administrateur.** `AdminController` insérait le `User-Agent`, l'adresse et les champs de géolocalisation dans le corps HTML du courriel par `String.format`, sans échappement. Le `User-Agent` est posé par la personne que l'alerte dénonce : elle pouvait écrire un lien ou un faux bouton dans le message qui la signale, et l'exploitant n'avait aucun moyen de l'en distinguer | Élevée | Les textes vivent dans notification-service ; l'émetteur ne fournit que des variables, échappées par le rendu. `AucunCorpsHtmlEnJavaTest` refuse le retour du balisage dans du Java |
| Trois alertes partaient en `text/plain` **avec leur balisage HTML visible** : le corps était composé en Java et transporté par un modèle sans variante `corps-html` | Faible | Trois modèles réels, dont deux avec variante HTML |
| `AdminController` lisait `@Value("${admin.mail.from}")`, clef retirée de la configuration et **sans valeur par défaut** : tout démarrage neuf échouait sur un marqueur non résolu. Le défaut était masqué en local par une variable morte restée dans `.env`, Spring résolvant aussi contre l'environnement par liaison souple | Modérée | Clef remplacée par `admin.default.email`, qui est ce que le champ désignait. `ValeursInjecteesResolublesTest` refuse toute clef injectée sans source déclarée, et ne lit jamais l'environnement de celui qui l'exécute |
| Sept endpoints `/internal/**` étaient publiés dans les collections Postman avec l'URL de la passerelle, qui ne les route pas. Outre les 404, cela invitait à router `/internal` — ce qui aurait détruit l'isolement qui fait leur sécurité | Faible | Les requêtes internes pointent le port du service et portent `X-Internal-Secret` |

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
