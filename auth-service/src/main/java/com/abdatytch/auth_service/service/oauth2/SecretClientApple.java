package com.abdatytch.auth_service.service.oauth2;

import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Secret client d'Apple : un JWT signe, pas une chaine.
 *
 * <h2>Ce qui distingue Apple de Google et de Facebook</h2>
 *
 * Les deux autres fournissent une chaine fixe que l'on colle dans la
 * configuration. Apple n'en donne aucune : il faut <b>produire</b> un JWT signe
 * en <b>ES256</b> avec la cle privee {@code .p8} telechargee depuis la console,
 * portant :
 *
 * <pre>
 * iss = Team ID        (l'equipe de developpement)
 * sub = Services ID    (ce qui joue le role du client_id cote web)
 * aud = https://appleid.apple.com
 * exp = au plus 6 mois apres iat
 * </pre>
 *
 * <p>C'est l'essentiel du travail d'integration, et la raison pour laquelle
 * Apple ne pouvait pas etre une huitieme branche {@code if} dans le service
 * existant.
 *
 * <h2>Pourquoi un cache avec renouvellement, et pas une generation par appel</h2>
 *
 * Signer en ES256 est du calcul, et le faire a chaque echange de code placerait
 * ce calcul sur le chemin de connexion — le defaut mesure a 241 ms avec BCrypt
 * a rendu cette lecon concrete. Le secret est donc produit une fois et reutilise
 * jusqu'a l'approche de son echeance.
 *
 * <p>La marge de renouvellement n'est pas cosmetique : un secret qui expire
 * <b>pendant</b> un echange de code fait echouer la connexion avec une erreur
 * {@code invalid_client} du cote d'Apple, c'est-a-dire un message qui ne
 * designe pas la cause. On renouvelle donc largement avant.
 *
 * <h2>Ce qui n'est jamais journalise</h2>
 *
 * Ni la cle privee, ni le secret produit. Le secret est un justificatif complet
 * : qui l'obtient peut echanger des codes d'autorisation au nom de la
 * plateforme jusqu'a son echeance. Seuls les identifiants publics — Team ID,
 * Key ID — peuvent apparaitre dans un journal.
 */
@Slf4j
public class SecretClientApple {

    /** Audience imposee par Apple. */
    static final String AUDIENCE = "https://appleid.apple.com";

    /**
     * Duree de validite demandee.
     *
     * <p>Apple refuse au-dela de six mois. On reste nettement en dessous : un
     * secret de longue duree est un justificatif de longue duree, et le
     * renouveler coute une signature.
     */
    static final Duration VALIDITE = Duration.ofDays(30);

    /**
     * Marge avant echeance a partir de laquelle on reproduit le secret.
     *
     * <p>Genereuse a dessein : un secret qui expire pendant un echange de code
     * produit un {@code invalid_client} chez Apple, qui ne designe pas la
     * cause. Mieux vaut renouveler trop tot que trop tard.
     */
    static final Duration MARGE_DE_RENOUVELLEMENT = Duration.ofDays(1);

    private final String teamId;
    private final String servicesId;
    private final String keyId;
    private final PrivateKey clePrivee;

    /** Secret courant et son echeance, remplaces ensemble. */
    private final AtomicReference<SecretDate> courant = new AtomicReference<>();

    private record SecretDate(String valeur, Instant echeance) {
    }

    /**
     * @param clePriveePkcs8 contenu du {@code .p8}, avec ou sans les lignes
     *        {@code -----BEGIN PRIVATE KEY-----}
     * @throws IllegalArgumentException si la cle est illisible — a la
     *         construction, et non au premier echange de code : une cle mal
     *         copiee doit se voir au demarrage du fournisseur, pas a la
     *         premiere connexion d'un utilisateur
     */
    public SecretClientApple(String teamId, String servicesId, String keyId,
                             String clePriveePkcs8) {
        this.teamId = teamId;
        this.servicesId = servicesId;
        this.keyId = keyId;
        this.clePrivee = lireClePrivee(clePriveePkcs8);
    }

    /**
     * Rend le secret courant, en le reproduisant si son echeance approche.
     *
     * <p>Le remplacement est fait par {@code compareAndSet} : deux connexions
     * simultanees peuvent produire deux secrets, l'un des deux est retenu, et
     * les deux sont valides. Un verrou serait du blocage sur le chemin de
     * connexion pour economiser une signature tous les trente jours.
     */
    public String valeur() {
        SecretDate actuel = courant.get();
        if (actuel != null && Instant.now().isBefore(
                actuel.echeance().minus(MARGE_DE_RENOUVELLEMENT))) {
            return actuel.valeur();
        }

        Instant emission = Instant.now();
        Instant echeance = emission.plus(VALIDITE);
        String secret = Jwts.builder()
                .header().keyId(keyId).and()
                .issuer(teamId)
                .subject(servicesId)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(emission))
                .expiration(Date.from(echeance))
                .signWith(clePrivee, Jwts.SIG.ES256)
                .compact();

        courant.compareAndSet(actuel, new SecretDate(secret, echeance));
        // Ni la cle ni le secret : seuls les identifiants publics.
        log.info("Secret client Apple renouvele (team {}, cle {}), echeance {}",
                teamId, keyId, echeance);
        return secret;
    }

    /**
     * Lit une cle EC au format PKCS#8, telle qu'Apple la livre.
     *
     * <p>Les en-tetes PEM et les retours a la ligne sont retires : un fichier
     * {@code .p8} colle dans une variable d'environnement arrive presque
     * toujours avec eux, parfois avec des {@code \\n} litteraux.
     */
    private static PrivateKey lireClePrivee(String pkcs8) {
        if (pkcs8 == null || pkcs8.isBlank()) {
            throw new IllegalArgumentException("Cle privee Apple absente");
        }
        String base64 = pkcs8
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replaceAll("\\s", "");
        try {
            byte[] octets = Base64.getDecoder().decode(base64.getBytes(StandardCharsets.UTF_8));
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(octets));
        } catch (Exception e) {
            // Le message ne reprend NI la cle ni un fragment : une cle mal
            // copiee reste un secret. Seule la classe d'erreur est utile.
            throw new IllegalArgumentException(
                    "Cle privee Apple illisible (" + e.getClass().getSimpleName()
                            + ") : attendu une cle EC au format PKCS#8, telle que le "
                            + "fichier .p8 d'Apple");
        }
    }
}
