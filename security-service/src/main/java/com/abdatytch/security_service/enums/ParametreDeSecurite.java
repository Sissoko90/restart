package com.abdatytch.security_service.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * Catalogue de la politique de securite : <b>le</b> detenteur unique.
 *
 * <h2>Pourquoi un catalogue, et non des colonnes</h2>
 *
 * Une table a une colonne par parametre demanderait une migration a chaque
 * ajout. Un magasin cle/valeur libre, comme celui de {@code setting-service},
 * accepte n'importe quelle cle et n'importe quelle valeur : une faute de frappe
 * dans la cle cree un parametre fantome que rien ne lit, et la lecture retombe
 * sur le defaut sans que personne le remarque.
 *
 * <p>Le catalogue tranche : seules les valeurs declarees ici existent, chacune
 * porte son type, son defaut et ses bornes. Une cle inconnue est refusee a
 * l'ecriture, et {@link #values()} rend la politique <b>complete</b> — ce qui est
 * exactement ce qu'il faut pour la publier dans Redis.
 *
 * <h2>Ce qui remplace SettingCategory.SECURITY</h2>
 *
 * {@code setting-service} portait {@code security.max-login-attempts},
 * {@code security.lockout-duration-minutes}, {@code security.password-expiry-days}
 * et {@code security.force-password-change}. Les quatre sont repris ici, sous le
 * meme nom de cle, de sorte que la migration des lignes existantes soit une
 * copie et non une traduction.
 *
 * <p>Ils sont retires de {@code setting-service} dans le meme mouvement : deux
 * magasins pour une meme decision est precisement le defaut qui a fait qu'un
 * verrouillage administrateur ne verrouillait personne dans ce depot.
 *
 * <h2>La portee n'est pas cosmetique</h2>
 *
 * {@link Portee#UTILISATEUR} et {@link Portee#ADMINISTRATION} designent des
 * publics dont les exigences divergent : une session d'administration doit etre
 * courte, une session client peut durer. Un parametre unique forcerait a choisir
 * entre genez les clients ou exposez l'administration.
 */
public enum ParametreDeSecurite {

    // ==================================================================
    // Inscription et verification des contacts
    // ==================================================================

    /**
     * Impose la confirmation de l'adresse avant toute connexion.
     *
     * <p>Faux par defaut, et ce defaut est deliberе : passer a vrai sur une
     * installation existante enferme dehors tous les comptes deja inscrits sans
     * adresse confirmee. L'activation doit etre un acte conscient, precede d'une
     * campagne de confirmation.
     */
    EMAIL_CONFIRME_OBLIGATOIRE("security.email-confirme-obligatoire",
            TypeDeParametre.BOOLEEN, "false", Portee.UTILISATEUR),

    /** Impose la confirmation du numero. Meme precaution que l'adresse. */
    TELEPHONE_CONFIRME_OBLIGATOIRE("security.telephone-confirme-obligatoire",
            TypeDeParametre.BOOLEEN, "false", Portee.UTILISATEUR),

    // ==================================================================
    // Second facteur
    // ==================================================================

    /**
     * Rend le second facteur obligatoire pour les comptes clients.
     *
     * <p>Distinct du parametre d'administration : l'imposer aux clients est une
     * decision commerciale, l'imposer a l'administration est une decision de
     * securite. Les confondre obligerait a refuser l'un pour obtenir l'autre.
     */
    SECOND_FACTEUR_OBLIGATOIRE_UTILISATEUR("security.second-facteur-obligatoire-utilisateur",
            TypeDeParametre.BOOLEEN, "false", Portee.UTILISATEUR),

    /** Rend le second facteur obligatoire pour l'administration. */
    SECOND_FACTEUR_OBLIGATOIRE_ADMIN("security.second-facteur-obligatoire-admin",
            TypeDeParametre.BOOLEEN, "true", Portee.ADMINISTRATION),

    // ==================================================================
    // Sessions
    // ==================================================================

    /** Duree de validite d'une session client. */
    DUREE_SESSION_UTILISATEUR("security.duree-session-utilisateur-minutes",
            TypeDeParametre.DUREE_MINUTES, "1440", Portee.UTILISATEUR, 5, 43_200),

    /**
     * Duree de validite d'une session d'administration.
     *
     * <p>Plus courte par defaut que celle d'un client, et le plafond est plus
     * bas : une session d'administration oubliee sur un poste partage donne
     * acces a la surface la plus sensible de la plateforme.
     */
    DUREE_SESSION_ADMIN("security.duree-session-admin-minutes",
            TypeDeParametre.DUREE_MINUTES, "60", Portee.ADMINISTRATION, 5, 720),

    // ==================================================================
    // Verrouillage et force brute
    // ==================================================================

    /**
     * Tentatives echouees avant verrouillage.
     *
     * <p>Cle reprise telle quelle de {@code setting-service}. Le minimum est
     * trois : a un, une faute de frappe verrouille, et le verrou devient une
     * gene pour le proprietaire plus qu'une protection.
     */
    TENTATIVES_AVANT_BLOCAGE("security.max-login-attempts",
            TypeDeParametre.ENTIER, "5", Portee.GLOBALE, 3, 20),

    /** Duree du verrou. Cle reprise de {@code setting-service}. */
    DUREE_VERROUILLAGE("security.lockout-duration-minutes",
            TypeDeParametre.DUREE_MINUTES, "15", Portee.GLOBALE, 1, 10_080),

    /**
     * Bloque sept jours une adresse reconnue en force brute, sans intervention.
     *
     * <p>Faux par defaut, et c'est un choix assume. Un blocage de sept jours
     * sans intervention humaine touche aussi les adresses partagees : derriere
     * un NAT d'operateur, un seul attaquant bloque tout un quartier. Le
     * parametre existe parce que la decision vous revient, pas parce qu'elle est
     * evidente.
     */
    BLOCAGE_LONG_SUR_FORCE_BRUTE("security.blocage-long-force-brute",
            TypeDeParametre.BOOLEEN, "false", Portee.GLOBALE),

    /** Duree du blocage long, quand il est actif. */
    DUREE_BLOCAGE_LONG("security.duree-blocage-long-minutes",
            TypeDeParametre.DUREE_MINUTES, "10080", Portee.GLOBALE, 60, 43_200),

    // ==================================================================
    // Debit
    // ==================================================================

    /**
     * Requetes autorisees par minute et par adresse.
     *
     * <p>Applique par le gateway, qui lit cette valeur dans Redis — jamais en
     * appelant ce service. Un appel par requete mettrait un saut reseau sur le
     * chemin critique, ce que l'architecture de cette plateforme refuse.
     */
    REQUETES_PAR_MINUTE_PAR_IP("security.requetes-par-minute-par-ip",
            TypeDeParametre.ENTIER, "120", Portee.GLOBALE, 10, 10_000),

    // ==================================================================
    // Donnees personnelles
    // ==================================================================

    /**
     * Autorise le client a declencher lui-meme l'export de ses donnees.
     *
     * <p>Vrai par defaut : la loi n° 2013-015 ouvre un droit d'acces, et le
     * fermer par defaut obligerait chaque demande a passer par un humain. Le
     * parametre permet de le couper si le parcours doit etre suspendu.
     */
    EXPORT_LIBRE_SERVICE("security.export-libre-service",
            TypeDeParametre.BOOLEEN, "true", Portee.UTILISATEUR),

    // ==================================================================
    // Repris de setting-service, conserves pour ne rien perdre
    // ==================================================================

    /** Expiration des mots de passe. Zero signifie « jamais ». */
    EXPIRATION_MOT_DE_PASSE_JOURS("security.password-expiry-days",
            TypeDeParametre.ENTIER, "90", Portee.GLOBALE, 0, 3_650),

    /** Impose un changement de mot de passe a la prochaine connexion. */
    CHANGEMENT_MOT_DE_PASSE_FORCE("security.force-password-change",
            TypeDeParametre.BOOLEEN, "false", Portee.GLOBALE),

    // ==================================================================
    // Repris de SettingCategory.SYSTEM, qui portait de la securite
    // ==================================================================
    // Ces cinq parametres etaient ranges sous « system » dans
    // setting-service, mais decident de la securite. Les laisser la-bas
    // aurait recree exactement ce que ce service corrige : deux magasins pour
    // une meme decision, l'un typé et publie, l'autre non.

    /**
     * Impose la complexite du mot de passe.
     *
     * <p>Etait {@code system.password-complexity-enabled}. La regle elle-meme —
     * majuscule, minuscule, chiffre, huit caracteres — vit dans
     * {@code AppConstants.PASSWORD_PATTERN} d'auth-service, et ce parametre
     * decide seulement si elle s'applique.
     */
    COMPLEXITE_MOT_DE_PASSE("security.complexite-mot-de-passe",
            TypeDeParametre.BOOLEEN, "true", Portee.GLOBALE);

    /** Public concerne par un parametre. */
    public enum Portee {
        /** Comptes clients. */
        UTILISATEUR,
        /** Comptes d'administration. */
        ADMINISTRATION,
        /** Les deux, sans distinction possible. */
        GLOBALE
    }

    private final String cle;
    private final TypeDeParametre type;
    private final String valeurParDefaut;
    private final Portee portee;
    private final Integer minimum;
    private final Integer maximum;

    ParametreDeSecurite(String cle, TypeDeParametre type, String valeurParDefaut,
                        Portee portee) {
        this(cle, type, valeurParDefaut, portee, null, null);
    }

    ParametreDeSecurite(String cle, TypeDeParametre type, String valeurParDefaut,
                        Portee portee, Integer minimum, Integer maximum) {
        this.cle = cle;
        this.type = type;
        this.valeurParDefaut = valeurParDefaut;
        this.portee = portee;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public String getCle() {
        return cle;
    }

    public TypeDeParametre getType() {
        return type;
    }

    public String getValeurParDefaut() {
        return valeurParDefaut;
    }

    public Portee getPortee() {
        return portee;
    }

    public Integer getMinimum() {
        return minimum;
    }

    public Integer getMaximum() {
        return maximum;
    }

    /**
     * Retrouve un parametre par sa cle.
     *
     * <p>Rend un {@link Optional} vide et ne leve pas : une cle inconnue vient
     * d'un appelant, et l'appelant doit recevoir un refus nomme plutot qu'une
     * exception non traduite.
     */
    public static Optional<ParametreDeSecurite> parCle(String cle) {
        if (cle == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(parametre -> parametre.cle.equals(cle))
                .findFirst();
    }
}
