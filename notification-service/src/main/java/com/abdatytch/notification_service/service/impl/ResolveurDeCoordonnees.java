package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import org.springframework.stereotype.Component;

/**
 * Ou joindre une personne, pour un canal donne.
 *
 * <h2>Pourquoi cette etape existe</h2>
 *
 * Parce que l'emetteur ne fournit PAS de coordonnee : il donne un
 * {@code destinataireId}. Trois raisons, detaillees dans
 * {@code DemandeDeNotification} — les coordonnees changent et un message qui en
 * recopie une part a l'ancienne adresse ; une adresse dans un message Kafka est
 * une donnee personnelle que l'effacement RGPD ne peut pas atteindre ; et un
 * seul service doit savoir ou joindre quelqu'un, sinon le consentement se
 * verifie a sept endroits.
 *
 * <h2>ETAT ACTUEL : seule la coordonnee de secours est resolue</h2>
 *
 * Cette implementation ne sait lire que {@code destinataireDeSecours}, le champ
 * prevu pour les destinataires sans compte — confirmation d'inscription,
 * invitation. Pour un {@code destinataireId} ordinaire, elle rend
 * {@code null}, et les canaux concernes repondent alors
 * « aucune adresse connue » : {@code REFUSE}, visible dans le compte rendu et
 * compte dans {@code notification.remises.total}.
 *
 * <p>C'est une limite ANNONCEE, pas un oubli, et elle se voit : les canaux
 * COURRIEL, SMS et PUSH refusent tant qu'elle n'est pas levee. Les canaux
 * IN_APP et WEBSOCKET fonctionnent, eux, puisqu'ils n'ont besoin d'aucune
 * coordonnee — c'est pourquoi la chaine complete est deja utile.
 *
 * <h2>Ce qu'il faut pour la lever, et la decision qu'elle porte</h2>
 *
 * Un appel a user-service, qui detient les contacts. Mais cet appel ne doit
 * surtout pas etre naif :
 *
 * <ul>
 *   <li>il est sur le chemin de CHAQUE notification, donc il faut un cache avec
 *       une duree de vie courte — un contact modifie ne doit pas continuer a
 *       recevoir pendant une heure ;</li>
 *   <li>une panne de user-service ne doit pas faire perdre la notification :
 *       {@code ECHOUE} et non {@code REFUSE}, pour que la reprise la
 *       rattrape ;</li>
 *   <li>c'est aussi le bon endroit pour verifier le CONSENTEMENT par canal. Un
 *       utilisateur qui a refuse les SMS ne doit pas en recevoir, et ce controle
 *       a un seul endroit valait mieux que dans chaque emetteur.</li>
 * </ul>
 */
@Component
public class ResolveurDeCoordonnees {

    /**
     * La coordonnee a utiliser, ou {@code null} si elle est inconnue.
     *
     * <p>{@code null} et non une chaine vide : le canal distingue ainsi
     * « inconnue » de « vide », et son message de refus est exact.
     */
    public String pour(DemandeDeNotification demande, CanalDeNotification canal) {
        // IN_APP et WEBSOCKET n'ont pas de coordonnee : ils routent sur
        // l'identifiant du porteur. Rendre null ici est la bonne reponse, et les
        // canaux concernes ne la consultent pas.
        if (canal == CanalDeNotification.IN_APP || canal == CanalDeNotification.WEBSOCKET) {
            return null;
        }

        String secours = demande.destinataireDeSecours();
        return secours != null && !secours.isBlank() ? secours : null;
    }
}
