package com.abdatytch.auth_service.testutil;

import com.abdatytch.auth_service.dto.response.EtatDesCodesResponse;
import com.abdatytch.auth_service.dto.response.LotDeCodesResponse;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.service.ICodeDeSecoursService;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Codes de secours inertes, pour les suites qui ne les mesurent pas.
 *
 * <h2>Pourquoi un helper partage</h2>
 *
 * {@code SecondFacteurServiceImpl} a gagne une dependance a
 * {@link ICodeDeSecoursService} : un code de secours est une preuve recevable
 * partout ou un code TOTP l'est, sans quoi celui qui perd son telephone se
 * retrouve enferme. Quatre suites construisent ce service, et aucune ne mesure
 * cette seconde preuve — elles portent sur le TOTP, le verrouillage et
 * l'enrolement.
 *
 * <p>Ecrire quatre fois le meme simulacre recreerait la duplication que ce depot
 * passe son temps a retirer. Meme forme que {@link ConfianceDEssai}.
 *
 * <h2>Pourquoi « aucun code valide » et non « tous »</h2>
 *
 * {@link #aucunCodeDeSecours()} refuse toujours, donc un code faux <b>reste</b>
 * faux. C'est ce qui laisse les suites existantes mesurer ce qu'elles mesurent :
 * avec un simulacre acceptant tout, les tests qui verifient le refus d'un code
 * errone passeraient sans rien exercer.
 */
public final class CodesDeSecoursDEssai {

    private CodesDeSecoursDEssai() {
    }

    public static ICodeDeSecoursService aucunCodeDeSecours() {
        return new ICodeDeSecoursService() {

            @Override
            public Mono<LotDeCodesResponse> regenerer(User user) {
                return Mono.empty();
            }

            /**
             * Toujours faux : un code errone le reste.
             *
             * <p>Mono.just et non Mono.empty : un Mono vide ferait se terminer
             * la chaine du second facteur sans decision — ni acceptee ni
             * refusee — et le test echouerait sur le simulacre plutot que sur ce
             * qu'il mesure. Le piege coute cher dans ce depot ; il est note ici
             * pour qu'on ne le repose pas.
             */
            @Override
            public Mono<Boolean> consommer(User user, String codePresente,
                                           String ipAddress) {
                return Mono.just(false);
            }

            @Override
            public Mono<EtatDesCodesResponse> etat(String userId) {
                return Mono.just(EtatDesCodesResponse.builder()
                        .restants(0)
                        .total(0)
                        .codes(List.of())
                        .build());
            }

            /** Zero, et non vide : les chaines qui enchainent dessus avancent. */
            @Override
            public Mono<Long> invaliderLeLot(String userId) {
                return Mono.just(0L);
            }
        };
    }
}
