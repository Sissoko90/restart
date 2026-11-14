package com.abdatytch.order_service.security;

import com.abdatytch.order_service.client.ClientMarketCatalogue;
import com.abdatytch.order_service.client.ClientProfilUtilisateur;
import com.abdatytch.order_service.exception.OrderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * La portee de l'acheteur courant.
 *
 * <h2>Le pays vient de user-service, la boutique de market-service</h2>
 *
 * Deux appels, parce que les deux informations ont deux detenteurs. Les
 * rassembler dans un seul service serait plus rapide et ferait de ce service un
 * second detenteur de donnees qu'il ne possede pas.
 *
 * <p>Les deux appels partent EN PARALLELE, par {@code Mono.zip} : ils ne
 * dependent pas l'un de l'autre, et les enchainer doublerait la latence de
 * chaque requete.
 *
 * <h2>L'identifiant vient du JETON, jamais de la requete</h2>
 *
 * Le {@code sub} du porteur. Un parametre laisserait quiconque commander,
 * consulter ou retourner au nom d'un autre — et ce serait la faille la plus
 * directe possible sur une place de marche.
 */
@Component
public class ResolveurDePortee {

    private static final Logger log = LoggerFactory.getLogger(ResolveurDePortee.class);

    private final ClientProfilUtilisateur profils;
    private final ClientMarketCatalogue marche;

    public ResolveurDePortee(ClientProfilUtilisateur profils, ClientMarketCatalogue marche) {
        this.profils = profils;
        this.marche = marche;
    }

    /**
     * La portee de l'appelant courant.
     *
     * @throws OrderException 401 si aucun jeton n'est present. Il ne devrait
     *         pas y arriver — la chaine de securite refuse d'abord — mais le
     *         verifier ici evite une {@code NullPointerException} si un chemin
     *         devenait public par erreur
     */
    public Mono<PorteeDeLAcheteur> courante() {
        return ReactiveSecurityContextHolder.getContext()
                .map(contexte -> contexte.getAuthentication())
                .flatMap(this::pour)
                .switchIfEmpty(Mono.error(() -> new OrderException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "Aucune authentification sur la requete")));
    }

    private Mono<PorteeDeLAcheteur> pour(Authentication authentification) {
        if (!(authentification instanceof
                org.springframework.security.oauth2.server.resource.authentication
                        .JwtAuthenticationToken jeton)) {
            return Mono.empty();
        }
        Jwt jwt = jeton.getToken();
        String authUserId = jwt.getSubject();
        if (authUserId == null || authUserId.isBlank()) {
            // Un jeton valide mais sans `sub` : on refuse plutot que de
            // deviner. Toute la propriete des commandes repose sur cette
            // valeur.
            return Mono.error(new OrderException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Jeton sans sujet"));
        }

        return Mono.zip(profils.pour(authUserId), marche.porteeDuCompte(authUserId))
                .map(paire -> {
                    ClientProfilUtilisateur.Profil profil = paire.getT1();
                    ClientMarketCatalogue.PorteeDuCompte compte = paire.getT2();

                    // Le pays de user-service fait foi. Celui rendu par
                    // market-service vient de la BOUTIQUE, et un vendeur peut
                    // tres bien resider ailleurs que sa boutique.
                    String pays = profil.country();
                    if (pays == null || pays.isBlank()) {
                        log.debug("Pays inconnu : aucune commande ne pourra etre creee");
                    }

                    return new PorteeDeLAcheteur(authUserId, pays,
                            compte.boutiqueId(), compte.professionnelle());
                });
    }
}
