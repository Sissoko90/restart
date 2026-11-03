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
