# setting-service

> Port **8090** · PostgreSQL R2DBC (`setting_service_db`) · Flyway

Paramètres de la plateforme, à deux niveaux : **système** (global, catalogue fermé)
et **utilisateur** (préférences, clés libres).

---

## 1. Principe : la configuration porte les défauts, la base les surcharges

```
Lecture d'un paramètre système
  ├─ ligne en base ?  ── oui ──▶ valeur surchargée  (overridden = true)
  └─                     non ──▶ valeur de config-repo (overridden = false)
```

La table `system_setting` ne contient **que les paramètres modifiés**. Trois
conséquences voulues :

- changer une valeur par défaut dans `config-repo` prend effet immédiatement pour
  tous les paramètres non surchargés, sans migration ;
- « réinitialiser » un paramètre revient à **supprimer sa ligne**, jamais à
  réécrire la valeur d'origine ;
- la réponse indique toujours `value`, `defaultValue` et `overridden` : on sait
  d'où vient la valeur courante.

---

## 2. Catalogue système (liste blanche)

Les clés modifiables sont figées dans `SettingsProperties.catalog()`. Une clé
inconnue est rejetée en **404** — sans quoi un appelant pourrait injecter des
paramètres arbitraires dans la configuration de la plateforme.

| Clé | Type | Défaut | Catégorie |
|---|---|---|---|
| `system.maintenance-mode` | booléen | `false` | SYSTEM |
| `system.registration-enabled` | booléen | `true` | SYSTEM |
| `system.email-verification-required` | booléen | `true` | SYSTEM |
| `system.phone-verification-required` | booléen | `false` | SYSTEM |
| `system.two-factor-auth-enabled` | booléen | `false` | SYSTEM |
| `system.password-complexity-enabled` | booléen | `true` | SYSTEM |
| `system.session-timeout-minutes` | entier | `30` | SYSTEM |
| `security.max-login-attempts` | entier | `5` | SECURITY |
| `security.lockout-duration-minutes` | entier | `15` | SECURITY |
| `security.password-expiry-days` | entier | `90` | SECURITY |
| `security.force-password-change` | booléen | `false` | SECURITY |
| `notifications.email-enabled` | booléen | `true` | NOTIFICATIONS |
| `notifications.sms-enabled` | booléen | `true` | NOTIFICATIONS |
| `notifications.push-enabled` | booléen | `false` | NOTIFICATIONS |

Chaque valeur est validée contre son type : `"beaucoup"` pour un entier est
rejeté en **400**, `"oui"` pour un booléen aussi.

`system.maintenance-mode` fait l'objet d'une trace dédiée (`WARN` à l'activation) :
ce paramètre coupe l'accès à la plateforme.

---

## 3. API

Routes exposées sous `/settings/**` et `/admin/settings/**` ; le Gateway y route
`/api/v1/settings/**`.

| Méthode | Route | Accès |
|---|---|---|
| `GET` | `/settings/system[?category=SECURITY]` | authentifié |
| `GET` | `/settings/system/{key}` | authentifié |
| `GET` | `/settings/users/{userId}` | authentifié |
| `GET` | `/settings/users/{userId}/{key}` | authentifié — **404** si non définie |
| `PUT` | `/settings/users/{userId}/{key}` | authentifié |
| `DELETE` | `/settings/users/{userId}/{key}` | authentifié |
| `PUT` | `/admin/settings/system/{key}` | **ADMIN** |
| `DELETE` | `/admin/settings/system/{key}` | **ADMIN** — réinitialise |
| `DELETE` | `/admin/settings/users/{userId}` | **ADMIN** — suppression de compte |

```bash
# Activer le mode maintenance
curl -X PUT http://localhost:8081/api/v1/admin/settings/system/system.maintenance-mode \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"value":"true"}'

# Revenir à la valeur de configuration
curl -X DELETE http://localhost:8081/api/v1/admin/settings/system/system.maintenance-mode \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

Toute modification système est tracée avec son auteur (`updatedBy`), dérivé du JWT.

---

## 4. Schéma

Créé par Flyway au démarrage — voir `V1__init_settings_schema.sql`.

| Table | Clé | Contenu |
|---|---|---|
| `system_setting` | `setting_key` | surcharges uniquement |
| `user_setting` | `id`, unique `(user_id, setting_key)` | préférences par utilisateur |

Les deux entités implémentent `Persistable` : les clés primaires étant assignées
par l'application, sans cela Spring Data R2DBC émettrait un `UPDATE` pour une ligne
jamais insérée et le `save()` serait **silencieusement sans effet**.

---

## 5. Sécurité

Même chaîne que les autres services métier :

1. `GatewayAccessWebFilter` — signature HMAC obligatoire, accès direct au port refusé ;
2. `JwtAuthenticationWebFilter` — JWT validé (clé par `kid`, issuer, audience `setting-service`), rôle dérivé des **claims validés** ;
3. `SecurityConfig` — `/admin/**` réservé au rôle `ADMIN`.

Voir [JWT-CONTRACT.md](JWT-CONTRACT.md).

---

## 6. Reste à faire

- Les autres services lisent encore leurs seuils depuis leur propre configuration
  (`AppConstants.MAX_FAILED_LOGIN_ATTEMPTS` dans auth-service, par exemple). Les
  brancher sur setting-service demanderait un client + un cache, et une décision :
  un paramètre modifié à chaud doit-il s'appliquer immédiatement aux sessions en cours ?
- `system.maintenance-mode` n'est pas encore consommé par le Gateway.
- Tests d'intégration Testcontainers sur la persistance.
