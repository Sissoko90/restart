package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.ContexteDeSecurite;
import com.abdatytch.auth_service.dto.request.GeoLocationData;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.impl.AlerteDeSecuriteServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Les alertes de sécurité, et la propriété qui les rend utiles.
 *
 * <h2>Ce qu'une alerte de sécurité est</h2>
 *
 * Elle ne bloque rien, n'autorise rien. Elle rend <b>visible</b> ce qui serait
 * passé inaperçu — et c'est toute la différence entre un détournement de compte
 * réussi et un détournement repéré.
 *
 * <h2>Les quatre tests qui comptent</h2>
 *
 * <ol>
 *   <li>un changement d'adresse prévient l'<b>ANCIENNE</b>. Prévenir la nouvelle
 *       ne préviendrait que l'attaquant, qui vient de la choisir — et c'est une
 *       inversion qui s'écrit sans y penser, puisque l'entité en mémoire porte
 *       déjà la nouvelle valeur ;</li>
 *   <li>rien n'est envoyé à une coordonnée <b>non vérifiée</b> : une adresse que
 *       personne n'a prouvée peut avoir été posée par l'attaquant au tour
 *       précédent, ce qui ferait de l'alerte un accusé de réception à son
 *       intention ;</li>
 *   <li>un échec d'envoi <b>ne fait pas échouer</b> l'opération signalée, qui est
 *       déjà enregistrée — mais il est <b>compté</b>, sinon la protection
 *       s'éteint sans bruit ;</li>
 *   <li>le texte envoyé ne contient ni code, ni jeton, ni lien d'action.</li>
 * </ol>
 */
@DisplayName("Alertes de securite : rendre visible ce qui serait passe inaperçu")
class AlerteDeSecuriteServiceImplTest {

    private static final String ANCIENNE_ADRESSE = "awa.traore@exemple.test";
    private static final String NOUVELLE_ADRESSE = "attaquant@exemple.test";
    private static final String TELEPHONE = "+22374427759";
    private static final String IP = "41.223.10.5";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/131.0.0.0";

    /** Un envoi retenu : destinataire, sujet, corps. */
    private record Envoi(String canal, String destinataire, String sujet, String corps) {
    }

    private List<Envoi> envois;
    private boolean smtpEnPanne;
    private SimpleMeterRegistry registre;
    private UserRepository userRepository;
    private AlerteDeSecuriteServiceImpl service;
    private User compte;

    @BeforeEach
    void setUp() {
        envois = new ArrayList<>();
        smtpEnPanne = false;
        registre = new SimpleMeterRegistry();
        userRepository = mock(UserRepository.class);

        IEmailService emails = new IEmailService() {
            @Override
            public Mono<Void> sendWelcomeEmail(String toEmail, String firstName) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> sendVerificationEmail(
                    String toEmail, String code,
                    com.abdatytch.auth_service.enums.VerificationCodeType type) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> envoyerAvisDeSecurite(String toEmail, String sujet,
                                                     String corps) {
                if (smtpEnPanne) {
                    return Mono.error(new IllegalStateException("SMTP indisponible"));
                }
                envois.add(new Envoi("courriel", toEmail, sujet, corps));
                return Mono.empty();
            }
        };

        ISmsService sms = new ISmsService() {
            @Override
            public Mono<Void> sendSms(String phoneNumber, String message) {
                envois.add(new Envoi("sms", phoneNumber, null, message));
                return Mono.empty();
            }

            @Override
            public boolean envoiActif() {
                return true;
            }
        };

        service = new AlerteDeSecuriteServiceImpl(
                userRepository, emails, sms, new AuthMetrics(registre));

        compte = new User();
        compte.setId("client-1");
        compte.setEmail(ANCIENNE_ADRESSE);
        compte.setPhoneNumber(TELEPHONE);
        compte.setIsEmailVerified(true);
        compte.setIsPhoneVerified(true);
        compte.setAlerteNouvelleConnexion(false);
    }

    private ContexteDeSecurite contexte() {
        return ContexteDeSecurite.de(IP, USER_AGENT, GeoLocationData.builder()
                .city("Bamako")
                .country("Mali")
                .build());
    }

    private double compteur(String evenement, String resultat) {
        var compteur = registre.find("auth.security.alerte")
                .tag("evenement", evenement)
                .tag("resultat", resultat)
                .counter();
        return compteur == null ? 0 : compteur.count();
    }

    // ==================================================================
    @Nested
    @DisplayName("Changement d'adresse : l'ANCIENNE est prevenue")
    class ChangementDadresse {

        @Test
        @DisplayName("l'avis part a l'ANCIENNE adresse, jamais a la nouvelle")
        void ancienneAdressePrevenue() {
            // LE test de cette suite.
            //
            // L'entite porte deja la nouvelle adresse a cet instant — c'est
            // l'etat reel apres `ensureEmailAvailable`. Un service qui lirait
            // user.getEmail() enverrait donc l'avis a celui qui vient de la
            // choisir, et l'alerte servirait l'attaquant.
            compte.setEmail(NOUVELLE_ADRESSE);

            StepVerifier.create(service.adresseModifiee(
                            compte, ANCIENNE_ADRESSE, true, contexte()))
                    .verifyComplete();

            assertThat(envois).hasSize(1);
            assertThat(envois.get(0).destinataire()).isEqualTo(ANCIENNE_ADRESSE);
            assertThat(envois.get(0).destinataire()).isNotEqualTo(NOUVELLE_ADRESSE);
        }

        @Test
        @DisplayName("le texte dit explicitement qu'il arrive sur l'ANCIENNE adresse")
        void texteExplicite() {
            compte.setEmail(NOUVELLE_ADRESSE);
            service.adresseModifiee(compte, ANCIENNE_ADRESSE, true, contexte()).block();

            // Sans cette precision, la personne lit « votre adresse a ete
            // modifiee » sur une adresse qui, de son point de vue, est toujours
            // la bonne — et ne comprend pas qu'elle vient de perdre l'acces.
            assertThat(envois.get(0).corps()).containsIgnoringCase("ancienne adresse");
        }

        @Test
        @DisplayName("une ancienne adresse NON verifiee ne reçoit rien")
        void ancienneNonVerifieeIgnoree() {
            compte.setEmail(NOUVELLE_ADRESSE);
            compte.setIsPhoneVerified(false);

            StepVerifier.create(service.adresseModifiee(
                            compte, ANCIENNE_ADRESSE, false, contexte()))
                    .verifyComplete();

            // Une adresse que personne n'a prouvee peut avoir ete posee par
            // l'attaquant au tour precedent : l'avis serait alors un accuse de
            // reception a son intention.
            assertThat(envois).isEmpty();
            assertThat(compteur("adresse_modifiee", "sans_destinataire")).isEqualTo(1);
        }

        @Test
        @DisplayName("sans adresse verifiee, le numero verifie sert de repli")
        void repliParSms() {
            compte.setEmail(NOUVELLE_ADRESSE);

            service.adresseModifiee(compte, ANCIENNE_ADRESSE, false, contexte()).block();

            // Rien : l'ancienne adresse n'etait pas verifiee, donc pas de
            // courriel — mais le numero l'est, donc le repli joue.
            assertThat(envois).hasSize(1);
            assertThat(envois.get(0).canal()).isEqualTo("sms");
            assertThat(envois.get(0).destinataire()).isEqualTo(TELEPHONE);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Changement de mot de passe")
    class ChangementDeMotDePasse {

        @Test
        @DisplayName("l'avis part a l'adresse verifiee")
        void adresseVerifiee() {
            StepVerifier.create(service.motDePasseModifie(compte, contexte()))
                    .verifyComplete();

            assertThat(envois).hasSize(1);
            assertThat(envois.get(0).canal()).isEqualTo("courriel");
            assertThat(envois.get(0).destinataire()).isEqualTo(ANCIENNE_ADRESSE);
            assertThat(compteur("mot_de_passe_modifie", "envoyee")).isEqualTo(1);
        }

        @Test
        @DisplayName("envoye SANS CONDITION : aucun reglage ne le desactive")
        void sansCondition() {
            // Le contraste avec l'alerte de connexion est delibere.
            //
            // Un changement de mot de passe est rare et toujours notable : le
            // rendre optionnel le desactiverait pour tous ceux qui n'ouvrent
            // jamais leurs reglages — c'est-a-dire la plupart, et precisement
            // ceux qu'un detournement surprendrait.
            compte.setAlerteNouvelleConnexion(false);

            service.motDePasseModifie(compte, contexte()).block();

            assertThat(envois).hasSize(1);
        }

        @Test
        @DisplayName("sans adresse verifiee, le SMS prend le relais")
        void repliParSms() {
            compte.setIsEmailVerified(false);

            service.motDePasseModifie(compte, contexte()).block();

            // Un compte inscrit par telephone — cas courant ici — n'a pas
            // d'adresse verifiee. Sans repli, il ne serait prevenu de rien.
            assertThat(envois).hasSize(1);
            assertThat(envois.get(0).canal()).isEqualTo("sms");
        }

        @Test
        @DisplayName("un seul canal : pas de courriel ET de SMS")
        void unSeulCanal() {
            // Les deux coordonnees sont verifiees ici. Envoyer les deux
            // doublerait la facture SMS sans rien apprendre de plus : le
            // courriel porte deja les circonstances completes.
            service.motDePasseModifie(compte, contexte()).block();

            assertThat(envois).hasSize(1);
            assertThat(envois.get(0).canal()).isEqualTo("courriel");
        }

        @Test
        @DisplayName("aucune coordonnee verifiee : rien, et c'est COMPTE")
        void aucuneCoordonnee() {
            compte.setIsEmailVerified(false);
            compte.setIsPhoneVerified(false);

            StepVerifier.create(service.motDePasseModifie(compte, contexte()))
                    .verifyComplete();

            assertThat(envois).isEmpty();
            // Compte a part d'une panne : ce n'est pas un echec technique, c'est
            // un compte qu'aucune alerte ne peut atteindre. Une courbe qui monte
            // ici se corrige par la verification des contacts, pas par une
            // reprise d'envoi.
            assertThat(compteur("mot_de_passe_modifie", "sans_destinataire")).isEqualTo(1);
            assertThat(compteur("mot_de_passe_modifie", "echec")).isZero();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Changement de numero : l'ANCIEN est prevenu, par SMS d'abord")
    class ChangementDeNumero {

        private static final String ANCIEN_NUMERO = "+22374427759";
        private static final String NOUVEAU_NUMERO = "+22376543210";

        @Test
        @DisplayName("le SMS passe AVANT le courriel, a l'inverse des autres alertes")
        void smsDabord() {
            // LE test de ce bloc, et le contraste est delibere.
            //
            // L'ancien numero est la coordonnee qui disparait : c'est son
            // titulaire qui vient d'etre depossede, donc c'est lui qu'il faut
            // atteindre. Envoyer d'abord au courriel — qui, lui, n'a pas
            // change — laisserait sans information la seule personne pour qui
            // elle compte.
            //
            // L'adresse est VERIFIEE ici : si l'ordre etait celui des autres
            // alertes, le courriel serait choisi.
            compte.setPhoneNumber(NOUVEAU_NUMERO);

            StepVerifier.create(service.numeroModifie(
                            compte, ANCIEN_NUMERO, true, contexte()))
                    .verifyComplete();

            assertThat(envois).hasSize(1);
            assertThat(envois.get(0).canal()).isEqualTo("sms");
            assertThat(envois.get(0).destinataire()).isEqualTo(ANCIEN_NUMERO);
            assertThat(envois.get(0).destinataire()).isNotEqualTo(NOUVEAU_NUMERO);
        }

        @Test
        @DisplayName("un ancien numero NON verifie bascule sur le courriel")
        void repliParCourriel() {
            // Un numero jamais prouve peut n'avoir jamais existe : y envoyer un
            // SMS payant n'apprendrait rien a personne. Mais le changement doit
            // quand meme etre signale.
            compte.setPhoneNumber(NOUVEAU_NUMERO);

            service.numeroModifie(compte, ANCIEN_NUMERO, false, contexte()).block();

            assertThat(envois).hasSize(1);
            assertThat(envois.get(0).canal()).isEqualTo("courriel");
            assertThat(envois.get(0).destinataire()).isEqualTo(ANCIENNE_ADRESSE);
        }

        @Test
        @DisplayName("le texte nomme la consequence : la recuperation par SMS est perdue")
        void texteNommeLaConsequence() {
            compte.setPhoneNumber(NOUVEAU_NUMERO);
            compte.setIsPhoneVerified(false);

            service.numeroModifie(compte, ANCIEN_NUMERO, false, contexte()).block();

            // « Votre numero a change » ne dit pas ce qui compte. Le numero est
            // le canal de la reinitialisation du mot de passe : qui le remplace
            // prend le controle du compte entier sans le mot de passe. La
            // personne doit pouvoir mesurer ce qu'elle vient de perdre.
            assertThat(envois.get(0).corps())
                    .containsIgnoringCase("ancien numero")
                    .containsIgnoringCase("recuperation");
        }

        @Test
        @DisplayName("ni ancien numero verifie ni adresse verifiee : rien, et c'est compte")
        void aucuneCoordonnee() {
            compte.setPhoneNumber(NOUVEAU_NUMERO);
            compte.setIsEmailVerified(false);

            StepVerifier.create(service.numeroModifie(
                            compte, ANCIEN_NUMERO, false, contexte()))
                    .verifyComplete();

            assertThat(envois).isEmpty();
            assertThat(compteur("numero_modifie", "sans_destinataire")).isEqualTo(1);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Une panne d'envoi ne fait pas echouer l'operation")
    class PanneDenvoi {

        @Test
        @DisplayName("un SMTP en panne se termine NORMALEMENT")
        void smtpEnPanneNEchouePas() {
            smtpEnPanne = true;

            // L'operation signalee est deja enregistree : le mot de passe a
            // change. Remonter l'erreur ferait croire a la personne que rien
            // n'a bouge, et elle reessaierait avec l'ancien.
            StepVerifier.create(service.motDePasseModifie(compte, contexte()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("mais l'echec est COMPTE")
        void echecCompte() {
            smtpEnPanne = true;
            service.motDePasseModifie(compte, contexte()).block();

            // Une erreur avalee et seulement journalisee est une protection qui
            // s'eteint sans bruit. Ce compteur est ce qui rend l'extinction
            // visible : « echec » qui monte signale que les detournements de
            // compte redeviennent silencieux.
            assertThat(compteur("mot_de_passe_modifie", "echec")).isEqualTo(1);
            assertThat(compteur("mot_de_passe_modifie", "envoyee")).isZero();
        }

        @Test
        @DisplayName("aucun repli par SMS apres un echec de courriel")
        void pasDeRepliApresEchec() {
            smtpEnPanne = true;
            service.motDePasseModifie(compte, contexte()).block();

            // Le repli joue sur l'ABSENCE d'adresse verifiee, pas sur une
            // panne. Basculer en SMS a chaque incident SMTP transformerait une
            // indisponibilite en facture, sans que personne l'ait decide.
            assertThat(envois).isEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Alerte de nouvelle connexion : un reglage")
    class NouvelleConnexion {

        @Test
        @DisplayName("rien n'est envoye quand le reglage est inactif")
        void inactifNEnvoieRien() {
            compte.setAlerteNouvelleConnexion(false);

            StepVerifier.create(service.nouvelleConnexion(compte, contexte()))
                    .verifyComplete();

            assertThat(envois).isEmpty();
            // « desactivee » et non « sans_destinataire » : l'un est un choix de
            // la personne, l'autre un compte qu'aucune alerte ne protege.
            // Confondus, la courbe ne distinguerait plus un reglage d'un trou.
            assertThat(compteur("nouvelle_connexion", "desactivee")).isEqualTo(1);
            assertThat(compteur("nouvelle_connexion", "sans_destinataire")).isZero();
        }

        @Test
        @DisplayName("l'avis part quand le reglage est actif")
        void actifEnvoie() {
            compte.setAlerteNouvelleConnexion(true);

            service.nouvelleConnexion(compte, contexte()).block();

            assertThat(envois).hasSize(1);
            assertThat(envois.get(0).canal()).isEqualTo("courriel");
        }

        @Test
        @DisplayName("AUCUN repli par SMS, contrairement aux deux autres alertes")
        void pasDeRepliSms() {
            compte.setAlerteNouvelleConnexion(true);
            compte.setIsEmailVerified(false);

            service.nouvelleConnexion(compte, contexte()).block();

            // La personne a demande une alerte PAR COURRIEL, et une connexion
            // est frequente : basculer en SMS enverrait un message payant a
            // chaque connexion, pour un canal qu'elle n'a pas choisi.
            assertThat(envois).isEmpty();
            assertThat(compteur("nouvelle_connexion", "sans_destinataire")).isEqualTo(1);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Reglage de l'alerte de connexion")
    class Reglage {

        @BeforeEach
        void depotEnPlace() {
            when(userRepository.findById(anyString())).thenReturn(Mono.just(compte));
            when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
        }

        @Test
        @DisplayName("l'activation est REFUSEE sans adresse verifiee")
        void activationRefuseeSansAdresse() {
            compte.setIsEmailVerified(false);

            // Refus, et non acceptation silencieuse : un reglage affiche comme
            // actif qui n'enverrait jamais rien ferait croire a la personne
            // qu'elle est prevenue de chaque connexion — elle ne chercherait
            // donc aucun autre moyen de surveiller son compte. C'est la pire
            // des deux reponses.
            StepVerifier.create(service.changerLalerteDeConnexion("client-1", true))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(AuthException.class)
                            .hasFieldOrPropertyWithValue("errorCode",
                                    ErrorCode.ADRESSE_NON_VERIFIEE));

            assertThat(compte.getAlerteNouvelleConnexion()).isFalse();
        }

        @Test
        @DisplayName("l'activation aboutit avec une adresse verifiee")
        void activationAboutit() {
            StepVerifier.create(service.changerLalerteDeConnexion("client-1", true))
                    .expectNext(true)
                    .verifyComplete();

            assertThat(compte.getAlerteNouvelleConnexion()).isTrue();
        }

        @Test
        @DisplayName("la DESACTIVATION n'exige pas d'adresse verifiee")
        void desactivationToujoursPossible() {
            compte.setIsEmailVerified(false);
            compte.setAlerteNouvelleConnexion(true);

            // Exiger une adresse verifiee pour RETIRER un reglage enfermerait
            // la personne dans un etat qu'elle ne peut plus changer — par
            // exemple apres un changement d'adresse, qui remet le drapeau de
            // verification a faux.
            StepVerifier.create(service.changerLalerteDeConnexion("client-1", false))
                    .expectNext(false)
                    .verifyComplete();

            assertThat(compte.getAlerteNouvelleConnexion()).isFalse();
        }

        @Test
        @DisplayName("un compte inconnu est refuse")
        void compteInconnu() {
            when(userRepository.findById(anyString())).thenReturn(Mono.empty());

            StepVerifier.create(service.changerLalerteDeConnexion("inconnu", true))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(AuthException.class)
                            .hasFieldOrPropertyWithValue("errorCode",
                                    ErrorCode.USER_NOT_FOUND));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Ce que le texte contient, et ce qu'il ne contient pas")
    class Texte {

        @Test
        @DisplayName("les circonstances y sont : date, lieu, adresse, appareil")
        void circonstances() {
            service.motDePasseModifie(compte, contexte()).block();

            String corps = envois.get(0).corps();
            // Sans circonstances, l'avis n'informe pas, il inquiete : la
            // personne ne peut pas juger si c'est elle.
            assertThat(corps).contains("Bamako", "Mali", IP, USER_AGENT);
            assertThat(corps).contains("Quand");
        }

        @Test
        @DisplayName("le lieu est OMIS quand il est inconnu, et non rendu « Unknown »")
        void lieuInconnuOmis() {
            service.motDePasseModifie(compte,
                    ContexteDeSecurite.de(IP, USER_AGENT)).block();

            String corps = envois.get(0).corps();
            // « depuis Unknown » se lit comme une anomalie du systeme plutot
            // que comme une information manquante, et detourne l'attention de
            // ce que l'avis annonce.
            assertThat(corps).doesNotContain("Unknown");
            assertThat(corps).doesNotContain("Lieu");
            // Le reste est bien la : l'absence de lieu ne retient pas l'avis.
            assertThat(corps).contains(IP);
        }

        @Test
        @DisplayName("une geolocalisation VIDE est omise, et non rendue « Unknown »")
        void geoVideOmise() {
            // Cas distinct du precedent, et c'est un test de mutation qui l'a
            // revele : la, aucune geolocalisation n'etait demandee, donc le
            // court-circuit « geo == null » suffisait.
            //
            // Ici le service de geolocalisation a bien REPONDU, mais il ne
            // connait pas l'adresse — adresse privee, plage inconnue. Il rend
            // alors un objet dont getFormattedLocation() vaut « Unknown », et
            // c'est le chemin qui mettait « Lieu : Unknown » dans le courriel.
            service.motDePasseModifie(compte, ContexteDeSecurite.de(
                    IP, USER_AGENT, GeoLocationData.builder().build())).block();

            String corps = envois.get(0).corps();
            assertThat(corps).doesNotContain("Unknown");
            assertThat(corps).doesNotContain("Lieu");
            assertThat(corps).contains(IP);
        }

        @Test
        @DisplayName("la conduite a tenir y figure")
        void conduiteATenir() {
            service.motDePasseModifie(compte, contexte()).block();

            // Une alerte sans suite a donner laisse la personne inquiete et
            // sans prise.
            assertThat(envois.get(0).corps()).containsIgnoringCase("mot de passe");
        }

        @Test
        @DisplayName("AUCUN lien d'action, aucun code, aucun jeton")
        void aucunLienNiSecret() {
            service.motDePasseModifie(compte, contexte()).block();

            String corps = envois.get(0).corps();
            // Un courriel d'alerte est lu par qui a acces a la boite — et c'est
            // precisement ce dont on n'est pas sur a l'instant ou on l'envoie.
            // Un lien « ce n'etait pas moi, reinitialiser ici » offrirait a
            // l'attaquant un chemin de reprise en main.
            assertThat(corps).doesNotContain("http://", "https://");
            assertThat(corps).doesNotMatch("(?s).*\\b\\d{6}\\b.*");
        }
    }
}
