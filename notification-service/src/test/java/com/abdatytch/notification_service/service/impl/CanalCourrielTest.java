package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CompteRenduDeRemise;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.notification_service.service.CanalDeRemise;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("Remise par courriel")
class CanalCourrielTest {

    private static final String ADRESSE = "personne@exemple.test";
    private static final String EXPEDITEUR = "notifications@exemple.test";

    private final JavaMailSender ordinaire = mock(JavaMailSender.class);
    private final JavaMailSender critique = mock(JavaMailSender.class);

    /**
     * Les {@code @Value} sont poses APRES la construction, par reflexion.
     *
     * <p>Le constructeur ne les lit pas, donc l'ordre est sans danger ici — mais
     * la regle vaut qu'on la rappelle : ce depot a paye cette difference
     * ailleurs, ou {@code bindToController} ecrasait les champs poses avant
     * {@code build()} et faisait passer deux tests pour la mauvaise raison.
     */
    private CanalCourriel canal(String adresseExpediteur) {
        CanalCourriel canal = new CanalCourriel(ordinaire, critique);
        ReflectionTestUtils.setField(canal, "adresseExpediteur", adresseExpediteur);
        ReflectionTestUtils.setField(canal, "adresseReponse", "");
        return canal;
    }

    private static CanalDeRemise.MessageARemettre message(
            DemandeDeNotification.Urgence urgence) {
        return new CanalDeRemise.MessageARemettre("porteur", ADRESSE,
                "Un sujet", "Un corps", urgence, UUID.randomUUID());
    }

    @Nested
    @DisplayName("Le compte employé dépend de l'urgence")
    class CompteParUrgence {

        /**
         * Le point de ce canal, et la raison du second compte.
         *
         * <p>Une alerte de securite ne doit pas partager sa reputation
         * d'expedition avec le trafic ordinaire : un courriel marque indesirable
         * parce qu'une campagne a sature le compte commun est un code de
         * verification que personne ne recoit.
         */
        @Test
        @DisplayName("une notification CRITIQUE part par le compte dédié")
        void critiqueParLeCompteDedie() {
            StepVerifier.create(canal(EXPEDITEUR)
                            .remettre(message(DemandeDeNotification.Urgence.CRITIQUE)))
                    .expectNextMatches(CanalDeRemise.Resultat::aAbouti)
                    .verifyComplete();

            verify(critique).send(any(SimpleMailMessage.class));
            verify(ordinaire, never()).send(any(SimpleMailMessage.class));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = DemandeDeNotification.Urgence.class,
                names = {"NORMALE", "INFORMATION"})
        @DisplayName("les autres urgences partent par le compte ordinaire")
        void autresParLeCompteOrdinaire(DemandeDeNotification.Urgence urgence) {
            StepVerifier.create(canal(EXPEDITEUR).remettre(message(urgence)))
                    .expectNextMatches(CanalDeRemise.Resultat::aAbouti)
                    .verifyComplete();

            verify(ordinaire).send(any(SimpleMailMessage.class));
            verify(critique, never()).send(any(SimpleMailMessage.class));
        }

        /**
         * Une urgence absente vaut « ordinaire ».
         *
         * <p>Le repli sans surprise : on ne promeut pas un message au compte
         * critique faute de savoir. L'inverse aurait fait partir du trafic
         * ordinaire par le compte dont on protege justement la reputation.
         */
        @Test
        @DisplayName("une urgence absente ne promeut pas au compte critique")
        void urgenceAbsenteResteOrdinaire() {
            StepVerifier.create(canal(EXPEDITEUR).remettre(message(null)))
                    .expectNextMatches(CanalDeRemise.Resultat::aAbouti)
                    .verifyComplete();

            verify(ordinaire).send(any(SimpleMailMessage.class));
            verify(critique, never()).send(any(SimpleMailMessage.class));
        }
    }

    @Nested
    @DisplayName("Les refus, avant tout envoi")
    class Refus {

        @Test
        @DisplayName("sans expéditeur configuré, le canal refuse et n'appelle aucun compte")
        void sansExpediteurConfigure() {
            var canal = canal("");

            assertThat(canal.estActif()).isFalse();
            StepVerifier.create(canal.remettre(message(
                            DemandeDeNotification.Urgence.CRITIQUE)))
                    .expectNextMatches(resultat ->
                            resultat.etat() == CompteRenduDeRemise.Etat.REFUSE)
                    .verifyComplete();

            verify(ordinaire, never()).send(any(SimpleMailMessage.class));
            verify(critique, never()).send(any(SimpleMailMessage.class));
        }

        /**
         * Aucune adresse : refus AVANT d'appeler le serveur SMTP.
         *
         * <p>Laisser passer une adresse vide ferait refuser le serveur distant,
         * donc remplacerait un refus clair et gratuit par un aller-retour reseau
         * et un message d'erreur de fournisseur.
         */
        @Test
        @DisplayName("sans adresse destinataire, le canal refuse sans appeler le serveur")
        void sansAdresseDestinataire() {
            var sansAdresse = new CanalDeRemise.MessageARemettre("porteur", null,
                    "Un sujet", "Un corps",
                    DemandeDeNotification.Urgence.NORMALE, UUID.randomUUID());

            StepVerifier.create(canal(EXPEDITEUR).remettre(sansAdresse))
                    .expectNextMatches(resultat ->
                            resultat.etat() == CompteRenduDeRemise.Etat.REFUSE)
                    .verifyComplete();

            verify(ordinaire, never()).send(any(SimpleMailMessage.class));
            verify(critique, never()).send(any(SimpleMailMessage.class));
        }
    }
}
