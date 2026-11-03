# 📁 Configuration Repository - Spring Cloud Config

Ce repository contient toutes les configurations centralisées pour les microservices.

## 📋 Structure

```
config-repo/
├── application.yml              # Configuration globale (tous les services)
├── config-service.yml          # Config Service
├── gateway-service.yml         # API Gateway
├── auth-service.yml            # Service d'authentification
├── user-service.yml            # Service utilisateur
├── geo-service.yml             # Service géolocalisation
├── payment-service.yml         # Service paiement
├── notification-service.yml    # Service notification
└── media-service.yml           # Service média
```

## 🔧 Configuration par Service

### **application.yml** - Configuration Globale
- JPA/Hibernate
- Actuator & Prometheus
- Logging
- Consul Discovery
- Vault Integration
- Resilience4J (Circuit Breaker, Retry, Rate Limiter)
- CORS

### **gateway-service.yml**
- Port: 8081
- Routes vers tous les microservices
- Circuit Breaker par route
- OAuth2 Resource Server
- Rate Limiting
- Retry Policy

### **auth-service.yml**
- Port: 8082
- OAuth2 Client (Keycloak)
- Keycloak Admin Client
- JWT Configuration

### **user-service.yml**
- Port: 8083
- MySQL Database (port 3307)
- Kafka Producer/Consumer
- OAuth2 Resource Server
- Keycloak Integration

### **geo-service.yml**
- Port: 8084
- MySQL Database (port 3306)
- Redis Cache
- OAuth2 Resource Server
- Configuration géospatiale

### **payment-service.yml**
- Port: 8085
- MySQL Database (port 3308)
- Kafka Producer/Consumer
- OAuth2 Resource Server
- Orange Money & Moov Money Integration

### **notification-service.yml**
- Port: 8086
- MySQL Database (port 3309)
- Kafka Consumer
- SMTP Configuration
- SMS Provider (Twilio)
- Firebase Push Notifications

### **media-service.yml**
- Port: 8087
- MySQL Database (port 3310)
- File Upload Configuration
- Storage (Local/S3/MinIO)
- OAuth2 Resource Server

## 🚀 Utilisation

### 1. Initialiser le Repository Git

```bash
cd /Users/abdatytechnologie/Documents/Restart/config-repo
git init
git add .
git commit -m "Initial configuration"
```

### 2. Démarrer Config Service

```bash
cd /Users/abdatytechnologie/Documents/Restart/config-service
./mvnw spring-boot:run
```

### 3. Tester la Configuration

```bash
# Récupérer la configuration d'un service
curl http://localhost:8888/gateway-service/default
curl http://localhost:8888/user-service/default
curl http://localhost:8888/application/default
```

## 🔄 Rafraîchir la Configuration

Pour rafraîchir la configuration d'un service sans redémarrage :

```bash
curl -X POST http://localhost:8083/actuator/refresh
```

## 🔐 Variables d'Environnement

Les variables sensibles sont externalisées :

```bash
# Keycloak
KEYCLOAK_CLIENT_SECRET=your-client-secret

# JWT
JWT_SECRET=your-256-bit-secret-key

# Payment Providers
ORANGE_MONEY_API_KEY=your-api-key
MOOV_MONEY_API_KEY=your-api-key

# Notification
SMS_PROVIDER_API_KEY=your-api-key
FCM_SERVER_KEY=your-firebase-key
SMTP_PASSWORD=your-email-password

# Storage
S3_ACCESS_KEY=your-access-key
S3_SECRET_KEY=your-secret-key
```

## 📝 Bonnes Pratiques

1. **Ne jamais commiter de secrets** - Utiliser des variables d'environnement
2. **Versionner les changements** - Chaque modification doit être commitée
3. **Tester avant de déployer** - Vérifier la configuration via l'API Config Server
4. **Utiliser des profils** - dev, test, prod
5. **Documenter les changements** - Messages de commit clairs

## 🔄 Workflow Git

```bash
# Modifier une configuration
vim user-service.yml

# Commiter le changement
git add user-service.yml
git commit -m "Update user-service database pool size"

# Config Server détecte automatiquement le changement
# Rafraîchir le service
curl -X POST http://localhost:8083/actuator/refresh
```

## 🎯 Prochaines Étapes

1. Initialiser le repository Git
2. Configurer les variables d'environnement
3. Démarrer Config Service
4. Démarrer les autres microservices
5. Vérifier que chaque service récupère sa configuration
