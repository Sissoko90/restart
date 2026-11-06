package com.abdatytch.notification_service.service.sms;

import reactor.core.publisher.Mono;

/**
 * Mémoire courte des SMS remis au fournisseur, pour pouvoir interpréter ses
 * accusés de réception.
 *
 * <h2>Pourquoi ce registre existe</h2>
 *
 * AfrikSMS rend un {@code resourceId} à l'envoi, puis rappelle plus tard notre
 * serveur avec <b>ce seul identifiant</b> et un code de livraison. Sans
 * correspondance conservée de notre côté, l'accusé dit
 * « {@code P7COiDNjFvZ} : numéro injoignable » — une phrase qui ne désigne
 * personne et ne permet rien.
 *
 * <p>Le point d'entrée des accusés serait alors une protection écrite et
 * inutilisable : c'est le défaut le plus fréquent de ce dépôt, et il n'est pas
 * question de le reproduire en recevant des accusés qu'on ne sait pas lire.
 *
 * <h2>Ce qui est conservé, et ce qui ne l'est pas</h2>
 *
 * Le numéro du destinataire seulement. <b>Jamais le contenu du message</b> :
 * conserver le code à usage unique au-delà de son envoi lui donnerait une
 * seconde vie dans Redis, bien après sa péremption fonctionnelle.
 *
 * <p>La durée de vie est courte et bornée : un accusé qui arriverait des jours
 * plus tard n'intéresse personne, et une entrée sans date d'expiration
 * s'accumulerait indéfiniment.
 */
public interface IRegistreDesEnvoisSms {

    /**
     * Note qu'un SMS a été accepté par le fournisseur.
     *
     * <p>L'échec de cette écriture ne doit <b>jamais</b> faire échouer l'envoi :
     * le SMS est parti, et le registre n'est qu'un confort de diagnostic. Les
     * appelants le traitent comme un enrichissement.
     */
    Mono<Void> enregistrer(String resourceId, String numeroDeTelephone);

    /**
     * @return le numéro associé, ou un {@code Mono} <b>vide</b> si l'identifiant
     *         est inconnu — accusé en retard, registre purgé, ou identifiant
     *         forgé
     */
    Mono<String> numeroDe(String resourceId);
}
