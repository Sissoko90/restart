package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.service.CanalDeRemise;
import com.abdatytch.notification_service.service.IMessagerieDirecte;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Rend le modele, puis remet par le canal demande. Rien d'autre.
 *
 * <h2>Ce que cette classe ne fait PAS, et c'est le point</h2>
 *
 * Elle ne rend aucun texte elle-meme, n'ouvre aucune connexion SMTP, ne compte
 * aucun SMS. Le rendu est celui de {@link MoteurDeModeles}, la remise celle des
 * {@link CanalDeRemise} — <b>les memes objets</b> que ceux de l'orchestrateur,
 * injectes par la meme liste.
 *
 * <p>C'est ce qui fait que les garde-fous de depense du SMS s'appliquent ici
 * sans une ligne de plus, et qu'une correction de modele posee a chaud touche
 * aussi bien les codes de verification que les notifications. Un second moteur
 * ou un second chemin SMTP aurait redonne deux versions d'une meme chose, qui
 * divergent des la premiere correction — exactement ce que cette passe supprime.
 *
 * <h2>Ni enregistrement, ni consentement, ni idempotence</h2>
 *
 * Les trois manquent volontairement, et chacun pour une raison propre :
 *
 * <ul>
 *   <li><b>pas d'enregistrement</b> : ecrire le corps en base y laisserait un
 *       code a usage unique encore valide, lisible par son proprietaire ;</li>
 *   <li><b>pas de consentement</b> : une preference « pas de SMS » est un
 *       reglage de confort, et l'appliquer a un code de connexion enfermerait
 *       la personne hors de son compte ;</li>
 *   <li><b>pas de controle de doublon</b> : il n'y a pas de {@code demandeId} a
 *       comparer, et il n'en faut pas. L'appelant est synchrone : s'il rejoue,
 *       c'est qu'il veut un second envoi — et c'est lui qui tient l'anti-rejeu,
 *       par « un code encore valide interdit d'en redemander un ».</li>
 * </ul>
 */
@Service
public class MessagerieDirecteImpl implements IMessagerieDirecte {

    private static final Logger log = LoggerFactory.getLogger(MessagerieDirecteImpl.class);

    private final MoteurDeModeles moteur;
    private final Map<CanalDeNotification, CanalDeRemise> canaux =
            new EnumMap<>(CanalDeNotification.class);

    public MessagerieDirecteImpl(MoteurDeModeles moteur,
                                 List<CanalDeRemise> canauxDisponibles) {
        this.moteur = moteur;
        // Tous les canaux sont enregistres, et le filtrage se fait a l'appel par
        // CANAUX_ACCEPTES. Ne garder ici que les canaux acceptes rendrait le
        // refus indistinguable de « canal non implemente », donc le motif
        // mensonger pour IN_APP, qui est bien implemente — simplement pas par
        // ce chemin.
        canauxDisponibles.forEach(c -> this.canaux.put(c.canal(), c));
        log.info("Messagerie directe : canaux acceptes {}, implementations connues {}",
                CANAUX_ACCEPTES, this.canaux.keySet());
    }

    @Override
    public Mono<Resultat> remettre(Demande demande) {
        if (demande.destinataire() == null || demande.destinataire().isBlank()) {
            return Mono.just(Resultat.refuse("aucun destinataire"));
        }
        if (demande.canal() == null || !CANAUX_ACCEPTES.contains(demande.canal())) {
            // Le motif nomme le canal demande et la liste : sans cela,
            // l'appelant d'un IN_APP ne saurait pas que ce chemin n'est pas
            // celui des notifications.
            return Mono.just(Resultat.refuse("canal " + demande.canal()
                    + " non acceptable par ce chemin, qui ne sert que "
                    + CANAUX_ACCEPTES));
        }

        CanalDeRemise implementation = canaux.get(demande.canal());
        if (implementation == null) {
            return Mono.just(Resultat.refuse("canal non implemente"));
        }
        // Lu a CHAQUE appel, et non une fois au demarrage : un canal peut
        // devenir indisponible — configuration retiree, operateur resilie.
        if (!implementation.estActif()) {
            return Mono.just(Resultat.refuse("canal inactif"));
        }

        var contenu = moteur.rendre(demande.modele(), demande.canal(), demande.variables());
        if (contenu.isEmpty()) {
            // Un modele absent n'envoie RIEN, et ne compose surtout pas un texte
            // vide : un message vide fait croire a l'emetteur qu'il a prevenu.
            log.warn("Modele {} introuvable pour le canal {} : rien n'est parti",
                    demande.modele(), demande.canal());
            return Mono.just(Resultat.refuse("modele introuvable"));
        }

        var message = new CanalDeRemise.MessageARemettre(
                // destinataireId et notificationId nuls : aucune notification
                // n'est enregistree, et aucun canal de CANAUX_ACCEPTES ne les
                // lit. C'est precisement pourquoi la liste blanche existe — un
                // canal qui en aurait besoin ecrirait une ligne sans
                // identifiant.
                null,
                demande.destinataire(),
                contenu.get().sujet(),
                contenu.get().corps(),
                demande.urgence(),
                null);

        return implementation.remettre(message)
                .map(resultat -> resultat.aAbouti()
                        ? Resultat.accepte()
                        : Resultat.refuse(resultat.motif()))
                // Un canal ne devrait jamais signaler d'erreur ; s'il le fait,
                // elle ne doit pas remonter en 500 chez l'appelant, qui a deja
                // enregistre son code et peut proposer un autre canal.
                .onErrorResume(imprevu -> {
                    log.error("Canal {} a leve une exception : {}", demande.canal(),
                            imprevu.getClass().getSimpleName());
                    return Mono.just(Resultat.refuse(
                            imprevu.getClass().getSimpleName()));
                });
    }
}
