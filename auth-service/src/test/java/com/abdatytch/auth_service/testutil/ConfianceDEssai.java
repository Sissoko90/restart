package com.abdatytch.auth_service.testutil;

import com.abdatytch.auth_service.dto.response.AppareilDeConfianceResponse;
import com.abdatytch.auth_service.dto.response.ConfianceAccordeeResponse;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.service.IAppareilDeConfianceService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Appareils de confiance inertes, pour les suites qui ne les mesurent pas.
 *
 * <h2>Pourquoi un helper partage</h2>
 *
 * {@code AuthServiceImpl} a gagne une dependance a
 * {@code IAppareilDeConfianceService} : un appareil de confiance dispense du
 * second facteur, et la connexion doit le consulter. Deux suites le
 * construisent, et aucune ne mesure cette dispense — elles portent sur le mot
 * de passe, le verrouillage et le second facteur.
 *
 * <p>Ecrire deux fois le meme simulacre recreerait la duplication que ce depot
 * passe son temps a retirer. Meme forme que {@link RevocationDEssai}.
 *
 * <h2>Pourquoi « aucun appareil de confiance » et non « tous »</h2>
 *
 * {@link #aucunAppareilDeConfiance()} rend toujours {@code false}, donc le
 * second facteur est <b>demande</b>. C'est le comportement qui laisse les
 * suites existantes mesurer ce qu'elles mesurent : avec un simulacre rendant
 * {@code true}, le second facteur serait saute et les tests qui le verifient
 * passeraient sans rien exercer.
 */
public final class ConfianceDEssai {

    private ConfianceDEssai() {
    }

    public static IAppareilDeConfianceService aucunAppareilDeConfiance() {
        return new IAppareilDeConfianceService() {

            @Override
            public Mono<ConfianceAccordeeResponse> accorder(User user, String nomDemande,
                                                            String userAgent,
                                                            String ipAddress) {
                return Mono.empty();
            }

            /**
             * Toujours faux : le second facteur reste demande.
             *
             * <p>Mono.just et non Mono.empty : un Mono vide ferait se terminer la
             * chaine de connexion sans decision — ni autorisee ni refusee — et le
             * test echouerait sur le simulacre plutot que sur ce qu'il mesure.
             */
            @Override
            public Mono<Boolean> estDeConfiance(String userId, String jeton) {
                return Mono.just(false);
            }

            @Override
            public Flux<AppareilDeConfianceResponse> duCompte(String userId) {
                return Flux.empty();
            }

            @Override
            public Mono<Void> revoquer(String userId, String appareilId) {
                return Mono.empty();
            }

            @Override
            public Mono<Long> revoquerTous(String userId) {
                return Mono.just(0L);
            }
        };
    }
}
