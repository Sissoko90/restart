# Conformité et référentiels de sécurité

> Audit du 2026-09-04. Ce document dit ce qui est en place **et ce qui ne l'est
> pas**. Une case cochée à tort coûte plus cher qu'une case vide.

---

## 1. Droit à l'effacement (RGPD art. 17) — ✅ implémenté

> Mise à jour du 2026-09-04. Auparavant, `deleteUser` se contentait de marquer
> `deleted_at` : la personne devenait invisible dans l'application, ses données
> restaient en base. L'article 17 ouvre un droit à l'**effacement**, pas à la
> dissimulation.

### Comment cela fonctionne

```
   POST /auth/erasure/me                     (la personne elle-même)
   POST /auth/erasure/{subjectId}            (administrateur)
            │
            ▼
   ┌─────────────────────┐   1. enregistre la demande AVANT tout effacement
   │    auth-service     │   2. efface ses propres données
   │                     │   3. publie UserErasureRequestedEvent
   └──────────┬──────────┘
              │  Kafka : user.erasure.requested
       ┌──────┴───────┐
       ▼              ▼
 ┌───────────┐  ┌────────────┐
 │   user    │  │   media    │   effacent, puis accusent réception
 └─────┬─────┘  └──────┬─────┘
       │               │  Kafka : user.erasure.completed
       └───────┬───────┘
               ▼
        registre d'exécution  →  GET /auth/erasure/{requestId}/status
```

**Pourquoi un événement et non des appels en cascade.** Un effacement
synchrone échouerait dès qu'un service est indisponible, et laisserait la
personne à moitié effacée sans trace de ce qui reste à faire.

**Pourquoi un registre.** L'article 5.2 impose de pouvoir *démontrer* la
conformité. Chaque service accuse réception ; une demande dont un accusé manque
reste visiblement inachevée plutôt que faussement close.

### Ce qui est effacé, service par service

| Service | Effacé | Conservé, et pourquoi |
|---|---|---|
| `auth-service` | courriel, téléphone, nom, prénom, pays, **empreinte du mot de passe**, jetons, biométrie, comptes OAuth, affectations de rôles | l'identifiant technique — les journaux d'audit y font référence ; il devient un jeton opaque |
| | IP, agent utilisateur et téléphone des journaux d'authentification | la ligne du journal, qui garde une valeur antifraude une fois détachée de la personne |
| `user-service` | date de naissance, **photographie sur le disque**, biographie, adresse, ville, pays, code postal, préférences, détail des journaux d'audit | la ligne, vidée |
| `media-service` | **pièces justificatives, vignettes et métadonnées** — objets supprimés du stockage puis de MongoDB | rien |
| `interface-service` | compte développeur, projets, **secret TOTP**, secret de récupération, jetons et codes en cours | la ligne, vidée ; le nom d'utilisateur est neutralisé car unique en base |
| `quotas-service` | palier d'abonnement et compteurs de consommation | rien |
| `setting-service` | toutes les préférences, sans tri | les paramètres système, qui ne concernent personne |

Trois points qui se manquent facilement, et que les tests verrouillent :

- **la photographie sur le disque.** Effacer l'URL en base sans supprimer le
  fichier laisse une photo d'identité accessible à qui connaît le chemin ;
- **l'empreinte du mot de passe.** Une empreinte BCrypt reste attaquable hors
  ligne, et les mots de passe sont souvent réutilisés ailleurs ;
- **l'ordre dans media-service.** Le fichier part avant ses métadonnées : l'ordre
  inverse perdrait le chemin de stockage en cas d'interruption, et le fichier
  deviendrait invisible à tout audit — présent, mais introuvable.

### Effacer n'est pas supprimer la ligne

Supprimer la ligne briserait les références des journaux d'audit et des écritures
de facturation. La règle appliquée : **écraser les champs identifiants, conserver
l'identifiant technique**. Une fois le courriel, le téléphone, le nom et
l'empreinte écrasés, cet identifiant ne renvoie plus à personne — aucune table ne
conserve la correspondance. C'est un effacement, non une pseudonymisation : celle-ci
suppose une table de correspondance conservée quelque part.

### État des autres droits

| Droit | Article | État |
|---|---|---|
| Effacement — utilisateurs finaux | 17 | ✅ auth, user, media, quotas, setting |
| Effacement — développeurs | 17 | ✅ interface, quotas, setting |
| Rectification | 16 | ✅ `PUT /users/me` |
| Accès | 15 | ⚠️ partiel — `GET /users/me` ne couvre pas documents, journaux, quotas |
| Portabilité | 20 | ❌ aucun export |

### Deux populations, deux points d'entrée

interface-service tient son **propre** registre d'utilisateurs : les développeurs
du portail. Ils n'ont rien à voir avec les utilisateurs finaux des applications,
dont l'identité vit dans auth-service. Deux tables `users`, deux espaces
d'identifiants.

La conséquence était sérieuse : **les développeurs n'avaient aucun moyen
d'exercer leur droit** — ni endpoint, ni suppression, pas même une suppression
logique. Leur adresse, leur empreinte de mot de passe et leur secret TOTP
restaient indéfiniment. Un secret TOTP conservé permet, à lui seul, de calculer
indéfiniment des codes valides pour un compte censé ne plus exister.

`POST /dev/erasure/me` couvre désormais cette population. Les deux flux
empruntent la même file : quotas-service et setting-service indexent leurs
données sur le sujet du jeton, quel qu'il soit, et un identifiant qui ne leur
correspond à rien produit simplement un effacement de zéro enregistrement.

### Ce qui reste à traiter

- **Comptes inactifs et consommation d'API** n'ont pas de purge. Pour les
  comptes inactifs, l'automatisation est délibérément écartée : effacer un
  compte sans intervention humaine sur la seule foi d'une date de dernière
  connexion est irréversible et se trompera un jour. La voie raisonnable est de
  détecter, notifier la personne, puis effacer sans réponse — pas d'effacer
  directement.
- **Les sauvegardes.** Un effacement qui laisse la donnée dans une sauvegarde
  restaurable n'est pas complet. Il faut documenter le cycle de rotation et
  rejouer l'effacement après toute restauration.
- **Le chiffrement ne dispense pas d'effacer.** Les clés des documents dérivent
  d'une clé maîtresse commune, conservée : un document chiffré reste
  déchiffrable tant qu'elle existe.

---

## 1bis. Les deux administrateurs — organisation ABANDONNÉE

> **Périmé depuis le 2026-09-30.** Cette section décrivait deux comptes
> administrateur dans deux services. Le modèle retenu est désormais un jeu de
> rôles unique dans `auth-service` — `SUPER_ADMIN`, `ADMIN`, `DEVELOPER` — décrit
> dans [ROLES.md](ROLES.md). `interface-service` devient la couche vitrine et
> n'aura plus de compte administrateur propre.
>
> La section est conservée le temps de la transition : la table `users`
> d'`interface-service` existe encore.

Point de confusion fréquent, et cause directe d'un échec de démarrage
d'interface-service : **la plateforme a deux comptes administrateur distincts**,
dans deux bases différentes.

| | Admin `auth-service` | Admin `interface-service` |
|---|---|---|
| **Table** | `auth_service_db.users` | `interface_db.users` |
| **Qui il administre** | les **utilisateurs finaux** des applications clientes | les **développeurs** du portail |
| **Sert à** | débloquer un compte, gérer les rôles métier, consulter les journaux d'authentification | valider une inscription développeur, gérer les projets et les quotas, la console d'administration |
| **Créé par** | `DataSeeder` | `AdminSeeder` |
| **Second facteur** | non | **TOTP obligatoire** à la connexion |
| **Point d'entrée** | `POST /auth/login` | `POST /admin/login` |
| **Configuration** | `admin.default.*` dans `auth-service-dev.yml` | `admin.default.*` dans `interface-service-dev.yml` |
| **Rôle émis** | `ROLE_ADMIN` dans un jeton de `type=user` | `ADMIN` dans un jeton de `type=developer` |

### Pourquoi deux, et pas un

Ce ne sont pas deux instances du même concept. Les deux populations ne se
recouvrent pas :

- un **utilisateur final** appartient à l'application d'un développeur client.
  Son identité vit dans `auth-service`, qui est le service d'authentification
  *offert en tant que service* ;
- un **développeur** est un client de Restart. Son identité vit dans
  `interface-service`, qui est le portail.

Fusionner les deux donnerait à l'administrateur du portail le pouvoir d'agir sur
les comptes des utilisateurs finaux de tous ses clients — ce qui n'est ni
souhaitable, ni défendable vis-à-vis de ces clients.

### Ce que cette distinction implique en pratique

**Les deux comptes doivent avoir des identifiants différents.** Ils partagent
aujourd'hui les mêmes variables `ADMIN_DEFAULT_*`, donc les mêmes. C'est
commode en développement et à corriger avant toute mise en production : une
compromission du portail ne doit pas livrer l'administration des utilisateurs
finaux.

**L'effacement suit deux chemins**, pour la même raison — voir §1 :
`POST /auth/erasure/me` pour les utilisateurs finaux, `POST /dev/erasure/me`
pour les développeurs.

**La configuration est dupliquée, volontairement.** Le bloc `admin.default.*`
apparaît dans les deux fichiers de configuration plutôt que dans le fichier
partagé. C'est ce qui permettra de leur donner des identifiants distincts sans
rien restructurer.

> L'oubli de ce bloc dans `interface-service-dev.yml` est ce qui faisait échouer
> son démarrage sur `Could not resolve placeholder 'admin.default.password'`.


## 2. Registre des données personnelles

Établi à partir des entités du code.

| Donnée | Service | Base | Finalité | Effacée sur demande |
|---|---|---|---|---|
| Téléphone, courriel, nom | `auth-service` | PostgreSQL | authentification | ✅ |
| Empreinte de mot de passe | `auth-service` | PostgreSQL | authentification | ✅ |
| Codes de vérification (OTP) | `auth-service` | PostgreSQL | vérification | ✅ expiration courte |
| Journaux d'authentification, IP | `auth-service` | PostgreSQL | sécurité, antifraude | ✅ anonymisés |
| Date de naissance, adresse | `user-service` | PostgreSQL | profil | ✅ |
| Photo de profil | `user-service` | disque | profil | ✅ fichier supprimé |
| Pièces d'identité | `media-service` | MongoDB + objets | vérification KYC | ✅ objets supprimés |
| Consommation d'API | `quotas-service` | Cassandra | facturation | ❌ pas encore |
| Projets, clés d'API | `interface-service` | PostgreSQL | portail développeur | ❌ pas encore |
| Jetons révoqués | `interface-service` | Redis | sécurité | ✅ TTL |

**L'adresse IP est une donnée personnelle.** Elle est journalisée dans
`AuthenticationLog` et sert à la géolocalisation et à la liste noire. Ces usages
sont légitimes au titre de la sécurité, mais doivent figurer dans l'information
donnée aux personnes. Elle est effacée lors d'un effacement de compte.

### Durées de conservation

Déclarées dans `config-repo/dev/application-dev.yml`, section `data-retention`.

| Donnée | Durée | Application |
|---|---|---|
| Codes de vérification | 10 minutes | ✅ appliquée |
| Jetons révoqués | 7 jours | ✅ TTL Redis |
| Pièces justificatives | 3 ans | ✅ `DocumentExpirationScheduler` |
| Journaux d'authentification | 1 an | ✅ `ScheduledTasksConfig`, dimanche 3 h |
| Journaux d'audit | 3 ans | ✅ `RetentionPurgeScheduler`, dimanche 4 h |
| Comptes inactifs | 3 ans | ⚠️ **déclarée, aucune purge** |
| Consommation d'API | 2 ans | ⚠️ **déclarée, aucune purge** |
| Registre des effacements | 10 ans | ✅ à dessein — preuve de conformité, sans donnée personnelle |

> Une durée déclarée sans purge ne protège personne. La distinction figure dans
> le fichier de configuration lui-même, pour qu'on ne s'y trompe pas.

#### Un défaut trouvé en implémentant ces purges

auth-service possédait déjà un ordonnanceur de nettoyage. Il était écrit ainsi :

```java
Mono.fromRunnable(() -> authenticationLogService.cleanupOldLogs(90))
    .subscribeOn(Schedulers.boundedElastic())
    .subscribe();
```

Le corps du `Runnable` **appelle** la méthode — donc construit le `Mono` de
suppression — puis le jette. Un `Mono` froid jamais souscrit n'exécute rien. Ce
qui était souscrit, c'est le `fromRunnable`, dont le travail se réduisait à créer
un objet.

**Aucune des trois purges ne supprimait quoi que ce soit** : codes à usage
unique, jetons de rafraîchissement expirés et journaux d'authentification —
adresses IP, agents utilisateurs et numéros de téléphone compris — s'accumulaient
depuis l'origine. Le message « démarrage de la tâche de nettoyage » était écrit
à chaque exécution, ce qui donnait toutes les apparences du bon fonctionnement.

La durée était par ailleurs écrite en dur à 90 jours, alors que la configuration
en annonçait 365.

Les tests correspondants n'observent pas l'appel de la méthode — la version
défectueuse l'appelait bien — mais la **souscription** elle-même.

---

## 3. OWASP Top 10 (2021)

| Risque | État | Où |
|---|---|---|
| **A01** Contrôle d'accès défaillant | ✅ corrigé | Deux IDOR trouvés et corrigés — [GATEWAY-SECURITY.md §2](GATEWAY-SECURITY.md) |
| **A02** Défaillances cryptographiques | ✅ corrigé | AES-256-GCM, dérivation de clé rendue non ambiguë, JWT RS256 avec rotation |
| **A03** Injection | ✅ vérifié | Aucune concaténation SQL ; filtres XSS et traversée de chemin au Gateway |
| **A04** Conception non sécurisée | ✅ traité | Effacement RGPD conçu et implémenté (§1) |
| **A05** Mauvaise configuration | ✅ corrigé | CORS strict, en-têtes CSP/HSTS, double exécution des filtres supprimée |
| **A06** Composants vulnérables | ✅ outillé | Trivy + Dependabot + OWASP Dependency-Check |
| **A07** Identification défaillante | ✅ corrigé | Verrouillage de compte, TOTP conforme RFC 6238, révocation appliquée au Gateway |
| **A08** Intégrité logicielle | ⚠️ partiel | Dépendances vérifiées ; artefacts non signés, pas de SBOM publié |
| **A09** Journalisation insuffisante | ⚠️ partiel | Journaux de sécurité présents ; pas d'alerte automatisée |
| **A10** SSRF | ✅ | Aucune URL fournie par l'utilisateur n'est appelée côté serveur |

---

## 4. MITRE — CWE Top 25

CodeQL exécute le jeu `security-and-quality`, qui couvre ces catégories. Celles
qui ont donné lieu à une correction réelle dans ce dépôt :

| CWE | Intitulé | Trouvé ici |
|---|---|---|
| **CWE-639** | Autorisation contournée par clé contrôlée par l'utilisateur | `GET /users/{id}`, `GET /files/{userId}/…` |
| **CWE-22** | Traversée de chemin | `FileReactiveController` |
| **CWE-807** | Décision de sécurité fondée sur une entrée non fiable | `X-Forwarded-For` dans quatre filtres |
| **CWE-613** | Expiration de session insuffisante | Jeton de rafraîchissement accepté comme jeton d'accès |
| **CWE-323** | Réutilisation de clé et de vecteur d'initialisation | Vérifié par test — non présent |
| **CWE-798** | Identifiants codés en dur | `.env` committé ; Gitleaks empêche la récidive |
| **CWE-209** | Divulgation par message d'erreur | Refus en 404 plutôt qu'en 403 |

Le détail de chaque correction est dans [GATEWAY-SECURITY.md](GATEWAY-SECURITY.md).

---

## 5. Chaîne d'approvisionnement logicielle

| Mesure | État |
|---|---|
| Versions figées dans le POM parent | ✅ source unique |
| Montées majeures non automatiques | ✅ décidées, pas subies |
| Analyse des dépendances à chaque modification | ✅ Trivy |
| Second avis hebdomadaire | ✅ OWASP Dependency-Check |
| Licences vérifiées | ✅ `scripts/license-check.sh` |
| SBOM publié | ❌ à faire (CycloneDX) |
| Artefacts signés | ❌ à faire |
| Actions GitHub épinglées par empreinte | ⚠️ épinglées par version, pas par SHA |

---

## 6. Ce qu'il faut faire avant une mise en production

Par ordre d'urgence.

1. **Faire tourner tous les secrets** du commit `71af837`. Ils sont dans
   l'historique Git et doivent être considérés comme compromis.
2. **Étendre l'effacement** à `quotas-service`, `interface-service` et
   `setting-service`, puis les inscrire dans `erasure.expected-services`.
3. **Implémenter les purges** des durées déclarées mais non appliquées (§2).
4. Renseigner `gateway.trusted-proxies` si un proxy est en frontal, sans quoi le
   plafond de débit devient global.
5. Implémenter la portabilité (art. 20) : un export des données personnelles.
6. Remplacer le substitut WebAuthn par une vraie vérification de signature.
7. Publier un SBOM et signer les artefacts.
