package com.abdatytch.market_service.security;

import com.abdatytch.market_service.cache.CacheDePortee;
import com.abdatytch.market_service.client.ClientProfilUtilisateur;
import com.abdatytch.market_service.entities.Boutique;
import com.abdatytch.market_service.repository.BoutiqueRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Construit la {@link PorteeDuCatalogue} du demandeur.
 *
 * <h2>Trois sources, et aucune n'est la requete</h2>
 *
 * <ul>
 *   <li>le JETON donne l'identite et le role d'administration ;</li>
 *   <li>la BASE donne la boutique, donc la qualite de professionnel ;</li>
 *   <li>user-service donne le PAYS.</li>
 * </ul>
 *
 * <p>Rien ne vient du corps ni des parametres de la requete. C'est la propriete
 * qui fait tenir tout l'edifice : un particulier ne peut pas se declarer
 * professionnel, ni se declarer d'un autre pays.
 *
 * <h2>L'administrateur ne paie pas les deux appels</h2>
 *
 * Il voit tout : ni sa boutique ni son pays n'entrent dans la decision. Les
 * resoudre quand meme ferait deux allers-retours pour une portee qui les
 * ignore — et ferait dependre la file de moderation de la sante de
 * user-service.
 */
@Component
public class ResolveurDePortee {

    /** Le role d'administration, tel que la chaine de securite le nomme. */
    static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final BoutiqueRepository boutiques;
    private final ClientProfilUtilisateur profils;
    private final CacheDePortee cache;

    public ResolveurDePortee(BoutiqueRepository boutiques,
                             ClientProfilUtilisateur profils,
                             CacheDePortee cache) {
        this.boutiques = boutiques;
        this.profils = profils;
        this.cache = cache;
    }

    /** La portee du porteur courant. */
    public Mono<PorteeDuCatalogue> courante() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .flatMap(this::pour)
                // La chaine exige `authenticated()` partout : un contexte vide
                // ne devrait pas arriver. S'il arrive, la portee est VIDE —
                // aucun pays, aucune boutique — donc aucun produit visible.
                // Jamais une portee par defaut qui montrerait quelque chose.
                .switchIfEmpty(Mono.just(
                        new PorteeDuCatalogue(null, null, null, false, false)));
    }

    private Mono<PorteeDuCatalogue> pour(Authentication authentification) {
        String authUserId = authentification.getName();
        boolean administrateur = authentification.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ROLE_ADMIN::equals);

        if (administrateur) {
            return Mono.just(PorteeDuCatalogue.administrateur(authUserId));
        }

        return cache.lire(authUserId, false)
                .switchIfEmpty(Mono.defer(() -> calculer(authUserId))
                        .flatMap(portee -> cache.ecrire(portee).thenReturn(portee)));
    }

    /**
     * Les deux appels sortants, EN PARALLELE.
     *
     * <p>Ils sont independants : la boutique vient de la base locale, le pays
     * de user-service. Les enchainer ajouterait le delai de l'un a celui de
     * l'autre sur le chemin de chaque requete non mise en cache.
     *
     * <h3>{@code boutiqueId} est pose des qu'une boutique EXISTE</h3>
     *
     * Pas seulement quand elle est approuvee, et la distinction est le coeur du
     * cycle de validation : un vendeur dont la boutique est encore en attente
     * doit voir SES produits pour les corriger. C'est la deuxieme branche de la
     * clause SQL. {@code professionnel}, lui, n'est vrai qu'une fois la
     * boutique approuvee — c'est lui qui ouvre le catalogue des autres pays et
     * les prix de gros.
     *
     * <h3>{@code Optional} et non un {@code null} dans le zip</h3>
     *
     * {@code Mono.zip} abandonne des qu'une de ses sources est vide, et ne
     * transporte aucun nul. Sans cette enveloppe, un compte SANS boutique —
     * le cas le plus courant — produirait un {@code Mono} vide, donc une
     * portee absente, donc un catalogue vide pour tous les particuliers.
     */
    private Mono<PorteeDuCatalogue> calculer(String authUserId) {
        Mono<Optional<Boutique>> boutique = boutiques.findByAuthUserId(authUserId)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());

        return Mono.zip(boutique, profils.pour(authUserId))
                .map(paire -> {
                    Boutique sienne = paire.getT1().orElse(null);
                    return new PorteeDuCatalogue(
                            authUserId,
                            paire.getT2().pays(),
                            sienne == null ? null : sienne.getId(),
                            sienne != null && sienne.estProfessionnelle(),
                            false);
                });
    }
}
