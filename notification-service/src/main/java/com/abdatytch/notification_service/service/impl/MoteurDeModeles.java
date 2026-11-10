package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.entities.SurchargeDeModele;
import com.abdatytch.notification_service.repositories.SurchargeDeModeleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
 *
 * <h2>DEUX COUCHES : le fichier, puis la base</h2>
 *
 * Le fichier est le <b>socle</b>, charge une fois au demarrage depuis le chemin
 * de classe. La table {@code surcharge_de_modele} ne porte que les corrections
 * posees a chaud, et prend le pas sur lui.
 *
 * <p>Trois raisons de garder le fichier au-dessous plutot que de tout mettre en
 * base :
 *
 * <ul>
 *   <li>une table vide se comporte exactement comme avant son existence, donc
 *       aucune migration n'a a recopier correctement les modeles sous peine de
 *       notifications sans texte ;</li>
 *   <li>les modeles sont sur le chemin des codes de verification : les faire
 *       dependre de la base rendrait une panne de base equivalente a « plus
 *       aucun code ne part » ;</li>
 *   <li>le fichier est dans git, donc relu et versionne ; la table est la voie
 *       de correction d'urgence.</li>
 * </ul>
 *
 * <h2>Le rendu reste SYNCHRONE, et c'est la contrainte qui decide de tout</h2>
 *
 * {@code rendre} est appele a deux endroits, tous deux synchrones : dans le
 * chemin de remise ({@code ServiceDeNotificationImpl}) et dans le rendu de la
 * <b>boite de reception</b> ({@code NotificationController}).
 *
 * <p>Ce second appelant tranche la question. La boite de reception rend une
 * PAGE de notifications, une par une, dans un mapper. Une lecture en base a
 * chaque rendu y ferait une requete par notification affichee — le defaut N+1,
 * sur l'ecran le plus consulte du service. Et dans le chemin de remise, une
 * notification {@code CRITIQUE} viserait quatre canaux, donc quatre lectures du
 * meme modele.
 *
 * <p>Les surcharges sont donc tenues en memoire, dans un instantane remplace
 * d'un bloc. Un {@link AtomicReference} vers une carte immuable, et non une
 * carte mutable : un rendu en cours ne peut pas observer un instantane a moitie
 * remplace.
 *
 * <h2>Un seul mecanisme de rechargement</h2>
 *
 * Le rechargement est <b>periodique</b>, et c'est la l'unique mecanisme de
 * convergence : chaque instance finit par voir une correction sans qu'un
 * exploitant ait a agir sur chacune. Une ecriture par l'API d'administration
 * recharge en plus immediatement l'instance qui l'a recue — ce n'est pas un
 * second mecanisme, c'est le meme, declenche tot, pour que l'exploitant
 * constate son changement tout de suite.
 *
 * <p>Pas de {@code @RefreshScope} sur cette classe, et c'est <b>obligatoire</b>
 * : une tache {@code @Scheduled} est enregistree une seule fois, au demarrage,
 * et n'est jamais reenregistree sur le mandataire recree par un rafraichissement
 * — la tache cesserait donc de s'executer. La regle est gardee par
 * {@code RefreshScopeSansPlanificationTest} dans platform-commons. Le delai est
 * par consequent lu au demarrage et ne se modifie pas a chaud ; en revanche le
 * CONTENU des modeles, lui, est bien modifiable a chaud — c'est le besoin.
 */
@Component
public class MoteurDeModeles {

    private static final Logger log = LoggerFactory.getLogger(MoteurDeModeles.class);

    /**
     * Les canaux pour lesquels l'absence de variante NE retombe PAS sur le texte
     * commun.
     *
     * <p>Le SMS seul, et pour une raison de cout : le texte commun est ecrit
     * pour le courriel. L'envoyer par SMS le fait tronquer par l'operateur, ou
     * facturer en plusieurs messages — sans que rien ne le signale. Mieux vaut
     * un refus visible dans le compte rendu.
     *
     * <p>Un ensemble et non un {@code == SMS} : le jour ou un canal borne
     * s'ajoute — un message push limite en longueur, par exemple — la regle est
     * a un seul endroit. Et son nom dit la regle, ce qu'une comparaison en ligne
     * ne fait pas.
     */
    private static final Set<CanalDeNotification> CANAUX_SANS_REPLI =
            Set.of(CanalDeNotification.SMS);

    /** Le socle : le fichier du chemin de classe. Jamais modifie apres le demarrage. */
    private final Properties socle = new Properties();

    private final SurchargeDeModeleRepository surcharges;

    /**
     * L'instantane des surcharges.
     *
     * <p>Vide jusqu'au premier rechargement, qui a lieu des le demarrage. Entre
     * les deux, le socle sert seul : c'est la degradation voulue, et non une
     * fenetre a refermer. L'inverse — attendre la base avant d'accepter de
     * rendre — ferait dependre le demarrage du service d'une dependance, ce que
     * la politique de ce depot refuse.
     */
    private final AtomicReference<Map<String, String>> instantane =
            new AtomicReference<>(Map.of());

    /**
     * L'instant de la derniere lecture REUSSIE des surcharges.
     *
     * <h3>Pourquoi cet horodatage existe</h3>
     *
     * {@code rechargerMaintenant} degrade en silence : si la base est
     * injoignable, l'instantane precedent reste en vigueur et la methode rend
     * sa taille comme si de rien n'etait. C'est la bonne degradation — le
     * service continue de rendre des notifications — mais elle rend la panne
     * INVISIBLE de l'exterieur : le nombre de surcharges ne bouge pas, et
     * aucune metrique ne distingue « rien n'a change » de « plus rien ne se
     * lit ».
     *
     * <p>Consequence concrete : une correction de texte poussee par
     * {@code PUT /admin/modeles} n'arriverait jamais, et l'exploitant qui l'a
     * poussee verrait sa valeur dans la base sans comprendre pourquoi les
     * messages ne changent pas.
     *
     * <p>{@code null} tant qu'aucune lecture n'a abouti : c'est distinct de
     * « lu il y a longtemps », et les deux meritent une alerte differente.
     */
    private final AtomicReference<java.time.Instant> derniereLecture =
            new AtomicReference<>(null);

    public MoteurDeModeles(@Value("classpath:modeles-notification.properties") Resource fichier,
                           SurchargeDeModeleRepository surcharges) {
        this.surcharges = surcharges;
        try (var lecteur = new InputStreamReader(fichier.getInputStream(), StandardCharsets.UTF_8)) {
            socle.load(lecteur);
            log.info("{} cles de modeles chargees depuis le fichier", socle.size());
        } catch (IOException absent) {
            // Le demarrage n'est PAS bloque — politique du projet. Mais chaque
            // rendu echouera, et c'est dit une fois, fort, au lieu d'etre
            // decouvert notification par notification.
            log.error("modeles-notification.properties illisible : AUCUNE "
                    + "notification ne pourra etre rendue, sauf celles dont la "
                    + "cle est surchargee en base ({})", absent.getMessage());
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

    /**
     * La valeur la plus specifique disponible.
     *
     * <h3>L'ordre : la SPECIFICITE d'abord, la couche ensuite</h3>
     *
     * Pour chaque cle candidate — d'abord celle du canal, puis celle du repli —
     * la surcharge est consultee avant le socle. Et non l'inverse : toutes les
     * surcharges, puis tout le socle.
     *
     * <p>Cette difference n'est pas theorique. Le fichier porte
     * {@code securite.code-de-verification.sms.corps}, une variante COURTE
     * ecrite pour tenir en 160 caracteres. Si un exploitant corrige seulement le
     * texte generique {@code securite.code-de-verification.corps} — ecrit pour
     * le courriel, donc long :
     *
     * <ul>
     *   <li>dans l'ordre retenu, le SMS continue d'utiliser sa variante courte.
     *       La correction ne touche que ce qu'elle visait ;</li>
     *   <li>dans l'ordre inverse, la surcharge generique passerait DEVANT la
     *       variante SMS du fichier. Le SMS partirait tronque, ou compterait
     *       pour plusieurs credits — sans que rien ne le signale.</li>
     * </ul>
     *
     * <p>Autrement dit : une variante de canal est plus specifique qu'une
     * couche. Pour changer aussi le SMS, il faut surcharger la cle du SMS — et
     * {@code GET /admin/modeles} liste justement les cles effectives, variantes
     * de canal comprises, pour que cela se voie au lieu de se deviner.
     *
     * <h3>LE SMS NE REPLIE PAS, et c'est un garde-fou de depense</h3>
     *
     * Pour tous les autres canaux, l'absence de variante retombe sur le texte
     * commun. Pour le SMS, non : sans variante {@code .sms}, le modele est
     * declare introuvable et rien ne part.
     *
     * <p>C'est une CORRECTION. Avec le repli, un modele sans variante courte
     * partait par SMS avec le corps ecrit pour le courriel — plusieurs centaines
     * de caracteres — donc tronque par l'operateur, ou facture en plusieurs
     * messages. Silencieusement, et sur le canal qui coute.
     *
     * <p>Le commentaire de {@code compte.bienvenue} dans le fichier de modeles
     * affirmait justement que « un canal sans modele produit un refus », et
     * c'etait faux : le repli l'en empechait. Ce controle rend l'affirmation
     * vraie au lieu de la corriger a la baisse.
     *
     * <p>Trois modeles sont concernes aujourd'hui — {@code compte.bienvenue},
     * {@code quota.seuil-atteint} et {@code securite.nouvelle-connexion} — et
     * pour les trois, refuser est l'intention ecrite. Donner une variante SMS a
     * l'un d'eux ne demande pas de livraison : {@code PUT /admin/modeles} suffit.
     */
    private String valeur(String modele, CanalDeNotification canal, String partie) {
        String avecCanal = modele + "." + canal.name().toLowerCase(Locale.ROOT) + "." + partie;
        String resolue = effective(avecCanal);
        if (resolue != null) {
            return resolue;
        }
        if (CANAUX_SANS_REPLI.contains(canal)) {
            return null;
        }
        return effective(modele + "." + partie);
    }

    /** Une cle exacte : la surcharge si elle existe, le socle sinon. */
    private String effective(String cle) {
        String surchargee = instantane.get().get(cle);
        return surchargee != null ? surchargee : socle.getProperty(cle);
    }

    /**
     * Relit les surcharges et remplace l'instantane.
     *
     * <h3>Une panne de base NE VIDE PAS les surcharges</h3>
     *
     * L'instantane precedent est conserve en cas d'erreur. Le remplacer par du
     * vide ferait revenir en silence aux textes du fichier — donc annulerait
     * une correction d'urgence au moment ou la base hoquette, et la
     * retablirait au rechargement suivant. Un texte qui oscille est pire qu'un
     * texte perime.
     *
     * <h3>{@code Flux.defer} et non l'appel direct</h3>
     *
     * Un appel direct a {@code findAll()} qui leverait de facon SYNCHRONE —
     * acquisition de connexion impossible, pool epuise — ferait echapper
     * l'exception de la chaine, donc passerait a cote de l'{@code onErrorResume}
     * ci-dessous. La promesse « une panne conserve l'instantane precedent » ne
     * tiendrait alors que pour les erreurs asynchrones, c'est-a-dire pas pour la
     * panne la plus probable.
     *
     * <p>Cela compte d'autant plus que la tache planifiee part a
     * {@code initialDelay = 0}, donc pendant l'initialisation du contexte : le
     * moment ou une dependance a le plus de chances de ne pas etre prete.
     *
     * @return le nombre de surcharges en vigueur apres l'operation
     */
    public Mono<Integer> rechargerMaintenant() {
        return reactor.core.publisher.Flux.defer(surcharges::findAll)
                .collect(Collectors.toUnmodifiableMap(
                        SurchargeDeModele::getCle, SurchargeDeModele::getValeur))
                .doOnNext(lues -> {
                    instantane.set(lues);
                    derniereLecture.set(java.time.Instant.now());
                    log.debug("{} surcharges de modeles en vigueur", lues.size());
                })
                .map(Map::size)
                .onErrorResume(panne -> {
                    log.error("Lecture des surcharges de modeles impossible, "
                            + "les {} surcharges precedentes restent en vigueur : {}",
                            instantane.get().size(), panne.getMessage());
                    return Mono.just(instantane.get().size());
                });
    }

    /**
     * Le rechargement periodique.
     *
     * <p>{@code initialDelay} a zero : le premier passage a lieu au demarrage,
     * ce qui evite un second chemin de chargement initial a cote de celui-ci.
     * Un seul mecanisme, donc une seule chose a verifier.
     *
     * <p>{@code subscribe} explicite, avec son gestionnaire d'erreur : un
     * {@code Mono} non souscrit ne s'execute pas, et ce depot a deja trouve des
     * chaines reactives construites puis abandonnees. L'erreur est deja traitee
     * dans {@code rechargerMaintenant}, le gestionnaire ici ne couvre que
     * l'imprevu.
     */
    @Scheduled(fixedDelayString = "${notification.modeles.rechargement-ms:60000}",
            initialDelay = 0)
    public void rechargerPeriodiquement() {
        rechargerMaintenant().subscribe(
                nombre -> { },
                imprevu -> log.error("Rechargement des modeles interrompu : {}",
                        imprevu.getMessage()));
    }

    /** Les surcharges en vigueur, pour l'API d'administration. */
    /**
     * L'instant de la derniere lecture reussie, ou {@code null}.
     *
     * <p>Lu par {@code NotificationMetrics.suivreMoteurDeModeles} et par
     * l'indicateur de sante. Aucune des deux ne recalcule l'age : une seule
     * source, et le moteur est le seul a savoir.
     */
    public java.time.Instant derniereLectureReussie() {
        return derniereLecture.get();
    }

    public Map<String, String> surchargesEnVigueur() {
        return instantane.get();
    }

    /** Les cles du fichier, pour l'API d'administration. */
    public Set<String> clesDuSocle() {
        return socle.stringPropertyNames();
    }

    /**
     * Le texte du fichier pour une cle exacte, sans tenir compte des surcharges.
     *
     * <p>Expose pour que l'API d'administration montre ce qui reprendrait effet
     * si l'on retirait une surcharge. Elle le lit ICI et ne recharge pas le
     * fichier de son cote : deux lecteurs du meme fichier finissent par ne plus
     * dire la meme chose, et c'est le moteur qui fait foi puisque c'est lui qui
     * rend.
     */
    public String valeurDuSocle(String cle) {
        return socle.getProperty(cle);
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
     * <p>Cela compte doublement depuis que les textes sont modifiables a chaud :
     * un gabarit surcharge vient d'une table, et non plus seulement d'un fichier
     * relu en revue de code.
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
