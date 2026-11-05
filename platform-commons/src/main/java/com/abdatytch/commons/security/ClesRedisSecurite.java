package com.abdatytch.commons.security;

/**
 * Conventions de cles Redis partagees entre qui <b>decide</b> et qui
 * <b>applique</b>.
 *
 * <h2>Pourquoi ces constantes vivent dans platform-commons</h2>
 *
 * Elles forment un contrat entre deux services : {@code security-service} ecrit,
 * {@code gateway-service} lit. Un contrat recopie de part et d'autre est un
 * contrat qui divergera — et la divergence serait <b>silencieuse</b>, puisque
 * lire une cle qui n'existe pas rend simplement « pas bloque ».
 *
 * <p>Ce depot a deja paye exactement cela : le verrouillage de compte vivait
 * dans Redis d'un cote et dans les colonnes de la base de l'autre, les deux ne
 * se rencontraient jamais, et un administrateur verrouillait un compte qui
 * continuait de se connecter. Deux litteraux {@code "security:ip:blacklist:"}
 * dans deux modules auraient reproduit ce defaut a l'identique.
 *
 * <h2>Les valeurs sont celles qui existent deja</h2>
 *
 * Elles ne sont pas choisies : elles sont reprises de
 * {@code IpBlacklistWebFilter} et de {@code GeoLocationService}, qui tournent et
 * dont les cles sont peut-etre deja peuplees en production. Les renommer aurait
 * vide les listes au deploiement, sans erreur et sans trace.
 *
 * <h2>Qui ecrit, qui lit</h2>
 *
 * Une seule regle, et elle tient tout l'edifice : <b>rien n'ecrit dans ces cles
 * sans passer par la base de security-service</b>. La base porte la decision,
 * son motif et son auteur ; Redis ne porte que la reponse immediate dont le
 * gateway a besoin a chaque requete. Ecrire directement dans Redis
 * reintroduirait un second magasin sans trace.
 *
 * <h2>La liste noire d'IP se lit au Gateway, et NULLE PART ailleurs</h2>
 *
 * Un service derriere la passerelle ne doit pas consulter
 * {@link #PREFIXE_IP_BLOQUEE}. Quatre modules s'y essayaient ; deux ont ete
 * supprimes, pour deux raisons qui se cumulent.
 *
 * <p><b>Ils sont inatteignables sans la passerelle.</b> Leur
 * {@code GatewayAccessWebFilter} tourne a {@code HIGHEST_PRECEDENCE + 10},
 * donc avant eux, et exige un pair TCP en reseau prive plus une signature HMAC
 * valide. Toute requete qui atteint un tel filtre a donc deja traverse
 * {@code IpBlacklistWebFilter} au Gateway, sur cette meme requete.
 *
 * <p><b>Et ils decidaient sur une valeur que l'attaquant choisit.</b> Ils
 * lisaient {@code GatewayClientIp.of()}, c'est-a-dire l'en-tete
 * {@code X-Client-Ip}. Contre le seul adversaire capable de les atteindre — un
 * appelant direct au port, depuis le reseau prive, muni du secret HMAC qui
 * figure dans l'historique Git — cet en-tete est fourni par l'adversaire
 * lui-meme. Le controle ne protegeait donc rien precisement dans le cas ou il
 * aurait servi : une defense en profondeur qui s'effondre exactement quand la
 * premiere couche tombe n'ajoute aucune profondeur.
 *
 * <p>Les deux supprimes etaient par ailleurs defaillants, et leurs defauts
 * valent d'etre connus avant d'ecrire un troisieme filtre de ce genre :
 *
 * <ul>
 *   <li><b>setting-service</b> lisait l'adresse, la journalisait en DEBUG et
 *       appelait {@code chain.filter()} sans condition. Il injectait un
 *       {@code ReactiveStringRedisTemplate} qu'il n'utilisait jamais.
 *       Cinquante-sept lignes qui ne refusaient rien.</li>
 *   <li><b>quotas-service</b> tenait sa PROPRE liste, sous
 *       {@code ip:blocked:} et {@code ip:attempts:} — des cles qu'aucun autre
 *       code du depot ne lit. Il portait de plus
 *       {@code PERMANENT_BLACKLIST = Set.of("0.0.0.0", "127.0.0.1")}, et comme
 *       la passerelle pose {@code X-Client-Ip: 127.0.0.1} pour un appel local,
 *       chaque requete de developpement repartait en 403. Enfin son comptage
 *       partait d'un {@code doOnError} : il comptait NOS pannes, pas les
 *       tentatives d'un appelant, et cinq exceptions internes bloquaient
 *       l'adresse d'un utilisateur une heure.</li>
 * </ul>
 *
 * <p>Le troisieme lecteur, {@code interface-service}, subsiste parce qu'il
 * <b>detecte</b> : il compte les refus d'authentification de la console
 * d'administration et inscrit au-dela d'un seuil. Que cette detection ecrive
 * directement dans Redis contrevient a la regle ci-dessus, et reste a arbitrer
 * — {@code security-service} tient deja la base de decision et l'invariant
 * « interdiction permanente refusee a la detection automatique ».
 */
public final class ClesRedisSecurite {

    private ClesRedisSecurite() {
        throw new UnsupportedOperationException("Cette classe ne peut pas etre instanciee");
    }

    /**
     * Prefixe des adresses bloquees. La cle complete est
     * {@code security:ip:blacklist:<adresse>}.
     *
     * <p>C'est l'<b>existence</b> de la cle qui vaut blocage, pas sa valeur :
     * {@code IpBlacklistWebFilter} fait {@code hasKey}. Une valeur y est quand
     * meme ecrite — le motif — parce qu'un diagnostic depuis {@code redis-cli}
     * sans motif ne dit rien.
     */
    public static final String PREFIXE_IP_BLOQUEE = "security:ip:blacklist:";

    /** Prefixe du comptage de violations, tenu par le gateway lui-meme. */
    public static final String PREFIXE_VIOLATIONS_IP = "security:ip:violations:";

    /**
     * Ensemble Redis des codes pays refuses, en majuscules.
     *
     * <p>Un {@code SET} et non des cles individuelles : le filtre a besoin de
     * {@code SISMEMBER}, et la liste complete tient dans une lecture.
     */
    public static final String ENSEMBLE_PAYS_BLOQUES = "geo_blocked_countries";

    /**
     * Politique de securite publiee, sous forme de hachage Redis.
     *
     * <p>Un {@code HASH} parce que la politique est lue par morceaux : le
     * gateway ne veut que la limite de debit, auth-service ne veut que les
     * durees de session et les obligations. Un document unique obligerait chacun
     * a tout lire et a tout desserialiser.
     */
    public static final String HACHAGE_POLITIQUE = "security:politique";

    /**
     * Fenetres de maintenance actives, par service.
     *
     * <p>Hachage dont la cle est le nom du service tel que Consul le connait, et
     * {@code *} pour la plateforme entiere. La valeur porte le message et
     * l'echeance, parce que le gateway doit pouvoir les rendre a l'appelant sans
     * appeler personne.
     */
    public static final String HACHAGE_MAINTENANCE = "security:maintenance";

    /** Cle du hachage {@link #HACHAGE_MAINTENANCE} designant toute la plateforme. */
    public static final String MAINTENANCE_TOUTE_PLATEFORME = "*";
}
