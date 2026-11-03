package com.abdatytch.auth_service.service.oauth2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Le nom transmis par Apple a la premiere autorisation, et a elle seule.
 *
 * <h2>Une seule occasion de le lire</h2>
 *
 * Apple ne met le nom ni dans le {@code id_token} ni dans aucun point de
 * profil — il n'en a pas. Il l'envoie dans le champ {@code user} du formulaire
 * de rappel, sous forme de JSON, et <b>uniquement lors de la premiere
 * autorisation</b> de cette personne pour cette application.
 *
 * <p>Les connexions suivantes n'apportent plus rien. Un nom non enregistre ce
 * jour-la est donc perdu <b>definitivement</b>, sauf si la personne va revoquer
 * l'acces dans les reglages de son appareil puis se reconnecte — ce qu'on ne
 * peut pas lui demander. C'est la cause connue des comptes Apple anonymes : le
 * champ est facile a ignorer, puisqu'il est absent de toutes les connexions
 * suivantes et que rien ne signale son passage.
 *
 * <h2>Pourquoi une lecture tolerante</h2>
 *
 * Le champ est absent a chaque connexion sauf la premiere : son absence est
 * donc l'etat <b>normal</b> et ne doit rien faire echouer. Un JSON illisible non
 * plus — il vaut mieux un compte sans nom qu'une connexion refusee pour un champ
 * d'agrement.
 *
 * <p>Le contenu vient d'un formulaire poste par un tiers et n'est donc pas une
 * donnee de confiance : il ne sert qu'a renseigner un prenom et un nom, jamais
 * l'identite. Celle-ci vient du {@code sub} du {@code id_token} verifie.
 */
@Slf4j
public final class ProfilInitialApple {

    private final String prenom;
    private final String nom;

    private ProfilInitialApple(String prenom, String nom) {
        this.prenom = prenom;
        this.nom = nom;
    }

    /** Profil vide : l'etat de toutes les connexions sauf la premiere. */
    public static ProfilInitialApple absent() {
        return new ProfilInitialApple(null, null);
    }

    /**
     * Lit le champ {@code user} du formulaire de rappel d'Apple.
     *
     * <p>Forme attendue :
     * {@code {"name":{"firstName":"...","lastName":"..."},"email":"..."}}.
     * L'adresse n'est pas reprise ici : celle du {@code id_token} verifie est
     * preferable, et elle est presente a chaque connexion.
     */
    public static ProfilInitialApple lire(String champUser, ObjectMapper jackson) {
        if (champUser == null || champUser.isBlank()) {
            return absent();
        }
        try {
            JsonNode nomComplet = jackson.readTree(champUser).path("name");
            return new ProfilInitialApple(
                    texte(nomComplet.path("firstName")),
                    texte(nomComplet.path("lastName")));
        } catch (Exception e) {
            // Ni le contenu ni l'exception : le champ porte le nom civil d'une
            // personne, et les journaux n'ont pas a le recevoir.
            log.warn("Champ user du rappel Apple illisible ({}) : la connexion "
                    + "continue sans nom", e.getClass().getSimpleName());
            return absent();
        }
    }

    private static String texte(JsonNode noeud) {
        if (!noeud.isTextual()) {
            return null;
        }
        String valeur = noeud.asText().trim();
        return valeur.isEmpty() ? null : valeur;
    }

    public String prenom() {
        return prenom;
    }

    public String nom() {
        return nom;
    }

    public boolean estPresent() {
        return prenom != null || nom != null;
    }

    /** Nom affichable, ou {@code null} si rien n'a ete transmis. */
    public String nomComplet() {
        if (!estPresent()) {
            return null;
        }
        return prenom == null ? nom : (nom == null ? prenom : prenom + " " + nom);
    }
}
