# 📘 DOCUMENTATION TECHNIQUE - USER-SERVICE

## Table des Matières

1. [Vue d'Ensemble](#vue-densemble)
2. [Architecture Réactive](#architecture-réactive)
3. [Modèle de Données](#modèle-de-données)
4. [Logiques Métier](#logiques-métier)
5. [Flux de Synchronisation Kafka](#flux-de-synchronisation-kafka)
6. [Sécurité](#sécurité)
7. [API Endpoints](#api-endpoints)
8. [Configuration](#configuration)
9. [Déploiement](#déploiement)

---

## 1. Vue d'Ensemble

### Rôle du Service

Le **user-service** est responsable de :
- ✅ Gestion des profils utilisateurs (CRUD)
- ✅ Synchronisation avec auth-service via Kafka
- ✅ Administration des utilisateurs (blocage, suspension, suppression)
- ✅ Gestion des fichiers (photos de profil)
- ✅ Audit des actions administratives
- ✅ Cache des données utilisateur (Redis)

### Positionnement dans l'Architecture

```
┌──────────────┐     ┌─────────────┐     ┌──────────────┐
│   Gateway    │────▶│ Auth-Service│────▶│ User-Service │
│   (8080)     │     │   (8082)    │     │   (8083)     │
└──────────────┘     └─────────────┘     └──────────────┘
                            │                    │
                            ▼                    ▼
                     ┌───────────┐        ┌───────────┐
                     │   MySQL   │        │PostgreSQL │
                     └───────────┘        └───────────┘
                            │                    │
                            └────────┬───────────┘
                                     ▼
                              ┌────────────┐
                              │   Kafka    │
                              │user-events │
                              └────────────┘
```

**Source de vérité :**
- **Auth-Service** : Identité (email, phone, password, role)
- **User-Service** : Profil (bio, adresse, préférences, photo)

---

## 2. Architecture Réactive

### Stack Technique

```
┌─────────────────────────────────────────┐
│         Spring Boot 4.0.1               │
├─────────────────────────────────────────┤
│  WebFlux (Netty) - Serveur HTTP Réactif│
├─────────────────────────────────────────┤
│  R2DBC PostgreSQL - Base de données     │
│  Reactor Kafka - Messaging              │
│  Redis Reactive - Cache                 │
├─────────────────────────────────────────┤
│  Project Reactor (Mono/Flux)            │
└─────────────────────────────────────────┘
```

### Avantages de l'Architecture Réactive

1. **Non-Bloquant** : Pas de threads bloqués en attente I/O
2. **Scalabilité** : Gère plus de connexions avec moins de ressources
3. **Backpressure** : Contrôle du flux de données
4. **Résilience** : Circuit breaker, retry automatique

### Netty Configuration

**Worker Threads :** Auto-calculé (2x CPU cores)
- 10 CPU → 20 worker threads
- Optimisé pour I/O intensif

**Buffers :**
- Receive: 256 KB
- Send: 256 KB

**Optimisations TCP :**
- TCP_NODELAY: true (latence faible)
- SO_KEEPALIVE: true (connexions persistantes)

---

## 3. Modèle de Données

### Schéma PostgreSQL

#### Table `users`

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_user_id VARCHAR(255) NOT NULL UNIQUE,  -- Référence auth-service
    email VARCHAR(255),                          -- Copie pour recherche
    phone_number VARCHAR(50),                    -- Copie pour recherche
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    date_of_birth DATE,
    profile_picture_url VARCHAR(500),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    blocked_reason VARCHAR(500),
    blocked_at TIMESTAMP,
    suspended_until TIMESTAMP,
    last_login TIMESTAMP,
    deleted_at TIMESTAMP,                        -- Soft delete
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**Champs clés :**
- `auth_user_id` : UUID de l'utilisateur dans auth-service (source de vérité)
- `status` : ACTIVE, BLOCKED, SUSPENDED, DELETED
- `deleted_at` : Soft delete (RGPD compliant)

#### Table `user_profiles`

```sql
CREATE TABLE user_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    bio TEXT,
    address VARCHAR(500),
    city VARCHAR(100),
    country VARCHAR(100),
    postal_code VARCHAR(20),
    preferences JSONB,                           -- Préférences utilisateur
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**Relation :** 1-to-1 avec `users`

#### Table `user_role_info`

```sql
CREATE TABLE user_role_info (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,                   -- USER, ADMIN, SUPER_ADMIN
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    assigned_by UUID,                            -- Qui a assigné le rôle
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

#### Table `audit_logs`

```sql
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(100) NOT NULL,               -- BLOCK_USER, UPDATE_USER, etc.
    entity_type VARCHAR(100),                   -- USER, PROFILE, etc.
    entity_id VARCHAR(255),
    details TEXT,                               -- JSON avec détails
    ip_address VARCHAR(50),
    user_agent VARCHAR(500),
    status VARCHAR(50) NOT NULL,                -- SUCCESS, FAILED
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**Usage :** Traçabilité des actions administratives

### Entités R2DBC

#### BaseEntity

```java
@MappedSuperclass
public abstract class BaseEntity {
    @Id
    private UUID id;
    
    @CreatedDate
    private LocalDateTime createdAt;
    
    @LastModifiedDate
    private LocalDateTime updatedAt;
}
```

**Auditing automatique** via `@EnableR2dbcAuditing`

---

## 4. Logiques Métier

### 4.1 Synchronisation Kafka

**Événement reçu :** `UserCreatedEvent`

```java
{
  "authUserId": "uuid",
  "email": "user@example.com",
  "phoneNumber": "+33612345678",
  "firstName": "John",
  "lastName": "Doe",
  "role": "USER",
  "createdAt": "2026-02-19T14:00:00"
}
```

**Traitement :**

```java
@Component
public class UserEventListener {
    
    @PostConstruct
    public void startConsuming() {
        kafkaReceiver.receive()
            .flatMap(this::handleUserEventReactive)
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(5)))
            .subscribe();
    }
    
    private Mono<Void> handleUserCreatedReactive(ReceiverRecord<String, String> record) {
        return Mono.fromCallable(() -> objectMapper.readValue(record.value(), UserCreatedEvent.class))
            .flatMap(event -> {
                // 1. Vérifier si utilisateur existe déjà
                return userService.getUserByAuthUserId(event.getAuthUserId())
                    .flatMap(existingUser -> {
                        log.info("User already exists");
                        return Mono.<Void>empty();
                    })
                    .onErrorResume(ResourceNotFoundException.class, e -> {
                        // 2. Créer nouvel utilisateur
                        UserCreationDTO dto = new UserCreationDTO(event.getAuthUserId(), ...);
                        return userService.createUser(dto)
                            .doOnSuccess(created -> {
                                record.receiverOffset().acknowledge();
                                log.info("✅ User created: {}", event.getAuthUserId());
                            })
                            .then();
                    });
            });
    }
}
```

**Points clés :**
- ✅ Désérialisation JSON manuelle (ObjectMapper)
- ✅ Acknowledgement manuel après succès
- ✅ Retry avec backoff exponentiel (3 tentatives)
- ✅ Idempotence (vérification existence)

### 4.2 Gestion des Profils

#### Récupération Profil

```java
@Service
public class UserServiceReactiveImpl implements UserService {
    
    public Mono<UserDetailDTO> getUserProfile(String authUserId) {
        // 1. Vérifier cache Redis
        return redisTemplate.opsForValue()
            .get("user:profile:" + authUserId)
            .switchIfEmpty(
                // 2. Charger depuis PostgreSQL
                userRepository.findByAuthUserId(authUserId)
                    .switchIfEmpty(Mono.error(new ResourceNotFoundException("User not found")))
                    .flatMap(user -> 
                        // 3. Charger profil associé
                        userProfileRepository.findByUserId(user.getId())
                            .map(profile -> userMapper.toDetailDTO(user, profile))
                    )
                    .flatMap(dto -> 
                        // 4. Mettre en cache (TTL 15min)
                        redisTemplate.opsForValue()
                            .set("user:profile:" + authUserId, dto, Duration.ofMinutes(15))
                            .thenReturn(dto)
                    )
            );
    }
}
```

**Stratégie de cache :**
- Cache-aside pattern
- TTL: 15 minutes
- Invalidation sur mise à jour

#### Mise à Jour Profil

```java
public Mono<UserDetailDTO> updateUserProfile(String authUserId, UserProfileUpdateDTO dto) {
    return userRepository.findByAuthUserId(authUserId)
        .switchIfEmpty(Mono.error(new ResourceNotFoundException("User not found")))
        .flatMap(user -> {
            // 1. Mettre à jour User
            user.setDateOfBirth(dto.getDateOfBirth());
            user.setProfilePictureUrl(dto.getProfilePictureUrl());
            
            return userRepository.save(user)
                .flatMap(savedUser -> 
                    // 2. Mettre à jour UserProfile
                    userProfileRepository.findByUserId(savedUser.getId())
                        .switchIfEmpty(createDefaultProfile(savedUser.getId()))
                        .flatMap(profile -> {
                            profile.setBio(dto.getBio());
                            profile.setAddress(dto.getAddress());
                            profile.setCity(dto.getCity());
                            profile.setCountry(dto.getCountry());
                            profile.setPostalCode(dto.getPostalCode());
                            
                            return userProfileRepository.save(profile)
                                .map(savedProfile -> userMapper.toDetailDTO(savedUser, savedProfile));
                        })
                )
                .flatMap(result -> 
                    // 3. Invalider cache
                    redisTemplate.delete("user:profile:" + authUserId)
                        .thenReturn(result)
                );
        });
}
```

**Transaction réactive :**
- Pas de `@Transactional` (bloquant)
- Chaînage avec `flatMap`
- Rollback manuel si erreur

### 4.3 Administration Utilisateurs

#### Blocage Utilisateur

```java
public Mono<Void> blockUser(UUID userId, String reason, String adminId) {
    return userRepository.findById(userId)
        .switchIfEmpty(Mono.error(new ResourceNotFoundException("User not found")))
        .flatMap(user -> {
            // 1. Bloquer utilisateur
            user.setStatus(UserStatus.BLOCKED);
            user.setBlockedReason(reason);
            user.setBlockedAt(LocalDateTime.now());
            
            return userRepository.save(user)
                .flatMap(blockedUser -> 
                    // 2. Logger action audit
                    auditLogService.logAction(
                        adminId,
                        "BLOCK_USER",
                        "USER",
                        userId.toString(),
                        "Reason: " + reason,
                        null, // IP
                        null, // User-Agent
                        AuditStatus.SUCCESS
                    )
                )
                .then();
        });
}
```

**Actions admin disponibles :**
- `blockUser` - Blocage permanent
- `suspendUser` - Suspension temporaire
- `deleteUser` - Suppression définitive (hard delete)
- `softDeleteUser` - Soft delete (RGPD)

### 4.4 Upload Fichiers

```java
@Service
public class LocalFileStorageService implements FileStorageService {
    
    public Mono<String> storeFile(FilePart filePart, String userId) {
        return Mono.fromCallable(() -> {
            // 1. Générer nom unique
            String fileName = UUID.randomUUID() + "_" + filePart.filename();
            Path uploadPath = Paths.get(uploadDir, userId);
            
            // 2. Créer répertoire si nécessaire
            Files.createDirectories(uploadPath);
            
            // 3. Sauvegarder fichier
            Path filePath = uploadPath.resolve(fileName);
            filePart.transferTo(filePath).block(); // Bloquant ici car I/O fichier
            
            return "/uploads/" + userId + "/" + fileName;
        })
        .subscribeOn(Schedulers.boundedElastic()); // Thread pool pour I/O bloquant
    }
}
```

**Stratégie :**
- Stockage local (production: S3, MinIO, etc.)
- Organisation par userId
- Noms uniques (UUID)
- Thread pool dédié pour I/O fichier

---

## 5. Flux de Synchronisation Kafka

### Configuration Consumer

```yaml
kafka:
  consumer:
    resilience:
      auto-offset-reset: earliest      # ⚠️ CRITIQUE pour rattrapage
      enable-auto-commit: false        # Contrôle manuel
      max-poll-records: 100
      session-timeout: 30000
      max-poll-interval: 300000
      heartbeat-interval: 10000
```

### Scénario : Service Down puis Restart

**Situation :**
1. User-service est arrêté à 10:00
2. Auth-service crée 5 utilisateurs entre 10:00 et 10:30
3. User-service redémarre à 10:35

**Comportement :**

```
10:00 - User-service DOWN
        ↓
10:05 - Auth-service publie UserCreatedEvent #1 → Kafka
10:10 - Auth-service publie UserCreatedEvent #2 → Kafka
10:15 - Auth-service publie UserCreatedEvent #3 → Kafka
10:20 - Auth-service publie UserCreatedEvent #4 → Kafka
10:25 - Auth-service publie UserCreatedEvent #5 → Kafka
        ↓
10:35 - User-service RESTART
        ↓
        Consumer lit depuis dernier offset connu
        auto-offset-reset=earliest garantit lecture depuis début si aucun offset
        ↓
        Consomme événements #1, #2, #3, #4, #5
        ↓
        Crée 5 utilisateurs dans PostgreSQL
        ↓
        Acknowledge chaque message après succès
```

**Garanties :**
- ✅ **At-least-once delivery** : Chaque message traité au moins une fois
- ✅ **Idempotence** : Vérification existence avant création
- ✅ **Ordering** : Messages traités dans l'ordre (même partition)

### Gestion des Erreurs

```java
.retryWhen(Retry.backoff(3, Duration.ofSeconds(5))
    .maxBackoff(Duration.ofSeconds(30))
    .filter(throwable -> throwable instanceof TransientException)
)
.onErrorResume(e -> {
    log.error("Failed to process event after retries", e);
    // Ne pas acknowledge → message sera retraité
    return Mono.empty();
})
```

**Stratégie :**
- Retry 3 fois avec backoff exponentiel
- Erreurs transitoires (DB timeout) → Retry
- Erreurs permanentes (validation) → Log + Acknowledge (dead letter queue)

---

## 6. Sécurité

### 6.1 Validation JWT

```java
@Component
public class JwtTokenValidator {
    
    private final PublicKey publicKey;
    
    public Mono<Claims> validateToken(String token) {
        return Mono.fromCallable(() -> {
            return Jwts.parserBuilder()
                .setSigningKey(publicKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
        })
        .flatMap(claims -> 
            // Vérifier si token blacklisté
            tokenBlacklistService.isBlacklisted(token)
                .flatMap(isBlacklisted -> 
                    isBlacklisted 
                        ? Mono.error(new InvalidTokenException("Token blacklisted"))
                        : Mono.just(claims)
                )
        )
        .subscribeOn(Schedulers.boundedElastic());
    }
}
```

**Clé publique RSA :**
- Chargée depuis `classpath:keys/public_key.pem`
- Partagée avec auth-service (clé privée)
- Validation signature asymétrique

### 6.2 Gateway Filter

```java
@Component
public class GatewayAccessFilter implements WebFilter {
    
    @Value("${gateway.secret.key}")
    private String gatewaySecretKey;
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String secretKey = exchange.getRequest().getHeaders().getFirst("X-Secret-Key");
        
        if (!gatewaySecretKey.equals(secretKey)) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        
        return chain.filter(exchange);
    }
}
```

**Protection :**
- Seul le Gateway peut appeler user-service
- Header `X-Secret-Key` vérifié
- Empêche accès direct

### 6.3 Autorisation

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/**").permitAll()
                .pathMatchers("/api/v1/internal/**").hasAuthority("SCOPE_SERVICE")
                .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .build();
    }
}
```

**Règles :**
- `/actuator/**` → Public (health checks)
- `/api/v1/internal/**` → Service-to-service (scope SERVICE)
- `/api/v1/admin/**` → Admins uniquement
- Autres → Authentifié

---

## 7. API Endpoints

### 7.1 Profil Utilisateur

#### GET /api/v1/users/profile
**Description :** Récupérer profil utilisateur connecté

**Headers :**
```
Authorization: Bearer <JWT_TOKEN>
X-Secret-Key: <GATEWAY_SECRET>
```

**Response 200 :**
```json
{
  "id": "uuid",
  "authUserId": "uuid",
  "email": "user@example.com",
  "phoneNumber": "+33612345678",
  "firstName": "John",
  "lastName": "Doe",
  "dateOfBirth": "1990-01-01",
  "profilePictureUrl": "/uploads/user-id/photo.jpg",
  "bio": "Software engineer",
  "address": "123 Main St",
  "city": "Paris",
  "country": "France",
  "postalCode": "75001",
  "status": "ACTIVE",
  "createdAt": "2026-01-01T10:00:00",
  "updatedAt": "2026-02-19T14:00:00"
}
```

#### PUT /api/v1/users/profile
**Description :** Mettre à jour profil

**Request Body :**
```json
{
  "dateOfBirth": "1990-01-01",
  "bio": "Updated bio",
  "address": "456 New St",
  "city": "Lyon",
  "country": "France",
  "postalCode": "69001"
}
```

**Response 200 :** UserDetailDTO

#### POST /api/v1/users/profile/picture
**Description :** Upload photo de profil

**Content-Type :** `multipart/form-data`

**Form Data :**
- `file`: Image file (JPG, PNG, max 5MB)

**Response 200 :**
```json
{
  "message": "Profile picture uploaded successfully",
  "fileUrl": "/uploads/user-id/photo.jpg"
}
```

### 7.2 Administration

#### GET /api/v1/admin/users
**Description :** Liste utilisateurs (pagination)

**Query Params :**
- `page`: Page number (default: 0)
- `size`: Page size (default: 20)
- `status`: Filter by status (ACTIVE, BLOCKED, etc.)

**Response 200 :**
```json
{
  "content": [UserDetailDTO, ...],
  "totalElements": 150,
  "totalPages": 8,
  "size": 20,
  "number": 0
}
```

#### POST /api/v1/admin/users/{id}/block
**Description :** Bloquer utilisateur

**Request Body :**
```json
{
  "reason": "Violation des conditions d'utilisation"
}
```

**Response 200 :**
```json
{
  "message": "User blocked successfully"
}
```

#### DELETE /api/v1/admin/users/{id}
**Description :** Suppression définitive (hard delete)

**Response 200 :**
```json
{
  "message": "User permanently deleted"
}
```

### 7.3 Internal (Service-to-Service)

#### GET /api/v1/internal/users/{authUserId}
**Description :** Récupérer utilisateur par authUserId (pour autres services)

**Headers :**
```
X-Secret-Key: <GATEWAY_SECRET>
```

**Response 200 :** UserDetailDTO

---

## 8. Configuration

### 8.1 Variables d'Environnement

**Base de données :**
```env
DB_HOST=localhost
DB_PORT=5432
DB_NAME_USER=user_service_db
DB_USERNAME=postgres
DB_PASSWORD=97P@ssw0rd
```

**Kafka :**
```env
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
KAFKA_AUTO_OFFSET_RESET=earliest
KAFKA_ENABLE_AUTO_COMMIT=false
```

**Netty :**
```env
NETTY_WORKER_THREADS=0  # Auto-detect
NETTY_BACKLOG=1024
NETTY_RECEIVE_BUFFER=262144
NETTY_SEND_BUFFER=262144
```

**Sécurité :**
```env
GATEWAY_SECRET_KEY=gw_sec_2024_prod_key_change_me_in_production_xyz789
JWT_PUBLIC_KEY_PATH=classpath:keys/public_key.pem
```

### 8.2 Profils Spring

**dev :**
- Config server: http://localhost:8888
- PostgreSQL local
- Kafka local
- Redis local

**prod :**
- Config server: https://config.prod.example.com
- PostgreSQL cluster
- Kafka cluster
- Redis cluster

---

## 9. Déploiement

### 9.1 Prérequis

- Java 17+
- PostgreSQL 14+
- Kafka 3.0+
- Redis 6+
- Consul (service discovery)

### 9.2 Build

```bash
mvn clean package -DskipTests
```

**Artifact :** `target/user-service-0.0.1-SNAPSHOT.jar`

### 9.3 Run

```bash
java -jar user-service-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod \
  --server.port=8083
```

### 9.4 Health Checks

**Liveness :**
```bash
curl http://localhost:8083/actuator/health/liveness
```

**Readiness :**
```bash
curl http://localhost:8083/actuator/health/readiness
```

**Composants surveillés :**
- R2DBC (PostgreSQL)
- Redis
- Consul
- Disk Space

---

## 📊 Métriques Clés

**Performance :**
- Latence P95 < 100ms (endpoints profil)
- Throughput: 1000 req/s
- Worker threads: 20 (10 CPU cores)

**Disponibilité :**
- Uptime: 99.9%
- Circuit breaker: Activé sur appels externes

**Kafka :**
- Consumer lag: < 10 messages
- Processing time: < 50ms/message

---

## 🔧 Troubleshooting

### Problème : Consumer Kafka ne consomme pas

**Vérifications :**
1. Kafka broker accessible ?
   ```bash
   telnet localhost 9092
   ```

2. Topic existe ?
   ```bash
   kafka-topics --list --bootstrap-server localhost:9092
   ```

3. Consumer group actif ?
   ```bash
   kafka-consumer-groups --describe --group user-service-group-dev --bootstrap-server localhost:9092
   ```

### Problème : Connexion PostgreSQL échoue

**Vérifications :**
1. PostgreSQL démarré ?
   ```bash
   pg_isready -h localhost -p 5432
   ```

2. Base de données existe ?
   ```bash
   psql -h localhost -U postgres -l
   ```

3. Tables créées ?
   ```bash
   psql -h localhost -U postgres -d user_service_db -c "\dt"
   ```

### Problème : Cache Redis ne fonctionne pas

**Vérifications :**
1. Redis démarré ?
   ```bash
   redis-cli ping
   ```

2. Connexion OK ?
   ```bash
   redis-cli -h localhost -p 6379
   ```

---

**Dernière mise à jour :** 19 Février 2026  
**Auteur :** Équipe Technique  
**Version :** 1.0
