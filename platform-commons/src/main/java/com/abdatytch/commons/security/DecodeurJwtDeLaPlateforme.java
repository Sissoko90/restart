package com.abdatytch.commons.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.util.Map;

/**
 * Le decodeur de jetons de cette plateforme.
 *
 * <h2>LE DEFAUT QU'IL CORRIGE</h2>
 *
 * Les services qui declaraient
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} refusaient TOUS
 * les jetons de la plateforme, avec « The iss claim is not valid ».
 *
 * <p>La cause tient en une ligne : {@code issuer-uri} fait valider le claim
 * {@code iss} contre cette URL, alors que l'autorite de ce depot emet
 * {@code iss: interface-service} — un NOM, pas une adresse. C'est ce que
 * declare {@code jwt.issuer} dans la configuration partagee, et ce que le
 * filtre de la passerelle verifie depuis toujours.
 *
 * <p>Mesure du 2026-11-12 : {@code GET /api/v1/notifications} rendait 401 avec
 * un jeton valide, donc la boite de reception, l'enregistrement d'appareil et
 * l'administration des modeles etaient tous injoignables. Le defaut n'avait pas
 * ete vu parce que la seule verification de bout en bout faite sur ce service
 * passait par le flux WebSocket — dont le jeton est valide par la PASSERELLE,
 * et non par le serveur de ressources du module.
 *
 * <h2>Pourquoi un decodeur partage et non une ligne de configuration</h2>
 *
 * Parce qu'il n'y a pas de ligne de configuration qui dise « valide la
 * signature par ce JWKS, et l'emetteur par ce NOM ». Sans {@code issuer-uri},
 * Spring ne verifie plus l'emetteur du tout — ce qui ferait accepter un jeton
 * correctement signe emis pour un autre usage.
 *
 * <p>Deux services en avaient besoin le jour ou ceci a ete ecrit, et un
 * troisieme en aura besoin. Trois copies d'une regle de validation de jeton,
 * c'est trois endroits ou elle peut s'assouplir sans que personne ne le voie.
 */
public final class DecodeurJwtDeLaPlateforme {

    private DecodeurJwtDeLaPlateforme() {
    }

    /**
     * Un decodeur qui verifie la signature, les horodatages et l'emetteur.
     *
     * <p>La tolerance d'horloge des horodatages reste celle de Spring —
     * soixante secondes — et c'est voulu : deux machines dont les horloges
     * different de quelques secondes se refuseraient sinon mutuellement des
     * jetons parfaitement valides.
     *
     * @param jwkSetUri l'adresse du JWKS de l'autorite
     * @param emetteur  la valeur attendue du claim {@code iss}, par exemple
     *                  {@code interface-service}
     */
    public static ReactiveJwtDecoder avecEmetteur(String jwkSetUri, String emetteur) {
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            throw new IllegalArgumentException(
                    "jwk-set-uri est obligatoire : sans lui, aucune signature ne peut "
                            + "etre verifiee.");
        }
        if (emetteur == null || emetteur.isBlank()) {
            // Un emetteur vide ferait SAUTER la verification au lieu de la
            // durcir. Mieux vaut refuser de demarrer que valider plus large
            // qu'annonce.
            throw new IllegalArgumentException(
                    "jwt.issuer est obligatoire : un emetteur vide reviendrait a "
                            + "accepter n'importe quelle autorite.");
        }

        return appliquerLesRegles(
                NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build(), emetteur);
    }

    /**
     * Applique les regles de validation a un decodeur deja construit.
     *
     * <h3>Pourquoi cette methode est PUBLIQUE</h3>
     *
     * Pour que les tests de chaine exercent les MEMES regles que la production.
     * Un test ne peut pas joindre un JWKS reel : il construit son decodeur sur
     * une cle publique engendree sur place. S'il s'arretait la, il validerait
     * la signature et rien d'autre — et c'est exactement ce qui a laisse passer
     * le defaut d'emetteur, dont la seule verification de bout en bout
     * contournait le serveur de ressources.
     *
     * @param decodeur un decodeur construit sur la source de cles voulue
     * @param emetteur la valeur attendue du claim {@code iss}
     */
    public static ReactiveJwtDecoder appliquerLesRegles(NimbusReactiveJwtDecoder decodeur,
                                                        String emetteur) {
        // `iss` RESTE UNE CHAINE, et c'est indispensable ici.
        //
        // Le convertisseur par defaut de Spring transforme `iss` en URL AU
        // DECODAGE, avant toute validation. Avec `iss: interface-service`, il
        // leve « Unable to convert claim 'iss' of type String to URL » et la
        // requete sort en 500 — pas meme en 401, donc le journal parle d'une
        // erreur interne au lieu d'un jeton refuse.
        //
        // La RFC 7519 definit `iss` comme une StringOrURI : une chaine
        // quelconque est valide, et Spring fait une hypothese plus etroite que
        // la norme. On la leve pour ce claim seulement.
        decodeur.setClaimSetConverter(MappedJwtClaimSetConverter.withDefaults(
                Map.of(JwtClaimNames.ISS, valeur -> valeur)));

        decodeur.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), emetteurExact(emetteur)));
        return decodeur;
    }

    /**
     * Compare {@code iss} a une chaine EXACTE.
     *
     * <p>{@code JwtIssuerValidator} de Spring ferait la meme chose ; il est
     * reecrit ici pour que le message d'erreur nomme la valeur attendue et la
     * valeur recue. Un « iss claim is not valid » sans plus de detail a coute
     * une demi-journee a ce depot.
     */
    static OAuth2TokenValidator<Jwt> emetteurExact(String attendu) {
        return jeton -> {
            // getClaimAsString et non getIssuer() : ce dernier rend une URL,
            // et leve sur un emetteur qui n'en est pas une.
            String recu = jeton.getClaimAsString(JwtClaimNames.ISS);
            if (attendu.equals(recu)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "Emetteur refuse : attendu « " + attendu + " », recu « " + recu + " ». "
                            + "La valeur attendue vient de `jwt.issuer` ; l'autorite de "
                            + "cette plateforme emet un NOM, pas une URL.",
                    null));
        };
    }
}
