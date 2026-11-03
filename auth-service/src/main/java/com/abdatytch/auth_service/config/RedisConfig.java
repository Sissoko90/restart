package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.constants.ConfigConstants;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
@Slf4j
public class RedisConfig implements CachingConfigurer {
    
    @Bean
    public GenericJackson2JsonRedisSerializer jsonRedisSerializer() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        
        // Activer le typage polymorphique pour éviter les erreurs de désérialisation
        BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
            .allowIfBaseType(Object.class)
            .build();
        
        objectMapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        
        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }
    
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = jsonRedisSerializer();
        
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(jsonSerializer);
        
        template.afterPropertiesSet();
        return template;
    }
    
    /**
     * ReactiveRedisTemplate pour opérations Redis non-bloquantes
     * Utilisé par TokenBlacklistService pour blacklist JWT
     */
    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory reactiveConnectionFactory) {
        
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        
        RedisSerializationContext<String, String> serializationContext = 
            RedisSerializationContext.<String, String>newSerializationContext(stringSerializer)
                .key(stringSerializer)
                .value(stringSerializer)
                .hashKey(stringSerializer)
                .hashValue(stringSerializer)
                .build();
        
        return new ReactiveRedisTemplate<>(reactiveConnectionFactory, serializationContext);
    }
    
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Configuration par défaut avec gestion d'erreurs
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonRedisSerializer()))
                .disableCachingNullValues()
                .prefixCacheNameWith("auth-service::");
        
        // Configuration spécifique pour les codes de vérification (3 minutes - aligné avec expiration DB)
        RedisCacheConfiguration verificationCodesConfig = defaultConfig
                .entryTtl(Duration.ofMinutes(3));
        
        // Configuration spécifique pour les refresh tokens (DÉSACTIVÉ - géré en DB uniquement)
        // Les refresh tokens ne doivent PAS être cachés car ils sont révoqués/validés en temps réel
        
        // Configuration spécifique pour les JWT tokens (DÉSACTIVÉ - stateless)
        // Les JWT sont stateless et ne doivent pas être cachés
        // La blacklist est gérée directement dans Redis avec TTL
        
        // Configuration spécifique pour les tentatives de connexion (15 minutes)
        RedisCacheConfiguration loginAttemptsConfig = defaultConfig
                .entryTtl(Duration.ofMinutes(15));
        
        // Configuration spécifique pour les templates de messages (24 heures - rarement modifiés)
        RedisCacheConfiguration messageTemplatesConfig = defaultConfig
                .entryTtl(Duration.ofHours(24));
        
        log.info(ConfigConstants.LOG_INFO_REDIS_CACHE_MANAGER_CONFIG);
        
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration("verificationCodes", verificationCodesConfig)
                .withCacheConfiguration("loginAttempts", loginAttemptsConfig)
                .withCacheConfiguration("messageTemplates", messageTemplatesConfig)
                .transactionAware()
                .build();
    }
    
    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new CustomCacheErrorHandler();
    }
    
    /**
     * Gestionnaire d'erreurs personnalisé pour le cache Redis
     * Permet à l'application de continuer même si Redis est indisponible
     */
    private static class CustomCacheErrorHandler implements CacheErrorHandler {
        
        @Override
        public void handleCacheGetError(RuntimeException exception, org.springframework.cache.Cache cache, Object key) {
            log.warn(ConfigConstants.LOG_WARN_CACHE_GET_ERROR, 
                cache.getName(), key, exception.getMessage());
        }
        
        @Override
        public void handleCachePutError(RuntimeException exception, org.springframework.cache.Cache cache, Object key, Object value) {
            log.warn(ConfigConstants.LOG_WARN_CACHE_PUT_ERROR, 
                cache.getName(), key, exception.getMessage());
        }
        
        @Override
        public void handleCacheEvictError(RuntimeException exception, org.springframework.cache.Cache cache, Object key) {
            log.warn(ConfigConstants.LOG_WARN_CACHE_EVICT_ERROR, 
                cache.getName(), key, exception.getMessage());
        }
        
        @Override
        public void handleCacheClearError(RuntimeException exception, org.springframework.cache.Cache cache) {
            log.warn(ConfigConstants.LOG_WARN_CACHE_CLEAR_ERROR, 
                cache.getName(), exception.getMessage());
        }
    }
}
