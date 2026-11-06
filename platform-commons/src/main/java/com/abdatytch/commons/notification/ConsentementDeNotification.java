package com.abdatytch.commons.notification;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Le consentement d'une personne, canal par canal.
 *
 * <h2>Pourquoi cette classe vit dans platform-commons</h2>
 *
 * Trois endroits doivent s'accorder sur le NOM des cles :
 *
 * <ul>
 *   <li>notification-service, qui les lit pour decider d'envoyer ;</li>
 *   <li>setting-service, qui les stocke — son magasin par utilisateur accepte
 *       n'importe quelle cle, donc rien ne l'empeche d'en inventer une
 *       voisine ;</li>
 *   <li>l'interface, qui les ecrit quand la personne regle ses preferences.</li>
 * </ul>
 *
 * <p>Une convention de nommage qui vit a trois endroits finit par divergier, et
 * le symptome serait le pire possible : la personne coche « pas de SMS »,
 * l'interface ecrit {@code notifications.sms}, notification-service lit
 * {@code notifications.canal.sms}, ne trouve rien, applique le defaut — et les
 * SMS continuent.
 *
 * <h2>Pourquoi le magasin de setting-service et non une table ici</h2>
 *
 * setting-service detient deja les preferences par utilisateur, avec son
 * effacement RGPD et son administration. Une table de consentement dans
 * notification-service serait une seconde liste de preferences, a effacer
 * separement — et un consentement oublie par un effacement est un probleme
 * reglementaire, pas un detail.
 *
 * <h2>Ce que cette classe ne decide PAS</h2>
 *
 * Les valeurs par defaut. Elles appartiennent a notification-service et a sa
 * configuration, parce qu'elles sont une politique d'exploitation : refuser le
 * SMS par defaut est un choix sur la facture et sur le consentement, pas une
 * propriete du contrat.
 */
public final class ConsentementDeNotification {

    /**
     * Prefixe des cles de consentement.
     *
     * <p>Distinct de {@code notifications.email-enabled} et de ses voisins, qui
     * sont des reglages SYSTEME du catalogue de setting-service — « la
     * plateforme autorise-t-elle ce canal » — et non le choix d'une personne.
     * Confondre les deux ferait d'une preference individuelle un interrupteur
     * general, ou l'inverse.
     */
    public static final String PREFIXE = "notifications.canal.";

    /**
     * Les canaux pour lesquels un consentement a un sens.
     *
     * <p>{@code IN_APP} et {@code WEBSOCKET} n'y figurent pas, et ce n'est pas
     * un oubli : ils remettent dans la propre boite de reception de la personne,
     * sur sa propre session. Lui demander la permission de lui montrer ses
     * notifications dans son interface n'aurait pas de sens — et refuser ce
     * canal reviendrait a lui cacher ses messages.
     *
     * <p>Les trois autres sortent de l'application : ils arrivent sur un
     * telephone ou dans une boite de courriel, c'est-a-dire chez la personne.
     */
    private static final Set<CanalDeNotification> CANAUX_A_CONSENTEMENT = Set.of(
            CanalDeNotification.COURRIEL,
            CanalDeNotification.SMS,
            CanalDeNotification.PUSH);

    private ConsentementDeNotification() {
    }

    /** {@code true} si ce canal demande le consentement de la personne. */
    public static boolean demandeUnConsentement(CanalDeNotification canal) {
        return CANAUX_A_CONSENTEMENT.contains(canal);
    }

    /**
     * La cle de preference d'un canal.
     *
     * <p>Minuscules : les cles d'un magasin de preferences se comparent
     * litteralement, et {@code notifications.canal.SMS} ne serait pas la meme
     * cle que {@code notifications.canal.sms}. La conversion est faite ici, une
     * fois, plutot que laissee a chaque appelant.
     *
     * @return par exemple {@code notifications.canal.sms}
     */
    public static String cle(CanalDeNotification canal) {
        return PREFIXE + canal.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Lit un consentement dans les preferences d'une personne.
     *
     * <h2>Trois reponses, et la troisieme compte</h2>
     *
     * <ul>
     *   <li>accorde : la personne a dit oui ;</li>
     *   <li>refuse : elle a dit non ;</li>
     *   <li><b>absent</b> : elle n'a rien dit. C'est le cas de la quasi-totalite
     *       des comptes, et c'est l'appelant qui tranche, avec sa politique de
     *       defaut par canal.</li>
     * </ul>
     *
     * <p>Rendre {@code false} pour un consentement absent aurait fondu les deux
     * derniers cas, et la politique de defaut serait devenue impossible a
     * exprimer : tout canal aurait ete refuse pour tout le monde, y compris le
     * courriel de confirmation d'inscription.
     *
     * <h2>Toute valeur autre que « true » vaut REFUS</h2>
     *
     * Et non « valeur invalide, on prend le defaut ». Le magasin accepte des
     * chaines libres : une valeur abimee ne doit pas rouvrir un canal que la
     * personne pensait avoir ferme. Entre se tromper en envoyant et se tromper
     * en n'envoyant pas, le second est reparable.
     *
     * @param preferences les preferences de la personne, cle vers valeur
     * @param canal       le canal concerne
     * @return {@code null} si la personne n'a rien exprime pour ce canal
     */
    public static Boolean lire(Map<String, String> preferences, CanalDeNotification canal) {
        if (preferences == null) {
            return null;
        }
        String valeur = preferences.get(cle(canal));
        if (valeur == null || valeur.isBlank()) {
            return null;
        }
        return "true".equalsIgnoreCase(valeur.strip());
    }
}
