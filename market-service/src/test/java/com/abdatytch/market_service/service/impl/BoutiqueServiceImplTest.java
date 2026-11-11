package com.abdatytch.market_service.service.impl;

import com.abdatytch.market_service.cache.CacheDePortee;
import com.abdatytch.market_service.client.ClientProfilUtilisateur;
import com.abdatytch.market_service.dto.request.CreationBoutiqueRequest;
import com.abdatytch.market_service.dto.request.PieceJustificativeRequest;
import com.abdatytch.market_service.entities.Boutique;
import com.abdatytch.market_service.entities.BoutiqueDocument;
import com.abdatytch.market_service.enums.StatutBoutique;
import com.abdatytch.market_service.enums.TypeDocumentBoutique;
import com.abdatytch.market_service.exception.MarketException;
import com.abdatytch.market_service.exception.RefusExplique;
import com.abdatytch.market_service.repository.BoutiqueDocumentRepository;
import com.abdatytch.market_service.repository.BoutiqueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le cycle de vie d'une boutique.
 *
 * <p>Ces tests portent sur des REGLES METIER, pas sur du passage de donnees :
 * une personne ne tient qu'une boutique, le pays vient d'ailleurs, et modifier
 * les pieces d'un dossier valide renvoie en moderation.
 */
@DisplayName("BoutiqueServiceImpl")
class BoutiqueServiceImplTest {

    private static final String MOI = "u-1";

    private BoutiqueRepository boutiques;
    private BoutiqueDocumentRepository pieces;
    private ClientProfilUtilisateur profils;
    private CacheDePortee cache;
    private BoutiqueServiceImpl service;

    @BeforeEach
    void preparer() {
        boutiques = mock(BoutiqueRepository.class);
        pieces = mock(BoutiqueDocumentRepository.class);
        profils = mock(ClientProfilUtilisateur.class);
        cache = mock(CacheDePortee.class);
        when(cache.evincer(anyString())).thenReturn(Mono.empty());
        when(boutiques.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        service = new BoutiqueServiceImpl(boutiques, pieces, profils, cache);
    }

    private static CreationBoutiqueRequest demande(String rccm, String nif) {
        return new CreationBoutiqueRequest("Sankadi", "Gros et detail", rccm, nif,
                "Bamako", "Rue 100", "+22300000000");
    }

    private static Boutique existante(StatutBoutique statut) {
        Boutique boutique = new Boutique();
        boutique.setId(UUID.randomUUID());
        boutique.setAuthUserId(MOI);
        boutique.setNom("Sankadi");
        boutique.setStatut(statut);
        boutique.setRccm("R1");
        boutique.setNif("N1");
        boutique.setPays("ML");
        return boutique;
    }

    // ==================================================================
    @Nested
    @DisplayName("L'ouverture")
    class Ouverture {

        @Test
        @DisplayName("lit le pays chez user-service et NAIT en attente")
        void paysEtStatut() {
            when(boutiques.findByAuthUserId(MOI)).thenReturn(Mono.empty());
            when(profils.pour(MOI)).thenReturn(
                    Mono.just(new ClientProfilUtilisateur.Profil("ML", "USER")));

            StepVerifier.create(service.ouvrir(MOI, demande("R1", "N1")))
                    .assertNext(reponse -> {
                        assertThat(reponse.pays()).isEqualTo("ML");
                        assertThat(reponse.statut()).isEqualTo(StatutBoutique.EN_ATTENTE);
                    })
                    .verifyComplete();
        }

        /**
         * L'identifiant est produit par la BASE.
         *
         * <p>Un {@code setId()} avant l'ecriture ferait emettre un UPDATE pour
         * une ligne jamais inseree, et le {@code save()} serait silencieusement
         * sans effet.
         */
        @Test
        @DisplayName("ne pose JAMAIS l'identifiant avant l'ecriture")
        void identifiantLaisseALaBase() {
            when(boutiques.findByAuthUserId(MOI)).thenReturn(Mono.empty());
            when(profils.pour(MOI)).thenReturn(
                    Mono.just(new ClientProfilUtilisateur.Profil("ML", "USER")));

            service.ouvrir(MOI, demande("R1", "N1")).block();

            var capture = ArgumentCaptor.forClass(Boutique.class);
            verify(boutiques).save(capture.capture());
            assertThat(capture.getValue().getId())
                    .withFailMessage("""
                            L'identifiant a ete pose avant l'ecriture.

                            R2DBC decide qu'une ligne est neuve a l'identifiant nul. \
                            Un identifiant deja pose fait emettre un UPDATE pour une \
                            ligne jamais inseree, et le save() est sans effet.""")
                    .isNull();
        }

        @Test
        @DisplayName("refuse une SECONDE boutique, avec un motif")
        void uneSeuleParCompte() {
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(existante(StatutBoutique.APPROUVEE)));

            StepVerifier.create(service.ouvrir(MOI, demande("R1", "N1")))
                    .expectErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(RefusExplique.class)
                            .hasMessageContaining("deja une boutique"))
                    .verify();
            verify(boutiques, never()).save(any());
        }

        /**
         * Sans pays, un article ne serait visible de PERSONNE.
         *
         * <p>La clause compare {@code p.pays = :pays} pour tout particulier.
         * Ouvrir une boutique sans pays creerait une vitrine dont rien ne
         * sortirait jamais, sans que son proprietaire comprenne pourquoi.
         */
        @Test
        @DisplayName("refuse sans pays connu, et dit quoi faire")
        void refuseSansPays() {
            when(boutiques.findByAuthUserId(MOI)).thenReturn(Mono.empty());
            when(profils.pour(MOI)).thenReturn(Mono.just(ClientProfilUtilisateur.INCONNU));

            StepVerifier.create(service.ouvrir(MOI, demande("R1", "N1")))
                    .expectErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(RefusExplique.class)
                            .hasMessageContaining("Completez votre profil"))
                    .verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La modification")
    class Modification {

        /**
         * LE CONTOURNEMENT QUE CETTE REGLE FERME.
         *
         * <p>Sans elle, il suffirait de faire valider un dossier anodin puis de
         * remplacer le RCCM par un autre.
         */
        @Test
        @DisplayName("changer le RCCM d'une boutique APPROUVEE la renvoie en moderation")
        void rccmModifieRenvoieEnModeration() {
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(existante(StatutBoutique.APPROUVEE)));

            StepVerifier.create(service.modifier(MOI, demande("R2", "N1")))
                    .assertNext(reponse -> assertThat(reponse.statut())
                            .isEqualTo(StatutBoutique.EN_ATTENTE))
                    .verifyComplete();
            verify(cache).evincer(MOI);
        }

        @Test
        @DisplayName("changer le NIF produit le meme effet")
        void nifModifieAussi() {
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(existante(StatutBoutique.APPROUVEE)));

            StepVerifier.create(service.modifier(MOI, demande("R1", "N2")))
                    .assertNext(reponse -> assertThat(reponse.statut())
                            .isEqualTo(StatutBoutique.EN_ATTENTE))
                    .verifyComplete();
        }

        @Test
        @DisplayName("changer le NOM seul ne la renvoie PAS en moderation")
        void nomSeulNeRenvoiePas() {
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(existante(StatutBoutique.APPROUVEE)));

            StepVerifier.create(service.modifier(MOI, demande("R1", "N1")))
                    .assertNext(reponse -> assertThat(reponse.statut())
                            .withFailMessage("""
                                    Un simple changement de nom renvoie en moderation.

                                    La file se remplirait de dossiers deja valides, et \
                                    l'equipe finirait par approuver sans regarder.""")
                            .isEqualTo(StatutBoutique.APPROUVEE))
                    .verifyComplete();
            verify(cache, never()).evincer(anyString());
        }

        @Test
        @DisplayName("refuse de modifier une boutique SUSPENDUE")
        void suspendueNonModifiable() {
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(existante(StatutBoutique.SUSPENDUE)));

            StepVerifier.create(service.modifier(MOI, demande("R2", "N2")))
                    .expectError(RefusExplique.class)
                    .verify();
        }

        @Test
        @DisplayName("sans boutique : introuvable")
        void sansBoutique() {
            when(boutiques.findByAuthUserId(MOI)).thenReturn(Mono.empty());
            StepVerifier.create(service.modifier(MOI, demande("R1", "N1")))
                    .expectError(MarketException.class)
                    .verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les pieces justificatives")
    class Pieces {

        @Test
        @DisplayName("une piece ajoutee a une boutique APPROUVEE la renvoie en moderation")
        void pieceRenvoieEnModeration() {
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(existante(StatutBoutique.APPROUVEE)));
            when(pieces.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));

            StepVerifier.create(service.rattacherUnePiece(MOI,
                            new PieceJustificativeRequest(TypeDocumentBoutique.RCCM, "doc-1")))
                    .verifyComplete();

            var capture = ArgumentCaptor.forClass(Boutique.class);
            verify(boutiques).save(capture.capture());
            assertThat(capture.getValue().getStatut()).isEqualTo(StatutBoutique.EN_ATTENTE);
            verify(cache).evincer(MOI);
        }

        @Test
        @DisplayName("une piece ajoutee a un dossier EN ATTENTE ne change rien")
        void dossierEnAttenteInchange() {
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(existante(StatutBoutique.EN_ATTENTE)));
            when(pieces.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));

            StepVerifier.create(service.rattacherUnePiece(MOI,
                            new PieceJustificativeRequest(TypeDocumentBoutique.NIF, "doc-2")))
                    .verifyComplete();
            verify(boutiques, never()).save(any());
        }

        @Test
        @DisplayName("ne garde que l'identifiant du document, jamais le fichier")
        void seulementLIdentifiant() {
            when(boutiques.findByAuthUserId(MOI))
                    .thenReturn(Mono.just(existante(StatutBoutique.EN_ATTENTE)));
            when(pieces.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));

            service.rattacherUnePiece(MOI,
                    new PieceJustificativeRequest(TypeDocumentBoutique.RCCM, "doc-9")).block();

            var capture = ArgumentCaptor.forClass(BoutiqueDocument.class);
            verify(pieces).save(capture.capture());
            assertThat(capture.getValue().getDocumentId()).isEqualTo("doc-9");
            assertThat(capture.getValue().getType()).isEqualTo(TypeDocumentBoutique.RCCM);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La vitrine publique")
    class Vitrine {

        @Test
        @DisplayName("n'expose ni RCCM ni NIF")
        void sansDossierAdministratif() {
            Boutique approuvee = existante(StatutBoutique.APPROUVEE);
            when(boutiques.parIdentifiantSiApprouvee(approuvee.getId()))
                    .thenReturn(Mono.just(approuvee));

            StepVerifier.create(service.vitrine(approuvee.getId()))
                    .assertNext(reponse -> {
                        assertThat(reponse.rccm())
                                .withFailMessage("Le RCCM est expose sur la vitrine "
                                        + "publique : un acheteur n'a pas a lire le "
                                        + "dossier administratif d'un commercant.")
                                .isNull();
                        assertThat(reponse.nif()).isNull();
                        assertThat(reponse.motifDecision()).isNull();
                        assertThat(reponse.nom()).isEqualTo("Sankadi");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("une boutique non approuvee est INTROUVABLE, pas interdite")
        void nonApprouveeIntrouvable() {
            UUID id = UUID.randomUUID();
            when(boutiques.parIdentifiantSiApprouvee(id)).thenReturn(Mono.empty());

            StepVerifier.create(service.vitrine(id))
                    .expectErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(MarketException.class)
                            .hasMessageContaining("404"))
                    .verify();
        }
    }
}
