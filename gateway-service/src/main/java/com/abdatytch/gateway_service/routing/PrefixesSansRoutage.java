package com.abdatytch.gateway_service.routing;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Les prefixes que {@link CheminCible} connait et qu'AUCUNE route ne sert.
 *
 * <h2>Pourquoi cette classe existe</h2>
 *
 * Normalement, un prefixe declare par {@code CheminCible} sans route
 * correspondante est un defaut, et {@code RoutageCoherentTest} le refuse. Cette
 * classe est la liste — courte, nommee, motivee — des exceptions assumees.
 *
 * <p>Elle est en code de PRODUCTION et non dans un test, deliberement. Une
 * exception cachee dans un harnais n'est lue par personne : elle devient un
 * trou qu'on redecouvre. Ici, elle se lit a cote de la table des prefixes
 * qu'elle contredit.
 *
 * <h2>Ce qui l'a rendue necessaire</h2>
 *
 * Onze routes vivaient dans {@code config-repo/dev/gateway-service-dev.yml},
 * en {@code spring.cloud.gateway.routes}. Elles n'ont jamais rien route, pour
 * deux raisons independantes :
 *
 * <ul>
 *   <li>gateway-service ne declare pas {@code spring-cloud-starter-gateway} :
 *       il n'y a aucun moteur pour les lire. Le {@code RouterFunction} de
 *       {@code GatewayRoutesConfig} est le seul routage de cette passerelle ;</li>
 *   <li>le bloc etait indente sous la clef {@code geolocation:} et non sous
 *       {@code spring:}. Une clef inconnue est ignoree en silence.</li>
 * </ul>
 *
 * <p>Trois garde-fous les lisaient pourtant comme reelles, en cherchant des
 * chaines ligne par ligne dans le YAML. Une lecture de TEXTE ne peut voir ni
 * l'indentation d'une clef, ni l'absence du moteur qui la lirait. C'est la
 * troisieme fois dans ce depot qu'une lecture de texte valide une declaration
 * inerte — apres un commentaire de POM qui satisfaisait son propre garde-fou,
 * et une expression reguliere qui prenait {@code "/admin/**"} pour une
 * ouverture de commentaire.
 *
 * <p>Cinq des six prefixes orphelins ont rejoint le {@code RouterFunction}.
 * Le sixieme est ci-dessous.
 */
public final class PrefixesSansRoutage {

    /**
     * Prefixe -&gt; raison pour laquelle aucune route ne peut le servir.
     *
     * <p>La raison est obligatoire et elle est lue par le harnais : un ajout
     * sans motif ne compile pas, et un ajout tout court fait echouer le test
     * qui pince la taille de cette table. Grandir demande donc une decision
     * explicite, ce qui est exactement le but.
     */
    private static final Map<String, String> MOTIFS = new LinkedHashMap<>();

    static {
        // Le flux temps reel des notifications.
        //
        // C'est une MONTEE EN PROTOCOLE WebSocket. Le RouterFunction proxifie
        // avec WebClient, qui ne sait pas la faire : il n'y a pas de maniere
        // d'ecrire cette route avec le mecanisme en place.
        //
        // Le service, lui, est pret : notification-service porte
        // GestionnaireWebSocket sur /notifications-flux, enregistre par
        // ConfigurationWebSocket. Le chemin manquant est celui de la
        // passerelle, et lui seul.
        //
        // Deux issues possibles, et c'est une decision a prendre :
        //
        //   1. declarer spring-cloud-starter-gateway et laisser son
        //      WebsocketRoutingFilter faire le relais. Cela ajoute un SECOND
        //      mecanisme de routage a cette passerelle, avec ses propres
        //      priorites de HandlerMapping — precisement la confusion dont ce
        //      depot vient de payer le prix ;
        //
        //   2. ecrire un relais dedie : un WebFilter place apres la chaine de
        //      securite, qui ouvre une session cliente vers l'instance resolue
        //      et transmet les trames dans les deux sens. Il doit reproduire ce
        //      que fait la proxification HTTP — signature HMAC du preambule,
        //      X-Auth-User-Id issu des claims VALIDES, adresse resolue par
        //      ClientIpResolver — et c'est la que se trouve le risque : un
        //      relais qui oublie une de ces trois choses ouvre une porte.
        //
        // Tant que la decision n'est pas prise, le flux n'est joignable qu'en
        // direct, de service a service. Un client mobile ne peut donc pas s'y
        // abonner. Les notifications PUSH, elles, passent par FCM et ne
        // dependent pas de ce chemin : la remise hors ligne fonctionne, c'est
        // l'abonnement temps reel dans l'application qui ne fonctionne pas.
        MOTIFS.put("/api/v1/notifications-flux",
                "Montee en protocole WebSocket : WebClient ne sait pas la faire. "
                + "Demande soit spring-cloud-starter-gateway, soit un relais dedie. "
                + "Voir le commentaire de PrefixesSansRoutage.");
    }

    private PrefixesSansRoutage() {
    }

    /** Les prefixes assumes comme non routes, avec leur motif. */
    public static Map<String, String> motifs() {
        return Map.copyOf(MOTIFS);
    }

    /** Vrai si ce prefixe est une exception assumee. */
    public static boolean contient(String prefixe) {
        return MOTIFS.containsKey(prefixe);
    }
}
