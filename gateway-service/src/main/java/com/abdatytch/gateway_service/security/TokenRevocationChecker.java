package com.abdatytch.gateway_service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**

 * Consulte la liste de révocation publiée par l'autorité JWT (interface-service).
 *
 * <p><b>Pourquoi au Gateway.</b> Une signature RSA valide prouve seulement que
 * l'autorité a émis le jeton — pas qu'il est encore en vigueur. Sans cette
 * vérification, une déconnexion, une révocation d'urgence ou la suspension d'un
 * compte restaient sans effet sur tout le trafic entrant : le jeton continuait
 * d'ouvrir chaque route jusqu'à son expiration naturelle. L'autorité vérifiait
 * bien la révocation, mais uniquement pour les requêtes qui lui étaient
 * destinées ; celles routées vers user-service, media-service ou quotas-service
 * ne la traversaient jamais.
 *
 * <p><b>Contrat de données.</b> Le Gateway est ici <b>lecteur seul</b> d'un
 * espace de clés dont interface-service est le seul écrivain :
 * <ul>
 * <li>{@code dev:token:blacklist:<jti>} — un jeton précis, révoqué jusqu'à sa
 * date d'expiration ;</li>
 * <li>{@code dev:revoked:<sub>} — tous les jetons d'un porteur.</li>
 * </ul>
 * Ces clés vivent dans la base Redis de l'autorité, pas dans celle du Gateway :
 * d'où {@code gateway.shared-security.redis-database}, qui doit rester aligné
 * sur {@code spring.data.redis.database} d'interface-service.
 *
 * <p><b>En cas de panne Redis</b>, le comportement par défaut est de refuser
 * ({@code gateway.revocation.fail-open=false}) : une révocation ignorée est une
 * porte ouverte, et mieux vaut une interruption visible qu'un contrôle qui
 * s'efface en silence. Basculer ce drapeau privilégie la disponibilité — c'est
 * un choix légitime, mais il doit être conscient.
 */
@Component
public class TokenRevocationChecker {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationChecker.class);

    private final ReactiveRedisTemplate<String, String> revocationRedis;
    private final boolean enabled;
    private final boolean failOpen;
    private final String tokenPrefix;
    private final String subjectPrefix;

    public TokenRevocationChecker(
            @Qualifier("sharedSecurityRedisTemplate") ReactiveRedisTemplate<String, String> revocationRedis,
            @Value("${gateway.revocation.enabled:true}") boolean enabled,
            @Value("${gateway.revocation.fail-open:false}") boolean failOpen,
            @Value("${gateway.revocation.token-prefix:dev:token:blacklist:}") String tokenPrefix,
            @Value("${gateway.revocation.subject-prefix:dev:revoked:}") String subjectPrefix) {
        this.revocationRedis = revocationRedis;
        this.enabled = enabled;
        this.failOpen = failOpen;
        this.tokenPrefix = tokenPrefix;
        this.subjectPrefix = subjectPrefix;

        if (!enabled) {
            log.warn(" Vérification de révocation DÉSACTIVÉE : les jetons révoqués "
                    + "resteront acceptés jusqu'à leur expiration naturelle.");
        } else if (failOpen) {
            log.warn(" Vérification de révocation en mode fail-open : une panne Redis "
                    + "laissera passer les jetons révoqués.");
        }
    }

    /**

     * @param tokenId l'identifiant {@code jti} du jeton
     * @param subject le porteur ({@code sub})
     * @param emisLe  le claim {@code iat} du jeton. Décide si une révocation
     *                globale le frappe : seuls les jetons émis AVANT elle le
     *                sont. {@code null} signifie « inconnu », et le jeton est
     *                alors refusé — on ne peut pas prouver qu'il la postdate
     * @return {@code true} si le jeton doit être refusé
     */
    public Mono<Boolean> isRevoked(String tokenId, String subject, java.time.Instant emisLe) {
        if (!enabled) {
            return Mono.just(false);
        }

        // Un jeton sans `jti` ne peut pas être révoqué individuellement : c'est une
        // rupture du contrat d'émission, on refuse plutôt que d'ignorer.
        if (tokenId == null || tokenId.isBlank()) {
            log.warn(" Jeton sans identifiant `jti` : révocation impossible, jeton refusé.");
            return Mono.just(true);
        }

        Mono<Boolean> tokenRevoked = revocationRedis.hasKey(tokenPrefix + tokenId);

        // LE DEFAUT CORRIGE : c'etait `hasKey(subjectPrefix + subject)`.
        //
        // La cle `dev:revoked:<sub>` porte l'INSTANT de la revocation, avec une
        // duree de vie de sept jours. Un hasKey ignore cette valeur : une
        // revocation globale frappait donc aussi les jetons emis APRES elle, et
        // verrouillait le porteur sept jours — reconnexion comprise.
        //
        // Ce lecteur-ci est sur le chemin de CHAQUE requete de la plateforme,
        // donc la panne aurait ete totale. Elle n'etait pas active :
        // `revokeAllDeveloperTokens`, cote interface-service, n'a aucun
        // appelant. Brancher cette fonction d'administration l'aurait declenchee.
        //
        // La regle vit dans RevocationGlobale, de platform-commons :
        // interface-service lit la MEME cle et portait la MEME erreur. Deux
        // lecteurs d'une cle qui n'appliquent pas la meme regle, c'est ce que ce
        // module partage existe pour empecher.
        Mono<Boolean> subjectRevoked = (subject == null || subject.isBlank())
                ? Mono.just(false)
                : revocationRedis.opsForValue().get(subjectPrefix + subject)
                        .map(horodatage -> com.abdatytch.commons.security.RevocationGlobale
                                .frappe(horodatage, emisLe))
                        // Aucune cle : aucune revocation. `get` rend un Mono
                        // VIDE, et sans ce repli le zip ci-dessous rendrait vide
                        // — la requete serait perdue, ni acceptee ni refusee.
                        .defaultIfEmpty(false);

        return Mono.zip(tokenRevoked, subjectRevoked)
                .map(both -> Boolean.TRUE.equals(both.getT1()) || Boolean.TRUE.equals(both.getT2()))
                // Mono.zip se termine VIDE si l'une de ses sources l'est.
                //
                // C'est une forme distincte du piege du Mono vide, et la plus
                // discrete : hasKey ne devrait pas rendre vide, mais si cela
                // arrivait, zip rendrait vide, isRevoked rendrait vide, et le
                // flatMap du JwtWebFilter ne s'executerait jamais. La requete
                // serait ni authentifiee ni refusee — perdue.
                //
                // Le repli suit la meme politique que l'erreur : !failOpen.
                // Ne pas pouvoir determiner si un jeton est revoque, c'est ne
                // pas savoir si son porteur a encore le droit — et la, on
                // refuse.
                .defaultIfEmpty(!failOpen)
                .onErrorResume(error -> {
                    log.error(" Liste de révocation injoignable ({}). Jeton {}.",
                            error.getMessage(), failOpen ? "ACCEPTÉ (fail-open)" : "refusé");
                    return Mono.just(!failOpen);
                });
    }
}
