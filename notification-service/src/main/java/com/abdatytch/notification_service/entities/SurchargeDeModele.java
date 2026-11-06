package com.abdatytch.notification_service.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Une surcharge de modele : un texte qui prend le pas sur celui du fichier.
 *
 * <h2>Surcharge, et non modele</h2>
 *
 * Le nom dit la conception. Les modeles vivent dans
 * {@code modeles-notification.properties}, versionne et relu ; cette table ne
 * porte que les corrections posees a chaud. Une table vide se comporte donc
 * exactement comme avant son existence.
 *
 * <h2>Pas de {@code Persistable} ici, contrairement aux deux autres entites</h2>
 *
 * {@link Notification} et {@link RemiseParCanal} l'implementent parce qu'elles
 * portent un identifiant que nous generons : Spring Data y verrait une ligne
 * existante et emettrait un {@code UPDATE} sur rien.
 *
 * <p>Cette entite n'a pas ce besoin parce qu'elle n'est <b>jamais</b> ecrite par
 * {@code save()}. L'ecriture passe par un {@code INSERT ... ON CONFLICT DO
 * UPDATE} explicite, qui tranche la question en une seule instruction atomique
 * au lieu de la faire dependre d'un drapeau porte par l'objet. Voir
 * {@code SurchargeDeModeleRepository.enregistrer}.
 */
@Table("surcharge_de_modele")
public class SurchargeDeModele {

    /** La cle complete, telle que le moteur l'interroge. Cle primaire naturelle. */
    @Id
    @Column("cle")
    private String cle;

    @Column("valeur")
    private String valeur;

    /** Le {@code sub} du porteur qui a pose la surcharge, jamais son adresse. */
    @Column("modifiee_par")
    private String modifieePar;

    @Column("modifiee_le")
    private LocalDateTime modifieeLe;

    protected SurchargeDeModele() {
    }

    /**
     * Construit une surcharge en memoire.
     *
     * <h3>A quoi elle sert reellement</h3>
     *
     * Le chemin de production n'en a pas besoin : l'ecriture passe par
     * {@code enregistrer}, qui envoie un {@code INSERT ... ON CONFLICT} sans
     * jamais materialiser d'objet, et la lecture est faite par Spring Data qui
     * remplit les champs par reflexion.
     *
     * <p>Elle existe pour que les tests du moteur puissent fabriquer les lignes
     * que le depot rendrait. L'alternative — ouvrir les champs par des mutateurs,
     * ou les poser par reflexion depuis le test — rendrait l'entite mutable pour
     * tout le monde, ou ferait dependre un test de noms de champs qu'un
     * renommage casse en silence.
     */
    public static SurchargeDeModele de(String cle, String valeur, String modifieePar) {
        SurchargeDeModele surcharge = new SurchargeDeModele();
        surcharge.cle = cle;
        surcharge.valeur = valeur;
        surcharge.modifieePar = modifieePar;
        surcharge.modifieeLe = LocalDateTime.now();
        return surcharge;
    }

    public String getCle() {
        return cle;
    }

    public String getValeur() {
        return valeur;
    }

    public String getModifieePar() {
        return modifieePar;
    }

    public LocalDateTime getModifieeLe() {
        return modifieeLe;
    }
}
