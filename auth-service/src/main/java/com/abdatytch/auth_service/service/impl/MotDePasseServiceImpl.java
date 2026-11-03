package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.service.IMotDePasseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Seul detenteur du {@code PasswordEncoder}.
 *
 * <p>Voir {@link IMotDePasseService} pour la mesure et le raisonnement.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MotDePasseServiceImpl implements IMotDePasseService {

    private final PasswordEncoder passwordEncoder;

    @Override
    public Mono<Boolean> correspond(String motDePasseEnClair, String empreinte) {
        if (motDePasseEnClair == null || empreinte == null) {
            // Ni erreur ni exception : un compte sans mot de passe — cree par
            // OAuth2 ou par la biometrie — doit echouer la comparaison de la
            // meme façon qu'un mot de passe faux. Lever ici produirait un 500
            // sur ces comptes, ce qui les designerait.
            return Mono.just(false);
        }
        return Mono.fromCallable(() -> passwordEncoder.matches(motDePasseEnClair, empreinte))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<String> chiffrer(String motDePasseEnClair) {
        return Mono.fromCallable(() -> passwordEncoder.encode(motDePasseEnClair))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
