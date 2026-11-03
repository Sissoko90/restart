package com.abdatytch.setting_service.erasure;

import com.abdatytch.setting_service.repositories.UserSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**

 * Efface les préférences d'un porteur.
 *
 * <p><b>Toutes</b> les préférences sont supprimées, sans tri. Leur valeur est
 * une chaîne libre : tenter de distinguer ce qui est personnel de ce qui ne
 * l'est pas reviendrait à deviner, et à se tromper au premier cas non prévu —
 * une adresse de contact rangée dans une préférence, par exemple.
 *
 * <p>Les paramètres système ne sont pas touchés : ils ne concernent personne en
 * particulier. Leur champ {@code updated_by} porte en revanche l'identifiant de
 * l'auteur d'une modification ; il devient opaque une fois l'identité effacée
 * dans auth-service, et n'a donc pas à être vidé ici.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingErasureService {

    private final UserSettingRepository userSettingRepository;

    public Mono<Integer> erase(String subjectId) {
        return userSettingRepository.findByUserId(subjectId)
                .collectList()
                .flatMap(preferences -> preferences.isEmpty()
                        ? Mono.just(0)
                        : userSettingRepository.deleteByUserId(subjectId)
                                .thenReturn(preferences.size()))
                .doOnSuccess(n -> log.info(" {} préférence(s) effacée(s) pour {}", n, subjectId));
    }
}
