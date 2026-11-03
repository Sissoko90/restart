# 📁 Media Service - Microservice de Gestion de Documents

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![WebFlux](https://img.shields.io/badge/WebFlux-Reactive-blue.svg)](https://docs.spring.io/spring-framework/docs/current/reference/html/web-reactive.html)
[![MongoDB](https://img.shields.io/badge/MongoDB-7.0-green.svg)](https://www.mongodb.com/)
[![MinIO](https://img.shields.io/badge/MinIO-S3%20Compatible-orange.svg)](https://min.io/)

---

## 🎯 Vue d'Ensemble

Service réactif de gestion de documents avec validation de sécurité, analyse de qualité, stockage distribué et système de métriques complet dans une **architecture microservices**.

**Port**: 8087  
**Architecture**: Microservices avec Spring Cloud  
**Stack**: Spring Boot 4.0.1, WebFlux (Netty), MongoDB Reactive, MinIO S3, Redis, Kafka

---

## 🏗️ Architecture Microservices

### Vue d'ensemble

```
┌─────────────────────────────────────────────────────────────┐
│                  GATEWAY (Port 8081)                         │
│  - Rate Limiting Global                                      │
│  - JWT Validation                                            │
│  - Request Routing                                           │
└────────────────┬────────────────────────────────────────────┘
                 │
    ┌────────────┼────────────┐
    │            │            │
    ▼            ▼            ▼
┌─────────┐ ┌─────────┐ ┌─────────────┐
│  Auth   │ │  User   │ │   Media     │
│ Service │ │ Service │ │  Service    │
│  :8082  │ │  :8083  │ │   :8087     │
└─────────┘ └─────────┘ └─────────────┘
                              │
        ┌─────────────────────┼─────────────────┐
        │                     │                 │
        ▼                     ▼                 ▼
   ┌────────┐           ┌─────────┐      ┌─────────┐
   │ MinIO  │           │  Redis  │      │  Kafka  │
   │ :9000  │           │  :6379  │      │  :9092  │
   └────────┘           └─────────┘      └─────────┘
```

### Services de l'écosystème

| Service | Port | Base de données | Rôle |
|---------|------|-----------------|------|
| **Gateway** | 8081 | - | Routage, Rate limiting, Load balancing |
| **Auth Service** | 8082 | PostgreSQL (R2DBC) | Authentification, JWT, OAuth2 |
| **User Service** | 8083 | PostgreSQL (R2DBC) | Gestion utilisateurs, profils |
| **Media Service** | 8087 | MongoDB Reactive | Gestion documents, stockage |
| **Config Server** | 8888 | - | Configuration centralisée |
| **Consul** | 8500 | - | Service discovery |

### Infrastructure partagée

- **MongoDB** - Base NoSQL réactive pour media-service
- **MinIO** - Stockage S3-compatible pour fichiers
- **Redis** - Cache distribué + Rate limiting
- **Kafka** - Messaging asynchrone inter-services
- **Prometheus** - Métriques
- **Grafana** - Dashboards
- **Loki** - Logs centralisés
- **Tempo/Zipkin** - Traces distribuées

---

## ✨ Fonctionnalités

### 📤 Gestion de Documents
- ✅ Upload réactif avec streaming (pas de buffering mémoire)
- ✅ Validation multi-niveaux (format, taille, MIME type)
- ✅ Détection de malware (heuristique + patterns)
- ✅ Analyse de qualité (résolution, contraste, netteté)
- ✅ Génération automatique de thumbnails
- ✅ Watermarking configurable
- ✅ Compression d'images intelligente
- ✅ Chiffrement AES-256-GCM
- ✅ Stockage distribué MinIO avec fallback local

### 🔒 Sécurité
- ✅ Authentification JWT avec RSA public key
- ✅ Validation Gateway secret
- ✅ Authorization basée sur les rôles (RBAC)
- ✅ Token blacklist (Redis)
- ✅ Rate limiting distribué (Redis)
- ✅ Géolocalisation des requêtes (MaxMind GeoIP2)
- ✅ Audit logging complet

### 🛡️ Résilience
- ✅ Circuit Breaker (MinIO, Malware, Quality)
- ✅ Retry avec backoff exponentiel
- ✅ Bulkhead pour isolation
- ✅ Fallback storage local chiffré
- ✅ Timeout configurables
- ✅ Health checks custom (MongoDB, Redis, MinIO)

### 📊 Observabilité
- ✅ Métriques Prometheus (business + techniques)
- ✅ Distributed tracing Zipkin
- ✅ Logs centralisés Loki
- ✅ Health indicators réactifs
- ✅ Actuator endpoints

---

## �� Prérequis

- **Java 17+** (JDK Eclipse Temurin recommandé)
- **Maven 3.9+**
- **Docker & Docker Compose** (pour infrastructure)

---

## 🚀 Installation

### 1. Configuration des variables d'environnement

Le projet utilise un fichier `.env` centralisé à la racine du projet parent:

```bash
# Localisation du fichier
/Users/abdatytechnologie/Documents/Restart/.env
```

**Variables critiques pour media-service**:
```bash
# MongoDB
MONGODB_URI=mongodb://localhost:27017/media_service_db

# MinIO
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin

# Encryption
ENCRYPTION_MASTER_KEY=your-32-chars-aes256-key-change-in-production

# Gateway
GATEWAY_SECRET_KEY=your-gateway-secret-key-min-32-chars

# JWT
JWT_PUBLIC_KEY_PATH=classpath:keys/public_key.pem

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# Kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

### 2. Démarrer l'infrastructure

```bash
# Depuis la racine du projet
cd /Users/abdatytechnologie/Documents/Restart

# Démarrer services de base
docker-compose -f docker-compose-minimal.yml up -d

# Démarrer observabilité (optionnel)
docker-compose -f docker-compose-observability.yml up -d

# Vérifier
docker ps
```

**Services démarrés**:
- MongoDB (27017)
- Redis (6379)
- MinIO (9000, 9001)
- Kafka (9092)
- Zookeeper (2181)
- Consul (8500)

### 3. Compiler et lancer

```bash
cd media-service

# Compiler
mvn clean install -DskipTests

# Lancer
mvn spring-boot:run

# Ou avec profil
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

### 4. Vérifier le démarrage

```bash
# Health check
curl http://localhost:8087/actuator/health

# Swagger UI
open http://localhost:8087/swagger-ui.html

# Prometheus metrics
curl http://localhost:8087/actuator/prometheus
```

---

## 🔌 Endpoints API

### Endpoints Utilisateur (`/media`)

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| POST | `/media/upload` | Upload un document |
| GET | `/media/download/{id}` | Télécharger un document |
| GET | `/media/{id}` | Détails d'un document |
| GET | `/media/user/documents` | Liste des documents utilisateur |
| GET | `/media/user/status` | Statut de complétion |
| DELETE | `/media/{id}` | Supprimer un document |

### Endpoints Admin (`/admin/documents`)

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| POST | `/admin/documents/{id}/approve` | Approuver un document |
| POST | `/admin/documents/{id}/reject` | Rejeter un document |
| GET | `/admin/documents/pending` | Documents en attente |
| GET | `/admin/documents/all` | Tous les documents |
| GET | `/admin/documents/stats` | Statistiques |

**Documentation complète**: Voir `TEST_CURL_COMMANDS.md`

---

## 🧪 Tests

### Structure

```
src/test/java/
└── com/abdatytch/media_service/
    ├── service/
    │   └── DocumentServiceTest.java
    └── integration/
        └── (TestContainers à venir)
```

### Exécuter les tests

```bash
# Tous les tests
mvn clean test

# Test spécifique
mvn test -Dtest=DocumentServiceTest

# Avec couverture
mvn clean test jacoco:report
```

---

## 🔐 Sécurité

### Authentification

Le service utilise **JWT avec RSA public key** partagée par auth-service:

1. Gateway valide le JWT
2. Gateway ajoute header `X-Gateway-Secret`
3. Media-service vérifie le secret Gateway
4. Media-service valide le JWT avec clé publique RSA

### Secrets Management

**⚠️ IMPORTANT**: Tous les secrets sont dans `/Users/abdatytechnologie/Documents/Restart/.env`

**NE JAMAIS COMMITER** ce fichier dans Git!

Pour production, utiliser:
- HashiCorp Vault
- AWS Secrets Manager
- Kubernetes Secrets

---

## 📊 Monitoring

### Métriques Prometheus

```bash
# Endpoint
curl http://localhost:8087/actuator/prometheus

# Métriques business
- documents_uploaded_total
- documents_approved_total
- documents_rejected_total
- storage_usage_bytes
- malware_detected_total
```

### Dashboards Grafana

Accès: `http://localhost:3000` (admin/admin)

Dashboards disponibles:
- Media Service - Overview
- Media Service - Performance
- Media Service - Security

### Logs Loki

```bash
# Recherche logs
{service="media-service"} |= "ERROR"
{service="media-service",level="WARN"}
```

### Traces Zipkin

Accès: `http://localhost:9411`

---

## 🐳 Déploiement

### Docker Compose

Les fichiers docker-compose sont à la racine du projet:

- `docker-compose-minimal.yml` - Infrastructure de base
- `docker-compose-observability.yml` - Stack monitoring

### Variables d'environnement

Toutes les variables sont centralisées dans `.env` à la racine.

---

## 📚 Documentation Complémentaire

- **Analyse critique**: `ANALYSE_CRITIQUE_2026.md`
- **Tests API**: `TEST_CURL_COMMANDS.md`
- **Corrections appliquées**: `CORRECTIONS_APPLIED.md`

---

## 🎯 Roadmap

### Phase 1 - Complété ✅
- Architecture réactive complète
- Sécurité multi-couches
- Résilience (Circuit Breaker, Retry, Bulkhead)
- Observabilité (Prometheus, Zipkin, Loki)
- Custom Health Indicators
- Bean Validation
- Swagger enrichi

### Phase 2 - En cours 🚧
- Tests unitaires (couverture 80%+)
- Tests d'intégration (TestContainers)
- Tests de sécurité
- Tests de performance

### Phase 3 - À venir 📋
- CI/CD Pipeline (GitHub Actions)
- Containerisation (Dockerfile multi-stage)
- Kubernetes manifests
- Helm charts
- Documentation Runbook

---

## 🤝 Contribution

1. Fork le projet
2. Créer une branche (`git checkout -b feature/AmazingFeature`)
3. Commit (`git commit -m 'Add AmazingFeature'`)
4. Push (`git push origin feature/AmazingFeature`)
5. Ouvrir une Pull Request

---

## 📞 Support

**Équipe**: Media Service Team  
**Documentation**: Voir `/docs`  
**Issues**: GitHub Issues

---

**Version**: 1.0.0  
**Dernière mise à jour**: 2026-03-03  
**Statut**: ✅ Production Ready (après tests complets)
