package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.entities.UserRoleAssignment;
import com.abdatytch.auth_service.enums.UserRole;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.repository.UserRoleAssignmentRepository;
import com.abdatytch.commons.totp.TotpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Cree le compte SUPER_ADMIN au deploiement.
 *
 * <h2>Pourquoi un seeder, et pas un endpoint</h2>
 *
 * {@code SUPER_ADMIN} n'est pas attribuable par l'API — voir
 * {@link UserRole#estAttribuableParApi()} et {@code docs/ROLES.md}. Sans cette
 * regle, un {@code ADMIN} compromis se promouvrait lui-meme : le pouvoir de
 * distribuer les roles inclurait celui de s'octroyer le plus eleve.
 *
 * <p>Ce seeder est donc le <b>seul</b> chemin par lequel le role existe. Il
 * s'execute au demarrage, et ne fait rien si le compte est deja la.
 *
 * <h2>Identifiants distincts de ceux de l'ADMIN</h2>
 *
 * Les variables sont {@code SUPER_ADMIN_*}, et non {@code ADMIN_DEFAULT_*}.
 * Partager les identifiants entre le responsable technique et l'administrateur
 * fonctionnel annulerait la separation que ces deux roles etablissent : une
 * compromission de l'un livrerait l'autre.
 *
 * <h2>Aucune valeur par defaut</h2>
 *
 * Si les variables ne sont pas fournies, le seeder <b>ne cree rien</b> et le
 * signale. Il ne retombe pas sur un mot de passe connu : un compte
 * administrateur cree a l'insu de tout le monde, avec des identifiants lisibles
 * dans le depot, est pire que pas de compte du tout.
 *
 * <p>Pour un environnement sans base : {@code app.seeders.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(name = "app.seeders.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SuperAdminSeeder {

    private final UserRepository userRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;

    /**
     * Ordre 1 : avant tout autre seeder, pour qu'un responsable technique existe
     * si l'un d'eux echoue.
     */
    @Bean
    @Order(1)
    // Le nom de cette methode ne doit PAS etre celui de sa classe.
    //
    // Elle s'appelait superAdminSeeder, comme la classe @Configuration qui la
    // porte : Spring enregistre la classe sous le nom superAdminSeeder, puis la
    // methode @Bean sous le meme nom, et refuse la seconde definition —
    // « A bean with that name has already been defined and overriding is
    // disabled ». Le service ne demarrait pas.
    //
    // Le conflit n'apparaissait qu'en profil dev, parce que
    // @EnableFeignClients sans basePackages declenche un second balayage qui
    // re-enregistre les classes de configuration : d'ou les deux origines
    // distinctes dans le message d'erreur, « defined in file » et « defined in
    // class path resource ». AuthServiceApplicationTests chargeait le contexte
    // sans declencher ce second balayage, et passait.
    public ApplicationRunner superAdminApplicationRunner(SuperAdminProperties properties) {
        return args -> semer(properties)
                .doOnError(e -> log.error("Creation du SUPER_ADMIN impossible : {}", e.getMessage()))
                .onErrorComplete()
                // La souscription est ce qui declenche reellement le travail.
                // Un Mono construit puis abandonne n'execute rien, et ne produit
                // aucune erreur pour le signaler.
                .subscribe();
    }

    private Mono<Void> semer(SuperAdminProperties properties) {
        if (!properties.estConfigure()) {
            log.warn("SUPER_ADMIN non cree : SUPER_ADMIN_EMAIL, SUPER_ADMIN_PHONE et "
                    + "SUPER_ADMIN_PASSWORD doivent etre renseignes. Aucun compte de repli "
                    + "n'est cree - un compte aux identifiants connus serait pire que rien.");
            return Mono.empty();
        }

        // Le resultat de la recherche est transforme en BOOLEEN avant de decider.
        //
        // Un `.switchIfEmpty(...)` apres `garantirRole()` serait faux :
        // garantirRole renvoie un Mono<Void>, donc toujours vide, et la branche
        // de repli s'executerait meme lorsque le compte existe. Le seeder
        // recreait alors le compte a chaque demarrage, ecrasant au passage un mot
        // de passe change depuis.
        return userRepository.findByEmail(properties.email())
                .flatMap(existant -> {
                    log.info("SUPER_ADMIN deja present ({})", properties.email());
                    // Le role peut avoir ete retire a la main : on le retablit,
                    // sans quoi le compte existe sans ses pouvoirs et personne ne
                    // peut plus rien administrer.
                    return garantirRole(existant).thenReturn(Boolean.TRUE);
                })
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(existeDeja -> Boolean.TRUE.equals(existeDeja)
                        ? Mono.<Void>empty()
                        : creer(properties));
    }

    private Mono<Void> creer(SuperAdminProperties properties) {
        User superAdmin = new User();
        superAdmin.setId(UUID.randomUUID().toString());
        superAdmin.setEmail(properties.email());
        superAdmin.setPhoneNumber(properties.phone());
        superAdmin.setCountryCode(properties.countryCode());
        superAdmin.setFirstName(properties.firstName());
        superAdmin.setLastName(properties.lastName());
        // Hachage CONSERVE en appel direct, et c'est le seul endroit permis.
        //
        // Ce code tourne au demarrage de l'application, jamais sur une requete :
        // il n'y a pas d'EventLoop a immobiliser, et les 241 ms de BCrypt
        // s'ajoutent une fois au temps de demarrage. Le porter sur
        // boundedElastic n'apporterait rien et rendrait le semis asynchrone,
        // donc plus difficile a ordonner avec les migrations.
        //
        // HachageHorsEventLoopTest connait cette exception et la justifie.
        superAdmin.setPasswordHash(passwordEncoder.encode(properties.password()));
        superAdmin.setRole(UserRole.SUPER_ADMIN);

        // Le secret du second facteur est genere ici et remis UNE SEULE FOIS,
        // a l'enrolement via POST /auth/admin/totp/enroll. Il n'est jamais
        // journalise, meme tronque : huit caracteres d'un secret TOTP en
        // revelent 40 bits sur 160.
        superAdmin.setTotpSecret(totpService.generateTotpSecret());
        superAdmin.setTotpEnabled(false);
        superAdmin.setIsActive(true);
        // Le compte est verifie d'office : il n'y a personne pour lui envoyer un
        // code, et le deploiement ne peut pas attendre une confirmation humaine.
        superAdmin.setIsEmailVerified(true);
        superAdmin.setIsPhoneVerified(true);
        superAdmin.setCreatedAt(LocalDateTime.now());

        return userRepository.save(superAdmin)
                .flatMap(this::garantirRole)
                .doOnSuccess(v -> {
                    log.info("SUPER_ADMIN cree : {}", properties.email());
                    log.info("Le mot de passe est celui de SUPER_ADMIN_PASSWORD - il n'est "
                            + "pas journalise, meme tronque.");
                    log.info("Second facteur : enroler via POST /auth/admin/totp/enroll "
                            + "avec l'identifiant et le mot de passe. Le secret n'est "
                            + "remis qu'une fois.");
                })
                .then();
    }

    /** Affectation de role idempotente. */
    private Mono<Void> garantirRole(User user) {
        return roleAssignmentRepository.findByUserId(user.getId())
                .filter(assignment -> assignment.getRole() == UserRole.SUPER_ADMIN)
                .hasElements()
                .flatMap(deja -> Boolean.TRUE.equals(deja)
                        ? Mono.empty()
                        : creerAffectation(user));
    }

    /**
     * Crée l'affectation SUPER_ADMIN, avec <b>toutes</b> les colonnes
     * obligatoires.
     *
     * <h3>Le défaut corrigé</h3>
     *
     * {@code email} et {@code assigned_at} sont {@code NOT NULL} dans
     * {@code user_role_assignments}, et cette méthode ne les renseignait pas.
     * L'insertion échouait donc à chaque démarrage, et <b>le SUPER_ADMIN n'a
     * jamais eu d'affectation de rôle</b>.
     *
     * <h3>Pourquoi personne ne l'avait vu</h3>
     *
     * {@code JwtServiceImpl} se replie sur {@code users.role} lorsque la liste
     * des affectations est vide :
     *
     * <pre>{@code
     * .map(roles -> roles.isEmpty()
     *         ? user.getRole().expand()...   // repli
     *         : roles)
     * }</pre>
     *
     * La connexion fonctionnait donc parfaitement, le jeton portait bien
     * {@code [ROLE_SUPER_ADMIN, ROLE_ADMIN]}, et l'échec d'insertion restait
     * invisible. Il ne se voyait qu'en touchant la <b>gestion</b> des rôles,
     * qui lit les affectations et elles seules :
     *
     * <pre>
     * GET    /auth-users/by-role/ADMIN              -> []    (pour un SUPER_ADMIN)
     * DELETE /auth-users/{id}/roles/ADMIN           -> « dernier rôle »
     * PUT    /auth-users/{id}/primary-role/SUPER_ADMIN -> échec
     * </pre>
     *
     * <p>Un repli qui rattrape une écriture ratée est pire qu'une absence de
     * repli : il transforme une erreur bruyante en divergence silencieuse entre
     * deux sources.
     */
    private Mono<Void> creerAffectation(User user) {
        UserRoleAssignment assignment = new UserRoleAssignment();
        assignment.setId(UUID.randomUUID().toString());
        assignment.setUserId(user.getId());
        // LA colonne NOT NULL que cette methode omettait.
        assignment.setEmail(user.getEmail());
        // NOT NULL aussi, mais deja initialisee par l'entite : pose ici par
        // explicitation, pas par necessite.
        assignment.setAssignedAt(LocalDateTime.now());
        // Facultative, mais `findActiveByEmailAndRole` et les recherches par
        // numero s'en servent : l'omettre rendrait l'affectation introuvable
        // par telephone.
        assignment.setPhoneNumber(user.getPhoneNumber());
        assignment.setRole(UserRole.SUPER_ADMIN);
        assignment.setIsPrimary(true);
        assignment.setIsActive(true);
        assignment.setCreatedAt(LocalDateTime.now());
        return roleAssignmentRepository.save(assignment)
                .doOnSuccess(saved -> log.info("Role SUPER_ADMIN affecte a {}", user.getId()))
                // L'echec N'EST PLUS avale : sans affectation, la gestion des
                // roles est inutilisable pour ce compte, et le repli du JWT
                // ferait croire que tout va bien.
                .doOnError(e -> log.error("Affectation SUPER_ADMIN impossible pour {} : {}",
                        user.getId(), e.getMessage()))
                .then();
    }
}
