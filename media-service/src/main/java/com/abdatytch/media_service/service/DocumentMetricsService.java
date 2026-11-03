package com.abdatytch.media_service.service;

/**
 * Service de métriques Prometheus pour monitoring.
 *
 * <h2>Le paramètre de type a été retiré, pas converti</h2>
 *
 * Chaque méthode recevait un {@code DocumentType} et l'<b>ignorait</b> : les
 * compteurs sont bruts, sans étiquette. Une signature qui promet une mesure par
 * type sans la produire est pire qu'une signature qui ne la promet pas.
 *
 * <p>Il n'a pas été transformé en étiquette Prometheus, et la raison vient de la
 * migration même : le catalogue des types vit désormais <b>en base</b>. Une
 * étiquette par code serait donc de <b>cardinalité non bornée</b> — un
 * administrateur créant des types ferait proliférer les séries temporelles, et
 * c'est la façon la plus classique de faire tomber un Prometheus. L'énumération
 * bornait cela à vingt-cinq valeurs ; la base ne borne rien.
 *
 * <p>Si une mesure par type devient nécessaire, elle devra passer par une liste
 * blanche de codes explicitement observés, et non par le code reçu.
 */
public interface DocumentMetricsService {

    void incrementUploadCounter();

    void incrementApprovedCounter();

    void incrementRejectedCounter();

    void incrementDeletedCounter();

    void incrementSecurityThreatCounter(String threatType);

    /**
     * Compte un rejet pour qualite insuffisante.
     *
     * <p>{@code documents.rejected.total} ne compte que les rejets
     * d'ADMINISTRATEUR, par {@code rejectDocument}. Un document refuse sous
     * 70/100 par l'analyse de qualite levait une exception et n'incrementait
     * <b>rien</b> : un taux de rejet qui s'envole — seuil trop severe, ou
     * client qui envoie mal — etait invisible.
     *
     * @param raison {@code ILLISIBLE} ou {@code SCORE_INSUFFISANT}. Deux
     *               valeurs, donc etiquette bornee. Les deux conditions sont
     *               distinctes et ne se corrigent pas de la meme facon.
     */
    void incrementQualityRejectedCounter(String raison);

    /**
     * Compte un echec d'operation sur le stockage objet.
     *
     * <p>{@code MinioHealthIndicator} dit si MinIO repond ; il ne dit rien des
     * ecritures qui echouent alors que le service repond.
     *
     * @param operation {@code DEPOT}, {@code LECTURE} ou {@code SUPPRESSION}.
     */
    void incrementStorageFailureCounter(String operation);

    /**
     * Compte l'echec d'une ecriture <b>secondaire</b> du pipeline d'envoi.
     *
     * <p>Le document est enregistre et l'envoi reussit ; ce qui a echoue est le
     * suivi de completion ou la ligne de journal d'acces. Faire echouer l'envoi
     * pour autant serait pire — le fichier est deja dans MinIO et la fiche en
     * base — mais l'avaler sans trace reviendrait a troquer une annulation
     * silencieuse contre une perte silencieuse.
     *
     * <p>Ces ecritures etaient auparavant dans la portee d'un
     * {@code @Transactional} qui ne pouvait pas demarrer. Elles sont desormais
     * explicitement non fatales, et comptees pour que leur echec se voie.
     *
     * @param etape {@code EXIGENCE} ou {@code JOURNAL_ACCES}. Deux valeurs,
     *              donc etiquette bornee.
     */
    void incrementEcritureSecondaireEchouee(String etape);

    void recordUploadDuration(long durationMs);

    void recordDownloadDuration(long durationMs);
}
