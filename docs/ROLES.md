# Modèle de rôles

> Décidé le 2026-09-30. Remplace l'organisation précédente, qui reposait sur deux
> comptes administrateur distincts dans deux services.

---

## 1. Les rôles

Trois familles s'y mêlent, et les confondre conduit à des autorisations trop
larges.

### Rôles métier

Ils décrivent ce qu'une personne **est** sur la plateforme, pas ce qu'elle a le
droit d'administrer.

`USER` · `DRIVER` · `DELIVERY` · `MERCHANT` · `PHARMACY` · `RESTAURANT` ·
`SUPERMARKET` · `TENANT` · `DISPATCHER`

### Rôle d'accès API

**`DEVELOPER`** — un tiers qui consomme l'API. Soumis aux quotas comme les
autres, et **sans aucun pouvoir d'administration**. C'est la confusion la plus
coûteuse possible dans ce modèle : un développeur n'administre rien.

### Rôles d'administration

| | `ADMIN` | `SUPER_ADMIN` |
|---|---|---|
| Attribuer et retirer des rôles | oui | oui |
| Débloquer un compte | oui | oui |
| Consulter les journaux d'authentification et d'audit | oui | oui |
| Mettre le site en maintenance | non | oui |
| Faire tourner les clés de signature JWT | non | oui |
| Déclencher un effacement RGPD pour le compte d'autrui | non | oui |
| Modifier les paramètres système | non | oui |
| Purger les données à durée de conservation écoulée | non | oui |

En une phrase : **`ADMIN` fait fonctionner l'application, `SUPER_ADMIN` en est
responsable techniquement.**

---

## 2. L'héritage est résolu à l'émission du jeton

`SUPER_ADMIN` hérite de tout ce que peut `ADMIN`. Cet héritage n'est **pas**
déclaré dans chaque service : le jeton d'un super administrateur porte
directement les deux rôles.

```java
// UserRole.expand()
SUPER_ADMIN.expand()  →  { SUPER_ADMIN, ADMIN }
ADMIN.expand()        →  { ADMIN }
```

Appelé par `JwtServiceImpl` au moment de la signature.

**Pourquoi ainsi.** Huit services consomment ces jetons. Une `RoleHierarchy`
configurée service par service serait oubliée dans l'un d'eux, et le défaut
serait invisible : l'endpoint continuerait de fonctionner pour `ADMIN`, et
refuserait `SUPER_ADMIN` sans que rien ne le signale. Résoudre à l'émission met
les services d'accord sans qu'ils aient à connaître la hiérarchie — ils lisent
des rôles effectifs.

Conséquence pratique : `hasRole('ADMIN')` accepte un super administrateur, et
`hasRole('SUPER_ADMIN')` continue de refuser un administrateur. Les annotations
existantes n'ont pas eu à changer.

---

## 3. `SUPER_ADMIN` n'est pas attribuable par l'API

Il n'existe que par le seeder, au déploiement.

Sans cette règle, un `ADMIN` compromis se promouvrait lui-même : le pouvoir de
distribuer les rôles inclurait celui de s'octroyer le plus élevé, et la
distinction entre les deux ne servirait plus à rien.

Le contrôle est fait **dans le service**, `UserRoleManagementServiceImpl`, et pas
seulement par une annotation sur le contrôleur — une règle vérifiée à un seul
endroit se contourne en ajoutant un second point d'entrée.

```java
if (!role.estAttribuableParApi()) {
    return Mono.error(new IllegalArgumentException(...));
}
```

---

## 4. Ce que cela change par rapport à avant

L'organisation précédente reposait sur **deux comptes administrateur distincts** :
un dans `auth-service` pour les utilisateurs finaux, un dans `interface-service`
pour le portail. Elle est abandonnée.

| | Avant | Maintenant |
|---|---|---|
| Comptes admin | 2, dans 2 bases | 1 modèle de rôles, dans `auth-service` |
| `interface-service` | portail développeur avec sa propre table `users` | couche vitrine — site, affichages, publicité |
| Accès API d'un tiers | compte dans `interface-service` | rôle `DEVELOPER` dans `auth-service` |
| Maintenance du site | admin `interface-service` | `SUPER_ADMIN` |

> `interface-service` pourra redevenir un portail développeur plus tard. Dans ce
> cas il n'aura pas besoin de sa propre table d'utilisateurs : ses visiteurs
> s'authentifieront via `auth-service` avec le rôle `DEVELOPER`.

---

## 5. Le seeder du SUPER_ADMIN

`SuperAdminSeeder` est le **seul** chemin par lequel le rôle existe.

| Règle | Pourquoi |
|---|---|
| Variables `SUPER_ADMIN_*`, distinctes de `ADMIN_DEFAULT_*` | partager les identifiants entre le responsable technique et l'administrateur fonctionnel annulerait la séparation que ces deux rôles établissent |
| Aucune valeur de repli — sans configuration, **rien n'est créé** | un compte administrateur aux identifiants lisibles dans le dépôt est pire que pas de compte |
| Idempotent : un compte existant n'est pas recréé | sinon chaque démarrage écraserait un mot de passe changé depuis |
| Un rôle retiré à la main est rétabli | sinon le compte existe sans ses pouvoirs, et plus personne ne peut administrer |
| Un échec de base ne fait pas échouer le démarrage | mieux vaut un service debout sans `SUPER_ADMIN`, avec l'erreur journalisée, qu'un service qui refuse de se lever |

---

## 6. Trois failles corrigées en chemin

### Trente-trois annotations d'autorisation inertes

`auth-service` portait **33 annotations `@PreAuthorize` sans
`@EnableReactiveMethodSecurity`**. Tous les autres services l'activaient ; pas
celui-là. Ces 33 annotations étaient donc **décoratives** : seuls les chemins
déclarés dans la chaîne de filtres étaient réellement contrôlés.

La plupart des endpoints concernés vivaient sous `/admin/**` ou `/auth-users/**`,
tous deux protégés par la chaîne. Mais un ne l'était pas :

```
POST /auth/erasure/{subjectId}      @PreAuthorize("hasRole('ADMIN')")   ← inerte
.pathMatchers("/auth/**").authenticated()                               ← seule règle appliquée
```

**N'importe quel utilisateur authentifié pouvait donc effacer irréversiblement
les données de n'importe qui.** L'endpoint venait d'être ajouté par le chantier
RGPD ; c'est l'analyse du modèle de rôles qui l'a mis au jour.

Deux corrections, volontairement redondantes :

1. `@EnableReactiveMethodSecurity` activé — les 33 annotations fonctionnent ;
2. une règle explicite dans la chaîne, **avant** la règle générale `/auth/**` :

```java
.pathMatchers(HttpMethod.POST, "/auth/erasure/me").authenticated()
.pathMatchers("/auth/erasure/**").hasRole("SUPER_ADMIN")
.pathMatchers("/auth/**").authenticated()
```

L'effacement pour le compte d'autrui relève de `SUPER_ADMIN` et non d'`ADMIN` :
c'est irréversible et cela engage l'organisation. Un `ADMIN` gère les rôles et les
comptes ; il n'a pas à pouvoir détruire les données d'une personne.

L'ordre compte : une règle plus générale déclarée avant une règle plus précise
l'emporte, et c'est exactement ce qui s'était produit.

### La rotation des clés était ouverte à tout porteur de jeton

```java
/**
 * Déclenche une rotation manuelle des clés
 * Réservé aux administrateurs          ← commentaire, pas une règle
 */
@PostMapping("/admin/keys/rotate")      ← aucun @PreAuthorize, aucun pathMatcher
```

Les trois endpoints de gestion du matériel cryptographique —
`/dev/admin/keys/rotate`, `/status`, `/expired` — tombaient sous
`anyExchange().authenticated()`. **N'importe quel porteur de jeton pouvait faire
tourner les clés de signature de la plateforme**, ou supprimer des clés encore en
période de grâce, ce qui invalide les jetons en circulation.

Un commentaire qui affirme une protection est plus dangereux que pas de
commentaire : il décourage la vérification.

### `SUPER_ADMIN` était auto-attribuable à l'inscription

```java
if (desiredRole == UserRole.ADMIN) {    ← une liste tenue à la main
    return UserRole.USER;
}
```

Le contrôle nommait `ADMIN` explicitement. `SUPER_ADMIN` n'existait pas encore ;
dès son ajout, **une inscription portant `role=SUPER_ADMIN` l'aurait obtenu**.

C'est le défaut classique d'une liste tenue à la main : elle est exacte le jour où
on l'écrit. La question posée est désormais « ce rôle est-il un rôle
d'administration ? », à laquelle `UserRole` répond — un futur rôle
d'administration sera couvert sans que ce code change.

---

## 6bis. La connexion des administrateurs

`POST /auth/admin/login` — **public**, car un administrateur n'a aucune
application devant lui pour présenter un jeton.

### Le gap qu'il comble

Tout `/auth/**` est sous `authenticated()`, et c'est délibéré : auth-service est
un SDK, c'est l'application du développeur qui appelle `/auth/login` pour son
utilisateur final, en présentant son propre jeton.

Conséquence non vue au moment d'écrire le seeder : **le `SUPER_ADMIN` n'avait
aucun chemin de connexion.** Son compte vit dans `auth_service_db.users`, et le
seul login public de la plateforme lisait la table d'`interface-service`. Le compte
existait sans être utilisable.

### Ce que ce chemin exige

| Contrôle | Détail |
|---|---|
| Rôle d'administration | sans quoi cet endpoint public serait un second point d'entrée pour les utilisateurs finaux, contournant le modèle du SDK |
| Mot de passe | BCrypt coût 12 |
| **Code TOTP** | obligatoire dès que le second facteur est enrôlé |

Le second facteur n'est pas un confort : ce compte peut mettre le site en
maintenance, faire tourner les clés de signature et déclencher un effacement
administratif.

`TotpServiceImpl` a été **remonté dans `platform-commons`** avec ses 18 tests,
plutôt que réécrit — deux implémentations d'un second facteur divergent, et l'une
des deux finit par être la mauvaise.

### L'impasse de l'enrôlement, et sa sortie

`/auth/admin/login` exige un code TOTP ; obtenir le secret exige d'être connecté.
La même impasse que dans `interface-service`, qui avait été résolue par un
`permitAll()` — et le secret était alors récupérable sans authentification.

Sortie retenue ici : **la connexion passe tant que le second facteur n'est pas
enrôlé**, et `POST /auth/admin/totp/enroll` remet le secret **une seule fois**,
contre le mot de passe. Une fois `totpEnabled` à vrai, le secret n'est plus jamais
servi.

### Aucune divulgation

Compte inexistant, mot de passe faux, code faux, compte sans rôle
d'administration : **la même erreur**. Distinguer ces cas permettrait d'énumérer
les comptes, et surtout d'identifier lesquels sont administrateurs — c'est-à-dire
de désigner la cible. Chaque refus est en revanche journalisé : le refus est muet
pour l'appelant, pas pour l'exploitant.

20 tests verrouillent ces règles.

---

## 7. Ce qui reste à faire

| Sujet | État |
|---|---|
| `/dev/admin/keys/**` sous `SUPER_ADMIN` | fait |
| `/auth/erasure/**` sous `SUPER_ADMIN` | fait |
| Seeder du `SUPER_ADMIN`, avec identifiants distincts (`SUPER_ADMIN_*`) | fait |
| Connexion des administrateurs, avec second facteur | fait — §6bis |
| Extraction de l'autorité de signature JWT dans un service dédié | décidé, non commencé |
| Suppression de la table `users` d'`interface-service` | décidé, non commencé |
| `/maintenance/**` : canal d'urgence à identifiants propres — à rattacher ou non à `SUPER_ADMIN` | à décider |

Les deux extractions forment un chantier à part : nouveau module, migration des
identités, et tous les services doivent repointer leur URL JWKS. À planifier
séparément — elles ne peuvent pas se faire sans interruption si l'ancienne
autorité s'arrête avant que la nouvelle ne serve des clés.
