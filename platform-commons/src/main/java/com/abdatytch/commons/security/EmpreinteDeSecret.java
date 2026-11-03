package com.abdatytch.commons.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Fabrication d'un secret porteur, et de l'empreinte sous laquelle on le stocke.
 *
 * <h2>Le defaut que cette classe existe pour fermer</h2>
 *
 * Un jeton de rafraichissement est un <b>justificatif de longue duree</b> : le
 * presenter suffit a obtenir un jeton d'acces neuf, indefiniment, sans mot de
 * passe et sans second facteur. Le stocker en clair revient a garder une
 * colonne de mots de passe en clair : une sauvegarde egaree, un export, une
 * lecture imprevue, et chaque ligne est une session utilisable immediatement.
 *
 * <p>Ce depot masquait deja ces jetons dans les journaux — voir
 * {@link SecretMasking} — tout en les ecrivant en clair en base. La precaution
 * la plus visible etait prise, la plus importante ne l'etait pas.
 *
 * <h2>Pourquoi SHA-256 et non bcrypt ou Argon2</h2>
 *
 * Parce qu'un secret porteur n'est pas un mot de passe. Un mot de passe est
 * choisi par une personne, donc devinable : il faut rendre chaque essai couteux.
 * Le secret produit ici est tire de {@link SecureRandom} sur <b>256 bits</b> :
 * il n'existe ni dictionnaire ni liste a essayer, et une fonction lente
 * n'ajouterait aucune resistance.
 *
 * <p>Elle couterait en revanche sa latence a <b>chaque rafraichissement</b>, sur
 * le chemin d'une requete, dans un service qui a pour regle de ne jamais
 * bloquer. Le choix n'est donc pas un compromis : c'est le bon outil pour la
 * bonne menace.
 *
 * <h2>Ce que l'empreinte ne protege pas</h2>
 *
 * Elle protege la <b>base</b>, pas le transport ni le client. Un secret
 * intercepte reste utilisable : c'est le role de TLS, de la revocation et de
 * l'echeance. Elle garantit seulement qu'une personne qui lit la table ne
 * repart pas avec des sessions pretes a l'emploi.
 */
public final class EmpreinteDeSecret {

    private EmpreinteDeSecret() {
        throw new UnsupportedOperationException("Cette classe ne peut pas etre instanciee");
    }

    /**
     * Longueur du secret, en octets.
     *
     * <p>256 bits. Un {@code UUID.randomUUID()} — ce que ce depot employait —
     * n'en porte que 122 : six bits sont figes par la version et la variante.
     * C'est suffisant en pratique, mais rien ne justifie de s'en contenter quand
     * la valeur n'est jamais saisie par un humain et que sa longueur est sans
     * consequence.
     */
    private static final int OCTETS_DU_SECRET = 32;

    /**
     * Une seule instance, partagee.
     *
     * <p>{@link SecureRandom} est sur pour l'usage concurrent et se reseme
     * lui-meme. En construire une par appel, en revanche, peut couter une
     * initialisation complete et, sur certaines plateformes, <b>bloquer</b> en
     * attendant l'entropie du systeme — un appel bloquant sur le chemin d'une
     * connexion.
     */
    private static final SecureRandom ALEA = new SecureRandom();

    /** Nom de l'algorithme d'empreinte. */
    private static final String ALGORITHME = "SHA-256";

    /**
     * Tire un nouveau secret porteur.
     *
     * <p>Encode en Base64 <b>URL</b> sans remplissage : la valeur circule dans
     * des corps JSON et peut se retrouver dans un parametre, et les caracteres
     * {@code +}, {@code /} et {@code =} du Base64 ordinaire y changent de sens.
     *
     * @return le secret en clair — a remettre a l'appelant, et a ne jamais
     *         ecrire en base ni dans un journal
     */
    public static String nouveauSecret() {
        byte[] graine = new byte[OCTETS_DU_SECRET];
        ALEA.nextBytes(graine);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(graine);
    }

    /**
     * Empreinte sous laquelle un secret est stocke et retrouve.
     *
     * <p>Deterministe, et c'est <b>indispensable</b> ici : le secret presente
     * doit permettre de retrouver la ligne par une recherche indexee. Une
     * fonction a sel — bcrypt, Argon2 — produirait une empreinte differente a
     * chaque appel et obligerait a parcourir toute la table en comparant ligne
     * par ligne. C'est la seconde raison, apres le cout, de ne pas les employer
     * pour un secret porteur.
     *
     * @param secret le secret en clair
     * @return l'empreinte en hexadecimal minuscule, 64 caracteres
     * @throws IllegalArgumentException si le secret est nul ou vide — une
     *         empreinte de valeur vide est une empreinte valide, et la stocker
     *         ferait d'une absence de jeton un jeton acceptable
     */
    public static String empreinte(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException(
                    "Le secret est requis : une empreinte de valeur vide serait acceptee "
                            + "comme un jeton");
        }
        try {
            // Une instance par appel : MessageDigest n'est PAS sur pour l'usage
            // concurrent, et une instance partagee melangerait les empreintes de
            // deux requetes simultanees.
            MessageDigest condensat = MessageDigest.getInstance(ALGORITHME);
            byte[] empreinte = condensat.digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(empreinte);
        } catch (NoSuchAlgorithmException absent) {
            // SHA-256 est exige de toute implementation Java. Cette branche ne
            // peut pas s'executer ; la laisser avaler l'erreur ferait passer un
            // jeton non verifie.
            throw new IllegalStateException(ALGORITHME + " est absent de cette plateforme",
                    absent);
        }
    }
}
