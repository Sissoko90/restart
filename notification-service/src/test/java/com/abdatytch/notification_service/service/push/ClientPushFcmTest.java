package com.abdatytch.notification_service.service.push;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Le tri des codes d'erreur de FCM : quel jeton survit, lequel disparait.
 *
 * <h2>Pourquoi ce test existe, et comment son absence a ete trouvee</h2>
 *
 * C'est la logique la plus couteuse de tout le canal push si elle se trompe, et
 * elle se trompe silencieusement dans les deux sens :
 *
 * <ul>
 *   <li>traiter un code PASSAGER comme definitif effacerait la base
 *       d'appareils de toute la plateforme sur une panne FCM d'une minute.
 *       Personne ne recevrait plus de push, et aucune erreur ne le dirait —
 *       chaque remise rendrait « aucun appareil enregistre », qui est un
 *       message parfaitement normal ;</li>
 *   <li>traiter un code DEFINITIF comme passager ferait pousser indefiniment
 *       vers des telephones effaces : un appel reseau par notification, et un
 *       echec perpetuel dans chaque compte rendu.</li>
 * </ul>
 *
 * <p>L'absence de ce test a ete trouvee par MUTATION, et non par relecture :
 * rendre le tri aveugle — purger sur n'importe quel code d'erreur — laissait
 * les onze tests du canal verts. Ils exercaient le canal avec un client
 * bouchonne, donc jamais cette methode.
 *
 * <h2>Pourquoi des bouchons et non un vrai appel</h2>
 *
 * Un {@code UNREGISTERED} ne se provoque pas a volonte : il faudrait
 * desinstaller une application reelle et attendre que Google le constate. Les
 * bouchons permettent d'exercer chaque code, y compris ceux qu'on espere ne
 * jamais voir.
 */
@DisplayName("Tri des reponses de FCM, jeton par jeton")
class ClientPushFcmTest {

    private static final String VIVANT = "jeton-vivant";
    private static final String MORT = "jeton-mort";

    /** Un client sans Firebase configure : {@code getIfAvailable} rend null. */
    private static ClientPushFcm clientSansFirebase() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FirebaseMessaging> absent = mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);
        return new ClientPushFcm(absent);
    }

    /** Un client dont Firebase est present : seul {@code depouiller} est exerce. */
    private static ClientPushFcm clientAvecFirebase() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FirebaseMessaging> present = mock(ObjectProvider.class);
        when(present.getIfAvailable()).thenReturn(mock(FirebaseMessaging.class));
        return new ClientPushFcm(present);
    }

    private static SendResponse succes() {
        SendResponse r = mock(SendResponse.class);
        when(r.isSuccessful()).thenReturn(true);
        return r;
    }

    private static SendResponse echec(MessagingErrorCode code) {
        FirebaseMessagingException erreur = mock(FirebaseMessagingException.class);
        when(erreur.getMessagingErrorCode()).thenReturn(code);

        SendResponse r = mock(SendResponse.class);
        when(r.isSuccessful()).thenReturn(false);
        when(r.getException()).thenReturn(erreur);
        return r;
    }

    /**
     * Un lot de reponses.
     *
     * <p>Le nombre de succes est calcule AVANT l'appel a {@code when}, et non
     * dans son argument. Mockito leve {@code UnfinishedStubbing} si une methode
     * d'un simulacre est invoquee pendant qu'un stubbing est en cours — et
     * {@code reponses.stream().filter(SendResponse::isSuccessful)} appelle
     * precisement {@code isSuccessful()} sur chaque simulacre. Piege rencontre
     * a l'ecriture de ce test.
     */
    private static BatchResponse lot(List<SendResponse> reponses) {
        int succes = (int) reponses.stream().filter(SendResponse::isSuccessful).count();

        BatchResponse lot = mock(BatchResponse.class);
        when(lot.getResponses()).thenReturn(reponses);
        when(lot.getSuccessCount()).thenReturn(succes);
        return lot;
    }

    @Nested
    @DisplayName("Les codes DEFINITIFS font disparaitre le jeton")
    class JetonsMorts {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = MessagingErrorCode.class,
                names = {"UNREGISTERED", "INVALID_ARGUMENT"})
        @DisplayName("le jeton est désigné pour retrait")
        void codeDefinitifDesigneLeJeton(MessagingErrorCode code) {
            var resultat = clientAvecFirebase().depouiller(
                    lot(List.of(succes(), echec(code))),
                    List.of(VIVANT, MORT));

            assertThat(resultat.jetonsInvalides())
                    .withFailMessage("""
                            %s ne fait pas retirer le jeton.

                            Ce code est DEFINITIF : application desinstallee, ou \
                            jeton remplace par le systeme. Le garder fait \
                            pousser indefiniment vers un telephone disparu — un \
                            appel reseau par notification, et un echec perpetuel \
                            dans chaque compte rendu.""", code)
                    .containsExactly(MORT);
        }

        /**
         * Et le jeton VIVANT du meme lot n'est pas emporte.
         *
         * <p>Sans cette assertion, une implementation qui designerait tous les
         * jetons passerait le test precedent.
         */
        @Test
        @DisplayName("le jeton vivant du même lot survit")
        void leVivantSurvit() {
            var resultat = clientAvecFirebase().depouiller(
                    lot(List.of(succes(), echec(MessagingErrorCode.UNREGISTERED))),
                    List.of(VIVANT, MORT));

            assertThat(resultat.jetonsInvalides())
                    .withFailMessage("le jeton vivant est emporte par la purge")
                    .doesNotContain(VIVANT);
            assertThat(resultat.acceptes()).isEqualTo(1);
            assertThat(resultat.aAbouti()).isTrue();
        }
    }

    @Nested
    @DisplayName("Les codes PASSAGERS ne font rien disparaitre")
    class JetonsVivants {

        /**
         * LE test de cette classe, et le plus important du lot.
         *
         * <p>Une panne FCM rend {@code UNAVAILABLE} ou {@code INTERNAL} pour
         * TOUS les jetons a la fois. Les traiter comme definitifs effacerait la
         * base d'appareils de la plateforme entiere en une passe, et le symptome
         * — « aucun appareil enregistre » — est un message parfaitement normal
         * que personne n'irait chercher.
         */
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = MessagingErrorCode.class,
                names = {"UNAVAILABLE", "INTERNAL", "QUOTA_EXCEEDED",
                        "SENDER_ID_MISMATCH", "THIRD_PARTY_AUTH_ERROR"})
        @DisplayName("aucun jeton n'est retiré")
        void codePassagerNeRetireRien(MessagingErrorCode code) {
            var resultat = clientAvecFirebase().depouiller(
                    lot(List.of(echec(code), echec(code))),
                    List.of(VIVANT, MORT));

            assertThat(resultat.jetonsInvalides())
                    .withFailMessage("""
                            %s fait retirer des jetons, et il ne devrait pas.

                            Ce code est PASSAGER. Une panne FCM le rend pour TOUS \
                            les jetons a la fois : la base d'appareils de toute \
                            la plateforme disparaitrait en une passe. Et le \
                            symptome — « aucun appareil enregistre » — est un \
                            message normal que personne n'irait chercher.""", code)
                    .isEmpty();
            // Rien n'a abouti, donc le motif porte le CODE : il est borne, donc
            // exploitable en metrique, et il ne cite pas le jeton fautif.
            assertThat(resultat.aAbouti()).isFalse();
            assertThat(resultat.motif()).contains(code.name());
        }

        /**
         * Une reponse en echec SANS code ne fait rien retirer non plus.
         *
         * <p>Faute de code, on ne sait pas si l'echec est definitif. Retirer
         * dans le doute reviendrait a traiter l'inconnu comme le pire.
         */
        @Test
        @DisplayName("un échec sans code ne retire rien")
        void echecSansCodeNeRetireRien() {
            SendResponse sansCode = mock(SendResponse.class);
            when(sansCode.isSuccessful()).thenReturn(false);
            when(sansCode.getException()).thenReturn(null);

            var resultat = clientAvecFirebase().depouiller(
                    lot(List.of(sansCode)), List.of(VIVANT));

            assertThat(resultat.jetonsInvalides()).isEmpty();
            assertThat(resultat.motif()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Le client inerte")
    class SansFirebase {

        @Test
        @DisplayName("sans compte de service, il n'est pas configuré et refuse")
        void refuseSansCompteDeService() {
            var client = clientSansFirebase();

            assertThat(client.estConfigure()).isFalse();
            StepVerifier.create(client.pousser(List.of(VIVANT), "s", "c"))
                    .expectNextMatches(r -> !r.aAbouti()
                            && r.motif().contains("non configure"))
                    .verifyComplete();
        }

        /**
         * Et une liste de jetons vide refuse AVANT tout appel reseau.
         *
         * <p>FCM refuserait de toute facon un envoi sans destinataire : autant
         * ne pas payer l'aller-retour.
         */
        @Test
        @DisplayName("aucun jeton : refus sans appel réseau")
        void refuseSansJeton() {
            StepVerifier.create(clientAvecFirebase().pousser(List.of(), "s", "c"))
                    .expectNextMatches(r -> !r.aAbouti()
                            && r.motif().contains("aucun jeton"))
                    .verifyComplete();
        }
    }
}
