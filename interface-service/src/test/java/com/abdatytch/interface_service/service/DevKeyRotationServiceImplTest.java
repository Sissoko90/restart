package com.abdatytch.interface_service.service;

import com.abdatytch.interface_service.service.impl.DevKeyRotationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La rotation des clés qui signent TOUS les jetons de la plateforme.
 *
 * <h2>Pourquoi ce test arrive maintenant</h2>
 *
 * Cette classe n'en avait <b>aucun</b>, et son endpoint —
 * {@code POST /dev/admin/keys/rotate} — était injoignable : la règle
 * {@code hasRole("SUPER_ADMIN")} ne pouvait être satisfaite faute de
 * convertisseur d'autorités dans {@code SecurityConfig}. Ce convertisseur vient
 * d'être posé, donc ce chemin est désormais <b>atteignable</b> par un
 * SUPER_ADMIN — et il n'avait jamais été exercé.
 *
 * <p>Rendre un endpoint joignable sans l'avoir testé, c'est déplacer le risque
 * plutôt que le réduire.
 *
 * <h2>Ce que ce test a trouvé</h2>
 *
 * {@code jwt.dev.key.rotation.enabled} vaut <b>false par défaut</b>.
 * {@code rotateKeys()} rendait alors {@code Mono.empty()}, et le
 * {@code .then(...)} du contrôleur transformait ce vide en
 * {@code 200 {"status":"success"}} annonçant une rotation accomplie, avec un
 * {@code newKeyId} <b>inchangé</b>.
 *
 * <p>Un exploitant qui fait tourner les clés après une suspicion de
 * compromission s'entendait donc dire que c'était fait. C'est le motif que ce
 * dépôt rencontre le plus souvent — une opération qui annonce un succès sans
 * avoir agi — et il était ici sur l'endpoint le plus privilégié de la
 * plateforme.
 *
 * <h2>Ce que ce test NE couvre pas, et il faut le dire</h2>
 *
 * La <b>durabilité</b> de la rotation. {@code keyCache} est une
 * {@code ConcurrentHashMap} en mémoire, et {@code rotateKeys()} ne persiste que
 * l'<b>identifiant</b> de la clé dans Redis, jamais la matière de la clé. Donc :
 *
 * <ul>
 *   <li>au redémarrage, {@code loadInitialKeys()} recharge la clé configurée et
 *       la clé pivotée est <b>perdue</b> — tous les jetons qu'elle a signés
 *       deviennent invérifiables ;</li>
 *   <li>avec plusieurs instances, celle qui pivote signe avec une clé que les
 *       autres n'ont pas dans leur JWKS : les jetons sont refusés selon
 *       l'instance qui valide.</li>
 * </ul>
 *
 * <p>Écrire l'identifiant dans Redis sans la clé fait <b>paraître</b> la
 * rotation persistée. Corriger cela demande de décider si Redis détient de la
 * matière de clé privée — une décision de conception, pas un correctif. Les
 * tests ci-dessous constatent donc l'état en mémoire sans le figer comme
 * souhaitable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DevKeyRotationServiceImpl - rotation des cles de signature")
class DevKeyRotationServiceImplTest {

    /** Engendree une fois : une paire RSA coute cher, et son contenu importe peu. */
    private static KeyPair paireConfiguree;

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    private DevKeyRotationServiceImpl service;

    @BeforeEach
    void preparer() throws Exception {
        if (paireConfiguree == null) {
            KeyPairGenerator generateur = KeyPairGenerator.getInstance("RSA");
            generateur.initialize(2048);
            paireConfiguree = generateur.generateKeyPair();
        }

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.set(anyString(), anyString())).thenReturn(Mono.just(true));

        service = new DevKeyRotationServiceImpl(redisTemplate);

        // Les @Value ne sont pas injectes hors du conteneur. Les poser en Base64
        // exerce le vrai chemin de chargement — celui que la configuration
        // emprunte — plutot que la paire ephemere, qui est un repli reserve au
        // poste de developpement.
        ReflectionTestUtils.setField(service, "privateKeyBase64",
                Base64.getEncoder().encodeToString(paireConfiguree.getPrivate().getEncoded()));
        ReflectionTestUtils.setField(service, "publicKeyBase64",
                Base64.getEncoder().encodeToString(paireConfiguree.getPublic().getEncoded()));
        ReflectionTestUtils.setField(service, "gracePeriodDays", 7);
        ReflectionTestUtils.setField(service, "rotationIntervalDays", 90);
    }

    /** Active la rotation puis initialise. L'ordre compte : {@code init()} lit les champs. */
    private void initialiser(boolean rotationActivee) {
        ReflectionTestUtils.setField(service, "rotationEnabled", rotationActivee);
        service.init();
    }

    /** Antidate l'expiration d'une clé du cache, par réflexion. */
    @SuppressWarnings("unchecked")
    private void expirer(String kid) {
        var cache = (java.util.Map<String, Object>)
                ReflectionTestUtils.getField(service, "keyCache");
        Object info = cache.get(kid);
        ReflectionTestUtils.setField(info, "expiresAt",
                java.time.Instant.now().minusSeconds(60));
    }

    // ==================================================================
    @Nested
    @DisplayName("Le chargement initial")
    class ChargementInitial {

        @Test
        @DisplayName("La cle configuree est chargee, et son kid en derive")
        void cleConfigureeChargee() {
            initialiser(false);

            assertThat(service.getCurrentPublicKey()).isEqualTo(paireConfiguree.getPublic());
            assertThat(service.getCurrentPrivateKey()).isEqualTo(paireConfiguree.getPrivate());
            assertThat(service.getCurrentKeyId()).isNotBlank();
        }

        /**
         * Le {@code kid} est une empreinte de la clé PUBLIQUE, donc déterministe.
         *
         * <p>C'est ce qui permet au JWKS et à l'en-tête des jetons de désigner la
         * même clé. Un {@code kid} aléatoire — le repli en cas d'absence de
         * SHA-256 — produirait un identifiant ne correspondant à aucune clé, et
         * la validation échouerait partout sans que rien ne le dise.
         */
        @Test
        @DisplayName("Le kid est deterministe : deux chargements de la meme cle le repetent")
        void kidDeterministe() {
            initialiser(false);
            String premier = service.getCurrentKeyId();

            DevKeyRotationServiceImpl second = new DevKeyRotationServiceImpl(redisTemplate);
            ReflectionTestUtils.setField(second, "privateKeyBase64",
                    Base64.getEncoder().encodeToString(paireConfiguree.getPrivate().getEncoded()));
            ReflectionTestUtils.setField(second, "publicKeyBase64",
                    Base64.getEncoder().encodeToString(paireConfiguree.getPublic().getEncoded()));
            ReflectionTestUtils.setField(second, "rotationEnabled", false);
            second.init();

            assertThat(second.getCurrentKeyId()).isEqualTo(premier);
        }

        /**
         * Sans clé configurée et sans autorisation d'éphémère, le service REFUSE.
         *
         * <p>C'est voulu, et c'est une exception à la règle « jamais de refus de
         * démarrage pour une variable absente » : démarrer sans clé de signature
         * ne donnerait pas un service dégradé mais un service incapable de
         * signer, dont chaque appel échouerait plus loin, sans rapport apparent
         * avec la cause.
         */
        @Test
        @DisplayName("Sans cle ni autorisation d'ephemere : refus explicite")
        void refusSansCle() {
            DevKeyRotationServiceImpl sansCle = new DevKeyRotationServiceImpl(redisTemplate);
            ReflectionTestUtils.setField(sansCle, "privateKeyBase64", "");
            ReflectionTestUtils.setField(sansCle, "publicKeyBase64", "");
            ReflectionTestUtils.setField(sansCle, "privateKeyPath", "");
            ReflectionTestUtils.setField(sansCle, "publicKeyPath", "");
            ReflectionTestUtils.setField(sansCle, "allowEphemeralKeys", false);
            ReflectionTestUtils.setField(sansCle, "rotationEnabled", false);

            assertThatThrownBy(sansCle::init)
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to initialize");
        }

        /**
         * Le classpath est refusé comme source de clé privée.
         *
         * <p>Le défaut historique : la clé vivait dans
         * {@code src/main/resources}, donc <b>empaquetée dans le JAR livré</b>.
         * Quiconque disposait de l'artefact pouvait l'extraire et forger
         * n'importe quel jeton, pour n'importe quel rôle.
         */
        @Test
        @DisplayName("Une cle sur le classpath est REFUSEE")
        void classpathRefuse() {
            DevKeyRotationServiceImpl surClasspath = new DevKeyRotationServiceImpl(redisTemplate);
            ReflectionTestUtils.setField(surClasspath, "privateKeyBase64", "");
            ReflectionTestUtils.setField(surClasspath, "publicKeyBase64", "");
            ReflectionTestUtils.setField(surClasspath, "privateKeyPath",
                    "classpath:keys/dev_private.pem");
            ReflectionTestUtils.setField(surClasspath, "publicKeyPath",
                    "classpath:keys/dev_public.pem");
            ReflectionTestUtils.setField(surClasspath, "rotationEnabled", false);

            assertThatThrownBy(surClasspath::init)
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La rotation DESACTIVEE ne doit pas annoncer un succes")
    class RotationDesactivee {

        /**
         * LE DÉFAUT GARDÉ ICI.
         *
         * <p>{@code rotationEnabled} est faux par défaut. La rotation rendait
         * alors un {@code Mono} <b>vide</b>, que le {@code .then(...)} du
         * contrôleur transformait en réponse de succès. L'opération doit au
         * contraire se signaler comme n'ayant pas eu lieu.
         *
         * <p>Un {@code Mono} vide est le pire des trois états possibles : il ne
         * ressemble ni à un succès ni à une erreur, et chaque appelant en décide
         * autrement. Ici le contrôleur l'a lu comme un succès.
         */
        @Test
        @DisplayName("Rotation desactivee : signalee en ERREUR, et non par un Mono vide")
        void rotationDesactiveeSignalee() {
            initialiser(false);

            StepVerifier.create(service.rotateKeys())
                    .expectErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("rotation"))
                    .verify();
        }

        @Test
        @DisplayName("Rotation desactivee : la cle courante ne change PAS")
        void cleInchangee() {
            initialiser(false);
            String avant = service.getCurrentKeyId();

            StepVerifier.create(service.rotateKeys()).expectError().verify();

            assertThat(service.getCurrentKeyId()).isEqualTo(avant);
            assertThat(service.getCurrentPrivateKey()).isEqualTo(paireConfiguree.getPrivate());
        }

        @Test
        @DisplayName("Rotation desactivee : rien n'est ecrit dans Redis")
        void rienDansRedis() {
            initialiser(false);

            StepVerifier.create(service.rotateKeys()).expectError().verify();

            verify(valueOps, never()).set(anyString(), anyString());
        }

        @Test
        @DisplayName("Rotation desactivee : isRotationNeeded rend toujours faux")
        void rotationJamaisNecessaire() {
            initialiser(false);
            assertThat(service.isRotationNeeded()).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La rotation ACTIVEE")
    class RotationActivee {

        @Test
        @DisplayName("Une nouvelle cle devient courante, et l'ancienne reste valide")
        void nouvelleCleCourante() {
            initialiser(true);
            String ancienKid = service.getCurrentKeyId();

            StepVerifier.create(service.rotateKeys()).verifyComplete();

            assertThat(service.getCurrentKeyId()).isNotEqualTo(ancienKid);
            assertThat(service.getCurrentPrivateKey()).isNotEqualTo(paireConfiguree.getPrivate());

            // L'ancienne cle PUBLIQUE reste servie : sans cela, tous les jetons
            // en circulation deviendraient invalides a l'instant de la rotation.
            // C'est toute la raison de la periode de grace.
            assertThat(service.getPublicKeyById(ancienKid))
                    .isEqualTo(paireConfiguree.getPublic());
            assertThat(service.getAllValidPublicKeysById()).hasSize(2);
        }

        @Test
        @DisplayName("Le nouvel identifiant est ecrit dans Redis")
        void identifiantEcrit() {
            initialiser(true);

            StepVerifier.create(service.rotateKeys()).verifyComplete();

            verify(valueOps).set("dev:jwt:keys:current", service.getCurrentKeyId());
        }

        /**
         * Le JWKS et l'en-tête des jetons doivent désigner la même clé.
         *
         * <p>{@code getAllValidPublicKeysById()} est la source unique du JWKS. Si
         * le {@code kid} publié différait de celui inscrit dans l'en-tête, chaque
         * consommateur chercherait une clé inexistante et refuserait tous les
         * jetons — une panne totale d'authentification, sans message qui désigne
         * la cause.
         */
        @Test
        @DisplayName("Le kid courant figure toujours dans le JWKS")
        void kidCourantPublie() {
            initialiser(true);
            assertThat(service.getAllValidPublicKeysById())
                    .containsKey(service.getCurrentKeyId());

            StepVerifier.create(service.rotateKeys()).verifyComplete();

            assertThat(service.getAllValidPublicKeysById())
                    .containsKey(service.getCurrentKeyId());
        }

        @Test
        @DisplayName("Deux rotations de suite gardent les trois cles valides")
        void deuxRotations() {
            initialiser(true);
            String kid1 = service.getCurrentKeyId();

            StepVerifier.create(service.rotateKeys()).verifyComplete();
            String kid2 = service.getCurrentKeyId();

            StepVerifier.create(service.rotateKeys()).verifyComplete();
            String kid3 = service.getCurrentKeyId();

            assertThat(kid1).isNotEqualTo(kid2);
            assertThat(kid2).isNotEqualTo(kid3);
            assertThat(service.getAllValidPublicKeysById())
                    .containsKeys(kid1, kid2, kid3);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le nettoyage des cles expirees")
    class NettoyageDesCles {

        /**
         * La clé COURANTE n'est jamais retirée, même marquée expirée.
         *
         * <p>La retirer rendrait le service incapable de valider ce qu'il vient
         * de signer. La boucle de nettoyage la saute explicitement, et ce test
         * garde cette exception.
         */
        @Test
        @DisplayName("La cle courante n'est JAMAIS retiree")
        void cleCouranteConservee() {
            initialiser(true);
            String kidCourant = service.getCurrentKeyId();

            // On force l'expiration de la cle courante : la garde doit tenir
            // malgre cela.
            expirer(kidCourant);

            StepVerifier.create(service.cleanupExpiredKeys())
                    .assertNext(retirees -> assertThat(retirees).isZero())
                    .verifyComplete();

            assertThat(service.getCurrentKeyId()).isEqualTo(kidCourant);
        }

        @Test
        @DisplayName("Une ancienne cle expiree est retiree, et compte")
        void ancienneCleRetiree() {
            initialiser(true);
            String ancienKid = service.getCurrentKeyId();
            StepVerifier.create(service.rotateKeys()).verifyComplete();

            expirer(ancienKid);

            StepVerifier.create(service.cleanupExpiredKeys())
                    .assertNext(retirees -> assertThat(retirees).isEqualTo(1))
                    .verifyComplete();

            assertThat(service.getPublicKeyById(ancienKid)).isNull();
            assertThat(service.getAllValidPublicKeysById())
                    .doesNotContainKey(ancienKid);
        }

        @Test
        @DisplayName("Une cle expiree n'est plus servie, meme avant nettoyage")
        void cleExpireeNonServie() {
            initialiser(true);
            String ancienKid = service.getCurrentKeyId();
            StepVerifier.create(service.rotateKeys()).verifyComplete();

            expirer(ancienKid);

            // Le nettoyage n'a pas encore tourne, mais la lecture doit deja
            // refuser : sinon un jeton signe par une cle expiree resterait
            // accepte jusqu'au prochain passage du planificateur.
            assertThat(service.getPublicKeyById(ancienKid)).isNull();
        }

        @Test
        @DisplayName("Un kid inconnu rend null, sans lever")
        void kidInconnu() {
            initialiser(false);
            assertThat(service.getPublicKeyById("kid-qui-nexiste-pas")).isNull();
        }
    }
}
