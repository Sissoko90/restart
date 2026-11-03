# 📁 Media Service - Analyse Critique & Documentation

## 🎯 Vue d'ensemble

Service de gestion de documents réactif (WebFlux) avec stockage MinIO, validation de sécurité, analyse de qualité et système de métriques.

**Port**: 8087  
**Stack**: Spring Boot 4.0.1, WebFlux (Netty), MongoDB Reactive, MinIO, Redis, Kafka

---

## 🚨 FAILLES DE SÉCURITÉ CRITIQUES

### 1. ⚠️ **CRITIQUE - Exposition de Secrets**

**Problème**: Clés secrètes potentiellement exposées dans les logs
```java
// GatewayAccessWebFilter.java:58
if (!secretKey.equals(gatewaySecretKey)) {
    log.error("🚫 INVALID SECRET KEY - IP: {}, Path: {}", remoteAddress, path);
```

**Impact**: Les secrets peuvent apparaître dans les logs si mal configurés  
**Solution**: 
- Ne JAMAIS logger les secrets, même en cas d'erreur
- Utiliser un système de vault (HashiCorp Vault, AWS Secrets Manager)
- Rotation automatique des secrets

---

### 2. 🔴 **CRITIQUE - Validation JWT Insuffisante**

**Problèmes identifiés**:

```java
// JwtAuthenticationWebFilter.java:86-91
Claims claims = Jwts.parser()
    .verifyWith(publicKey)
    .build()
    .parseSignedClaims(token)
    .getPayload();
```

**Manque**:
- ❌ Pas de vérification de l'expiration du token
- ❌ Pas de vérification de l'issuer (iss)
- ❌ Pas de vérification de l'audience (aud)
- ❌ Pas de blacklist pour tokens révoqués
- ❌ Pas de rate limiting sur les tentatives de validation

**Solution**:
```java
Claims claims = Jwts.parser()
    .verifyWith(publicKey)
    .requireIssuer("auth-service")
    .requireAudience("media-service")
    .build()
    .parseSignedClaims(token)
    .getPayload();

// Vérifier expiration explicitement
if (claims.getExpiration().before(new Date())) {
    throw new ExpiredJwtException(...);
}

// Vérifier blacklist Redis
if (tokenBlacklistService.isBlacklisted(token)) {
    throw new RevokedTokenException(...);
}
```

---

### 3. 🔴 **CRITIQUE - Injection de Path Traversal**

**Problème**: Validation insuffisante des noms de fichiers

```java
// StorageServiceImpl.java:72-79
minioClient.putObject(
    PutObjectArgs.builder()
        .bucket(bucketName)
        .object(fileName)  // ⚠️ Pas de sanitization!
```

**Attaque possible**:
```bash
fileName = "../../etc/passwd"
fileName = "../../../root/.ssh/id_rsa"
```

**Solution**:
```java
private String sanitizeFileName(String fileName) {
    // Supprimer path traversal
    fileName = fileName.replaceAll("\\.\\./", "");
    fileName = fileName.replaceAll("\\.\\\\", "");
    
    // Supprimer caractères dangereux
    fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    
    // Limiter longueur
    if (fileName.length() > 255) {
        fileName = fileName.substring(0, 255);
    }
    
    return fileName;
}
```

---

### 4. 🟠 **ÉLEVÉ - Absence de Rate Limiting Global**

**Problème**: Rate limiting uniquement sur certains endpoints

```java
// DocumentController.java:36
@RateLimiter(name = "document-upload")
```

**Manque**:
- ❌ Pas de rate limiting sur download (risque de DDoS)
- ❌ Pas de rate limiting sur validation de documents
- ❌ Pas de protection contre brute force sur JWT

**Solution**: Implémenter rate limiting global avec Redis

---

### 5. 🟠 **ÉLEVÉ - Logs Sensibles**

**Problème**: Exposition d'informations sensibles dans les logs

```java
// DocumentServiceImpl.java:94
log.debug("📄 File details - Name: {}, Size: {} bytes, MIME: {}", 
    fileName, fileData.length, mimeType);
```

**Risques**:
- Noms de fichiers potentiellement sensibles (CNI, passeport)
- Métadonnées utilisateur
- Chemins de stockage

**Solution**: Masquer les données sensibles dans les logs

---

### 6. 🟡 **MOYEN - Fallback Storage Non Sécurisé**

**Problème**: Stockage local sans chiffrement

```java
// StorageServiceImpl.java:112-115
Path fallbackPath = Paths.get(fallbackStoragePath, fileName);
Files.createDirectories(fallbackPath.getParent());
Files.write(fallbackPath, bytes);
```

**Risques**:
- Documents stockés en clair sur disque
- Pas de contrôle d'accès fichier
- Pas de nettoyage automatique

**Solution**: Chiffrer les fichiers même en fallback

---

## 🏗️ PROBLÈMES D'ARCHITECTURE

### 1. ⚠️ **Couplage Fort avec Gateway**

**Problème**: Dépendance stricte aux headers du Gateway

```java
// GatewayAccessWebFilter.java:41-44
String secretKey = exchange.getRequest().getHeaders().getFirst(SecurityConstants.HEADER_X_SECRET_KEY);
String gatewaySignature = exchange.getRequest().getHeaders().getFirst("X-Gateway-Signature");
String gatewayRequestId = exchange.getRequest().getHeaders().getFirst("X-Gateway-Request-Id");
```

**Impact**:
- Impossible de tester le service indépendamment
- Impossible d'utiliser le service sans gateway
- Complexité de déploiement

**Solution**: Mode "standalone" pour tests et développement

---

### 2. 🔴 **Absence de Transaction Management**

**Problème**: Opérations MongoDB sans transactions

```java
// DocumentServiceImpl.java:573-578
return cacheService.delete(cacheKey)
    .then(storageService.deleteFile(document.getStoragePath()))
    .then(logAccess(documentId, userId, "DELETE", null, null, null))
    .then(mediaFileRepository.deleteById(documentId));
```

**Risque**: Si une étape échoue, état incohérent
- Document supprimé de MinIO mais pas de MongoDB
- Cache invalidé mais document toujours présent

**Solution**: Utiliser MongoDB Transactions
```java
@Transactional
public Mono<Void> deleteDocument(String documentId, String userId) {
    return transactionalOperator.execute(status -> {
        // Toutes les opérations dans la transaction
    });
}
```

---

### 3. 🟠 **Circuit Breaker Mal Configuré**

**Problème**: Pas de stratégie de dégradation progressive

```yaml
# application.yml:81-84
minio-storage:
  baseConfig: default
  waitDurationInOpenState: 15s
  failureRateThreshold: 60
```

**Manque**:
- Pas de half-open state adaptatif
- Pas de slow call threshold
- Pas de métriques de santé du circuit

**Solution**: Configuration avancée avec monitoring

---

### 4. 🟡 **Cache Sans Stratégie d'Éviction**

**Problème**: TTL fixe sans considération de la mémoire

```yaml
# application.yml:171-174
ttl:
  document-metadata: 3600
  document-validation: 1800
  user-documents: 600
```

**Risques**:
- Explosion mémoire Redis
- Pas de LRU/LFU
- Pas de compression

**Solution**: Stratégie d'éviction intelligente + monitoring

---

## ⚡ PROBLÈMES DE PERFORMANCE

### 1. 🔴 **Blocking I/O dans Reactive Stream**

**CRITIQUE**: Opérations bloquantes dans le flux réactif

```java
// DocumentServiceImpl.java:82-86
return DataBufferUtils.join(filePart.content())
    .flatMap(dataBuffer -> {
        byte[] fileData = new byte[dataBuffer.readableByteCount()];
        dataBuffer.read(bytes);  // ⚠️ Charge tout en mémoire!
```

**Impact**:
- Upload de 100MB = 100MB en RAM
- 10 uploads simultanés = 1GB RAM
- Risque OutOfMemoryError

**Solution**: Streaming avec backpressure
```java
return filePart.content()
    .windowTimeout(1000, Duration.ofSeconds(1))
    .flatMap(window -> window.collectList())
    .flatMap(chunks -> processChunks(chunks));
```

---

### 2. 🟠 **N+1 Query Problem**

**Problème**: Requêtes multiples pour récupérer documents

```java
// DocumentServiceImpl.java:529-531
public Flux<DocumentListResponse> getUserDocuments(String userId) {
    return mediaFileRepository.findByUserIdOrderByCreatedAtDesc(userId)
        .map(this::buildDocumentListResponse);
}
```

**Manque**: Pas de pagination, charge tous les documents

**Solution**: Pagination + projection
```java
public Flux<DocumentListResponse> getUserDocuments(String userId, Pageable pageable) {
    return mediaFileRepository.findByUserId(userId, pageable)
        .map(this::buildDocumentListResponse);
}
```

---

### 3. 🟡 **Image Processing Synchrone**

**Problème**: Traitement d'image bloquant

```java
// DocumentQualityServiceImpl.java:37-38
BufferedImage image = ImageIO.read(bis);  // ⚠️ Bloquant!
```

**Impact**: Thread pool saturé sur images volumineuses

**Solution**: Utiliser Schedulers.boundedElastic() correctement

---

### 4. 🟡 **Absence de Compression**

**Problème**: Pas de compression des réponses HTTP

**Impact**:
- Bande passante gaspillée
- Latence élevée
- Coûts réseau

**Solution**: Activer compression Netty
```yaml
server:
  compression:
    enabled: true
    mime-types: application/json,application/xml
```

---

## 🐛 PROBLÈMES DE GESTION D'ERREURS

### 1. 🔴 **Erreurs Génériques Exposées**

**Problème**: Stack traces potentiellement exposées

```java
// GlobalExceptionHandler.java:288-293
@ExceptionHandler(Exception.class)
public Mono<ResponseEntity<Map<String, Object>>> handleGenericException(Exception ex) {
    log.error("Unexpected error: {}", ex.getMessage(), ex);
    String message = ex.getMessage() != null ? ex.getMessage() : "Une erreur inattendue s'est produite";
    // ⚠️ Expose le message d'erreur interne!
```

**Risque**: Information disclosure

**Solution**: Messages génériques en production
```java
String message = isProd() ? 
    "Une erreur s'est produite" : 
    ex.getMessage();
```

---

### 2. 🟠 **Pas de Retry Idempotent**

**Problème**: Retry sans vérification d'idempotence

```java
// DocumentServiceImpl.java:558
@Retry(name = "mongodb-operations")
public Mono<Void> deleteDocument(String documentId, String userId) {
```

**Risque**: Double suppression, double facturation

**Solution**: Vérifier idempotence avant retry

---

### 3. 🟡 **Logs Non Structurés**

**Problème**: Logs difficiles à parser

```java
log.info("✅ Document uploaded - ID: {}", response.getDocumentId());
```

**Solution**: Logs structurés (JSON)
```java
log.info("document.uploaded", 
    kv("documentId", response.getDocumentId()),
    kv("userId", userId),
    kv("size", fileSize));
```

---

## 📊 PROBLÈMES DE MONITORING

### 1. 🔴 **Métriques Incomplètes**

**Manque**:
- ❌ Latence P95/P99 des opérations
- ❌ Taux d'erreur par endpoint
- ❌ Utilisation mémoire par opération
- ❌ Queue depth des threads

**Solution**: Métriques Micrometer complètes

---

### 2. 🟠 **Pas de Distributed Tracing**

**Problème**: Impossible de tracer une requête cross-service

**Solution**: Implémenter Zipkin/Jaeger avec correlation IDs

---

### 3. 🟡 **Health Checks Basiques**

**Problème**: Health check ne vérifie pas les dépendances critiques

**Solution**: Health indicators pour MinIO, MongoDB, Redis

---

## 🔧 PROBLÈMES DE CONFIGURATION

### 1. 🔴 **Secrets en Clair**

**Problème**: Configuration sensible non chiffrée

```yaml
# Potentiellement dans config-repo
minio:
  access-key: ${MINIO_ACCESS_KEY}
  secret-key: ${MINIO_SECRET_KEY}
```

**Solution**: Spring Cloud Config avec encryption

---

### 2. 🟠 **Pas de Profils Environnement**

**Manque**: Configuration dev/staging/prod

**Solution**: Profils Spring distincts

---

### 3. 🟡 **Timeouts Non Configurés**

**Problème**: Timeouts par défaut trop élevés

**Solution**: Timeouts explicites partout

---

## ✅ POINTS POSITIFS

### Architecture Réactive
- ✅ WebFlux correctement implémenté
- ✅ Reactive MongoDB
- ✅ Reactive Redis
- ✅ Non-blocking I/O

### Sécurité
- ✅ JWT validation
- ✅ Gateway filtering
- ✅ Role-based access control
- ✅ Malware detection
- ✅ Document quality validation

### Résilience
- ✅ Circuit Breaker implémenté
- ✅ Retry mechanism
- ✅ Bulkhead pattern
- ✅ Rate limiting
- ✅ Fallback storage

### Observabilité
- ✅ Prometheus metrics
- ✅ Structured logging
- ✅ Health endpoints
- ✅ Custom business metrics

---

## 🎯 RECOMMANDATIONS PRIORITAIRES

### 🔴 Critique (Immédiat)
1. **Sécuriser validation JWT** - Ajouter vérifications iss/aud/exp
2. **Sanitizer noms fichiers** - Prévenir path traversal
3. **Implémenter transactions** - Cohérence des données
4. **Streaming upload** - Éviter OOM sur gros fichiers
5. **Chiffrer fallback storage** - Sécurité données au repos

### 🟠 Élevé (Cette semaine)
6. **Rate limiting global** - Protection DDoS
7. **Masquer logs sensibles** - RGPD compliance
8. **Pagination requêtes** - Performance
9. **Health checks complets** - Monitoring
10. **Compression HTTP** - Bande passante

### 🟡 Moyen (Ce mois)
11. **Distributed tracing** - Observabilité
12. **Métriques avancées** - P95/P99
13. **Cache éviction** - Gestion mémoire
14. **Tests de charge** - Scalabilité
15. **Documentation API** - Swagger complet

---

## 📝 TODOs Identifiés dans le Code

```java
// DocumentServiceImpl.java:248-249
// TODO: Uncomment when UserActivityLogService is implemented
// .then(activityLogService.logDocumentUpload(...))

// DocumentServiceImpl.java:397-398
// TODO: Uncomment when UserActivityLogService is implemented
// .then(activityLogService.logSecurityIncident(...))
```

**Action**: Implémenter UserActivityLogService pour audit complet

---

## 🚀 Architecture Cible Recommandée

```
┌─────────────────────────────────────────────────────────┐
│                    API Gateway (8081)                    │
│  - Rate Limiting Global                                  │
│  - JWT Validation                                        │
│  - Request Routing                                       │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│                  Media Service (8087)                    │
│                                                          │
│  ┌────────────────┐  ┌────────────────┐                │
│  │   Controllers   │  │   Security     │                │
│  │   - Document    │  │   - JWT Filter │                │
│  │   - Admin       │  │   - Role Auth  │                │
│  └────────────────┘  └────────────────┘                │
│           │                    │                         │
│           ▼                    ▼                         │
│  ┌──────────────────────────────────┐                  │
│  │         Service Layer             │                  │
│  │  - Document Service               │                  │
│  │  - Storage Service (CB+Fallback)  │                  │
│  │  - Malware Detection (CB)         │                  │
│  │  - Quality Service (CB+Bulkhead)  │                  │
│  │  - Cache Service                  │                  │
│  └──────────────────────────────────┘                  │
│           │                                              │
│           ▼                                              │
│  ┌──────────────────────────────────┐                  │
│  │      Infrastructure Layer         │                  │
│  │  - MongoDB (Reactive)             │                  │
│  │  - MinIO (S3)                     │                  │
│  │  - Redis (Cache)                  │                  │
│  │  - Kafka (Events)                 │                  │
│  └──────────────────────────────────┘                  │
└─────────────────────────────────────────────────────────┘
```

---

## 📚 Documentation Complémentaire

- **API**: Voir `TEST_CURL_COMMANDS.md`
- **Déploiement**: À créer
- **Monitoring**: À créer
- **Runbook**: À créer

---

## 🔐 Checklist Sécurité

- [ ] Validation JWT complète (iss/aud/exp)
- [ ] Token blacklist implémentée
- [ ] Path traversal sanitization
- [ ] Rate limiting global
- [ ] Secrets dans vault
- [ ] Logs sensibles masqués
- [ ] HTTPS obligatoire
- [ ] CORS configuré
- [ ] Input validation stricte
- [ ] Output encoding
- [ ] SQL injection prevention (N/A - NoSQL)
- [ ] XSS prevention
- [ ] CSRF protection
- [ ] Security headers
- [ ] Audit logging complet

---

## 📈 Métriques à Surveiller

### Performance
- Latence upload/download (P50, P95, P99)
- Throughput (req/s)
- Error rate (%)
- CPU/Memory usage

### Business
- Documents uploaded/day
- Documents approved/rejected
- Security threats detected
- Storage usage

### Infrastructure
- MongoDB connection pool
- Redis hit/miss ratio
- MinIO availability
- Circuit breaker state

---

## 🎓 Bonnes Pratiques à Adopter

1. **12-Factor App** - Configuration externalisée
2. **Fail Fast** - Validation précoce
3. **Defense in Depth** - Sécurité en couches
4. **Least Privilege** - Permissions minimales
5. **Immutable Infrastructure** - Containers
6. **Chaos Engineering** - Tests de résilience
7. **Continuous Security** - Scans automatiques
8. **Documentation as Code** - OpenAPI/Swagger

---

## 📞 Support & Maintenance

**Équipe**: Media Service Team  
**On-call**: À définir  
**Runbook**: À créer  
**Escalation**: À définir

---

**Dernière mise à jour**: 2026-03-02  
**Version**: 1.0.0  
**Statut**: ⚠️ En développement - Nécessite corrections sécurité
