package com.abdatytch.auth_service.security.webauthn;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.authenticator.Authenticator;
import com.webauthn4j.authenticator.AuthenticatorImpl;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Verification WebAuthn reelle, deleguee a {@code webauthn4j}.
 *
 * <h2>Ce que cette classe remplace</h2>
 *
 * La verification precedente etait un <b>placeholder</b> :
 *
 * <pre>
 * Base64(publicKey + authenticatorData + clientDataJSON + challenge)
 * </pre>
 *
 * comparee a la signature fournie. L'appelant fournissant
 * {@code authenticatorData}, {@code clientDataJSON}, {@code challenge} <b>et</b>
 * {@code signature}, il ne lui manquait que {@code publicKey} — qui n'est pas un
 * secret par construction. Quiconque connaissait le {@code credentialId} et la
 * cle publique d'une personne obtenait un jeton a sa place.
 *
 * <h2>Pourquoi deleguer plutot qu'implementer</h2>
 *
 * Une assertion WebAuthn se verifie en signant
 * {@code authenticatorData || SHA-256(clientDataJSON)} avec la cle privee de
 * l'authentificateur. Mais la verification complete comporte bien davantage :
 * concordance du {@code rpIdHash}, du {@code type} et de l'{@code origin} dans
 * les donnees client, controle du drapeau de presence utilisateur, progression
 * du compteur, formats COSE des cles, et une demi-douzaine de regles de la
 * specification. Chacune omise est une faille.
 *
 * <p>{@code webauthn4j} est maintenu et audite ; reimplementer ces regles a la
 * main serait reintroduire le probleme sous une autre forme. Cette classe ne
 * contient donc aucune cryptographie : elle traduit les donnees du depot vers
 * les types de la bibliotheque, et rien d'autre.
 *
 * <h2>Ce que le client ne decide plus</h2>
 *
 * <ul>
 *   <li><b>La cle publique</b> n'est plus prise dans la requete : elle est
 *       extraite de l'attestation verifiee. Accepter une cle publique de la main
 *       du client revient a lui laisser choisir la serrure ;</li>
 *   <li><b>Le challenge</b> n'est plus pris dans la requete : il est lu dans le
 *       depot ou le serveur l'a ecrit, et {@code webauthn4j} verifie qu'il
 *       correspond a celui que l'authentificateur a signe, a l'interieur de
 *       {@code clientDataJSON}. L'ancien code comparait le challenge du corps a
 *       celui du depot, ce qui ne prouve rien : les deux viennent de l'appelant
 *       pour le premier, et le second n'etait meme pas stocke a
 *       l'authentification ;</li>
 *   <li><b>Le compteur</b> est verifie par la bibliotheque : un compteur qui ne
 *       progresse pas revele un authentificateur clone.</li>
 * </ul>
 *
 * <h2>Consequence sur les donnees existantes</h2>
 *
 * Les credentials deja enregistrees portent une cle publique fournie par le
 * client, inexploitable par une verification reelle. <b>Elles devront etre
 * re-enregistrees.</b> Ce n'est pas un effet de bord regrettable : ces
 * credentials n'ont jamais rien prouve.
 */
@Component
@Slf4j
public class WebAuthnVerifier {

    /**
     * Identifiant de la partie dependante — le domaine, sans schema ni port.
     *
     * <p>L'authentificateur signe le hachage de cette valeur. Une erreur ici
     * fait echouer toute verification, ce qui est le bon sens de l'echec : une
     * valeur trop permissive permettrait a un autre domaine de rejouer des
     * assertions.
     */
    @Value("${webauthn.rp-id}")
    private String rpId;

    /**
     * Origine attendue dans {@code clientDataJSON}, schema et port compris.
     *
     * <p>C'est ce qui empeche un site tiers de collecter une assertion chez lui
     * et de la presenter ici.
     */
    @Value("${webauthn.origin}")
    private String origin;

    private final WebAuthnManager manager = WebAuthnManager.createNonStrictWebAuthnManager();
    private final AttestedCredentialDataConverter credentialDataConverter =
            new AttestedCredentialDataConverter(new ObjectConverter());

    /**
     * Verifie une attestation d'enregistrement et rend le materiel a conserver.
     *
     * @param attestationObject attestation, en base64url telle que le navigateur la produit
     * @param clientDataJSON    donnees client, en base64url
     * @param challengeAttendu  challenge ecrit par le serveur, en base64url
     * @throws WebAuthnVerificationException si l'attestation n'est pas valide
     */
    public ResultatEnregistrement verifierEnregistrement(String attestationObject,
                                                         String clientDataJSON,
                                                         String challengeAttendu) {
        try {
            RegistrationRequest requete = new RegistrationRequest(
                    decoder(attestationObject), decoder(clientDataJSON));

            // userVerificationRequired a faux, userPresenceRequired a vrai :
            // l'empreinte ou le visage valent presence, et exiger la
            // verification locale refuserait les authentificateurs qui ne la
            // proposent pas. C'est un choix, pas un oubli.
            RegistrationParameters parametres = new RegistrationParameters(
                    serveur(challengeAttendu), false, true);

            RegistrationData donnees = manager.validate(requete, parametres);

            var authenticatorData = donnees.getAttestationObject().getAuthenticatorData();
            AttestedCredentialData credentialData = authenticatorData.getAttestedCredentialData();

            return new ResultatEnregistrement(
                    encoder(credentialData.getCredentialId()),
                    encoder(credentialDataConverter.convert(credentialData)),
                    authenticatorData.getSignCount());
        } catch (WebAuthnVerificationException e) {
            throw e;
        } catch (Exception e) {
            // Le detail n'est pas renvoye a l'appelant : il renseignerait sur ce
            // qui a echoue, donc sur ce qu'il faut ajuster a l'essai suivant.
            log.warn("Attestation WebAuthn refusee : {}", e.getMessage());
            throw new WebAuthnVerificationException("Attestation biometrique invalide", e);
        }
    }

    /**
     * Verifie une assertion d'authentification.
     *
     * @param donneesCredentialStockees {@code AttestedCredentialData} serialisee, en base64url
     * @param compteurStocke            dernier compteur connu pour cette credential
     * @return le nouveau compteur, a enregistrer
     * @throws WebAuthnVerificationException si l'assertion n'est pas valide
     */
    public long verifierAssertion(String donneesCredentialStockees,
                                  long compteurStocke,
                                  String credentialId,
                                  String userHandle,
                                  String authenticatorData,
                                  String clientDataJSON,
                                  String signature,
                                  String challengeAttendu) {
        try {
            AttestedCredentialData credentialData =
                    credentialDataConverter.convert(decoder(donneesCredentialStockees));

            Authenticator authenticator = new AuthenticatorImpl(
                    credentialData, null, compteurStocke);

            AuthenticationRequest requete = new AuthenticationRequest(
                    decoder(credentialId),
                    userHandle == null ? null : decoder(userHandle),
                    decoder(authenticatorData),
                    decoder(clientDataJSON),
                    null,
                    decoder(signature));

            AuthenticationParameters parametres = new AuthenticationParameters(
                    serveur(challengeAttendu), authenticator, null, false, true);

            AuthenticationData donnees = manager.validate(requete, parametres);

            // webauthn4j refuse un compteur qui ne progresse pas. Le nouveau est
            // rendu pour etre persiste : sans cela, la protection contre le
            // clonage ne tiendrait qu'une requete.
            return donnees.getAuthenticatorData().getSignCount();
        } catch (Exception e) {
            log.warn("Assertion WebAuthn refusee : {}", e.getMessage());
            throw new WebAuthnVerificationException("Verification biometrique echouee", e);
        }
    }

    private ServerProperty serveur(String challengeBase64Url) {
        return new ServerProperty(
                new Origin(origin),
                rpId,
                new DefaultChallenge(decoder(challengeBase64Url)),
                null);
    }

    /** WebAuthn emploie base64url sans remplissage, et non le Base64 standard. */
    private static byte[] decoder(String valeur) {
        return Base64.getUrlDecoder().decode(valeur);
    }

    private static String encoder(byte[] valeur) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(valeur);
    }

    /** Materiel issu d'un enregistrement verifie. */
    public record ResultatEnregistrement(String credentialId,
                                         String donneesCredential,
                                         long compteurInitial) {
    }
}
