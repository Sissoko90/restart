package com.abdatytch.order_service.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * La reference qu'un acheteur cite au support.
 *
 * <h2>Pourquoi pas l'identifiant technique</h2>
 *
 * Un UUID ne se dicte pas au telephone, et Sankadi etant l'intermediaire, il
 * recevra ces appels. {@code CMD-261114-K7M2QX} se lit, s'epelle et se
 * recherche.
 *
 * <h2>CE NUMERO N'EST PAS UN SECRET</h2>
 *
 * Aucune autorisation n'en depend. L'acces a une commande est controle par
 * l'APPARTENANCE — l'acheteur, la boutique concernee, ou un administrateur —
 * et jamais par la connaissance du numero. Le deviner ne donne donc rien.
 *
 * <p>C'est pourquoi {@code ThreadLocalRandom} suffit et que
 * {@code SecureRandom} serait un mauvais choix : il peut bloquer sur l'entropie
 * du systeme, et ce code est sur le chemin du passage en caisse. Ce depot a
 * deja paye un blocage de ce genre.
 *
 * <h2>L'alphabet exclut les caracteres ambigus</h2>
 *
 * Ni {@code O} ni {@code 0}, ni {@code I} ni {@code 1}. Un numero dicte au
 * telephone est retranscrit par un humain, et un zero pris pour un O fait
 * chercher une commande qui n'existe pas.
 */
public final class NumeroDeCommande {

    /** Sans O, 0, I, 1, L ni S : les confusions les plus frequentes a l'oral. */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRTUVWXYZ23456789".toCharArray();

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("yyMMdd");

    private static final int LONGUEUR_DU_SUFFIXE = 6;

    private NumeroDeCommande() {
    }

    /**
     * Un numero du jour.
     *
     * <p>La date en prefixe rend le numero triable et parlant pour le support.
     * Le suffixe aleatoire, et non un compteur : un compteur global exigerait
     * une sequence partagee, donc un point de contention sur le chemin du
     * passage en caisse, et il revelerait le volume de ventes de la plateforme.
     *
     * <p>L'unicite n'est PAS garantie ici mais par la contrainte
     * {@code commande_numero_unique}. Avec trente caracteres et six positions,
     * la collision est improbable ; quand elle arrive, l'insertion echoue et
     * l'appelant regenere. Se fier a la base plutot qu'a une probabilite est la
     * seule garantie qui tienne.
     */
    public static String nouveau() {
        StringBuilder suffixe = new StringBuilder(LONGUEUR_DU_SUFFIXE);
        ThreadLocalRandom hasard = ThreadLocalRandom.current();
        for (int i = 0; i < LONGUEUR_DU_SUFFIXE; i++) {
            suffixe.append(ALPHABET[hasard.nextInt(ALPHABET.length)]);
        }
        return "CMD-" + LocalDate.now().format(JOUR) + "-" + suffixe;
    }
}
