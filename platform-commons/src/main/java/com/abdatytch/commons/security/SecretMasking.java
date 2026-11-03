package com.abdatytch.commons.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Rend un justificatif citable dans un journal sans le rendre utilisable.
 *
 * <h2>Pourquoi au point d'appel, et non au niveau de l'appender</h2>
 *
 * interface-service masque déjà à l'écriture, via un {@code PatternLayout}
 * logback ({@code LogMaskingConfig$MaskingPatternLayout}). Ce filet est utile,
 * mais il a deux limites <b>mesurées</b> :
 *
 * <ol>
 *   <li>il n'existe que dans ce service. auth-service, gateway-service,
 *       user-service et media-service n'en ont <b>aucun</b> ;</li>
 *   <li>son motif exige le mot {@code token}, {@code secret} ou
 *       {@code password} <b>adjacent</b> au séparateur. Trois des quatre formes
 *       réellement présentes dans auth-service y échappent :
 *       <pre>
 *       "Rafraîchissement du token pour l'utilisateur: {}"   -> échappe
 *       "Révocation du refresh token pour l'utilisateur: {}" -> échappe
 *       "Code de vérification généré: {} pour: {}"           -> échappe
 *       </pre>
 *       La dernière est la pire : un code à usage unique ne contient aucun des
 *       mots surveillés.</li>
 * </ol>
 *
 * <p>C'est le mode d'échec déjà rencontré dans ce dépôt, quand
 * {@code gateway-service} masquait les variables d'environnement dont le nom
 * contenait {@code SECRET|PASSWORD|TOKEN} : le filtre laissait passer 153 des
 * 182 variables, dont la clé privée de signature. <b>Une liste de mots interdits
 * échoue toujours ainsi.</b> Celui qui écrit la ligne sait ce qu'il journalise ;
 * le motif, lui, devine.
 *
 * <p>Le filet reste souhaitable comme dernière barrière. Il ne remplace pas la
 * décision prise au point d'appel.
 */
public final class SecretMasking {

    /** Longueur de l'empreinte citée. Six caractères hexadécimaux suffisent à
     *  corréler des lignes entre elles sans former un identifiant durable. */
    private static final int LONGUEUR_EMPREINTE = 6;

    /**
     * Seuil d'entropie en dessous duquel <b>aucune</b> empreinte n'est publiée.
     *
     * <p>Voir {@link #masquerCodeCourt(String)} : une empreinte d'un secret
     * court est réversible par force brute.
     */
    private static final int LONGUEUR_MINIMALE_POUR_EMPREINTE = 24;

    private static final String ABSENT = "[absent]";
    private static final String MASQUE = "[masqué]";

    private SecretMasking() {
    }

    /**
     * Masque un justificatif <b>long</b> — jeton JWT, jeton de rafraîchissement,
     * clé d'API — en conservant une empreinte stable qui permet de suivre la
     * même valeur d'une ligne de journal à l'autre.
     *
     * <p>L'empreinte est le début d'un SHA-256. Sur un jeton de plusieurs
     * centaines de caractères, elle n'est pas inversible : il n'existe aucun
     * espace de candidats à parcourir. C'est ce qui la distingue du cas traité
     * par {@link #masquerCodeCourt(String)}.
     *
     * <p>Ce que l'empreinte permet, et qui justifie de ne pas tout effacer :
     * relier « validation du jeton », « jeton révoqué » et « rafraîchissement »
     * au même porteur lors d'une investigation, sans qu'aucune de ces lignes ne
     * permette de rejouer la session.
     *
     * @return {@code "[absent]"} si la valeur est nulle ou vide,
     *         {@code "[masqué]"} si elle est trop courte pour être empreintée,
     *         sinon {@code "[empr:xxxxxx]"}.
     */
    public static String masquer(String justificatif) {
        if (justificatif == null || justificatif.isBlank()) {
            return ABSENT;
        }
        if (justificatif.length() < LONGUEUR_MINIMALE_POUR_EMPREINTE) {
            // Trop court pour qu'une empreinte soit sûre : voir masquerCodeCourt.
            return MASQUE;
        }
        return "[empr:" + empreinte(justificatif) + "]";
    }

    /**
     * Masque un secret <b>court</b> — code de vérification, code TOTP, code à
     * usage unique — <b>sans aucune empreinte</b>.
     *
     * <h3>Pourquoi une empreinte serait ici une fausse protection</h3>
     *
     * Un code de vérification à six chiffres compte un million de valeurs
     * possibles. Publier {@code SHA-256(code)} dans un journal revient à
     * publier le code : un million de hachages se calculent en une fraction de
     * seconde, et la correspondance est immédiate. Le haché n'est un secret que
     * lorsque l'espace des entrées est trop grand pour être parcouru.
     *
     * <p>Ajouter un sel ne sauverait pas la méthode : le sel devrait vivre dans
     * la configuration, ce qui en fait un secret de plus à protéger pour un
     * bénéfice nul — personne n'a besoin de corréler deux lignes portant le même
     * code à usage unique.
     *
     * <p>Un code de vérification ne doit donc <b>jamais</b> figurer dans un
     * journal, sous aucune forme. Si le besoin est de savoir qu'un code a été
     * émis, journaliser l'identifiant du destinataire et l'horodatage suffit.
     */
    public static String masquerCodeCourt(String code) {
        return (code == null || code.isBlank()) ? ABSENT : MASQUE;
    }

    /**
     * Empreinte hexadécimale courte et stable d'une valeur.
     *
     * <p>Utile pour corréler sans divulguer. À réserver aux valeurs de forte
     * entropie — {@link #masquerCodeCourt(String)} explique pourquoi.
     */
    public static String empreinte(String valeur) {
        if (valeur == null) {
            return ABSENT;
        }
        try {
            byte[] condense = MessageDigest.getInstance("SHA-256")
                    .digest(valeur.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexa = new StringBuilder(LONGUEUR_EMPREINTE);
            for (int i = 0; i < LONGUEUR_EMPREINTE / 2; i++) {
                hexa.append(String.format("%02x", condense[i]));
            }
            return hexa.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 est exigé de toute implémentation Java. Si cette branche
            // est atteinte, journaliser quoi que ce soit du justificatif serait
            // le moindre des problèmes : on masque complètement.
            return MASQUE;
        }
    }
}
