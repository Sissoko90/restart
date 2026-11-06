package com.abdatytch.notification_service.dto.response;

/**
 * Un modele tel qu'il s'applique REELLEMENT, et d'ou il vient.
 *
 * <h2>Pourquoi l'origine figure dans la reponse</h2>
 *
 * Sans elle, l'exploitant ne peut pas distinguer un texte qu'il a corrige d'un
 * texte livre avec le code. Il ne saurait donc pas ce que ferait une suppression
 * de surcharge, ni pourquoi deux environnements n'envoient pas le meme message.
 *
 * <h2>Pourquoi {@code presenteDansLeFichier} est separe de l'origine</h2>
 *
 * Parce que les deux repondent a des questions differentes. {@code origine} dit
 * qui gagne ; {@code presenteDansLeFichier} dit s'il y a un socle au-dessous.
 *
 * <p>Une surcharge sans socle est le cas qu'il faut rendre visible : c'est soit
 * l'ajout volontaire d'une variante de canal, soit une FAUTE DE FRAPPE dans un
 * nom de modele. Dans le second cas l'exploitant vient de creer une cle que
 * personne ne rend jamais, et sa correction reste sans effet. La forme de la cle
 * est deja controlee a l'ecriture, mais aucun controle ne peut deviner qu'on
 * voulait ecrire {@code projet.cree} plutot que {@code projet.creee} : seule
 * l'absence de socle le signale.
 *
 * @param cle                  la cle complete, telle que le moteur l'interroge
 * @param valeur               le texte en vigueur, ou {@code null} si plus rien
 *                             ne s'applique
 * @param origine              laquelle des deux couches fournit ce texte
 * @param presenteDansLeFichier vrai si le fichier porte cette cle
 */
public record ModeleResponse(
        String cle,
        String valeur,
        Origine origine,
        boolean presenteDansLeFichier) {

    /** D'ou vient le texte en vigueur. */
    public enum Origine {

        /** Du fichier livre avec le code. */
        FICHIER,

        /** De la table, posee a chaud : elle prend le pas sur le fichier. */
        SURCHARGE,

        /**
         * Plus rien ne s'applique.
         *
         * <p>Rendu apres la suppression d'une surcharge qui n'avait pas de socle
         * au-dessous. Le dire explicitement plutot que de rendre un texte vide :
         * un modele sans texte ne s'envoie pas, il remonte « modele
         * introuvable » dans le compte rendu, et l'exploitant doit l'apprendre
         * ici et non en constatant qu'un message ne part plus.
         */
        AUCUNE
    }
}
