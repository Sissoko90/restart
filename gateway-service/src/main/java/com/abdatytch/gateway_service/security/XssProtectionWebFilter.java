package com.abdatytch.gateway_service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**

 * WebFilter Reactive pour protection XSS (WebFlux compatible)
 * Remplace XssProtectionFilter (Servlet) pour architecture non-bloquante
 * Nettoie les paramètres et headers pour prévenir les attaques XSS
 */
public class XssProtectionWebFilter implements WebFilter {

    private final ClientIpResolver clientIpResolver;

    /**
     * Signale les violations detectees, pour alimenter la liste noire.
     *
     * <p>Peut rester {@code null} : le filtre doit refuser la requete meme sans
     * enregistreur. Le refus immediat et la mise en liste noire sont deux
     * decisions distinctes, et la seconde ne doit pas conditionner la premiere.
     */
    private SecurityViolationRecorder violationRecorder;

    /** Branche l'enregistreur de violations. */
    public XssProtectionWebFilter avecEnregistreur(SecurityViolationRecorder recorder) {
        this.violationRecorder = recorder;
        return this;
    }

    private reactor.core.publisher.Mono<Void> signaler(String clientIp, String motif) {
        return violationRecorder == null
                ? reactor.core.publisher.Mono.empty()
                : violationRecorder.enregistrer(clientIp, motif);
    }

    public XssProtectionWebFilter(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    private static final Logger log = LoggerFactory.getLogger(XssProtectionWebFilter.class);

    // Patterns XSS dangereux
    private static final Pattern[] XSS_PATTERNS = {
        Pattern.compile("<script>(.*?)</script>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("src[\r\n]*=[\r\n]*\\\"(.*?)\\\"", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
        Pattern.compile("</script>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("<script(.*?)>", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
        Pattern.compile("eval\\((.*?)\\)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
        Pattern.compile("expression\\((.*?)\\)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
        Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),
        Pattern.compile("vbscript:", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onload(.*?)=", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL)
    };

    /**
     * Detecte les motifs d'injection et <b>signale</b>, sans bloquer.
     *
     * <h2>Ce filtre ne protegeait rien</h2>
     *
     * Son nom dit « Protection », et il n'en assurait aucune. Il appelait
     * {@code stripXss(query)}, comparait le resultat a l'original, ecrivait un
     * avertissement — puis <b>jetait la chaine nettoyee</b> et poursuivait la
     * chaine avec la requete inchangee. Les deux controles d'en-tetes ne
     * faisaient que journaliser. Aucun chemin ne refusait quoi que ce soit.
     *
     * <p>C'est pire qu'un filtre absent : sa presence et son nom laissaient
     * croire que les parametres de requete etaient assainis au Gateway.
     *
     * <h2>Pourquoi il ne bloque toujours pas</h2>
     *
     * Ses motifs ont ete mesures sur des charges reelles. Ils <b>manquent</b>
     * {@code <img src=x onerror=alert(1)>} — l'une des formes les plus
     * courantes — et produisent des <b>faux positifs</b> sur du trafic
     * legitime : {@code src="..."} dans une URL de redirection,
     * {@code expression(...)} dans un texte libre, un nom de parametre
     * contenant {@code onload}. Trois faux positifs sur six echantillons
     * plausibles.
     *
     * <p>Bloquer sur cette base casserait du trafic valide, et un filtre qui
     * casse le trafic valide finit desactive. Le faire bloquer serait une
     * decision de produit, pas une correction : elle demande d'abord des motifs
     * fiables ou un assainisseur HTML veritable.
     *
     * <h2>Ce qu'il apporte desormais</h2>
     *
     * Il <b>signale</b> la tentative a {@link SecurityViolationRecorder}. Une
     * adresse qui essaie dix fois finit inscrite en liste noire — ce qui traite
     * le balayage sans refuser la requete isolee d'un utilisateur dont le texte
     * contenait malencontreusement un motif.
     *
     * <h2>Ou se defend reellement le XSS</h2>
     *
     * A l'<b>encodage en sortie</b>, la ou la donnee est rendue, et par la
     * politique de securite de contenu — {@code default-src 'self'}, posee par
     * {@code SecurityConfig}. Le Gateway voit des donnees qui peuvent
     * legitimement contenir {@code <script>} : quelqu'un qui ecrit au sujet du
     * HTML. Filtrer a l'entree est un instrument emousse ; c'est une couche
     * supplementaire, jamais la defense principale.
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String query = exchange.getRequest().getURI().getQuery();
        String userAgent = exchange.getRequest().getHeaders().getFirst("User-Agent");
        String referer = exchange.getRequest().getHeaders().getFirst("Referer");

        String origine = null;
        if (query != null && containsXss(query)) {
            origine = "query";
        } else if (userAgent != null && containsXss(userAgent)) {
            origine = "user-agent";
        } else if (referer != null && containsXss(referer)) {
            origine = "referer";
        }

        if (origine == null) {
            return chain.filter(exchange);
        }

        String clientIp = getClientIp(exchange);
        log.warn("Motif d'injection detecte dans {} depuis {} — signale, non bloque",
                origine, clientIp);

        // La requete poursuit son chemin : voir le commentaire de classe sur la
        // fiabilite des motifs. Le signalement, lui, s'accumule.
        return signaler(clientIp, "xss-" + origine)
                .then(Mono.defer(() -> chain.filter(exchange)));
    }

    /**

     * Vérifie si une chaîne contient des patterns XSS
     */
    private boolean containsXss(String value) {
        if (value == null) {
            return false;
        }

        for (Pattern pattern : XSS_PATTERNS) {
            if (pattern.matcher(value).find()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Adresse du client, pour la journalisation.
     *
     * <p>Lisait {@code X-Forwarded-For} directement : l'appelant choisissait
     * donc l'adresse inscrite dans les journaux de tentative d'attaque. Voir
     * {@code ClientIpResolver}, qui ne croit cet en-tete que depuis une plage
     * declaree dans {@code gateway.trusted-proxies}.
     */
    private String getClientIp(ServerWebExchange exchange) {
        return clientIpResolver.resolve(exchange);
    }
}