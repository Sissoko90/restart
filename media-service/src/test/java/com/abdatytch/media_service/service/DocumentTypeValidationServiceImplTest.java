package com.abdatytch.media_service.service;

import com.abdatytch.media_service.entity.DocumentRoleRequirement;
import com.abdatytch.media_service.entity.DocumentTypeConfig;
import com.abdatytch.media_service.enums.UserRole;
import com.abdatytch.media_service.repository.DocumentRoleRequirementRepository;
import com.abdatytch.media_service.repository.DocumentTypeConfigRepository;
import com.abdatytch.media_service.service.impl.DocumentTypeValidationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * La résolution d'un type de document contre le catalogue et le rôle.
 *
 * <h2>Ce que ce test garde</h2>
 *
 * C'est la seule logique <b>nouvelle</b> de la migration qui a sorti le
 * catalogue des types du code. Avant elle, {@code DocumentController} faisait
 * {@code DocumentType.valueOf(code)} : un type créé par
 * {@code POST /admin/document-types} s'écrivait en base et rendait <b>400</b> au
 * premier envoi. Cinq codes sur vingt-cinq et dix-sept figuraient dans les deux
 * catalogues — un chauffeur ne pouvait pas fournir sa pièce d'identité
 * obligatoire, un supermarché aucun de ses quatre documents.
 *
 * <h2>La décision que les assertions figent</h2>
 *
 * <b>Un type sans affectation active est universel.</b> C'est le choix le plus
 * lourd de la migration, et il reproduit {@code requiredForRoles.isEmpty()} de
 * l'énumération. L'inverse — « aucune affectation, donc personne » — rendrait
 * d'un coup inutilisables tous les types que le catalogue n'assigne pas, et le
 * seeder n'assigne {@code NATIONAL_ID} qu'à deux rôles sur douze.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentTypeValidationServiceImpl — resolution d'un type")
class DocumentTypeValidationServiceImplTest {

    private static final String CODE = "NATIONAL_ID";
    private static final String LIBELLE = "Pièce d'identité nationale";

    @Mock
    private DocumentTypeConfigRepository documentTypeConfigRepository;
    @Mock
    private DocumentRoleRequirementRepository roleRequirementRepository;

    @InjectMocks
    private DocumentTypeValidationServiceImpl service;

    @BeforeEach
    void setUp() {
        when(documentTypeConfigRepository.findByCode(CODE))
                .thenReturn(Mono.just(configActif(CODE, LIBELLE)));
        when(roleRequirementRepository.findByDocumentTypeCode(anyString()))
                .thenReturn(Flux.empty());
    }

    private static DocumentTypeConfig configActif(String code, String libelle) {
        return DocumentTypeConfig.builder()
                .code(code)
                .displayName(libelle)
                .isActive(true)
                .build();
    }

    private static DocumentRoleRequirement affectation(UserRole role, boolean obligatoire,
                                                       boolean active) {
        return DocumentRoleRequirement.builder()
                .documentTypeCode(CODE)
                .userRole(role)
                .mandatory(obligatoire)
                .isActive(active)
                .build();
    }

    // ==================================================================
    @Nested
    @DisplayName("Le defaut corrige : un type du catalogue est accepte")
    class TypeDuCatalogue {

        /**
         * Le test qui ferme le défaut d'origine.
         *
         * <p>Ce code n'existe dans <b>aucune</b> énumération : c'est exactement
         * le cas d'un type que l'administrateur vient de créer par l'API. Avant
         * la migration, l'envoi échouait en 400.
         */
        @Test
        @DisplayName("un code absent de toute enumeration est resolu s'il est au catalogue")
        void typeCreeParLApiEstAccepte() {
            String nouveau = "ATTESTATION_INVENTEE_PAR_UN_ADMIN";
            when(documentTypeConfigRepository.findByCode(nouveau))
                    .thenReturn(Mono.just(configActif(nouveau, "Attestation inventée")));

            StepVerifier.create(service.resoudre(nouveau, UserRole.DRIVER))
                    .assertNext(type -> {
                        assertThat(type.code()).isEqualTo(nouveau);
                        assertThat(type.libelle()).isEqualTo("Attestation inventée");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("le libelle du catalogue est rendu, pour que les refus soient lisibles")
        void libelleRendu() {
            StepVerifier.create(service.resoudre(CODE, UserRole.DRIVER))
                    .assertNext(type -> assertThat(type.libelle()).isEqualTo(LIBELLE))
                    .verifyComplete();
        }

        @Test
        @DisplayName("un code inconnu du catalogue est refuse")
        void codeInconnuRefuse() {
            when(documentTypeConfigRepository.findByCode("INEXISTANT"))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.resoudre("INEXISTANT", UserRole.DRIVER))
                    .expectError(IllegalArgumentException.class)
                    .verify();
        }

        @Test
        @DisplayName("un type desactive est refuse")
        void typeDesactiveRefuse() {
            DocumentTypeConfig inactif = configActif(CODE, LIBELLE);
            inactif.setIsActive(false);
            when(documentTypeConfigRepository.findByCode(CODE)).thenReturn(Mono.just(inactif));

            StepVerifier.create(service.resoudre(CODE, UserRole.DRIVER))
                    .expectError(IllegalArgumentException.class)
                    .verify();
        }

        @Test
        @DisplayName("un code vide est refuse sans toucher au catalogue")
        void codeVideRefuse() {
            StepVerifier.create(service.resoudre("   ", UserRole.DRIVER))
                    .expectError(IllegalArgumentException.class)
                    .verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un type sans affectation active est UNIVERSEL")
    class Universalite {

        /**
         * La décision centrale, figée.
         *
         * <p>Sans cette règle, le seeder n'assignant {@code NATIONAL_ID} qu'à
         * DRIVER et DELIVERY, aucun des dix autres rôles ne pourrait fournir de
         * pièce d'identité.
         */
        @Test
        @DisplayName("aucune affectation : tout role peut fournir le document")
        void aucuneAffectationDoncOuvertATous() {
            StepVerifier.create(service.resoudre(CODE, UserRole.MERCHANT))
                    .assertNext(type -> assertThat(type.obligatoire())
                            .withFailMessage("un type universel ne doit pas etre obligatoire "
                                    + "par defaut : personne ne l'a exige de ce role")
                            .isFalse())
                    .verifyComplete();
        }

        /**
         * Désactiver la dernière affectation <b>rouvre</b> le type.
         *
         * <p>C'est cohérent avec « restreindre est un acte explicite », et cela
         * évite qu'une désactivation bloque silencieusement un dépôt.
         */
        @Test
        @DisplayName("des affectations toutes inactives rouvrent le type")
        void affectationsInactivesRouvrent() {
            when(roleRequirementRepository.findByDocumentTypeCode(CODE))
                    .thenReturn(Flux.just(affectation(UserRole.DRIVER, true, false)));

            StepVerifier.create(service.resoudre(CODE, UserRole.MERCHANT))
                    .assertNext(type -> assertThat(type.obligatoire()).isFalse())
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Avec des affectations, le role est exige")
    class RestrictionParRole {

        @Test
        @DisplayName("un role affecte obtient le type, avec son caractere obligatoire")
        void roleAffecteObtientLeType() {
            when(roleRequirementRepository.findByDocumentTypeCode(CODE))
                    .thenReturn(Flux.just(affectation(UserRole.DRIVER, true, true)));

            StepVerifier.create(service.resoudre(CODE, UserRole.DRIVER))
                    .assertNext(type -> assertThat(type.obligatoire())
                            .withFailMessage("le caractere obligatoire de l'affectation "
                                    + "n'est pas repris : c'est lui qui alimente "
                                    + "MediaFile.isMandatory")
                            .isTrue())
                    .verifyComplete();
        }

        @Test
        @DisplayName("une affectation optionnelle rend obligatoire=false")
        void affectationOptionnelle() {
            when(roleRequirementRepository.findByDocumentTypeCode(CODE))
                    .thenReturn(Flux.just(affectation(UserRole.DRIVER, false, true)));

            StepVerifier.create(service.resoudre(CODE, UserRole.DRIVER))
                    .assertNext(type -> assertThat(type.obligatoire()).isFalse())
                    .verifyComplete();
        }

        /**
         * Le contrôle d'applicabilité, qui vivait dans l'énumération.
         *
         * <p>Il décidait par {@code requiredForRoles.contains(role)}. Sans ce
         * test, un permis de conduire deviendrait fournissable par un
         * commerçant.
         */
        @Test
        @DisplayName("un role NON affecte est refuse quand d'autres le sont")
        void roleNonAffecteRefuse() {
            when(roleRequirementRepository.findByDocumentTypeCode(CODE))
                    .thenReturn(Flux.just(
                            affectation(UserRole.DRIVER, true, true),
                            affectation(UserRole.DELIVERY, true, true)));

            StepVerifier.create(service.resoudre(CODE, UserRole.MERCHANT))
                    .expectError(IllegalArgumentException.class)
                    .verify();
        }

        /**
         * Un rôle ajouté récemment aux énumérations n'est pas un cas spécial.
         *
         * <p>{@code SUPER_ADMIN} manquait à la copie de ce module, ce qui faisait
         * lever {@code UserRole.valueOf}. La parité est rétablie ; ce test
         * constate qu'il se comporte comme les autres.
         */
        @Test
        @DisplayName("SUPER_ADMIN est traite comme tout autre role")
        void superAdminEstUnRoleOrdinaire() {
            when(roleRequirementRepository.findByDocumentTypeCode(CODE))
                    .thenReturn(Flux.just(affectation(UserRole.SUPER_ADMIN, false, true)));

            StepVerifier.create(service.resoudre(CODE, UserRole.SUPER_ADMIN))
                    .assertNext(type -> assertThat(type.code()).isEqualTo(CODE))
                    .verifyComplete();
        }
    }
}
