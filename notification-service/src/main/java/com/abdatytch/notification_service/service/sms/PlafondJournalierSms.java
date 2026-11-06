package com.abdatytch.notification_service.service.sms;

import com.abdatytch.notification_service.metrics.NotificationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Plafond du nombre de SMS envoyes dans une journee.
 *
 * <h2>Le trou que ce plafond ferme</h2>
 *
 * {@code SurveillanceDuSoldeSms} releve le solde chez le fournisseur <b>une
 * fois par jour</b> ({@code cron: 0 0 8 * * *}). Entre deux releves, rien ne
 * surveille la depense.
 *
 * <p>Et un credit epuise ne se signale pas de lui-meme : l'envoi repond
 * {@code 200} avec un code de refus dans le corps, donc sans erreur HTTP. Une
 * boucle fautive — un flux Kafka rejoue, un reessai mal borne, un jeu de
 * donnees de production charge par erreur — peut donc vider le solde en
 * quelques minutes, et le premier signe visible serait des utilisateurs qui ne
 * recoivent plus leur code.
 *
 * <p>Ce compteur transforme « le solde a disparu » en « N SMS depenses, puis des
 * refus, avec une alerte ». C'est un plafond de depense, pas une limite de
 * debit.
 *
 * <h2>Compte les envois ACCEPTES, pas les tentatives</h2>
 *
 * Ce qui coute est un message que le fournisseur a pris en charge. Compter les
 * tentatives aurait fait baisser le plafond a chaque panne de reseau —
 * c'est-a-dire couper les envois precisement quand ils recommencent a
 * fonctionner.
 *
 * <h2>Redis indisponible : on LAISSE PASSER</h2>
 *
 * Et c'est un choix assume, contre l'habitude de ce depot — ou un secret absent
 * refuse tout.
 *
 * <p>La raison est la consequence d'un refus : les SMS de ce service portent les
 * codes de verification et de connexion. Refuser faute de pouvoir lire un
 * compteur transformerait un hoquet Redis en impossibilite de se connecter pour
 * tout le monde. Le risque qu'on evite — depasser un plafond de depense pendant
 * une panne Redis — est tres inferieur a celui qu'on creerait.
 *
 * <p>Ce repli n'est pas silencieux, et c'est ce qui le rend acceptable : il
 * journalise en {@code ERROR}. Un repli qui masque sa propre panne serait le
 * defaut ; un repli qui l'annonce est une decision.
 *
 * <h2>Le compte n'est pas exact a l'unite, et il n'a pas a l'etre</h2>
 *
 * La lecture precede l'increment, donc plusieurs envois simultanes peuvent
 * franchir le plafond ensemble. Le depassement est borne par le nombre d'envois
 * en vol — quelques unites. Un plafond de depense n'a pas besoin d'etre exact a
 * l'unite ; le rendre exact demanderait un script atomique ou un verrou sur le
 * chemin de chaque code de verification, pour gagner « 503 au lieu de 505 ».
 */
@Service
@RefreshScope
public class PlafondJournalierSms {

    private static final Logger log = LoggerFactory.getLogger(PlafondJournalierSms.class);

    /**
     * Prefixe des cles de comptage.
     *
     * <p>Distinct de celui du registre des envois : deux usages, deux espaces de
     * noms, pour qu'un effacement de l'un ne touche pas l'autre.
     */
    static final String PREFIXE = "sms:compteur:";

    /**
     * Duree de vie d'un compteur journalier.
     *
     * <p>48 h et non 24 : la cle doit survivre a la journee qu'elle compte, quel
     * que soit le fuseau du serveur et l'heure a laquelle elle a ete creee. Une
     * cle qui expire avant la fin de sa journee remettrait le compteur a zero en
     * pleine journee — le plafond serait alors double sans que rien ne le dise.
     */
    private static final Duration DUREE_DE_VIE = Duration.ofHours(48);

    private final ReactiveRedisTemplate<String, String> redis;
    private final NotificationMetrics metriques;
    private final int plafond;

    public PlafondJournalierSms(
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redis,
            NotificationMetrics metriques,
            @Value("${sms.plafond-journalier:500}") int plafond) {
        this.redis = redis;
        this.metriques = metriques;
        this.plafond = plafond;
    }

    @jakarta.annotation.PostConstruct
    void annoncerLEtat() {
        if (plafond <= 0) {
            log.warn("sms.plafond-journalier = {} : AUCUN plafond de depense. "
                    + "Une boucle fautive peut vider le solde du fournisseur.", plafond);
        } else {
            log.info("Plafond de depense SMS : {} envois par jour", plafond);
        }
    }

    /** {@code true} si un envoi de plus reste sous le plafond. */
    public Mono<Boolean> resteDuCredit() {
        if (plafond <= 0) {
            // Plafond desactive explicitement. Pas de lecture Redis inutile sur
            // le chemin de chaque envoi.
            return Mono.just(true);
        }

        return redis.opsForValue().get(cleDuJour())
                .map(PlafondJournalierSms::enNombre)
                .defaultIfEmpty(0)
                .map(dejaEnvoyes -> dejaEnvoyes < plafond)
                .doOnNext(reste -> {
                    if (!reste) {
                        // Compte le REFUS, et non le franchissement.
                        //
                        // `compterUnEnvoi` journalise une fois, au moment ou le
                        // plafond est franchi. Ce compteur-ci s'incremente a
                        // chaque envoi effectivement refuse ensuite : c'est lui
                        // qui dit l'AMPLEUR — combien de personnes n'ont pas
                        // recu leur code — et non le seul instant du
                        // franchissement.
                        metriques.plafondSmsAtteint();
                    }
                })
                .onErrorResume(erreur -> {
                    // Voir la javadoc de classe : on laisse passer, et on le
                    // DIT. Le message de l'erreur peut porter l'adresse du
                    // serveur Redis, seul le nom de sa classe est journalise.
                    log.error("Plafond de depense SMS non verifiable ({}) : "
                            + "l'envoi est autorise sans controle",
                            erreur.getClass().getSimpleName());
                    return Mono.just(true);
                });
    }

    /** Enregistre un envoi que le fournisseur a accepte. */
    public Mono<Void> compterUnEnvoi() {
        if (plafond <= 0) {
            return Mono.empty();
        }

        String cle = cleDuJour();
        return redis.opsForValue().increment(cle)
                // L'expiration est posee a CHAQUE increment, et non seulement a
                // la creation : poser un TTL une seule fois obligerait a savoir
                // si la cle vient de naitre, ce que `increment` ne dit pas. Le
                // reposer est idempotent et coute une commande.
                .flatMap(total -> redis.expire(cle, DUREE_DE_VIE).thenReturn(total))
                .doOnNext(total -> {
                    if (total >= plafond) {
                        log.error("Plafond de depense SMS ATTEINT : {} envois "
                                + "aujourd'hui, plafond {}. Les envois suivants "
                                + "seront refuses.", total, plafond);
                    }
                })
                .onErrorResume(erreur -> {
                    // Un comptage rate ne doit pas faire echouer un envoi DEJA
                    // parti : le SMS est remis au fournisseur, et le signaler en
                    // echec ferait croire a l'appelant que rien n'est parti.
                    log.error("Comptage d'un envoi SMS impossible ({}) : "
                            + "le plafond du jour est sous-evalue",
                            erreur.getClass().getSimpleName());
                    return Mono.empty();
                })
                .then();
    }

    /**
     * La cle du jour, en date locale.
     *
     * <p>{@code LocalDate} et non un horodatage : le plafond est une regle de
     * gestion quotidienne, et il doit changer de periode au minuit de
     * l'exploitant.
     */
    private static String cleDuJour() {
        return PREFIXE + LocalDate.now();
    }

    /**
     * Lit le compteur, en traitant une valeur illisible comme un zero.
     *
     * <p>Une valeur corrompue ne doit pas lever : cela viderait le {@code Mono}
     * et le controle rendrait « plus de credit » alors que rien n'est connu.
     * Zero autorise l'envoi, ce qui est le repli coherent avec celui de Redis
     * indisponible.
     */
    private static int enNombre(String valeur) {
        try {
            return Integer.parseInt(valeur.trim());
        } catch (NumberFormatException illisible) {
            log.error("Compteur d'envois SMS illisible dans Redis : traite comme zero");
            return 0;
        }
    }
}
