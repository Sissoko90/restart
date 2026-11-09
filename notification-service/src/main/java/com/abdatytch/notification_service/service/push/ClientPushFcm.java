package com.abdatytch.notification_service.service.push;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Pousse par Firebase Cloud Messaging.
 *
 * <h2>Le SDK est BLOQUANT, et tout est a l'interieur du scope</h2>
 *
 * {@code FirebaseMessaging} n'a pas d'API reactive : l'appel occupe son thread
 * du debut a la fin. La politique de ce depot est zero blocage sur le chemin
 * requete, mesuree par BlockHound.
 *
 * <p>Le message est donc construit <b>dans</b> le {@code fromCallable}, et non
 * avant : un objet construit a l'exterieur s'evalue a l'assemblage, donc sur le
 * thread appelant, avant que {@code subscribeOn} ne s'applique. C'est le meme
 * piege que celui deja corrige dans {@code CanalCourriel} pour SMTP et dans
 * {@code ProjectServiceImpl} pour la publication Kafka.
 *
 * <h2>Pourquoi {@code ObjectProvider} et non une injection directe</h2>
 *
 * {@code ConfigurationFirebase} rend {@code null} quand le compte de service
 * n'est pas configure — le demarrage ne doit jamais etre bloque par une valeur
 * absente. Spring enregistre alors un {@code NullBean}, et une injection
 * obligatoire de {@code FirebaseMessaging} echouerait au demarrage, pour une
 * raison sans rapport apparent avec la cause.
 *
 * <p>{@code ObjectProvider.getIfAvailable()} rend {@code null} sans drame, et
 * {@code estConfigure} le traduit en « canal inactif » — un refus visible dans
 * le compte rendu plutot qu'un service qui ne demarre pas.
 */
@Component
public class ClientPushFcm implements IClientPush {

    private static final Logger log = LoggerFactory.getLogger(ClientPushFcm.class);

    /**
     * Borne de FCM pour un envoi multiple.
     *
     * <p>C'est la limite de l'API, pas une precaution : au-dela, l'appel entier
     * est refuse. Un porteur de plus de 500 appareils est invraisemblable, mais
     * un jeton qui ne se purge pas pourrait y mener — et l'envoi cesserait
     * alors d'aboutir pour TOUS ses appareils, pas seulement les surnumeraires.
     */
    static final int JETONS_MAX_PAR_ENVOI = 500;

    /**
     * Les codes qui rendent un jeton DEFINITIVEMENT injoignable.
     *
     * <p>{@code UNREGISTERED} : application desinstallee, ou jeton remplace par
     * le systeme. {@code INVALID_ARGUMENT} : jeton mal forme, souvent un reste
     * d'une autre application.
     *
     * <p>Les autres codes — {@code UNAVAILABLE}, {@code INTERNAL},
     * {@code QUOTA_EXCEEDED} — sont PASSAGERS et ne doivent surtout pas faire
     * supprimer le jeton : une panne FCM d'une minute effacerait sinon la base
     * d'appareils de toute la plateforme.
     */
    private static final Set<MessagingErrorCode> JETON_MORT =
            EnumSet.of(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT);

    private final ObjectProvider<FirebaseMessaging> messagerie;

    public ClientPushFcm(ObjectProvider<FirebaseMessaging> messagerie) {
        this.messagerie = messagerie;
    }

    @Override
    public boolean estConfigure() {
        return messagerie.getIfAvailable() != null;
    }

    @Override
    public Mono<Resultat> pousser(List<String> jetons, String titre, String corps) {
        FirebaseMessaging client = messagerie.getIfAvailable();
        if (client == null) {
            return Mono.just(Resultat.refuse("client FCM non configure"));
        }
        if (jetons == null || jetons.isEmpty()) {
            return Mono.just(Resultat.refuse("aucun jeton d'appareil enregistre"));
        }

        List<String> envoyes = jetons.size() > JETONS_MAX_PAR_ENVOI
                ? jetons.subList(0, JETONS_MAX_PAR_ENVOI)
                : jetons;
        if (envoyes.size() < jetons.size()) {
            // Le NOMBRE, jamais les jetons : un jeton d'appareil identifie un
            // telephone, et ce journal part vers Loki.
            log.warn("{} jetons d'appareil pour un seul destinataire : seuls les "
                            + "{} plus recents sont servis. Verifier la purge.",
                    jetons.size(), JETONS_MAX_PAR_ENVOI);
        }

        return Mono.fromCallable(() -> {
                    // TOUT est construit ici, dans le scope boundedElastic.
                    MulticastMessage message = MulticastMessage.builder()
                            .setNotification(Notification.builder()
                                    .setTitle(titre == null ? "" : titre)
                                    .setBody(corps)
                                    .build())
                            .addAllTokens(envoyes)
                            .build();

                    return depouiller(client.sendEachForMulticast(message), envoyes);
                })
                .subscribeOn(Schedulers.boundedElastic())
                // Une panne de FCM ne doit pas interrompre la remise des autres
                // canaux de la meme notification. Le motif ne porte que le TYPE
                // de l'erreur : son message peut citer un jeton.
                .onErrorResume(panne -> {
                    log.warn("Envoi FCM impossible : {}", panne.getClass().getSimpleName());
                    return Mono.just(Resultat.refuse(
                            "FCM : " + panne.getClass().getSimpleName()));
                });
    }

    /**
     * Traduit la reponse de FCM, appareil par appareil.
     *
     * <p>L'ordre des reponses suit celui des jetons envoyes — c'est le contrat
     * de {@code sendEachForMulticast}. C'est ce qui permet de rattacher un code
     * d'erreur au jeton qui l'a provoque.
     *
     * <h3>Visible pour le test, et c'est delibere</h3>
     *
     * C'est la logique la plus COUTEUSE de ce canal si elle se trompe, dans les
     * deux sens : traiter un code passager comme definitif effacerait la base
     * d'appareils de la plateforme sur une panne FCM d'une minute ; traiter un
     * code definitif comme passager ferait pousser indefiniment vers des
     * telephones effaces.
     *
     * <p>Une mutation l'a montre : rendre le tri aveugle — purger sur TOUT code
     * d'erreur — laissait les onze tests du canal verts, parce qu'aucun
     * n'exercait cette methode. Package-private plutot que private, pour
     * qu'elle en ait un.
     */
    Resultat depouiller(BatchResponse reponse, List<String> jetons) {
        List<SendResponse> parJeton = reponse.getResponses();
        List<String> morts = new ArrayList<>();
        String premierMotif = null;

        for (int i = 0; i < parJeton.size() && i < jetons.size(); i++) {
            SendResponse r = parJeton.get(i);
            if (r.isSuccessful()) {
                continue;
            }
            FirebaseMessagingException erreur = r.getException();
            MessagingErrorCode code = erreur == null ? null : erreur.getMessagingErrorCode();

            if (code != null && JETON_MORT.contains(code)) {
                morts.add(jetons.get(i));
            }
            if (premierMotif == null) {
                // Le CODE de FCM, jamais son message : celui-la peut citer le
                // jeton fautif. Le code est une valeur bornee, donc exploitable
                // en metrique.
                premierMotif = code == null ? "FCM : erreur sans code" : "FCM : " + code;
            }
        }

        int acceptes = reponse.getSuccessCount();
        if (!morts.isEmpty()) {
            log.info("{} jeton(s) d'appareil declare(s) injoignable(s) par FCM : "
                    + "ils vont etre retires", morts.size());
        }
        return new Resultat(acceptes, morts, acceptes > 0 ? null : premierMotif);
    }
}
