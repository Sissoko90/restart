package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.response.CodeDeSecoursResponse;
import com.abdatytch.auth_service.dto.response.EtatDesCodesResponse;
import com.abdatytch.auth_service.dto.response.LotDeCodesResponse;
import com.abdatytch.auth_service.entities.CodeDeSecours;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.repository.CodeDeSecoursRepository;
import com.abdatytch.auth_service.service.ICodeDeSecoursService;
import com.abdatytch.auth_service.service.IMotDePasseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Codes de secours : voir {@link ICodeDeSecoursService}.
 */
@Service
@Slf4j
public class CodeDeSecoursServiceImpl implements ICodeDeSecoursService {

    /**
     * Alphabet des codes, sans caracteres ambigus.
     *
     * <p>Ni {@code O} ni {@code 0}, ni {@code I} ni {@code 1}, ni {@code L}. Un
     * code de secours est recopie <b>a la main</b> depuis un papier : la
     * confusion entre O et zero est la premiere cause de saisie fausse, et une
     * saisie fausse compte comme un echec — donc rapproche du verrouillage
     * quelqu'un qui avait le bon code.
     */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /** Longueur d'un code. Dix caracteres sur 31 symboles : assez large. */
    private static final int LONGUEUR = 10;

    /** Caracteres rendus en clair, pour que la personne reconnaisse le code. */
    private static final int LONGUEUR_DU_DEBUT = 4;

    private static final SecureRandom ALEA = new SecureRandom();

    private final CodeDeSecoursRepository codes;
    private final IMotDePasseService motDePasseService;
    private final int nombreParLot;

    public CodeDeSecoursServiceImpl(
            CodeDeSecoursRepository codes,
            IMotDePasseService motDePasseService,
            @Value("${auth.codes-de-secours.nombre-par-lot:10}") int nombreParLot) {
        this.codes = codes;
        this.motDePasseService = motDePasseService;
        this.nombreParLot = nombreParLot;
    }

    @Override
    @Transactional
    public Mono<LotDeCodesResponse> regenerer(User user) {
        // Mono.defer : la verification de l'enrolement leve, et une levee a
        // l'assemblage sortirait de la chaine. Piege corrige plusieurs fois
        // dans ce depot.
        return Mono.defer(() -> regenererMaintenant(user));
    }

    private Mono<LotDeCodesResponse> regenererMaintenant(User user) {
        if (!Boolean.TRUE.equals(user.getTotpEnabled())) {
            // Des codes de secours ne secourent de rien quand il n'y a rien a
            // contourner. Les remettre laisserait croire a une protection
            // supplementaire la ou le compte n'a meme pas de second facteur.
            log.info(AppConstants.LOG_INFO_CODES_SANS_SECOND_FACTEUR, user.getId());
            return Mono.error(new AuthException(ErrorCode.TWO_FA_NOT_ENABLED));
        }

        LocalDateTime maintenant = LocalDateTime.now();
        List<String> enClair = new ArrayList<>(nombreParLot);
        for (int i = 0; i < nombreParLot; i++) {
            enClair.add(produireUnCode());
        }

        // Le lot precedent est INVALIDE avant que le nouveau soit pose.
        //
        // Deux lots valides a la fois doubleraient la surface sans que la
        // personne le sache : elle croirait avoir remplace ses codes alors que
        // les anciens fonctionneraient encore.
        return invaliderLeLot(user.getId(), maintenant)
                .flatMap(nombreInvalide -> Flux.fromIterable(enClair)
                        .concatMap(code -> enregistrer(user.getId(), code))
                        .then(Mono.just(nombreInvalide)))
                // Les CODES ne sont jamais journalises : ils remplacent le
                // second facteur. Seul leur nombre l'est.
                .doOnSuccess(nombreInvalide -> log.info(
                        AppConstants.LOG_INFO_CODES_REGENERES,
                        enClair.size(), user.getId(), nombreInvalide))
                .map(nombreInvalide -> LotDeCodesResponse.builder()
                        .codes(enClair)
                        .nombre(enClair.size())
                        .lotPrecedentInvalide(nombreInvalide > 0)
                        .message(AppConstants.CODES_DE_SECOURS_UNIQUES)
                        .build());
    }

    @Override
    public Mono<Long> invaliderLeLot(String userId) {
        return invaliderLeLot(userId, LocalDateTime.now())
                .doOnNext(nombre -> {
                    if (nombre > 0) {
                        log.info(AppConstants.LOG_INFO_CODES_INVALIDES, nombre, userId);
                    }
                });
    }

    /**
     * Marque le lot utilisable comme <b>invalide</b>, et non consomme.
     *
     * <p>La distinction est visible par la personne : un code « consomme »
     * signale un usage, et lui ferait soupçonner une intrusion alors qu'elle a
     * simplement demande de nouveaux codes — ou desactive son second facteur.
     *
     * <p>L'horodatage est passe par l'appelant pour que tout un lot porte la
     * <b>meme</b> marque : un {@code LocalDateTime.now()} par ligne les
     * disperserait sur quelques millisecondes, et rien ne dirait plus qu'elles
     * ont ete invalidees ensemble.
     */
    private Mono<Long> invaliderLeLot(String userId, LocalDateTime maintenant) {
        return codes.findByUserIdAndConsommeLeIsNullAndInvalideLeIsNull(userId)
                .flatMap(code -> {
                    code.setInvalideLe(maintenant);
                    return codes.save(code);
                })
                .count();
    }

    private Mono<CodeDeSecours> enregistrer(String userId, String enClair) {
        // Le chiffrement passe par IMotDePasseService, seul detenteur du
        // PasswordEncoder, qui porte BCrypt sur boundedElastic. L'appeler
        // directement ici remettrait 241 ms de calcul sur une EventLoop.
        return motDePasseService.chiffrer(enClair)
                .flatMap(empreinte -> {
                    CodeDeSecours ligne = CodeDeSecours.builder()
                            .userId(userId)
                            .empreinte(empreinte)
                            .debut(enClair.substring(0, LONGUEUR_DU_DEBUT))
                            .build();
                    // L'identifiant et created_at sont poses par
                    // prepareForSave(), via le BeforeConvertCallback de
                    // R2dbcAuditingConfig. Il est fixe ici quand meme pour que
                    // les tests, ou le callback n'est pas enregistre, voient une
                    // ligne complete.
                    ligne.setId(UUID.randomUUID().toString());
                    return codes.save(ligne);
                });
    }

    @Override
    @Transactional
    public Mono<Boolean> consommer(User user, String codePresente, String ipAddress) {
        if (codePresente == null || codePresente.isBlank()) {
            return Mono.just(false);
        }
        // Normalise comme la personne l'a sous les yeux : majuscules, sans
        // espaces. Un code recopie depuis un papier arrive souvent avec un
        // espace ou en minuscules, et refuser pour cela ferait compter un echec
        // a quelqu'un qui avait le bon code.
        String propre = codePresente.trim().replace(" ", "")
                .toUpperCase(java.util.Locale.ROOT);

        return codes.findByUserIdAndConsommeLeIsNullAndInvalideLeIsNull(user.getId())
                .collectList()
                .flatMap(utilisables -> chercherEtConsommer(
                        user, utilisables, propre, ipAddress))
                .onErrorResume(erreur -> {
                    // Une panne de base ne doit pas faire echouer la connexion
                    // autrement que par « code refuse » : le parcours normal,
                    // avec le second facteur, reste possible.
                    log.error(AppConstants.LOG_ERROR_CODES_ILLISIBLES,
                            erreur.getClass().getSimpleName());
                    return Mono.just(false);
                });
    }

    /**
     * Compare le code aux empreintes utilisables, puis consomme celle qui
     * correspond.
     *
     * <h2>Ce que le temps de reponse laisse voir, et pourquoi c'est admis</h2>
     *
     * {@code concatMap} suivi de {@code next()} s'arrete a la premiere
     * correspondance : le temps de reponse depend donc de la <b>position</b> du
     * code dans le lot, et un echec coute la comparaison de tous les codes
     * restants — donc revele leur nombre.
     *
     * <p>Cette fuite est admise, et non ignoree. Ce qu'elle apprend a qui
     * l'observe : combien de codes restent sur un compte dont il detient deja le
     * mot de passe. Les codes sont des tirages independants de
     * {@code SecureRandom} : connaitre leur nombre, ou la position de l'un
     * d'eux, ne reduit en rien l'espace a deviner pour les autres.
     *
     * <p>Pretendre le contraire couterait dix comparaisons BCrypt — 2,4
     * secondes mesurees — a chaque tentative, et ne masquerait de toute facon
     * pas le nombre : une liste de dix codes met dix fois plus de temps a etre
     * parcourue qu'une liste d'un seul, quoi qu'on fasse du resultat.
     *
     * <h2>Le cout reste borne</h2>
     *
     * Dix comparaisons BCrypt au pire, sur {@code boundedElastic} — jamais sur
     * une EventLoop — pour une operation qui sert quelques fois dans la vie d'un
     * compte.
     */
    private Mono<Boolean> chercherEtConsommer(User user, List<CodeDeSecours> utilisables,
                                              String propre, String ipAddress) {
        if (utilisables.isEmpty()) {
            return Mono.just(false);
        }

        return Flux.fromIterable(utilisables)
                // flatMap et non map : un mappeur qui rend null ne produit PAS
                // un element vide, il LEVE un NullPointerException. L'ecrire
                // avec map et une sentinelle a ete essaye, et le resultat etait
                // pire que faux : la levee etait rattrapee par l'onErrorResume
                // de consommer(), donc tout code place apres un code non
                // concordant etait refuse — silencieusement, et seulement
                // quand la personne n'utilisait pas son premier code.
                //
                // Ici, Mono.empty() signifie « pas celui-la » et concatMap
                // passe simplement au suivant.
                .concatMap(code -> motDePasseService.correspond(propre, code.getEmpreinte())
                        .filter(Boolean.TRUE::equals)
                        .map(concorde -> code))
                .next()
                .flatMap(trouve -> {
                    trouve.setConsommeLe(LocalDateTime.now());
                    trouve.setConsommeIp(ipAddress);
                    return codes.save(trouve)
                            // WARN : un code de secours consomme est un
                            // evenement rare qui signale une perte d'appareil —
                            // ou une intrusion. Une alerte doit pouvoir se
                            // declencher des la premiere occurrence.
                            .doOnSuccess(consomme -> log.warn(
                                    AppConstants.LOG_WARN_CODE_DE_SECOURS_CONSOMME,
                                    consomme.getDebut(), user.getId()))
                            // Le reste se deduit de la liste deja chargee :
                            // aucune requete de plus. Prevenir a epuisement
                            // est le seul moment utile — apres, la personne n'a
                            // plus de sortie si elle perd son appareil.
                            .doOnSuccess(consomme -> avertirSiLotEpuise(
                                    user.getId(), utilisables.size() - 1))
                            .thenReturn(true);
                })
                // defaultIfEmpty sur le Mono TYPE, et non sur un Mono<Void>
                // final : la regle corrigee une douzaine de fois dans ce depot.
                //
                // Aucun comptage d'echec ici : il appartient a
                // ISecondFacteurService, seul endroit ou « la tentative a
                // echoue » se decide. Le mettre ici le ferait disparaitre des
                // que cette lecture echoue — et la force brute sur le TOTP
                // deviendrait gratuite.
                .defaultIfEmpty(false);
    }

    /** Seuil en dessous duquel l'epuisement du lot est signale. */
    private static final int SEUIL_DALERTE = 2;

    private static void avertirSiLotEpuise(String userId, int restants) {
        if (restants <= SEUIL_DALERTE) {
            log.warn(AppConstants.LOG_WARN_CODES_PRESQUE_EPUISES, restants, userId);
        }
    }

    @Override
    public Mono<EtatDesCodesResponse> etat(String userId) {
        return codes.findByUserId(userId)
                // Les utilisables d'abord, puis les plus recemment consommes :
                // c'est l'ordre dans lequel la personne les cherche.
                .sort(Comparator
                        .comparing(CodeDeSecours::estUtilisable, Comparator.reverseOrder())
                        .thenComparing(CodeDeSecours::getConsommeLe,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .collectList()
                .map(lignes -> {
                    // Le nombre restant est compte sur la liste deja chargee,
                    // et non par une seconde requete : deux lectures separees
                    // pourraient tomber de part et d'autre d'une consommation,
                    // et afficher « 7 restants » au-dessus de 6 lignes
                    // utilisables.
                    long restants = lignes.stream()
                            .filter(CodeDeSecours::estUtilisable)
                            .count();
                    return EtatDesCodesResponse.builder()
                            .restants(restants)
                            .total(lignes.size())
                            .message(restants == 0
                                    ? AppConstants.CODES_DE_SECOURS_AUCUN_RESTANT
                                    : null)
                            .codes(lignes.stream()
                                    .map(CodeDeSecoursResponse::de)
                                    .toList())
                            .build();
                });
    }

    /**
     * Produit un code dans l'alphabet sans caracteres ambigus.
     *
     * <p>{@code SecureRandom} et non {@code Random} : un code de secours
     * remplace le second facteur, et un generateur previsible rendrait le lot
     * entier devinable a partir d'un seul code connu.
     */
    private static String produireUnCode() {
        StringBuilder code = new StringBuilder(LONGUEUR);
        for (int i = 0; i < LONGUEUR; i++) {
            code.append(ALPHABET.charAt(ALEA.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
