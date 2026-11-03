# 🌍 Observabilité Géolocalisation - Guide Complet

## 📊 Vue d'ensemble

L'observabilité de la géolocalisation IP a été intégrée dans votre stack Grafana/Prometheus existante avec:
- ✅ **Métriques Prometheus** - Collecte automatique
- ✅ **Dashboard Grafana** - Visualisation en temps réel
- ✅ **Alertes Prometheus** - Détection d'anomalies
- ✅ **Intégration complète** - Prêt pour production

---

## 📈 Métriques Disponibles

### **Métriques Principales**

| Métrique | Type | Description |
|----------|------|-------------|
| `geo_requests_total` | Counter | Total des requêtes de géolocalisation |
| `geo_blocked_requests_total` | Counter | Total des requêtes bloquées par pays |
| `geo_lookup_errors_total` | Counter | Total des erreurs de lookup GeoIP2 |
| `geo_blocked_countries_count` | Gauge | Nombre de pays actuellement bloqués |

### **Métriques par Pays**

| Métrique | Type | Description |
|----------|------|-------------|
| `geo_requests_by_country_total{country="XX"}` | Counter | Requêtes par code pays |
| `geo_blocked_by_country_total{country="XX"}` | Counter | Blocages par code pays |

### **Exemples de Requêtes PromQL**

```promql
# Taux de requêtes géolocalisées par minute
rate(geo_requests_total[1m]) * 60

# Taux de blocage
rate(geo_blocked_requests_total[5m]) / rate(geo_requests_total[5m])

# Top 10 pays par requêtes
topk(10, sum by (country) (rate(geo_requests_by_country_total[5m])))

# Top 10 pays bloqués
topk(10, sum by (country) (geo_blocked_by_country_total))

# Taux d'erreurs de lookup
rate(geo_lookup_errors_total[5m])
```

---

## 📊 Dashboard Grafana

### **Emplacement**
```
/observability/grafana/dashboards/gateway-geolocation.json
```

### **Panneaux Disponibles**

1. **🌍 Pays Bloqués** (Stat)
   - Nombre total de pays bloqués
   - Seuil: Rouge si > 0

2. **📊 Requêtes Géo/min** (Stat)
   - Taux de requêtes géolocalisées par minute
   - Graphique d'évolution

3. **🚫 Requêtes Bloquées/min** (Stat)
   - Taux de blocage par minute
   - Alerte visuelle si > 0

4. **⚠️ Erreurs Lookup/min** (Stat)
   - Erreurs de résolution GeoIP2
   - Jaune si > 1, Rouge si > 10

5. **📈 Requêtes Géolocalisation (Rate)** (TimeSeries)
   - Évolution temporelle des requêtes totales vs bloquées
   - Légende avec moyenne et max

6. **🌍 Top 10 Pays (Requêtes)** (PieChart)
   - Distribution des requêtes par pays
   - Affichage en pourcentage

7. **🚫 Pays Bloqués (Top 20)** (Table)
   - Liste des pays avec le plus de blocages
   - Triable par nombre de requêtes

8. **🚫 Blocages par Pays (Dernière Heure)** (TimeSeries)
   - Évolution des blocages par pays
   - Stacked bar chart

### **Accès au Dashboard**

```bash
# URL Grafana
http://localhost:3000

# Dashboard
→ Dashboards
→ Gateway - Géolocalisation IP
```

**UID**: `gateway-geolocation`  
**Tags**: `gateway`, `geolocation`, `security`  
**Refresh**: 10 secondes

---

## 🚨 Alertes Prometheus

### **Alertes Configurées**

#### **1. HighGeoBlockedRequests**
```yaml
Condition: rate(geo_blocked_requests_total[5m]) > 1
Duration: 2 minutes
Severity: warning
```
**Déclenchement**: Plus de 1 requête/seconde bloquée  
**Action**: Vérifier si attaque en cours ou mauvaise configuration

#### **2. GeoLocationLookupErrors**
```yaml
Condition: rate(geo_lookup_errors_total[5m]) > 0.1
Duration: 2 minutes
Severity: warning
```
**Déclenchement**: Erreurs de lookup GeoIP2  
**Action**: Vérifier base de données GeoIP2 et logs

#### **3. SuspiciousCountryActivity**
```yaml
Condition: rate(geo_blocked_by_country_total[10m]) > 10
Duration: 5 minutes
Severity: critical
```
**Déclenchement**: Plus de 10 requêtes bloquées d'un pays en 10 min  
**Action**: Analyser logs, possibilité d'attaque ciblée

#### **4. TooManyBlockedCountries**
```yaml
Condition: geo_blocked_countries_count > 50
Duration: 5 minutes
Severity: warning
```
**Déclenchement**: Plus de 50 pays bloqués  
**Action**: Vérifier configuration, risque de bloquer trafic légitime

### **Vérifier les Alertes**

```bash
# Prometheus Alerts UI
http://localhost:9090/alerts

# Via API
curl http://localhost:9090/api/v1/alerts | jq '.data.alerts[] | select(.labels.category=="geolocation")'
```

---

## 🚀 Démarrage

### **1. Démarrer la Stack Observabilité**

```bash
cd /Users/abdatytechnologie/Documents/Restart/observability

# Démarrer tous les services
docker-compose up -d

# Vérifier les services
docker-compose ps
```

**Services démarrés**:
- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000
- AlertManager: http://localhost:9093
- Loki: http://localhost:3100
- Tempo: http://localhost:3200

### **2. Vérifier les Métriques**

```bash
# Vérifier que Gateway expose les métriques
curl http://localhost:8081/actuator/prometheus | grep geo_

# Résultat attendu:
# geo_requests_total 42.0
# geo_blocked_requests_total 5.0
# geo_lookup_errors_total 0.0
# geo_blocked_countries_count 3.0
# geo_requests_by_country_total{country="US"} 20.0
# geo_blocked_by_country_total{country="CN"} 5.0
```

### **3. Accéder au Dashboard**

```bash
# Ouvrir Grafana
open http://localhost:3000

# Login (par défaut)
Username: admin
Password: admin

# Naviguer vers le dashboard
Dashboards → Gateway - Géolocalisation IP
```

---

## 📊 Exemples de Visualisation

### **Scénario 1: Trafic Normal**
```
 Pays Bloqués: 3
 Requêtes Géo/min: 120
 Requêtes Bloquées/min: 2
 Erreurs Lookup/min: 0
```
**Interprétation**: Système fonctionne normalement

### **Scénario 2: Attaque en Cours**
```
🌍 Pays Bloqués: 5
📊 Requêtes Géo/min: 1500 ⚠️
🚫 Requêtes Bloquées/min: 800 🚨
⚠️ Erreurs Lookup/min: 0
```
**Interprétation**: Attaque massive d'un pays bloqué
**Action**: Vérifier logs, analyser pays source

### **Scénario 3: Problème Base GeoIP2**
```
🌍 Pays Bloqués: 3
📊 Requêtes Géo/min: 100
🚫 Requêtes Bloquées/min: 0
⚠️ Erreurs Lookup/min: 50 🚨
```
**Interprétation**: Base de données GeoIP2 corrompue ou manquante
**Action**: Vérifier `/opt/geoip/GeoLite2-Country.mmdb`

---

## 🔍 Analyse et Debugging

### **Requêtes PromQL Utiles**

```promql
# Taux de blocage global
(rate(geo_blocked_requests_total[5m]) / rate(geo_requests_total[5m])) * 100

# Pays générant le plus de trafic
topk(5, sum by (country) (increase(geo_requests_by_country_total[1h])))

# Pays générant le plus de blocages
topk(5, sum by (country) (increase(geo_blocked_by_country_total[1h])))

# Évolution du nombre de pays bloqués
geo_blocked_countries_count

# Taux d'erreur de lookup
(rate(geo_lookup_errors_total[5m]) / rate(geo_requests_total[5m])) * 100
```

### **Logs à Surveiller**

```bash
# Logs Gateway avec géolocalisation
docker logs -f gateway-service | grep "🌍\|🚫"

# Exemples de logs:
# 🌍 IP 1.2.3.4 resolved to country: CN
# 🚫 Blocked request from country: CN (IP: 1.2.3.4) on path: /api/v1/auth/login
# ✅ GeoLocation check passed for IP: 8.8.8.8
```

---

## 🎯 Cas d'Usage

### **1. Détecter une Attaque DDoS**

**Indicateurs**:
- `geo_blocked_requests_total` augmente rapidement
- Un ou plusieurs pays dominent dans `geo_blocked_by_country_total`
- Alerte `SuspiciousCountryActivity` déclenchée

**Actions**:
```bash
# Identifier le pays source
curl -s http://localhost:9090/api/v1/query?query='topk(1, rate(geo_blocked_by_country_total[5m]))' | jq

# Vérifier les IPs
redis-cli SMEMBERS ip_blacklist

# Bloquer temporairement plus de pays si nécessaire
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/api/v1/admin/geo/block?country=XX"
```

### **2. Analyser le Trafic Légitime**

**Indicateurs**:
- `geo_requests_by_country_total` montre la distribution géographique
- Faible taux de `geo_blocked_requests_total`

**Actions**:
```bash
# Top 10 pays légitimes
curl -s http://localhost:9090/api/v1/query?query='topk(10, geo_requests_by_country_total)' | jq

# Exporter pour analyse
curl -s http://localhost:9090/api/v1/query?query='geo_requests_by_country_total' | \
  jq -r '.data.result[] | "\(.metric.country): \(.value[1])"' > country_stats.txt
```

### **3. Optimiser la Configuration**

**Indicateurs**:
- `geo_blocked_countries_count` trop élevé
- Alerte `TooManyBlockedCountries` déclenchée
- Trafic légitime potentiellement impacté

**Actions**:
```bash
# Lister les pays bloqués
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/v1/admin/geo/blocked-countries

# Analyser les blocages par pays
curl -s http://localhost:9090/api/v1/query?query='geo_blocked_by_country_total' | jq

# Débloquer pays avec peu de blocages
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8081/api/v1/admin/geo/unblock?country=XX"
```

---

## 📚 Intégration avec Autres Dashboards

### **Dashboard Microservices Overview**

Le dashboard géolocalisation complète le dashboard existant:
- **Microservices Overview**: Vue globale de tous les services
- **Gateway Géolocalisation**: Focus sur la sécurité géographique
- **Logs Explorer**: Analyse détaillée des logs

**Navigation**:
```
Grafana → Dashboards
├── Microservices Overview (vue globale)
├── Gateway - Géolocalisation IP (sécurité géo)
└── Logs Explorer (debugging)
```

---

## 🔧 Configuration Avancée

### **Ajuster les Seuils d'Alerte**

Éditer `/observability/prometheus/alerts.yml`:

```yaml
# Exemple: Augmenter le seuil de HighGeoBlockedRequests
- alert: HighGeoBlockedRequests
  expr: rate(geo_blocked_requests_total[5m]) > 5  # Changé de 1 à 5
  for: 5m  # Changé de 2m à 5m
```

Recharger Prometheus:
```bash
docker-compose restart prometheus
```

### **Ajouter des Panneaux Personnalisés**

Éditer `/observability/grafana/dashboards/gateway-geolocation.json`:

```json
{
  "title": "Mon Panneau Custom",
  "targets": [
    {
      "expr": "votre_requete_promql",
      "refId": "A"
    }
  ]
}
```

---

## ✅ Checklist de Vérification

### **Après Démarrage**
- [ ] Prometheus scrape Gateway (`http://localhost:9090/targets`)
- [ ] Métriques `geo_*` visibles dans Prometheus
- [ ] Dashboard "Gateway - Géolocalisation IP" accessible
- [ ] Alertes géolocalisation chargées (`http://localhost:9090/alerts`)
- [ ] Grafana affiche les données en temps réel

### **En Production**
- [ ] Alertes configurées dans AlertManager
- [ ] Notifications Slack/Email configurées
- [ ] Retention Prometheus configurée (15 jours minimum)
- [ ] Dashboards sauvegardés et versionnés
- [ ] Runbooks créés pour chaque alerte

---

## 🚨 Troubleshooting

### **Problème**: Métriques non visibles

**Solution**:
```bash
# Vérifier que Gateway expose les métriques
curl http://localhost:8081/actuator/prometheus | grep geo_

# Vérifier que Prometheus scrape Gateway
curl http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | select(.labels.job=="gateway-service")'

# Vérifier les logs Prometheus
docker logs prometheus
```

### **Problème**: Dashboard vide

**Solution**:
```bash
# Vérifier la datasource Prometheus dans Grafana
curl -u admin:admin http://localhost:3000/api/datasources

# Tester une requête depuis Grafana
# Explore → Prometheus → Metrics browser → geo_requests_total
```

### **Problème**: Alertes non déclenchées

**Solution**:
```bash
# Vérifier les règles chargées
curl http://localhost:9090/api/v1/rules | jq '.data.groups[] | select(.name=="geolocation_alerts")'

# Forcer une évaluation
curl -X POST http://localhost:9090/api/v1/admin/tsdb/reload
```

---

## 📊 Métriques de Performance

### **Overhead de la Géolocalisation**

| Métrique | Valeur Typique |
|----------|----------------|
| Latence lookup GeoIP2 | < 1ms |
| Mémoire base GeoIP2 | ~70MB |
| CPU overhead | < 1% |
| Impact sur throughput | Négligeable |

### **Monitoring de l'Impact**

```promql
# Latence moyenne des requêtes avec géolocalisation
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{uri=~"/api/v1/.*"}[5m]))

# Comparer avec/sans géolocalisation
# (nécessite métriques avant/après activation)
```

---

## 🎓 Bonnes Pratiques

1. **Monitoring Proactif**
   - Surveiller le dashboard quotidiennement
   - Analyser les tendances hebdomadaires
   - Ajuster les pays bloqués selon les patterns

2. **Alertes**
   - Ne pas ignorer les alertes warning
   - Documenter chaque incident
   - Créer des runbooks pour chaque alerte

3. **Optimisation**
   - Bloquer uniquement les pays nécessaires
   - Mettre à jour la base GeoIP2 mensuellement
   - Analyser les faux positifs

4. **Documentation**
   - Documenter chaque changement de configuration
   - Maintenir une liste des pays bloqués avec justification
   - Partager les insights avec l'équipe

---

## 📚 Ressources

- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000
- **AlertManager**: http://localhost:9093
- **Gateway Metrics**: http://localhost:8081/actuator/prometheus
- **Gateway Admin API**: http://localhost:8081/swagger-ui.html

---

**Version**: 1.0.0  
**Date**: 2026-01-26  
**Statut**: ✅ Production-Ready  
**Auteur**: Gateway Service Team
