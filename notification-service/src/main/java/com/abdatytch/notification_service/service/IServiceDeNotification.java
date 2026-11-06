package com.abdatytch.notification_service.service;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.CompteRenduDeRemise;
import com.abdatytch.commons.notification.DemandeDeNotification;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Le point d'entree unique du service : une demande entre, un compte rendu sort.
 *
 * <p>Une seule methode, et c'est voulu. Le bus Kafka et l'endpoint interne
 * appellent tous deux celle-ci : si chacun avait sa propre logique, la politique
 * de repli entre canaux existerait en deux versions, et une correction se ferait
 * une fois sur deux. C'est le defaut que ce depot a corrige sur les chemins
 * publics, sur la lecture d'adresse IP et sur la lecture des roles.
 */
public interface IServiceDeNotification {

    /**
     * Traite une demande : enregistre, rend, remet par canal, rend compte.
     *
     * <p>Ne signale JAMAIS d'erreur. Un echec de canal est un etat dans le
     * compte rendu, pas une exception : sans cela, un serveur SMTP injoignable
     * ferait perdre la notification au lieu de la laisser en boite de reception.
     *
     * @return toujours un compte rendu, meme quand aucun canal n'aboutit
     */
    Mono<CompteRenduDeRemise> traiter(DemandeDeNotification demande);

    /**
     * Rejoue UN canal d'une notification deja enregistree.
     *
     * <p>Appele par la reprise, et non par un emetteur : la notification existe
     * en base, et son texte se reconstruit depuis son modele et ses variables —
     * c'est precisement pourquoi la table conserve le gabarit plutot que le
     * texte rendu.
     *
     * <p>Un seul canal, et non la notification entiere : les autres canaux ont
     * peut-etre abouti du premier coup, et les rejouer enverrait un doublon.
     * L'unite de reprise est la ligne de {@code remise_par_canal}, pas la
     * notification.
     *
     * @param notificationId la notification a rejouer
     * @param canal          le canal a reessayer
     * @return l'etat obtenu, deja ecrit avec son compteur de tentatives
     *         incremente. Termine <b>vide</b> si la notification a disparu entre
     *         la selection et le rejeu — un effacement RGPD, par exemple
     */
    Mono<CompteRenduDeRemise.Etat> rejouer(UUID notificationId, CanalDeNotification canal);
}
