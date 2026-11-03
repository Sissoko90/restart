package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.request.PhoneAuthRequest;
import com.abdatytch.auth_service.dto.request.ResendCodeRequest;
import com.abdatytch.auth_service.dto.response.VerificationResponse;
import com.abdatytch.auth_service.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'aiguillage du renvoi de code, et l'indicatif qui en decoule.
 *
 * <h2>Pourquoi ces tests sont ici et non dans AuthControllerTest</h2>
 *
 * Ils y etaient, parce que le controleur portait la regle : il construisait le
 * {@code PhoneAuthRequest}, deduisait l'indicatif, et appelait toujours
 * {@code initiatePhoneRegister}. Choisir entre deux parcours d'inscription est
 * une regle metier, et elle a rejoint le service.
 *
 * <p>Les assertions suivent la regle. Les laisser dans le test du controleur en
 * aurait fait des assertions sur un simulacre : le controleur delegue
 * desormais, et verifier qu'il delegue ne dit rien de ce qui est delegue.
 *
 * <h2>Pourquoi une sous-classe et non un simulacre complet</h2>
 *
 * {@code AuthServiceImpl} a une quinzaine de dependances dont aucune n'est
 * utilisee par {@code renvoyerLeCode} : il n'appelle que ses deux propres
 * methodes de parcours. Monter quinze simulacres pour en exercer une reviendrait
 * a tester le constructeur. La sous-classe capture les deux appels et laisse la
 * methode sous test intacte.
 */
@DisplayName("Renvoi du code : l'aiguillage selon le canal")
class RenvoiDuCodeTest {

    /**
     * {@code AuthServiceImpl} dont seuls les deux parcours sont interceptes.
     *
     * <p>Toutes les dependances sont nulles, et c'est sans danger : le code sous
     * test ne les touche pas. Si un jour il les touche, le test echouera par
     * {@code NullPointerException} — ce qui est le signal voulu, et non un
     * silence.
     */
    private static final class ServiceInstrumente extends AuthServiceImpl {

        private final List<PhoneAuthRequest> parTelephone = new ArrayList<>();
        private final List<String> parAdresse = new ArrayList<>();

        ServiceInstrumente() {
            super(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }

        @Override
        public Mono<VerificationResponse> initiatePhoneRegister(PhoneAuthRequest request) {
            parTelephone.add(request);
            return Mono.just(new VerificationResponse());
        }

        @Override
        public Mono<VerificationResponse> initiateEmailRegister(
                com.abdatytch.auth_service.dto.request.EmailOtpRequest request) {
            parAdresse.add(request.getEmail());
            return Mono.just(new VerificationResponse());
        }
    }

    private ServiceInstrumente service() {
        return new ServiceInstrumente();
    }

    // ==================================================================
    @Nested
    @DisplayName("Le canal choisi decide du parcours")
    class AiguillageDuCanal {

        /**
         * Le defaut d'origine : l'endpoint appelait TOUJOURS le parcours
         * telephone.
         */
        @Test
        @DisplayName("une adresse mene au parcours par adresse, et pas au telephone")
        void adresseMeneAuParcoursAdresse() {
            ServiceInstrumente service = service();

            service.renvoyerLeCode(ResendCodeRequest.builder()
                    .email("awa@exemple.test")
                    .build()).block();

            assertThat(service.parAdresse)
                    .withFailMessage("""
                            Une demande par adresse n'a pas emprunte le parcours \
                            par adresse.

                            C'etait le defaut : l'endpoint appelait toujours \
                            initiatePhoneRegister, donc qui s'etait inscrit par \
                            adresse n'avait aucun moyen de se faire renvoyer son \
                            code — et recommencer butait sur \
                            EMAIL_ALREADY_EXISTS.""")
                    .containsExactly("awa@exemple.test");
            assertThat(service.parTelephone).isEmpty();
        }

        @Test
        @DisplayName("un numero mene au parcours par telephone")
        void numeroMeneAuParcoursTelephone() {
            ServiceInstrumente service = service();

            service.renvoyerLeCode(ResendCodeRequest.builder()
                    .phoneNumber("+22374427759")
                    .build()).block();

            assertThat(service.parTelephone).hasSize(1);
            assertThat(service.parAdresse).isEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L'indicatif reste celui d'avant")
    class IndicatifInchange {

        /**
         * La valeur enregistree ne doit pas bouger.
         *
         * <p>Le deplacement de la regle du controleur vers le service est un
         * refactoring : s'il changeait l'indicatif deduit, ce serait une
         * migration de donnees deguisee, et les comptes deja crees porteraient
         * une valeur differente de celle des nouveaux.
         */
        /**
         * Les valeurs attendues sont celles que la plateforme produit
         * <b>reellement</b>, et deux d'entre elles sont fausses au sens de
         * E.164.
         *
         * <h2>Ce que le test constate, et n'arrange pas</h2>
         *
         * {@code PhoneNumbers.prefixeIndicatif} coupe les <b>quatre</b> premiers
         * caracteres, point. Cela tombe juste pour le Mali ({@code +223}) et
         * faux pour tout indicatif plus court : la France {@code +33} donne
         * {@code +336}, les Etats-Unis {@code +1} donnent {@code +155} — le
         * prefixe emporte des chiffres du numero d'abonne.
         *
         * <p>La javadoc de {@code PhoneNumbers} assume ce choix : elle parle de
         * « longueur historique » et dit n'avoir traite que le plantage. Le
         * corriger changerait la valeur de {@code country_code} des comptes deja
         * crees, donc une migration de donnees — pas un refactoring.
         *
         * <p>Le test fixe donc le comportement observe. Ecrire {@code +33} ici
         * aurait fait echouer la suite sur un defaut connu, et l'ecrire en
         * silence aurait laisse croire que la deduction est correcte.
         */
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "+22374427759, +223",
                // Faux au sens E.164, et c'est le comportement reel : voir
                // la javadoc ci-dessus.
                "+33612345678, +336",
                "+15551234567, +155",
        })
        @DisplayName("un numero complet donne le meme indicatif qu'avant")
        void indicatifDUnNumeroComplet(String numero, String indicatifAttendu) {
            ServiceInstrumente service = service();

            service.renvoyerLeCode(ResendCodeRequest.builder()
                    .phoneNumber(numero)
                    .build()).block();

            assertThat(service.parTelephone.get(0).getCountryCode())
                    .isEqualTo(indicatifAttendu);
        }

        /**
         * Le defaut de robustesse corrige, et toujours ferme.
         *
         * <p>Le motif E.164 accepte deux caracteres. Le controleur deduisait
         * l'indicatif par {@code substring(0, 4)}, ce qui levait une
         * {@code StringIndexOutOfBoundsException} sur ces valeurs — toutes
         * recevables. L'endpoint rendait 500 sur une entree que le client
         * controle.
         */
        @ParameterizedTest(name = "numero court « {0} »")
        @ValueSource(strings = {"12", "+22", "223"})
        @DisplayName("un numero plus court que l'indicatif ne leve plus")
        void numeroCourtNeLevePlus(String numero) {
            ServiceInstrumente service = service();

            service.renvoyerLeCode(ResendCodeRequest.builder()
                    .phoneNumber(numero)
                    .build()).block();

            assertThat(service.parTelephone)
                    .withFailMessage("le parcours n'a pas ete atteint : la "
                            + "deduction de l'indicatif a leve sur « %s »", numero)
                    .hasSize(1);
        }
    }
}
