package com.abdatytch.media_service.service;

/**
 * Un type de document tiré du catalogue, déjà confronté au rôle de l'appelant.
 *
 * <h2>Pourquoi ce type existe</h2>
 *
 * Il remplace l'énumération compilée {@code DocumentType} sur le chemin d'envoi.
 * Le catalogue vit désormais en base — {@code DocumentTypeConfig} et
 * {@code DocumentRoleRequirement} — afin que les endpoints d'administration
 * puissent réellement créer, modifier et retirer des types. Tant que le chemin
 * d'envoi faisait {@code DocumentType.valueOf(code)}, ces endpoints écrivaient
 * des types que personne ne pouvait utiliser.
 *
 * <h2>Pourquoi un objet plutôt qu'une simple chaîne</h2>
 *
 * Deux raisons, et la seconde est structurelle.
 *
 * <p>D'abord, la lisibilité des erreurs : un refus doit nommer le document
 * — « Permis de conduire » — et non son code.
 *
 * <p>Ensuite, {@code obligatoire}. L'ancien code écrivait
 * {@code isMandatory(documentType.isRequiredForRole(userRole))} <b>au milieu de
 * la construction de l'entité</b>, ce qu'une énumération permet puisqu'elle
 * répond sans entrée-sortie. La même information vient maintenant de la base,
 * donc d'un {@code Mono} : la demander à cet endroit obligerait à casser le
 * constructeur en deux, ou — bien pire — à l'y bloquer.
 *
 * <p>La résolution a donc lieu <b>une seule fois</b>, en tête de l'envoi, et le
 * résultat circule. C'est aussi ce qui garantit qu'un seul aller-retour est fait
 * par envoi, et non un par champ qui s'en sert.
 *
 * <h2>Les deux limites, enfin portées jusqu'au pipeline</h2>
 *
 * {@code acceptedMimeTypes} et {@code maxFileSizeMB} sont saisis par un
 * administrateur dans le catalogue, persistés, et renvoyés par l'API — mais
 * <b>aucun code de l'envoi ne les lisait</b>. Un administrateur réglait une
 * limite de taille pour un type de document, elle était acceptée, et le
 * pipeline l'ignorait.
 *
 * <p>Pire : il n'existait <b>aucune</b> borne de taille, d'aucune sorte.
 * {@code StorageConstants.MAX_FILE_SIZE} déclare 100 Mo et n'était appliqué
 * nulle part, et {@code DataBufferUtils.join} était appelé sans
 * {@code maxByteCount}. Le fichier était donc lu entier en mémoire, puis
 * recopié à chaque étape — lecture, compression, filigrane, chiffrement, dépôt
 * — soit environ cinq copies par envoi simultané, sur le service qui détient
 * les pièces d'identité.
 *
 * <p>Les porter ici plutôt que de les relire en base dans le pipeline préserve
 * la règle de ce record : <b>un seul aller-retour par envoi</b>. La
 * configuration est déjà chargée par {@code resoudre}.
 *
 * @param code              le code du catalogue, par exemple {@code NATIONAL_ID}
 * @param libelle           le nom d'affichage, destiné aux messages et aux réponses
 * @param obligatoire       si ce document est exigé du rôle de l'appelant. Vient de
 *                          {@code DocumentRoleRequirement}, et vaut {@code false}
 *                          pour un type universel — un type qu'aucune affectation
 *                          de rôle ne mentionne
 * @param typesMimeAcceptes les types MIME autorisés pour ce type de document.
 *                          <b>Vide ou nul signifie « pas de restriction propre »</b>
 *                          : la liste globale de {@code StorageConstants}
 *                          s'applique alors seule. Ne jamais interpréter vide
 *                          comme « rien n'est autorisé » — tous les types du
 *                          seeder sont dans ce cas, et le dépôt deviendrait
 *                          impossible
 * @param tailleMaxMo       la taille maximale en mégaoctets, ou {@code null} pour
 *                          retomber sur le plafond global
 */
public record TypeDeDocumentResolu(
        String code,
        String libelle,
        boolean obligatoire,
        java.util.List<String> typesMimeAcceptes,
        Integer tailleMaxMo) {

    /**
     * La limite effective en octets.
     *
     * <p>Celle du type si elle est renseignée et strictement positive, sinon le
     * plafond global. Une valeur nulle ou négative en base ne doit pas rendre
     * la limite <b>infinie</b> : ce serait transformer une erreur de saisie en
     * absence de protection.
     */
    public long limiteEnOctets() {
        if (tailleMaxMo != null && tailleMaxMo > 0) {
            return tailleMaxMo * 1024L * 1024L;
        }
        return com.abdatytch.media_service.constants.StorageConstants.MAX_FILE_SIZE;
    }

    /**
     * Ce type MIME est-il accepté <b>par ce type de document</b> ?
     *
     * <p>La liste globale de {@code StorageConstants} s'applique de toute façon,
     * en amont : cette méthode n'ajoute qu'une restriction, elle n'en lève
     * aucune.
     */
    public boolean accepteLeTypeMime(String typeMime) {
        if (typesMimeAcceptes == null || typesMimeAcceptes.isEmpty()) {
            return true;
        }
        return typesMimeAcceptes.contains(typeMime);
    }
}
