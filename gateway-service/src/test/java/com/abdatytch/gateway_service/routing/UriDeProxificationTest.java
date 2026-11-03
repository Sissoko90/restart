package com.abdatytch.gateway_service.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que WebClient fait d'un chemin déjà encodé, mesuré.
 *
 * <p>Le Gateway construit l'URI de proxification par concaténation de chaînes,
 * puis la passe à {@code uri(String)}. Or cette surcharge traite son argument
 * comme un <b>gabarit d'URI</b>. La question — à laquelle aucune lecture ne
 * répond — est de savoir si un {@code %2B} déjà présent y survit, ou s'il est
 * encodé une seconde fois.
 *
 * <p>L'enjeu est direct : le Gateway signe le chemin en HMAC avant de
 * proxifier. Si l'URI émise diffère du chemin signé, le service aval refuse
 * tout, et le message parle de signature, jamais d'encodage.
 */
@DisplayName("URI de proxification — ce que WebClient fait d'un %2B")
class UriDeProxificationTest {

    private static final String CHEMIN = "/admin/accounts/%2B22399999999/lockout-status";

    private URI uriEmise(java.util.function.Function<WebClient, Mono<?>> appel) {
        AtomicReference<URI> vue = new AtomicReference<>();
        WebClient client = WebClient.builder()
                .exchangeFunction(requete -> {
                    vue.set(requete.url());
                    return Mono.just(ClientResponse.create(HttpStatus.OK).build());
                })
                .build();
        appel.apply(client).block();
        return vue.get();
    }

    /**
     * La mesure qui a trouvé le défaut.
     *
     * <p>{@code uri(String)} ré-encode le pourcent : {@code %2B} devient
     * {@code %252B}. Le Gateway émettait donc un chemin différent de celui
     * qu'il venait de signer.
     */
    @Test
    @DisplayName("uri(String) ré-encode le %, et casse la signature")
    void uriChaineReencode() {
        URI emise = uriEmise(c -> c.get()
                .uri("http://auth-service" + CHEMIN)
                .retrieve().bodyToMono(String.class));

        assertThat(emise.getRawPath())
                .as("la surcharge String double l'encodage : c'est pour cela "
                        + "qu'elle ne doit pas servir a proxifier")
                .isEqualTo("/admin/accounts/%252B22399999999/lockout-status")
                .isNotEqualTo(CHEMIN);
    }

    /**
     * Garde-fou à la source : le routeur ne doit pas revenir à la surcharge
     * {@code String}.
     *
     * <p>Le comportement correct n'est pas vérifiable de bout en bout sans
     * registre de services ni instance en écoute — {@code proxyRequest} résout
     * une instance par Consul avant de construire l'URI. Ce qu'un test peut
     * faire sans cela, c'est vérifier quelle surcharge est appelée.
     */
    @Test
    @DisplayName("le routeur passe un URI, pas une chaîne")
    void leRouteurPasseUnUri() throws java.io.IOException {
        java.nio.file.Path source = java.nio.file.Path.of(
                "src/main/java/com/abdatytch/gateway_service/config/GatewayRoutesConfig.java");
        assertThat(source)
                .withFailMessage("source introuvable : ce garde-fou serait vert "
                        + "sans rien verifier")
                .exists();
        String contenu = java.nio.file.Files.readString(source);

        assertThat(contenu)
                .withFailMessage("le routeur proxifie avec uri(String), qui double "
                        + "l'encodage du pourcent : il emettrait un chemin different "
                        + "de celui qu'il a signe, et le service aval refuserait tout "
                        + "en parlant de signature invalide")
                .contains("uri(java.net.URI.create(fullUri))")
                .doesNotContain(".uri(fullUri)");
    }

    @Test
    @DisplayName("uri(URI) transmet le chemin verbatim")
    void uriObjetVerbatim() {
        URI emise = uriEmise(c -> c.get()
                .uri(URI.create("http://auth-service" + CHEMIN))
                .retrieve().bodyToMono(String.class));

        assertThat(emise.getRawPath())
                .withFailMessage("uri(URI) doit transmettre le chemin tel quel : "
                        + "c'est sur ce chemin que la signature HMAC a ete calculee")
                .isEqualTo(CHEMIN);
        System.out.println("  uri(URI)    -> " + emise.getRawPath());
    }
}
