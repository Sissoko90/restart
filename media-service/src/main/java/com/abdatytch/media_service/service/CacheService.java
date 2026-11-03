package com.abdatytch.media_service.service;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Service de cache Redis pour optimiser les performances
 */
public interface CacheService {
    
    /**
     * Récupère une valeur du cache
     */
    <T> Mono<T> get(String key, Class<T> type);
    
    /**
     * Stocke une valeur dans le cache avec TTL par défaut
     */
    <T> Mono<Boolean> set(String key, T value);
    
    /**
     * Stocke une valeur dans le cache avec TTL personnalisé
     */
    <T> Mono<Boolean> set(String key, T value, Duration ttl);
    
    /**
     * Supprime une clé du cache
     */
    Mono<Boolean> delete(String key);
    
    /**
     * Supprime toutes les clés correspondant au pattern
     */
    Mono<Long> deletePattern(String pattern);
    
    /**
     * Vérifie si une clé existe
     */
    Mono<Boolean> exists(String key);
    
    /**
     * Récupère ou calcule une valeur (cache-aside pattern)
     */
    <T> Mono<T> getOrCompute(String key, Class<T> type, Mono<T> supplier, Duration ttl);
}
