package com.abdatytch.interface_service.controller;

import com.abdatytch.interface_service.entity.User;
import com.abdatytch.interface_service.repository.UserRepository;
import com.abdatytch.interface_service.service.EmailService;
import com.abdatytch.interface_service.service.GeoLocationService;
import com.abdatytch.interface_service.service.IDevJwtService;
import com.abdatytch.commons.totp.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Confirmation d'accès administrateur — « C'est moi / Ce n'est pas moi ».
 *
 * <h2>Ce que cet endpoint décide</h2>
 *
 * Il débloque un compte administrateur, ou met une adresse IP en liste noire
 * pour 120 jours. Les deux sont des actions de sécurité, déclenchées par un
 * simple clic sur un lien reçu par courriel.
 *
 * <h2>Les trois défauts corrigés</h2>
 *
 * <ol>
 *   <li><b>{@code findAll()} puis filtre en mémoire.</b> La table entière des
 *       utilisateurs était chargée à chaque clic, pour en trouver un.
 *       {@code findByAdminConfirmationToken} existait déjà dans le dépôt et
 *       n'était appelée nulle part — encore une méthode écrite, présente et
 *       jamais atteinte ;</li>
 *   <li><b>comparaison par {@code .equals}</b> d'un jeton à usage unique, dont
 *       le temps d'exécution dépend du nombre de caractères identiques en
 *       tête ;</li>
 *   <li><b>le blocage d'IP partait en {@code .subscribe(...)}</b>, donc en
 *       parallèle de la réponse. Celle-ci annonçait « IP bloquée avec succès »
 *       avant de savoir si Redis avait accepté l'écriture. Sur l'action « ce
 *       n'est pas moi », le blocage <i>est</i> l'objet de la requête.</li>
 * </ol>
 *
 * <h2>Ce qui N'EST PAS corrigé, et pourquoi</h2>
 *
 * Le jeton voyage dans la <b>chaîne de requête</b> d'un GET, donc dans les
 * journaux d'accès, l'historique du navigateur et l'en-tête {@code Referer}.
 * C'est la même exposition que celle corrigée ailleurs dans ce dépôt pour les
 * données personnelles — mais ici, la contrainte est réelle : un lien cliqué
 * depuis un courriel ne peut être qu'un GET avec paramètres.
 *
 * <p>Le risque résiduel est borné par deux propriétés vérifiées ci-dessous : le
 * jeton <b>expire</b> et il est <b>invalidé à l'usage</b>. Changer cela
 * demanderait une page intermédiaire qui poste le jeton, donc une modification
 * du parcours par courriel.
 */
@DisplayName("AdminController - confirmation d'accès administrateur")
class AdminConfirmationTest {

    private static final String JETON = "jeton-de-confirmation-abc123";
    private static final String IP_SUSPECTE = "203.0.113.7";

    private UserRepository userRepository;
    private ReactiveStringRedisTemplate redisTemplate;
    private ReactiveValueOperations<String, String> valeurs;
    private WebTestClient client;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        redisTemplate = Mockito.mock(ReactiveStringRedisTemplate.class);
        valeurs = Mockito.mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valeurs);
        when(valeurs.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));
        when(userRepository.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));

        AdminController controleur = new AdminController(
                userRepository,
                Mockito.mock(PasswordEncoder.class),
                Mockito.mock(TotpService.class),
                Mockito.mock(IDevJwtService.class),
                Mockito.mock(EmailService.class),
                Mockito.mock(GeoLocationService.class),
                redisTemplate);

        client = WebTestClient.bindToController(controleur).build();
    }

    /** Un administrateur en attente de confirmation, jeton encore valide. */
    private User administrateurEnAttente() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("admin@exemple.test");
        user.setAdminConfirmationPending(true);
        user.setAdminConfirmationToken(JETON);
        user.setAdminConfirmationExpiresAt(LocalDateTime.now().plusMinutes(10));
        user.setAdminConfirmationIp(IP_SUSPECTE);
        return user;
    }

    private WebTestClient.ResponseSpec confirmer(String jeton, String action) {
        return client.get()
                .uri(uri -> uri.path("/admin/confirm")
                        .queryParam("token", jeton)
                        .queryParam("action", action)
                        .build())
                .exchange();
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : recherche indexée, pas de balayage")
    class RechercheIndexee {

        @Test
        @DisplayName("la recherche se fait PAR JETON, et non sur toute la table")
        void rechercheParJeton() {
            // LE DEFAUT. findByAdminConfirmationToken existait deja et
            // n'etait appelee nulle part ; le controleur chargeait toute la
            // table a chaque clic puis comparait avec .equals — un balayage
            // complet, et une comparaison dont le temps depend des caracteres
            // communs en tete.
            when(userRepository.findByAdminConfirmationToken(JETON))
                    .thenReturn(Mono.just(administrateurEnAttente()));

            confirmer(JETON, "confirm").expectStatus().isOk();

            verify(userRepository).findByAdminConfirmationToken(JETON);
            verify(userRepository, never()).findAll();
        }

        @Test
        @DisplayName("un jeton inconnu rend 404 sans rien modifier")
        void jetonInconnu() {
            when(userRepository.findByAdminConfirmationToken(anyString()))
                    .thenReturn(Mono.empty());

            confirmer("jeton-inexistant", "confirm")
                    .expectStatus().isNotFound();

            verify(userRepository, never()).save(any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : le blocage d'IP est confirmé avant la réponse")
    class BlocageConfirme {

        @Test
        @DisplayName("l'action « ce n'est pas moi » écrit bien la clé de liste noire")
        void blocageEcrit() {
            when(userRepository.findByAdminConfirmationToken(JETON))
                    .thenReturn(Mono.just(administrateurEnAttente()));

            confirmer(JETON, "block").expectStatus().isOk();

            // La cle est partagee avec le Gateway. Un prefixe divergent ferait
            // ecrire un blocage que personne n'applique — defaut deja rencontre
            // ici, l'ancien prefixe « ip:blacklist: » n'etant lu par personne.
            verify(valeurs).set(
                    Mockito.eq("security:ip:blacklist:" + IP_SUSPECTE),
                    anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("un échec de Redis n'est PAS rapporté comme un blocage réussi")
        void echecNonMasque() {
            // LE DEFAUT. Le blocage partait en .subscribe(...), donc en
            // parallele de la reponse : celle-ci annonçait « IP bloquée avec
            // succès » sans savoir si Redis avait accepte l'ecriture, et un
            // echec ne laissait qu'une ligne dans les journaux.
            //
            // Une API qui affirme avoir bloque sans l'avoir fait est pire qu'une
            // API qui echoue, parce que personne ne va verifier.
            when(userRepository.findByAdminConfirmationToken(JETON))
                    .thenReturn(Mono.just(administrateurEnAttente()));
            when(valeurs.set(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(Mono.error(new RuntimeException("Redis injoignable")));

            confirmer(JETON, "block")
                    .expectStatus().value(code -> assertThat(code)
                            .withFailMessage("un blocage échoué est rapporté comme "
                                    + "réussi (%s)", code)
                            .isNotEqualTo(200));
        }

        @Test
        @DisplayName("une réponse VIDE de Redis n'est pas rapportée comme un succès")
        void reponseVideNonRapporteeCommeSucces() {
            // Correction d'une erreur de ma part, trouvee par mutation.
            //
            // J'avais d'abord ecrit que, sans defaultIfEmpty, la requete serait
            // PERDUE. C'est faux : contrairement a `flatMap`, **`then()`
            // souscrit a la suite meme sur une source vide**. J'avais applique
            // par reflexe la lecon du piege Mono<Void> la ou elle ne vaut pas, et
            // la mutation a refuse de mourir — a juste titre.
            //
            // Ce qui se joue reellement ici est le sens du repli : un `set` qui
            // ne confirme rien doit fermer, pas ouvrir. Sans cela, la reponse
            // annonce « IP bloquée avec succès » sur un blocage dont rien
            // n'atteste.
            when(userRepository.findByAdminConfirmationToken(JETON))
                    .thenReturn(Mono.just(administrateurEnAttente()));
            when(valeurs.set(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(Mono.empty());

            confirmer(JETON, "block")
                    .expectStatus().value(code -> assertThat(code)
                            .withFailMessage("un blocage non confirmé par Redis est "
                                    + "rapporté comme réussi (%s)", code)
                            .isNotEqualTo(200));
        }

        @Test
        @DisplayName("une IP absente ne fait pas échouer le déblocage")
        void ipAbsente() {
            // Le parcours doit aboutir meme si l'adresse n'a pas ete enregistree
            // : refuser ici laisserait le compte bloque sans recours.
            User user = administrateurEnAttente();
            user.setAdminConfirmationIp(null);
            when(userRepository.findByAdminConfirmationToken(JETON))
                    .thenReturn(Mono.just(user));

            confirmer(JETON, "block").expectStatus().isOk();

            verify(valeurs, never()).set(anyString(), anyString(), any(Duration.class));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Ce qui borne le risque du jeton dans l'URL")
    class BornesDuJeton {

        @Test
        @DisplayName("un jeton expiré est refusé, et effacé au passage")
        void jetonExpire() {
            // Premiere borne. Le jeton voyage dans la chaine de requete d'un
            // GET — contrainte d'un lien reçu par courriel — donc dans les
            // journaux d'acces. Son expiration limite la fenetre pendant
            // laquelle une copie lue dans ces journaux serait utilisable.
            User user = administrateurEnAttente();
            user.setAdminConfirmationExpiresAt(LocalDateTime.now().minusMinutes(1));
            when(userRepository.findByAdminConfirmationToken(JETON))
                    .thenReturn(Mono.just(user));

            confirmer(JETON, "confirm").expectStatus().isEqualTo(410);

            assertThat(user.getAdminConfirmationToken())
                    .withFailMessage("un jeton expiré reste en base : il y demeure "
                            + "indéfiniment, lisible par tout accès à la table")
                    .isNull();
        }

        @Test
        @DisplayName("le jeton est INVALIDÉ à l'usage - un second clic ne refait rien")
        void jetonAUsageUnique() {
            // Seconde borne, et la plus importante : une copie lue dans les
            // journaux d'acces n'est utilisable qu'avant le clic legitime.
            User user = administrateurEnAttente();
            when(userRepository.findByAdminConfirmationToken(JETON))
                    .thenReturn(Mono.just(user));

            confirmer(JETON, "confirm").expectStatus().isOk();

            assertThat(user.getAdminConfirmationToken())
                    .withFailMessage("le jeton survit à son usage : il reste "
                            + "rejouable depuis les journaux d'accès")
                    .isNull();
            assertThat(user.isAdminConfirmationPending()).isFalse();
        }

        @Test
        @DisplayName("une action inconnue ne modifie rien")
        void actionInconnue() {
            // Sans ce controle, une action mal orthographiee tomberait dans une
            // branche par defaut — et le pire defaut serait de debloquer.
            when(userRepository.findByAdminConfirmationToken(JETON))
                    .thenReturn(Mono.just(administrateurEnAttente()));

            confirmer(JETON, "debloquer-tout").expectStatus().isBadRequest();

            verify(userRepository, never()).save(any());
            verify(valeurs, never()).set(anyString(), anyString(), any(Duration.class));
        }
    }
}
