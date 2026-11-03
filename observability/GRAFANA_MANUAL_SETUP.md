# 🎯 Configuration Manuelle Grafana - Guide Complet

## ✅ Étape 1 : Accéder à Grafana

1. Ouvrir Safari et aller sur : **http://localhost:3000**
2. Login :
   - **Username:** `admin`
   - **Password:** `admin`
3. (Optionnel) Changer le mot de passe ou cliquer "Skip"

---

## 📊 Étape 2 : Configurer les Datasources

### 2.1 Ajouter Prometheus

1. Menu latéral → **Connections** → **Data sources** → **Add data source**
2. Sélectionner **Prometheus**
3. Configuration :
   - **Name:** `Prometheus`
   - **URL:** `http://prometheus:9090`
   - **Access:** Server (default)
4. Cliquer **Save & Test** → Devrait afficher "Data source is working"

### 2.2 Ajouter Loki

1. **Add data source** → Sélectionner **Loki**
2. Configuration :
   - **Name:** `Loki`
   - **URL:** `http://loki:3100`
   - **Access:** Server (default)
3. Cliquer **Save & Test** → Devrait afficher "Data source successfully connected"

### 2.3 Ajouter Tempo

1. **Add data source** → Sélectionner **Tempo**
2. Configuration :
   - **Name:** `Tempo`
   - **URL:** `http://tempo:3200`
   - **Access:** Server (default)
3. Cliquer **Save & Test** → Devrait afficher "Data source is working"

---

## 📈 Étape 3 : Importer les Dashboards

### 3.1 Media Service Dashboard

1. Menu latéral → **Dashboards** → **New** → **Import**
2. Cliquer **Upload JSON file**
3. Sélectionner : `/Users/abdatytechnologie/Documents/Restart/observability/grafana/dashboards/media-service-dashboard.json`
4. Sélectionner datasource **Prometheus** pour tous les panneaux
5. Cliquer **Import**

### 3.2 Auth Service Dashboard

1. **Dashboards** → **New** → **Import**
2. Upload : `auth-service-dashboard.json`
3. Sélectionner datasource **Prometheus**
4. Cliquer **Import**

### 3.3 Gateway Geolocation Dashboard

1. **Dashboards** → **New** → **Import**
2. Upload : `gateway-geolocation-detailed.json`
3. Sélectionner datasource **Prometheus**
4. Cliquer **Import**

### 3.4 User Service Dashboard

1. **Dashboards** → **New** → **Import**
2. Upload : `user-service-dashboard.json`
3. Sélectionner datasource **Prometheus**
4. Cliquer **Import**

### 3.5 Microservices Overview

1. **Dashboards** → **New** → **Import**
2. Upload : `microservices-overview.json`
3. Sélectionner datasource **Prometheus**
4. Cliquer **Import**

---

## 🔔 Étape 4 : Configurer les Notifications (Contact Points)

### 4.1 Créer Contact Point Email

1. Menu latéral → **Alerting** → **Contact points** → **Add contact point**
2. Configuration :
   - **Name:** `Email Security Team`
   - **Integration:** Email
   - **Addresses:** `abdaty11@gmail.com`
   - **Subject:** `🚨 Grafana Alert: {{ .GroupLabels.alertname }}`
   - **Message:** (laisser par défaut ou personnaliser)
3. Cliquer **Test** pour vérifier
4. Cliquer **Save contact point**

### 4.2 Créer Contact Point Ops Team

1. **Add contact point**
2. Configuration :
   - **Name:** `Email Ops Team`
   - **Integration:** Email
   - **Addresses:** `abdaty11@gmail.com`
   - **Subject:** `⚠️ Grafana Alert: {{ .GroupLabels.alertname }}`
3. Cliquer **Save contact point**

### 4.3 (Optionnel) Configurer Slack

1. **Add contact point**
2. Configuration :
   - **Name:** `Slack Alerts`
   - **Integration:** Slack
   - **Webhook URL:** (votre webhook Slack)
   - **Channel:** `#alerts`
3. Cliquer **Save contact point**

---

## ⚠️ Étape 5 : Configurer les Alertes Grafana

### 5.1 Créer une Notification Policy

1. Menu latéral → **Alerting** → **Notification policies**
2. Cliquer **Edit** sur la politique par défaut
3. Configuration :
   - **Default contact point:** `Email Security Team`
   - **Group by:** `alertname`, `grafana_folder`
   - **Timing options:**
     - Group wait: `10s`
     - Group interval: `5m`
     - Repeat interval: `12h`
4. Cliquer **Save**

### 5.2 Créer des Alert Rules (Exemples)

#### Alert 1 : Service Down

1. **Alerting** → **Alert rules** → **New alert rule**
2. Configuration :
   - **Alert name:** `Service Down`
   - **Folder:** `Microservices`
   - **Query A:**
     - Datasource: `Prometheus`
     - Query: `up == 0`
   - **Condition:** `WHEN last() OF A IS BELOW 1`
   - **Evaluate every:** `1m`
   - **For:** `1m`
   - **Labels:**
     - `severity`: `critical`
     - `team`: `ops`
   - **Annotations:**
     - `summary`: `Service {{ $labels.job }} is down`
     - `description`: `{{ $labels.job }} on {{ $labels.instance }} has been down for more than 1 minute`
3. Cliquer **Save rule and exit**

#### Alert 2 : High CPU Usage

1. **New alert rule**
2. Configuration :
   - **Alert name:** `High CPU Usage`
   - **Query A:**
     - Datasource: `Prometheus`
     - Query: `process_cpu_usage > 0.8`
   - **Condition:** `WHEN last() OF A IS ABOVE 0.8`
   - **Evaluate every:** `1m`
   - **For:** `5m`
   - **Labels:**
     - `severity`: `warning`
   - **Annotations:**
     - `summary`: `High CPU on {{ $labels.application }}`
3. Cliquer **Save rule and exit**

#### Alert 3 : High Memory Usage

1. **New alert rule**
2. Configuration :
   - **Alert name:** `High Memory Usage`
   - **Query A:**
     - Datasource: `Prometheus`
     - Query: `(jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}) > 0.85`
   - **Condition:** `WHEN last() OF A IS ABOVE 0.85`
   - **Evaluate every:** `1m`
   - **For:** `5m`
3. Cliquer **Save rule and exit**

---

## 🔥 Étape 6 : Vérifier les Alertes Prometheus

Les alertes Prometheus sont déjà configurées dans `/observability/prometheus/alerts.yml` et sont actives.

### Vérifier les Alertes Prometheus

1. Ouvrir : **http://localhost:9090/alerts**
2. Vous devriez voir toutes les alertes configurées :
   - ServiceDown
   - HighCPUUsage
   - HighMemoryUsage
   - HighErrorRate
   - SlowResponseTime
   - DatabaseConnectionPoolExhaustion
   - HighJWTValidationFailures
   - SuspiciousAuthenticationAttempts
   - HighGeoBlockedRequests
   - GeoLocationLookupErrors

### Vérifier AlertManager

1. Ouvrir : **http://localhost:9093**
2. Vérifier que AlertManager est connecté à Prometheus
3. Les notifications seront envoyées selon la configuration dans `/observability/alertmanager/alertmanager.yml`

---

## ✅ Étape 7 : Tests de Vérification

### Test 1 : Vérifier les Datasources

```bash
# Via API Grafana
curl -s -u admin:admin http://localhost:3000/api/datasources | jq
```

### Test 2 : Vérifier les Dashboards

```bash
# Lister les dashboards
curl -s -u admin:admin http://localhost:3000/api/search | jq
```

### Test 3 : Tester une Alerte

```bash
# Arrêter un service pour déclencher l'alerte ServiceDown
docker-compose -f docker-compose-observability.yml stop loki

# Attendre 2 minutes puis vérifier dans Grafana Alerting
# Redémarrer le service
docker-compose -f docker-compose-observability.yml start loki
```

### Test 4 : Vérifier les Métriques

1. Aller dans **Explore** (menu latéral)
2. Sélectionner datasource **Prometheus**
3. Tester des requêtes :
   ```promql
   up
   process_cpu_usage
   http_server_requests_seconds_count
   documents_uploaded_total
   auth_login_success_total
   ```

### Test 5 : Vérifier les Logs

1. **Explore** → Datasource **Loki**
2. Tester des requêtes :
   ```logql
   {app="media-service"}
   {app="auth-service"} |= "ERROR"
   {app=~".*-service"} | json | level="INFO"
   ```

---

## 📋 Checklist Finale

- [ ] Grafana accessible sur http://localhost:3000
- [ ] 3 Datasources configurées (Prometheus, Loki, Tempo)
- [ ] 5 Dashboards importés
- [ ] Contact points email configurés
- [ ] Notification policy configurée
- [ ] Au moins 3 alert rules créées dans Grafana
- [ ] Alertes Prometheus visibles sur http://localhost:9090/alerts
- [ ] AlertManager accessible sur http://localhost:9093
- [ ] Tests de métriques réussis
- [ ] Tests de logs réussis

---

## 🆘 Troubleshooting

### Datasource ne se connecte pas

```bash
# Vérifier que les services sont UP
docker ps

# Vérifier les logs
docker logs prometheus
docker logs loki
docker logs tempo
```

### Dashboards ne chargent pas les données

1. Vérifier que les datasources sont configurées
2. Vérifier que les microservices exposent `/actuator/prometheus`
3. Vérifier les targets Prometheus : http://localhost:9090/targets

### Alertes ne se déclenchent pas

1. Vérifier que les règles sont actives dans Prometheus
2. Vérifier la configuration AlertManager
3. Vérifier les logs : `docker logs alertmanager`

---

## 🎉 Configuration Terminée !

Votre stack d'observabilité complète est maintenant opérationnelle :

- ✅ Grafana avec dashboards personnalisés
- ✅ Prometheus avec alertes configurées
- ✅ Loki pour les logs centralisés
- ✅ Tempo pour les traces distribuées
- ✅ AlertManager pour les notifications
- ✅ Notifications email configurées

**Prochaines étapes recommandées :**
1. Démarrer vos microservices (auth-service, media-service, user-service, gateway-service)
2. Générer du trafic pour voir les métriques
3. Surveiller les dashboards en temps réel
4. Tester les alertes en simulant des pannes
