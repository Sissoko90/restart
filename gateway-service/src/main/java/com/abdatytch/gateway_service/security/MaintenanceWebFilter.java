package com.abdatytch.gateway_service.security;

import com.abdatytch.commons.security.CheminDeDecision;
import com.abdatytch.commons.security.ClesRedisSecurite;
import com.abdatytch.gateway_service.routing.CheminCible;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Applique le mode maintenance. C'est la piece qui manquait entierement.
 *
 * <h2>Le defaut corrige</h2>
 *
 * {@code setting-service} declarait {@code system.maintenance-mode}, le
 * journalisait en {@code WARN} avec un message dedie, et <b>rien ne le
 * lisait</b>. La cle n'apparaissait que dans {@code setting-service} lui-meme :
 * aucun filtre, aucune route, aucun service ne la consultait.
 *
 * <p>Un administrateur pouvait donc « mettre le site en maintenance », voir la
 * confirmation dans l'interface et la ligne {@code MODE MAINTENANCE ACTIVE}
 * dans les journaux, et le trafic continuait de passer sans la moindre gene.
 * C'est le motif le plus frequent de ce depot : une protection ecrite,
 * confirmee a son auteur, et jamais atteinte.
 *
 * <h2>Pourquoi ce filtre lit Redis et n'appelle personne</h2>
 *
 * Il tourne sur <b>chaque</b> requete. Appeler security-service y mettrait un
 * saut reseau, et son indisponibilite arreterait la plateforme — une panne
 * causee par l'outil censé annoncer les pannes. Redis est alimente par
 * security-service, qui republie periodiquement.
 *
 * <h2>Pourquoi une panne de Redis LAISSE PASSER</h2>
 *
 * C'est le choix le plus discutable de cette classe, et il est delibere. Deux
 * options :
 *
 * <ul>
 *   <li>refuser en cas de doute : une panne de Redis coupe alors toute la
 *       plateforme, alors qu'aucune maintenance n'etait declaree ;</li>
 *   <li>laisser passer : une panne de Redis <b>pendant</b> une maintenance
 *       laisse passer du trafic vers un service en travaux.</li>
 * </ul>
 *
 * <p>La seconde est retenue parce qu'une maintenance est <b>annoncee</b> : le
 * service en travaux refuse de lui-meme, ou repond mal, mais la plateforme
 * entiere ne tombe pas a cause de son cache. {@code IpBlacklistWebFilter} a fait
 * le meme choix, pour la meme raison, et il est documente la-bas.
 *
 * <h2>Ordre d'execution</h2>
 *
 * Apres la liste noire d'adresses et avant l'authentification : une maintenance
 * n'a pas a verifier un jeton pour annoncer qu'un service est coupe, et faire
 * passer l'authentification d'abord ferait repondre 401 a qui n'a pas de jeton —
 * un message faux pendant une maintenance.
 */
public class MaintenanceWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceWebFilter.class);

    /**
     * Chemins qui passent meme en maintenance globale.
     *
     * <p>Les sondes et la supervision : couper {@code /actuator/health} pendant
     * une maintenance ferait sortir toutes les instances du pool, et
     * l'orchestrateur les redemarrerait en boucle. La maintenance deviendrait
     * une panne.
     */
    private static final String PREFIXE_ACTUATOR = "/actuator";

    private final ReactiveRedisTemplate<String, String> redis;

    public MaintenanceWebFilter(ReactiveRedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange echange, WebFilterChain chaine) {
        // Le chemin de DECISION, decode, et non le chemin brut.
        //
        // LE DEFAUT CORRIGE : `getPath().value()` rend le brut, alors que le
        // routeur resout sur des segments decodes. `/api/v1/%6dedia/upload`
        // faisait donc rendre `null` a `serviceCible`, donc la fenetre de
        // maintenance PAR SERVICE ne s'appliquait pas — a une requete que le
        // routeur dirigeait bien vers media-service. Couper un service laissait
        // passer qui encodait une lettre de son nom.
        //
        // Seule la fenetre globale tenait. Meme famille que le contournement de
        // la garde de documentation, et meme correctif.
        //
        // La signature HMAC continue de porter sur le chemin BRUT : voir la
        // javadoc de CheminDeDecision, ces deux chemins ne se substituent jamais
        // l'un a l'autre.
        String chemin = CheminDeDecision.de(echange);

        if (chemin.startsWith(PREFIXE_ACTUATOR)) {
            return chaine.filter(echange);
        }

        String service = CheminCible.serviceCible(chemin);

        // La fenetre GLOBALE est consultee d'abord : elle est plus restrictive,
        // et une maintenance ciblee ne doit pas pouvoir la lever par accident.
        //
        // ATTENTION a la forme de cette chaine. Le piege du Mono vide :
        // `refuser(...)` rend un Mono<Void>, qui se termine VIDE meme quand il a
        // refuse. Place sur ce Mono<Void>, un `switchIfEmpty` enchainerait donc
        // la requete APRES l'avoir refusee — le filtre paraitrait enregistre et
        // ne couperait rien.
        //
        // La regle, corrigee une douzaine de fois dans ce depot :
        // `switchIfEmpty` et `defaultIfEmpty` vont sur le Mono TYPE, jamais sur
        // le Mono<Void> final. D'ou le booleen intermediaire.
        return fenetre(ClesRedisSecurite.MAINTENANCE_TOUTE_PLATEFORME)
                .switchIfEmpty(service == null ? Mono.empty() : fenetre(service))
                .flatMap(description -> refuser(echange, description).thenReturn(Boolean.TRUE))
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(refuse -> Boolean.TRUE.equals(refuse)
                        ? Mono.empty()
                        // Mono.defer : sans lui, la chaine suivante serait
                        // ASSEMBLEE sur le fil courant avant qu'on sache s'il
                        // faut refuser.
                        : Mono.defer(() -> chaine.filter(echange)));
    }

    /**
     * Lit la fenetre d'un service dans le cache.
     *
     * <p>Une erreur Redis rend un {@code Mono} vide, donc « pas de maintenance »
     * : voir la javadoc de la classe pour ce choix. L'erreur est journalisee en
     * {@code warn} et non en {@code error} — elle est attendue pendant un
     * redemarrage de Redis, et une alerte a chaque redemarrage serait ignoree.
     */
    private Mono<String> fenetre(String service) {
        return redis.<String, String>opsForHash()
                .get(ClesRedisSecurite.HACHAGE_MAINTENANCE, service)
                .onErrorResume(erreur -> {
                    log.warn("Cache de maintenance illisible ({}) : la requete passe",
                            erreur.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    /**
     * Refuse avec 503 et l'en-tete {@code Retry-After}.
     *
     * <h2>503 et non 500 ni 404</h2>
     *
     * 503 dit « indisponible, reessaie plus tard », ce qu'un client sait
     * interpreter et ce qu'une sonde sait distinguer d'une defaillance. 500
     * accuserait la plateforme d'un defaut, et 404 laisserait croire que le
     * chemin n'existe pas — un integrateur chercherait alors une erreur dans son
     * code.
     *
     * <h2>Retry-After n'est pas decoratif</h2>
     *
     * Sans lui, les clients reessaient immediatement et en boucle : la
     * maintenance recoit alors plus de trafic qu'un fonctionnement normal, ce
     * qui gene precisement les travaux qu'elle protege.
     */
    private Mono<Void> refuser(ServerWebExchange echange, String description) {
        String[] morceaux = description.split("\\|", 2);
        String message = morceaux[0];
        String echeance = morceaux.length > 1 ? morceaux[1] : "";

        var reponse = echange.getResponse();
        reponse.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        reponse.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (!echeance.isBlank()) {
            reponse.getHeaders().add("X-Maintenance-Until", echeance);
        }

        // Corps construit a la main et non par un serialiseur : le filtre tourne
        // avant la negociation de contenu, et aucune donnee de la requete n'y
        // entre — donc aucun risque d'injection par un champ reflete.
        String corps = "{\"error\":\"maintenance\",\"message\":"
                + enJson(message)
                + (echeance.isBlank() ? "" : ",\"jusqua\":" + enJson(echeance))
                + "}";

        var tampon = reponse.bufferFactory()
                .wrap(corps.getBytes(StandardCharsets.UTF_8));
        return reponse.writeWith(Mono.just(tampon));
    }

    /**
     * Echappe une chaine pour un corps JSON.
     *
     * <p>Le message vient d'un administrateur, pas d'un appelant anonyme — mais
     * il contient volontiers des guillemets (« retour prevu a 14h00 ») et une
     * barre oblique inverse suffirait a produire un JSON invalide. Un client qui
     * recoit du JSON malforme pendant une maintenance conclut a une panne.
     */
    private static String enJson(String valeur) {
        StringBuilder sortie = new StringBuilder("\"");
        for (char caractere : valeur.toCharArray()) {
            switch (caractere) {
                case '"' -> sortie.append("\\\"");
                case '\\' -> sortie.append("\\\\");
                case '\n' -> sortie.append("\\n");
                case '\r' -> sortie.append("\\r");
                case '\t' -> sortie.append("\\t");
                default -> {
                    if (caractere < 0x20) {
                        sortie.append(String.format("\\u%04x", (int) caractere));
                    } else {
                        sortie.append(caractere);
                    }
                }
            }
        }
        return sortie.append('"').toString();
    }
}
