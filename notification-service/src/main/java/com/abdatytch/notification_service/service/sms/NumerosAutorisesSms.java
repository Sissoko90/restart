package com.abdatytch.notification_service.service.sms;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Les seuls numeros vers lesquels un SMS peut reellement partir.
 *
 * <h2>A quoi cela sert</h2>
 *
 * Pendant le developpement et les essais, un envoi part vers un vrai
 * fournisseur et consomme un vrai credit. Une boucle, un jeu de donnees recopie
 * de la production, un test lance par megarde sur la bonne configuration : il
 * suffit d'une fois pour envoyer des messages a des personnes reelles et payer
 * pour cela.
 *
 * <p>{@code sms.enabled=false} protege deja, mais c'est un interrupteur tout ou
 * rien : des qu'on veut verifier un envoi, il faut le rouvrir, et il protege
 * alors <b>tous</b> les numeros, y compris ceux qu'on ne voulait pas joindre.
 *
 * <p>Cette liste repond a l'autre besoin : envoyer vraiment, mais seulement vers
 * les numeros qu'on a nommes.
 *
 * <h2>Liste vide = aucun filtrage</h2>
 *
 * Et non « aucun envoi ». C'est le choix inverse de celui des secrets, ou une
 * valeur vide refuse tout, et la raison est differente : ici la liste est une
 * restriction volontaire, pas une autorisation. Vide par defaut, elle laisse la
 * production fonctionner normalement — le garde-fou de la production est le
 * plafond journalier, pas cette liste.
 *
 * <p>Si une liste vide coupait les envois, ajouter ce garde-fou aurait suffi a
 * casser l'envoi de tous les codes de verification sans que personne ne change
 * de configuration.
 *
 * <h2>La comparaison porte sur le numero NORMALISE</h2>
 *
 * {@code +223 70 00 00 00}, {@code 00223 70 00 00 00} et {@code 22370000000}
 * designent la meme personne. Comparer les chaines brutes aurait laisse passer
 * un numero liste sous une autre forme — un garde-fou qui refuse le numero meme
 * qu'on lui a demande d'autoriser, et dont la cause serait tres difficile a
 * lire.
 *
 * <p>Un numero inexploitable normalise en chaine vide. Il n'est donc jamais
 * autorise par une liste non vide, ce qui est le bon sens : on n'envoie pas vers
 * ce qu'on n'a pas su lire.
 */
public final class NumerosAutorisesSms {

    private final Set<String> autorises;

    /**
     * @param liste numeros separes par des virgules, sous n'importe quelle
     *              forme d'ecriture. Vide ou {@code null} : aucun filtrage
     */
    public NumerosAutorisesSms(String liste) {
        this.autorises = normaliserTous(liste);
    }

    private static Set<String> normaliserTous(String liste) {
        if (liste == null || liste.isBlank()) {
            return Set.of();
        }
        Set<String> resultat = new LinkedHashSet<>();
        Arrays.stream(liste.split(","))
                .map(NumeroPourAfrikSms::normaliser)
                // Une entree illisible de la liste est ECARTEE, et non conservee
                // telle quelle : gardee, elle ne correspondrait a aucun numero
                // normalise et donnerait l'illusion d'une autorisation.
                .filter(numero -> !numero.isEmpty())
                .forEach(resultat::add);
        return resultat;
    }

    /** {@code true} si aucun filtrage n'est demande. */
    public boolean inactif() {
        return autorises.isEmpty();
    }

    /**
     * Nombre de numeros retenus.
     *
     * <p>Pour le journal de demarrage. Les valeurs n'y figurent jamais : un
     * numero de telephone est une donnee personnelle, et ce journal part vers
     * Loki.
     */
    public int taille() {
        return autorises.size();
    }

    public boolean autorise(String numero) {
        if (autorises.isEmpty()) {
            return true;
        }
        String normalise = NumeroPourAfrikSms.normaliser(numero);
        return !normalise.isEmpty() && autorises.contains(normalise);
    }
}
