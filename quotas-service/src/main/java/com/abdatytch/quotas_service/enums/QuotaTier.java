package com.abdatytch.quotas_service.enums;

/**
 * Palier commercial d'un utilisateur.
 *
 * <p>Chaque palier correspond à un bloc de limites dans la configuration
 * ({@code quotas.default}, {@code quotas.premium}, {@code quotas.enterprise}).
 */
public enum QuotaTier {

    /** Palier gratuit, attribué par défaut à tout nouvel utilisateur. */
    FREE,

    /** Palier payant intermédiaire. */
    PREMIUM,

    /** Palier entreprise. */
    ENTERPRISE;

    /**
     * Palier appliqué quand aucun quota n'a encore été enregistré pour l'utilisateur.
     */
    public static QuotaTier defaultTier() {
        return FREE;
    }
}
