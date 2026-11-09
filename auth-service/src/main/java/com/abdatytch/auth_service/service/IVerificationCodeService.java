package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.entities.VerificationCode;
import com.abdatytch.auth_service.enums.VerificationCodeType;
import reactor.core.publisher.Mono;

/**
 * Codes de vérification à usage unique (OTP), par SMS ou email.
 */
public interface IVerificationCodeService {

    /**
     * Ce qui est REELLEMENT parti, par canal.
     *
     * <h2>Pourquoi ce type remplace {@code VerificationCode} en retour</h2>
     *
     * Les deux surcharges rendaient l'entite enregistree, et <b>aucun</b> de
     * leurs huit appelants ne s'en servait : tous faisaient {@code .thenReturn}
     * ou {@code .then}, donc jetaient la valeur. Pour relire un code, il y a
     * {@link #getLatestCode}.
     *
     * <p>Ce qu'ils voulaient, en revanche, c'est savoir si le code est parti —
     * et ils le devinaient depuis un interrupteur de configuration qui ne
     * connait qu'<b>une</b> des quatre raisons de ne rien envoyer. Les trois
     * autres — numero hors liste autorisee, plafond journalier atteint,
     * fournisseur mal configure — passaient pour des succes, et l'API repondait
     * « Code OTP envoye par SMS » pour un message qui n'arriverait jamais.
     *
     * <h2>Trois etats par canal, et non deux</h2>
     *
     * {@code null} signifie « canal non tente » : une inscription par adresse
     * seule n'essaie aucun SMS, et le compter comme un echec ferait croire a une
     * panne. {@code FALSE} signifie « tente et refuse ».
     *
     * @param parSms      {@code null} si aucun SMS n'a ete tente
     * @param parCourriel {@code null} si aucun courriel n'a ete tente
     */
    record EnvoiDuCode(Boolean parSms, Boolean parCourriel) {

        public static EnvoiDuCode parSmsSeul(boolean parti) {
            return new EnvoiDuCode(parti, null);
        }

        public static EnvoiDuCode parCourrielSeul(boolean parti) {
            return new EnvoiDuCode(null, parti);
        }

        public boolean smsAccepte() {
            return Boolean.TRUE.equals(parSms);
        }

        public boolean courrielAccepte() {
            return Boolean.TRUE.equals(parCourriel);
        }

        /**
         * Le message a rendre : celui promis, ou l'aveu que rien n'est parti.
         *
         * <h3>Pourquoi la regle est ICI et non a chaque site d'appel</h3>
         *
         * Sept endpoints annoncent l'envoi d'un code. La regle « si ce n'est pas
         * parti, le dire » y serait recopiee sept fois, et une correction
         * appliquee a six d'entre eux laisserait le septieme mentir — exactement
         * le defaut que ce depot vient de fermer sur les textes d'alerte, en
         * verifiant que les quatre portent la meme conduite a tenir.
         *
         * <p>Le type qui connait le resultat porte donc la regle ; le site
         * d'appel ne fournit que le message du cas nominal, qu'il est seul a
         * connaitre.
         *
         * @param canalAccepte    le canal que CET endpoint promet
         * @param messageSiEnvoye ce qu'on repond quand le canal a accepte
         */
        public static String message(boolean canalAccepte, String messageSiEnvoye) {
            return canalAccepte ? messageSiEnvoye : AppConstants.OTP_GENERE_SANS_ENVOI;
        }
    }

    Mono<EnvoiDuCode> generateAndSendCode(String phoneNumber, String email, VerificationCodeType type);

    Mono<EnvoiDuCode> generateAndSendCode(String phoneNumber, String email, VerificationCodeType type,
                                          String firstName, String lastName, String countryCode);

    /**
     * Valide un code par téléphone et le marque comme utilisé.
     * Émet une {@code AuthException} si le code est inconnu, déjà utilisé,
     * expiré, ou si le nombre de tentatives est dépassé.
     */
    Mono<Void> validateCode(String phoneNumber, String code, VerificationCodeType type);

    /** Idem, par adresse email. */
    Mono<Void> validateCodeByEmail(String email, String code, VerificationCodeType type);

    Mono<Void> cleanupExpiredCodes();

    Mono<Void> incrementAttempts(String phoneNumber, VerificationCodeType type);

    /**
     * Dernier code émis pour ce couple téléphone/type.
     * Mono vide si aucun code n'existe (l'absence n'est pas une erreur).
     */
    Mono<VerificationCode> getLatestCode(String phoneNumber, VerificationCodeType type);
}
