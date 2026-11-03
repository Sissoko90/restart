package com.abdatytch.security_service.service.impl;

import com.abdatytch.security_service.constant.SecurityConstants;
import com.abdatytch.security_service.dto.response.ParametreResponse;
import com.abdatytch.security_service.entities.SurchargeDeParametre;
import com.abdatytch.security_service.enums.ParametreDeSecurite;
import com.abdatytch.security_service.enums.TypeDeParametre;
import com.abdatytch.security_service.exception.SecurityPolicyException;
import com.abdatytch.security_service.repositories.SurchargeDeParametreRepository;
import com.abdatytch.security_service.service.IPolitiqueDeSecuriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Politique de securite : voir {@link IPolitiqueDeSecuriteService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PolitiqueDeSecuriteServiceImpl implements IPolitiqueDeSecuriteService {

    private final SurchargeDeParametreRepository surcharges;

    @Override
    public Mono<Map<String, String>> politiqueComplete() {
        return surcharges.findAll()
                .collectMap(SurchargeDeParametre::getCle, SurchargeDeParametre::getValeur)
                // Un depot vide rend une Map vide et non un Mono vide : sans
                // ce defaut, la premiere publication sur une installation
                // neuve se terminerait sans rien ecrire, et le gateway
                // trouverait un cache vide — donc aucune limite de debit.
                .defaultIfEmpty(Map.of())
                .map(this::fusionnerAvecLesDefauts);
    }

    /**
     * Superpose les surcharges aux defauts du catalogue.
     *
     * <p>Une surcharge dont la cle n'est <b>plus</b> au catalogue est ignoree :
     * c'est le cas d'un parametre retire du code alors que sa ligne subsiste en
     * base. La publier ferait apparaitre dans Redis un parametre que plus
     * personne ne lit, et qu'aucune interface ne permet d'enlever.
     */
    private Map<String, String> fusionnerAvecLesDefauts(Map<String, String> decidees) {
        Map<String, String> complete = new LinkedHashMap<>();
        for (ParametreDeSecurite parametre : ParametreDeSecurite.values()) {
            String valeur = decidees.getOrDefault(parametre.getCle(),
                    parametre.getValeurParDefaut());
            complete.put(parametre.getCle(), valeur);
        }
        return complete;
    }

    @Override
    public Flux<ParametreResponse> politiqueDetaillee() {
        return surcharges.findAll()
                .collectMap(SurchargeDeParametre::getCle, surcharge -> surcharge)
                .defaultIfEmpty(Map.of())
                .flatMapMany(decidees -> Flux.fromArray(ParametreDeSecurite.values())
                        .map(parametre -> vueDe(parametre, decidees.get(parametre.getCle()))));
    }

    private static ParametreResponse vueDe(ParametreDeSecurite parametre,
                                           SurchargeDeParametre surcharge) {
        if (surcharge == null) {
            return ParametreResponse.auDefaut(parametre);
        }
        return ParametreResponse.surcharge(parametre, surcharge.getValeur(),
                surcharge.getModifiePar(), surcharge.getModifieLe());
    }

    @Override
    public Mono<ParametreResponse> parametre(String cle) {
        // Mono.defer, et ce n'est pas decoratif : exigerAuCatalogue LEVE.
        //
        // Sans defer, la levee se produit a l'assemblage — chez l'appelant,
        // hors de la chaine. Un onErrorResume place apres ne la verrait jamais,
        // et WebFlux rendrait 500 la ou le refus doit valoir 400. Le piege est
        // le meme que celui corrige dans ClesPubliquesApple et dans les filtres
        // de la passerelle.
        return Mono.defer(() -> {
            ParametreDeSecurite parametre = exigerAuCatalogue(cle);
            return surcharges.findById(cle)
                    .map(surcharge -> vueDe(parametre, surcharge))
                    .defaultIfEmpty(ParametreResponse.auDefaut(parametre));
        });
    }

    @Override
    @Transactional
    public Mono<ParametreResponse> changer(String cle, String valeur, String auteur) {
        // Voir parametre(cle) : exigerAuCatalogue et valider LEVENT tous deux.
        return Mono.defer(() -> changerMaintenant(cle, valeur, auteur));
    }

    private Mono<ParametreResponse> changerMaintenant(String cle, String valeur, String auteur) {
        ParametreDeSecurite parametre = exigerAuCatalogue(cle);
        String normalisee = valider(parametre, valeur);
        Instant maintenant = Instant.now();

        // findById d'abord, pour savoir s'il faut INSERT ou UPDATE.
        //
        // La cle primaire est assignee par l'application : sans ce drapeau,
        // Spring Data R2DBC emet un UPDATE pour une ligne jamais inseree et le
        // save() est silencieusement sans effet. Le piege est deja documente sur
        // SystemSetting et sur RegleDIp.
        return surcharges.findById(cle)
                .map(existante -> {
                    existante.setValeur(normalisee);
                    existante.setModifiePar(auteur);
                    existante.setModifieLe(maintenant);
                    return existante.dejaEnBase();
                })
                .defaultIfEmpty(SurchargeDeParametre.builder()
                        .cle(cle)
                        .valeur(normalisee)
                        .modifiePar(auteur)
                        .modifieLe(maintenant)
                        .build())
                .flatMap(surcharges::save)
                .doOnSuccess(enregistree -> log.warn(SecurityConstants.LOG_PARAMETRE_MODIFIE,
                        cle, normalisee, auteur))
                .map(enregistree -> vueDe(parametre, enregistree));
    }

    @Override
    @Transactional
    public Mono<ParametreResponse> remettreAuDefaut(String cle, String auteur) {
        return Mono.defer(() -> remettreAuDefautMaintenant(cle, auteur));
    }

    private Mono<ParametreResponse> remettreAuDefautMaintenant(String cle, String auteur) {
        ParametreDeSecurite parametre = exigerAuCatalogue(cle);

        // deleteById et non une ecriture du defaut : ecrire le defaut figerait
        // la valeur, et un changement de defaut dans le code ne s'appliquerait
        // plus jamais a cette installation — qui se croirait pourtant remise a
        // zero.
        return surcharges.deleteById(cle)
                .doOnSuccess(rien -> log.warn(
                        SecurityConstants.LOG_PARAMETRE_REMIS_AU_DEFAUT, cle, auteur))
                .thenReturn(ParametreResponse.auDefaut(parametre));
    }

    @Override
    public Mono<String> valeurEffective(ParametreDeSecurite parametre) {
        return surcharges.findById(parametre.getCle())
                .map(SurchargeDeParametre::getValeur)
                // Le catalogue garantit un defaut pour chaque parametre : la
                // question « et si le parametre n'est pas configure » n'existe
                // pas, et aucun appelant n'a a la traiter.
                .defaultIfEmpty(parametre.getValeurParDefaut());
    }

    /**
     * Exige que la cle soit au catalogue.
     *
     * <p>C'est la difference de fond avec le magasin cle/valeur remplace : une
     * faute de frappe y creait un parametre fantome que rien ne lisait, et la
     * lecture retombait sur le defaut. L'administrateur voyait son changement
     * enregistre et sans effet.
     */
    private static ParametreDeSecurite exigerAuCatalogue(String cle) {
        return ParametreDeSecurite.parCle(cle)
                .orElseThrow(() -> new SecurityPolicyException(
                        SecurityConstants.CLE_INCONNUE, cle));
    }

    /**
     * Valide la valeur contre le type et les bornes, et la normalise.
     *
     * <p>A l'<b>ecriture</b> et non a la lecture : un defaut decouvert a la
     * lecture se manifeste loin de l'ecriture qui l'a cause, et rien ne relie
     * les deux.
     */
    private static String valider(ParametreDeSecurite parametre, String valeur) {
        if (valeur == null || valeur.isBlank()) {
            throw new SecurityPolicyException(
                    SecurityConstants.VALEUR_DU_MAUVAIS_TYPE, parametre.getCle());
        }
        String propre = valeur.trim();

        return switch (parametre.getType()) {
            case BOOLEEN -> validerBooleen(parametre, propre);
            case ENTIER, DUREE_MINUTES -> validerEntier(parametre, propre);
            case TEXTE -> propre;
        };
    }

    private static String validerBooleen(ParametreDeSecurite parametre, String valeur) {
        // Boolean.parseBoolean rend false pour TOUTE chaine non reconnue : il
        // accepterait « oui », « 1 » et « peut-etre » en les traitant comme
        // faux. Sur une politique de securite, une valeur mal ecrite qui devient
        // silencieusement « desactive » est le pire des comportements.
        if ("true".equalsIgnoreCase(valeur) || "false".equalsIgnoreCase(valeur)) {
            return valeur.toLowerCase();
        }
        throw new SecurityPolicyException(
                SecurityConstants.VALEUR_DU_MAUVAIS_TYPE, parametre.getCle());
    }

    private static String validerEntier(ParametreDeSecurite parametre, String valeur) {
        int nombre;
        try {
            nombre = Integer.parseInt(valeur);
        } catch (NumberFormatException e) {
            throw new SecurityPolicyException(
                    SecurityConstants.VALEUR_DU_MAUVAIS_TYPE, parametre.getCle());
        }
        if (parametre.getMinimum() != null && nombre < parametre.getMinimum()) {
            throw new SecurityPolicyException(detailDesBornes(parametre), parametre.getCle());
        }
        if (parametre.getMaximum() != null && nombre > parametre.getMaximum()) {
            throw new SecurityPolicyException(detailDesBornes(parametre), parametre.getCle());
        }
        // Rendu sans zero initial : « 05 » et « 5 » designent le meme seuil, et
        // deux ecritures differentes de la meme valeur rendraient une
        // comparaison de chaines fausse cote applicateur.
        return String.valueOf(nombre);
    }

    /**
     * Le refus nomme la borne franchie.
     *
     * <p>Un « valeur hors bornes » sans dire lesquelles oblige l'administrateur
     * a deviner, et il finira par essayer des valeurs au hasard sur une surface
     * de securite.
     */
    private static String detailDesBornes(ParametreDeSecurite parametre) {
        return SecurityConstants.VALEUR_HORS_BORNES
                + " [" + parametre.getMinimum() + ", " + parametre.getMaximum() + "]";
    }
}
