package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.dto.response.AccuseRecuResponse;
import com.abdatytch.auth_service.service.sms.IAccusesDeReceptionSms;
import com.abdatytch.auth_service.util.ChampsDAccuseSms;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Reçoit les accusés de réception d'AfrikSMS : le seul point d'entrée de ce
 * service appelé par un tiers.
 *
 * <h2>Pourquoi le corps est lu à la main, et non par {@code @RequestBody}</h2>
 *
 * {@code typeNotification} vaut 1 (POST) ou 2 (GET), et ce réglage vit chez
 * AfrikSMS. Un accusé peut donc arriver en GET avec des paramètres de requête,
 * ou en POST avec un corps JSON, un corps de formulaire, ou rien du tout.
 *
 * <p>La première version de ce contrôleur déclarait une méthode par forme, avec
 * {@code consumes} et {@code @RequestBody}. Mesuré par les tests : <b>deux des
 * quatre formes échouaient</b> — 415 sur un corps de formulaire, 500 sur un POST
 * sans corps. La négociation de contenu de Spring et le choix de la méthode se
 * combinaient d'une façon qu'aucune relecture ne révèle.
 *
 * <p>Pour un appel dont on ne maîtrise ni la méthode, ni le {@code Content-Type},
 * ni la présence d'un corps, le déterminisme vaut mieux que l'élégance : le
 * corps brut est lu une fois, puis analysé selon sa forme réelle. Deux méthodes,
 * un seul traitement, aucune négociation.
 *
 * <h2>Comment il s'authentifie, et pourquoi c'est la seule façon possible</h2>
 *
 * AfrikSMS ne signe rien et n'envoie aucun en-tête de notre choix : il appelle
 * l'URL qu'on lui a déclarée, telle quelle. Le seul secret partagé possible est
 * donc <b>dans l'URL</b> — c'est aussi ce que montre l'exemple de leur
 * documentation ({@code ?key=...}). Il apparaît par conséquent dans les journaux
 * d'accès du Gateway et chez AfrikSMS. Il n'ouvre rien d'autre que ce point
 * d'entrée et ne donne accès à aucune donnée : au pire, qui l'obtiendrait
 * pourrait inventer de faux accusés.
 *
 * <p>La comparaison est à <b>temps constant</b>, et un secret faux rend
 * <b>404</b>, jamais 403 : un 403 confirmerait l'existence du point d'entrée et
 * inviterait à chercher le secret. C'est la règle du dépôt pour tout refus.
 *
 * <h2>Pourquoi 200 même sur un accusé inexploitable</h2>
 *
 * Dès lors que le secret est bon, la réponse est 200, y compris si le
 * {@code resourceId} est inconnu. Un fournisseur qui reçoit une erreur
 * réessaie : répondre 4xx ou 5xx sur un accusé qu'on ne sait pas interpréter
 * déclencherait des reprises indéfinies pour une information déjà perdue.
 */
@RestController
@RequestMapping("/sms/accuses")
@Slf4j
public class AccuseDeReceptionSmsController {

    /** Au-delà, ce n'est pas un accusé de réception. */
    private static final int TAILLE_MAX_DU_CORPS = 8192;

    private static final String PARAMETRE_CLE = "cle";
    private static final String CHAMP_RESOURCE_ID = "resourceId";
    private static final String CHAMP_CODE = "code";

    private final IAccusesDeReceptionSms accuses;

    /**
     * Secret attendu dans la chaîne de requête.
     *
     * <p>Sans valeur par défaut utilisable : vide, <b>tout accusé est refusé</b>.
     * C'est l'inverse qui serait dangereux — un secret par défaut laisserait le
     * point d'entrée ouvert sur un déploiement où personne n'a pensé à le
     * renseigner.
     */
    private final String secretAttendu;

    public AccuseDeReceptionSmsController(
            IAccusesDeReceptionSms accuses,
            @Value("${sms.afriksms.notify-secret:}") String secretAttendu) {
        this.accuses = accuses;
        this.secretAttendu = secretAttendu == null ? "" : secretAttendu.trim();
    }

    /** Accusé en GET ({@code typeNotification = 2}) : tout est dans l'URL. */
    @GetMapping("/afriksms")
    public Mono<ResponseEntity<AccuseRecuResponse>> recevoirEnGet(
            ServerWebExchange exchange) {
        return traiter(exchange, Map.of());
    }

    /**
     * Accusé en POST ({@code typeNotification = 1}).
     *
     * <p>{@code consumes} n'est pas déclaré : le contrôleur accepte n'importe
     * quel {@code Content-Type}, y compris son absence. C'est la correction du
     * 415 et du 500 décrits dans la javadoc de la classe.
     */
    @PostMapping("/afriksms")
    public Mono<ResponseEntity<AccuseRecuResponse>> recevoirEnPost(
            ServerWebExchange exchange) {
        return corpsBrut(exchange)
                .map(corps -> ChampsDAccuseSms.depuis(corps, typeAnnonce(exchange)))
                // Un POST sans corps rend un Mono VIDE ; sans ce defaut, la
                // requete disparaitrait et AfrikSMS reessaierait. Pose sur le
                // Mono<Map>, et non sur le Mono<ResponseEntity> final.
                .defaultIfEmpty(Map.of())
                .flatMap(champs -> traiter(exchange, champs));
    }

    /** {@code Content-Type} declare, pour le seul journal du corps non reconnu. */
    private static String typeAnnonce(ServerWebExchange exchange) {
        MediaType type = exchange.getRequest().getHeaders().getContentType();
        return type == null ? null : type.toString();
    }

    /**
     * Lit le corps une seule fois, en texte, et borné.
     *
     * <p>La borne n'est pas une précaution de principe : ce point d'entrée est
     * public, et sans limite un corps de plusieurs mégaoctets serait assemblé en
     * mémoire avant même qu'on ait vérifié le secret.
     */
    private Mono<String> corpsBrut(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), TAILLE_MAX_DU_CORPS)
                .map(tampon -> {
                    try {
                        return tampon.toString(StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(tampon);
                    }
                })
                .onErrorResume(erreur -> {
                    log.warn(ServiceConstants.LOG_WARN_SMS_ACCUSE_CORPS_ILLISIBLE,
                            erreur.getClass().getSimpleName());
                    return Mono.just("");
                });
    }

    /**
     * Traitement commun : verifier le secret, puis deleguer l'interpretation.
     *
     * <p>Les parametres de requete servent de repli aux champs du corps : en
     * GET il n'y a que l'URL, et en POST certaines passerelles mettent les
     * champs dans les deux. Ce repli est propre a la requete, il reste donc ici.
     *
     * <p>L'analyse du corps est dans {@link ChampsDAccuseSms} et
     * l'interpretation de l'accuse dans {@link IAccusesDeReceptionSms} : ce
     * controleur ne garde que le transport et l'authentification.
     */
    private Mono<ResponseEntity<AccuseRecuResponse>> traiter(
            ServerWebExchange exchange, Map<String, String> champsDuCorps) {

        var parametres = exchange.getRequest().getQueryParams();

        if (!secretValide(parametres.getFirst(PARAMETRE_CLE))) {
            // Ni le secret recu ni le secret attendu ne sont journalises.
            log.warn(ServiceConstants.LOG_WARN_SMS_ACCUSE_REFUSE);
            return Mono.just(ResponseEntity.notFound().build());
        }

        String resourceId = champsDuCorps.containsKey(CHAMP_RESOURCE_ID)
                ? champsDuCorps.get(CHAMP_RESOURCE_ID)
                : parametres.getFirst(CHAMP_RESOURCE_ID);
        String code = champsDuCorps.containsKey(CHAMP_CODE)
                ? champsDuCorps.get(CHAMP_CODE)
                : parametres.getFirst(CHAMP_CODE);

        // Pose sur le Mono<Void> du service par `then` : `then` souscrit a
        // l'etape suivante meme sur une source vide, donc la reponse part dans
        // tous les cas — y compris sur un accuse inexploitable.
        return accuses.enregistrer(resourceId, code)
                .then(Mono.fromSupplier(AccuseRecuResponse::acquitte))
                .map(ResponseEntity::ok);
    }

    /**
     * Compare le secret à temps constant.
     *
     * <p>{@code String.equals} s'arrête au premier caractère différent : le
     * temps de réponse révèle alors combien de caractères de tête sont justes,
     * ce qui permet de reconstituer le secret octet par octet. Sur un point
     * d'entrée public et sans limitation de débit propre, c'est une attaque
     * praticable.
     *
     * <p>Un secret attendu vide refuse tout, y compris une clé vide : sinon un
     * déploiement où la propriété manque accepterait n'importe quel appel.
     */
    private boolean secretValide(String cle) {
        if (secretAttendu.isEmpty() || cle == null) {
            return false;
        }
        return MessageDigest.isEqual(
                cle.getBytes(StandardCharsets.UTF_8),
                secretAttendu.getBytes(StandardCharsets.UTF_8));
    }
}
