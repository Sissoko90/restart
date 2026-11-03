package com.abdatytch.commons.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture d'une adresse IP <b>litterale</b>, sans jamais resoudre de nom.
 *
 * <h2>Pourquoi cette classe vit dans platform-commons</h2>
 *
 * Elle est le contrat entre qui <b>produit</b> une adresse et qui l'<b>ecrit</b>
 * comme cle. {@code ClientIpResolver}, dans le gateway, produit la valeur qui
 * sert de cle Redis ; {@code RegleDIpServiceImpl}, dans security-service, ecrit
 * la cle que le gateway interrogera. Les deux doivent s'accorder sur la forme
 * <b>textuelle exacte</b> d'une adresse, sans quoi une regle posee sur
 * {@code 041.223.010.005} n'arretera jamais un client annonce
 * {@code 41.223.10.5} — et le blocage serait confirme a son auteur sans effet.
 *
 * <p>Meme raison que {@link ClesRedisSecurite} : un contrat recopie de part et
 * d'autre divergera, et la divergence serait silencieuse puisque lire une cle
 * absente rend simplement « pas bloque ».
 *
 * <h2>Pourquoi aucune resolution DNS, et comment c'est garanti</h2>
 *
 * {@link InetAddress#getByName} resout un <b>nom</b> des que la chaine n'est pas
 * une adresse litterale valide. Mesure sur ce poste, JDK 17.0.12 :
 *
 * <pre>
 * 20 litteraux valides        2 ms
 * 20 formes IPv4 invalides    2258 ms      (~113 ms par appel)
 * </pre>
 *
 * <p>{@code 999.1.1.1} satisfait la forme {@code \d{1,3}(\.\d{1,3}){3}} sans
 * etre une adresse : {@code getByName} echoue a la lire, et part donc
 * interroger le resolveur. Sur le chemin d'une requete, c'est un appel bloquant
 * de plus de cent millisecondes sur une EventLoop Netty, declenche par une
 * valeur d'en-tete que l'appelant choisit.
 *
 * <p>La garantie tient ici a l'ordre des verifications : chaque octet IPv4 est
 * valide <b>avant</b> toute construction d'adresse, et {@link #octets} n'appelle
 * {@code getByName} que lorsque la chaine contient un deux-points — cas ou le
 * JDK lit un litteral IPv6 et echoue sans consulter le resolveur (mesure :
 * 2 ms pour vingt formes IPv6 invalides). Une chaine hexadecimale <b>sans</b>
 * deux-points, elle, est un nom d'hote pour le JDK et declenche bien une
 * resolution : c'est pourquoi le deux-points est exige.
 */
public final class AdresseIpLitterale {

    private AdresseIpLitterale() {
        throw new UnsupportedOperationException("Cette classe ne peut pas etre instanciee");
    }

    /**
     * Quatre groupes de un a trois chiffres.
     *
     * <p>Les groupes sont <b>captures</b> : la validation de chaque octet se fait
     * sur le groupe, et non en deleguant la lecture au JDK. C'est cette capture
     * qui supprime le chemin DNS.
     */
    private static final Pattern IPV4 =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    /**
     * Caracteres admis dans un litteral IPv6.
     *
     * <p>La longueur est bornee a 45 caracteres : c'est la plus longue forme
     * possible, celle d'une IPv6 portant une IPv4 en queue. Le deux-points est
     * exige separement, parce que cette forme accepte aussi une chaine purement
     * hexadecimale — {@code abcdef} — qui est un nom d'hote pour le JDK.
     */
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]{2,45}$");

    /** Longueur d'une adresse IPv4 en octets. */
    private static final int OCTETS_IPV4 = 4;

    /** Valeur maximale d'un octet IPv4. */
    private static final int OCTET_MAX = 255;

    /**
     * Convertit une adresse litterale en octets.
     *
     * <p>Les zeros de tete sont acceptes et absorbes : {@code 041.223.010.005}
     * et {@code 41.223.10.5} designent la meme machine, et en faire deux valeurs
     * distinctes produirait deux regles dont une seule serait consultee. Ils sont
     * lus en <b>decimal</b>, jamais en octal.
     *
     * @return les octets — quatre ou seize — ou {@code null} si la valeur n'est
     *         pas une adresse litterale. {@code null} vaut refus : on ne devine
     *         jamais, et aucune resolution de nom n'est declenchee.
     */
    public static byte[] octets(String valeur) {
        if (valeur == null) {
            return null;
        }
        String nettoyee = valeur.trim();

        if (nettoyee.indexOf(':') >= 0) {
            // Le deux-points fait lire au JDK un litteral IPv6 : il echoue sans
            // consulter le resolveur. Ecrire ici un analyseur IPv6 a la main —
            // formes compressees, IPv4 en queue, zone — multiplierait les
            // occasions de se tromper sur la seule forme que le JDK lit bien.
            if (!IPV6.matcher(nettoyee).matches()) {
                return null;
            }
            try {
                return InetAddress.getByName(nettoyee).getAddress();
            } catch (UnknownHostException illisible) {
                return null;
            }
        }

        Matcher forme = IPV4.matcher(nettoyee);
        if (!forme.matches()) {
            return null;
        }
        byte[] adresse = new byte[OCTETS_IPV4];
        for (int rang = 0; rang < OCTETS_IPV4; rang++) {
            // parseInt et non getByName : trois chiffres au plus, donc aucun
            // debordement possible, et surtout aucun appel au resolveur quand
            // la valeur depasse 255.
            int octet = Integer.parseInt(forme.group(rang + 1));
            if (octet > OCTET_MAX) {
                return null;
            }
            adresse[rang] = (byte) octet;
        }
        return adresse;
    }

    /** @return vrai si la valeur est une adresse IP litterale. */
    public static boolean estLitterale(String valeur) {
        return octets(valeur) != null;
    }

    /**
     * Forme canonique d'une adresse litterale.
     *
     * <p>C'est la forme que rend {@code InetAddress.getHostAddress()}, donc
     * exactement celle que {@code ClientIpResolver} produit depuis l'adresse de
     * la socket. C'est ce qui fait qu'une regle ecrite ici correspond a la cle
     * que le gateway interrogera.
     *
     * <p>L'IPv6 n'est pas comprimee — {@code 2001:db8::1} devient
     * {@code 2001:db8:0:0:0:0:0:1} — parce que c'est ce que rend le JDK des deux
     * cotes. Comprimer ici donnerait une cle que le gateway ne formerait jamais.
     *
     * @return la forme canonique, ou {@code null} si la valeur n'est pas une
     *         adresse litterale
     */
    public static String canonique(String valeur) {
        byte[] adresse = octets(valeur);
        if (adresse == null) {
            return null;
        }
        try {
            // getByAddress et non getByName : il construit depuis des octets
            // deja valides et ne consulte aucun resolveur. getHostAddress se
            // contente de formater, sans resolution inverse.
            return InetAddress.getByAddress(adresse).getHostAddress();
        } catch (UnknownHostException impossible) {
            // getByAddress ne leve que sur une longueur autre que 4 ou 16, et
            // octets() ne rend que ces deux longueurs.
            return null;
        }
    }
}
