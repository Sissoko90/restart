package com.abdatytch.security_service.service.impl;

import com.abdatytch.commons.security.AdresseIpLitterale;
import com.abdatytch.security_service.constant.SecurityConstants;
import com.abdatytch.security_service.dto.request.PoseDeRegleIpRequest;
import com.abdatytch.security_service.dto.response.RegleDIpResponse;
import com.abdatytch.security_service.entities.RegleDIp;
import com.abdatytch.security_service.exception.RessourceIntrouvableException;
import com.abdatytch.security_service.exception.SecurityPolicyException;
import com.abdatytch.security_service.repositories.RegleDIpRepository;
import com.abdatytch.security_service.service.IRegleDIpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/**
 * Regles de blocage d'adresses : voir {@link IRegleDIpService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegleDIpServiceImpl implements IRegleDIpService {

    private final RegleDIpRepository regles;

    @Override
    public Flux<RegleDIpResponse> regles() {
        Instant maintenant = Instant.now();

        return regles.findByLeveeLeIsNull()
                // Les actives d'abord, puis les plus recentes : un
                // administrateur qui ouvre cette liste cherche ce qui bloque
                // maintenant, pas ce qui a expire le mois dernier.
                .sort(Comparator
                        .comparing((RegleDIp regle) -> !regle.estActive(maintenant))
                        .thenComparing(RegleDIp::getCreeLe, Comparator.reverseOrder()))
                .map(regle -> RegleDIpResponse.de(regle, maintenant));
    }

    @Override
    @Transactional
    public Mono<RegleDIpResponse> poser(PoseDeRegleIpRequest requete, String auteur) {
        // Mono.defer : la normalisation leve sur une adresse illisible, et une
        // levee a l'assemblage sortirait de la chaine. Meme piege que dans les
        // autres services de ce module.
        return Mono.defer(() -> poserMaintenant(requete, auteur));
    }

    private Mono<RegleDIpResponse> poserMaintenant(PoseDeRegleIpRequest requete, String auteur) {
        String adresse = normaliser(requete.getAdresse());
        Instant maintenant = Instant.now();

        if (requete.getExpireLe() != null && !requete.getExpireLe().isAfter(maintenant)) {
            // Une echeance passee produirait une regle ecartee par estActive des
            // l'ecriture : enregistree, publiee, et sans effet.
            return Mono.error(new SecurityPolicyException(
                    SecurityConstants.ECHEANCE_DEJA_PASSEE));
        }

        if (requete.getExpireLe() == null
                && requete.getMotif() != null
                && requete.getMotif().estUneDetectionAutomatique()) {
            // Verifie ICI en plus du @AssertTrue de PoseDeRegleIpRequest, et ce
            // n'est pas une redite inutile : la validation declarative ne
            // s'execute que sur le chemin HTTP. Le jour ou un filtre posera une
            // detection automatique en appelant ce service directement — ce qui
            // est l'evolution prevue — il contournerait la regle, et un faux
            // positif deviendrait permanent.
            //
            // L'invariant appartient a la decision, pas au format de la requete.
            return Mono.error(new SecurityPolicyException(
                    SecurityConstants.PERMANENT_INTERDIT_POUR_DETECTION));
        }

        return regles.findByAdresseAndLeveeLeIsNull(adresse)
                // Seules les regles ACTIVES font obstacle. Une regle expiree et
                // non levee ne bloque plus rien : refuser a cause d'elle
                // obligerait a lever une regle qui ne s'applique deja plus.
                .filter(existante -> existante.estActive(maintenant))
                .hasElements()
                .flatMap(dejaBloquee -> {
                    if (dejaBloquee) {
                        return Mono.error(new SecurityPolicyException(
                                SecurityConstants.ADRESSE_DEJA_BLOQUEE));
                    }
                    return enregistrer(requete, adresse, auteur, maintenant);
                });
    }

    private Mono<RegleDIpResponse> enregistrer(PoseDeRegleIpRequest requete,
                                               String adresse,
                                               String auteur,
                                               Instant maintenant) {
        RegleDIp regle = RegleDIp.builder()
                .id(UUID.randomUUID().toString())
                .adresse(adresse)
                .motif(requete.getMotif())
                .commentaire(requete.getCommentaire() != null
                        ? requete.getCommentaire().trim() : null)
                .expireLe(requete.getExpireLe())
                .posePar(auteur)
                .creeLe(maintenant)
                .build();

        return regles.save(regle)
                // WARN : bloquer une adresse coupe l'acces a tout ce qui se
                // trouve derriere, et derriere le NAT d'un operateur cela peut
                // etre un quartier entier. Une alerte doit pouvoir se declencher
                // des la premiere occurrence, pas sur un taux.
                //
                // L'ADRESSE N'EST PAS JOURNALISEE. Une adresse IP est une donnee
                // personnelle, et les journaux de ce depot ne portent pas de
                // justificatif. Le motif, l'auteur et l'echeance suffisent a
                // reconstituer la decision ; l'adresse se lit en base, sous
                // controle d'acces.
                .doOnSuccess(enregistree -> log.warn(SecurityConstants.LOG_IP_BLOQUEE,
                        enregistree.getMotif(), auteur, enregistree.getExpireLe()))
                .map(enregistree -> RegleDIpResponse.de(enregistree, maintenant));
    }

    @Override
    @Transactional
    public Mono<RegleDIpResponse> lever(String id, String auteur) {
        Instant maintenant = Instant.now();

        return regles.findById(id)
                .map(RegleDIp::dejaEnBase)
                // 404 et non 403 : parcourir des identifiants ne doit pas
                // reveler quelles regles existent. C'est le TYPE qui porte le
                // statut — voir RessourceIntrouvableException.
                .switchIfEmpty(Mono.error(new RessourceIntrouvableException(
                        SecurityConstants.REGLE_IP_INTROUVABLE)))
                .flatMap(regle -> {
                    if (regle.getLeveeLe() != null) {
                        // Deja levee. Repondre un succes ferait croire qu'un
                        // blocage vient d'etre leve, donc qu'il courait.
                        return Mono.error(new SecurityPolicyException(
                                SecurityConstants.REGLE_IP_DEJA_LEVEE));
                    }
                    regle.setLeveeLe(maintenant);
                    regle.setLeveePar(auteur);
                    return regles.save(regle);
                })
                .doOnSuccess(levee -> log.warn(SecurityConstants.LOG_IP_DEBLOQUEE, auteur))
                .map(levee -> RegleDIpResponse.de(levee, maintenant));
    }

    /**
     * Normalise et valide une adresse.
     *
     * <p>Le prefixe CIDR est teste <b>avant</b> la forme litterale, pour que le
     * refus nomme le vrai probleme. {@link AdresseIpLitterale} rejetterait
     * {@code 41.223.0.0/16} comme « pas une adresse litterale », ce qui est vrai
     * mais laisse croire a une faute de frappe : l'administrateur reessaierait
     * une autre ecriture du meme prefixe.
     */
    private static String normaliser(String saisie) {
        if (saisie != null && saisie.indexOf('/') >= 0) {
            throw new SecurityPolicyException(SecurityConstants.CIDR_NON_APPLICABLE);
        }
        String canonique = AdresseIpLitterale.canonique(saisie);
        if (canonique == null) {
            // La saisie n'est PAS reprise dans le message : elle vient de
            // l'appelant et se retrouverait dans les journaux via le
            // gestionnaire d'exceptions, qui journalise le message du refus.
            throw new SecurityPolicyException(SecurityConstants.ADRESSE_INVALIDE);
        }
        return canonique;
    }
}
