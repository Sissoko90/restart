package com.abdatytch.order_service.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le GRAPHE de la machine a etats, verifie comme un graphe.
 *
 * <h2>Ce que ce test attrape et qu'une relecture ne voit pas</h2>
 *
 * Un etat ajoute a l'enumeration sans etre branche. Il compile, il se
 * serialise, la contrainte de base l'accepte — et aucune commande ne peut y
 * entrer ni en sortir. Le defaut ne se manifeste que le jour ou quelqu'un
 * cherche pourquoi une commande ne bouge pas.
 *
 * <p>Ces proprietes sont verifiees sur TOUS les etats, et non sur une liste
 * ecrite a la main : une liste a la main oublie precisement l'etat qu'on vient
 * d'ajouter.
 */
@DisplayName("Le graphe des etats de commande")
class MachineDeCommandeGrapheTest {

    /** Les etats depuis lesquels rien ne part, et c'est voulu. */
    private static final Set<StatutCommande> TERMINAUX_ATTENDUS = EnumSet.of(
            StatutCommande.CLOTUREE, StatutCommande.REMBOURSEE, StatutCommande.ANNULEE);

    /** Le seul etat d'entree : une commande naît a payer. */
    private static final StatutCommande ENTREE = StatutCommande.A_PAYER;

    @Nested
    @DisplayName("Aucun etat n'est orphelin")
    class PasDOrphelin {

        /**
         * Tout etat sauf l'entree doit etre ATTEIGNABLE depuis un autre. Un
         * etat inatteignable est du code mort qui a l'air vivant : il figure
         * dans la contrainte de base, dans les reponses JSON, dans les
         * etiquettes de metriques — et aucune commande ne l'atteint jamais.
         */
        @ParameterizedTest
        @EnumSource(StatutCommande.class)
        @DisplayName("chaque etat a un predecesseur, sauf l'etat d'entree")
        void toutEtatEstAtteignable(StatutCommande etat) {
            if (etat == ENTREE) {
                return;
            }
            boolean atteignable = Arrays.stream(StatutCommande.values())
                    .anyMatch(depuis -> depuis != etat && depuis.peutAllerVers(etat));

            assertThat(atteignable)
                    .withFailMessage("""
                            L'etat %s n'est atteignable depuis AUCUN autre.

                            Il figure dans la contrainte de base, dans les reponses \
                            JSON et dans les etiquettes de metriques, et aucune \
                            commande ne l'atteindra jamais. Soit le brancher, soit \
                            le retirer.""".formatted(etat))
                    .isTrue();
        }

        /**
         * Tout etat non terminal doit MENER quelque part. Une commande qui y
         * entre et n'en sort pas est bloquee pour toujours — et le symptome est
         * un acheteur qui attend sans que rien ne soit en panne.
         */
        @ParameterizedTest
        @EnumSource(StatutCommande.class)
        @DisplayName("chaque etat non terminal mene quelque part")
        void toutEtatMeneQuelquePart(StatutCommande etat) {
            if (TERMINAUX_ATTENDUS.contains(etat)) {
                assertThat(etat.estTerminal())
                        .withFailMessage("%s est declare terminal ici mais a des "
                                + "successeurs : %s", etat, etat.suivantsPossibles())
                        .isTrue();
                return;
            }
            assertThat(etat.suivantsPossibles())
                    .withFailMessage("""
                            L'etat %s ne mene nulle part et n'est pas declare \
                            terminal.

                            Une commande qui y entre est bloquee pour toujours, et \
                            le symptome est un acheteur qui attend sans que rien ne \
                            soit en panne.""".formatted(etat))
                    .isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Les decisions du graphe, epinglees")
    class DecisionsEpinglees {

        /**
         * Une fois expediee, plus d'annulation : le colis est parti. Le recours
         * est le retour, qui n'a pas les memes consequences sur le stock ni sur
         * l'argent.
         */
        @Test
        @DisplayName("on n'annule plus une commande expediee ou livree")
        void plusDAnnulationApresExpedition() {
            for (StatutCommande apresDepart : EnumSet.of(StatutCommande.EXPEDIEE,
                    StatutCommande.LIVREE, StatutCommande.RETOUR_EN_COURS)) {
                assertThat(apresDepart.peutAllerVers(StatutCommande.ANNULEE))
                        .withFailMessage("""
                                %s peut etre ANNULEE, alors que le colis est parti.

                                Annuler et retourner n'ont pas les memes \
                                consequences : l'annulation rend le stock sans que \
                                l'article soit revenu.""".formatted(apresDepart))
                        .isFalse();
            }
        }

        /**
         * Un retour refuse renvoie la commande a LIVREE : elle reprend son
         * cours, sa fenetre court toujours, et la boutique finira par etre
         * payee. Sans ce retour en arriere, un refus la laisserait bloquee en
         * RETOUR_EN_COURS, donc jamais cloturable, donc jamais payee.
         */
        @Test
        @DisplayName("un retour refuse ramene la commande a LIVREE")
        void retourRefuseRamneALivree() {
            assertThat(StatutCommande.RETOUR_EN_COURS.peutAllerVers(StatutCommande.LIVREE))
                    .withFailMessage("""
                            RETOUR_EN_COURS ne peut pas revenir a LIVREE.

                            Un retour refuse laisserait la commande bloquee : jamais \
                            cloturable, donc la boutique n'est JAMAIS payee.""")
                    .isTrue();
        }

        /**
         * Les trois etats terminaux ne rouvrent pas. CLOTUREE est le plus
         * sensible : la somme y est payable, et un retour en arriere apres un
         * versement produit un versement en double.
         */
        @ParameterizedTest
        @EnumSource(value = StatutCommande.class, names = {"CLOTUREE", "REMBOURSEE", "ANNULEE"})
        @DisplayName("un etat terminal ne rouvre jamais")
        void terminalNeRouvrePas(StatutCommande terminal) {
            assertThat(terminal.suivantsPossibles())
                    .withFailMessage("""
                            %s a des successeurs : %s.

                            CLOTUREE signifie que la somme est payable a la \
                            boutique. Un etat qui revient en arriere apres un \
                            versement produit un versement en DOUBLE.""".formatted(
                            terminal, terminal.suivantsPossibles()))
                    .isEmpty();
        }

        /**
         * {@code argentEncaisse} decide si une annulation implique un
         * remboursement. Se tromper ici fait soit rembourser quelqu'un qui n'a
         * rien paye, soit garder l'argent de quelqu'un qu'on a annule.
         */
        @Test
        @DisplayName("l'argent n'est encaisse que hors de A_PAYER et ANNULEE")
        void argentEncaisseEstJuste() {
            assertThat(StatutCommande.A_PAYER.argentEncaisse())
                    .withFailMessage("A_PAYER serait encaisse : une annulation "
                            + "declencherait un remboursement sans encaissement.")
                    .isFalse();
            assertThat(StatutCommande.ANNULEE.argentEncaisse()).isFalse();

            for (StatutCommande apresPaiement : EnumSet.of(StatutCommande.PAYEE,
                    StatutCommande.PREPAREE, StatutCommande.EXPEDIEE,
                    StatutCommande.LIVREE, StatutCommande.CLOTUREE,
                    StatutCommande.RETOUR_EN_COURS, StatutCommande.REMBOURSEE)) {
                assertThat(apresPaiement.argentEncaisse())
                        .withFailMessage("%s n'est pas considere comme encaisse : une "
                                + "annulation garderait l'argent de l'acheteur.",
                                apresPaiement)
                        .isTrue();
            }
        }

        /**
         * Le stock est rendu sur annulation et sur remboursement, et sur rien
         * d'autre. Le rendre a tort fait vendre deux fois le meme exemplaire ;
         * ne pas le rendre le perd pour la boutique.
         */
        @Test
        @DisplayName("le stock est rendu sur ANNULEE et REMBOURSEE, et nulle part ailleurs")
        void stockRenduAuBonMoment() {
            for (StatutCommande etat : StatutCommande.values()) {
                boolean attendu = etat == StatutCommande.ANNULEE
                        || etat == StatutCommande.REMBOURSEE;
                assertThat(etat.rendLeStock())
                        .withFailMessage("""
                                %s : rendLeStock() vaut %s, attendu %s.

                                Rendre a tort fait vendre deux fois le meme \
                                exemplaire ; ne pas rendre le perd pour la \
                                boutique.""".formatted(etat, etat.rendLeStock(), attendu))
                        .isEqualTo(attendu);
            }
        }
    }

    @Nested
    @DisplayName("Le graphe des retours")
    class GrapheDesRetours {

        /**
         * RECU est OBLIGATOIRE entre ACCEPTE et REMBOURSE. C'est la decision la
         * plus importante du module : accepter un retour et avoir recu
         * l'article sont deux faits differents, et les confondre fait
         * rembourser un colis jamais revenu.
         */
        @Test
        @DisplayName("on ne rembourse pas sans etre passe par RECU")
        void remboursementExigeLaReception() {
            assertThat(StatutRetour.ACCEPTE.peutAllerVers(StatutRetour.REMBOURSE))
                    .withFailMessage("""
                            ACCEPTE mene directement a REMBOURSE.

                            Accepter un retour et avoir RECU l'article sont deux \
                            faits differents. Les confondre fait rembourser un colis \
                            jamais revenu, et c'est industrialisable : on demande, on \
                            obtient l'accord, on n'envoie rien.""")
                    .isFalse();
            assertThat(StatutRetour.DEMANDE.peutAllerVers(StatutRetour.REMBOURSE)).isFalse();
            assertThat(StatutRetour.RECU.peutAllerVers(StatutRetour.REMBOURSE)).isTrue();
        }

        @ParameterizedTest
        @EnumSource(StatutRetour.class)
        @DisplayName("chaque etat de retour est atteignable, sauf DEMANDE")
        void toutEtatDeRetourEstAtteignable(StatutRetour etat) {
            if (etat == StatutRetour.DEMANDE) {
                return;
            }
            assertThat(Arrays.stream(StatutRetour.values())
                    .anyMatch(d -> d != etat && d.peutAllerVers(etat)))
                    .withFailMessage("L'etat de retour %s n'est atteignable depuis "
                            + "aucun autre.", etat)
                    .isTrue();
        }

        /**
         * Seuls DEMANDE, ACCEPTE et RECU occupent la place unique ouverte sur
         * une commande. Un REFUSE ou un REMBOURSE qui la bloquerait empecherait
         * l'acheteur de redemander apres un refus mal compris — et l'index
         * unique partiel de la base applique exactement cette liste.
         */
        @Test
        @DisplayName("seuls trois etats de retour sont OUVERTS")
        void etatsOuvertsExacts() {
            for (StatutRetour etat : StatutRetour.values()) {
                boolean attendu = etat == StatutRetour.DEMANDE
                        || etat == StatutRetour.ACCEPTE
                        || etat == StatutRetour.RECU;
                assertThat(etat.estOuvert())
                        .withFailMessage("""
                                %s : estOuvert() vaut %s, attendu %s.

                                Cette liste doit correspondre a l'index partiel \
                                retour_un_seul_ouvert_par_commande. Si elle s'en \
                                ecarte, soit la base refuse un retour legitime, soit \
                                elle en laisse passer deux.""".formatted(
                                etat, etat.estOuvert(), attendu))
                        .isEqualTo(attendu);
            }
        }
    }
}
