package com.abdatytch.notification_service.health;

import com.abdatytch.notification_service.service.impl.MoteurDeModeles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Le rechargement des surcharges de modeles tourne-t-il encore ?
 *
 * <h2>La panne que cet indicateur rend visible</h2>
 *
 * {@code MoteurDeModeles.rechargerMaintenant} degrade <b>en silence</b>, et
 * c'est voulu : si la base est injoignable, l'instantane precedent reste en
 * vigueur et le service continue de rendre des notifications a partir du
 * fichier. C'est la bonne degradation.
 *
 * <p>Mais vue de l'exterieur, elle est indistinguable du cas normal. Le nombre
 * de surcharges ne bouge pas — exactement comme quand personne n'a rien
 * modifie. Un exploitant qui pousse une correction par
 * {@code PUT /admin/modeles} verrait sa valeur en base, ne la verrait pas dans
 * les messages, et n'aurait aucun endroit ou le constater.
 *
 * <p>L'age de la derniere lecture REUSSIE est la seule mesure qui separe les
 * deux cas.
 *
 * <h2>Les trois etats, et pourquoi « jamais lu » est a part</h2>
 *
 * <ul>
 *   <li><b>UP</b> : lecture reussie recemment ;</li>
 *   <li><b>DOWN, jamais lu</b> : les corrections a chaud n'ont JAMAIS
 *       fonctionne sur cette instance. La tache part a {@code initialDelay = 0},
 *       donc pendant l'initialisation du contexte — le moment ou une dependance
 *       a le plus de chances de ne pas etre prete ;</li>
 *   <li><b>DOWN, lecture perimee</b> : la tache planifiee s'est arretee, ou la
 *       base refuse depuis. Deux causes differentes, meme consequence.</li>
 * </ul>
 *
 * <h2>Le seuil est derive de l'intervalle, et non pose en dur</h2>
 *
 * Cinq fois l'intervalle de rechargement. Un multiple et non une valeur fixe :
 * poser « 5 minutes » en dur rendrait l'indicateur faux des que quelqu'un
 * change l'intervalle, et il le rendrait faux dans le sens dangereux — un
 * intervalle de dix minutes ferait crier l'indicateur en permanence, puis
 * quelqu'un le desactiverait.
 *
 * <p>Cinq, parce qu'un echec isole est deja couvert : le rechargement suivant
 * reussira. Alerter au premier manque produirait du bruit sur un hoquet
 * reseau.
 *
 * <h2>Cet indicateur ne garde pas la decouverte</h2>
 *
 * Consul interroge {@code /actuator/health/readiness}, qui ne le contient pas.
 * Un rechargement arrete n'empeche pas le service de remettre des
 * notifications : le retirer du routage aggraverait la situation au lieu de la
 * corriger. Meme raisonnement que pour {@link SanteDesCanaux}.
 */
/*
 * Le nom du bean est AUSSI la clef de cet indicateur dans /actuator/health, et
 * il ne peut pas etre « moteurDeModeles » : c'est le nom par defaut du
 * composant MoteurDeModeles lui-meme, et Spring refuse le contexte entier sur
 * un ConflictingBeanDefinitionException. Mesure : le test de contexte est tombe
 * au premier chargement reel, la ou aucun test unitaire ne pouvait le voir.
 *
 * « surchargesDeModeles » dit de plus ce que l'indicateur mesure — le
 * rechargement des surcharges — et non la classe qui le porte.
 */
@Component("surchargesDeModeles")
public class SanteDuMoteurDeModeles implements ReactiveHealthIndicator {

    /** Multiplicateur du seuil de peremption. Voir le javadoc de la classe. */
    private static final int TOLERANCE_EN_INTERVALLES = 5;

    private final MoteurDeModeles moteur;
    private final Duration seuil;

    public SanteDuMoteurDeModeles(
            MoteurDeModeles moteur,
            @Value("${notification.modeles.rechargement-ms:60000}") long intervalleMs) {
        this.moteur = moteur;
        this.seuil = Duration.ofMillis(intervalleMs).multipliedBy(TOLERANCE_EN_INTERVALLES);
    }

    @Override
    public Mono<Health> health() {
        return Mono.fromSupplier(() -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("surcharges", moteur.surchargesEnVigueur().size());
            details.put("seuilSecondes", seuil.toSeconds());

            Instant lue = moteur.derniereLectureReussie();
            if (lue == null) {
                details.put("derniereLecture", "jamais");
                details.put("consequence",
                        "les corrections poussees par PUT /admin/modeles ne s'appliquent pas ; "
                                + "le fichier versionne sert seul");
                return Health.down().withDetails(details).build();
            }

            Duration age = Duration.between(lue, Instant.now());
            details.put("derniereLecture", lue.toString());
            details.put("ageSecondes", age.toSeconds());

            if (age.compareTo(seuil) > 0) {
                details.put("consequence",
                        "le rechargement est arrete ou la base refuse ; les surcharges "
                                + "en vigueur sont figees a leur derniere lecture reussie");
                return Health.down().withDetails(details).build();
            }
            return Health.up().withDetails(details).build();
        });
    }
}
