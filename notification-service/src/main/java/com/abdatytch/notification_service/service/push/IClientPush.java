package com.abdatytch.notification_service.service.push;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * L'envoi de notifications push, vu par le canal.
 *
 * <h2>Pourquoi une interface devant le SDK</h2>
 *
 * Pour la meme raison que {@code ISmsService} devant les fournisseurs SMS : le
 * canal decide QUOI pousser et QUE FAIRE du resultat, le client sait seulement
 * pousser. Un test du canal n'a alors pas besoin d'un compte Google.
 *
 * <h2>Le resultat est PAR JETON, et c'est l'essentiel</h2>
 *
 * Un envoi vers cinq appareils peut aboutir pour trois et echouer pour deux,
 * dont un parce que l'application a ete desinstallee. Un booleen global
 * perdrait cette information, et on pousserait indefiniment vers des appareils
 * disparus — en payant un appel reseau a chaque notification, et en portant un
 * echec qui ne se reparera jamais dans chaque compte rendu.
 */
public interface IClientPush {

    /**
     * Le client peut-il pousser ?
     *
     * <p>Faux quand le compte de service Firebase n'est pas configure. Lu a
     * CHAQUE tentative et non une fois au demarrage, comme pour les autres
     * canaux : rien ne garantit qu'une configuration reste valide.
     */
    boolean estConfigure();

    /**
     * Pousse le meme message vers plusieurs appareils.
     *
     * @param jetons les jetons d'enregistrement, au moins un
     * @param titre  le titre affiche par le systeme. Peut etre vide
     * @param corps  le texte affiche
     * @return toujours un resultat, jamais une erreur : une panne de FCM ne
     *         doit pas interrompre la remise des AUTRES canaux de la meme
     *         notification
     */
    Mono<Resultat> pousser(List<String> jetons, String titre, String corps);

    /**
     * Ce que l'envoi a produit.
     *
     * @param acceptes        nombre d'appareils pour lesquels FCM a accepte le
     *                        message
     * @param jetonsInvalides jetons que FCM declare definitivement
     *                        injoignables — application desinstallee, jeton
     *                        remplace. A SUPPRIMER, pas a reessayer
     * @param motif           en clair quand rien n'a abouti, {@code null}
     *                        sinon. SANS donnee personnelle et SANS jeton : ce
     *                        motif part en base et dans les journaux
     */
    record Resultat(int acceptes, List<String> jetonsInvalides, String motif) {

        public static Resultat refuse(String motif) {
            return new Resultat(0, List.of(), motif);
        }

        public boolean aAbouti() {
            return acceptes > 0;
        }
    }
}
