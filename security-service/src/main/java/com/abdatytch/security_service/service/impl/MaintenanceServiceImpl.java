package com.abdatytch.security_service.service.impl;

import com.abdatytch.security_service.constant.SecurityConstants;
import com.abdatytch.security_service.dto.request.OuvertureDeMaintenanceRequest;
import com.abdatytch.security_service.dto.response.MaintenanceResponse;
import com.abdatytch.security_service.entities.FenetreDeMaintenance;
import com.abdatytch.security_service.exception.RessourceIntrouvableException;
import com.abdatytch.security_service.exception.SecurityPolicyException;
import com.abdatytch.security_service.repositories.FenetreDeMaintenanceRepository;
import com.abdatytch.security_service.service.IMaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Fenetres de maintenance : voir {@link IMaintenanceService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MaintenanceServiceImpl implements IMaintenanceService {

    private final FenetreDeMaintenanceRepository fenetres;

    @Override
    public Flux<MaintenanceResponse> fenetres() {
        Instant maintenant = Instant.now();
        return fenetres.findByLeveeLeIsNull()
                .map(fenetre -> MaintenanceResponse.de(fenetre, maintenant));
    }

    @Override
    @Transactional
    public Mono<MaintenanceResponse> ouvrir(OuvertureDeMaintenanceRequest requete,
                                            String auteur) {
        // Mono.defer : la validation leve, et une levee a l'assemblage sortirait
        // de la chaine. Meme piege que dans PolitiqueDeSecuriteServiceImpl.
        return Mono.defer(() -> ouvrirMaintenant(requete, auteur));
    }

    private Mono<MaintenanceResponse> ouvrirMaintenant(OuvertureDeMaintenanceRequest requete,
                                                       String auteur) {
        Instant maintenant = Instant.now();
        // Le debut absent vaut « immediat ». Le laisser nul en base rendrait
        // estActive dependant d'un nul, et la fenetre serait active sans qu'on
        // sache depuis quand.
        Instant debut = requete.getDebuteLe() != null ? requete.getDebuteLe() : maintenant;

        if (requete.getFinitLe() != null && !requete.getFinitLe().isAfter(maintenant)) {
            // Une fenetre dont la fin est deja passee ne couperait rien, et
            // l'appel rendrait pourtant un succes : l'administrateur croirait la
            // maintenance active.
            return Mono.error(new SecurityPolicyException(
                    SecurityConstants.FENETRE_DEJA_TERMINEE));
        }

        FenetreDeMaintenance fenetre = FenetreDeMaintenance.builder()
                .id(UUID.randomUUID().toString())
                .service(requete.getService().trim())
                .message(requete.getMessage().trim())
                .debuteLe(debut)
                .finitLe(requete.getFinitLe())
                .activeePar(auteur)
                .creeLe(maintenant)
                .build();

        return fenetres.save(fenetre)
                // WARN et non INFO : couper un service est l'operation la plus
                // visible de ce service, et une alerte doit pouvoir se declencher
                // des la premiere occurrence plutot que sur un taux.
                .doOnSuccess(enregistree -> log.warn(SecurityConstants.LOG_MAINTENANCE_ACTIVEE,
                        enregistree.getService(), auteur, enregistree.getMessage()))
                .map(enregistree -> MaintenanceResponse.de(enregistree, maintenant));
    }

    @Override
    @Transactional
    public Mono<MaintenanceResponse> lever(String id, String auteur) {
        Instant maintenant = Instant.now();

        return fenetres.findById(id)
                .map(fenetre -> fenetre.dejaEnBase())
                // 404 et non 403 : parcourir des identifiants ne doit pas
                // reveler quelles fenetres existent. Regle appliquee partout
                // dans ce depot.
                //
                // Le type porte le statut. Lever une SecurityPolicyException
                // nue, comme ici auparavant, rendait un 400 — et, avant que
                // GlobalExceptionHandler existe, un 500 : le commentaire
                // ci-dessus annoncait une protection que rien ne produisait.
                .switchIfEmpty(Mono.error(new RessourceIntrouvableException(
                        SecurityConstants.FENETRE_INTROUVABLE)))
                .flatMap(fenetre -> {
                    if (fenetre.getLeveeLe() != null) {
                        // Deja levee. Repondre un succes ferait croire qu'une
                        // coupure vient d'etre levee, donc qu'elle courait.
                        return Mono.error(new SecurityPolicyException(
                                SecurityConstants.FENETRE_DEJA_LEVEE));
                    }
                    fenetre.setLeveeLe(maintenant);
                    fenetre.setLeveePar(auteur);
                    return fenetres.save(fenetre);
                })
                .doOnSuccess(levee -> log.warn(SecurityConstants.LOG_MAINTENANCE_LEVEE,
                        levee.getService(), auteur))
                .map(levee -> MaintenanceResponse.de(levee, maintenant));
    }
}
