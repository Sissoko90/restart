package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.enums.OAuthProvider;
import com.abdatytch.auth_service.exception.AuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Le seul endroit qui choisisse un fournisseur OAuth2.
 *
 * <h2>Ce que ce registre remplace</h2>
 *
 * {@code OAuth2ServiceImpl} branchait sur le fournisseur en plusieurs endroits
 * independants : un {@code if/else} pour l'URL d'autorisation, un
 * {@code switch} pour l'echange de code, et un ternaire — <b>recopie trois
 * fois</b> — pour le type d'evenement journalise. Ajouter Apple aurait demande
 * de retrouver et modifier chacun de ces endroits, en sachant qu'un seul oubli
 * ne produit aucune erreur : il produit un comportement faux et silencieux.
 *
 * <p>Le ternaire le montrait bien. Ecrit
 * {@code provider == GOOGLE ? GOOGLE_LOGIN : FACEBOOK_LOGIN}, il attribuait a
 * Facebook <b>tout</b> fournisseur qui n'etait pas Google. Une connexion Apple
 * aurait ete journalisee, comptee et alertee comme une connexion Facebook, sans
 * la moindre erreur visible. Cette correspondance vit maintenant sur
 * {@link OAuthProvider#getEvenementDeConnexion()}, ou elle est exhaustive par
 * construction.
 *
 * <h2>Pourquoi une recherche et non un switch</h2>
 *
 * Un {@code switch} se compile meme s'il oublie une valeur, et sa branche
 * {@code default} absorbe l'oubli. Ici, un fournisseur sans implementation est
 * refuse explicitement, et ajouter une implementation ne demande de toucher
 * <b>aucun</b> appelant : le bean suffit.
 */
@Slf4j
@Component
public class RegistreDesFournisseursOAuth2 {

    private final Map<OAuthProvider, IFournisseurOAuth2> parFournisseur =
            new EnumMap<>(OAuthProvider.class);

    public RegistreDesFournisseursOAuth2(List<IFournisseurOAuth2> fournisseurs) {
        for (IFournisseurOAuth2 fournisseur : fournisseurs) {
            IFournisseurOAuth2 precedent =
                    parFournisseur.put(fournisseur.fournisseur(), fournisseur);
            if (precedent != null) {
                // Deux implementations pour un meme fournisseur : l'une
                // masquerait l'autre selon l'ordre des beans, donc selon
                // l'ordre de scan. C'est le genre de dependance invisible qui
                // fait qu'un comportement change sans qu'aucun code change.
                throw new IllegalStateException(
                        "Deux implementations declarees pour le fournisseur "
                                + fournisseur.fournisseur());
            }
        }
        // L'inventaire est journalise au demarrage, avec l'etat de
        // configuration de chacun. Sans cette ligne, « Apple ne marche pas »
        // demande d'aller lire la configuration pour savoir si le fournisseur
        // est seulement actif.
        parFournisseur.values().forEach(f -> log.info(
                "Fournisseur OAuth2 {} present, configure : {}",
                f.fournisseur().getProviderName(), f.estConfigure()));
    }

    /**
     * Fournisseur utilisable pour cette valeur.
     *
     * @throws AuthException si aucun fournisseur ne la sert, ou s'il n'est pas
     *         configure sur cette installation. Les deux cas rendent le
     *         <b>meme</b> code : du point de vue de l'appelant, un fournisseur
     *         que nous n'offrons pas et un fournisseur que nous n'avons pas
     *         configure sont indiscernables, et les distinguer renseignerait un
     *         appelant anonyme sur l'etat de notre configuration.
     */
    public IFournisseurOAuth2 pour(OAuthProvider fournisseur) {
        IFournisseurOAuth2 trouve = parFournisseur.get(fournisseur);
        if (trouve == null) {
            log.warn("Aucune implementation pour le fournisseur OAuth2 {}", fournisseur);
            throw new AuthException(ErrorCode.OAUTH2_PROVIDER_NOT_CONFIGURED);
        }
        if (!trouve.estConfigure()) {
            log.warn("Fournisseur OAuth2 {} demande mais non configure",
                    fournisseur.getProviderName());
            throw new AuthException(ErrorCode.OAUTH2_PROVIDER_NOT_CONFIGURED);
        }
        return trouve;
    }

    /**
     * Fournisseur par sa valeur, <b>sans</b> exiger qu'il soit configure.
     *
     * <h2>Pourquoi cette porte existe a cote de {@link #pour}</h2>
     *
     * {@link #pour} refuse un fournisseur non configure, et c'est juste pour
     * engager un parcours : inutile d'envoyer quelqu'un vers Apple si Apple n'a
     * pas d'identifiants.
     *
     * <p>Mais certaines operations portent sur des liaisons <b>deja faites</b>.
     * Un compte lie du temps ou Apple etait configure doit pouvoir etre delie
     * apres sa desactivation — sinon desactiver un fournisseur emprisonne les
     * liaisons existantes, et la personne ne peut plus retirer de son compte un
     * fournisseur qu'elle n'utilise plus.
     *
     * <p>Rend {@code null} plutot qu'une erreur : l'appelant decide. Dans le cas
     * de la deliaison, un fournisseur introuvable signifie seulement qu'il n'y a
     * personne a prevenir, ce qui n'empeche pas de delier.
     */
    public IFournisseurOAuth2 sansVerifierLaConfiguration(OAuthProvider fournisseur) {
        return parFournisseur.get(fournisseur);
    }

    /** Fournisseurs reellement utilisables, pour le diagnostic. */
    public List<OAuthProvider> fournisseursUtilisables() {
        return parFournisseur.values().stream()
                .filter(IFournisseurOAuth2::estConfigure)
                .map(IFournisseurOAuth2::fournisseur)
                .toList();
    }
}
