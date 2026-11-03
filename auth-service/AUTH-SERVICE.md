# 📘 Documentation Complète - Auth Service

## 📋 Table des Matières

1. [Vue d'ensemble](#vue-densemble)
2. [Architecture Technique](#architecture-technique)
3. [Composants Principaux](#composants-principaux)
4. [Services Métier](#services-métier)
5. [Sécurité et Authentification](#sécurité-et-authentification)
6. [Mappers et DTOs](#mappers-et-dtos)
7. [Observabilité](#observabilité)
8. [Configuration](#configuration)
9. [Flux d'Authentification](#flux-dauthentification)
10. [Dépendances](#dépendances)

---

## 🎯 Vue d'ensemble

### Rôle du Auth Service

Le **Auth Service** est le **service d'authentification et d'autorisation** de l'architecture microservices. Il gère :

- ✅ **Authentification** multi-canal (Email, Téléphone, OAuth2)
- ✅ **Gestion des utilisateurs** et rôles multiples avec sélection lors de l'inscription
- ✅ **Tokens JWT** (génération, validation, révocation) avec rôles multiples
- ✅ **Refresh Tokens** avec rotation
- ✅ **Vérification OTP** (SMS et Email)
- ✅ **OAuth2** (Google, Facebook)
- ✅ **Réinitialisation mot de passe**
- ✅ **Account Lockout** (protection brute-force)
- ✅ **Audit** des actions sensibles
- ✅ **Métriques** métier (Prometheus)

### Caractéristiques Techniques

| Propriété | Valeur |
|-----------|--------|
| **Port** | `8082` |
| **Framework** | Spring Boot 4.0.1 + Spring WebFlux (Reactive) |
| **Serveur** | Netty (non-bloquant, asynchrone) |
| **Base de données** | MySQL (JPA wrapped en boundedElastic) |
| **Cache** | Redis (ReactiveRedisTemplate) |
| **Messaging** | Kafka (reactor-kafka) |
| **Discovery** | Consul |
| **Config** | Spring Cloud Config Server |
| **Observabilité** | Prometheus + Grafana + Zipkin + Loki |

---

## 🏗️ Architecture Technique

### Architecture Réactive Hybride

Le Auth Service utilise une **architecture réactive hybride** :
- **HTTP I/O** : Netty + WebFlux (100% réactif)
- **Controllers** : Reactive (Mono/Flux)
- **Services** : Synchrones wrappés en `Schedulers.boundedElastic()`
- **Redis** : ReactiveRedisTemplate (100% réactif)
- **Kafka** : reactor-kafka (100% réactif)
- **Database** : JPA (bloquant, exécuté sur boundedElastic)

```
┌─────────────────────────────────────────────────────────────┐
│                    CLIENT (Browser/App)                      │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTP Request
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  NETTY SERVER (Port 8082)                    │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  Event Loop - Thread unique non-bloquant             │  │
│  │  - Détecte les I/O events (read/write)               │  │
│  │  - Alloue des Channels pour chaque connexion         │  │
│  └───────────────────────────────────────────────────────┘  │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              CHAÎNE DE FILTRES WEBFLUX (Mono/Flux)           │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ 1. GatewayAccessWebFilter (Validation HMAC Gateway) │    │
│  │ 2. JwtAuthenticationWebFilter (Validation JWT)      │    │
│  └─────────────────────────────────────────────────────┘    │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   CONTROLLERS (Reactive)                     │
│  - AuthController                                            │
│  - UserRoleManagementController                              │
│  - AdminAccountController                                    │
│  - AuditController                                           │
│  - InternalAuthController                                    │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              SERVICES (Wrapped en boundedElastic)            │
│  - AuthServiceImpl                                           │
│  - OAuth2ServiceImpl                                         │
│  - JwtServiceImpl                                            │
│  - AccountLockoutServiceImpl (Redis Reactive)                │
│  - TokenBlacklistServiceImpl (Redis Reactive)                │
│  - AuditServiceImpl (Kafka Reactive)                         │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   MAPPERS (DTO/Entity)                       │
│  - UserMapper                                                │
│  - UserRoleMapper                                            │
│  - AuthResponseMapper                                        │
│  - VerificationResponseMapper                                │
│  - OAuth2ResponseMapper                                      │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              REPOSITORIES & EXTERNAL SERVICES                │
│  - JPA Repositories (MySQL)                                  │
│  - ReactiveRedisTemplate (Redis)                             │
│  - KafkaSender (Kafka)                                       │
│  - UserServiceClient (Feign)                                 │
│  - SmsService (Twilio)                                       │
│  - EmailService (SMTP)                                       │
└─────────────────────────────────────────────────────────────┘
```

### Avantages de l'Architecture Hybride

| Aspect | Justification |
|--------|---------------|
| **HTTP Réactif** | Netty gère 10,000+ connexions simultanées |
| **Services Synchrones** | JPA mature, stable, facile à maintenir |
| **boundedElastic** | Pool de threads dédié pour I/O bloquantes |
| **Redis Réactif** | Cache distribué haute performance |
| **Kafka Réactif** | Messaging asynchrone avec backpressure |

---

## 🧩 Composants Principaux

### 1. AuthServiceApplication

**Fichier:** `AuthServiceApplication.java`

Point d'entrée de l'application.

```java
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableJpaRepositories
@EnableCaching
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
```

**Annotations:**
- `@SpringBootApplication`: Configuration Spring Boot
- `@EnableDiscoveryClient`: Active Consul Discovery
- `@EnableFeignClients`: Active OpenFeign pour appels inter-services
- `@EnableJpaRepositories`: Active JPA repositories
- `@EnableCaching`: Active le cache Redis

---

### 2. Configuration

#### NettyConfig

**Fichier:** `config/NettyConfig.java`

Configure le serveur Netty et le WebClient.

```java
@Configuration
public class NettyConfig {
    
    @Value("${spring.netty.worker-threads}")
    private int workerThreads;
    
    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder
            .clientConnector(new ReactorClientHttpConnector(
                HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .responseTimeout(Duration.ofSeconds(5))
            ))
            .build();
    }
}
```

**Configuration:**
```yaml
spring:
  netty:
    worker-threads: 4
    connection-timeout: 5000
    max-connections: 1000
```

---

#### RedisConfig

**Fichier:** `config/RedisConfig.java`

Configure Redis pour cache et ReactiveRedisTemplate.

```java
@Bean
public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
        ReactiveRedisConnectionFactory connectionFactory) {
    return new ReactiveRedisTemplate<>(
        connectionFactory,
        RedisSerializationContext.string()
    );
}
```

**Utilisations:**
- ✅ Account Lockout (compteur tentatives échouées)
- ✅ Token Blacklist (révocation JWT)
- ✅ Cache distribué (sessions, templates)

---

#### KafkaProducerConfig

**Fichier:** `config/KafkaProducerConfig.java`

Configure Kafka Producer réactif.

```java
@Bean
public KafkaSender<String, Object> kafkaSender() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
    
    SenderOptions<String, Object> senderOptions = SenderOptions.create(props);
    return KafkaSender.create(senderOptions);
}
```

**Topics Kafka:**
- `user-events`: Événements utilisateur (création, connexion, logout)
- `audit-events`: Événements d'audit (actions sensibles)
- `oauth2-events`: Événements OAuth2

---

#### SecurityConfig

**Fichier:** `config/SecurityConfig.java`

Configure la sécurité WebFlux.

```java
@Bean
public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
    return http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .exceptionHandling(exception -> exception
            .authenticationEntryPoint(jwtAuthenticationEntryPoint)
            .accessDeniedHandler(jwtAccessDeniedHandler)
        )
        .headers(headers -> headers
            .contentSecurityPolicy(csp -> csp
                .policyDirectives("default-src 'self'; frame-ancestors 'none'")
            )
        )
        .authorizeExchange(auth -> auth
            .pathMatchers("/auth/**").permitAll()
            .pathMatchers("/actuator/health", "/actuator/info").permitAll()
            .pathMatchers("/admin/**").hasRole("ADMIN")
            .anyExchange().authenticated()
        )
        .addFilterAt(gatewayAccessWebFilter, SecurityWebFiltersOrder.FIRST)
        .addFilterAt(jwtAuthenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
        .build();
}
```

**Endpoints publics:**
- `/auth/**`: Authentification, inscription, OAuth2
- `/actuator/health`, `/actuator/info`: Health checks
- `/swagger-ui/**`, `/v3/api-docs/**`: Documentation API

**Endpoints protégés:**
- `/admin/**`: Administration (ROLE_ADMIN requis)
- `/internal/**`: Communication inter-services (Gateway uniquement)
- `/auth-users/**`: Gestion utilisateurs (ROLE_ADMIN requis)

---

### 3. Filtres de Sécurité

#### GatewayAccessWebFilter

**Fichier:** `security/GatewayAccessWebFilter.java`

**Rôle:** Valide que la requête provient du Gateway via signature HMAC

**Validation:**
```java
// 1. Extraire les headers
String timestamp = exchange.getRequest().getHeaders().getFirst("X-Gateway-Timestamp");
String signature = exchange.getRequest().getHeaders().getFirst("X-Gateway-Signature");
String requestId = exchange.getRequest().getHeaders().getFirst("X-Gateway-Request-Id");
String secretKey = exchange.getRequest().getHeaders().getFirst("X-Secret-Key");

// 2. Valider le timestamp (anti-replay)
if (!hmacSignatureUtil.isTimestampValid(timestamp)) {
    return sendUnauthorized("Request timestamp expired");
}

// 3. Reconstruire les données signées
String signatureData = timestamp + "|" + path + "|" + method + "|" + requestId;

// 4. Valider la signature
if (!hmacSignatureUtil.validateSignature(secretKey, signatureData, signature)) {
    return sendUnauthorized("Invalid gateway signature");
}
```

**Endpoints publics (skip validation):**
- `/actuator/**`
- `/swagger-ui/**`
- `/v3/api-docs/**`

---

#### JwtAuthenticationWebFilter

**Fichier:** `security/JwtAuthenticationWebFilter.java`

**Rôle:** Valide les tokens JWT pour les endpoints protégés

**Validation JWT:**
```java
String token = extractToken(request); // Bearer <token>

Claims claims = Jwts.parser()
    .setSigningKey(jwtSecret)
    .parseClaimsJws(token)
    .getBody();

String userId = claims.getSubject();
String email = claims.get("email", String.class);
List<String> roles = claims.get("roles", List.class);

// Vérifier si le token est révoqué (blacklist)
if (tokenBlacklistService.isTokenRevoked(tokenId).block()) {
    return sendUnauthorized("Token has been revoked");
}

// Ajouter les infos dans le contexte de sécurité
SecurityContextHolder.getContext().setAuthentication(authentication);
```

**Endpoints publics (skip JWT):**
- `/auth/login`
- `/auth/register`
- `/auth/email/**`
- `/auth/phone/**`
- `/auth/oauth2/**`
- `/auth/forgot-password`
- `/auth/reset-password`

---

## 🔧 Services Métier

### 1. AuthServiceImpl

**Fichier:** `service/impl/AuthServiceImpl.java`

**Responsabilités:**
- Authentification par login/password
- Authentification par Email OTP
- Authentification par Téléphone OTP
- Inscription (Email et Téléphone) avec **sélection de rôle**
  - Rôle par défaut : `USER` si non spécifié
  - Rôles disponibles : `USER`, `DRIVER`, `MERCHANT`, `PHARMACY`, `RESTAURANT`, `SUPERMARKET`, `DISPATCHER`
  - Protection : `ADMIN` ne peut pas être auto-attribué
- Réinitialisation mot de passe
- Gestion profil utilisateur
- Refresh tokens

**Méthodes principales:**

#### login()
```java
public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
    // 1. Vérifier utilisateur
    User user = userRepository.findByPhoneNumber(request.getPhoneNumber())
        .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS));
    
    // 2. Vérifier account lockout
    if (user.getIsLocked() && LocalDateTime.now().isBefore(user.getLockedUntil())) {
        throw new AuthException(ErrorCode.ACCOUNT_LOCKED);
    }
    
    // 3. Vérifier mot de passe
    if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
        handleFailedLogin(user, ipAddress, userAgent);
        throw new AuthException(ErrorCode.INVALID_CREDENTIALS);
    }
    
    // 4. Générer tokens
    String accessToken = jwtService.generateToken(user);
    RefreshToken refreshToken = refreshTokenService.createRefreshToken(user, ipAddress, userAgent);
    
    // 5. Logger l'événement
    authenticationLogService.logAuthenticationEvent(userId, phoneNumber, EventType.LOGIN, Status.SUCCESS);
    
    // 6. Publier événement Kafka
    eventPublisher.publishUserLoggedIn(userId, ipAddress);
    
    return authResponseMapper.toAuthResponse(accessToken, refreshToken, expiresIn, user);
}
```

#### initiatePhoneAuth()
```java
public VerificationResponse initiatePhoneAuth(PhoneAuthRequest request) {
    // 1. Vérifier que l'utilisateur existe
    User user = userRepository.findByPhoneNumber(request.getPhoneNumber())
        .orElseThrow(() -> new AuthException(ErrorCode.USER_NOT_FOUND));
    
    // 2. Générer et envoyer code OTP
    verificationCodeService.generateAndSendCode(
        request.getPhoneNumber(),
        user.getEmail(),
        VerificationCodeType.PHONE_VERIFICATION
    );
    
    return verificationResponseMapper.toSuccessResponse(
        "Code OTP envoyé par SMS",
        AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES
    );
}
```

---

### 2. OAuth2ServiceImpl

**Fichier:** `service/impl/OAuth2ServiceImpl.java`

**Responsabilités:**
- Authentification OAuth2 (Google, Facebook)
- Échange code d'autorisation contre access token
- Récupération infos utilisateur depuis provider
- Création/Liaison compte OAuth
- Complétion profil OAuth

**Flux OAuth2:**

```
1. Client → Redirect vers Google/Facebook
   ↓
2. User autorise l'application
   ↓
3. Provider → Redirect vers /auth/oauth2/callback?code=xxx
   ↓
4. Auth Service → Échange code contre access token
   ↓
5. Auth Service → Récupère infos utilisateur (email, nom, photo)
   ↓
6. Auth Service → Vérifie si compte existe
   ├─ OUI → Génère JWT + Refresh Token
   └─ NON → Crée compte temporaire + Demande complétion profil
```

**Méthodes principales:**

#### handleOAuth2Callback()
```java
public OAuth2AuthResponse handleOAuth2Callback(OAuth2CallbackRequest request) {
    // 1. Échanger code contre access token
    OAuth2UserInfo userInfo = exchangeCodeForToken(request.getProvider(), request.getCode());
    
    // 2. Chercher compte OAuth existant
    Optional<OAuthAccount> oauthAccount = oauthAccountRepository
        .findByProviderAndProviderUserId(provider, userInfo.getProviderId());
    
    if (oauthAccount.isPresent()) {
        // Utilisateur existant → Login
        return handleExistingOAuthUser(oauthAccount.get().getUser());
    } else {
        // Nouvel utilisateur → Création ou complétion profil
        return handleNewOAuthUser(provider, userInfo);
    }
}
```

---

### 3. JwtServiceImpl

**Fichier:** `service/impl/JwtServiceImpl.java`

**Responsabilités:**
- Génération tokens JWT
- Validation tokens JWT
- Extraction claims
- Vérification révocation (blacklist)

**Structure JWT:**
```json
{
  "sub": "user-id-123",
  "email": "user@example.com",
  "roles": ["ROLE_USER", "ROLE_DRIVER"],
  "primaryRole": "ROLE_DRIVER",
  "iat": 1738000000,
  "exp": 1738086400,
  "jti": "token-id-456"
}
```

**Méthodes principales:**

#### generateToken()
```java
public String generateToken(User user) {
    Date now = new Date();
    Date expiryDate = new Date(now.getTime() + jwtExpirationMs);
    
    // Récupérer tous les rôles actifs de l'utilisateur
    var roleAssignments = roleAssignmentRepository.findActiveRolesByUserId(user.getId());
    var roles = roleAssignments.stream()
            .map(assignment -> assignment.getRole().getAuthority())
            .toList();
    
    return Jwts.builder()
        .setSubject(user.getId())
        .claim("email", user.getEmail())
        .claim("roles", roles.isEmpty() ? user.getRole().getAuthority() : roles)
        .claim("primaryRole", user.getRole().getAuthority())
        .setIssuedAt(now)
        .setExpiration(expiryDate)
        .setId(UUID.randomUUID().toString())
        .signWith(privateKey, SignatureAlgorithm.RS256)
        .compact();
}
```

---

### 4. AccountLockoutServiceImpl

**Fichier:** `service/impl/AccountLockoutServiceImpl.java`

**Responsabilités:**
- Compteur tentatives échouées (Redis)
- Verrouillage automatique après N tentatives
- Déverrouillage automatique après expiration
- Déverrouillage manuel (admin)

**Fonctionnement:**

```java
public Mono<Boolean> recordFailedAttempt(String identifier) {
    String key = LOCKOUT_PREFIX + identifier;
    
    return reactiveRedisTemplate.opsForValue()
        .increment(key)
        .flatMap(attempts -> {
            if (attempts == 1) {
                // Premier échec → Set TTL
                return reactiveRedisTemplate.expire(key, Duration.ofMinutes(attemptWindowMinutes))
                    .thenReturn(false);
            } else if (attempts >= maxAttempts) {
                // Seuil atteint → Verrouiller
                return lockAccount(identifier).thenReturn(true);
            } else {
                return Mono.just(false);
            }
        });
}
```

**Configuration:**
```yaml
auth:
  lockout:
    max-attempts: 5
    duration-minutes: 30
    attempt-window-minutes: 15
```

---

### 5. TokenBlacklistServiceImpl

**Fichier:** `service/impl/TokenBlacklistServiceImpl.java`

**Responsabilités:**
- Révocation tokens JWT (blacklist Redis)
- Vérification si token révoqué
- Révocation tous les tokens d'un utilisateur
- TTL automatique (expiration token)

**Méthodes principales:**

```java
public Mono<Void> revokeToken(String tokenId, long expirationMs) {
    String key = BLACKLIST_PREFIX + tokenId;
    return reactiveRedisTemplate.opsForValue()
        .set(key, "revoked", Duration.ofMillis(expirationMs))
        .then();
}

public Mono<Boolean> isTokenRevoked(String tokenId) {
    String key = BLACKLIST_PREFIX + tokenId;
    return reactiveRedisTemplate.hasKey(key);
}
```

---

### 6. AuditServiceImpl

**Fichier:** `service/impl/AuditServiceImpl.java`

**Responsabilités:**
- Publication événements d'audit vers Kafka
- Logging actions sensibles
- Traçabilité des opérations

**Événements audités:**
- Tentatives de connexion (succès/échec)
- Modifications de rôles
- Révocations de tokens
- Changements de mot de passe
- Verrouillages de comptes
- Actions administratives

**Méthode principale:**

```java
public Mono<Void> logAuditEvent(String userId, String action, String details, String ipAddress) {
    AuditEvent event = AuditEvent.builder()
        .userId(userId)
        .action(action)
        .details(details)
        .ipAddress(ipAddress)
        .timestamp(LocalDateTime.now())
        .build();
    
    return kafkaSender.send(
        Mono.just(SenderRecord.create(
            new ProducerRecord<>("audit-events", userId, event),
            null
        ))
    ).then();
}
```

---

## 🗺️ Mappers et DTOs

### Architecture Mappers

Le service utilise une **couche mappers dédiée** pour séparer les entités JPA des DTOs.

```
┌─────────────────────────────────────┐
│         Controllers (Web)           │
│  - Reçoivent/Retournent DTOs        │
└──────────────┬──────────────────────┘
               │
    ┌──────────▼──────────┐
    │     Mappers         │
    │  - UserMapper       │
    │  - UserRoleMapper   │
    │  - AuthResponseMapper│
    │  - VerificationResponseMapper│
    │  - OAuth2ResponseMapper│
    └──────────┬──────────┘
               │
┌──────────────▼──────────────────────┐
│      Services (Business Logic)      │
│  - Travaillent avec Entities        │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│      Entities (JPA)                 │
│  - User, RefreshToken, etc.         │
└─────────────────────────────────────┘
```

### Mappers Disponibles

#### 1. UserMapper

```java
@Component
public class UserMapper {
    public UserResponse toResponse(User user) {
        return UserResponse.builder()
            .id(user.getId())
            .phoneNumber(user.getPhoneNumber())
            .email(user.getEmail())
            .firstName(user.getFirstName())
            .lastName(user.getLastName())
            .role(user.getRole())
            .isPhoneVerified(user.getIsPhoneVerified())
            .isEmailVerified(user.getIsEmailVerified())
            .isActive(user.getIsActive())
            .build();
    }
}
```

#### 2. AuthResponseMapper

```java
@Component
public class AuthResponseMapper {
    public AuthResponse toAuthResponse(String accessToken, RefreshToken refreshToken, 
                                        long expiresInSeconds, User user) {
        return AuthResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken.getToken())
            .tokenType("Bearer")
            .expiresIn(expiresInSeconds)
            .user(userMapper.toResponse(user))
            .build();
    }
}
```

---

## 📊 Observabilité

### Métriques Prometheus

**Endpoint:** `http://localhost:8082/actuator/prometheus`

#### Métriques Métier (AuthMetrics)

| Métrique | Type | Description |
|----------|------|-------------|
| `auth_login_success_total{method="password"}` | Counter | Connexions réussies par méthode |
| `auth_login_failure_total{method="password"}` | Counter | Connexions échouées par méthode |
| `auth_registration_total{method="phone"}` | Counter | Inscriptions par méthode |
| `auth_token_generation_duration_seconds` | Timer | Durée génération tokens |
| `auth_token_revocation_total` | Counter | Révocations de tokens |
| `auth_account_lockout_total` | Counter | Verrouillages de comptes |
| `auth_password_reset_total` | Counter | Réinitialisations mot de passe |
| `auth_oauth2_login_total{provider="google"}` | Counter | Connexions OAuth2 par provider |
| `auth_otp_sent_total{channel="sms"}` | Counter | OTP envoyés par canal |
| `auth_otp_verified_total{channel="email"}` | Counter | OTP vérifiés par canal |

### Logs Structurés

**Configuration:** `logback-spring.xml`

**Format:**
```json
{
  "timestamp": "2026-02-11T17:35:00.123Z",
  "level": "INFO",
  "logger": "com.abdatytch.auth_service.service.impl.AuthServiceImpl",
  "message": "User login successful",
  "userId": "user-123",
  "ipAddress": "192.168.1.100",
  "userAgent": "Mozilla/5.0...",
  "traceId": "6978b4a3f4c41273",
  "spanId": "d2880444599f3ffe"
}
```

---

## ⚙️ Configuration

### Variables d'Environnement (.env)

```bash
# Database
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_DATABASE=auth_db
MYSQL_USER=auth_user
MYSQL_PASSWORD=auth_password

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=redis_password

# Kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# JWT
JWT_SECRET=your-jwt-secret-key
JWT_EXPIRATION_MS=86400000
JWT_REFRESH_EXPIRATION_MS=604800000

# OAuth2
OAUTH2_GOOGLE_CLIENT_ID=your-google-client-id
OAUTH2_GOOGLE_CLIENT_SECRET=your-google-client-secret
OAUTH2_FACEBOOK_CLIENT_ID=your-facebook-client-id
OAUTH2_FACEBOOK_CLIENT_SECRET=your-facebook-client-secret

# SMS (Twilio)
TWILIO_ACCOUNT_SID=your-twilio-sid
TWILIO_AUTH_TOKEN=your-twilio-token
TWILIO_PHONE_NUMBER=+1234567890

# Email
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=your-email@gmail.com
SMTP_PASSWORD=your-email-password

# Gateway
GATEWAY_SECRET_KEY=gw_secret_key_xyz789
```

---

## 🔄 Flux d'Authentification

### Flux 1: Login par Mot de Passe

```
1. POST /auth/login
   Body: { "phoneNumber": "+33612345678", "password": "password123" }
   ↓
2. Vérifier utilisateur + mot de passe
   ↓
3. Vérifier account lockout
   ↓
4. Générer JWT + Refresh Token
   ↓
5. Logger événement + Publier Kafka
   ↓
6. Response: { "accessToken": "...", "refreshToken": "...", "user": {...} }
```

### Flux 2: Authentification par Téléphone OTP

```
1. POST /auth/phone/auth-request
   Body: { "phoneNumber": "+33612345678" }
   ↓
2. Générer code OTP (6 chiffres)
   ↓
3. Envoyer SMS via Twilio
   ↓
4. Stocker code en DB (TTL 5 min)
   ↓
5. Response: { "success": true, "expiresInMinutes": 5 }

---

6. POST /auth/phone/verify-code
   Body: { "phoneNumber": "+33612345678", "otp": "123456" }
   ↓
7. Valider code OTP
   ↓
8. Générer JWT + Refresh Token
   ↓
9. Response: { "accessToken": "...", "refreshToken": "...", "user": {...} }
```

### Flux 3: OAuth2 (Google/Facebook)

```
1. Client → Redirect vers Google OAuth
   https://accounts.google.com/o/oauth2/v2/auth?client_id=...
   ↓
2. User autorise l'application
   ↓
3. Google → Redirect vers /auth/oauth2/callback/google?code=xxx
   ↓
4. POST /auth/oauth2/callback
   Body: { "code": "xxx", "provider": "google" }
   ↓
5. Échanger code contre access token Google
   ↓
6. Récupérer infos utilisateur depuis Google API
   ↓
7. Vérifier si compte OAuth existe
   ├─ OUI → Générer JWT + Refresh Token
   └─ NON → Créer compte temporaire + Demander complétion profil
   ↓
8. Response: { "accessToken": "...", "profileComplete": true/false }

---

9. Si profileComplete=false:
   POST /auth/oauth2/complete-profile
   Body: { "tempToken": "...", "phoneNumber": "+33612345678", "countryCode": "+33" }
   ↓
10. Compléter profil utilisateur
    ↓
11. Générer JWT + Refresh Token
    ↓
12. Response: { "accessToken": "...", "refreshToken": "...", "user": {...} }
```

---

## 📦 Dépendances

### Core Dependencies

```xml
<!-- Spring Boot WebFlux (Reactive) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- Spring Data JPA -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<!-- Spring Data Redis Reactive -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
</dependency>

<!-- Reactor Kafka -->
<dependency>
    <groupId>io.projectreactor.kafka</groupId>
    <artifactId>reactor-kafka</artifactId>
    <version>1.3.24</version>
</dependency>

<!-- JWT (JJWT) -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>

<!-- Resilience4j (Circuit Breaker, Rate Limiter, Retry) -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>

<!-- Micrometer (Metrics) -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- Consul Discovery -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-consul-discovery</artifactId>
</dependency>

<!-- OpenFeign -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>

<!-- Twilio (SMS) -->
<dependency>
    <groupId>com.twilio.sdk</groupId>
    <artifactId>twilio</artifactId>
    <version>9.14.1</version>
</dependency>
```

---

## 🎯 Points Clés

### ✅ Avantages

1. **Architecture Réactive** - Haute performance avec Netty
2. **Sécurité Renforcée** - HMAC Gateway + JWT + Account Lockout + Audit
3. **Multi-Canal** - Email, Téléphone, OAuth2
4. **Observabilité** - Métriques métier + Logs structurés + Tracing
5. **Résilience** - Circuit Breaker + Retry + Rate Limiting
6. **Séparation des Couches** - Mappers dédiés pour DTO/Entity
7. **Cache Distribué** - Redis pour performance
8. **Messaging Asynchrone** - Kafka pour événements

### 🔧 Améliorations Futures

- [ ] Migration complète vers R2DBC (database réactive)
- [ ] Token Rotation automatique sur refresh
- [ ] 2FA (Two-Factor Authentication)
- [ ] Biométrie (Face ID, Touch ID)
- [ ] Magic Links (connexion sans mot de passe)
- [ ] Session Management avancé
- [ ] Détection anomalies (ML)

---

**Version:** 1.0.0  
**Date:** Février 2026  
**Auteur:** Abdaty Technologies
