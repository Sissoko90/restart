# quotas-service

> Port **8089** · Cassandra (`quotas_keyspace`) · Consommateur Kafka

Source de vérité des limites d'usage par utilisateur et par palier commercial.

---

## 1. Modèle d'application : vérification synchrone, consommation asynchrone

```
POST /api/v1/projects
  └─ interface-service
       ├─ 1. GET quotas-service /quotas/{userId}/check?resource=PROJECTS&amount=1
       │       └─ allowed=false ──▶ 403 PROJECT_LIMIT_EXCEEDED (rien n'est créé)
       ├─ 2. save(project)
       └─ 3. kafka: project.created
                    └─ quotas-service : projects_count += 1
```

Suppression : `project.deleted` ⇒ `projects_count -= 1` (jamais sous zéro).

**Pourquoi ce découpage.** La vérification doit être synchrone, sinon on ne peut pas
refuser une création au bon moment. L'incrémentation, elle, doit se faire *après*
création effective : la faire dans le même appel obligerait à compenser en cas
d'échec de l'écriture du projet.

**Panne de quotas-service.** `QuotasServiceClient` est protégé par un circuit breaker
et **autorise par défaut** (fail-open). Un dépassement temporaire est jugé moins grave
que le blocage de tout le portail développeur ; les compteurs se resynchronisent
ensuite via Kafka.

**Limite connue.** Cassandra n'a pas de transaction multi-lignes : deux créations
simultanées par le même utilisateur peuvent dépasser la limite de 1. Une garantie
stricte demanderait des LWT Cassandra (`IF`), au prix d'une latence nettement
supérieure. Compromis assumé.

---

## 2. Paliers et limites

Les limites ne sont **pas** en base : elles vivent dans
`config-repo/dev/quotas-service-dev.yml`. Une évolution tarifaire est un changement de
configuration, pas une migration de données.

| Ressource | FREE | PREMIUM | ENTERPRISE |
|---|---|---|---|
| `STORAGE_MB` | 1 024 | 10 240 | 102 400 |
| `API_CALLS` (par heure) | 1 000 | 10 000 | 100 000 |
| `PROJECTS` | 10 | 100 | 1 000 |
| `COLLABORATORS` | 5 | 50 | 500 |

Seuils : alerte à 80 % (`quotas.check.warning-threshold`), blocage à 100 %
(`quotas.check.block-threshold`).

`PROJECTS`, `COLLABORATORS` et `STORAGE_MB` sont **cumulatives** (elles doivent être
libérées). `API_CALLS` est **fenêtrée** : le compteur repart de zéro à chaque heure.

---

## 3. API

Toutes les routes passent par le Gateway sous `/api/v1/quotas/**`.

| Méthode | Route | Description |
|---|---|---|
| `GET` | `/quotas/{userId}` | État complet : palier + consommation de chaque ressource |
| `GET` | `/quotas/{userId}/check?resource=X&amount=N` | Autorisation, sans effet de bord |
| `POST` | `/quotas/{userId}/consume` | Incrémente. **429** si dépassement |
| `POST` | `/quotas/{userId}/release` | Décrémente, jamais sous zéro |
| `PUT` | `/admin/quotas/{userId}/tier` | Change le palier (**ADMIN**) |
| `POST` | `/admin/quotas/{userId}/reset` | Remet la consommation à zéro (**ADMIN**) |

```bash
# Vérifier
curl "http://localhost:8081/api/v1/quotas/user-123/check?resource=PROJECTS&amount=1" \
  -H "Authorization: Bearer $TOKEN"

# Passer un utilisateur en PREMIUM
curl -X PUT http://localhost:8081/api/v1/quotas/user-123/tier \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"tier":"PREMIUM"}'
```

Un utilisateur inconnu n'est pas une erreur : il est créé au palier `FREE` à la
première consultation.

---

## 4. Schéma Cassandra

Les **tables** sont créées automatiquement (`schema-action: create-if-not-exists`).
Le **keyspace**, lui, doit préexister :

```bash
docker exec -i cassandra cqlsh < scripts/cassandra-init.cql
```

| Table | Clé | Contenu |
|---|---|---|
| `user_quota` | `user_id` | palier commercial |
| `user_consumption` | `user_id` | compteurs + début de fenêtre horaire API |

---

## 5. Sécurité

Même chaîne que les autres services métier :

1. `GatewayAccessWebFilter` — signature HMAC obligatoire, accès direct au port refusé ;
2. `JwtAuthenticationWebFilter` — JWT validé (clé par `kid`, issuer, audience `quotas-service`), rôle dérivé des **claims validés** ;
3. `SecurityConfig` — `/admin/**` réservé au rôle `ADMIN`.

Voir [JWT-CONTRACT.md](JWT-CONTRACT.md).

---

## 6. Reste à faire

- Tests d'intégration Testcontainers sur la persistance Cassandra (le `contextLoads` actuel désactive Cassandra et mocke les repositories).
- Brancher `STORAGE_MB` sur media-service (l'upload ne consomme pas encore de quota).
- Brancher `API_CALLS` sur le rate limiting du Gateway.
- `COLLABORATORS` : la notion de collaborateur n'existe pas encore dans interface-service.
