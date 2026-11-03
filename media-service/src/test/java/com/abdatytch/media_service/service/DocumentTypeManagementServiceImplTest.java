package com.abdatytch.media_service.service;

import com.abdatytch.media_service.dto.request.CreateDocumentTypeRequest;
import com.abdatytch.media_service.dto.response.DocumentRoleAssignmentResponse;
import com.abdatytch.media_service.entity.DocumentRoleRequirement;
import com.abdatytch.media_service.entity.DocumentTypeConfig;
import com.abdatytch.media_service.enums.DocumentCategory;
import com.abdatytch.media_service.enums.UserRole;
import com.abdatytch.media_service.exception.MediaNotFoundException;
import com.abdatytch.media_service.repository.DocumentRoleRequirementRepository;
import com.abdatytch.media_service.repository.DocumentTypeConfigRepository;
import com.abdatytch.media_service.service.impl.DocumentTypeManagementServiceImpl;
import com.abdatytch.media_service.service.impl.DocumentTypeValidationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L'administration du catalogue des types de documents.
 *
 * <h2>Pourquoi ce test existe</h2>
 *
 * Douze endpoints d'administration ont été ajoutés pour que le catalogue des
 * types de documents vive en base plutôt que dans une énumération compilée.
 * Ils n'avaient <b>aucun test</b> — ni ici, ni au niveau du contrôleur.
 *
 * <p>Or ce sont les endpoints qui décident <b>qui peut déposer quoi</b> sur le
 * service qui détient les pièces d'identité. Une erreur y est une erreur
 * d'autorisation, pas un défaut d'affichage.
 *
 * <h2>Ce qu'il fixe en priorité</h2>
 *
 * Les propriétés dont la violation serait silencieuse : qu'une suppression
 * n'efface pas, qu'un code ne se réécrive pas, qu'un {@code null} ne se
 * transforme pas en {@code false}, et surtout la conséquence réelle du retrait
 * de la dernière affectation de rôle.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Administration des types de documents")
class DocumentTypeManagementServiceImplTest {

    private static final String ADMIN = "admin-1";
    private static final String CODE = "DRIVER_LICENSE";
    private static final String ID = "type-1";

    @Mock private DocumentTypeConfigRepository typeRepository;
    @Mock private DocumentRoleRequirementRepository affectationRepository;

    private DocumentTypeManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DocumentTypeManagementServiceImpl(typeRepository, affectationRepository);

        when(typeRepository.save(any(DocumentTypeConfig.class)))
                .thenAnswer(i -> {
                    DocumentTypeConfig c = i.getArgument(0);
                    if (c.getId() == null) {
                        c.setId(ID);
                    }
                    return Mono.just(c);
                });
        when(affectationRepository.save(any(DocumentRoleRequirement.class)))
                .thenAnswer(i -> Mono.just(i.getArgument(0)));
    }

    private DocumentTypeConfig typeExistant() {
        DocumentTypeConfig config = DocumentTypeConfig.builder()
                .code(CODE)
                .displayName("Permis de conduire")
                .category(DocumentCategory.VEHICLE)
                .isActive(true)
                .build();
        config.setId(ID);
        return config;
    }

    private CreateDocumentTypeRequest demande() {
        return CreateDocumentTypeRequest.builder()
                .code(CODE)
                .displayName("Permis de conduire")
                .category(DocumentCategory.VEHICLE)
                .build();
    }

    // ==================================================================
    @Nested
    @DisplayName("Creation")
    class Creation {

        @Test
        @DisplayName("un type cree est actif et porte l'identifiant de l'administrateur")
        void typeCreeActif() {
            when(typeRepository.existsByCode(CODE)).thenReturn(Mono.just(false));

            StepVerifier.create(service.createDocumentType(demande(), ADMIN))
                    .assertNext(reponse -> {
                        assertThat(reponse.getCode()).isEqualTo(CODE);
                        assertThat(reponse.getIsActive()).isTrue();
                        assertThat(reponse.getCreatedBy()).isEqualTo(ADMIN);
                    })
                    .verifyComplete();
        }

        /**
         * Un code déjà pris est refusé <b>avant</b> tout enregistrement.
         *
         * <p>Le code nomme le chemin de stockage MinIO
         * ({@code users/{id}/documents/{code}/{empreinte}}). Deux types portant
         * le même code mêleraient leurs documents dans le même préfixe.
         */
        @Test
        @DisplayName("un code deja pris est refuse, et rien n'est enregistre")
        void codeDejaPrisRefuse() {
            when(typeRepository.existsByCode(CODE)).thenReturn(Mono.just(true));

            StepVerifier.create(service.createDocumentType(demande(), ADMIN))
                    .expectError(IllegalArgumentException.class)
                    .verify();

            verify(typeRepository, never()).save(any(DocumentTypeConfig.class));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Modification")
    class Modification {

        /**
         * Le code n'est <b>pas</b> modifiable.
         *
         * <p>{@code updateDocumentType} ne réécrit pas le code, même si la
         * requête en porte un autre. C'est la propriété qui protège les
         * documents déjà stockés : le code est dans leur chemin MinIO, donc le
         * renommer les rendrait introuvables sans aucune erreur.
         */
        @Test
        @DisplayName("la modification ne reecrit jamais le code")
        void codeNonModifiable() {
            when(typeRepository.findById(ID)).thenReturn(Mono.just(typeExistant()));

            CreateDocumentTypeRequest avecAutreCode = CreateDocumentTypeRequest.builder()
                    .code("CODE_DIFFERENT")
                    .displayName("Libelle modifie")
                    .category(DocumentCategory.VEHICLE)
                    .build();

            StepVerifier.create(service.updateDocumentType(ID, avecAutreCode, ADMIN))
                    .assertNext(reponse -> {
                        assertThat(reponse.getCode()).isEqualTo(CODE);
                        assertThat(reponse.getDisplayName()).isEqualTo("Libelle modifie");
                    })
                    .verifyComplete();

            ArgumentCaptor<DocumentTypeConfig> enregistre =
                    ArgumentCaptor.forClass(DocumentTypeConfig.class);
            verify(typeRepository).save(enregistre.capture());
            assertThat(enregistre.getValue().getCode()).isEqualTo(CODE);
            assertThat(enregistre.getValue().getLastModifiedBy()).isEqualTo(ADMIN);
        }

        @Test
        @DisplayName("un identifiant inconnu est refuse")
        void identifiantInconnu() {
            when(typeRepository.findById("absent")).thenReturn(Mono.empty());

            StepVerifier.create(service.updateDocumentType("absent", demande(), ADMIN))
                    .expectError(MediaNotFoundException.class)
                    .verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Suppression")
    class Suppression {

        /**
         * Supprimer, c'est désactiver.
         *
         * <p>La ligne reste, {@code isActive} passe à faux. Les documents déjà
         * déposés sous ce type gardent donc un type résoluble, et la piste
         * d'audit reste lisible. C'est la même règle que l'effacement RGPD de
         * ce dépôt : effacer n'est pas supprimer la ligne.
         */
        @Test
        @DisplayName("la suppression desactive, elle n'efface pas la ligne")
        void suppressionEstDesactivation() {
            when(typeRepository.findById(ID)).thenReturn(Mono.just(typeExistant()));

            StepVerifier.create(service.deleteDocumentType(ID, ADMIN)).verifyComplete();

            ArgumentCaptor<DocumentTypeConfig> enregistre =
                    ArgumentCaptor.forClass(DocumentTypeConfig.class);
            verify(typeRepository).save(enregistre.capture());
            assertThat(enregistre.getValue().getIsActive()).isFalse();
            assertThat(enregistre.getValue().getLastModifiedBy()).isEqualTo(ADMIN);

            verify(typeRepository, never()).deleteById(anyString());
        }

        @Test
        @DisplayName("supprimer un type inconnu est refuse")
        void suppressionTypeInconnu() {
            when(typeRepository.findById("absent")).thenReturn(Mono.empty());

            StepVerifier.create(service.deleteDocumentType("absent", ADMIN))
                    .expectError(MediaNotFoundException.class)
                    .verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Affectation a un role")
    class Affectation {

        /**
         * {@code mandatory} absent vaut <b>facultatif</b>, pas {@code null}.
         *
         * <p>Le pipeline d'envoi lit cette valeur pour renseigner
         * {@code isMandatory} sur le document. Un {@code null} y produirait un
         * suivi de complétion faux, sans erreur visible.
         */
        @Test
        @DisplayName("une affectation sans mandatory est creee facultative, jamais nulle")
        void mandatoryAbsentVautFaux() {
            when(typeRepository.findById(ID)).thenReturn(Mono.just(typeExistant()));
            when(affectationRepository.existsByDocumentTypeCodeAndUserRole(CODE, UserRole.DRIVER))
                    .thenReturn(Mono.just(false));

            StepVerifier.create(service.assignDocumentToRole(ID, UserRole.DRIVER, null, ADMIN))
                    .assertNext(reponse -> {
                        assertThat(reponse.getMandatory()).isFalse();
                        assertThat(reponse.getDocumentTypeCode()).isEqualTo(CODE);
                        assertThat(reponse.getUserRole()).isEqualTo(UserRole.DRIVER);
                        assertThat(reponse.getIsActive()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("une affectation en doublon est refusee, et rien n'est enregistre")
        void doublonRefuse() {
            when(typeRepository.findById(ID)).thenReturn(Mono.just(typeExistant()));
            when(affectationRepository.existsByDocumentTypeCodeAndUserRole(CODE, UserRole.DRIVER))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.assignDocumentToRole(ID, UserRole.DRIVER, true, ADMIN))
                    .expectError(IllegalArgumentException.class)
                    .verify();

            verify(affectationRepository, never()).save(any(DocumentRoleRequirement.class));
        }

        @Test
        @DisplayName("affecter un type inconnu est refuse")
        void typeInconnu() {
            when(typeRepository.findById("absent")).thenReturn(Mono.empty());

            StepVerifier.create(service.assignDocumentToRole("absent", UserRole.DRIVER, true, ADMIN))
                    .expectError(MediaNotFoundException.class)
                    .verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Mise a jour d'une affectation")
    class MiseAJourAffectation {

        private DocumentRoleRequirement affectationExistante() {
            return DocumentRoleRequirement.builder()
                    .documentTypeCode(CODE)
                    .userRole(UserRole.DRIVER)
                    .mandatory(true)
                    .displayOrder(3)
                    .isActive(true)
                    .build();
        }

        /**
         * Un paramètre absent <b>conserve</b> la valeur en place.
         *
         * <p>C'est ce qui distingue une mise à jour partielle d'un écrasement.
         * Si {@code mandatory == null} devenait {@code false}, modifier
         * seulement l'ordre d'affichage rendrait facultatif un document
         * obligatoire — et personne ne le verrait avant qu'un dossier
         * incomplet soit accepté.
         */
        @Test
        @DisplayName("un parametre absent conserve la valeur existante")
        void parametreAbsentConserve() {
            when(affectationRepository.findByDocumentTypeCodeAndUserRole(CODE, UserRole.DRIVER))
                    .thenReturn(Mono.just(affectationExistante()));
            when(typeRepository.findByCode(CODE)).thenReturn(Mono.just(typeExistant()));

            StepVerifier.create(service.updateRoleAssignment(CODE, UserRole.DRIVER, null, 7, ADMIN))
                    .assertNext(reponse -> {
                        assertThat(reponse.getMandatory()).isTrue();
                        assertThat(reponse.getDisplayOrder()).isEqualTo(7);
                    })
                    .verifyComplete();
        }

        /**
         * Le piège du {@code Mono} vide, corrigé.
         *
         * <p>Quand l'affectation existe mais que le type n'est plus au
         * catalogue, {@code findByCode} rend un {@code Mono} vide. Sans
         * {@code defaultIfEmpty}, il vidait <b>toute</b> la chaîne : l'écriture
         * avait pourtant eu lieu, et l'appelant recevait un 200 au corps vide,
         * donc concluait à un échec et pouvait réessayer.
         */
        @Test
        @DisplayName("un type absent du catalogue ne fait plus disparaitre la reponse")
        void typeAbsentNeVidePasLaReponse() {
            when(affectationRepository.findByDocumentTypeCodeAndUserRole(CODE, UserRole.DRIVER))
                    .thenReturn(Mono.just(affectationExistante()));
            when(typeRepository.findByCode(CODE)).thenReturn(Mono.empty());

            StepVerifier.create(service.updateRoleAssignment(CODE, UserRole.DRIVER, false, null, ADMIN))
                    .assertNext(reponse -> {
                        assertThat(reponse.getMandatory()).isFalse();
                        assertThat(reponse.getDocumentTypeName()).isEqualTo(CODE);
                    })
                    .verifyComplete();

            verify(affectationRepository).save(any(DocumentRoleRequirement.class));
        }

        @Test
        @DisplayName("une affectation inconnue est refusee")
        void affectationInconnue() {
            when(affectationRepository.findByDocumentTypeCodeAndUserRole(CODE, UserRole.MERCHANT))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.updateRoleAssignment(CODE, UserRole.MERCHANT, true, 1, ADMIN))
                    .expectError(MediaNotFoundException.class)
                    .verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Retrait d'un role, et ce qu'il ouvre")
    class RetraitDeRole {

        /**
         * Retirer la <b>dernière</b> affectation active est désormais refusé.
         *
         * <h3>L'inversion que ce refus ferme</h3>
         *
         * La règle « un type sans affectation active est universel » a été
         * retenue délibérément lors de la migration. Combinée à
         * {@code removeDocumentFromRole}, elle produisait une inversion :
         * l'action « retirer ce document du rôle CHAUFFEUR », appliquée à la
         * dernière affectation, ouvrait le dépôt à <b>tous les rôles</b> au
         * lieu de le fermer. Le geste le plus restrictif de l'interface
         * produisait l'élargissement maximal.
         *
         * <p>Ce test attestait cette inversion ; il atteste maintenant sa
         * fermeture. C'est bien lui qui a changé en premier, comme son ancienne
         * version l'annonçait.
         *
         * <p>Aucune des deux règles n'est renversée : un type sans affectation
         * reste universel, mais on ne peut plus <b>atteindre</b> cet état par
         * retrait. Et rien n'est supprimé : la vérification est faite avant
         * l'appel au dépôt.
         */
        @Test
        @DisplayName("retirer la derniere affectation active est refuse")
        void retraitDeLaDerniereAffectationRefuse() {
            when(affectationRepository.findByDocumentTypeCode(CODE)).thenReturn(
                    Flux.just(DocumentRoleRequirement.builder()
                            .documentTypeCode(CODE)
                            .userRole(UserRole.DRIVER)
                            .mandatory(true)
                            .displayOrder(0)
                            .isActive(true)
                            .build()));

            StepVerifier.create(service.removeDocumentFromRole(CODE, UserRole.DRIVER, ADMIN))
                    .expectError(IllegalArgumentException.class)
                    .verify();

            verify(affectationRepository, never())
                    .deleteByDocumentTypeCodeAndUserRole(anyString(), any(UserRole.class));
        }

        /**
         * Le refus indique la sortie : désactiver le type.
         *
         * <p>Un refus qui ne nomme pas l'issue se contourne par le chemin le
         * plus court — ici, recréer l'affectation ailleurs pour pouvoir retirer
         * celle-ci. Le message doit donc désigner la désactivation, qui ferme
         * réellement le type puisque {@code resoudre} vérifie {@code isActive}.
         */
        @Test
        @DisplayName("le refus indique de desactiver le type")
        void leRefusIndiqueLaSortie() {
            when(affectationRepository.findByDocumentTypeCode(CODE)).thenReturn(
                    Flux.just(DocumentRoleRequirement.builder()
                            .documentTypeCode(CODE).userRole(UserRole.DRIVER)
                            .mandatory(true).displayOrder(0).isActive(true).build()));

            StepVerifier.create(service.removeDocumentFromRole(CODE, UserRole.DRIVER, ADMIN))
                    .expectErrorSatisfies(erreur -> assertThat(erreur.getMessage())
                            .contains("TOUS les rôles")
                            .contains("désactiver"))
                    .verify();
        }

        /**
         * Tant qu'une autre affectation active subsiste, le retrait passe.
         *
         * <p>C'est la contrepartie indispensable : le refus doit porter sur la
         * <b>dernière</b> affectation, pas rendre les retraits impossibles.
         */
        @Test
        @DisplayName("le retrait passe tant qu'une autre affectation active subsiste")
        void retraitPasseSiUneAutreSubsiste() {
            when(affectationRepository.findByDocumentTypeCode(CODE)).thenReturn(
                    Flux.just(
                            DocumentRoleRequirement.builder()
                                    .documentTypeCode(CODE).userRole(UserRole.DRIVER)
                                    .mandatory(true).displayOrder(0).isActive(true).build(),
                            DocumentRoleRequirement.builder()
                                    .documentTypeCode(CODE).userRole(UserRole.MERCHANT)
                                    .mandatory(false).displayOrder(1).isActive(true).build()));
            when(affectationRepository.deleteByDocumentTypeCodeAndUserRole(CODE, UserRole.DRIVER))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.removeDocumentFromRole(CODE, UserRole.DRIVER, ADMIN))
                    .verifyComplete();

            verify(affectationRepository)
                    .deleteByDocumentTypeCodeAndUserRole(CODE, UserRole.DRIVER);
        }

        /**
         * Retirer une affectation déjà inactive reste possible.
         *
         * <p>Elle ne compte pas dans les actives, donc son retrait ne change
         * pas la résolution : le garde-fou ne doit pas s'y opposer, sinon il
         * bloquerait un nettoyage sans effet sur l'autorisation.
         */
        @Test
        @DisplayName("retirer une affectation deja inactive reste possible")
        void retraitDUneAffectationInactivePasse() {
            when(affectationRepository.findByDocumentTypeCode(CODE)).thenReturn(
                    Flux.just(
                            DocumentRoleRequirement.builder()
                                    .documentTypeCode(CODE).userRole(UserRole.DRIVER)
                                    .mandatory(true).displayOrder(0).isActive(true).build(),
                            DocumentRoleRequirement.builder()
                                    .documentTypeCode(CODE).userRole(UserRole.MERCHANT)
                                    .mandatory(false).displayOrder(1).isActive(false).build()));
            when(affectationRepository.deleteByDocumentTypeCodeAndUserRole(CODE, UserRole.MERCHANT))
                    .thenReturn(Mono.empty());

            StepVerifier.create(service.removeDocumentFromRole(CODE, UserRole.MERCHANT, ADMIN))
                    .verifyComplete();

            verify(affectationRepository)
                    .deleteByDocumentTypeCodeAndUserRole(CODE, UserRole.MERCHANT);
        }

        /**
         * Tant qu'une autre affectation subsiste, le type reste restreint.
         *
         * <p>C'est la contrepartie du test précédent : l'ouverture n'a lieu
         * qu'au retrait de la <b>dernière</b> affectation.
         */
        @Test
        @DisplayName("une affectation restante maintient la restriction")
        void uneAffectationRestanteMaintientLaRestriction() {
            when(typeRepository.findByCode(CODE)).thenReturn(Mono.just(typeExistant()));
            when(affectationRepository.findByDocumentTypeCode(CODE)).thenReturn(
                    Flux.just(DocumentRoleRequirement.builder()
                            .documentTypeCode(CODE)
                            .userRole(UserRole.DRIVER)
                            .mandatory(true)
                            .displayOrder(0)
                            .isActive(true)
                            .build()));

            DocumentTypeValidationServiceImpl resolution =
                    new DocumentTypeValidationServiceImpl(typeRepository, affectationRepository);

            StepVerifier.create(resolution.resoudre(CODE, UserRole.MERCHANT))
                    .expectError(IllegalArgumentException.class)
                    .verify();

            StepVerifier.create(resolution.resoudre(CODE, UserRole.DRIVER))
                    .assertNext(type -> assertThat(type.obligatoire()).isTrue())
                    .verifyComplete();
        }

        /**
         * Une affectation <b>inactive</b> ne retient pas la restriction.
         *
         * <p>La règle porte sur les affectations actives. Une affectation
         * désactivée ne compte donc pas, et le type redevient universel — même
         * conséquence que la suppression, par un autre chemin.
         */
        @Test
        @DisplayName("une affectation inactive ne retient pas la restriction")
        void affectationInactiveNeRetientRien() {
            when(typeRepository.findByCode(CODE)).thenReturn(Mono.just(typeExistant()));
            when(affectationRepository.findByDocumentTypeCode(CODE)).thenReturn(
                    Flux.just(DocumentRoleRequirement.builder()
                            .documentTypeCode(CODE)
                            .userRole(UserRole.DRIVER)
                            .mandatory(true)
                            .displayOrder(0)
                            .isActive(false)
                            .build()));

            DocumentTypeValidationServiceImpl resolution =
                    new DocumentTypeValidationServiceImpl(typeRepository, affectationRepository);

            StepVerifier.create(resolution.resoudre(CODE, UserRole.MERCHANT))
                    .expectNextCount(1)
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Consultations")
    class Consultations {

        private DocumentRoleRequirement affectation(String code, int ordre) {
            return DocumentRoleRequirement.builder()
                    .documentTypeCode(code)
                    .userRole(UserRole.DRIVER)
                    .mandatory(true)
                    .displayOrder(ordre)
                    .isActive(true)
                    .build();
        }

        /**
         * Les documents d'un rôle sortent dans l'ordre d'affichage.
         *
         * <p>L'ordre est une donnée d'interface : c'est la séquence dans
         * laquelle on demande ses pièces à quelqu'un qui s'inscrit. Le
         * {@code flatMap} qui résout chaque libellé ne garantit <b>aucun</b>
         * ordre par lui-même — d'où le tri explicite, que ce test protège.
         */
        @Test
        @DisplayName("les documents d'un role sortent tries par ordre d'affichage")
        void triParOrdreDAffichage() {
            when(affectationRepository.findByUserRoleAndIsActiveTrue(UserRole.DRIVER, true))
                    .thenReturn(Flux.just(affectation("TROISIEME", 3), affectation("PREMIER", 1),
                            affectation("DEUXIEME", 2)));
            when(typeRepository.findByCode(anyString())).thenAnswer(i -> {
                DocumentTypeConfig c = typeExistant();
                c.setCode(i.getArgument(0));
                c.setDisplayName("Libelle " + i.getArgument(0));
                return Mono.just(c);
            });

            List<DocumentRoleAssignmentResponse> resultat =
                    service.getDocumentsByRole(UserRole.DRIVER).collectList().block();

            assertThat(resultat).extracting(DocumentRoleAssignmentResponse::getDisplayOrder)
                    .containsExactly(1, 2, 3);
        }

        /**
         * Obligatoires et facultatifs interrogent le dépôt différemment.
         *
         * <p>Les deux méthodes appellent la même requête avec un drapeau
         * opposé. Les confondre ferait réclamer comme obligatoires des pièces
         * facultatives — ou l'inverse, bien pire : laisser passer un dossier
         * incomplet.
         */
        @Test
        @DisplayName("obligatoires et facultatifs n'interrogent pas le meme ensemble")
        void obligatoiresEtFacultatifsDistincts() {
            when(affectationRepository.findByUserRoleAndMandatoryAndIsActiveTrue(
                    UserRole.DRIVER, true, true)).thenReturn(Flux.just(affectation(CODE, 0)));
            when(affectationRepository.findByUserRoleAndMandatoryAndIsActiveTrue(
                    UserRole.DRIVER, false, true)).thenReturn(Flux.empty());
            when(typeRepository.findByCode(CODE)).thenReturn(Mono.just(typeExistant()));

            StepVerifier.create(service.getMandatoryDocumentsByRole(UserRole.DRIVER))
                    .expectNextCount(1)
                    .verifyComplete();

            StepVerifier.create(service.getOptionalDocumentsByRole(UserRole.DRIVER))
                    .verifyComplete();

            verify(affectationRepository).findByUserRoleAndMandatoryAndIsActiveTrue(
                    UserRole.DRIVER, true, true);
            verify(affectationRepository).findByUserRoleAndMandatoryAndIsActiveTrue(
                    UserRole.DRIVER, false, true);
        }

        @Test
        @DisplayName("un code inconnu est refuse, il ne rend pas un Mono vide")
        void codeInconnuRefuse() {
            when(typeRepository.findByCode("ABSENT")).thenReturn(Mono.empty());

            StepVerifier.create(service.getDocumentTypeByCode("ABSENT"))
                    .expectError(MediaNotFoundException.class)
                    .verify();
        }

        @Test
        @DisplayName("seuls les types actifs sortent de la liste des actifs")
        void seulsLesActifs() {
            when(typeRepository.findByIsActiveTrue()).thenReturn(Flux.just(typeExistant()));

            StepVerifier.create(service.getActiveDocumentTypes())
                    .assertNext(reponse -> assertThat(reponse.getIsActive()).isTrue())
                    .verifyComplete();

            verify(typeRepository).findByIsActiveTrue();
            verify(typeRepository, never()).findAll();
        }
    }
}
