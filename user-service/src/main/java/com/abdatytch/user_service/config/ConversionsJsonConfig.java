package com.abdatytch.user_service.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.PostgresDialect;

import java.util.List;
import java.util.Map;

/**
 * Conversion {@code Map<String, String>} ↔ texte JSON.
 *
 * <h2>Pourquoi ces convertisseurs sont nécessaires</h2>
 *
 * {@code UserProfile.preferences} est une {@code Map}. R2DBC ne sait pas la
 * convertir : sans convertisseur, l'écriture d'un profil échoue avant même
 * d'atteindre la base, sur un type que le pilote ne reconnaît pas.
 *
 * <p>Le défaut était latent et invisible : ce service n'avait <b>aucune
 * table</b>, donc aucune écriture n'a jamais été tentée.
 *
 * <h2>Pourquoi du TEXTE, et non une colonne JSONB</h2>
 *
 * JSONB vaudrait mieux sur le papier : la base validerait le document. Mais la
 * lier demanderait le type {@code Json} du pilote PostgreSQL, et ce pilote est
 * déclaré en portée {@code runtime} — volontairement, pour que le code du
 * service ne dépende pas d'un pilote précis. Passer la dépendance en
 * {@code compile} pour un champ libre jamais interrogé par son contenu serait
 * un mauvais échange.
 *
 * <p>Ce que la base ne valide plus, Jackson le valide à l'écriture : aucun
 * document malformé ne peut venir de l'application. Reste le cas d'une
 * modification manuelle en base, traité par le repli de lecture ci-dessous.
 *
 * <h2>Pourquoi un bean, et non {@code AbstractR2dbcConfiguration}</h2>
 *
 * C'était l'endroit prévu pour cela — {@code getCustomConverters()} — et c'est
 * précisément la classe qui empêchait le service de démarrer : elle surchargeait
 * {@code connectionFactory()} pour rendre {@code null}, et
 * {@code r2dbcCustomConversions()} appelait cette méthode. Déclarer le bean
 * directement donne le même résultat sans reprendre toute la configuration à sa
 * charge.
 *
 * <h2>Les préférences ne sont jamais nulles</h2>
 *
 * La colonne est {@code NOT NULL DEFAULT '{}'} et la lecture rend une carte
 * vide. Une {@code Map} nulle obligerait chaque lecteur à s'en méfier — dont
 * {@code UserMapper}, qui la place dans la réponse, et
 * {@code ProfileErasureService}, qui la vide.
 */
@Configuration
@Slf4j
public class ConversionsJsonConfig {

    /** Document vide : la colonne est NOT NULL. */
    static final String VIDE = "{}";

    /**
     * Enregistre les deux convertisseurs auprès de Spring Data R2DBC.
     *
     * <p>{@code PostgresDialect} est passé explicitement : c'est lui qui porte
     * les types simples reconnus sans conversion.
     */
    @Bean
    public R2dbcCustomConversions r2dbcCustomConversions(ObjectMapper objectMapper) {
        return R2dbcCustomConversions.of(PostgresDialect.INSTANCE, List.of(
                new PreferencesVersTexte(objectMapper),
                new TexteVersPreferences(objectMapper)));
    }

    /** Écriture : la carte devient du texte JSON. */
    @WritingConverter
    static class PreferencesVersTexte implements Converter<Map<String, String>, String> {

        private final ObjectMapper objectMapper;

        PreferencesVersTexte(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public String convert(Map<String, String> preferences) {
            if (preferences == null || preferences.isEmpty()) {
                // Document vide, et non NULL : la colonne est NOT NULL, et une
                // carte vide est l'absence de préférence — pas une absence de
                // valeur.
                return VIDE;
            }
            try {
                return objectMapper.writeValueAsString(preferences);
            } catch (Exception erreur) {
                // L'écriture ECHOUE, et ne se rabat pas sur « {} ».
                //
                // Un repli silencieux ferait disparaître les préférences de la
                // personne à l'enregistrement suivant, et la sauvegarde
                // répondrait « succès ». Mieux vaut une erreur visible qu'une
                // perte de données muette.
                throw new IllegalStateException(
                        "Preferences inconvertibles en JSON", erreur);
            }
        }
    }

    /** Lecture : le texte JSON redevient une carte. */
    @ReadingConverter
    static class TexteVersPreferences implements Converter<String, Map<String, String>> {

        private static final TypeReference<Map<String, String>> CARTE =
                new TypeReference<>() {
                };

        private final ObjectMapper objectMapper;

        TexteVersPreferences(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public Map<String, String> convert(String texte) {
            if (texte == null || texte.isBlank()) {
                return Map.of();
            }
            try {
                Map<String, String> preferences = objectMapper.readValue(texte, CARTE);
                return preferences == null ? Map.of() : preferences;
            } catch (Exception erreur) {
                // La LECTURE se rabat sur une carte vide — et le choix est
                // l'inverse de l'écriture, délibérément.
                //
                // Lever ici rendrait le compte entier illisible : la personne
                // ne pourrait plus consulter son profil, ni l'administration la
                // débloquer, à cause d'un champ accessoire. Le document
                // malformé est journalisé en ERROR pour être corrigé.
                log.error("Preferences illisibles pour un profil ({}) : "
                        + "une carte vide est rendue", erreur.getClass().getSimpleName());
                return Map.of();
            }
        }
    }
}
