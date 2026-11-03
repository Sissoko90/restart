package com.abdatytch.auth_service.exception;

import com.abdatytch.auth_service.constants.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correspondance entre code d'erreur et statut HTTP.
 *
 * <h2>Le defaut corrige</h2>
 *
 * {@code determineHttpStatus} testait {@code errorCode.getCode().contains("NOT_FOUND")}.
 * Or {@code getCode()} rend la chaine de code — {@code "USER_4001"} — et non le
 * nom de la constante. Les deux branches {@code NOT_FOUND} et {@code EXISTS} ne
 * pouvaient donc <b>jamais</b> s'appliquer : <b>17 des 42 codes</b> tombaient
 * dans le {@code else} final et rendaient <b>500</b>, dont
 * {@code USER_NOT_FOUND}, {@code TEMPLATE_NOT_FOUND} et
 * {@code TWO_FA_SETUP_NOT_FOUND}. Aucun code ne rendait jamais 404 ni 409.
 *
 * <p>Ce qui masquait le defaut : les regles <b>par prefixe</b> fonctionnaient,
 * elles. {@code "USER_4001".startsWith("USER_")} est vrai. Les erreurs
 * d'authentification et de validation recevaient donc le bon statut, et seules
 * les absences et les conflits etaient mal classes — c'est-a-dire les cas qu'on
 * teste le moins souvent a la main.
 *
 * <h2>Pourquoi cela compte</h2>
 *
 * Rendre 500 sur « utilisateur non trouve » n'est pas une imprecision
 * cosmetique. Le client ne peut pas distinguer une absence d'une panne, la
 * supervision compte des defaillances serveur sur un fonctionnement normal, et
 * les alertes qui en decoulent finissent par etre ignorees — ce qui coute
 * precisement au moment ou une vraie panne survient.
 */
@DisplayName("GlobalExceptionHandler - correspondance code / statut HTTP")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpStatus statut(ErrorCode code) {
        return (HttpStatus) ReflectionTestUtils.invokeMethod(
                handler, "determineHttpStatus", code);
    }

    // ==================================================================
    @Nested
    @DisplayName("Absences et conflits")
    class AbsencesEtConflits {

        @ParameterizedTest(name = "{0}")
        @EnumSource(ErrorCode.class)
        @DisplayName("tout code dont le nom dit NOT_FOUND rend 404")
        void absenceRend404(ErrorCode code) {
            if (!code.name().contains("NOT_FOUND")) {
                return;
            }
            assertThat(statut(code))
                    .withFailMessage("%s (code %s) rend %s au lieu de 404 : le client "
                            + "ne peut pas distinguer une absence d'une panne",
                            code.name(), code.getCode(), statut(code))
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ErrorCode.class)
        @DisplayName("tout code dont le nom dit EXISTS rend 409")
        void conflitRend409(ErrorCode code) {
            if (!code.name().contains("EXISTS")) {
                return;
            }
            assertThat(statut(code))
                    .withFailMessage("%s (code %s) rend %s au lieu de 409",
                            code.name(), code.getCode(), statut(code))
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("il existe bien des codes d'absence et de conflit a verifier")
        void lesCategoriesNeSontPasVides() {
            // Sans ce controle, les deux tests parametres ci-dessus passeraient
            // en n'exercant rien si les noms changeaient — exactement le genre de
            // succes qui ressemble a une absence de probleme.
            assertThat(Arrays.stream(ErrorCode.values())
                    .filter(c -> c.name().contains("NOT_FOUND")).toList())
                    .withFailMessage("plus aucun code NOT_FOUND : le test ne verifie rien")
                    .isNotEmpty();
            assertThat(Arrays.stream(ErrorCode.values())
                    .filter(c -> c.name().contains("EXISTS")).toList())
                    .withFailMessage("plus aucun code EXISTS : le test ne verifie rien")
                    .isNotEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les regles par prefixe, qui fonctionnaient deja")
    class Prefixes {

        @ParameterizedTest(name = "{0}")
        @EnumSource(ErrorCode.class)
        @DisplayName("un code AUTH_ rend 401, sauf absence ou conflit")
        void authRend401(ErrorCode code) {
            if (!code.getCode().startsWith("AUTH_")
                    || code.name().contains("NOT_FOUND")
                    || code.name().contains("EXISTS")) {
                return;
            }
            assertThat(statut(code)).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ErrorCode.class)
        @DisplayName("un code REG_, VER_ ou VAL_ rend 400, sauf absence ou conflit")
        void validationRend400(ErrorCode code) {
            boolean prefixe = code.getCode().startsWith("REG_")
                    || code.getCode().startsWith("VER_")
                    || code.getCode().startsWith("VAL_");
            // « ALREADY » et non seulement « EXISTS ».
            //
            // Le gestionnaire branche sur nom.contains("ALREADY") et rend 409,
            // deliberement : un conflit d'etat n'est pas une requete mal
            // formee. Cette exclusion ne citait que « EXISTS », ce qui etait
            // suffisant tant que tous les codes « ALREADY » contenaient aussi
            // « EXISTS ». Des que REG_ a gagne EMAIL_ALREADY_VERIFIED, le test
            // exigeait 400 la ou le gestionnaire rend 409 — le test encodait
            // une regle plus etroite que celle qu'il verifie.
            if (!prefixe || code.name().contains("NOT_FOUND")
                    || code.name().contains("EXISTS") || code.name().contains("ALREADY")) {
                return;
            }
            assertThat(statut(code)).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Ce qui reste en 500")
    class Serveur {

        @Test
        @DisplayName("aucune absence ni conflit ne rend plus 500")
        void plusAucuneAbsenceEn500() {
            List<String> mal = new ArrayList<>();
            for (ErrorCode code : ErrorCode.values()) {
                boolean devraitEtreClient = code.name().contains("NOT_FOUND")
                        || code.name().contains("EXISTS");
                if (devraitEtreClient && statut(code).is5xxServerError()) {
                    mal.add(code.name() + " (" + code.getCode() + ") -> " + statut(code));
                }
            }
            assertThat(mal)
                    .withFailMessage("""
                            Des erreurs attendues sont classees comme defaillances \
                            serveur. C'est le defaut d'origine : le test portait sur \
                            getCode() au lieu de name().

                            %s""", String.join("\n", mal))
                    .isEmpty();
        }

        /**
         * Une dependance muette n'est pas une defaillance de ce service.
         *
         * <p>{@code SERVICE_UNAVAILABLE} porte le prefixe {@code SYS_}, qui ne
         * satisfaisait aucune branche : il retombait sur le {@code else} final
         * et rendait <b>500</b>. Le delai maximal pose sur l'autorite JWT
         * accusait donc auth-service d'une panne d'interface-service.
         *
         * <p>La difference n'est pas cosmetique : 500 dit « ce service est
         * defaillant », 503 dit « reessaie plus tard ». C'est ce que lit un
         * client pour decider s'il reessaie, et une sonde pour decider si
         * l'instance doit sortir du pool.
         */
        @Test
        @DisplayName("une indisponibilite de dependance rend 503, pas 500")
        void indisponibiliteRend503() {
            assertThat(statut(ErrorCode.SERVICE_UNAVAILABLE))
                    .withFailMessage("une dependance muette est rapportee comme une "
                            + "defaillance de ce service : le client ne saura pas qu'il "
                            + "peut reessayer, et la supervision accusera le mauvais "
                            + "composant")
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("tout code dont le nom dit UNAVAILABLE rend 503")
        void toutUnavailableRend503() {
            // Regle sur le NOM, comme pour les absences et les conflits : un
            // futur code d'indisponibilite doit etre couvert sans qu'on y
            // pense.
            List<String> mal = new ArrayList<>();
            for (ErrorCode code : ErrorCode.values()) {
                if (code.name().contains("UNAVAILABLE")
                        && statut(code) != HttpStatus.SERVICE_UNAVAILABLE) {
                    mal.add(code.name() + " -> " + statut(code));
                }
            }
            assertThat(mal).isEmpty();
        }

        @Test
        @DisplayName("un code reellement interne rend bien 500")
        void codeInterneRend500() {
            // La correction ne devait pas tout basculer en erreur client : une
            // defaillance reelle doit continuer a rendre 500, sinon la
            // supervision ne voit plus rien.
            assertThat(statut(ErrorCode.INTERNAL_SERVER_ERROR))
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Erreur metier ou defaillance")
    class Classification {

        @Test
        @DisplayName("isBusinessLogicError reconnait les identifiants invalides")
        void identifiantsInvalidesSontMetier() {
            // Meme defaut que determineHttpStatus : le test portait sur
            // getCode().contains("INVALID_CREDENTIALS"), introuvable dans
            // "AUTH_1001". Ces erreurs etaient donc journalisees comme des
            // defaillances, alors qu'un mot de passe faux est le fonctionnement
            // normal d'un service d'authentification — et le plus frequent.
            Boolean metier = (Boolean) ReflectionTestUtils.invokeMethod(
                    handler, "isBusinessLogicError", ErrorCode.INVALID_CREDENTIALS);
            assertThat(metier)
                    .withFailMessage("un mot de passe faux est classe comme "
                            + "defaillance du systeme")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Erreurs attendues qui rendaient 500")
    class ErreursAttenduesEn500 {

        /**
         * Mesure sur l'environnement de developpement, avant correction :
         *
         * <pre>
         * GET /auth-users/00000000-0000-0000-0000-000000000000
         *   -> 500 {"code":"SYS_5001","message":"Erreur interne du serveur"}
         * </pre>
         *
         * Un identifiant inexistant est un cas d'usage normal, pas une
         * defaillance. En 500, l'appelant ne peut pas distinguer « absent » de
         * « en panne », et la supervision compte de fausses erreurs serveur.
         */
        @Test
        @DisplayName("ResourceNotFoundException rend 404, pas 500")
        void ressourceAbsenteRend404() {
            var reponse = handler.handleResourceNotFound(
                    new com.abdatytch.auth_service.exception.ResourceNotFoundException(
                            "Utilisateur introuvable"));

            assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(reponse.getBody().getMessage()).isEqualTo("Utilisateur introuvable");
        }

        /**
         * Les regles metier de UserRoleManagementServiceImpl portent une
         * explication que l'appelant doit lire :
         * « Impossible de supprimer le role principal. Changez d'abord le role
         * principal. » En 500, cette phrase etait remplacee par « Erreur
         * interne du serveur ».
         */
        @Test
        @DisplayName("IllegalArgumentException rend 400 et CONSERVE le message")
        void argumentInvalideRend400() {
            String explication = "Impossible de supprimer le rôle principal. "
                    + "Changez d'abord le rôle principal.";

            var reponse = handler.handleIllegalArgument(
                    new IllegalArgumentException(explication));

            assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(reponse.getBody().getMessage())
                    .withFailMessage("le message explicatif est perdu : l'administrateur "
                            + "apprend que le serveur a un probleme au lieu d'apprendre "
                            + "ce qu'il doit faire")
                    .isEqualTo(explication);
        }

        /**
         * Mesure sur l'environnement de developpement, avant correction, avec
         * {@code POST /auth/biometric/register/initiate} :
         *
         * <pre>
         * ?type=FINGERPRINT   -> 200
         * ?type=INEXISTANT    -> 500
         * (sans parametre)    -> 500
         * </pre>
         *
         * Tout endpoint prenant un {@code @RequestParam} obligatoire ou une
         * enumeration repondait 500 a une requete simplement mal formee.
         */
        @Test
        @DisplayName("un parametre obligatoire absent rend 400, pas 500")
        void parametreAbsentRend400() {
            var reponse = handler.handleServerWebInput(
                    new org.springframework.web.server.ServerWebInputException(
                            "Required query parameter 'type' is not present."));

            assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(reponse.getBody().getMessage())
                    .withFailMessage("la raison de Spring decrit ce que l'appelant doit "
                            + "corriger : la perdre le laisse sans indication")
                    .contains("type");
        }

        @Test
        @DisplayName("une raison absente retombe sur le message generique, sans NPE")
        void raisonAbsente() {
            var reponse = handler.handleServerWebInput(
                    new org.springframework.web.server.ServerWebInputException(null));

            assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(reponse.getBody().getMessage()).isNotBlank();
        }

        @Test
        @DisplayName("un etat interne incoherent reste en 500")
        void etatInterneResteEn500() {
            // La limite assumee de la correspondance precedente :
            // IllegalArgumentException dit que l'APPELANT a fourni une valeur
            // invalide, donc 400. IllegalStateException et NullPointerException
            // disent que l'etat interne est incoherent, donc 500 — un defaut de
            // programmation doit rester visible comme tel.
            assertThat(handler.handleGenericException(new IllegalStateException("incohérent"))
                    .getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(handler.handleGenericException(new NullPointerException())
                    .getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        /**
         * Le piege : la branche de conflit testait « EXISTS ».
         *
         * <p>{@code TWO_FA_ALREADY_ENABLED} dit « ENABLED ». Il ne satisfaisait
         * donc aucune branche et retombait sur le {@code else} final, soit
         * <b>500</b> — un conflit d'etat annonce comme une defaillance serveur.
         * Exactement le defaut deja corrige pour {@code USER_NOT_FOUND}, qui
         * cherchait « NOT_FOUND » dans la CHAINE de code au lieu du NOM.
         */
        @Test
        @DisplayName("un etat deja acquis rend 409, pas 500")
        void etatDejaAcquisRend409() {
            var reponse = handler.handleAuthException(
                    new AuthException(ErrorCode.TWO_FA_ALREADY_ENABLED));

            assertThat(reponse.getStatusCode())
                    .withFailMessage("TWO_FA_ALREADY_ENABLED retombe sur le else final : "
                            + "un conflit d'etat annonce comme une panne serveur")
                    .isEqualTo(HttpStatus.CONFLICT);
        }

        /**
         * TOUT code portant « ALREADY » rend 409, et pas seulement
         * « ALREADY_EXISTS ».
         *
         * <p>Ce test ne couvrait que {@code ALREADY_EXISTS}. L'exclusion de
         * {@code validationRend400} ne citait, elle aussi, que
         * {@code EXISTS} : les deux etaient d'accord entre eux et plus etroits
         * que le gestionnaire. Tant que chaque code « ALREADY » contenait aussi
         * « EXISTS », rien ne se voyait.
         *
         * <p>L'ajout de {@code EMAIL_ALREADY_VERIFIED} a rompu cette
         * coincidence : le test exigeait 400 la ou le gestionnaire rend 409.
         * Parcourir l'enumeration entiere ferme le trou pour de bon — un
         * nouveau code « ALREADY » est desormais couvert sans qu'on y pense.
         */
        @Test
        @DisplayName("tout conflit d'etat en ALREADY rend 409, pas seulement ALREADY_EXISTS")
        void tousLesConflitsEn409() {
            List<String> mal = new ArrayList<>();
            for (ErrorCode c : ErrorCode.values()) {
                if (!c.name().contains("ALREADY") || c.name().contains("NOT_FOUND")) {
                    continue;
                }
                HttpStatus obtenu = handler.handleAuthException(new AuthException(c))
                        .getStatusCode() instanceof HttpStatus statut
                        ? statut
                        : null;
                if (obtenu != HttpStatus.CONFLICT) {
                    mal.add(c.name() + " (" + c.getCode() + ") -> " + obtenu);
                }
            }
            assertThat(mal)
                    .withFailMessage("""
                            Des conflits d'etat ne rendent pas 409 : %s

                            Un « deja fait » annonce comme une requete mal formee \
                            (400) ou comme une panne (500) envoie le client \
                            corriger ce qui est correct.""", mal)
                    .isEmpty();
        }

        @Test
        @DisplayName("un 500 ne divulgue pas le message interne")
        void le500NeDivulguePas() {
            // Inchange, et il faut que ca le reste : le message d'une
            // defaillance peut contenir une requete SQL, un chemin de fichier ou
            // un fragment de configuration.
            var reponse = handler.handleGenericException(
                    new RuntimeException("bad SQL grammar [SELECT ... FROM users]"));

            assertThat(reponse.getBody().getMessage()).doesNotContain("SELECT");
        }
    }
}
