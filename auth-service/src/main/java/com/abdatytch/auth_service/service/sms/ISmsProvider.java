package com.abdatytch.auth_service.service.sms;

import reactor.core.publisher.Mono;

/**
 * Transporteur de SMS : la seule chose que le reste du service connaisse d'un
 * fournisseur.
 *
 * <p>Deux implémentations coexistent, {@link AfrikSmsProvider} et
 * {@link TwilioSmsProvider}, et c'est {@code sms.provider} qui désigne celle qui
 * sert. Les deux sont des beans en permanence : le choix se fait par nom dans
 * {@code SmsServiceImpl}, et non par {@code @ConditionalOnProperty}, pour qu'un
 * nom mal orthographié dans l'environnement produise un <b>échec de démarrage
 * nommant les fournisseurs disponibles</b> plutôt qu'un contexte sans aucun
 * transporteur, où l'envoi de SMS disparaîtrait en silence.
 *
 * <h2>Contrat sur les journaux</h2>
 *
 * Une implémentation reçoit le <b>contenu du message</b>, donc le code à usage
 * unique. Elle ne doit le passer à aucun logger, ni directement, ni à travers
 * une exception : ni le contenu, ni ses identifiants d'API ne doivent pouvoir
 * atteindre les journaux par l'exception qu'elle propage. C'est un point
 * concret, pas une précaution de principe — voir la javadoc de
 * {@link AfrikSmsProvider#envoyer}.
 *
 * <p>{@code AucunJustificatifDansLesJournauxTest} (platform-commons) balaie les
 * sources de tous les modules et verrouille la première moitié de cette règle.
 */
public interface ISmsProvider {

    /**
     * Nom du fournisseur, tel qu'on l'écrit dans {@code sms.provider}.
     *
     * <p>Comparé sans tenir compte de la casse ni des espaces : une valeur
     * venue d'un fichier {@code .env} arrive souvent avec l'une ou l'autre.
     */
    String nom();

    /**
     * Les identifiants nécessaires à l'envoi sont-ils tous présents ?
     *
     * <p>Interrogé au démarrage par {@code SmsServiceImpl}, qui avertit si le
     * fournisseur <b>sélectionné</b> n'est pas configuré. Sans cela, le défaut
     * n'apparaîtrait qu'au premier envoi réel, c'est-à-dire à la première
     * inscription d'un utilisateur.
     */
    boolean estConfigure();

    /**
     * Remet le message au fournisseur.
     *
     * @param numeroDeTelephone destinataire, dans la forme où il est stocké
     *                          (l'implémentation l'adapte à son API)
     * @param contenu           texte du SMS, qui contient le code à usage unique
     * @return un {@code Mono} vide en cas de succès, en erreur sinon
     */
    Mono<Void> envoyer(String numeroDeTelephone, String contenu);
}
