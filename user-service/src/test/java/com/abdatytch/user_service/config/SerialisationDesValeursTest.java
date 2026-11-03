package com.abdatytch.user_service.config;

import com.abdatytch.user_service.dto.response.AuthUserDTO;
import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.enums.UserRole;
import com.abdatytch.user_service.enums.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ce qu'on met en cache, on doit pouvoir le relire.
 *
 * <h2>Le trou que ce test ferme</h2>
 *
 * Aucun test du module ne traversait le sérialiseur : {@code CacheDesComptesTest}
 * simule {@code ReactiveRedisTemplate} par Mockito, donc l'encodage réel n'était
 * jamais exercé. Or les deux écritures de cache du service avalent leur erreur
 * en WARN — délibérément, pour qu'une panne de Redis ne fasse pas échouer une
 * modification déjà enregistrée.
 *
 * <p>Les deux faits mis ensemble donnent le pire cas : un cache qui ne
 * fonctionne <b>pas du tout</b> resterait indétectable. Chaque lecture
 * repartirait en base et vers auth-service, chaque écriture journaliserait un
 * avertissement que personne ne lit, et tous les tests resteraient verts.
 *
 * <h2>Pourquoi passer par la configuration de production</h2>
 *
 * Le test appelle {@link ReactiveRedisConfig#contexteDeSerialisation()}, et ne
 * construit pas son propre sérialiseur. Une garde qui recopie ce qu'elle garde
 * resterait verte le jour où la production changerait de sérialiseur — ce qui
 * est exactement ce qui va arriver, {@code GenericJackson2JsonRedisSerializer}
 * étant déprécié pour suppression depuis spring-data-redis 4.0.
 */
@DisplayName("Serialisation des valeurs mises en cache")
class SerialisationDesValeursTest {

    private static final RedisSerializationContext.SerializationPair<Object> PAIRE =
            ReactiveRedisConfig.contexteDeSerialisation().getValueSerializationPair();

    /** Encode puis relit, par le même chemin que la production. */
    private static Object allerRetour(Object valeur) {
        ByteBuffer encode = PAIRE.write(valeur);
        return PAIRE.read(encode);
    }

    /**
     * L'identité, nouvelle valeur mise en cache par {@code ClientIdentiteAuth}.
     *
     * <p>Deux pièges s'y croisent : c'est un {@code record} — donc sans
     * constructeur sans argument ni mutateurs — et il porte deux
     * {@code LocalDateTime}, que Jackson ne sait traiter qu'avec le module
     * temporel enregistré.
     */
    @Test
    @DisplayName("un AuthUserDTO se relit a l'identique")
    void identiteRelue() {
        AuthUserDTO avant = new AuthUserDTO(
                "auth-123",
                "awa.diallo@example.ml",
                "+22370000000",
                "ML",
                "Awa",
                "Diallo",
                UserRole.USER,
                true,
                true,
                false,
                LocalDateTime.of(2026, 10, 30, 14, 5),
                LocalDateTime.of(2026, 1, 2, 9, 0));

        assertThat(allerRetour(avant))
                .withFailMessage("l'identite ne se relit pas : le cache de "
                        + "ClientIdentiteAuth est inoperant, et son echec est "
                        + "avale en WARN donc invisible")
                .isEqualTo(avant);
    }

    /**
     * La réponse composée, mise en cache depuis l'origine par
     * {@code CacheDesComptes}.
     *
     * <p>Ce cas n'est pas nouveau, et c'est bien pourquoi il mérite d'être
     * vérifié : s'il échoue, le cache des comptes n'a jamais fonctionné.
     */
    @Test
    @DisplayName("un UserDetailDTO se relit a l'identique")
    void detailRelu() {
        UserDetailDTO avant = new UserDetailDTO(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "auth-123",
                "awa.diallo@example.ml",
                "+22370000000",
                "ML",
                "Awa",
                "Diallo",
                LocalDate.of(1995, 3, 12),
                "http://passerelle/api/v1/files/auth-123/photo.jpg",
                UserStatus.ACTIVE,
                UserRole.USER,
                "Commercante a Bamako",
                "Rue 12, Badalabougou",
                "Bamako",
                "Mali",
                "BP 1234",
                Map.of("langue", "fr", "theme", "sombre"),
                LocalDateTime.of(2026, 1, 2, 9, 0),
                LocalDateTime.of(2026, 10, 30, 11, 30),
                LocalDateTime.of(2026, 10, 30, 14, 5));

        assertThat(allerRetour(avant))
                .withFailMessage("la reponse composee ne se relit pas : le cache "
                        + "des comptes n'a jamais fonctionne, et rien ne le disait")
                .isEqualTo(avant);
    }

    /**
     * Les deux types se distinguent après relecture.
     *
     * <p>Le sérialiseur est générique : il inscrit le type dans le document pour
     * savoir le reconstruire. Si cette inscription disparaissait, les deux
     * valeurs se reliraient en {@code LinkedHashMap} et le {@code cast} de
     * {@code lire} lèverait une {@code ClassCastException} — rattrapée en WARN,
     * donc invisible elle aussi.
     */
    @Test
    @DisplayName("le type est preserve, et non relu en carte generique")
    void typePreserve() {
        AuthUserDTO identite = new AuthUserDTO(
                "auth-123", null, null, null, null, null,
                null, false, false, false, null, null);

        assertThat(allerRetour(identite)).isInstanceOf(AuthUserDTO.class);
    }

    /**
     * Une classe hors de la liste blanche est <b>refusée</b>.
     *
     * <h2>Pourquoi cette assertion existe</h2>
     *
     * L'inscription du type est aussi une <b>instruction de construction</b> : à
     * la relecture, Jackson instancie la classe que le document nomme. Sans
     * restriction, quiconque peut écrire dans Redis choisit donc la classe que
     * ce service instanciera — c'est ce que l'option
     * {@code enableUnsafeDefaultTyping} autorise, et son nom ne mentait pas.
     *
     * <p>{@code ReactiveRedisConfig} emploie à la place une liste blanche. Sans
     * ce test, rien ne distinguerait une liste blanche qui protège d'une liste
     * blanche si large qu'elle ne protège de rien : les deux laissent passer nos
     * propres DTO, et c'est tout ce que vérifient les autres cas de ce fichier.
     *
     * <p>Le document est forgé en substituant le nom de classe dans un document
     * légitime, et la substitution est elle-même vérifiée — sinon un
     * {@code replace} devenu inopérant rendrait ce test vert sans rien prouver.
     */
    @Test
    @DisplayName("une classe hors liste blanche est REFUSEE a la relecture")
    void typeNonAutoriseRefuse() {
        AuthUserDTO legitime = new AuthUserDTO(
                "auth-123", null, null, null, null, null,
                null, false, false, false, null, null);

        String document = StandardCharsets.UTF_8.decode(PAIRE.write(legitime)).toString();
        String forge = document.replace(AuthUserDTO.class.getName(), "java.io.File");

        assertThat(forge)
                .withFailMessage("le nom de classe n'a pas ete substitue : le document "
                        + "ne porte pas le type sous la forme attendue, et ce test ne "
                        + "verifie donc rien. Document obtenu : %s", document)
                .isNotEqualTo(document);

        assertThatThrownBy(() ->
                PAIRE.read(ByteBuffer.wrap(forge.getBytes(StandardCharsets.UTF_8))))
                .withFailMessage("une classe arbitraire a ete instanciee depuis une entree "
                        + "de cache : la liste blanche de ReactiveRedisConfig ne filtre plus")
                .isInstanceOf(Exception.class);
    }
}
