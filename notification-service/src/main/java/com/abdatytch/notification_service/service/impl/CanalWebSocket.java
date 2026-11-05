package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.service.CanalDeRemise;
import com.abdatytch.notification_service.service.RegistreDesSessions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remise immediate aux sessions ouvertes.
 *
 * <h2>Personne de connecte n'est PAS un echec</h2>
 *
 * C'est le point de conception de ce canal. La plupart des utilisateurs sont
 * hors ligne la plupart du temps : compter chaque absence comme un echec
 * noierait les vraies pannes et rendrait toute alerte sur ce service
 * inexploitable. D'ou {@code SANS_DESTINATAIRE}, un etat distinct de
 * {@code ECHOUE}, exclu des reessais.
 *
 * <p>C'est aussi pourquoi {@code WEBSOCKET} ne se demande presque jamais seul :
 * {@code IN_APP} conserve la notification, {@code WEBSOCKET} la rend immediate.
 *
 * <h2>Le message porte l'identifiant de la notification</h2>
 *
 * Sans lui, le client recevrait un texte sans pouvoir le marquer lu ni le relier
 * a sa boite de reception — il afficherait donc un doublon au prochain
 * rechargement.
 */
@Component
public class CanalWebSocket implements CanalDeRemise {

    private static final Logger log = LoggerFactory.getLogger(CanalWebSocket.class);

    private final RegistreDesSessions registre;
    private final ObjectMapper json;

    public CanalWebSocket(RegistreDesSessions registre, ObjectMapper json) {
        this.registre = registre;
        this.json = json;
    }

    @Override
    public CanalDeNotification canal() {
        return CanalDeNotification.WEBSOCKET;
    }

    @Override
    public boolean estActif() {
        return true;
    }

    @Override
    public Mono<Resultat> remettre(MessageARemettre message) {
        String charge;
        try {
            charge = json.writeValueAsString(enveloppe(message));
        } catch (JsonProcessingException illisible) {
            // Un contenu non serialisable est un defaut de code, pas une panne :
            // le reessayer donnerait le meme resultat.
            log.error("Message WebSocket non serialisable : {}", illisible.getMessage());
            return Mono.just(Resultat.refuse("contenu non serialisable"));
        }

        int servis = registre.emettre(message.destinataireId(), charge);
        if (servis == 0) {
            return Mono.just(Resultat.sansDestinataire());
        }
        log.debug("Notification poussee a {} flux", servis);
        return Mono.just(Resultat.remis());
    }

    /**
     * La forme du message envoye au client.
     *
     * <p>Une {@code LinkedHashMap} pour que l'ordre des cles soit stable : un
     * client qui compare des charges utiles — un test, un cache — ne doit pas
     * voir deux messages differents pour un meme contenu.
     */
    private Map<String, Object> enveloppe(MessageARemettre message) {
        Map<String, Object> enveloppe = new LinkedHashMap<>();
        enveloppe.put("type", "notification");
        enveloppe.put("notificationId", message.notificationId());
        enveloppe.put("sujet", message.sujet());
        enveloppe.put("corps", message.corps());
        enveloppe.put("urgence", message.urgence() == null ? null : message.urgence().name());
        return enveloppe;
    }
}
