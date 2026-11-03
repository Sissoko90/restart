package com.abdatytch.commons.security;

/**
 * Les plages d'adresses depuis lesquelles un service de la plateforme peut
 * légitimement être appelé par le Gateway.
 *
 * <h2>Pourquoi cette classe est partagée</h2>
 *
 * Chaque service a son filtre d'accès Gateway, et chacun décidait seul de ce
 * qu'est une adresse « interne ». Les deux versions trouvées divergeaient
 * gravement :
 *
 * <ul>
 *   <li>auth-service acceptait {@code ip.startsWith("172.")}, soit
 *       <b>172.0.0.0/8</b> — 16,7 millions d'adresses, dont l'essentiel est de
 *       l'espace <b>public routable</b> ; 172.217.x.x appartient à Google ;</li>
 *   <li>interface-service n'acceptait que {@code 127.0.0.1}, {@code ::1} et
 *       <b>{@code 10.10.10.47}</b> — une adresse de machine écrite en dur, donc
 *       un service injoignable dès que le Gateway change d'adresse, et
 *       notamment dès qu'il tourne en conteneur.</li>
 * </ul>
 *
 * Deux copies, deux erreurs opposées : l'une beaucoup trop large, l'autre
 * beaucoup trop étroite. C'est le motif que {@code JwtClaimsReader} a déjà
 * corrigé en remplaçant quatre lectures divergentes du claim de rôle.
 *
 * <h2>Ce qui est accepté</h2>
 *
 * Les trois plages privées de la RFC 1918, la boucle locale, et les adresses
 * IPv6 équivalentes. Rien d'autre.
 *
 * <p>{@code 10.0.0.0/8} et {@code 192.168.0.0/16} se vérifient correctement par
 * préfixe de chaîne — elles sont entièrement privées. {@code 172} <b>non</b> :
 * seul 172.<b>16</b> à 172.<b>31</b> l'est, ce qui impose de lire le second
 * octet. C'est précisément la plage que Docker utilise par défaut pour ses
 * réseaux de ponts.
 */
public final class PrivateNetworks {

    private PrivateNetworks() {
    }

    /**
     * L'adresse est-elle sur la boucle locale ou un réseau privé ?
     *
     * @param ip adresse en notation textuelle, telle que rendue par
     *           {@code getHostAddress()}. {@code null} et toute forme
     *           inattendue rendent {@code false} : ne pas savoir d'où vient un
     *           appel n'est pas une raison de l'accepter.
     */
    public static boolean contient(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String adresse = ip.trim();

        if (estBoucleLocale(adresse)) {
            return true;
        }
        if (adresse.startsWith("10.") || adresse.startsWith("192.168.")) {
            return true;
        }
        return estPlagePriveeDe172(adresse);
    }

    /** Boucle locale IPv4 et IPv6, y compris la forme étendue. */
    private static boolean estBoucleLocale(String adresse) {
        return adresse.startsWith("127.")
                || "::1".equals(adresse)
                || "0:0:0:0:0:0:0:1".equals(adresse);
    }

    /**
     * 172.16.0.0/12, et elle seule.
     *
     * <p>La vérification porte sur le <b>second octet</b>, et non sur un préfixe
     * de chaîne : {@code startsWith("172.")} acceptait 172.0 à 172.255, donc
     * 93 % d'espace public.
     */
    private static boolean estPlagePriveeDe172(String adresse) {
        if (!adresse.startsWith("172.")) {
            return false;
        }
        String[] octets = adresse.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            int second = Integer.parseInt(octets[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException e) {
            // Une adresse non numerique n'est pas une adresse privee. Elle ne
            // doit pas non plus faire echouer la requete en 500 : refuser suffit.
            return false;
        }
    }
}
