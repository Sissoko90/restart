package com.abdatytch.commons.erasure;

/**
 * Noms des files d'effacement, partagés pour éviter la divergence.
 *
 * <p>Une faute de frappe dans un nom de topic ne provoque aucune erreur : le
 * consommateur s'abonne à une file vide et n'effacera jamais rien. C'est
 * exactement le type de panne silencieuse qui a déjà touché la liste noire d'IP
 * de ce dépôt, où trois services utilisaient trois conventions de clés
 * différentes.
 */
public final class ErasureTopics {

    /** Demandes d'effacement — publiées par auth-service, consommées par tous. */
    public static final String ERASURE_REQUESTED = "user.erasure.requested";

    /** Accusés d'effacement — publiés par chaque service, consommés par auth-service. */
    public static final String ERASURE_COMPLETED = "user.erasure.completed";

    private ErasureTopics() {
    }
}
