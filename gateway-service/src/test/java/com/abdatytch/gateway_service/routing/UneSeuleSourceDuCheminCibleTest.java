package com.abdatytch.gateway_service.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou : le chemin cible se calcule à <b>un seul endroit</b>.
 *
 * <h2>Pourquoi ce contrôle est à la source, et non comportemental</h2>
 *
 * Deux endroits ont besoin du chemin qu'un service recevra :
 * {@code GatewaySignatureFilter}, qui le signe, et
 * {@code GatewayRoutesConfig#proxyRequest}, qui proxifie vers lui. Le service
 * aval recalcule la signature sur le chemin reçu : si les deux divergent d'un
 * caractère, <b>chaque requête du préfixe concerné est refusée en 403</b>, avec
 * un message qui parle de signature invalide et jamais de chemin.
 *
 * <p>Le côté filtre est vérifié par le comportement —
 * {@code GatewaySignatureFilterTest.signatureSurLeCheminReecrit}. Le côté
 * routeur ne l'est pas : {@code proxyRequest} résout une instance par Consul
 * avant de construire l'URI, et l'exercer demanderait un registre de services
 * et une instance en écoute. Ce qu'un test peut faire sans cela, c'est vérifier
 * que le routeur <b>ne refait pas le calcul lui-même</b>.
 *
 * <p>Un contrôle à la source est faible, et c'est assumé : il ne prouve pas que
 * le résultat est juste, seulement qu'il n'y a pas deux implémentations libres
 * de divergar. C'est exactement ce qui manquait.
 */
@DisplayName("Le chemin cible n'est calculé qu'à un seul endroit")
class UneSeuleSourceDuCheminCibleTest {

    /**
     * Les deux fichiers qui ont besoin du chemin cible.
     *
     * <p>Chemins relatifs au module : le répertoire de travail d'un test Maven
     * est celui du module.
     */
    private static final String FILTRE =
            "src/main/java/com/abdatytch/gateway_service/filter/GatewaySignatureFilter.java";
    private static final String ROUTEUR =
            "src/main/java/com/abdatytch/gateway_service/config/GatewayRoutesConfig.java";

    private String source(String chemin) throws IOException {
        Path fichier = Path.of(chemin);
        assertThat(fichier)
                .withFailMessage("fichier introuvable : %s — ce garde-fou serait vert "
                        + "sans rien vérifier", fichier.toAbsolutePath())
                .exists();
        return Files.readString(fichier);
    }

    @Test
    @DisplayName("le filtre de signature passe par CheminCible")
    void leFiltrePasseParCheminCible() throws IOException {
        assertThat(source(FILTRE)).contains("CheminCible.pour(");
    }

    @Test
    @DisplayName("le routeur passe par CheminCible")
    void leRouteurPasseParCheminCible() throws IOException {
        assertThat(source(ROUTEUR)).contains("CheminCible.pour(");
    }

    @Test
    @DisplayName("ni l'un ni l'autre ne refait le retrait du préfixe à la main")
    void aucunRetraitDePrefixeEnDouble() throws IOException {
        // C'est la forme exacte qui etait dupliquee :
        //   originalPath.replaceFirst("/api/v1", "")
        // Elle tenait tant que la transformation se reduisait a ce retrait. Elle
        // ne tient plus des la premiere reecriture, et rien ne rappelle alors
        // qu'il faut modifier les deux.
        for (String fichier : new String[] {FILTRE, ROUTEUR}) {
            assertThat(source(fichier))
                    .withFailMessage("%s recalcule le chemin cible au lieu d'utiliser "
                            + "CheminCible : le filtre et le routeur peuvent désormais "
                            + "divergar, et chaque requête concernée sera refusée en 403 "
                            + "avec un message parlant de signature, pas de chemin.", fichier)
                    .doesNotContain("replaceFirst(\"/api/v1\"");
        }
    }
}
