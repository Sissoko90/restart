package com.abdatytch.notification_service.scheduler;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.CompteRenduDeRemise;
import com.abdatytch.notification_service.entities.RemiseParCanal;
import com.abdatytch.notification_service.repositories.RemiseParCanalRepository;
import com.abdatytch.notification_service.service.IServiceDeNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Reprise des remises qui ont échoué")
class RepriseDesRemisesTest {

    private RemiseParCanalRepository remises;
    private IServiceDeNotification service;
    private RepriseDesRemises reprise;

    @BeforeEach
    void preparer() {
        remises = mock(RemiseParCanalRepository.class);
        service = mock(IServiceDeNotification.class);
        when(service.rejouer(any(), any()))
                .thenReturn(Mono.just(CompteRenduDeRemise.Etat.REMIS));
        reprise = new RepriseDesRemises(remises, service, (short) 3, 50, 5);
    }

    private static RemiseParCanal echec(CanalDeNotification canal) {
        return RemiseParCanal.de(UUID.randomUUID(), canal,
                CompteRenduDeRemise.Etat.ECHOUE, "serveur injoignable");
    }

    private void aReprendre(RemiseParCanal... lignes) {
        when(remises.aReprendre(any(LocalDateTime.class), anyShort(), anyInt()))
                .thenReturn(Flux.fromIterable(List.of(lignes)));
    }

    /**
     * La regle qui coute de l'argent si elle casse.
     *
     * <p>Un SMS remis au fournisseur se paie. Un rejeu dont on ne sait pas si le
     * premier envoi est parti fait payer deux fois et peut livrer le message en
     * double. La reprise ne doit donc JAMAIS toucher au canal SMS.
     *
     * <p>Ce test est le seul garde-fou de cette regle : rien dans la requete SQL
     * n'exclut le SMS — c'est un filtre en code, qu'une simplification pourrait
     * retirer sans que rien d'autre ne le signale. L'erreur ne se verrait alors
     * que sur la facture.
     */
    @Test
    @DisplayName("le SMS n'est JAMAIS rejoué")
    void smsJamaisRejoue() {
        aReprendre(echec(CanalDeNotification.SMS));

        reprise.reprendre();

        verify(service, never()).rejouer(any(), eq(CanalDeNotification.SMS));
        verify(service, never()).rejouer(any(), any());
    }

    @Test
    @DisplayName("les autres canaux sont rejoués")
    void autresCanauxRejoues() {
        var courriel = echec(CanalDeNotification.COURRIEL);
        var inApp = echec(CanalDeNotification.IN_APP);
        aReprendre(courriel, inApp);

        reprise.reprendre();

        verify(service).rejouer(courriel.getNotificationId(), CanalDeNotification.COURRIEL);
        verify(service).rejouer(inApp.getNotificationId(), CanalDeNotification.IN_APP);
    }

    /**
     * Le SMS est ecarte, les autres passent : les deux dans le meme lot.
     *
     * <p>Un filtre pose au mauvais endroit — sur le lot entier plutot que par
     * ligne — aurait annule toute la passe des qu'un SMS y figurait.
     */
    @Test
    @DisplayName("un SMS dans le lot n'empêche pas les autres de partir")
    void smsDansLeLotNEmpechePasLesAutres() {
        var sms = echec(CanalDeNotification.SMS);
        var courriel = echec(CanalDeNotification.COURRIEL);
        aReprendre(sms, courriel);

        reprise.reprendre();

        verify(service, never()).rejouer(sms.getNotificationId(), CanalDeNotification.SMS);
        verify(service).rejouer(courriel.getNotificationId(), CanalDeNotification.COURRIEL);
    }

    /**
     * Un rejeu en erreur ne doit pas vider le lot.
     *
     * <p>Sans la garde, la premiere notification fautive interromprait le flux
     * et toutes les suivantes attendraient la passe d'apres — ou la suivante
     * encore, si la meme ligne revenait en tete.
     */
    @Test
    @DisplayName("un rejeu en erreur n'arrête pas les suivants")
    void uneErreurNArretePasLeLot() {
        var premier = echec(CanalDeNotification.COURRIEL);
        var second = echec(CanalDeNotification.PUSH);
        aReprendre(premier, second);

        when(service.rejouer(premier.getNotificationId(), CanalDeNotification.COURRIEL))
                .thenReturn(Mono.error(new IllegalStateException("panne")));

        reprise.reprendre();

        verify(service).rejouer(second.getNotificationId(), CanalDeNotification.PUSH);
    }

    /**
     * Le plafond et la fenetre sont ceux de la configuration.
     *
     * <p>Les passer en dur aurait rendu la configuration decorative — un defaut
     * que ce depot a deja paye avec {@code spring.cache.redis.time-to-live},
     * propriete que personne ne lisait.
     */
    @Test
    @DisplayName("le plafond et l'âge minimal viennent de la configuration")
    void parametresVenusDeLaConfiguration() {
        aReprendre();
        var avant = LocalDateTime.now().minusMinutes(5);

        reprise.reprendre();

        var capteurDate = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(remises).aReprendre(capteurDate.capture(), eq((short) 3), eq(50));

        // Une seconde de tolerance : l'horodatage est calcule dans la methode.
        assertThat(capteurDate.getValue())
                .isCloseTo(avant, within(1, ChronoUnit.SECONDS));
    }
}
