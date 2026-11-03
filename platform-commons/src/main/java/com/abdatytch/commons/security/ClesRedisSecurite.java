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
