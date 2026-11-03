package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.service.CacheService;
import com.abdatytch.media_service.service.GeoLocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Wrapper pour GeoLocationService avec cache Redis
 * Évite les appels répétés à MaxMind pour la même IP
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GeoLocationCacheServiceImpl {

    private final GeoLocationService geoLocationService;
    private final CacheService cacheService;
    
    @Value("${cache.redis.ttl.geolocation:86400}")
    private long geoLocationCacheTtl;

    public Mono<GeoLocationService.GeoLocationInfo> getLocationFromIpWithCache(String ipAddress) {
        String cacheKey = "geolocation:" + ipAddress;
        
        return cacheService.getOrCompute(
            cacheKey,
            GeoLocationService.GeoLocationInfo.class,
            geoLocationService.getLocationFromIp(ipAddress),
            Duration.ofSeconds(geoLocationCacheTtl)
        ).doOnSuccess(info -> log.debug("GeoLocation retrieved for IP: {} (cached)", ipAddress));
    }
}
