package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * La forme des cles que le moteur sait LIRE.
 *
 * <h2>Pourquoi cette classe existe</h2>
 *
 * Parce qu'une table accepte n'importe quelle chaine, et que le moteur
 * n'interroge que deux formes :
 *
 * <pre>
 *   &lt;modele&gt;.&lt;canal&gt;.sujet     &lt;modele&gt;.&lt;canal&gt;.corps
 *   &lt;modele&gt;.sujet              &lt;modele&gt;.corps
 * </pre>
 *
 * <p>Une surcharge enregistree sous toute autre forme — {@code projet.cree} tout
 * court, {@code projet.cree.titre}, {@code Projet.Cree.corps} — serait acceptee
 * par la base, lue par le moteur au rechargement, et <b>jamais consultee</b>.
 * L'exploitant verrait sa correction enregistree, repondue en 200, presente dans
 * la liste, et sans aucun effet sur les messages envoyes.
 *
 * <p>C'est exactement le motif que ce depot paie en boucle : du travail ecrit,
 * present, et jamais atteint. Le controle est donc pose a l'ECRITURE, la ou il
 * peut encore dire non.
 *
 * <h2>Un nom de canal ne peut pas servir de nom de modele</h2>
 *
 * {@code securite.sms.corps} n'est pas ambigu : le moteur essaie toujours la
 * cle du canal d'abord, donc il y lit le modele {@code securite} sur le canal
 * {@code sms}, et cette cle est parfaitement valide.
 *
 * <p>Ce qui n'existe pas, en revanche, c'est un moyen d'exprimer un modele
 * <b>nomme</b> {@code securite.sms} : la cle qu'on ecrirait pour lui est
 * exactement celle ci-dessus, et elle serait lue autrement. Aucune validation
 * ne peut rattraper cela, puisque les deux intentions produisent la meme chaine
 * — il n'y a rien a refuser dans la cle.
 *
 * <p>La regle posee ici est donc en amont, et c'est une regle de nommage :
 * <b>aucun segment d'un nom de modele ne peut etre un nom de canal</b>. Elle
 * interdit de creer la situation plutot que de pretendre la detecter apres
 * coup. Aucun des modeles existants n'est dans ce cas, donc elle ne coute rien
 * aujourd'hui.
 *
 * <p>Une premiere version de cette classe contrôlait le dernier segment du
 * modele APRES retrait du canal — un controle qui ne pouvait jamais se
 * declencher sur le cas qu'il pretendait couvrir, puisque le canal avait
 * justement consomme le segment en question.
 */
public final class CleDeModele {

    /** Les deux seules parties qu'un modele porte. */
    public static final Set<String> PARTIES = Set.of("sujet", "corps");

    /**
     * Les noms de canaux tels qu'ils apparaissent dans une cle : en minuscules.
     *
     * <p>Derive de l'enumeration, et non recopie. Un canal ajoute a
     * {@link CanalDeNotification} devient donc immediatement surchargeable,
     * sans qu'il faille penser a cette liste — un oubli qui se serait vu en
     * « cle invalide » sur un canal pourtant implemente.
     */
    public static final Set<String> CANAUX = Arrays.stream(CanalDeNotification.values())
            .map(canal -> canal.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    /** Longueur de la colonne {@code cle} dans {@code surcharge_de_modele}. */
    public static final int LONGUEUR_MAXIMALE = 200;

    /**
     * Un segment de nom de modele.
     *
     * <p>Minuscules, chiffres et tirets. Pas de majuscules : une cle est
     * comparee litteralement, donc {@code Projet.cree.corps} et
     * {@code projet.cree.corps} seraient deux surcharges distinctes dont une
     * seule servirait. Pas de blanc souligne non plus — il est reserve aux noms
     * de canaux ({@code in_app}), ce qui garde les deux vocabulaires separes.
     */
    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private CleDeModele() {
    }

    /** Vrai si le moteur consultera effectivement cette cle. */
    public static boolean valide(String cle) {
        return motifDInvalidite(cle) == null;
    }

    /**
     * Le motif du refus, ou {@code null} si la cle est lisible.
     *
     * <p>Un motif et non un booleen : « cle invalide » obligerait l'exploitant
     * a deviner lequel des controles il a franchi. Le texte retourne part dans
     * la reponse HTTP, donc il doit tenir sans contexte.
     */
    public static String motifDInvalidite(String cle) {
        if (cle == null || cle.isBlank()) {
            return "La cle est vide.";
        }
        if (cle.length() > LONGUEUR_MAXIMALE) {
            return "La cle depasse " + LONGUEUR_MAXIMALE + " caracteres, la "
                    + "longueur de la colonne qui la stocke.";
        }

        String[] segments = cle.split("\\.", -1);
        if (segments.length < 2) {
            return "Une cle se termine par .sujet ou .corps, apres au moins un "
                    + "segment de nom de modele. Exemple : projet.cree.corps";
        }

        String partie = segments[segments.length - 1];
        if (!PARTIES.contains(partie)) {
            return "Le dernier segment est « " + partie + " », or le moteur ne "
                    + "lit que .sujet et .corps. Toute autre terminaison serait "
                    + "enregistree sans jamais etre consultee.";
        }

        // Le canal est optionnel : s'il est la, il consomme l'avant-dernier
        // segment, et le nom du modele est ce qui reste devant.
        int finDuModele = segments.length - 1;
        boolean avecCanal = segments.length >= 3
                && CANAUX.contains(segments[segments.length - 2]);
        if (avecCanal) {
            finDuModele--;
        }

        if (finDuModele == 0) {
            return "Il ne reste aucun segment pour nommer le modele devant « "
                    + segments[segments.length - 2] + "." + partie + " ».";
        }

        for (int i = 0; i < finDuModele; i++) {
            if (!SEGMENT.matcher(segments[i]).matches()) {
                return "Le segment « " + segments[i] + " » n'est pas un nom de "
                        + "modele valide : minuscules, chiffres et tirets "
                        + "uniquement.";
            }
            // Voir le javadoc de la classe. Un nom de canal employe comme
            // segment de modele produit des cles qui se lisent autrement
            // qu'elles ne s'ecrivent, et aucune validation ne peut rattraper
            // cela plus tard : les deux intentions donnent la meme chaine.
            if (CANAUX.contains(segments[i])) {
                return "Le segment « " + segments[i] + " » est un nom de canal, "
                        + "et ne peut pas servir a nommer un modele : la cle se "
                        + "lirait comme une variante de canal d'un autre modele.";
            }
        }

        return null;
    }
}
