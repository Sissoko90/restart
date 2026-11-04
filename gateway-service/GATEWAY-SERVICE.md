# 📘 Documentation Complète - Gateway Service

## 📋 Table des Matières

1. [Vue d'ensemble](#vue-densemble)
2. [Architecture Technique](#architecture-technique)
3. [Composants Principaux](#composants-principaux)
4. [Filtres de Sécurité](#filtres-de-sécurité)
5. [Routing et Proxification](#routing-et-proxification)
6. [Géolocalisation IP](#géolocalisation-ip)
7. [Métriques et Observabilité](#métriques-et-observabilité)
8. [Configuration](#configuration)
9. [Flux de Requête Complet](#flux-de-requête-complet)
10. [Dépendances](#dépendances)

---

## 🎯 Vue d'ensemble

### Rôle du Gateway

Le **Gateway Service** est la **porte d'entrée unique** de l'architecture microservices. Il agit comme un **reverse proxy intelligent** qui :

- ✅ **Route** les requêtes vers les microservices appropriés
- ✅ **Sécurise** l'accès avec plusieurs couches de protection
- ✅ **Authentifie** les utilisateurs via JWT
- ✅ **Limite** le trafic pour prévenir les abus (Rate Limiting)
- ✅ **Bloque** les attaques (XSS, Path Traversal, IP Blacklist, GeoIP)
- ✅ **Monitore** les requêtes avec métriques Prometheus
- ✅ **Trace** les appels avec Zipkin
- ✅ **Protège** les services downstream avec Circuit Breaker

### Caractéristiques Techniques

| Propriété | Valeur |
|-----------|--------|
| **Port** | `8081` |
| **Framework** | Spring Boot 4.0.1 + Spring WebFlux (Reactive) |
| **Serveur** | Netty (non-bloquant, asynchrone) |
| **Architecture** | Event Loop + Channels + Buffers |
| **Discovery** | Consul |
| **Config** | Spring Cloud Config Server |
| **Observabilité** | Prometheus + Grafana + Zipkin + Loki |

---

## 🏗️ Architecture Technique

### Architecture Réactive (Spring WebFlux)

Le Gateway utilise **Spring WebFlux** au lieu de Spring MVC traditionnel pour bénéficier d'une architecture **non-bloquante** et **hautement performante**.

```
┌─────────────────────────────────────────────────────────────┐
│                    CLIENT (Browser/App)                      │
└──────────────────────────┬──────────────────────────────────┘
                           │ HTTP Request
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  NETTY SERVER (Port 8081)                    │
│  ┌───────────────────────────────────────────────────────┐  │
│  │  Event Loop (Selector) - Thread unique non-bloquant  │  │
│  │  - Détecte les I/O events (read/write)               │  │
│  │  - Alloue des Channels pour chaque connexion         │  │
│  │  - Utilise des Buffers pour les données              │  │
│  └───────────────────────────────────────────────────────┘  │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              CHAÎNE DE FILTRES WEBFLUX (Mono/Flux)           │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ 1. RedisRateLimitWebFilter (Rate Limiting Redis)    │    │
│  │ 2. PathTraversalWebFilter (Anti Path Traversal)     │    │
│  │ 3. IpBlacklistWebFilter (Blacklist IP Redis)        │    │
│  │ 4. GeoLocationWebFilter (Blocage par pays)          │    │
│  │ 5. XssProtectionWebFilter (Anti XSS Headers)        │    │
│  │ 6. BodyXssScannerWebFilter (Scan XSS Body)          │    │
│  │ 7. RateLimitWebFilter (Rate Limiting Bucket4j)      │    │
│  │ 8. JwtWebFilter (Validation JWT)                    │    │
│  │ 9. ApiVersionWebFilter (Versioning API)             │    │
│  │ 10. GatewaySignatureFilter (Signature HMAC)         │    │
│  └─────────────────────────────────────────────────────┘    │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              ROUTING (GatewayRoutesConfig)                   │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ RouterFunction<ServerResponse>                      │    │
│  │ - /api/v1/auth/** → auth-service                    │    │
│  │ - /api/v1/users/** → user-service                   │    │
│  └─────────────────────────────────────────────────────┘    │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│           CIRCUIT BREAKER (Resilience4j)                     │
│  - Protection contre cascading failures                      │
│  - Fallback automatique si service down                      │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│         SERVICE DISCOVERY (Consul) + LOAD BALANCING          │
│  - Résolution dynamique des instances                        │
│  - Load balancing automatique                                │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              WEBCLIENT (Proxification Reactive)              │
│  - Appel HTTP non-bloquant vers le microservice             │
│  - Transfert des headers (JWT, HMAC, User-ID)               │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│         MICROSERVICE (auth-service / user-service)           │
└─────────────────────────────────────────────────────────────┘
```

### Avantages de l'Architecture Réactive

| Aspect | Spring MVC (Bloquant) | Spring WebFlux (Réactif) |
|--------|----------------------|--------------------------|
| **Thread Model** | 1 thread par requête | Event Loop + Worker Threads |
| **Scalabilité** | ~200 requêtes/s | ~10,000+ requêtes/s |
| **Latence** | Haute (I/O bloquant) | Basse (I/O non-bloquant) |
| **Mémoire** | ~1MB par thread | ~KB par connexion |
| **Serveur** | Tomcat (Servlet) | Netty (NIO) |

---

## 🧩 Composants Principaux

### 1. GatewayServiceApplication

**Fichier:** `GatewayServiceApplication.java`

Point d'entrée de l'application.

```java
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class GatewayServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
```

**Annotations:**
- `@SpringBootApplication`: Configuration Spring Boot
- `@EnableDiscoveryClient`: Active Consul Discovery
- `@EnableFeignClients`: Active OpenFeign pour appels inter-services

---

### 2. Configuration

#### NettyConfig

**Fichier:** `config/NettyConfig.java`

Configure le serveur Netty et le WebClient pour les appels HTTP réactifs.

```java
@Configuration
public class NettyConfig {
    
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

**Fonctionnalités:**
- ✅ Timeout de connexion: 5 secondes
- ✅ Timeout de réponse: 5 secondes
- ✅ HTTP/2 support
- ✅ Connection pooling

---

#### GatewayRoutesConfig

**Fichier:** `config/GatewayRoutesConfig.java`

Définit le **routing manuel réactif** pour remplacer Spring Cloud Gateway (non disponible en Spring Boot 4.0.1).

**Principe:**
```java
@Bean
public RouterFunction<ServerResponse> gatewayRoutes(WebClient webClient) {
    return route()
        // Route: /api/v1/auth/** → auth-service
        .nest(path("/api/v1/auth/**"), builder -> builder
            .GET("/**", request -> proxyRequest(request, webClient, "auth-service"))
            .POST("/**", request -> proxyRequest(request, webClient, "auth-service"))
            .PUT("/**", request -> proxyRequest(request, webClient, "auth-service"))
            .DELETE("/**", request -> proxyRequest(request, webClient, "auth-service"))
        )
        // Route: /api/v1/users/** → user-service
        .nest(path("/api/v1/users/**"), builder -> builder
            .GET("/**", request -> proxyRequest(request, webClient, "user-service"))
            .POST("/**", request -> proxyRequest(request, webClient, "user-service"))
            .PUT("/**", request -> proxyRequest(request, webClient, "user-service"))
            .DELETE("/**", request -> proxyRequest(request, webClient, "user-service"))
        )
        .build();
}
```

**Transformation de Path:**
```
Client Request:  /api/v1/auth/email/auth-request
                      ↓ (Gateway transforme)
Service Request: /auth/email/auth-request
```

**Flux de Proxification:**

1. **Extraction du path cible**
   ```java
   String originalPath = request.path(); // /api/v1/auth/email/auth-request
   String targetPath = originalPath.replaceFirst("/api/v1", ""); // /auth/email/auth-request
   ```

2. **Résolution du service via Consul**
   ```java
   discoveryClient.getInstances("auth-service")
       .next() // Prendre la première instance disponible
       .flatMap(instance -> {
           String fullUri = instance.getUri() + targetPath;
           // fullUri = http://10.10.10.103:8082/auth/email/auth-request
       })
   ```

3. **Proxification avec WebClient**
   ```java
   webClient.method(request.method())
       .uri(fullUri)
       .headers(headers -> {
           // Copier tous les headers (incluant HMAC du GatewaySignatureFilter)
           request.headers().asHttpHeaders().forEach((key, value) -> {
               if (!key.equalsIgnoreCase(HttpHeaders.HOST)) {
                   headers.addAll(key, value);
               }
           });
           // Ajouter X-Auth-User-Id si JWT présent
           if (userId != null) {
               headers.set("X-Auth-User-Id", userId);
           }
       })
       .body(request.bodyToMono(String.class), String.class)
       .exchangeToMono(response -> { /* ... */ })
   ```

4. **Gestion des erreurs**
   ```java
   if (response.statusCode().isError()) {
       String logMessage = switch (statusCode) {
           case 400 -> "🔴 Bad Request";
           case 401 -> "🔐 Unauthorized";
           case 403 -> "🚫 Forbidden";
           case 404 -> "❓ Not Found";
           case 500 -> "💥 Internal Server Error";
           default -> "⚠️ Error";
       };
       log.warn("{} - {} {} - Status: {}", logMessage, method, uri, statusCode);
   }
   ```

---

#### GatewayCircuitBreakerConfig

**Fichier:** `config/GatewayCircuitBreakerConfig.java`

Configure les **Circuit Breakers** pour protéger contre les cascading failures.

**Circuit Breakers configurés:**
- `auth-service-circuit-breaker`
- `user-service-circuit-breaker`
- `default-circuit-breaker`

**Configuration:**
```yaml
resilience4j:
  circuitbreaker:
    instances:
      auth-service:
        failure-rate-threshold: 50        # Ouvre si 50% d'échecs
        wait-duration-in-open-state: 10s  # Reste ouvert 10s
        sliding-window-size: 10           # Fenêtre de 10 requêtes
        minimum-number-of-calls: 5        # Min 5 appels avant calcul
```

**États du Circuit Breaker:**

```
┌─────────────┐
│   CLOSED    │ ◄─── État normal, toutes requêtes passent
│  (Normal)   │
└──────┬──────┘
       │ Trop d'échecs (>50%)
       ▼
┌─────────────┐
│    OPEN     │ ◄─── Toutes requêtes rejetées immédiatement
│  (Bloqué)   │      Retourne 503 Service Unavailable
└──────┬──────┘
       │ Après 10 secondes
       ▼
┌─────────────┐
│ HALF_OPEN   │ ◄─── Test avec quelques requêtes
│   (Test)    │
└──────┬──────┘
       │
       ├─── Si succès → CLOSED
       └─── Si échec → OPEN
```

---

#### RedisConfig

**Fichier:** `config/RedisConfig.java`

Configure Redis pour:
- Rate Limiting distribué
- IP Blacklist
- Pays bloqués (GeoIP)
- Cache de sessions

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

---

#### OpenApiConfig

**Fichier:** `config/OpenApiConfig.java`

Configure Swagger UI pour la documentation API centralisée.

**Accès:** `http://localhost:8081/swagger-ui.html`

---

### 3. Utilitaires

#### HMACSignatureUtil

**Fichier:** `util/HMACSignatureUtil.java`

Génère et valide les **signatures HMAC-SHA256** pour sécuriser les communications Gateway → Services.

**Méthodes principales:**

```java
// Générer une signature HMAC
public String generateSignature(String secretKey, String data) {
    Mac mac = Mac.getInstance("HmacSHA256");
    SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(), "HmacSHA256");
    mac.init(secretKeySpec);
    byte[] hash = mac.doFinal(data.getBytes());
    return Base64.getEncoder().encodeToString(hash);
}

// Construire les données à signer
public String buildSignatureData(String timestamp, String path, String method, String requestId) {
    return timestamp + "|" + path + "|" + method + "|" + requestId;
}

// Valider une signature
public boolean validateSignature(String secretKey, String data, String providedSignature) {
    String expectedSignature = generateSignature(secretKey, data);
    return MessageDigest.isEqual(
        expectedSignature.getBytes(),
        providedSignature.getBytes()
    );
}

// Valider le timestamp (anti-replay)
public boolean isTimestampValid(String timestamp) {
    long requestTime = Long.parseLong(timestamp);
    long currentTime = System.currentTimeMillis();
    long diff = Math.abs(currentTime - requestTime);
    return diff <= 5000; // 5 secondes max
}
```

**Exemple de signature:**
```
Timestamp: 1738000000000
Path: /auth/email/auth-request
Method: POST
RequestId: 550e8400-e29b-41d4-a716-446655440000

Data = "1738000000000|/auth/email/auth-request|POST|550e8400-e29b-41d4-a716-446655440000"
Signature = HMAC-SHA256(secretKey, Data) = "a7f3b2c1d4e5..."
```

---

#### SecurityLogUtil

**Fichier:** `util/SecurityLogUtil.java`

Masque les données sensibles dans les logs.

```java
public static String maskSecret(String secret) {
    if (secret == null || secret.length() < 8) return "***";
    return secret.substring(0, 4) + "***" + secret.substring(secret.length() - 4);
}

// Exemple: "gw_secret_key_xyz789" → "gw_s***z789"
```

---

## 🛡️ Filtres de Sécurité

Les filtres sont exécutés dans l'ordre suivant (du premier au dernier):

### 1. RedisRateLimitWebFilter

**Fichier:** `security/RedisRateLimitWebFilter.java`

**Rôle:** Rate Limiting distribué avec Redis (partagé entre instances Gateway)

**Configuration:**
```yaml
gateway:
  rate-limit:
    enabled: true
    requests-per-minute: 50
    burst-capacity: 10
```

**Fonctionnement:**
```java
String key = "rate_limit:" + clientIp + ":" + path;
Long currentCount = redisTemplate.opsForValue().increment(key);

if (currentCount == 1) {
    redisTemplate.expire(key, Duration.ofMinutes(1));
}

if (currentCount > maxRequests) {
    return sendTooManyRequests(); // 429 Too Many Requests
}
```

**Métriques:**
- `gateway_rate_limit_exceeded_total`: Nombre de requêtes bloquées

---

### 2. PathTraversalWebFilter

**Fichier:** `security/PathTraversalWebFilter.java`

**Rôle:** Bloque les attaques Path Traversal (`../`, `..%2F`, etc.)

**Patterns détectés:**
```java
private static final List<String> SUSPICIOUS_PATTERNS = List.of(
    "../", "..\\", "..",
    "%2e%2e%2f", "%2e%2e/", "..%2f",
    "%2e%2e%5c", "%252e%252e%252f"
);
```

**Exemple bloqué:**
```
❌ /api/v1/../../etc/passwd
❌ /api/v1/%2e%2e%2f%2e%2e%2fetc/passwd
```

---

### 3. IpBlacklistWebFilter

**Fichier:** `security/IpBlacklistWebFilter.java`

**Rôle:** Bloque les IPs blacklistées dans Redis

**Commandes Redis:**
```bash
# Ajouter une IP à la blacklist
SADD ip_blacklist "192.168.1.100"

# Vérifier si IP est blacklistée
SISMEMBER ip_blacklist "192.168.1.100"

# Retirer une IP
SREM ip_blacklist "192.168.1.100"
```

**Métriques:**
- `gateway_ip_blacklist_blocked_total`: IPs bloquées

---

### 4. GeoLocationWebFilter

**Fichier:** `security/GeoLocationWebFilter.java`

**Rôle:** Bloque les requêtes provenant de pays interdits (GeoIP)

**Base de données:** MaxMind GeoLite2 City (`GeoLite2-City.mmdb`)

**Fonctionnement:**
```java
String clientIp = getClientIp(exchange);
String countryCode = geoLocationService.getCountryCode(clientIp);

if (geoLocationService.isCountryBlocked(countryCode)) {
    log.warn("🚫 Request blocked from country: {} (IP: {})", countryCode, clientIp);
    return sendForbidden("Access denied from your country");
}
```

**API Admin:**
```bash
# Bloquer un pays
POST /api/geo/block/CN

# Débloquer un pays
DELETE /api/geo/unblock/CN

# Liste des pays bloqués
GET /api/geo/blocked
```

**Métriques:**
- `geo_requests_total`: Total requêtes géolocalisées
- `geo_blocked_requests_total`: Requêtes bloquées par pays
- `geo_requests_by_country_total{country="US"}`: Requêtes par pays
- `geo_blocked_country_list{country="CN"}`: Liste pays bloqués

---

### 5. XssProtectionWebFilter

**Fichier:** `security/XssProtectionWebFilter.java`

**Rôle:** Ajoute les headers de protection XSS

**Headers ajoutés:**
```http
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-XSS-Protection: 1; mode=block
Content-Security-Policy: default-src 'self'
```

---

### 6. BodyXssScannerWebFilter

**Fichier:** `security/BodyXssScannerWebFilter.java`

**Rôle:** Scan le body des requêtes POST/PUT pour détecter du code XSS

**Patterns détectés:**
```java
private static final List<String> XSS_PATTERNS = List.of(
    "<script", "javascript:", "onerror=", "onload=",
    "<iframe", "<object", "<embed", "eval(",
    "document.cookie", "document.write"
);
```

**Exemple bloqué:**
```json
{
  "name": "<script>alert('XSS')</script>"
}
```

**Réponse:** `400 Bad Request - XSS attempt detected`

---

### 7. RateLimitWebFilter

**Fichier:** `security/RateLimitWebFilter.java`

**Rôle:** Rate Limiting local avec Bucket4j (par instance Gateway)

**Configuration:**
```java
Bandwidth limit = Bandwidth.builder()
    .capacity(100)              // 100 requêtes max
    .refillGreedy(100, Duration.ofMinutes(1)) // Recharge 100/min
    .build();

Bucket bucket = Bucket.builder()
    .addLimit(limit)
    .build();
```

**Algorithme:** Token Bucket

```
Bucket (100 tokens max)
├─ Requête 1: -1 token → 99 tokens restants
├─ Requête 2: -1 token → 98 tokens restants
├─ ...
├─ Requête 100: -1 token → 0 tokens restants
└─ Requête 101: ❌ 429 Too Many Requests

Après 1 minute: Bucket rechargé à 100 tokens
```

---

### 8. JwtWebFilter

**Fichier:** `security/JwtWebFilter.java`

**Rôle:** Valide les tokens JWT pour les endpoints protégés

**Endpoints publics (skip JWT):**
```java
private static final List<String> PUBLIC_ENDPOINTS = List.of(
    "/api/v1/auth/login",
    "/api/v1/auth/register",
    "/api/v1/auth/email/auth-request",
    "/api/v1/auth/email/verify-code",
    "/actuator/**",
    "/swagger-ui/**",
    "/v3/api-docs/**"
);
```

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

// Ajouter les infos dans les headers pour les services
exchange.getRequest().mutate()
    .header("X-User-Id", userId)
    .header("X-User-Email", email)
    .header("X-User-Roles", String.join(",", roles))
    .build();
```

**Erreurs:**
- `401 Unauthorized`: Token manquant ou invalide
- `403 Forbidden`: Token expiré

---

### 9. ApiVersionWebFilter

**Fichier:** `filter/ApiVersionWebFilter.java`

**Rôle:** Gère le versioning des APIs

**Versions supportées:**
```
/api/v1/auth/login  ✅ Version 1 (actuelle)
/api/v2/auth/login  ⚠️ Version 2 (future)
```

**Header de réponse:**
```http
X-API-Version: v1
```

---

### 10. GatewaySignatureFilter

**Fichier:** `filter/GatewaySignatureFilter.java`

**Rôle:** Ajoute les headers de signature HMAC pour sécuriser Gateway → Services

**Headers ajoutés:**
```http
X-Gateway-Timestamp: 1738000000000
X-Gateway-Signature: a7f3b2c1d4e5f6g7h8i9j0k1l2m3n4o5
X-Gateway-Request-Id: 550e8400-e29b-41d4-a716-446655440000
X-Secret-Key: gw_secret_key_xyz789
```

**Fonctionnement:**
```java
// 1. Transformer le path
String originalPath = "/api/v1/auth/email/auth-request";
String targetPath = "/auth/email/auth-request"; // Ce que le service recevra

// 2. Générer timestamp et request-id
String timestamp = String.valueOf(System.currentTimeMillis());
String requestId = UUID.randomUUID().toString();

// 3. Construire les données à signer
String signatureData = timestamp + "|" + targetPath + "|" + method + "|" + requestId;

// 4. Générer signature HMAC-SHA256
String signature = hmacSignatureUtil.generateSignature(secretKey, signatureData);

// 5. Ajouter les headers
request.mutate()
    .header("X-Gateway-Timestamp", timestamp)
    .header("X-Gateway-Signature", signature)
    .header("X-Gateway-Request-Id", requestId)
    .header("X-Secret-Key", secretKey)
    .build();
```

**Endpoints publics (skip signature):**
```java
private boolean isPublicEndpoint(String path) {
    return path.startsWith("/actuator/") ||
           path.contains("/v3/api-docs") ||
           path.contains("/swagger-ui");
}
```

---

## 🌍 Géolocalisation IP

### GeoLocationService

**Fichier:** `service/GeoLocationService.java`

**Rôle:** Géolocalise les IPs et bloque les pays interdits

**Base de données:** MaxMind GeoLite2 City

**Méthodes principales:**

#### 1. getCountryCode(String ip)

Retourne le code pays ISO (ex: `US`, `FR`, `CN`)

```java
public Mono<String> getCountryCode(String ip) {
    if (isLocalIp(ip)) {
        return Mono.just("LOCAL");
    }
    
    try {
        InetAddress ipAddress = InetAddress.getByName(ip);
        CountryResponse response = databaseReader.country(ipAddress);
        String countryCode = response.getCountry().getIsoCode();
        
        // Incrémenter métrique
        geoRequestsTotal.increment();
        meterRegistry.counter("geo_requests_by_country_total", "country", countryCode).increment();
        
        return Mono.just(countryCode);
    } catch (Exception e) {
        log.error("GeoIP lookup failed for IP {}: {}", ip, e.getMessage());
        geoLookupErrorsTotal.increment();
        return Mono.just("UNKNOWN");
    }
}
```

#### 2. getDetailedGeoInfo(String ip)

Retourne les informations géographiques complètes

```java
public Mono<GeoLocationInfo> getDetailedGeoInfo(String ip) {
    CityResponse response = databaseReader.city(ipAddress);
    
    return Mono.just(GeoLocationInfo.builder()
        .ip(ip)
        .country(response.getCountry().getName())
        .countryCode(response.getCountry().getIsoCode())
        .city(response.getCity().getName())
        .region(response.getMostSpecificSubdivision().getName())
        .regionCode(response.getMostSpecificSubdivision().getIsoCode())
        .postalCode(response.getPostal().getCode())
        .latitude(response.getLocation().getLatitude())
        .longitude(response.getLocation().getLongitude())
        .timezone(response.getLocation().getTimeZone())
        .isp(asnResponse.getAutonomousSystemOrganization())
        .asn(asnResponse.getAutonomousSystemNumber())
        .build());
}
```

#### 3. blockCountry(String countryCode)

Bloque un pays dans Redis

```java
public Mono<Boolean> blockCountry(String countryCode) {
    return redisTemplate.opsForSet()
        .add(BLOCKED_COUNTRIES_KEY, countryCode.toUpperCase())
        .map(added -> {
            if (added > 0) {
                log.info("🚫 Country blocked: {}", countryCode);
                refreshBlockedCountriesMetrics();
            }
            return added > 0;
        });
}
```

#### 4. isIpBlocked(String ip)

Vérifie si une IP est bloquée (via pays)

```java
public Mono<Boolean> isIpBlocked(String ip) {
    return getCountryCode(ip)
        .flatMap(countryCode -> 
            redisTemplate.opsForSet()
                .isMember(BLOCKED_COUNTRIES_KEY, countryCode)
                .map(isBlocked -> {
                    if (Boolean.TRUE.equals(isBlocked)) {
                        geoBlockedRequestsTotal.increment();
                        meterRegistry.counter("geo_blocked_by_country_total", 
                            "country", countryCode).increment();
                    }
                    return isBlocked;
                })
        );
}
```

### GeoAdminController

**Fichier:** `controller/GeoAdminController.java`

**API d'administration GeoIP:**

```bash
# Bloquer un pays
POST /api/geo/block/{countryCode}

# Débloquer un pays
DELETE /api/geo/unblock/{countryCode}

# Liste des pays bloqués
GET /api/geo/blocked

# Infos géographiques d'une IP
GET /api/geo/lookup?ip=8.8.8.8
```

**Exemple de réponse:**
```json
{
  "ip": "8.8.8.8",
  "country": "United States",
  "countryCode": "US",
  "city": "Mountain View",
  "region": "California",
  "regionCode": "CA",
  "postalCode": "94043",
  "latitude": 37.4056,
  "longitude": -122.0775,
  "timezone": "America/Los_Angeles",
  "isp": "Google LLC",
  "asn": 15169
}
```

---

## 📊 Métriques et Observabilité

### Métriques Prometheus

**Endpoint:** `http://localhost:8081/actuator/prometheus`

#### Métriques GeoIP

| Métrique | Type | Description |
|----------|------|-------------|
| `geo_requests_total` | Counter | Total requêtes géolocalisées |
| `geo_blocked_requests_total` | Counter | Total requêtes bloquées par GeoIP |
| `geo_lookup_errors_total` | Counter | Erreurs de lookup GeoIP |
| `geo_requests_by_country_total{country="US"}` | Counter | Requêtes par pays |
| `geo_blocked_by_country_total{country="CN"}` | Counter | Requêtes bloquées par pays |
| `geo_blocked_country_list{country="CN"}` | Gauge | Liste des pays bloqués (1=bloqué) |

#### Métriques Rate Limiting

| Métrique | Type | Description |
|----------|------|-------------|
| `gateway_rate_limit_exceeded_total` | Counter | Requêtes bloquées par rate limit |
| `gateway_rate_limit_requests_total` | Counter | Total requêtes rate limit |

#### Métriques IP Blacklist

| Métrique | Type | Description |
|----------|------|-------------|
| `gateway_ip_blacklist_blocked_total` | Counter | IPs bloquées |

#### Métriques Circuit Breaker

| Métrique | Type | Description |
|----------|------|-------------|
| `resilience4j_circuitbreaker_state{name="auth-service"}` | Gauge | État du circuit breaker (0=CLOSED, 1=OPEN, 2=HALF_OPEN) |
| `resilience4j_circuitbreaker_failure_rate{name="auth-service"}` | Gauge | Taux d'échec (%) |
| `resilience4j_circuitbreaker_calls_total{name="auth-service",kind="successful"}` | Counter | Appels réussis |
| `resilience4j_circuitbreaker_calls_total{name="auth-service",kind="failed"}` | Counter | Appels échoués |

### Tracing Zipkin

**Endpoint:** `http://localhost:9411`

Chaque requête génère un trace avec:
- `traceId`: Identifiant unique du trace
- `spanId`: Identifiant du span (segment)
- Durée de chaque étape (filtres, routing, appel service)

**Exemple de trace:**
```
TraceId: 6978b4a3f4c41273d2880444599f3ffe
├─ Span: gateway-service (150ms)
│  ├─ RedisRateLimitWebFilter (5ms)
│  ├─ GeoLocationWebFilter (10ms)
│  ├─ JwtWebFilter (8ms)
│  ├─ GatewaySignatureFilter (2ms)
│  └─ Routing + Proxification (125ms)
└─ Span: auth-service (120ms)
   ├─ GatewayAccessFilter (3ms)
   ├─ JwtAuthenticationFilter (5ms)
   └─ Business Logic (112ms)
```

### Logs Loki

**Configuration:** `logback-spring.xml`

Les logs sont envoyés à Loki avec:
- `traceId` et `spanId` pour corrélation avec Zipkin
- Niveau de log (INFO, WARN, ERROR)
- Service name: `gateway-service`

**Exemple de log:**
```
2026-01-27 12:48:25.195 [gateway-event-loop-nio-2] DEBUG c.a.g.config.GatewayRoutesConfig 
[traceId=6978b4a3f4c41273d2880444599f3ffe, spanId=91fe683b7815f4e8] 
- ✅ Success - POST http://localhost:8081/api/v1/auth/email/auth-request - Status: 200
```

---

## ⚙️ Configuration

### Variables d'Environnement (.env)

```bash
# JWT
JWT_SECRET=your-super-secret-jwt-key-min-256-bits

# Gateway Security
GATEWAY_SECRET_KEY=gw_secret_key_xyz789
GATEWAY_API_KEY=api_key_abc123

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# GeoIP
GEOIP_DATABASE_PATH=/path/to/GeoLite2-City.mmdb
```

### Configuration Centralisée (Config Server)

**Fichier:** `config-repo/dev/gateway-service-dev.yml`

```yaml
server:
  port: 8081
  netty:
    connection-timeout: 5000
    idle-timeout: 60000

spring:
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    timeout: 2000ms

jwt:
  secret: ${JWT_SECRET}
  expiration: 86400000  # 24 heures

gateway:
  secret:
    key: ${GATEWAY_SECRET_KEY}
  api:
    key: ${GATEWAY_API_KEY}
  rate-limit:
    enabled: true
    requests-per-minute: 50
    burst-capacity: 10
  geo-location:
    enabled: true
    database-path: ${GEOIP_DATABASE_PATH}

resilience4j:
  circuitbreaker:
    instances:
      auth-service:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        sliding-window-size: 10
        minimum-number-of-calls: 5
      user-service:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        sliding-window-size: 10
        minimum-number-of-calls: 5

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    export:
      prometheus:
        enabled: true
  tracing:
    sampling:
      probability: 1.0  # 100% des requêtes tracées
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans

logging:
  level:
    com.abdatytch.gateway_service: DEBUG
    org.springframework.cloud: INFO
    io.github.resilience4j: DEBUG
```

---

## 🔄 Flux de Requête Complet

### Exemple: POST /api/v1/auth/email/auth-request

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. CLIENT                                                        │
│    POST http://localhost:8081/api/v1/auth/email/auth-request   │
│    Content-Type: application/json                               │
│    {"email": "user@example.com"}                                │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 2. NETTY SERVER (Port 8081)                                     │
│    Event Loop détecte la connexion                              │
│    Alloue un Channel et un Buffer                               │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 3. RedisRateLimitWebFilter                                      │
│    ✅ Check: 127.0.0.1 → 3/50 requêtes                          │
│    Action: PASS                                                 │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 4. PathTraversalWebFilter                                       │
│    ✅ Check: Pas de "../" dans le path                          │
│    Action: PASS                                                 │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 5. IpBlacklistWebFilter                                         │
│    ✅ Check: 127.0.0.1 pas dans blacklist Redis                 │
│    Action: PASS                                                 │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 6. GeoLocationWebFilter                                         │
│    ✅ Check: IP locale → Skip GeoIP                             │
│    Action: PASS                                                 │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 7. XssProtectionWebFilter                                       │
│    ✅ Ajoute headers: X-Content-Type-Options, X-Frame-Options   │
│    Action: PASS                                                 │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 8. BodyXssScannerWebFilter                                      │
│    ✅ Scan body: Pas de <script>, javascript:, etc.             │
│    Action: PASS                                                 │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 9. RateLimitWebFilter                                           │
│    ✅ Bucket4j: 95 tokens restants                              │
│    Action: PASS                                                 │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 10. JwtWebFilter                                                │
│     ✅ Endpoint public → Skip JWT validation                    │
│     Action: PASS                                                │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 11. ApiVersionWebFilter                                         │
│     ✅ Version: v1 (actuelle)                                   │
│     Ajoute header: X-API-Version: v1                            │
│     Action: PASS                                                │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 12. GatewaySignatureFilter                                      │
│     Génère:                                                     │
│     - Timestamp: 1738000000000                                  │
│     - RequestId: 550e8400-e29b-41d4-a716-446655440000           │
│     - Path transformé: /auth/email/auth-request                 │
│     - Signature: HMAC-SHA256(secretKey, data)                   │
│     Ajoute headers:                                             │
│     - X-Gateway-Timestamp: 1738000000000                        │
│     - X-Gateway-Signature: a7f3b2c1d4e5...                      │
│     - X-Gateway-Request-Id: 550e8400...                         │
│     - X-Secret-Key: gw_secret_key_xyz789                        │
│     Action: PASS                                                │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 13. GatewayRoutesConfig                                         │
│     Match route: /api/v1/auth/** → auth-service                 │
│     Transform path: /api/v1/auth/email/auth-request             │
│                  → /auth/email/auth-request                     │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 14. Circuit Breaker (auth-service)                              │
│     État: CLOSED (normal)                                       │
│     Action: PASS                                                │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 15. Consul Discovery                                            │
│     Résout: auth-service → http://10.10.10.103:8082             │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 16. WebClient (Proxification)                                   │
│     POST http://10.10.10.103:8082/auth/email/auth-request      │
│     Headers:                                                    │
│     - Content-Type: application/json                            │
│     - X-Gateway-Timestamp: 1738000000000                        │
│     - X-Gateway-Signature: a7f3b2c1d4e5...                      │
│     - X-Gateway-Request-Id: 550e8400...                         │
│     - X-Secret-Key: gw_secret_key_xyz789                        │
│     Body: {"email": "user@example.com"}                         │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 17. AUTH-SERVICE                                                │
│     GatewayAccessFilter:                                        │
│     ✅ IP: 127.0.0.1 (autorisée)                                │
│     ✅ Timestamp: < 5 secondes                                  │
│     ✅ Signature HMAC: valide                                   │
│     Business Logic:                                             │
│     - Génère code de vérification                               │
│     - Envoie email                                              │
│     Response: 200 OK                                            │
│     {"message": "Code envoyé", "expiresIn": 300}                │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 18. WebClient (Réception)                                       │
│     Status: 200 OK                                              │
│     Body: {"message": "Code envoyé", "expiresIn": 300}          │
│     Log: ✅ Success - POST /api/v1/auth/email/auth-request     │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 19. Circuit Breaker                                             │
│     Enregistre: SUCCESS                                         │
│     Failure Rate: 0% (reste CLOSED)                             │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 20. NETTY SERVER                                                │
│     Retourne réponse au client                                  │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ 21. CLIENT                                                      │
│     Reçoit: 200 OK                                              │
│     {"message": "Code envoyé", "expiresIn": 300}                │
└─────────────────────────────────────────────────────────────────┘
```

**Durée totale:** ~150ms
- Filtres: ~30ms
- Routing + Discovery: ~20ms
- Proxification: ~100ms

---

## 📦 Dépendances

### Principales Dépendances

| Dépendance | Version | Rôle |
|------------|---------|------|
| **Spring Boot** | 4.0.1 | Framework principal |
| **Spring WebFlux** | 4.0.1 | Serveur réactif Netty |
| **Spring Cloud Config** | 2025.1.0 | Configuration centralisée |
| **Spring Cloud Consul** | 2025.1.0 | Service Discovery |
| **Spring Security** | 4.0.1 | Sécurité |
| **JJWT** | 0.12.5 | JWT parsing/validation |
| **Resilience4j** | 2.1.0 | Circuit Breaker + Rate Limiting |
| **Bucket4j** | 8.10.1 | Rate Limiting local |
| **Redis** | - | Cache distribué |
| **MaxMind GeoIP2** | 5.0.2 | Géolocalisation IP |
| **OWASP HTML Sanitizer** | 20220608.1 | Protection XSS |
| **Micrometer** | - | Métriques Prometheus |
| **Zipkin** | - | Distributed Tracing |
| **Loki** | 2.0.2 | Logs centralisés |
| **SpringDoc OpenAPI** | 2.7.0 | Documentation Swagger |

### Dépendances Complètes (pom.xml)

```xml
<dependencies>
    <!-- Spring Boot Starters -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    
    <!-- Spring Cloud -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-config</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-consul-discovery</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-loadbalancer</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-openfeign</artifactId>
    </dependency>
    
    <!-- JWT -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.5</version>
    </dependency>
    
    <!-- Resilience4j -->
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-spring-boot3</artifactId>
        <version>2.1.0</version>
    </dependency>
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-circuitbreaker</artifactId>
        <version>2.1.0</version>
    </dependency>
    
    <!-- Rate Limiting -->
    <dependency>
        <groupId>com.bucket4j</groupId>
        <artifactId>bucket4j-core</artifactId>
        <version>8.10.1</version>
    </dependency>
    
    <!-- Observabilité -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-tracing-bridge-brave</artifactId>
    </dependency>
    <dependency>
        <groupId>io.zipkin.reporter2</groupId>
        <artifactId>zipkin-reporter-brave</artifactId>
    </dependency>
    <dependency>
        <groupId>com.github.loki4j</groupId>
        <artifactId>loki-logback-appender</artifactId>
        <version>2.0.2</version>
    </dependency>
    
    <!-- Sécurité -->
    <dependency>
        <groupId>com.googlecode.owasp-java-html-sanitizer</groupId>
        <artifactId>owasp-java-html-sanitizer</artifactId>
        <version>20220608.1</version>
    </dependency>
    <dependency>
        <groupId>com.maxmind.geoip2</groupId>
        <artifactId>geoip2</artifactId>
        <version>5.0.2</version>
    </dependency>
    
    <!-- Documentation -->
    <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
        <version>2.7.0</version>
    </dependency>
    
    <!-- Utilitaires -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>1.18.30</version>
    </dependency>
    <dependency>
        <groupId>io.github.cdimascio</groupId>
        <artifactId>dotenv-java</artifactId>
        <version>3.0.0</version>
    </dependency>
</dependencies>
```

---

## 🚀 Démarrage

### Prérequis

1. **Java 17+**
2. **Maven 3.9+**
3. **Redis** (localhost:6379)
4. **Consul** (localhost:8500)
5. **Config Server** (localhost:8888)
6. **Base GeoIP2** (`GeoLite2-City.mmdb`)

### Commandes

```bash
# 1. Compiler
mvn clean package -DskipTests

# 2. Démarrer
mvn spring-boot:run

# 3. Vérifier santé
curl http://localhost:8081/actuator/health

# 4. Accéder à Swagger
open http://localhost:8081/swagger-ui.html

# 5. Voir métriques Prometheus
curl http://localhost:8081/actuator/prometheus
```

---

## 📝 Résumé

Le **Gateway Service** est un composant critique qui:

✅ **Route** intelligemment les requêtes vers les microservices  
✅ **Sécurise** avec 10 filtres de protection (XSS, Rate Limit, GeoIP, etc.)  
✅ **Authentifie** via JWT  
✅ **Protège** avec signatures HMAC pour communications inter-services  
✅ **Limite** le trafic avec Rate Limiting distribué (Redis) et local (Bucket4j)  
✅ **Bloque** les attaques (Path Traversal, IP Blacklist, pays interdits)  
✅ **Monitore** avec métriques Prometheus et traces Zipkin  
✅ **Résiliente** avec Circuit Breaker Resilience4j  
✅ **Performant** avec architecture réactive Netty (10,000+ req/s)  

**Architecture:** Spring WebFlux + Netty + Consul + Redis + Resilience4j  
**Port:** 8081  
**Observabilité:** Prometheus + Grafana + Zipkin + Loki  

---

**Auteur:** Restart  
**Version:** 1.0.0  
**Date:** 27 Janvier 2026
