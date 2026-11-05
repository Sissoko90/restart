package com.abdatytch.notification_service.service;

import com.abdatytch.notification_service.metrics.NotificationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qui est connecte, et par quels flux.
 *
 * <h2>Un porteur, PLUSIEURS flux</h2>
 *
 * Une meme personne ouvre l'application sur son telephone et dans deux onglets.
 * Une table de l'identifiant vers UN flux aurait ecrase le precedent a chaque
 * nouvelle connexion : seul le dernier appareil aurait recu quelque chose, et
 * sans erreur puisque l'envoi a ce flux reussit.
 *
 * <h2>Pourquoi un {@link Sinks.Many} et non la session elle-meme</h2>
 *
 * {@code WebSocketSession.send()} ne s'abonne qu'une fois. Le gestionnaire
 * l'abonne a l'ouverture ; tout envoi ulterieur venu d'un autre thread serait
 * perdu. On pousse donc dans un flux que le gestionnaire branche une seule fois.
 *
 * <p>Le tampon est <b>borne</b> : un client qui ne lit pas ne doit pas faire
 * grossir la memoire du service sans limite. Au-dela, l'emission echoue et le
 * canal le declare — preferable a une fuite silencieuse.
 *
 * <h2>Ce registre est LOCAL a l'instance</h2>
 *
 * Avec plusieurs instances derriere la passerelle, un flux ouvert sur
 * l'instance A est invisible depuis B : une notification traitee par B
 * n'atteindrait pas ce client. C'est une limite reelle et assumee, pas un
 * oubli — la lever demande un relais partage (Redis Pub/Sub), decision
 * d'infrastructure a prendre pour elle-meme.
 *
 * <p>Le canal {@code IN_APP} couvre l'ecart entre-temps : la notification est
 * enregistree, donc jamais perdue, meme quand la remise immediate n'aboutit
 * pas. C'est precisement pourquoi les deux canaux vont ensemble.
 */
@Component
public class RegistreDesSessions {

    private static final Logger log = LoggerFactory.getLogger(RegistreDesSessions.class);

    /**
     * Messages en attente par flux.
     *
     * <p>256 : au-dessus d'une rafale normale, assez bas pour qu'un millier de
     * clients bloques ne consomme pas la memoire du service.
     */
    private static final int TAMPON = 256;

    /**
     * destinataireId -> (identifiant de flux -> flux).
     *
     * <p>Deux niveaux concurrents : les ouvertures viennent des threads du
     * serveur, les emissions des threads qui traitent le bus. Sans structure
     * concurrente, une fermeture pendant une emission corromprait la table.
     */
    private final Map<String, Map<String, Sinks.Many<String>>> parDestinataire =
            new ConcurrentHashMap<>();

    private final NotificationMetrics metriques;

    public RegistreDesSessions(NotificationMetrics metriques) {
        this.metriques = metriques;
    }

    /**
     * Ouvre un flux pour ce porteur et le rend, a brancher sur la session.
     *
     * @param destinataireId le {@code sub} du porteur
     * @param idDeFlux       identifiant de la session, pour pouvoir la retirer
     */
    public Sinks.Many<String> ouvrir(String destinataireId, String idDeFlux) {
        Sinks.Many<String> flux = Sinks.many().unicast()
                .onBackpressureBuffer(new java.util.concurrent.ArrayBlockingQueue<>(TAMPON));

        parDestinataire
                .computeIfAbsent(destinataireId, cle -> new ConcurrentHashMap<>())
                .put(idDeFlux, flux);

        metriques.sessionOuverte();
        log.debug("Flux ouvert, {} flux sur cette instance", metriques.sessionsOuvertes());
        return flux;
    }

    /**
     * Retire un flux.
     *
     * <p>L'entree du porteur est supprimee quand son dernier flux part : sans
     * cela, la table garderait une entree vide par personne s'etant connectee
     * une fois, et grossirait indefiniment.
     */
    public void fermer(String destinataireId, String idDeFlux) {
        parDestinataire.computeIfPresent(destinataireId, (cle, flux) -> {
            Sinks.Many<String> retire = flux.remove(idDeFlux);
            if (retire != null) {
                retire.tryEmitComplete();
                metriques.sessionFermee();
            }
            return flux.isEmpty() ? null : flux;
        });
    }

    /**
     * Pousse un message vers tous les flux de ce porteur.
     *
     * @return le nombre de flux qui l'ont accepte ; {@code 0} signifie
     *         « personne n'est connecte », ce qui n'est pas une panne
     */
    public int emettre(String destinataireId, String message) {
        Map<String, Sinks.Many<String>> flux = parDestinataire.get(destinataireId);
        if (flux == null || flux.isEmpty()) {
            return 0;
        }

        int acceptes = 0;
        for (Map.Entry<String, Sinks.Many<String>> entree : flux.entrySet()) {
            // tryEmitNext et non emitNext : la variante « try » rend un resultat
            // au lieu de lever. Un flux sature ou deja ferme ne doit pas
            // interrompre l'emission vers les AUTRES appareils du porteur.
            Sinks.EmitResult resultat = entree.getValue().tryEmitNext(message);
            if (resultat.isSuccess()) {
                acceptes++;
            } else {
                log.debug("Flux non servi ({}), il sera retire", resultat);
                fermer(destinataireId, entree.getKey());
            }
        }
        return acceptes;
    }

    /** Pour les tests et le diagnostic. */
    public int nombreDeFlux(String destinataireId) {
        Map<String, Sinks.Many<String>> flux = parDestinataire.get(destinataireId);
        return flux == null ? 0 : flux.size();
    }
}
