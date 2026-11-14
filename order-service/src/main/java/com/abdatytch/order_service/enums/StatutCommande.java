package com.abdatytch.order_service.enums;

import java.util.Set;

/**
 * Le cycle de vie d'une commande, et les seules transitions permises.
 *
 * <h2>La machine est DANS l'enumeration</h2>
 *
 * Les transitions legales sont declarees ici, a cote des etats, et non
 * dispersees en {@code if} dans les services. Un etat ajoute sans sa regle de
 * transition devient alors visible : il n'a ni predecesseur ni successeur, et
 * {@code MachineDeCommandeTest} le refuse.
 *
 * <p>Cette discipline vient d'un defaut recurrent de ce depot : une regle
 * ecrite a deux endroits finit par differer, et c'est la copie la moins lue qui
 * decide.
 *
 * <h2>Les etats qui viennent d'ailleurs</h2>
 *
 * {@link #PAYEE} sera constate par payment-service, {@link #EXPEDIEE} et
 * {@link #LIVREE} par logistics-service. Aucun des deux n'existe encore :
 * l'administration Sankadi les pose, par les memes methodes de service. La
 * machine ne connait pas l'appelant, seulement la transition.
 */
public enum StatutCommande {

    /** Creee, stock reserve chez market-service, en attente d'encaissement. */
    A_PAYER,

    /** Encaissement constate. La boutique peut preparer. */
    PAYEE,

    /** La boutique declare le colis pret. */
    PREPAREE,

    /** Remis a la livraison. */
    EXPEDIEE,

    /** Livraison constatee : la fenetre de retour s'ouvre. */
    LIVREE,

    /** Fenetre de retour passee. La somme est payable a la boutique. */
    CLOTUREE,

    /** Un retour est demande ou accepte. */
    RETOUR_EN_COURS,

    /** Retour abouti, l'acheteur est rembourse. */
    REMBOURSEE,

    /** Avant encaissement, ou par decision de Sankadi. */
    ANNULEE;

    /**
     * Les etats accessibles depuis celui-ci.
     *
     * <p>Declare en methode et non en champ : un champ initialise avec des
     * constantes de la meme enumeration leve une {@code NullPointerException}
     * au chargement de la classe, les constantes n'etant pas encore
     * construites.
     */
    public Set<StatutCommande> suivantsPossibles() {
        return switch (this) {
            // L'annulation reste ouverte tant que rien n'est encaisse.
            case A_PAYER -> Set.of(PAYEE, ANNULEE);
            // Une commande payee peut encore etre annulee par Sankadi : la
            // boutique peut etre en rupture, ou suspendue entre-temps. Le
            // remboursement est alors du, et c'est payment-service qui
            // l'executera.
            case PAYEE -> Set.of(PREPAREE, ANNULEE);
            case PREPAREE -> Set.of(EXPEDIEE, ANNULEE);
            // Une fois expediee, plus d'annulation : le colis est parti. Le
            // recours est le retour, pas l'annulation. Les deux n'ont pas les
            // memes consequences sur le stock ni sur l'argent.
            case EXPEDIEE -> Set.of(LIVREE, RETOUR_EN_COURS);
            // LIVREE mene a CLOTUREE par le balayeur, ou a un retour si
            // l'acheteur le demande dans la fenetre.
            case LIVREE -> Set.of(CLOTUREE, RETOUR_EN_COURS);
            // RETOUR_EN_COURS revient a LIVREE si Sankadi refuse le retour : la
            // commande reprend son cours, et sa fenetre de retour court
            // toujours.
            case RETOUR_EN_COURS -> Set.of(REMBOURSEE, LIVREE);
            // Trois etats terminaux. Une commande cloturee ne rouvre pas : la
            // somme est payable, et un etat qui revient en arriere apres un
            // versement produit un versement en double.
            case CLOTUREE, REMBOURSEE, ANNULEE -> Set.of();
        };
    }

    public boolean peutAllerVers(StatutCommande cible) {
        return suivantsPossibles().contains(cible);
    }

    /** Aucun etat ne succede a celui-ci. */
    public boolean estTerminal() {
        return suivantsPossibles().isEmpty();
    }

    /**
     * L'argent a-t-il ete encaisse ?
     *
     * <p>Determine si une annulation implique un remboursement. Les etats
     * terminaux {@code REMBOURSEE} et {@code CLOTUREE} en font partie : ils
     * decrivent une commande qui a ete payee, meme si le solde a depuis bouge.
     */
    public boolean argentEncaisse() {
        return this != A_PAYER && this != ANNULEE;
    }

    /** Le stock reserve doit-il etre rendu quand la commande atteint cet etat ? */
    public boolean rendLeStock() {
        return this == ANNULEE || this == REMBOURSEE;
    }
}
