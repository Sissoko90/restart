# Guide - Gestion des Rôles Multiples

## Vue d'ensemble

Le système supporte désormais la gestion de **rôles multiples** par utilisateur avec dispatching automatique basé sur le JWT.

## Architecture

### 1. Auth-Service (Génération JWT)

**Fichier**: `JwtServiceImpl.java`

Le JWT généré contient :
- **`roles`** : Tous les rôles actifs de l'utilisateur (string ou array)
- **`primaryRole`** : Le rôle principal de l'utilisateur
- **`userId`** : L'ID de l'utilisateur
- **`phone`** : Le numéro de téléphone

**Exemples de JWT** :

```json
// Utilisateur avec un seul rôle
{
  "userId": "123",
  "phone": "+33612345678",
  "roles": "ROLE_USER",
  "primaryRole": "ROLE_USER"
}

// Utilisateur avec plusieurs rôles
{
  "userId": "456",
  "phone": "+33687654321",
  "roles": ["ROLE_USER", "ROLE_ADMIN"],
  "primaryRole": "ROLE_ADMIN"
}

// Token de service inter-microservices
{
  "userId": "service-auth",
  "roles": "ROLE_SERVICE",
  "serviceName": "auth-service"
}
```

### 2. User-Service (Validation JWT)

**Fichier**: `SecurityConfig.java`

La configuration de sécurité extrait automatiquement les rôles du JWT :

```java
// Gère à la fois :
// - Un seul rôle : "ROLE_USER"
// - Plusieurs rôles : ["ROLE_USER", "ROLE_ADMIN"]

// Ajoute automatiquement le prefix ROLE_ si absent
```

## Configuration des Endpoints

### Endpoints Publics
```java
.pathMatchers("/actuator/**").permitAll()
.pathMatchers("/v3/api-docs/**").permitAll()
.pathMatchers(HttpMethod.GET, "/files/**").permitAll()
```

### Endpoints Internes (Inter-services)
```java
.pathMatchers("/internal/**").hasRole("SERVICE")
```
- Utilisé par `auth-service` pour créer/synchroniser les utilisateurs
- Requiert un token de service avec `ROLE_SERVICE`

### Endpoints Admin
```java
.pathMatchers("/admin/**").hasRole("ADMIN")
```
- Accessible uniquement aux utilisateurs avec `ROLE_ADMIN`

### Endpoints Utilisateur
```java
.anyExchange().authenticated()
```
- Tous les autres endpoints nécessitent une authentification
- Accessible avec n'importe quel rôle valide

## Annotations de Sécurité

### @PreAuthorize sur les méthodes

```java
// Authentification simple
@PreAuthorize("isAuthenticated()")
public Mono<ResponseEntity<UserDetailDTO>> getCurrentUser() { }

// Rôle spécifique
@PreAuthorize("hasRole('ADMIN')")
public Mono<ResponseEntity<Flux<UserDetailDTO>>> getAllUsers() { }

// Plusieurs rôles (OR)
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
public Mono<ResponseEntity<UserDetailDTO>> getUserById() { }

// Combinaison (AND)
@PreAuthorize("hasAnyRole('USER', 'ADMIN') and hasRole('ADMIN')")
public Mono<ResponseEntity<UserDetailDTO>> updateUser() { }
```

## Dispatching Automatique

Le dispatching des rôles se fait automatiquement :

1. **JWT reçu** → Extraction du claim `roles`
2. **Conversion** → String ou List → GrantedAuthority
3. **Ajout prefix** → `ROLE_` si absent
4. **Validation** → Spring Security vérifie les permissions

### Exemple de flux

```
Client → Request avec JWT
    ↓
Gateway → Validation basique
    ↓
User-Service → SecurityConfig
    ↓
reactiveJwtAuthenticationConverter()
    ↓
Extraction roles: ["ROLE_USER", "ROLE_ADMIN"]
    ↓
Création authorities: [ROLE_USER, ROLE_ADMIN]
    ↓
@PreAuthorize("hasRole('ADMIN')") → ✅ AUTORISÉ
```

## Gestion des Rôles dans Auth-Service

### Créer un utilisateur avec plusieurs rôles

```java
CreateUserWithRolesRequest request = new CreateUserWithRolesRequest();
request.setEmail("user@example.com");
request.setRoles(Set.of(UserRole.USER, UserRole.ADMIN));
request.setPrimaryRole(UserRole.ADMIN);

userRoleManagementService.createUserWithRoles(request);
```

### Ajouter un rôle à un utilisateur existant

```java
userRoleManagementService.addRoleToUser(userId, UserRole.MODERATOR);
```

### Supprimer un rôle

```java
userRoleManagementService.removeRoleFromUser(userId, UserRole.MODERATOR);
```

### Changer le rôle principal

```java
userRoleManagementService.changePrimaryRole(userId, UserRole.ADMIN);
```

## Synchronisation avec User-Service

Lorsqu'un utilisateur est créé ou ses rôles modifiés dans `auth-service`, la synchronisation se fait automatiquement via Kafka :

1. **Auth-Service** → Crée/modifie utilisateur
2. **Kafka Event** → `UserCreatedEvent` ou `UserUpdatedEvent`
3. **User-Service** → Consomme l'événement
4. **UserEventListener** → Crée/met à jour le profil

## Tokens de Service

Pour les appels inter-services :

```java
// Générer un token de service
String serviceToken = jwtService.generateServiceToken();

// Utiliser dans les headers
headers.put("Authorization", "Bearer " + serviceToken);

// Le token contient ROLE_SERVICE
// Permet d'accéder aux endpoints /internal/**
```

## Sécurité

### Prefix ROLE_

- **Spring Security** attend le prefix `ROLE_` pour `hasRole()`
- **Notre système** ajoute automatiquement le prefix si absent
- **JWT** peut contenir avec ou sans prefix

### Validation

1. **Signature RSA** : Vérification avec clé publique
2. **Expiration** : Vérification de la date d'expiration
3. **Blacklist** : Vérification dans Redis si token révoqué
4. **Issuer** : Vérification que le token vient de `auth-service`

## Debugging

### Vérifier les rôles extraits

Ajouter des logs dans `SecurityConfig` :

```java
log.info("Roles extraits du JWT: {}", authorities);
```

### Vérifier le JWT

Utiliser [jwt.io](https://jwt.io) pour décoder le token et voir les claims.

### Tester les permissions

```bash
# Token avec ROLE_USER
curl -H "Authorization: Bearer $USER_TOKEN" \
  http://localhost:8083/api/v1/users/me

# Token avec ROLE_ADMIN
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8083/api/v1/admin/users

# Token de service
curl -H "Authorization: Bearer $SERVICE_TOKEN" \
  http://localhost:8083/api/v1/internal/users/auth/123
```

## Résumé

✅ **Auth-Service** : Génère JWT avec tous les rôles actifs  
✅ **User-Service** : Extrait et valide les rôles automatiquement  
✅ **Spring Security** : Dispatche les requêtes selon les rôles  
✅ **Rôles multiples** : Supportés nativement  
✅ **Inter-services** : Token SERVICE pour communication interne  
✅ **Réactif** : Architecture 100% non-bloquante avec WebFlux
