package com.abdatytch.commons.config;

/**
 * Comment la configuration des services se recharge sans redemarrage.
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
 * <h2>La regle : un interrupteur d'incident, et rien d'autre</h2>
 *
 * Ce qui porte {@code @RefreshScope} est ce qu'un exploitant veut pouvoir
 * changer <b>pendant</b> un incident, sans redemarrer :
 *
 * <ul>
 *   <li>les canaux de notification — couper le SMS, le courriel, le push ;</li>
 *   <li>les plafonds de depense et les seuils — relever un plafond trop bas ne
 *       doit pas demander un redemarrage, puisque pendant ce temps plus aucun
 *       code de verification ne part ;</li>
 *   <li>le verrouillage de compte, les limites de taille de fichier, la
 *       compression, le filigrane, la duree de cache : des reglages qu'on
 *       ajuste en regardant une courbe.</li>
 * </ul>
 *
 * <h2>Ce qui n'en porte PAS, et pour trois raisons differentes</h2>
 *
 * <p><b>1. Les beans planifies</b> — {@code RepriseDesRemises},
 * {@code SurveillanceDuSoldeSms}, {@code DevKeyRotationServiceImpl}. Le
 * probleme est documente : {@code ScheduledAnnotationBeanPostProcessor}
 * enregistre la tache une fois, au demarrage, et un rechargement ne la
 * reenregistre pas. Selon les cas la tache continue avec l'ancienne
 * configuration ou cesse de tourner — dans les deux cas <b>sans rien
 * signaler</b>. Leurs reglages sont des cadences, pas des interrupteurs : un
 * redemarrage est acceptable.
 *
 * <p><b>2. Les filtres</b> — chaines de securite, liste noire d'adresses,
 * limitation de debit, protection XSS. C'est une prudence DELIBEREE, et non un
 * defaut mesure : un filtre {@code @RefreshScope} fonctionnerait probablement,
 * puisque la chaine detient un mandataire. Mais reconstruire un filtre de
 * securite sous charge est un risque que ces valeurs ne justifient pas — un
 * secret de passerelle ne change pas pendant un incident.
 *
 * <p><b>3. Ce qu'un rechargement casserait</b> :
 *
 * <ul>
 *   <li>les <b>cles de chiffrement</b> ({@code EncryptionServiceImpl},
 *       {@code UnifiedEncryptionServiceImpl}). Changer une cle a chaud rend
 *       illisibles les donnees chiffrees avec l'ancienne. Une rotation de cle
 *       est une operation a part entiere, pas un rechargement ;</li>
 *   <li>les <b>disjoncteurs</b> ({@code DisjoncteurSortantConfig}). Recreer le
 *       bean remettrait a zero l'etat accumule : un disjoncteur OUVERT se
 *       refermerait, et le rechargement relancerait le trafic vers la
 *       dependance en panne — l'inverse de ce qu'on cherche pendant un
 *       incident ;</li>
 *   <li>l'<b>infrastructure</b> ({@code NettyConfig}, les configurations
 *       Kafka, R2DBC, CORS). Ces beans construisent des connexions et des
 *       boucles d'evenements, une fois. Les recreer a chaud n'est pas un
 *       rechargement de configuration mais un redemarrage partiel, sans aucune
 *       des garanties d'un vrai redemarrage.</li>
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
