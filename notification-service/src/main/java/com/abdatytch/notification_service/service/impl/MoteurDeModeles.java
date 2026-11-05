package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Rend le contenu d'une notification, par canal.
 *
 * <h2>Pourquoi le rendu est PAR CANAL</h2>
 *
 * Un SMS de 160 caracteres et un courriel ne portent pas le meme texte. Rendre
 * une seule fois pour tous les canaux obligerait a choisir le plus contraint,
 * donc a appauvrir le courriel — ou l'inverse, a tronquer le SMS au hasard.
 *
 * <p>Le fichier de modeles porte donc des cles de la forme
 * {@code <modele>.<canal>.sujet} et {@code <modele>.<canal>.corps}, avec un
 * repli sur {@code <modele>.corps} quand un canal n'a pas de variante.
 *
 * <h2>Un modele inconnu est un REFUS, pas un texte vide</h2>
 *
 * Envoyer un courriel vide parce que le gabarit manque est pire que de ne rien
 * envoyer : le destinataire recoit un message incomprehensible et l'emetteur
 * croit avoir notifie. {@code rendre} rend donc un {@link Optional} vide, et
 * l'orchestrateur transforme cela en {@code REFUSE} visible dans le compte
 * rendu.
 *
 * <h2>Pourquoi un fichier de proprietes et non un moteur de gabarits</h2>
 *
 * Parce qu'un moteur complet — Thymeleaf, Freemarker — apporte l'evaluation
 * d'expressions, donc une surface d'injection : une variable qui vient d'un
 * autre service et qui serait interpretee comme du code. La substitution
 * litterale de {@code {cle}} ne peut rien evaluer.
 *
 * <p>Le jour ou le HTML devient necessaire, il faudra un moteur — avec
 * echappement par defaut, et des variables jamais traitees comme du gabarit.
 */
@Component
public class MoteurDeModeles {

    private static final Logger log = LoggerFactory.getLogger(MoteurDeModeles.class);

    private final Properties modeles = new Properties();

    public MoteurDeModeles(@Value("classpath:modeles-notification.properties") Resource fichier) {
        try (var lecteur = new InputStreamReader(fichier.getInputStream(), StandardCharsets.UTF_8)) {
            modeles.load(lecteur);
            log.info("{} cles de modeles chargees", modeles.size());
        } catch (IOException absent) {
            // Le demarrage n'est PAS bloque — politique du projet. Mais chaque
            // rendu echouera, et c'est dit une fois, fort, au lieu d'etre
            // decouvert notification par notification.
            log.error("modeles-notification.properties illisible : AUCUNE "
                    + "notification ne pourra etre rendue ({})", absent.getMessage());
        }
    }

    /** Le contenu rendu pour un canal, ou vide si le modele est introuvable. */
    public Optional<Contenu> rendre(String modele, CanalDeNotification canal,
                                    Map<String, String> variables) {
        String corps = valeur(modele, canal, "corps");
        if (corps == null) {
            log.warn("Modele introuvable : {} pour le canal {}", modele, canal);
            return Optional.empty();
        }
        String sujet = valeur(modele, canal, "sujet");
        return Optional.of(new Contenu(
                substituer(sujet == null ? "" : sujet, variables),
                substituer(corps, variables)));
    }

    /** La valeur la plus specifique disponible : canal d'abord, modele ensuite. */
    private String valeur(String modele, CanalDeNotification canal, String partie) {
        String parCanal = modeles.getProperty(
                modele + "." + canal.name().toLowerCase(java.util.Locale.ROOT) + "." + partie);
        return parCanal != null ? parCanal : modeles.getProperty(modele + "." + partie);
    }

    /**
     * Remplace {@code {cle}} par sa valeur.
     *
     * <h3>Les variables ne sont JAMAIS re-parcourues</h3>
     *
     * La substitution se fait en un seul passage sur le gabarit. Une valeur qui
     * contiendrait elle-meme {@code {autreCle}} reste donc litterale : sans
     * cela, un emetteur pourrait faire lire une variable qu'il n'a pas fournie,
     * ou provoquer une recursion.
     *
     * <p>Une cle absente du gabarit laisse le marqueur en place plutot que
     * d'inserer « null » : un texte ou l'on voit {@code {nomDuProjet}} se
     * diagnostique, un texte ou l'on lit « null » se devine.
     */
    private String substituer(String gabarit, Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return gabarit;
        }
        StringBuilder rendu = new StringBuilder(gabarit.length() + 32);
        int i = 0;
        while (i < gabarit.length()) {
            int debut = gabarit.indexOf('{', i);
            if (debut < 0) {
                rendu.append(gabarit, i, gabarit.length());
                break;
            }
            int fin = gabarit.indexOf('}', debut);
            if (fin < 0) {
                rendu.append(gabarit, i, gabarit.length());
                break;
            }
            rendu.append(gabarit, i, debut);
            String cle = gabarit.substring(debut + 1, fin);
            String valeur = variables.get(cle);
            rendu.append(valeur != null ? valeur : gabarit.substring(debut, fin + 1));
            i = fin + 1;
        }
        return rendu.toString();
    }

    /** Sujet et corps rendus pour un canal donne. */
    public record Contenu(String sujet, String corps) {
    }
}
