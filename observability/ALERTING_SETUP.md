# 🔔 Configuration des Alertes et Notifications

## ✅ Alertes Prometheus (Déjà Actives)

Les alertes Prometheus sont **déjà configurées et actives** dans `/observability/prometheus/alerts.yml`.

### Vérifier les Alertes Prometheus

**URL:** http://localhost:9090/alerts

**Alertes configurées:**
- **ServiceDown** - Service non disponible
- **HighCPUUsage** - CPU > 80%
- **HighMemoryUsage** - Mémoire > 85%
- **HighErrorRate** - Taux d'erreur > 5%
- **SlowResponseTime** - Temps de réponse > 1s
- **DatabaseConnectionPoolExhaustion** - Pool DB épuisé
- **HighJWTValidationFailures** - Échecs JWT
- **SuspiciousAuthenticationAttempts** - Tentatives suspectes
- **HighGeoBlockedRequests** - Requêtes bloquées
- **GeoLocationLookupErrors** - Erreurs géolocalisation

### AlertManager (Notifications Email)

**URL:** http://localhost:9093

**Configuration:** `/observability/alertmanager/alertmanager.yml`

**Receivers configurés:**
- **critical-alerts** → Email: abdaty11@gmail.com + Slack
- **security-team** → Email: abdaty11@gmail.com
- **ops-team** → Email: abdaty11@gmail.com
- **database-team** → Email: abdaty11@gmail.com

**SMTP:** smtp.gmail.com:587 (déjà configuré)

---

## 🎯 Configuration Grafana Alerting (Optionnel)

### Étape 1 : Créer Contact Points

1. Ouvrir Grafana: http://localhost:3000
2. Menu → **Alerting** → **Contact points**
3. Cliquer **Add contact point**

#### Contact Point: Email Security

```yaml
Name: Email Security Team
Integration: Email
Addresses: abdaty11@gmail.com
Subject: 🚨 Grafana Alert: {{ .GroupLabels.alertname }}
```

#### Contact Point: Email Ops

```yaml
Name: Email Ops Team
Integration: Email
Addresses: abdaty11@gmail.com
Subject: ⚠️ Grafana Alert: {{ .GroupLabels.alertname }}
```

### Étape 2 : Configurer Notification Policy

1. **Alerting** → **Notification policies**
2. Cliquer **Edit** sur la politique par défaut
3. Configuration:
   - **Default contact point:** Email Security Team
   - **Group by:** alertname, grafana_folder
   - **Group wait:** 10s
   - **Group interval:** 5m
   - **Repeat interval:** 12h

### Étape 3 : Créer Alert Rules Grafana

#### Alert 1: Service Down (Grafana)

```yaml
Alert name: Grafana - Service Down
Folder: Microservices
Query A:
  Datasource: Prometheus
  Query: up{job=~".*-service"} == 0
Condition: WHEN last() OF A IS BELOW 1
Evaluate every: 1m
For: 1m
Labels:
  severity: critical
  team: ops
Annotations:
  summary: Service {{ $labels.job }} is down
  description: {{ $labels.job }} on {{ $labels.instance }} has been down for more than 1 minute
```

#### Alert 2: High Memory Usage

```yaml
Alert name: High Memory Usage
Query A:
  Datasource: Prometheus
  Query: (jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}) > 0.85
Condition: WHEN last() OF A IS ABOVE 0.85
Evaluate every: 1m
For: 5m
Labels:
  severity: warning
Annotations:
  summary: High memory on {{ $labels.application }}
```

#### Alert 3: High Error Rate

```yaml
Alert name: High Error Rate
Query A:
  Datasource: Prometheus
  Query: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.05
Condition: WHEN last() OF A IS ABOVE 0.05
Evaluate every: 1m
For: 5m
Labels:
  severity: warning
  category: performance
```

#### Alert 4: Authentication Failures

```yaml
Alert name: High Authentication Failures
Query A:
  Datasource: Prometheus
  Query: rate(auth_login_failure_total[5m]) > 10
Condition: WHEN last() OF A IS ABOVE 10
Evaluate every: 1m
For: 2m
Labels:
  severity: critical
  category: security
```

#### Alert 5: Document Upload Failures

```yaml
Alert name: High Document Upload Failures
Query A:
  Datasource: Prometheus
  Query: rate(documents_rejected_total[5m]) > 5
Condition: WHEN last() OF A IS ABOVE 5
Evaluate every: 1m
For: 5m
Labels:
  severity: warning
```

---

## 🧪 Tester les Alertes

### Test 1: Arrêter un Service

```bash
# Arrêter Loki pour déclencher ServiceDown
docker-compose -f docker-compose-observability.yml stop loki

# Attendre 2 minutes
# Vérifier dans Grafana Alerting et Prometheus

# Redémarrer
docker-compose -f docker-compose-observability.yml start loki
```

### Test 2: Vérifier Email

1. Vérifier la boîte email: abdaty11@gmail.com
2. Chercher les emails d'AlertManager
3. Sujet: "🚨 CRITICAL ALERT: ServiceDown"

### Test 3: Vérifier AlertManager UI

1. Ouvrir: http://localhost:9093
2. Onglet **Alerts** → Voir les alertes actives
3. Onglet **Silences** → Créer des silences si nécessaire

---

## 📊 Dashboard Alerting dans Grafana

### Créer un Dashboard "Alerting Overview"

1. **Dashboards** → **New** → **New Dashboard**
2. Ajouter des panels:

#### Panel 1: Active Alerts

```promql
ALERTS{alertstate="firing"}
```

#### Panel 2: Alert Rate

```promql
rate(ALERTS[5m])
```

#### Panel 3: Alerts by Severity

```promql
sum by (severity) (ALERTS{alertstate="firing"})
```

---

## ✅ Checklist Configuration Alerting

- [x] Prometheus alerts configurées (15 alertes)
- [x] AlertManager configuré avec SMTP
- [x] Receivers email configurés (4 teams)
- [ ] Contact points Grafana créés
- [ ] Notification policy Grafana configurée
- [ ] Alert rules Grafana créées (5 exemples)
- [ ] Tests d'alertes effectués
- [ ] Emails de notification reçus

---

## 🎯 Recommandations

### Utiliser Prometheus Alerting (Recommandé)

**Avantages:**
- Plus stable et mature
- Déjà configuré et fonctionnel
- Intégration native avec AlertManager
- Moins de charge sur Grafana

**Utilisation:**
- Alertes basées sur métriques → **Prometheus**
- Alertes basées sur logs → **Grafana** (avec Loki)
- Alertes complexes multi-datasources → **Grafana**

### Configuration Slack (Optionnel)

1. Créer un Incoming Webhook Slack
2. Ajouter dans AlertManager:

```yaml
- name: slack-alerts
  slack_configs:
    - api_url: 'https://hooks.slack.com/services/YOUR/WEBHOOK/URL'
      channel: '#alerts'
      title: '🚨 {{ .GroupLabels.alertname }}'
      text: '{{ range .Alerts }}{{ .Annotations.description }}{{ end }}'
```

---

## 📝 Documentation Complète

- ✅ **GRAFANA_MANUAL_SETUP.md** - Configuration manuelle Grafana
- ✅ **OBSERVABILITY_SETUP.md** - Vue d'ensemble observabilité
- ✅ **ALERTING_SETUP.md** - Configuration alertes (ce fichier)

**Tous les services sont opérationnels et les alertes sont actives !** 🎉
