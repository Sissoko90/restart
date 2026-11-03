package com.abdatytch.user_service.constants;

/**
 * Constantes du mapper.
 *
 * <h2>Pourquoi cette classe a fondu</h2>
 *
 * Elle portait cinq valeurs de repli — {@code unknown@email.com},
 * {@code +00000000000}, {@code +000}, {@code Unknown}, {@code User} — qui
 * remplissaient l'identité quand auth-service ne répondait pas. Le défaut
 * n'était pas le repli, c'était la <b>vraisemblance</b> de ces valeurs : une
 * adresse syntaxiquement valide et un nom lisible, que rien ne distinguait d'une
 * vraie donnée. Un administrateur qui consultait une liste pendant une panne y
 * lisait des identités fausses sans le savoir, et un export les aurait figées.
 *
 * <p>{@code UserMapper} rend désormais des champs <b>nuls</b> : un champ nul se
 * voit. Les constantes sont retirées et non simplement laissées inutilisées,
 * parce qu'une valeur de repli vraisemblable qui traîne dans une classe de
 * constantes finit par être reprise.
 *
 * <p>Avec elles sont parties les constantes de journalisation du secret et du
 * détail de l'appel Feign, devenues sans objet depuis que
 * {@code ClientIdentiteAuth} porte l'appel sur {@code WebClient}.
 */
public final class MapperConstants {

    private MapperConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }

    /** Seule constante encore lue, par {@code UserMapper.identite}. */
    public static final String LOG_ERROR_AUTH_FETCH_FAILED =
            "Erreur lors de la recuperation des donnees auth pour userId: {}";
}
