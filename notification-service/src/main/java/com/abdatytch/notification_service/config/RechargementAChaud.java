package com.abdatytch.notification_service.config;

/**
 * Comment la configuration de ce service se recharge sans redemarrage.
 *
 * <p>Classe sans comportement : elle porte la documentation d'un mecanisme
 * reparti sur un fichier de configuration, des annotations et un test. Mise
 * ailleurs, l'explication aurait suivi l'un des trois et les deux autres
 * seraient devenus incomprehensibles.
 *
 * <h2>Rien n'etait rechargeable, dans AUCUN module</h2>
 *
 * Releve fait sur tout le depot : {@code @RefreshScope} n'apparaissait nulle
 * part, et l'endpoint {@code refresh} n'etait expose par aucun module. Les onze
 * services lisaient donc le config-repo <b>une seule fois, au demarrage</b>.
 *
 * <p>Changer une valeur dans le config-repo n'avait aucun effet jusqu'au
 * prochain redemarrage — et rien ne le disait. Un exploitant qui coupait
 * {@code sms.enabled} pendant un incident pouvait croire les SMS arretes alors
 * qu'ils continuaient de partir, et de se payer.
 *
 * <h2>Les deux pieces, et il faut les deux</h2>
 *
 * <ol>
 *   <li>{@code management.endpoints.web.exposure.include} doit contenir
 *       {@code refresh}. L'endpoint vient de spring-cloud-context, apporte par
 *       {@code spring-cloud-starter-config} : il existe deja, il n'etait pas
 *       expose ;</li>
 *   <li>les beans qui lisent la configuration doivent porter
 *       {@code @RefreshScope}. Sans cela, l'appel a {@code /actuator/refresh}
 *       recharge bien l'environnement — et les beans gardent les valeurs
 *       injectees au demarrage, <b>sans erreur</b>.</li>
 * </ol>
 *
 * <p>C'est le point ou l'on se trompe : exposer l'endpoint suffit a faire croire
 * que cela marche. L'appel repond {@code 200} avec la liste des cles modifiees,
 * et le comportement ne change pas.
 *
 * <h2>Qui peut appeler</h2>
 *
 * {@code /actuator/**} exige le role ADMIN dans {@code SecurityConfig}, sondes
 * exceptees. {@code refresh} n'y figure pas : le relire sans autorisation
 * divulguerait la configuration, et l'appeler est une action d'exploitation.
 *
 * <h2>Ce qui est rechargeable, et ce qui ne l'est pas</h2>
 *
 * Portent {@code @RefreshScope} :
 *
 * <ul>
 *   <li>{@code CanalSms}, {@code CanalCourriel}, {@code CanalPush} — couper ou
 *       ouvrir un canal est exactement ce qu'on veut faire pendant un
 *       incident ;</li>
 *   <li>{@code SmsServiceImpl} — {@code sms.enabled}, les numeros autorises, le
 *       fournisseur ;</li>
 *   <li>{@code PlafondJournalierSms} — relever le plafond quand il se revele
 *       trop bas ne doit pas demander un redemarrage, puisque pendant ce temps
 *       aucun code de verification ne part.</li>
 * </ul>
 *
 * <p>N'en portent PAS, deliberement :
 *
 * <ul>
 *   <li>{@code RepriseDesRemises} et {@code SurveillanceDuSoldeSms}. Un
 *       {@code @Scheduled} est enregistre une fois, au demarrage, avec la
 *       reference du bean d'alors. Sur un bean {@code @RefreshScope}, le
 *       planificateur garde l'ancienne instance : la tache continue de tourner
 *       avec l'ancienne configuration, et personne ne le voit. Leurs reglages
 *       demandent donc un redemarrage, et c'est acceptable — ce sont des
 *       cadences, pas des interrupteurs d'incident ;</li>
 *   <li>{@code GatewayAccessWebFilter} et la chaine de securite. Recharger un
 *       filtre de securite a chaud, c'est accepter une fenetre pendant laquelle
 *       la chaine se reconstruit. Le secret de passerelle ne change pas pendant
 *       un incident.</li>
 * </ul>
 *
 * <h2>La limite a connaitre</h2>
 *
 * Un {@code @RefreshScope} recree le bean au PROCHAIN acces. Si sa construction
 * echoue sur une valeur fautive — {@code sms.provider} inconnu, par exemple —
 * l'echec survient sur une requete, et non au rechargement. Le journal le dit,
 * mais {@code /actuator/refresh} aura repondu {@code 200}.
 */
public final class RechargementAChaud {

    private RechargementAChaud() {
    }
}
