package com.abdatytch.interface_service.filter;

/**
 * Ordre d'exécution des filtres de sécurité d'interface-service.
 *
 * <h2>Pourquoi l'ordre est une propriété de sécurité ici</h2>
 *
 * {@code IpBlacklistFilter} et {@code RateLimitFilter} décident à partir de
 * l'adresse rendue par {@code GatewayClientIp.of()}, qui lit l'en-tête
 * {@code X-Client-Ip}. C'est la bonne source — ces deux filtres veulent
 * l'adresse de l'<b>utilisateur final</b>, pas celle du Gateway — mais cet
 * en-tête n'est digne de confiance que parce que {@code GatewayAccessFilter} a
 * d'abord établi que la requête vient réellement du Gateway, lequel purge et
 * repose cet en-tête.
 *
 * <p>Si l'un de ces deux filtres s'exécutait <b>avant</b> le contrôle de
 * provenance, un appelant direct choisirait son adresse en posant l'en-tête
 * lui-même : liste noire contournée en changeant de valeur, plafond de débit
 * contourné en la faisant tourner.
 *
 * <h2>Ce qui était fragile</h2>
 *
 * Quatre des huit filtres n'avaient <b>aucun</b> {@code @Order} —
 * {@code IpBlacklistFilter}, {@code XssProtectionFilter},
 * {@code MaintenanceJwtFilter} et {@code CspFilter}. Spring trie les beans non
 * ordonnés en dernier, à {@code LOWEST_PRECEDENCE}, et entre eux dans l'ordre
 * du balayage du classpath.
 *
 * <p>L'invariant ci-dessus tenait donc, mais <b>par accident</b> : il suffisait
 * que quelqu'un annote {@code IpBlacklistFilter} d'un {@code @Order(0)} —
 * geste naturel, puisqu'on veut refuser une adresse hostile le plus tôt
 * possible — pour que la liste noire se mette à faire confiance à un en-tête
 * non vérifié. Rien n'aurait échoué, rien n'aurait été journalisé.
 *
 * <p>Les valeurs sont espacées de dix pour qu'une insertion ne force pas à tout
 * renuméroter. {@code OrdreDesFiltresTest} vérifie que la séquence déclarée est
 * bien celle appliquée.
 */
public final class OrdreDesFiltres {

    /**
     * La provenance d'abord : tout ce qui suit lui fait confiance.
     *
     * <p>Aucun filtre lisant {@code X-Client-Ip} ne doit passer devant.
     */
    public static final int ACCES_GATEWAY = 10;

    /**
     * Refuser une adresse réputée hostile avant d'engager le moindre travail.
     *
     * <p>Après {@code ACCES_GATEWAY}, parce que l'adresse consultée vient de
     * l'en-tête que le Gateway repose.
     */
    public static final int LISTE_NOIRE_IP = 20;

    /**
     * Plafonner avant de lire un corps de requête.
     *
     * <p>Un compteur Redis coûte moins qu'une lecture de corps : limiter
     * d'abord évite de lire les charges d'un client qui inonde.
     */
    public static final int LIMITATION_DEBIT = 30;

    /** Refuser une charge compressée disproportionnée avant de la décompresser. */
    public static final int BOMBE_ZIP = 40;

    /** Détection d'injection : signale, ne décide pas d'un accès. */
    public static final int PROTECTION_XSS = 50;

    /** Garde les endpoints de maintenance : une autorisation, donc après les refus. */
    public static final int JWT_MAINTENANCE = 60;

    /** Garde les endpoints d'administration par secret partagé. */
    public static final int SECRET_ADMIN = 70;

    /** En-têtes de réponse : rien n'en dépend, donc en dernier. */
    public static final int CSP = 80;

    private OrdreDesFiltres() {
    }
}
