# 📊 Configuration Observabilité Complète

## ✅ Stack Configurée

### Services Actifs
- **Prometheus** (9090) - Collecte métriques + Alertes
- **Loki** (3100) - Agrégation logs
- **Tempo** (3200, 9411) - Traces distribuées
- **AlertManager** (9093) - Notifications alertes
- **Grafana** (3000) - Interface visualisation

### Dashboards Grafana Disponibles

1. **📁 Media Service Dashboard** (`media-service-dashboard.json`)
   - Documents uploadés/approuvés/rejetés
   - Menaces sécurité détectées
   - Upload rate par type
   - Upload duration (P95/P50)
   - MongoDB connection pool
   - Logs temps réel

2. **🔐 Auth Service Dashboard** (`auth-service-dashboard.json`)
   - Logins réussis/échoués
   - Registrations
   - Account lockouts
   - OAuth2 logins par provider
   - JWT token generation time
   - OTP sent par channel
   - HikariCP connection pool

3. **🌐 Gateway Geolocation Dashboard** (existant)
4. **👤 User Service Dashboard** (existant)
5. **📊 Microservices Overview** (existant)

### Datasources Configurées

```yaml
- Prometheus (uid: prometheus) - http://prometheus:9090
- Loki (uid: loki) - http://loki:3100  
- Tempo (uid: tempo) - http://tempo:3200
```

### Alertes Prometheus

**Fichier:** `/observability/prometheus/alerts.yml`

**Groupes d'alertes:**
- Microservices (ServiceDown, HighCPU, HighMemory, HighErrorRate)
- Sécurité (JWT failures, Auth attempts)
- Infrastructure (Prometheus targets, Disk space)
- Géolocalisation (Blocked requests, Lookup errors)

### Configuration AlertManager

**Fichier:** `/observability/alertmanager/alertmanager.yml`

**Receivers configurés:**
- Email Security Team (abdaty11@gmail.com)
- Email Ops Team
- Email Database Team
- Slack (à configurer)

## 🚀 Démarrage

```bash
# 1. Démarrer stack observabilité
cd /Users/abdatytechnologie/Documents/Restart
docker-compose -f docker-compose-observability.yml up -d

# 2. Vérifier statut
docker-compose -f docker-compose-observability.yml ps

# 3. Accéder Grafana
# URL: http://localhost:3000
# User: admin / Pass: admin
```

## 📝 Notes Importantes

### Alertes Grafana vs Prometheus

**Décision:** Utiliser **Prometheus Alerting** au lieu de Grafana Alerting

**Raison:** 
- Grafana Alerting nécessite que les datasources soient chargées AVANT les règles d'alerte
- Cela crée une dépendance circulaire au démarrage
- Prometheus Alerting est plus stable et mature pour les alertes basées sur métriques

**Alertes Grafana désactivées:**
- `/observability/grafana/provisioning/alerting/rules.yml` → Supprimé
- Toutes les alertes sont dans Prometheus (`/observability/prometheus/alerts.yml`)

### Notifiers Email

**Fichier:** `/observability/grafana/provisioning/notifiers/email.yml`

Configuré pour envoyer notifications à: abdaty11@gmail.com

## 🔍 Vérification

```bash
# Health check Grafana
curl http://localhost:3000/api/health

# Vérifier datasources
curl -u admin:admin http://localhost:3000/api/datasources

# Vérifier dashboards
curl -u admin:admin http://localhost:3000/api/search

# Prometheus targets
curl http://localhost:9090/api/v1/targets

# Loki ready
curl http://localhost:3100/ready

# Tempo ready  
curl http://localhost:3200/ready
```

## 📊 Métriques Exposées

### Media Service (8087)
```promql
documents_uploaded_total
documents_approved_total
documents_rejected_total
documents_security_threats_total
documents_upload_duration_bucket
```

### Auth Service (8082)
```promql
auth_login_success_total
auth_login_failure_total
auth_registration_total
auth_account_lockout_total
auth_oauth2_login_total
auth_otp_sent_total
auth_token_generation_seconds_bucket
```

### Gateway Service (8081)
```promql
geo_requests_total
geo_requests_blocked_total
geo_lookup_errors_total
http_server_requests_seconds_count
```

## 🎯 Prochaines Étapes

1. ✅ Démarrer Grafana avec datasources uniquement
2. ⏳ Vérifier que Grafana démarre correctement
3. ⏳ Importer dashboards manuellement si nécessaire
4. ⏳ Configurer alertes Prometheus
5. ⏳ Tester notifications AlertManager
6. ⏳ Configurer Slack webhook (optionnel)

## 🐛 Troubleshooting

### Grafana ne démarre pas
```bash
# Voir logs
docker logs grafana --tail 100

# Vérifier provisioning
docker exec grafana ls -la /etc/grafana/provisioning/

# Restart
docker-compose -f docker-compose-observability.yml restart grafana
```

### Datasources non chargées
```bash
# Vérifier fichier
docker exec grafana cat /etc/grafana/provisioning/datasources/datasources.yml

# Vérifier permissions
docker exec grafana ls -la /etc/grafana/provisioning/datasources/
```

### Prometheus ne scrape pas
```bash
# Vérifier targets
curl http://localhost:9090/api/v1/targets | jq

# Vérifier config
docker exec prometheus cat /etc/prometheus/prometheus.yml
```
