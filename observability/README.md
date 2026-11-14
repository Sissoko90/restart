# 📊 STACK D'OBSERVABILITÉ - MICROSERVICES

Stack complète d'observabilité pour les microservices avec **Grafana** comme interface unique.

## 🎯 COMPOSANTS

| Composant | Port | Rôle | URL |
|-----------|------|------|-----|
| **Grafana** | 3000 | Interface unique d'observabilité | http://localhost:3000 |
| **Prometheus** | 9090 | Collecte des métriques | http://localhost:9090 |
| **Loki** | 3100 | Agrégation des logs | http://localhost:3100 |
| **Tempo** | 3200, 9411 | Traces distribuées (Zipkin compatible) | http://localhost:3200 |
| **AlertManager** | 9093 | Gestion des alertes | http://localhost:9093 |

---

## 🚀 DÉMARRAGE RAPIDE

### 1. Démarrer la stack d'observabilité

```bash
cd /Users/abdatytechnologie/Documents/Restart
docker-compose -f docker-compose-observability.yml up -d
```

### 2. Vérifier que tous les services sont démarrés

```bash
docker-compose -f docker-compose-observability.yml ps
```

### 3. Accéder à Grafana

- URL: http://localhost:3000
- Username: `admin`
- Password: `admin`

### 4. Démarrer les microservices

```bash
# Terminal 1 - Gateway
cd gateway-service
mvn spring-boot:run

# Terminal 2 - Auth Service
cd auth-service
mvn spring-boot:run

# Terminal 3 - User Service
cd user-service
mvn spring-boot:run
```

---

## 📈 UTILISATION DE GRAFANA

### **Datasources configurées automatiquement**

Les datasources suivantes sont pré-configurées :

1. **Prometheus** (par défaut) - Métriques
2. **Loki** - Logs centralisés
3. **Tempo** - Traces distribuées

### **Dashboards recommandés à importer**

Dans Grafana, aller dans **Dashboards > Import** et utiliser ces IDs :

| Dashboard | ID | Description |
|-----------|----|----|
| Spring Boot 2.1 Statistics | 11378 | Métriques JVM, HTTP, DB |
| Spring Boot APM Dashboard | 12900 | Performance applicative |
| Loki Dashboard | 13639 | Visualisation des logs |
| Tempo Dashboard | 16594 | Traces distribuées |

### **Requêtes utiles**

#### **Prometheus (Métriques)**

```promql
# Taux de requêtes HTTP par service
rate(http_server_requests_seconds_count[5m])

# Temps de réponse P95
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))

# Utilisation CPU
process_cpu_usage

# Utilisation mémoire JVM
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}

# Taux d'erreurs
rate(http_server_requests_seconds_count{status=~"5.."}[5m])
```

#### **Loki (Logs)**

```logql
# Tous les logs d'un service
{app="auth-service"}

# Logs avec erreurs
{app="auth-service"} |= "ERROR"

# Logs avec trace ID spécifique
{app="auth-service"} | json | traceId="abc123"

# Logs des 5 dernières minutes avec niveau ERROR
{app=~".*-service"} | json | level="ERROR"
```

#### **Tempo (Traces)**

- Rechercher par **Service Name**: `auth-service`, `user-service`, `gateway-service`
- Rechercher par **Trace ID**: Copier depuis les logs
- Visualiser le **Service Graph** pour voir les dépendances

---

## 🔔 ALERTES

### **Configuration AlertManager**

Fichier: `observability/alertmanager/alertmanager.yml`

**Alertes configurées :**

- ✅ Service Down (critique)
- ✅ High CPU Usage (warning)
- ✅ High Memory Usage (warning)
- ✅ High Error Rate (critique)
- ✅ Slow Response Time (warning)
- ✅ Database Connection Pool Exhaustion (critique)
- ✅ Redis Connection Issues (warning)
- ✅ High JWT Validation Failures (warning)
- ✅ Suspicious Authentication Attempts (critique)

### **Personnaliser les notifications**

Éditer `observability/alertmanager/alertmanager.yml` :

```yaml
receivers:
  - name: 'critical-alerts'
    email_configs:
      - to: 'votre-email@abdatytch.com'
    slack_configs:
      - api_url: 'https://hooks.slack.com/services/YOUR/SLACK/WEBHOOK'
        channel: '#alerts'
```

---

## 🔍 CORRÉLATION LOGS-TRACES-MÉTRIQUES

### **Flux de corrélation**

```
1. Voir une métrique anormale dans Prometheus
   ↓
2. Cliquer sur "Explore" → Loki
   ↓
3. Filtrer les logs par service et période
   ↓
4. Trouver le traceId dans les logs
   ↓
5. Cliquer sur le lien Tempo
   ↓
6. Visualiser la trace complète avec tous les spans
```

### **Exemple pratique**

1. **Prometheus** : Détecter un pic de latence
   ```promql
   histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))
   ```

2. **Loki** : Chercher les logs de la période
   ```logql
   {app="auth-service"} | json | level="ERROR"
   ```

3. **Tempo** : Analyser la trace complète avec le traceId trouvé

---

## 📊 MÉTRIQUES PERSONNALISÉES

### **Ajouter des métriques custom**

Dans votre code Spring Boot :

```java
@Component
public class CustomMetrics {
    
    private final Counter loginAttempts;
    private final Timer jwtValidationTimer;
    
    public CustomMetrics(MeterRegistry registry) {
        this.loginAttempts = Counter.builder("login.attempts")
            .description("Number of login attempts")
            .tag("status", "success")
            .register(registry);
            
        this.jwtValidationTimer = Timer.builder("jwt.validation.duration")
            .description("JWT validation duration")
            .register(registry);
    }
    
    public void recordLoginAttempt(boolean success) {
        Counter.builder("login.attempts")
            .tag("status", success ? "success" : "failure")
            .register(registry)
            .increment();
    }
}
```

---

## 🛠️ MAINTENANCE

### **Arrêter la stack**

```bash
docker-compose -f docker-compose-observability.yml down
```

### **Arrêter et supprimer les volumes**

```bash
docker-compose -f docker-compose-observability.yml down -v
```

### **Voir les logs des conteneurs**

```bash
# Tous les logs
docker-compose -f docker-compose-observability.yml logs -f

# Logs d'un service spécifique
docker-compose -f docker-compose-observability.yml logs -f grafana
docker-compose -f docker-compose-observability.yml logs -f prometheus
```

### **Redémarrer un service**

```bash
docker-compose -f docker-compose-observability.yml restart grafana
```

---

## 📝 RÉTENTION DES DONNÉES

| Composant | Rétention | Configuration |
|-----------|-----------|---------------|
| **Prometheus** | 15 jours | `--storage.tsdb.retention.time=15d` |
| **Loki** | 30 jours | `retention_period: 720h` |
| **Tempo** | 1 heure | `block_retention: 1h` |

Pour modifier, éditer les fichiers de configuration respectifs.

---

## 🎓 RESSOURCES

- [Grafana Documentation](https://grafana.com/docs/)
- [Prometheus Query Examples](https://prometheus.io/docs/prometheus/latest/querying/examples/)
- [LogQL Cheat Sheet](https://grafana.com/docs/loki/latest/logql/)
- [Tempo Documentation](https://grafana.com/docs/tempo/latest/)
- [AlertManager Configuration](https://prometheus.io/docs/alerting/latest/configuration/)

---

## ✅ CHECKLIST DE VÉRIFICATION

- [ ] Stack d'observabilité démarrée
- [ ] Grafana accessible sur http://localhost:3000
- [ ] Datasources configurées (Prometheus, Loki, Tempo)
- [ ] Microservices démarrés et exposent `/actuator/prometheus`
- [ ] Logs envoyés à Loki (vérifier dans Explore)
- [ ] Traces visibles dans Tempo
- [ ] Alertes configurées dans AlertManager
- [ ] Dashboards importés

---

## 🆘 TROUBLESHOOTING

### **Prometheus ne scrape pas les métriques**

```bash
# Vérifier que l'endpoint est accessible
curl http://localhost:8082/api/v1/actuator/prometheus

# Vérifier la configuration Prometheus
docker exec prometheus cat /etc/prometheus/prometheus.yml

# Voir les targets dans Prometheus UI
# http://localhost:9090/targets
```

### **Loki ne reçoit pas les logs**

```bash
# Vérifier que Loki est accessible
curl http://localhost:3100/ready

# Tester l'envoi manuel
curl -H "Content-Type: application/json" -XPOST -s "http://localhost:3100/loki/api/v1/push" \
  --data-raw '{"streams": [{"stream": {"app": "test"}, "values": [["'$(date +%s)000000000'", "test log"]]}]}'
```

### **Tempo ne reçoit pas les traces**

```bash
# Vérifier que Tempo est accessible
curl http://localhost:3200/ready

# Vérifier l'endpoint Zipkin
curl http://localhost:9411/health
```

---

**🎉 Votre stack d'observabilité est prête !**

---

## La chaîne de journalisation, module par module

Le mode d'emploi ci-dessus — « chercher les logs de la période, y trouver le
traceId, analyser la trace » — suppose trois choses de chaque service : qu'il
ait une configuration logback, que ses lignes portent un `traceId`, et qu'elles
partent vers Loki. **Le 2026-11-14, six des onze services compilés n'en
remplissaient pas les trois.**

Trois formes du même défaut, par ordre de visibilité croissante :

| Forme | Modules concernés | Ce qu'on voyait |
|---|---|---|
| Aucun `logback-spring.xml` | config, notification, quotas, security, setting | une console parfaitement lisible, et rien dans Loki |
| Un fichier, mais aucun appender Loki | interface-service | idem, plus un fichier local qui grossit |
| Un appender Loki, mais aucun `traceId` | media-service | les lignes **arrivaient** dans Loki, et ne se recoupaient avec rien |

La troisième est la plus trompeuse : la chaîne d'expédition fonctionnait, et
c'est le *contenu* qui ne servait à rien. Une requête qui traverse la
passerelle, market, user et media laissait quatre journaux que rien ne reliait —
précisément quand plusieurs services sont en cause, c'est-à-dire quand on
cherche.

Un quatrième cas, dans interface-service : ses trois loggers de sécurité
portaient `additivity="false"`. Couper l'additivité coupe l'accès à **tous** les
appenders de la racine, donc au fichier commun et à Loki. Les journaux les plus
importants du module étaient les seuls à ne jamais quitter la machine.

### Les règles, et pourquoi

**L'appender Loki est borné à une liste blanche de profils** (`prod | staging |
observability`), jamais à une négation de `dev`. Les tests s'exécutent **sans
profil actif** : une exclusion de `dev` les laisserait expédier dans le vide, et
comme Loki n'est pas démarré en développement, chaque lot échouerait en
déversant une pile complète. Un démarrage normal ressemblerait à une panne.

**L'appender est défini à l'intérieur du bloc de profil**, et pas seulement sa
référence : loki4j démarre son fil d'expédition dès sa définition.

**La dépendance et le fichier vont ensemble.** Le fichier sans la dépendance
fait échouer le démarrage sur une classe d'appender introuvable. La dépendance
sans le fichier est du poids mort — et c'était le cas de config-service et
notification-service, qui portaient `loki4j` sans aucun logback.

**Aucune version de `loki4j` dans un POM de module** : elle est gérée par le POM
racine. Une version posée dans un module surcharge silencieusement celle du
parent.

**Pas de logger pour un composant absent.** user-service déclarait
`org.hibernate.SQL` en DEBUG et `BasicBinder` en TRACE alors qu'il est en
R2DBC : Hibernate n'est pas dans son arbre de dépendances. Ces lignes ne
réglaient rien, mais donnaient à croire que le SQL était journalisé. Le second
était en outre le pire à réactiver — `BasicBinder` en TRACE imprime les
**valeurs liées**, donc les adresses et les numéros, dans un fichier conservé
trente jours. L'équivalent R2DBC, `org.springframework.r2dbc`, reste à INFO pour
la même raison.

### Le traceId était là, la valeur ne venait jamais

Le plus coûteux des défauts de cette série, et le dernier trouvé. Après avoir
mis `%X{traceId}` dans les motifs des onze modules, la mesure a montré ceci :

```
auth           traceId= : 488      rempli : 8
gateway        traceId= : 2576     rempli : 0
market         traceId= : 427062   rempli : 0
user           traceId= : 231762   rempli : 0
quotas         traceId= : 91       rempli : 0
security       traceId= : 42       rempli : 0
setting        traceId= : 152      rempli : 0
```

Des centaines de milliers de `traceId=`, **tous vides**. Et les huit lignes
remplies d'auth-service venaient toutes de tâches **planifiées** — aucune d'une
requête HTTP.

C'est le pire des deux états possibles : un champ vide se lit comme « cette
requête n'avait pas de trace », pas comme « le traçage n'est pas branché ».

**Trois morceaux sont nécessaires, et aucun ne suffit.**

1. **Le pont, `io.micrometer:micrometer-tracing-bridge-brave`** — l'implémentation
   de `Tracer`. Cinq modules ne l'avaient pas du tout.

2. **L'auto-configuration, `org.springframework.boot:spring-boot-micrometer-tracing-brave`.**
   Spring Boot 4 a modularisé son auto-configuration : c'est cet artefact qui
   câble le `Tracer` et le décorateur de MDC. Le pont seul n'apporte que les
   classes. Un seul module le portait — auth-service, le seul à tracer quoi que
   ce soit. Et il **ne tire pas le pont de façon transitive** : l'avoir sans le
   pont laisse le champ vide tout autant.

3. **`spring.reactor.context-propagation: auto`.** Le MDC de logback est lié au
   **fil**. WebFlux exécute la chaîne sur les boucles d'événements de Netty et
   les ordonnanceurs de Reactor, pas sur le fil qui a reçu la requête : la
   valeur posée à l'entrée n'est plus là où la ligne est écrite. Le défaut est
   `limited`.

C'est le troisième point qui explique pourquoi le défaut a survécu :
auth-service **avait l'air** de tracer. Ses tâches planifiées tournent sur un fil
dédié, où le MDC survit. Il suffisait de regarder ses journaux au bon endroit
pour conclure que tout allait bien.

**Mesuré, un facteur à la fois, sur les services en marche :**

| Pont | Auto-configuration | `context-propagation` | Lignes tracées |
|---|---|---|---|
| oui | non | `auto` | **0** |
| oui | oui | `limited` | **0** (sauf tâches planifiées) |
| **non** | oui | `auto` | **0** |
| oui | oui | `auto` | **145 à 1124 selon le service** |

La troisième ligne est celle que j'avais manquée : après avoir posé
l'auto-configuration partout et la propriété dans la configuration partagée,
cinq modules restaient à zéro — ils n'avaient jamais eu le pont, et
l'auto-configuration ne l'apporte pas.

Le coût de `auto` est réel : chaque bascule d'ordonnanceur restaure et nettoie
le contexte. C'est le prix d'un journal qu'on peut recouper avec une trace —
sans quoi le mode d'emploi plus haut, « trouver le traceId dans les logs », ne
se pratique pas.

### Le garde-fou

`ModuleLivrableTest.chaqueServiceJournaliseVersLAgregateur` refuse tout module du
réacteur — sauf `platform-commons`, qui n'est pas un processus — dont la
journalisation manque l'une de ces conditions : pas de `logback-spring.xml`, pas
de `%X{traceId}` ni de `%X{spanId}` dans les motifs, pas d'appender Loki,
appender Loki hors de la liste blanche de profils, appender Loki sans la
dépendance `loki4j`, ou **motif avec `%X{traceId}` sans l'auto-configuration qui
le remplit**.

`ModuleLivrableTest.propagationDuContexteReactifActivee` exige
`spring.reactor.context-propagation: auto` dans la configuration partagée.

Les deux lisent le XML **dépouillé de ses commentaires** : ces fichiers
expliquent longuement pourquoi `traceId` et Loki sont là, et un garde-fou qui lit
le texte brut se contenterait de l'explication. Sept mutations tuées.
