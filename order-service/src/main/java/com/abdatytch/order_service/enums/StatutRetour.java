package com.abdatytch.order_service.enums;

import java.util.Set;

/**
 * Le cycle de vie d'un retour arbitre par Sankadi.
 *
 * <p>Meme principe que {@link StatutCommande} : les transitions vivent ici.
 *
 * <h2>Pourquoi RECU est un etat distinct d'ACCEPTE</h2>
 *
 * Accepter un retour et avoir recu l'article sont deux faits differents, et les
 * confondre fait rembourser un colis qui n'est jamais revenu. C'est l'erreur
 * qui coute le plus cher a une place de marche, parce qu'elle est
 * industrialisable : un acheteur mal intentionne demande, obtient l'accord, et
 * n'envoie rien.
 */
public enum StatutRetour {

    /** L'acheteur a demande, Sankadi n'a pas tranche. */
    DEMANDE,

    /** Sankadi accepte : l'article doit revenir. */
    ACCEPTE,

    /** Sankadi refuse, avec un motif. La commande reprend son cours. */
    REFUSE,

    /** L'article est revenu. Seul etat depuis lequel on peut rembourser. */
    RECU,

    /** L'acheteur est rembourse, le dossier est clos. */
    REMBOURSE;

    public Set<StatutRetour> suivantsPossibles() {
        return switch (this) {
            case DEMANDE -> Set.of(ACCEPTE, REFUSE);
            case ACCEPTE -> Set.of(RECU);
            case RECU -> Set.of(REMBOURSE);
            case REFUSE, REMBOURSE -> Set.of();
        };
    }

    public boolean peutAllerVers(StatutRetour cible) {
        return suivantsPossibles().contains(cible);
    }

    public boolean estTerminal() {
        return suivantsPossibles().isEmpty();
    }

    /** Ce retour occupe-t-il la place unique ouverte sur sa commande ? */
    public boolean estOuvert() {
        return this == DEMANDE || this == ACCEPTE || this == RECU;
    }
}
