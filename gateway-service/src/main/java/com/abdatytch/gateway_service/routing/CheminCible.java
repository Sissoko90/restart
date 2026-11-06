package com.abdatytch.gateway_service.routing;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Le chemin qu'un microservice recevra, calculé une seule fois pour toute la
 * plateforme.
 *
 * <h2>Pourquoi cette classe doit exister</h2>
 *
 * Deux endroits ont besoin de ce chemin, et ils doivent donner <b>exactement</b>
 * la même réponse :
 *
 * <ol>
 *   <li>{@code GatewaySignatureFilter}, qui signe
 *       {@code timestamp|chemin|méthode|requestId} en HMAC-SHA256 ;</li>
 *   <li>{@code GatewayRoutesConfig}, qui construit l'URI de proxification.</li>
 * </ol>
 *
 * Le service en aval recalcule la signature sur le chemin qu'il a reçu. Si ces
 * deux endroits divergent <b>d'un seul caractère</b>, chaque requête concernée
 * est refusée en 403 — et le message d'erreur ne dit pas que les chemins
 * diffèrent, seulement que la signature est invalide. Le défaut serait cherché
 * du côté des clés.
 *
 * <p>Les deux faisaient chacun leur {@code replaceFirst("/api/v1", "")}, en
 * double. Tant que la transformation était ce seul retrait de préfixe, la
 * duplication tenait. Elle ne tient plus dès la première réécriture : il faut
 * alors penser à modifier les deux, et rien ne le rappelle.
 *
 * <h2>Les réécritures, et la collision qui les rend nécessaires</h2>
 *
 * auth-service et interface-service exposent <b>tous les deux</b>
 * {@code /admin/**} et {@code /auth/**}. Le préfixe {@code /api/v1} retiré ne
 * suffit donc pas à les distinguer : il faut des chemins publics distincts,
 * ramenés ensuite sur le chemin réel de chaque service.
 *
 * <p>interface-service garde les chemins historiques — son contrat d'URL ne
 * change pas — et auth-service reçoit les nouveaux.
 */
public final class CheminCible {

    /** Préfixe public de l'API, retiré pour tous les services. */
    private static final String PREFIXE_API = "/api/v1";

    /**
     * Réécritures appliquées après le retrait du préfixe.
     *
     * <p>Ordonnée, et parcourue dans l'ordre : la première correspondance
     * gagne. Avec des clés qui se recouvriraient, l'ordre de déclaration
     * deviendrait la règle, donc il est explicite.
     */
    private static final Map<String, String> REECRITURES = new LinkedHashMap<>();

    static {
        // auth-service expose /admin/** (comptes, audit, SMS). interface-service
        // aussi : /api/v1/admin/** lui est reserve.
        REECRITURES.put("/auth-admin", "/admin");
        // auth-service expose /auth/** (connexion administrateur de la
        // plateforme, biometrie, effacement RGPD). /api/v1/auth/** est reserve a
        // interface-service.
        REECRITURES.put("/auth-sdk", "/auth");
        // /auth-users/** et /sms/accuses/** n'ont pas besoin de reecriture :
        // auth-service les expose sous ce nom, et aucun autre service ne les
        // revendique.

        // ------------------------------------------------------------------
        // La documentation OpenAPI de chaque service
        // ------------------------------------------------------------------
        // `/api/v1/docs/<service>` est ramene sur `/v3/api-docs`, le chemin que
        // springdoc sert dans chaque service.
        //
        // Pourquoi ce prefixe-la, et non `/v3/api-docs/<service>` qui serait
        // plus naturel : `GatewaySignatureFilter.isPublicEndpoint` NE SIGNE PAS
        // les chemins commencant par `/v3/api-docs`, `/swagger-ui` ou
        // `/webjars/`. Une requete routee sous ces prefixes partirait donc sans
        // signature, et le service la refuserait en 403 — avec un message
        // parlant de signature invalide, jamais de chemin. Le defaut serait
        // cherche du cote des cles.
        //
        // Les services, eux, n'exemptent la documentation que du JETON, jamais
        // de la signature (voir CheminsPublics.documentationViaPasserelle) :
        // elle n'est donc lisible qu'a travers cette passerelle, qui l'agrege
        // et la protege par authentification HTTP dediee.
        REECRITURES.put("/docs/auth", "/v3/api-docs");
        REECRITURES.put("/docs/interface", "/v3/api-docs");
        REECRITURES.put("/docs/users", "/v3/api-docs");
        REECRITURES.put("/docs/media", "/v3/api-docs");
        REECRITURES.put("/docs/quotas", "/v3/api-docs");
        REECRITURES.put("/docs/settings", "/v3/api-docs");
        REECRITURES.put("/docs/security", "/v3/api-docs");

        // ------------------------------------------------------------------
        // Le JWKS, sur son chemin standard
        // ------------------------------------------------------------------
        // `/.well-known/jwks.json` est le chemin de decouverte standard, celui
        // qu'un client exterieur interroge pour verifier une signature de jeton.
        //
        // Il est ramene sur `/dev/.well-known/jwks.json`, le chemin REELLEMENT
        // servi : `DevKeyManagementController` d'interface-service porte
        // `@RequestMapping("/dev")` en tete de classe.
        //
        // LE DEFAUT CORRIGE : la route proxifiait vers AUTH-SERVICE, qui ne sert
        // aucun chemin `/.well-known`. L'autorite JWT de cette plateforme est
        // interface-service, seul detenteur des cles privees ; auth-service ne
        // fait que CONSOMMER le JWKS, par InterfaceServiceClient.
        //
        // La route standard rendait donc une erreur, sans que rien ne le
        // signale : une route qui existe et ne mene nulle part est
        // indistinguable d'un chemin inexistant. Le chemin explicite
        // `/dev/.well-known/jwks.json` fonctionnait, lui, par une route YAML —
        // d'ou l'absence de plainte.
        REECRITURES.put("/.well-known/jwks.json", "/dev/.well-known/jwks.json");
    }


    /**
     * Services derriere chaque prefixe public, dans l'ordre de declaration.
     *
     * <h2>Pourquoi cette table existe ici</h2>
     *
     * Le mode maintenance doit pouvoir couper <b>un</b> service sans toucher aux
     * autres. Pour cela, le filtre qui applique la coupure a besoin de savoir
     * quel service une requete allait atteindre — et cette connaissance n'etait
     * nulle part accessible : elle vivait dans les definitions de routes de
     * {@code GatewayRoutesConfig}, sous forme de {@code .nest(path(...))}.
     *
     * <h2>Deux declarations, et un test qui les compare</h2>
     *
     * Cette table <b>reproduit</b> le routage declare dans
     * {@code GatewayRoutesConfig}. Deux declarations pour une meme
     * correspondance est precisement ce que ce depot evite — mais extraire le
     * routage lui-meme vers cette classe demanderait de reecrire une
     * configuration qui fonctionne, et le risque serait plus grand que le mal.
     *
     * <p>La divergence est donc traitee autrement : {@code RoutageCoherentTest}
     * lit les deux et echoue si elles ne s'accordent pas. Un prefixe ajoute au
     * routage sans l'etre ici ferait tomber la suite, au lieu de produire une
     * maintenance qui ne coupe rien.
     *
     * <h2>L'ordre est la regle</h2>
     *
     * {@code /api/v1/admin/**} vient en dernier, apres les chemins
     * {@code /api/v1/admin/documents/**} et les autres : il les capturerait
     * sinon, et une maintenance de media-service couperait interface-service.
     * C'est le meme ordre que dans {@code GatewayRoutesConfig}, et pour la meme
     * raison.
     */
    private static final Map<String, String> SERVICES_PAR_PREFIXE = new LinkedHashMap<>();

    static {
        // La documentation de chaque service, en premier : ces prefixes sont
        // les plus specifiques et ne collisionnent avec rien, mais les declarer
        // ici les rend visibles du mode maintenance — couper media-service doit
        // aussi couper la lecture de SA documentation, sinon l'interface Swagger
        // reste a moitie chargee sans que rien ne l'explique.
        SERVICES_PAR_PREFIXE.put("/api/v1/docs/auth", "auth-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/docs/interface", "interface-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/docs/users", "user-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/docs/media", "media-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/docs/quotas", "quotas-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/docs/settings", "setting-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/docs/security", "security-service");

        // Suit le sous-systeme SMS, passe dans notification-service. La surface
        // d'administration est declaree plus bas, avec les autres /admin/*.
        SERVICES_PAR_PREFIXE.put("/api/v1/sms/accuses", "notification-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/auth-admin", "auth-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/auth-sdk", "auth-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/auth-users", "auth-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/auth", "interface-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/users", "user-service");
        // Photos de profil servies par user-service. Prefixe public identique au
        // chemin interne apres retrait de /api/v1 : aucune reecriture.
        //
        // L'URL rendue par POST /users/me/profile-picture pointait ici, et
        // aucune route n'existait : la photo etait enregistree, l'API repondait
        // 200 avec son URL, et cette URL ne menait nulle part.
        SERVICES_PAR_PREFIXE.put("/api/v1/files", "user-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/media", "media-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/quotas", "quotas-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/settings", "setting-service");
        // security-service : il n'etait route par AUCUN des deux mecanismes.
        //
        // Le module etait complet, teste et configure, et ses sept endpoints —
        // politique de securite, pays refuses, regles d'IP, fenetres de
        // maintenance — restaient injoignables depuis l'exterieur. Rien ne le
        // signalait : la passerelle rendait 404, ce qui est indistinguable d'un
        // chemin inexistant.
        //
        // Prefixe public identique au chemin interne : aucune reecriture n'est
        // necessaire, car /security-admin ne collisionne avec rien.
        SERVICES_PAR_PREFIXE.put("/api/v1/security-admin", "security-service");
        // notification-service : boite de reception et flux temps reel.
        //
        // DEUX entrees, et le tiret de la seconde n'est pas un detail.
        //
        // /api/v1/notifications/** est route par le RouterFunction (REST).
        // /api/v1/notifications-flux est route en YAML, parce qu'une montee en
        // protocole WebSocket ne passe pas par WebClient — et parce que le
        // RouterFunctionMapping, plus prioritaire, capterait tout chemin place
        // sous /notifications/.
        //
        // Les deux doivent figurer ICI pour que le mode maintenance les coupe :
        // MaintenanceWebFilter appelle serviceCible(chemin), et un chemin
        // inconnu de cette table traverserait une maintenance activee. C'est
        // precisement ce que RoutageCoherentTest refuse — il a rejete le build
        // tant que la premiere entree manquait.
        //
        // L'ordre compte : la table est un LinkedHashMap parcouru dans l'ordre
        // d'insertion, et /api/v1/notifications serait un prefixe de
        // /api/v1/notifications-flux. Le plus specifique d'abord.
        //
        // Prefixes publics identiques aux chemins internes apres retrait de
        // /api/v1 : aucune reecriture, /notifications ne collisionne avec rien.
        SERVICES_PAR_PREFIXE.put("/api/v1/notifications-flux", "notification-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/notifications", "notification-service");
        // QUATRE prefixes d'interface-service qui manquaient, et le defaut
        // etait reel : le mode maintenance ne les coupait pas.
        //
        // Ils sont routes en YAML (lb://interface-service) et non par le
        // RouterFunction, et RoutageCoherentTest ne lisait que le second. Son
        // angle mort n'avait jamais mordu parce qu'aucun prefixe n'etait
        // declare UNIQUEMENT en YAML ; l'ajout du flux WebSocket l'a revele, et
        // la correction du test a fait apparaitre ces quatre-ci.
        //
        // Consequence avant correction : activer une maintenance sur
        // interface-service laissait passer /api/v1/projects,
        // /api/v1/developers, /dev/auth et la publication du JWKS. Une
        // maintenance qui laisse passer est exactement ce que
        // MaintenanceWebFilter existe pour empecher.
        SERVICES_PAR_PREFIXE.put("/api/v1/developers", "interface-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/projects", "interface-service");
        SERVICES_PAR_PREFIXE.put("/dev/.well-known/jwks.json", "interface-service");
        SERVICES_PAR_PREFIXE.put("/dev/auth", "interface-service");
        // Surfaces d'administration de quotas-service et setting-service.
        //
        // Elles n'etaient routees par aucun des deux mecanismes, et
        // /api/v1/admin/** les aurait de toute façon envoyees a
        // interface-service, qui ne porte ni /admin/quotas ni /admin/settings.
        // Quatre endpoints d'administration rendaient donc 404.
        //
        // AVANT /api/v1/admin, comme les trois chemins de media-service : la
        // regle generale les capturerait sinon.
        // Administration des comptes, portee par user-service sous /admin/users.
        //
        // Elle etait declaree en YAML uniquement, donc masquee par la route
        // /api/v1/admin/** du RouterFunction : blocage, bannissement,
        // suspension, revocation, retablissement et listes partaient chez
        // interface-service, qui ne porte pas cette surface.
        //
        // AVANT /api/v1/admin, pour la meme raison que les suivants.
        SERVICES_PAR_PREFIXE.put("/api/v1/admin/users", "user-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/admin/quotas", "quotas-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/admin/settings", "setting-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/admin/document-types", "media-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/admin/role-requirements", "media-service");
        SERVICES_PAR_PREFIXE.put("/api/v1/admin/documents", "media-service");
        // Administration du SMS : solde chez le fournisseur, et declaration de
        // l'URL de rappel des accuses.
        //
        // N'etait routee NULLE PART du temps ou elle vivait dans auth-service :
        // AdminSmsController repond sur /admin/sms, et /api/v1/admin part vers
        // interface-service, qui ne porte pas ce chemin. Les deux endpoints
        // rendaient 404 — dont le releve du solde, seul moyen de voir venir un
        // credit epuise. Un credit epuise ne produit aucune erreur HTTP :
        // l'envoi repond 200 avec un code de refus.
        SERVICES_PAR_PREFIXE.put("/api/v1/admin/sms", "notification-service");
        // EN DERNIER : capturerait les trois precedents sinon.
        SERVICES_PAR_PREFIXE.put("/api/v1/admin", "interface-service");
    }

    /**
     * Service que cette requete allait atteindre.
     *
     * @return le nom Consul du service, ou {@code null} si aucun prefixe ne
     *         correspond. Un {@code null} signifie « hors routage » : actuator,
     *         Swagger, un chemin inconnu. Le filtre de maintenance le laisse
     *         alors passer, parce qu'une coupure annoncee pour un service n'a
     *         aucune raison de bloquer une sonde de sante.
     */
    public static String serviceCible(String cheminEntrant) {
        if (cheminEntrant == null) {
            return null;
        }
        for (Map.Entry<String, String> entree : SERVICES_PAR_PREFIXE.entrySet()) {
            if (estLeSegment(cheminEntrant, entree.getKey())) {
                return entree.getValue();
            }
        }
        return null;
    }

    /**
     * Prefixes declares, <b>dans l'ordre</b>, pour le test de coherence.
     *
     * <h2>Pourquoi pas Map.copyOf</h2>
     *
     * {@code Map.copyOf} rend une map immuable mais <b>non ordonnee</b> : l'ordre
     * de declaration est perdu. Or l'ordre est ici la regle — la premiere
     * correspondance l'emporte, et {@code /api/v1/admin} capturerait
     * {@code /api/v1/admin/documents} s'il venait avant.
     *
     * <p>L'accesseur annoncait donc exposer la table en perdant sa seule
     * propriete importante. {@code RoutageCoherentTest} l'a signale : il lisait
     * un ordre aleatoire et concluait, a tort mais legitimement, que le prefixe
     * general precedait les specifiques.
     *
     * <p>La recherche elle-meme n'a jamais ete affectee : {@code serviceCible}
     * parcourt la {@code LinkedHashMap} directement. Seul l'accesseur mentait —
     * ce qui aurait rendu le garde-fou inutilisable, donc le risque reel etait
     * de perdre le garde-fou, pas le routage.
     */
    public static Map<String, String> servicesParPrefixe() {
        return java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(SERVICES_PAR_PREFIXE));
    }

    private CheminCible() {
    }

    /**
     * @param cheminEntrant le chemin tel que le client l'a demandé, par exemple
     *                      {@code /api/v1/auth-admin/accounts/42/unlock}
     * @return le chemin que le service recevra, par exemple
     *         {@code /admin/accounts/42/unlock}
     */
    public static String pour(String cheminEntrant) {
        if (cheminEntrant == null) {
            return "";
        }
        String chemin = cheminEntrant.startsWith(PREFIXE_API)
                ? cheminEntrant.substring(PREFIXE_API.length())
                : cheminEntrant;

        for (Map.Entry<String, String> reecriture : REECRITURES.entrySet()) {
            String depuis = reecriture.getKey();
            if (estLeSegment(chemin, depuis)) {
                return reecriture.getValue() + chemin.substring(depuis.length());
            }
        }
        return chemin;
    }

    /**
     * Le préfixe correspond-il à un <b>segment entier</b> du chemin ?
     *
     * <p>Un simple {@code startsWith} ferait correspondre {@code /auth-admin} à
     * {@code /auth-administration}, qui irait alors chez auth-service sous le
     * chemin {@code /administration} — un chemin que personne n'a déclaré.
     * Ce dépôt a déjà corrigé trois fois la même famille de défaut dans
     * {@code GatewayAccessWebFilter} : un contrôle de chemin qui désigne plus
     * large que son intention.
     */
    private static boolean estLeSegment(String chemin, String prefixe) {
        if (!chemin.startsWith(prefixe)) {
            return false;
        }
        return chemin.length() == prefixe.length() || chemin.charAt(prefixe.length()) == '/';
    }
}
