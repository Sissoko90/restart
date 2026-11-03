package com.abdatytch.gateway_service.security;

import com.abdatytch.commons.security.AdresseIpLitterale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**

 * Détermine l'adresse IP réelle du client.
 *
 * <p><b>Pourquoi cette classe existe.</b> Les en-têtes {@code X-Forwarded-For} et
 * {@code X-Real-IP} sont écrits par le client autant que par un proxy : rien ne
 * les distingue à l'arrivée. Les lire sans condition — ce que faisaient les
 * quatre filtres du Gateway — permet à n'importe qui d'annoncer l'IP de son
 * choix, et donc :
 * <ul>
 * <li>d'obtenir un compteur de rate limiting neuf à chaque requête, en
 * changeant l'en-tête à chaque fois ;</li>
 * <li>de contourner la liste noire d'IP en s'annonçant sous une autre ;</li>
 * <li>de contourner le blocage géographique ;</li>
 * <li>d'attribuer ses propres violations à l'IP d'un tiers, et de la faire
 * blacklister à sa place.</li>
 * </ul>
 *
 * <p><b>Règle appliquée.</b> Ces en-têtes ne sont pris en compte que si le pair
 * TCP immédiat figure dans {@code gateway.trusted-proxies}. Par défaut la liste
 * est vide : aucun en-tête n'est cru, seule l'adresse de la socket fait foi.
 * C'est le comportement correct quand le Gateway est exposé directement.
 *
 * <p><b>À configurer en production.</b> Derrière un ALB, Nginx ou Cloudflare,
 * renseigner les plages du proxy — sinon toutes les requêtes seront comptées
 * sous l'IP unique du proxy et le rate limiting deviendra un plafond global :
 * <pre>
 * gateway:
 * trusted-proxies: 10.0.0.0/8,172.16.0.0/12
 * </pre>
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    public static final String UNKNOWN = "unknown";

    private final List<CidrRange> trustedProxies = new ArrayList<>();

    public ClientIpResolver(@Value("${gateway.trusted-proxies:}") String trustedProxiesCsv) {
        if (trustedProxiesCsv != null && !trustedProxiesCsv.isBlank()) {
            for (String cidr : trustedProxiesCsv.split(",")) {
                String trimmed = cidr.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    trustedProxies.add(CidrRange.parse(trimmed));
                } catch (IllegalArgumentException e) {
                    // Une entrée illisible ne doit pas élargir la confiance :
                    // on l'ignore, on la signale, et on continue.
                    log.error(" Plage de proxy de confiance invalide, ignorée : '{}'", trimmed);
                }
            }
        }
        log.info(" ClientIpResolver : {} plage(s) de proxy de confiance. "
                    + "X-Forwarded-For {}.", trustedProxies.size(),
                trustedProxies.isEmpty() ? "sera IGNORÉ" : "sera honoré depuis ces plages");
    }

    /**

     * @return l'IP du client, ou {@link #UNKNOWN} si elle ne peut être établie.
     * La valeur est toujours de forme IP : elle peut servir de clé Redis
     * et être journalisée sans risque d'injection.
     */
    public String resolve(ServerWebExchange exchange) {
        String peer = peerAddress(exchange);

        if (peer == null) {
            return UNKNOWN;
        }
        if (!isTrustedProxy(peer)) {
            // Pair non fiable : ses en-têtes de transfert sont ignorés.
            return peer;
        }

        String forwarded = firstUntrustedInChain(
                exchange.getRequest().getHeaders().get("X-Forwarded-For"));
        if (forwarded != null) {
            return forwarded;
        }

        String realIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (realIp != null && isIpShaped(realIp.trim())) {
            return realIp.trim();
        }

        return peer;
    }

    private String peerAddress(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return null;
        }
        return remote.getAddress().getHostAddress();
    }

    /**

     * Parcourt {@code X-Forwarded-For} de DROITE à GAUCHE et retourne la première
     * entrée qui n'est pas un proxy de confiance.
     *
     * <p>La lecture de gauche à droite — celle du code précédent — prend la
     * valeur que le client contrôle entièrement : c'est lui qui écrit le premier
     * élément de la chaîne. Seules les entrées ajoutées par nos propres proxys,
     * en fin de chaîne, sont dignes de foi.
     */
    private String firstUntrustedInChain(List<String> headerValues) {
        if (headerValues == null || headerValues.isEmpty()) {
            return null;
        }

        List<String> hops = new ArrayList<>();
        headerValues.forEach(value -> Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(hop -> !hop.isEmpty())
                .forEach(hops::add));

        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = stripPort(hops.get(i));
            if (!isIpShaped(hop)) {
                // Chaîne malformée : on ne devine pas, on retombe sur le pair TCP.
                return null;
            }
            if (!isTrustedProxy(hop)) {
                return hop;
            }
        }

        // Tous les sauts sont des proxys de confiance : le client est le premier.
        String first = stripPort(hops.get(0));
        return isIpShaped(first) ? first : null;
    }

    /** Retire un éventuel port (« 203.0.113.7:41234 »), en épargnant les IPv6 nues. */
    private String stripPort(String hop) {
        int lastColon = hop.lastIndexOf(':');
        if (lastColon > 0 && hop.indexOf(':') == lastColon) {
            return hop.substring(0, lastColon);
        }
        return hop;
    }

    private boolean isTrustedProxy(String ip) {
        byte[] address = toBytes(ip);
        if (address == null) {
            return false;
        }
        for (CidrRange range : trustedProxies) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private boolean isIpShaped(String value) {
        return toBytes(value) != null;
    }

    /**

     * Convertit une adresse littérale en octets.
     *
     * <p><b>Pourquoi ce corps a déménagé dans platform-commons.</b> La version
     * précédente validait la <i>forme</i> — quatre groupes de un à trois
     * chiffres — puis confiait la lecture à {@code InetAddress.getByName}. Or
     * {@code 999.1.1.1} satisfait cette forme sans être une adresse : le JDK
     * échoue à la lire et part alors interroger le <b>résolveur DNS</b>. Mesuré
     * sur JDK 17.0.12 : 2 ms pour vingt littéraux valides, <b>2258 ms</b> pour
     * vingt formes invalides, soit ~113 ms par appel.
     *
     * <p>Cette méthode est appelée depuis {@link #firstUntrustedInChain} sur
     * chaque saut de {@code X-Forwarded-For} — donc sur une valeur que
     * l'appelant écrit, sur le chemin de la requête, sur une EventLoop Netty.
     * Un en-tête portant une liste de formes invalides immobilisait une
     * EventLoop pendant plus de cent millisecondes par saut. Le commentaire
     * d'origine affirmait précisément l'inverse : la validation « garantit que
     * {@code getByName} ne déclenchera jamais de résolution DNS ». Elle ne le
     * garantissait que pour les valeurs bien formées.
     *
     * <p>{@link AdresseIpLitterale} valide chaque octet <b>avant</b> de
     * construire quoi que ce soit, et n'appelle {@code getByName} que sur une
     * chaîne contenant un deux-points — cas où le JDK lit un littéral IPv6 et
     * échoue sans consulter le résolveur. Le résultat est identique à octet
     * près ; seul le chemin DNS disparaît.
     *
     * @return {@code null} si la valeur n'est pas une adresse IP littérale — ce
     * qui vaut refus : on ne devine jamais, et aucune résolution DNS
     * n'est déclenchée depuis une donnée fournie par le client.
     */
    private static byte[] toBytes(String value) {
        return AdresseIpLitterale.octets(value);
    }

    /**

     * Plage d'adresses au format CIDR.
     *
     * <p>Écrite ici plutôt qu'empruntée à {@code IpAddressMatcher} de Spring
     * Security : cette classe implémente {@code RequestMatcher} et entraîne donc
     * l'API Servlet, absente d'une application WebFlux.
     */
    private record CidrRange(byte[] network, int prefixBits) {

        static CidrRange parse(String spec) {
            int slash = spec.indexOf('/');
            String addressPart = slash < 0 ? spec : spec.substring(0, slash);

            byte[] address = toBytes(addressPart);
            if (address == null) {
                throw new IllegalArgumentException("Adresse invalide : " + spec);
            }

            int maxBits = address.length * 8;
            int bits = maxBits;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(spec.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Préfixe invalide : " + spec);
                }
                if (bits < 0 || bits > maxBits) {
                    throw new IllegalArgumentException("Préfixe hors bornes : " + spec);
                }
            }
            return new CidrRange(address, bits);
        }

        boolean contains(byte[] candidate) {
            // Une plage IPv4 ne peut pas contenir une adresse IPv6, et
            // réciproquement : les longueurs doivent correspondre.
            if (candidate.length != network.length) {
                return false;
            }

            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }

            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
