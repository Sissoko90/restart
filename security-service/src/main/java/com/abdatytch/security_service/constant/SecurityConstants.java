package com.abdatytch.security_service.constant;

/**
 * Constantes de security-service.
 *
 * <p>Meme forme que {@code SettingsConstants} et {@code AppConstants} : aucun
 * litteral de message ni de chemin dans le code, un seul endroit a corriger.
 */
public final class SecurityConstants {

    private SecurityConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas etre instanciee");
    }

    // ------------------------------------------------------------------
    // Chemins
    // ------------------------------------------------------------------
    /** Base de l'administration de la securite. Reservee a l'administration. */
    public static final String ADMIN_BASE_PATH = "/security-admin";

    /** Politique de securite : lecture et ecriture. */
    public static final String POLITIQUE_ENDPOINT = "/politique";
    public static final String POLITIQUE_PAR_CLE_ENDPOINT = "/politique/{cle}";

    /** Regles d'adresses. */
    public static final String REGLES_IP_ENDPOINT = "/ip";
    public static final String REGLE_IP_PAR_ID_ENDPOINT = "/ip/{id}";

    /** Pays refuses. Reprend la surface de GeoAdminController, qui demenage. */
    public static final String PAYS_ENDPOINT = "/pays";
    public static final String PAYS_PAR_CODE_ENDPOINT = "/pays/{codeIso}";

    /** Fenetres de maintenance. */
    public static final String MAINTENANCE_ENDPOINT = "/maintenance";
    public static final String MAINTENANCE_PAR_ID_ENDPOINT = "/maintenance/{id}";

    // ------------------------------------------------------------------
    // Separateurs de serialisation
    // ------------------------------------------------------------------
    /**
     * Separe le message de l'echeance dans le cache de maintenance.
     *
     * <p>Une barre verticale : elle n'apparait pas dans un message de
     * maintenance redige normalement, contrairement a la virgule ou au
     * deux-points. Le service refuse un message qui la contient, plutot que de
     * produire une valeur que le gateway decouperait au mauvais endroit.
     */
    public static final String SEPARATEUR_MAINTENANCE = "|";

    // ------------------------------------------------------------------
    // Messages de journalisation
    // ------------------------------------------------------------------
    public static final String LOG_POLITIQUE_PUBLIEE =
            "Politique de securite publiee : {} parametres";
    public static final String LOG_REGLES_IP_PUBLIEES =
            "Regles d'adresses publiees : {} actives";
    public static final String LOG_PAYS_PUBLIES =
            "Pays refuses publies : {}";
    public static final String LOG_MAINTENANCE_PUBLIEE =
            "Fenetres de maintenance publiees : {} actives";

    /**
     * Echec de publication.
     *
     * <p>Ne journalise que la CLASSE de l'erreur : un message d'erreur Redis
     * peut contenir l'URL de connexion, donc son hote et son port.
     */
    public static final String LOG_PUBLICATION_ECHOUEE =
            "Publication de la politique en echec ({}) : la base reste la source, "
                    + "la republication periodique ramenera le cache";

    public static final String LOG_PARAMETRE_MODIFIE =
            "Parametre de securite {} porte a {} par {}";
    public static final String LOG_PARAMETRE_REMIS_AU_DEFAUT =
            "Parametre de securite {} remis a son defaut par {}";

    /** Changements sensibles : journalises en WARN pour qu'une alerte les voie. */
    public static final String LOG_MAINTENANCE_ACTIVEE =
            "MAINTENANCE ACTIVEE sur {} par {} : {}";
    public static final String LOG_MAINTENANCE_LEVEE =
            "Maintenance levee sur {} par {}";
    public static final String LOG_IP_BLOQUEE =
            "Adresse bloquee ({}) par {}, echeance {}";
    public static final String LOG_IP_DEBLOQUEE =
            "Adresse debloquee par {}";
    public static final String LOG_PAYS_BLOQUE =
            "Pays {} refuse par {} : {}";
    public static final String LOG_PAYS_DEBLOQUE =
            "Pays {} de nouveau accepte, par {}";

    // ------------------------------------------------------------------
    // Messages de validation
    // ------------------------------------------------------------------
    public static final String CLE_INCONNUE =
            "Parametre de securite inconnu";
    public static final String VALEUR_HORS_BORNES =
            "Valeur hors des bornes autorisees pour ce parametre";
    public static final String VALEUR_DU_MAUVAIS_TYPE =
            "La valeur ne correspond pas au type du parametre";
    public static final String ADRESSE_REQUISE =
            "L'adresse est requise";
    public static final String MOTIF_REQUIS =
            "Le motif est requis";
    public static final String CODE_ISO_INVALIDE =
            "Le code pays doit comporter exactement deux lettres (ISO 3166-1 alpha-2)";
    public static final String MESSAGE_MAINTENANCE_REQUIS =
            "Le message de maintenance est requis : un refus sans message n'est pas interpretable";
    public static final String MESSAGE_MAINTENANCE_SEPARATEUR_INTERDIT =
            "Le message ne peut pas contenir le caractere « | »";
    public static final String PERMANENT_INTERDIT_POUR_DETECTION =
            "Une detection automatique doit porter une echeance : un faux positif "
                    + "permanent enferme une adresse sans recours";
    public static final String VALEUR_REQUISE =
            "La valeur est requise";
    public static final String VALEUR_TROP_LONGUE =
            "La valeur ne peut pas depasser 500 caracteres";
    /**
     * Contexte de securite vide sur un chemin protege.
     *
     * <p>Signale une regle d'autorisation manquante, pas un appel anonyme
     * legitime : toute la surface de ce service est derriere une regle.
     */
    public static final String APPELANT_NON_IDENTIFIE =
            "Appelant non identifie : le contexte de securite est vide sur un chemin protege";
    public static final String CODES_PAYS_REQUIS =
            "Au moins un code pays est requis";
    public static final String TROP_DE_CODES_PAYS =
            "Trop de codes pays en une seule demande";
    /**
     * Pays non refuse.
     *
     * <p>Distingue d'un code invalide, ce que l'implementation remplacee ne
     * faisait pas : {@code unblockCountry} ne validait rien, donc une faute de
     * frappe et un pays deja accepte rendaient la meme reponse.
     */
    public static final String PAYS_NON_REFUSE =
            "Ce pays n'est pas refuse";
    public static final String FENETRE_DEJA_TERMINEE =
            "La fin de la fenetre precede son debut, ou est deja passee : "
                    + "une telle fenetre ne couperait rien";
    public static final String FENETRE_INTROUVABLE =
            "Fenetre de maintenance introuvable";
    public static final String FENETRE_DEJA_LEVEE =
            "Cette fenetre a deja ete levee";
    public static final String SERVICE_REQUIS =
            "Le service est requis, ou « * » pour toute la plateforme";
    public static final String REGLE_IP_INTROUVABLE =
            "Regle d'adresse introuvable";
    public static final String REGLE_IP_DEJA_LEVEE =
            "Cette regle a deja ete levee";
    public static final String ADRESSE_INVALIDE =
            "L'adresse n'est pas une adresse IP litterale (IPv4 ou IPv6)";
    /**
     * Prefixe CIDR refuse.
     *
     * <p>L'applicateur — {@code IpBlacklistWebFilter} — repond par un
     * {@code hasKey} sur {@code security:ip:blacklist:<adresse>}, donc par une
     * correspondance de cle <b>exacte</b>. Une regle {@code 41.223.0.0/16}
     * serait enregistree, publiee, et ne correspondrait a aucune requete : le
     * blocage serait accepte, confirme a son auteur, et sans effet.
     *
     * <p>Refuser est le seul comportement honnete tant que l'applicateur ne sait
     * pas evaluer un prefixe. Accepter en silence reproduirait le defaut que ce
     * depot traque partout ailleurs.
     */
    public static final String CIDR_NON_APPLICABLE =
            "Un prefixe CIDR n'est pas applicable : le filtre compare des cles exactes, "
                    + "une regle de prefixe serait enregistree sans jamais bloquer. "
                    + "Posez les adresses une a une";
    /**
     * Adresse deja couverte par une regle active.
     *
     * <p>Remplacer la regle existante serait plus souple mais dangereux : une
     * decision administrative permanente pourrait etre rabaissee a une echeance
     * d'une heure sans que personne le voie. Lever puis reposer rend le
     * changement explicite et trace.
     */
    public static final String ADRESSE_DEJA_BLOQUEE =
            "Une regle active couvre deja cette adresse : levez-la avant d'en poser une autre";
    /**
     * Echeance deja passee.
     *
     * <p>Une telle regle serait enregistree, publiee — et ecartee par
     * {@code estActive} des l'ecriture. L'administrateur verrait un succes et
     * l'adresse ne serait jamais bloquee. Meme raison que
     * {@link #FENETRE_DEJA_TERMINEE} pour une fenetre de maintenance.
     */
    public static final String ECHEANCE_DEJA_PASSEE =
            "L'echeance est deja passee : la regle serait enregistree sans jamais bloquer";

    // ------------------------------------------------------------------
    // Codes d'erreur rendus a l'appelant
    // ------------------------------------------------------------------
    /** Refus portant sur la politique de securite. */
    public static final String CODE_POLITIQUE_REFUSEE = "SECURITY_POLICY_REFUSED";
    /** Ressource designee par un identifiant inconnu. */
    public static final String CODE_RESSOURCE_INTROUVABLE = "SECURITY_RESOURCE_NOT_FOUND";
    /** Corps de requete invalide, refuse par la validation declarative. */
    public static final String CODE_REQUETE_INVALIDE = "SECURITY_REQUEST_INVALID";
    public static final String REQUETE_INVALIDE = "Requete invalide";
}
