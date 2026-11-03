package com.abdatytch.auth_service.service.sms;

import com.abdatytch.auth_service.constants.ServiceConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Interpretation d'un accuse de reception SMS.
 *
 * <p>Voir {@link IAccusesDeReceptionSms} pour le pourquoi de ce service et la
 * raison pour laquelle rien n'y echoue.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccusesDeReceptionSmsService implements IAccusesDeReceptionSms {

    private final IRegistreDesEnvoisSms registre;

    @Override
    public Mono<Void> enregistrer(String resourceId, String code) {
        StatutDeLivraisonSms statut = StatutDeLivraisonSms.depuisLeCode(code);

        if (resourceId == null || resourceId.isBlank()) {
            log.warn(ServiceConstants.LOG_WARN_SMS_ACCUSE_SANS_IDENTIFIANT, statut.name());
            return Mono.empty();
        }

        return registre.numeroDe(resourceId)
                .doOnNext(numero -> journaliser(statut, numero))
                // Un identifiant inconnu n'est pas une anomalie de notre code :
                // accuse en retard au-dela du TTL du registre, registre purge,
                // ou identifiant forge. Il se journalise, et l'accuse est
                // acquitte.
                .switchIfEmpty(Mono.fromRunnable(() -> log.warn(
                        ServiceConstants.LOG_WARN_SMS_ACCUSE_INCONNU, statut.name())))
                // Redis indisponible ne doit pas transformer un accuse en
                // erreur : le fournisseur reessaierait pendant toute la panne,
                // pour une information qu'on ne saura de toute façon pas lire.
                .onErrorResume(erreur -> {
                    log.error(ServiceConstants.LOG_ERROR_SMS_ACCUSE_REGISTRE,
                            erreur.getClass().getSimpleName());
                    return Mono.empty();
                })
                .then();
    }

    private void journaliser(StatutDeLivraisonSms statut, String numero) {
        if (statut.estUnEchec()) {
            log.warn(ServiceConstants.LOG_WARN_SMS_NON_LIVRE, numero, statut.name());
            return;
        }
        log.info(ServiceConstants.LOG_INFO_SMS_ACCUSE, numero, statut.name());
    }
}
