package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.response.VerificationResponse;
import reactor.core.publisher.Mono;

/**
 * Confirmer son numero et son adresse apres l'inscription.
 *
 * <h2>Le trou que ce service comble</h2>
 *
 * L'inscription se fait par <b>un</b> canal : par telephone
 * ({@code /auth/phone/register-request}) ou par adresse
 * ({@code /auth/email/register}). Le canal utilise ressort confirme ; l'autre
 * reste a l'etat de simple champ, non confirme, et <b>aucun</b> parcours ne
 * permettait de le confirmer ensuite.
 *
 * <p>Consequence : une personne inscrite par telephone qui renseigne son
 * adresse a un {@code isEmailVerified} faux pour toujours. Tout ce qui depend de
 * cette colonne — une relance, une recuperation de compte par courriel — ne lui
 * sera jamais accessible, sans qu'aucune erreur l'explique.
 *
 * <h2>Deux etapes, et un code par canal</h2>
 *
 * Demander puis confirmer. Le code voyage sur le canal qu'il s'agit justement de
 * prouver : un code envoye a l'adresse prouve l'adresse, un code envoye au
 * numero prouve le numero. L'envoyer sur l'autre canal ne prouverait rien.
 *
 * <h2>Le type de code est distinct de celui de la connexion</h2>
 *
 * Voir {@code VerificationCodeType.PHONE_CONFIRMATION}. Reutiliser le type de la
 * connexion par code rendrait les deux justificatifs interchangeables : un code
 * recu pour se connecter confirmerait le contact, et inversement.
 */
public interface IConfirmationDeContactService {

    /**
     * Envoie un code a l'adresse du compte pour la confirmer.
     *
     * @param userId compte, pris du jeton et jamais d'un parametre
     * @throws com.abdatytch.auth_service.exception.AuthException si le compte n'a
     *         aucune adresse — il n'y a rien a confirmer — ou si elle est deja
     *         confirmee, auquel cas renvoyer un code ferait croire a un besoin
     *         qui n'existe pas
     */
    Mono<VerificationResponse> demanderConfirmationEmail(String userId);

    /** Confirme l'adresse si le code correspond. */
    Mono<Void> confirmerEmail(String userId, String code);

    /**
     * Envoie un code au numero du compte pour le confirmer.
     *
     * <p>Un compte inscrit par adresse seule porte un numero temporaire prefixe
     * {@code temp_}, qui n'est pas un numero joignable. La demande est alors
     * refusee : tenter l'envoi echouerait chez le fournisseur SMS, et la
     * personne lirait une panne la ou il s'agit d'un numero a renseigner
     * d'abord.
     */
    Mono<VerificationResponse> demanderConfirmationTelephone(String userId);

    /** Confirme le numero si le code correspond. */
    Mono<Void> confirmerTelephone(String userId, String code);

    /**
     * Demande le remplacement du numero : le code part au <b>NOUVEAU</b>.
     *
     * <h2>Pourquoi deux etapes</h2>
     *
     * Ecrire le nouveau numero sur simple presentation du mot de passe
     * accepterait n'importe quelle saisie. Une faute de frappe enfermerait la
     * personne hors de la recuperation par SMS — et le numero est justement ce
     * qui sert a recuperer un compte. Le code part donc au nouveau numero, et
     * lui seul prouve qu'on le detient.
     *
     * <p>Tant qu'il n'est pas confirme, le compte garde son numero actuel :
     * l'operation s'annule par l'inaction.
     *
     * <h2>Pourquoi le mot de passe actuel est exige</h2>
     *
     * Le numero n'est pas seulement un identifiant de connexion : c'est le canal
     * de {@code /auth/forgot-password}. Qui le remplace prend le controle de la
     * reinitialisation, donc du compte entier — sans jamais connaitre le mot de
     * passe. Une session seule ne peut donc pas suffire : un jeton d'acces vole
     * ouvrirait une prise de controle definitive.
     *
     * <p>L'echec est COMPTE comme un echec de connexion, pour la meme raison que
     * dans {@code changerMotDePasse} : sans frein, ce point d'entree devient un
     * oracle a eprouver les mots de passe.
     *
     * @throws com.abdatytch.auth_service.exception.AuthException
     *         {@code AUTH_1001} si le mot de passe est faux ;
     *         {@code VAL_6009} si le compte n'a aucun mot de passe a opposer —
     *         cree par OAuth2, voir la javadoc de l'implementation ;
     *         {@code AUTH_1004} si le numero demande est deja pris ;
     *         {@code VAL_6001} si c'est le numero actuel
     */
    Mono<VerificationResponse> demanderChangementDeTelephone(
            String userId, String nouveauNumero, String indicatif,
            String motDePasseActuel);

    /**
     * Confirme le remplacement, puis previent l'<b>ANCIEN</b> numero.
     *
     * <p>L'unicite est verifiee une SECONDE fois ici : un autre compte a pu
     * revendiquer ce numero entre la demande et la confirmation, et la contrainte
     * ne se verifie utilement qu'a l'instant de l'ecriture.
     *
     * @param contexte circonstances transmises a l'alerte envoyee a l'ancien
     *        numero
     * @throws com.abdatytch.auth_service.exception.AuthException
     *         {@code VAL_6001} si aucune demande n'est en cours ou si elle a
     *         expire ; {@code AUTH_1004} si le numero a ete pris entre-temps
     */
    Mono<Void> confirmerChangementDeTelephone(
            String userId, String code,
            com.abdatytch.auth_service.dto.ContexteDeSecurite contexte);
}
