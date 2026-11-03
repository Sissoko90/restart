package com.abdatytch.auth_service.util;

import com.abdatytch.auth_service.constants.ServiceConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Lecture des champs d'un accuse de reception SMS, quelle que soit la forme du
 * corps.
 *
 * <h2>Pourquoi la forme est deduite du CONTENU</h2>
 *
 * {@code typeNotification} vaut 1 (POST) ou 2 (GET) et ce reglage vit chez le
 * fournisseur. Un accuse arrive donc en JSON, en formulaire, ou sans corps du
 * tout — et le {@code Content-Type} annonce n'est pas fiable : une passerelle
 * SMS qui poste du JSON en annonçant {@code text/plain} existe, et l'inverse
 * aussi. La forme est donc determinee par le premier caractere utile, et
 * l'en-tete ne sert qu'a journaliser ce qui n'a pas pu etre lu.
 *
 * <h2>Ce qui n'est jamais journalise</h2>
 *
 * Le corps lui-meme. Il vient d'un tiers et rien ne garantit qu'il ne contienne
 * pas un echo de ce qu'on lui a envoye — donc un code a usage unique. Seul le
 * nom de la classe d'exception et le type annonce sont traces.
 *
 * <p>Classe extraite de {@code AccuseDeReceptionSmsController}, qui portait
 * cette analyse en methodes privees : elle n'etait testable qu'au travers d'une
 * requete HTTP complete, alors qu'elle est purement fonctionnelle.
 */
@Slf4j
public final class ChampsDAccuseSms {

    private static final ObjectMapper LECTEUR_JSON = new ObjectMapper();

    private static final String CHAMP_RESOURCE_ID = "resourceId";
    private static final String CHAMP_CODE = "code";

    private ChampsDAccuseSms() {
    }

    /**
     * @param corps corps brut, possiblement nul ou vide
     * @param typeAnnonce {@code Content-Type} declare, uniquement pour le
     *        journal lorsqu'aucune forme n'est reconnue
     * @return les champs reconnus, jamais nul ; une carte vide si rien n'est
     *         exploitable — l'appelant retombe alors sur les parametres de
     *         requete
     */
    public static Map<String, String> depuis(String corps, String typeAnnonce) {
        if (corps == null || corps.isBlank()) {
            return Map.of();
        }
        String nettoye = corps.trim();
        if (nettoye.startsWith("{")) {
            return champsJson(nettoye);
        }
        if (nettoye.contains("=")) {
            return champsFormulaire(nettoye);
        }
        log.warn(ServiceConstants.LOG_WARN_SMS_ACCUSE_CORPS_INATTENDU,
                typeAnnonce == null ? "absent" : typeAnnonce);
        return Map.of();
    }

    private static Map<String, String> champsJson(String corps) {
        try {
            JsonNode racine = LECTEUR_JSON.readTree(corps);
            Map<String, String> champs = new HashMap<>();
            ajouter(champs, CHAMP_RESOURCE_ID, racine.get(CHAMP_RESOURCE_ID));
            ajouter(champs, CHAMP_CODE, racine.get(CHAMP_CODE));
            return champs;
        } catch (Exception e) {
            // Le corps N'EST PAS journalise : voir la javadoc de la classe.
            log.warn(ServiceConstants.LOG_WARN_SMS_ACCUSE_CORPS_ILLISIBLE,
                    e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static void ajouter(Map<String, String> champs, String nom, JsonNode valeur) {
        if (valeur != null && !valeur.isNull()) {
            champs.put(nom, valeur.asText());
        }
    }

    private static Map<String, String> champsFormulaire(String corps) {
        Map<String, String> champs = new HashMap<>();
        for (String paire : corps.split("&")) {
            int egal = paire.indexOf('=');
            if (egal <= 0) {
                continue;
            }
            String nom = decoder(paire.substring(0, egal));
            if (champs.containsKey(nom)) {
                continue;   // premiere occurrence retenue
            }
            champs.put(nom, decoder(paire.substring(egal + 1)));
        }
        return champs;
    }

    private static String decoder(String valeur) {
        try {
            return URLDecoder.decode(valeur, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return valeur;
        }
    }
}
