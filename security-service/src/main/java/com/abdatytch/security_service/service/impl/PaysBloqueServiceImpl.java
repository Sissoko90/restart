package com.abdatytch.security_service.service.impl;

import com.abdatytch.security_service.constant.SecurityConstants;
import com.abdatytch.security_service.dto.request.BlocageDePaysRequest;
import com.abdatytch.security_service.dto.response.PaysBloqueResponse;
import com.abdatytch.security_service.entities.PaysBloque;
import com.abdatytch.security_service.exception.SecurityPolicyException;
import com.abdatytch.security_service.repositories.PaysBloqueRepository;
import com.abdatytch.security_service.service.IPaysBloqueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pays refuses : voir {@link IPaysBloqueService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaysBloqueServiceImpl implements IPaysBloqueService {

    /**
     * ISO 3166-1 alpha-2 : deux lettres, exactement.
     *
     * <p>Applique <b>apres</b> normalisation en majuscules, contrairement a
     * l'implementation remplacee qui validait d'abord et normalisait ensuite —
     * et refusait donc {@iso ml} comme iso invalide.
     */
    private static final Pattern MOTIF_ISO = Pattern.compile("[A-Z]{2}");

    private final PaysBloqueRepository paysBloques;

    @Override
    public Flux<PaysBloqueResponse> refuses() {
        return paysBloques.findByLeveeLeIsNull()
                .sort(Comparator.comparing(PaysBloque::getCodeIso))
                .map(PaysBloqueResponse::de);
    }

    @Override
    @Transactional
    public Flux<PaysBloqueResponse> refuser(BlocageDePaysRequest requete, String auteur) {
        // Flux.defer : la normalisation leve sur un iso invalide, et une levee
        // a l'assemblage sortirait de la chaine. Meme piege que dans les autres
        // services de ce module.
        return Flux.defer(() -> refuserMaintenant(requete, auteur));
    }

    private Flux<PaysBloqueResponse> refuserMaintenant(BlocageDePaysRequest requete,
                                                       String auteur) {
        Set<String> codes = normaliser(requete.getCodesIso());
        Instant maintenant = Instant.now();

        return Flux.fromIterable(codes)
                .concatMap(iso -> paysBloques.findById(iso)
                        .map(existant -> {
                            // Deja en base : on le remet en service plutot que
                            // de refuser. Un pays refuse puis accepte puis
                            // refuse de nouveau garde ainsi une seule ligne, et
                            // son historique reste lisible.
                            existant.setMotif(requete.getMotif().trim());
                            existant.setBloquePar(auteur);
                            existant.setCreeLe(maintenant);
                            existant.setLeveeLe(null);
                            existant.setLeveePar(null);
                            return existant.dejaEnBase();
                        })
                        .defaultIfEmpty(PaysBloque.builder()
                                .codeIso(iso)
                                .motif(requete.getMotif().trim())
                                .bloquePar(auteur)
                                .creeLe(maintenant)
                                .build())
                        .flatMap(paysBloques::save)
                        // WARN : refuser un pays coupe l'acces a tous ses
                        // habitants. Une alerte doit pouvoir se declencher des
                        // la premiere occurrence, pas sur un taux.
                        .doOnSuccess(enregistre -> log.warn(
                                SecurityConstants.LOG_PAYS_BLOQUE,
                                iso, auteur, requete.getMotif()))
                        .map(PaysBloqueResponse::de));
    }

    @Override
    @Transactional
    public Mono<PaysBloqueResponse> accepter(String codeIso, String auteur) {
        return Mono.defer(() -> accepterMaintenant(codeIso, auteur));
    }

    private Mono<PaysBloqueResponse> accepterMaintenant(String codeIso, String auteur) {
        String iso = normaliserUnCode(codeIso);
        Instant maintenant = Instant.now();

        return paysBloques.findById(iso)
                .map(PaysBloque::dejaEnBase)
                // Distinction que l'implementation remplacee ne faisait pas :
                // unblockCountry ne validait rien, donc un iso fantaisiste et
                // un pays deja accepte rendaient la meme reponse. Impossible de
                // savoir si on avait fait une faute de frappe.
                .switchIfEmpty(Mono.error(new SecurityPolicyException(
                        SecurityConstants.PAYS_NON_REFUSE, iso)))
                .flatMap(pays -> {
                    if (pays.getLeveeLe() != null) {
                        return Mono.error(new SecurityPolicyException(
                                SecurityConstants.PAYS_NON_REFUSE, iso));
                    }
                    pays.setLeveeLe(maintenant);
                    pays.setLeveePar(auteur);
                    return paysBloques.save(pays);
                })
                .doOnSuccess(accepte -> log.warn(
                        SecurityConstants.LOG_PAYS_DEBLOQUE, iso, auteur))
                .map(PaysBloqueResponse::de);
    }

    /**
     * Normalise et valide une liste de codes.
     *
     * <p>Un {@link LinkedHashSet} : les doublons d'une meme demande sont
     * absorbes — {@iso ["ml", "ML"]} designe un seul pays — et l'ordre de
     * saisie est conserve pour que la reponse suive la demande.
     */
    private static Set<String> normaliser(java.util.List<String> codes) {
        Set<String> normalises = new LinkedHashSet<>();
        for (String iso : codes) {
            normalises.add(normaliserUnCode(iso));
        }
        return normalises;
    }

    private static String normaliserUnCode(String iso) {
        if (iso == null) {
            throw new SecurityPolicyException(SecurityConstants.CODE_ISO_INVALIDE);
        }
        // Majuscules AVANT la validation : c'est l'inversion de cet ordre qui
        // faisait refuser « ml » dans l'implementation remplacee.
        String normalise = iso.trim().toUpperCase(java.util.Locale.ROOT);
        if (!MOTIF_ISO.matcher(normalise).matches()) {
            throw new SecurityPolicyException(
                    SecurityConstants.CODE_ISO_INVALIDE + " : « " + iso + " »", normalise);
        }
        return normalise;
    }
}
