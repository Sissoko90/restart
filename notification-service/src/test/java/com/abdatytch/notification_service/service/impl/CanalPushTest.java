package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CompteRenduDeRemise;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.notification_service.entities.JetonDAppareil;
import com.abdatytch.notification_service.repositories.JetonDAppareilRepository;
import com.abdatytch.notification_service.service.CanalDeRemise;
import com.abdatytch.notification_service.service.push.IClientPush;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le canal push : ses refus, ses echecs, et la purge des appareils morts.
 *
 * <h2>Aucun compte Firebase n'est necessaire ici</h2>
 *
 * C'est la raison de {@code IClientPush} devant le SDK : le canal decide QUOI
 * pousser et QUE FAIRE du resultat, le client sait seulement pousser. Un test
 * du canal n'a donc pas besoin d'identifiants Google, et peut exercer des cas
 * — un jeton declare mort par FCM — qu'on ne saurait pas provoquer en vrai.
 */
@DisplayName("Remise par notification push")
class CanalPushTest {

    private static final String PORTEUR = "11111111-1111-1111-1111-111111111111";

    private JetonDAppareilRepository appareils;
    private IClientPush client;

    @BeforeEach
    void setUp() {
        appareils = mock(JetonDAppareilRepository.class);
        client = mock(IClientPush.class);
        when(client.estConfigure()).thenReturn(true);
        when(appareils.retirerJetonInvalide(anyString())).thenReturn(Mono.just(1L));
    }

    /** Le canal, avec son interrupteur pose par reflexion comme un {@code @Value}. */
    private CanalPush canal(boolean actif) {
        CanalPush canal = new CanalPush(appareils, client);
        ReflectionTestUtils.setField(canal, "actif", actif);
        return canal;
    }

    private void appareilsEnregistres(String... jetons) {
        when(appareils.parDestinataire(PORTEUR)).thenReturn(
                Flux.fromArray(jetons).map(j -> JetonDAppareil.de(PORTEUR, j, "ANDROID")));
    }

    private static CanalDeRemise.MessageARemettre message() {
        return new CanalDeRemise.MessageARemettre(PORTEUR, null, "Un sujet",
                "Un corps", DemandeDeNotification.Urgence.CRITIQUE, UUID.randomUUID());
    }

    @Nested
    @DisplayName("Les refus, avant tout appel")
    class Refus {

        /**
         * DEUX conditions pour etre actif, et il faut les deux.
         *
         * <p>L'interrupteur est un CHOIX d'exploitation, le client configure est
         * un FAIT. Les confondre empecherait de distinguer « on ne veut pas » de
         * « on ne peut pas » — et ce depot a deja paye deux interrupteurs pour
         * une meme question du cote SMS.
         */
        @Test
        @DisplayName("interrupteur coupé : refus sans lire la base")
        void interrupteurCoupe() {
            StepVerifier.create(canal(false).remettre(message()))
                    .expectNextMatches(r -> r.etat() == CompteRenduDeRemise.Etat.REFUSE)
                    .verifyComplete();

            verify(appareils, never()).parDestinataire(anyString());
            verify(client, never()).pousser(any(), any(), any());
        }

        @Test
        @DisplayName("client FCM non configuré : refus, même interrupteur allumé")
        void clientNonConfigure() {
            when(client.estConfigure()).thenReturn(false);

            assertThat(canal(true).estActif()).isFalse();
            StepVerifier.create(canal(true).remettre(message()))
                    .expectNextMatches(r -> r.etat() == CompteRenduDeRemise.Etat.REFUSE)
                    .verifyComplete();

            verify(appareils, never()).parDestinataire(anyString());
        }

        /**
         * Aucun appareil enregistre : REFUSE, et surtout pas ECHOUE.
         *
         * <p>Un {@code ECHOUE} ferait rejouer ce push toutes les trois minutes
         * par la reprise, pour une personne qui n'a simplement pas installe
         * l'application. Rien n'y changerait, et la file de reprise se
         * remplirait de cas definitivement perdus.
         */
        @Test
        @DisplayName("aucun appareil enregistré : REFUSE et non ECHOUE")
        void aucunAppareil() {
            appareilsEnregistres();

            StepVerifier.create(canal(true).remettre(message()))
                    .expectNextMatches(r -> r.etat() == CompteRenduDeRemise.Etat.REFUSE
                            && r.motif().contains("aucun appareil"))
                    .verifyComplete();

            verify(client, never()).pousser(any(), any(), any());
        }

        /**
         * Sans identifiant de porteur, aucun jeton a retrouver.
         *
         * <p>C'est le cas d'un message transactionnel direct. La liste blanche
         * de {@code /internal/message} refuse deja PUSH en amont ; cette garde
         * est la seconde ligne, pour un appelant interne a venir.
         */
        @Test
        @DisplayName("aucun destinataire : refus sans lire la base")
        void aucunDestinataire() {
            var sansPorteur = new CanalDeRemise.MessageARemettre(null, null, "s", "c",
                    DemandeDeNotification.Urgence.NORMALE, UUID.randomUUID());

            StepVerifier.create(canal(true).remettre(sansPorteur))
                    .expectNextMatches(r -> r.etat() == CompteRenduDeRemise.Etat.REFUSE)
                    .verifyComplete();

            verify(appareils, never()).parDestinataire(anyString());
        }
    }

    @Nested
    @DisplayName("La remise")
    class Remise {

        /**
         * UN appareil joint suffit a dire REMIS, et non « tous ».
         *
         * <p>Une personne qui a trois telephones dont un efface doit etre
         * consideree comme prevenue. Exiger l'unanimite ferait rejouer la
         * notification sur ses appareils vivants — donc la lui montrer
         * plusieurs fois — pour un appareil qu'elle a jete.
         */
        @Test
        @DisplayName("un seul appareil joint suffit à dire REMIS")
        void unSeulSuffit() {
            appareilsEnregistres("jeton-a", "jeton-b", "jeton-c");
            when(client.pousser(any(), any(), any())).thenReturn(Mono.just(
                    new IClientPush.Resultat(1, List.of("jeton-b"), null)));

            StepVerifier.create(canal(true).remettre(message()))
                    .expectNextMatches(CanalDeRemise.Resultat::aAbouti)
                    .verifyComplete();
        }

        @Test
        @DisplayName("tous les jetons du porteur sont poussés en un seul appel")
        void tousLesJetonsEnUnAppel() {
            appareilsEnregistres("jeton-a", "jeton-b");
            when(client.pousser(any(), any(), any())).thenReturn(Mono.just(
                    new IClientPush.Resultat(2, List.of(), null)));

            canal(true).remettre(message()).block();

            // Un seul appel, avec les deux jetons : pousser appareil par
            // appareil multiplierait les allers-retours reseau pour un message
            // identique, et FCM offre precisement un envoi multiple.
            verify(client).pousser(List.of("jeton-a", "jeton-b"), "Un sujet", "Un corps");
        }

        /**
         * Rien n'aboutit : ECHOUE, donc reessayable.
         *
         * <p>Et non REFUSE : les codes passagers de FCM — indisponibilite,
         * quota depasse — valent d'etre reessayes. Les jetons definitivement
         * morts viennent d'etre retires, donc la reprise ne les reverra pas.
         */
        @Test
        @DisplayName("rien n'aboutit : ECHOUE, donc rejouable")
        void rienNAboutitDonneEchoue() {
            appareilsEnregistres("jeton-a");
            when(client.pousser(any(), any(), any())).thenReturn(Mono.just(
                    IClientPush.Resultat.refuse("FCM : UNAVAILABLE")));

            StepVerifier.create(canal(true).remettre(message()))
                    .expectNextMatches(r -> r.etat() == CompteRenduDeRemise.Etat.ECHOUE
                            && "FCM : UNAVAILABLE".equals(r.motif()))
                    .verifyComplete();
        }

        /**
         * Une panne de BASE est un ECHOUE, pas un refus.
         *
         * <p>La lecture des jetons a echoue : rien ne dit que la personne n'a
         * pas d'appareil. La marquer REFUSE la rendrait non rejouable, et chaque
         * hoquet de base perdrait silencieusement des notifications.
         */
        @Test
        @DisplayName("lecture des appareils en panne : ECHOUE")
        void baseEnPanneDonneEchoue() {
            when(appareils.parDestinataire(PORTEUR))
                    .thenReturn(Flux.error(new IllegalStateException("base injoignable")));

            StepVerifier.create(canal(true).remettre(message()))
                    .expectNextMatches(r -> r.etat() == CompteRenduDeRemise.Etat.ECHOUE)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("La purge des appareils morts")
    class Purge {

        /**
         * Les jetons que FCM declare morts sont SUPPRIMES.
         *
         * <p>Sans cela, on pousse indefiniment vers des appareils disparus : un
         * appel reseau par notification, et un echec perpetuel dans chaque
         * compte rendu.
         */
        @Test
        @DisplayName("les jetons déclarés morts sont retirés")
        void jetonsMortsRetires() {
            appareilsEnregistres("vivant", "mort-1", "mort-2");
            when(client.pousser(any(), any(), any())).thenReturn(Mono.just(
                    new IClientPush.Resultat(1, List.of("mort-1", "mort-2"), null)));

            canal(true).remettre(message()).block();

            verify(appareils).retirerJetonInvalide("mort-1");
            verify(appareils).retirerJetonInvalide("mort-2");
            // Le vivant NE DOIT PAS etre retire : une purge trop large
            // effacerait la base d'appareils de la plateforme.
            verify(appareils, never()).retirerJetonInvalide("vivant");
        }

        /**
         * Aucun jeton mort : aucune suppression.
         *
         * <p>Sans cette assertion, une implementation qui supprimerait tous les
         * jetons a chaque envoi passerait le test precedent.
         */
        @Test
        @DisplayName("aucun jeton mort : aucune suppression")
        void aucuneSuppressionSansJetonMort() {
            appareilsEnregistres("vivant");
            when(client.pousser(any(), any(), any())).thenReturn(Mono.just(
                    new IClientPush.Resultat(1, List.of(), null)));

            canal(true).remettre(message()).block();

            verify(appareils, never()).retirerJetonInvalide(anyString());
        }

        /**
         * Une purge en echec ne doit PAS annuler une remise reussie.
         *
         * <p>Les autres appareils ont recu le message. Faire echouer la remise
         * pour un nettoyage rate ferait rejouer la notification, donc la
         * montrer deux fois a quelqu'un qui l'a deja vue.
         */
        @Test
        @DisplayName("une purge en échec ne casse pas une remise réussie")
        void purgeEnEchecNeCassePasLaRemise() {
            appareilsEnregistres("vivant", "mort");
            when(client.pousser(any(), any(), any())).thenReturn(Mono.just(
                    new IClientPush.Resultat(1, List.of("mort"), null)));
            when(appareils.retirerJetonInvalide("mort"))
                    .thenReturn(Mono.error(new IllegalStateException("base injoignable")));

            StepVerifier.create(canal(true).remettre(message()))
                    .expectNextMatches(CanalDeRemise.Resultat::aAbouti)
                    .verifyComplete();
        }
    }
}
