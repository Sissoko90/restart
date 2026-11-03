package com.abdatytch.auth_service.enums;

/**
 * Enum représentant les providers OAuth2 supportés
 */
public enum OAuthProvider {
    GOOGLE("google", EventType.GOOGLE_LOGIN),
    FACEBOOK("facebook", EventType.FACEBOOK_LOGIN),
    /**
     * Sign in with Apple.
     *
     * <p>Aucune migration de base n'a ete necessaire : la colonne
     * {@code oauth_accounts.provider} est un {@code VARCHAR(20)} et non un type
     * enumere PostgreSQL. Verifie dans {@code V1__init_auth_schema.sql} avant
     * d'ajouter cette valeur — un type enumere en base aurait exige une
     * migration, et l'insertion aurait echoue a la premiere connexion Apple.
     */
    APPLE("apple", EventType.APPLE_LOGIN),
    LOCAL("local", EventType.LOGIN); // Pour les utilisateurs créés localement (phone/email)

    private final String providerName;
    private final EventType evenementDeConnexion;

    OAuthProvider(String providerName, EventType evenementDeConnexion) {
        this.providerName = providerName;
        this.evenementDeConnexion = evenementDeConnexion;
    }

    public String getProviderName() {
        return providerName;
    }

    /**
     * Evenement a journaliser pour une connexion par ce fournisseur.
     *
     * <h2>Pourquoi cette correspondance vit ici</h2>
     *
     * Elle etait ecrite en clair dans {@code OAuth2ServiceImpl}, sous la forme
     * d'un ternaire repete <b>trois</b> fois :
     *
     * <pre>
     * provider == GOOGLE ? GOOGLE_LOGIN : FACEBOOK_LOGIN
     * </pre>
     *
     * <p>Le defaut n'est pas le nombre de copies, c'est le <b>repli
     * silencieux</b> : tout fournisseur qui n'est pas Google y devient Facebook.
     * Une connexion Apple aurait donc ete journalisee, comptee et alertee comme
     * une connexion Facebook — aux trois endroits, sans aucune erreur. La piste
     * d'audit aurait designe le mauvais fournisseur, et les metriques
     * {@code oauth2_login{provider}} auraient confirme cette fausse lecture.
     *
     * <p>Portee par l'enumeration, la correspondance devient <b>exhaustive par
     * construction</b> : ajouter une valeur sans choisir son evenement ne
     * compile pas. Un oubli redevient une erreur de compilation au lieu d'une
     * donnee d'audit fausse.
     */
    public EventType getEvenementDeConnexion() {
        return evenementDeConnexion;
    }


    public static OAuthProvider fromString(String provider) {
        for (OAuthProvider p : OAuthProvider.values()) {
            if (p.providerName.equalsIgnoreCase(provider)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Unknown OAuth provider: " + provider);
    }
}
