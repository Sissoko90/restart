-- ============================================================================
-- order-service — schema initial
-- ============================================================================
-- Panier, commandes et retours de la place de marche Sankadi.
--
-- SANKADI EST L'INTERMEDIAIRE. Le client n'a pas de relation directe avec la
-- boutique : il commande aupres de Sankadi, et c'est Sankadi qui arbitre les
-- retours. Ce schema porte donc, en plus de la commande, un REGISTRE des
-- transitions (`evenement_commande`) : un intermediaire qui ne peut pas prouver
-- ce qui s'est passe n'arbitre rien.
--
-- CE QUE CE SCHEMA NE CONTIENT PAS, ET POURQUOI
--
--   Aucune table de paiement, aucun mouvement d'argent. Le paiement est un
--   service a part entiere, a venir. La commande porte les MONTANTS et la date
--   d'encaissement constatee, pas les transactions : celles-ci auront leur
--   propre registre, chez payment-service, et il serait faux de les commencer
--   ici pour les y deplacer ensuite.
--
--   Aucune table de livraison, de tournee ni de point de retrait. La logistique
--   est un service a part entiere, a venir. La commande porte les DATES
--   d'expedition et de livraison constatees, parce que ce sont elles qui font
--   avancer son cycle de vie et ouvrent la fenetre de retour.
--
-- Les deux services futurs renseigneront ces dates par la meme couture que
-- l'administration Sankadi utilise aujourd'hui. Aucun endpoint n'est ecrit en
-- avance pour eux : ce depot a deja paye plusieurs fois le code ecrit pour un
-- appelant qui n'existe pas.
--
-- L'ARGENT EST EN UNITES MINEURES, DANS UN BIGINT
--
--   Comme `produit.prix` chez market-service. Un NUMERIC serait juste, mais
--   deux representations pour une meme somme finissent par se repondre a un
--   centime pres, et c'est un centime que personne ne sait expliquer. Jamais de
--   flottant : 0.1 + 0.2 n'est pas 0.3, et une place de marche additionne.
--
-- CHAQUE TABLE PORTE created_at ET updated_at
--
--   `BaseEntity` declare les deux. Une colonne manquante ne se voit pas a
--   l'ecriture mais a la LECTURE, sous la forme d'un « bad SQL grammar » qui ne
--   nomme pas la colonne. Trois tables de market-service l'ont appris.
-- ============================================================================

-- ============================================================================
-- LE PANIER
-- ============================================================================
-- Un seul panier ouvert par acheteur. L'index unique partiel plus bas le
-- garantit : sans lui, deux onglets ouverts font deux paniers, et l'acheteur
-- perd la moitie de ses articles sans comprendre.
CREATE TABLE panier (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_user_id    VARCHAR(64) NOT NULL,
    -- OUVERT : le panier courant. CONVERTI : il a produit des commandes et ne
    -- doit plus bouger. ABANDONNE : ferme sans commande, conserve pour la
    -- mesure.
    statut          VARCHAR(16) NOT NULL DEFAULT 'OUVERT',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT panier_statut_connu
        CHECK (statut IN ('OUVERT', 'CONVERTI', 'ABANDONNE'))
);

-- Un seul panier OUVERT par acheteur, les paniers clos ne genant pas.
CREATE UNIQUE INDEX panier_un_seul_ouvert_par_acheteur
    ON panier (auth_user_id) WHERE statut = 'OUVERT';

CREATE INDEX panier_par_acheteur ON panier (auth_user_id);

-- ============================================================================
-- LES LIGNES DE PANIER
-- ============================================================================
-- AUCUN PRIX N'EST STOCKE ICI, et c'est delibere.
--
-- Un prix copie dans le panier devient faux des que la boutique le change, et
-- l'acheteur verrait un montant qui n'est pas celui qu'on lui demandera. Le
-- prix est donc relu chez market-service a l'affichage et, surtout, au moment
-- de commander : c'est a cet instant seulement qu'il est fige, dans
-- `commande_ligne`.
--
-- La contrepartie est assumee : un panier peut changer de total entre deux
-- visites. C'est la verite, et la cacher serait pire.
CREATE TABLE panier_ligne (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    panier_id       UUID NOT NULL REFERENCES panier(id) ON DELETE CASCADE,
    -- Le produit ET la variante. La variante porte la couleur, la taille et le
    -- stock ; le produit sert a retrouver la boutique et a verifier la portee
    -- de visibilite de l'acheteur.
    produit_id      UUID NOT NULL,
    variante_id     UUID NOT NULL,
    quantite        INTEGER NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT panier_ligne_quantite_positive CHECK (quantite > 0),
    -- Ajouter deux fois la meme variante AUGMENTE la quantite ; cela ne cree
    -- pas une seconde ligne. Sans cette contrainte, un panier affiche
    -- « T-shirt rouge M » trois fois et personne ne sait si c'est trois
    -- articles ou un bug d'affichage.
    CONSTRAINT panier_ligne_variante_unique UNIQUE (panier_id, variante_id)
);

CREATE INDEX panier_ligne_par_panier ON panier_ligne (panier_id);

-- ============================================================================
-- LA COMMANDE
-- ============================================================================
-- UNE COMMANDE PAR BOUTIQUE.
--
-- Un panier qui contient des articles de trois boutiques produit TROIS
-- commandes. Ce n'est pas une commodite d'implementation, c'est la seule
-- decoupe qui tienne : chaque boutique expedie separement, est payee
-- separement, et un retour ne concerne qu'elle. Une commande unique forcerait
-- a redescendre au niveau de la ligne pour chacune de ces trois questions, et
-- un remboursement partiel deviendrait impossible a rattacher a un versement.
--
-- L'acheteur, lui, voit un seul passage en caisse. C'est l'affaire de la
-- presentation, pas du schema.
CREATE TABLE commande (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- La reference que l'acheteur cite au support. Lisible, courte, unique.
    -- Jamais l'identifiant technique : un UUID ne se dicte pas au telephone,
    -- et Sankadi etant l'intermediaire, il recevra ces appels.
    numero          VARCHAR(24) NOT NULL,

    auth_user_id    VARCHAR(64) NOT NULL,
    boutique_id     UUID NOT NULL,

    -- Le pays de la commande, fige a la creation. Il sert au reporting et au
    -- controle de portee a la relecture : une commande passee depuis le Mali
    -- reste une commande malienne meme si l'acheteur demenage.
    pays            VARCHAR(2) NOT NULL,

    statut          VARCHAR(24) NOT NULL DEFAULT 'A_PAYER',

    -- LES MONTANTS, EN UNITES MINEURES.
    --
    -- `montant_articles` est la somme des lignes. `commission_taux_pourcent` et
    -- `commission_montant` sont figes a la creation : le taux de Sankadi
    -- changera, et une commande passee sous l'ancien taux doit garder SES
    -- conditions. Recalculer une commission a partir du taux courant
    -- reecrirait le passe.
    --
    -- `montant_du_a_la_boutique` est redondant par construction
    -- (articles - commission) et stocke quand meme : c'est la somme que Sankadi
    -- DOIT, et une somme due se lit, elle ne se recalcule pas a chaque lecture
    -- avec le risque d'une formule qui change.
    montant_articles            BIGINT NOT NULL,
    commission_taux_pourcent    NUMERIC(5,2) NOT NULL,
    commission_montant          BIGINT NOT NULL,
    montant_du_a_la_boutique    BIGINT NOT NULL,
    devise                      VARCHAR(3) NOT NULL,

    -- L'ECHEANCE DE PAIEMENT.
    --
    -- Le stock est reserve chez market-service des la creation de la commande.
    -- Sans echeance, une commande jamais payee retient ce stock indefiniment :
    -- un seul acheteur mal intentionne viderait une boutique sans rien
    -- debourser. Le balayeur periodique annule au-dela de cette date et rend le
    -- stock.
    a_payer_jusqu_au    TIMESTAMP NOT NULL,

    -- LES DATES CONSTATEES.
    --
    -- Elles viennent de l'exterieur de ce service : l'encaissement sera
    -- constate par payment-service, l'expedition et la livraison par
    -- logistics-service. Aujourd'hui, c'est l'administration Sankadi qui les
    -- pose, par les memes methodes de service.
    payee_le        TIMESTAMP,
    expediee_le     TIMESTAMP,
    livree_le       TIMESTAMP,

    -- LA FENETRE DE RETOUR, posee a la livraison et non a la commande.
    --
    -- Un delai qui courrait depuis l'achat serait deja entame, voire epuise,
    -- quand le colis arrive. La date est STOCKEE et non recalculee depuis
    -- `livree_le` : le delai configure changera, et une commande deja livree
    -- doit garder la fenetre annoncee a son acheteur. C'est la lecon du message
    -- qui annonce une duree differente de l'echeance reelle.
    retour_possible_jusqu_au    TIMESTAMP,

    -- La cloture : la fenetre de retour est passee, la somme est payable a la
    -- boutique. C'est l'evenement que payment-service attendra pour verser.
    cloturee_le     TIMESTAMP,

    motif_annulation    TEXT,

    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT commande_numero_unique UNIQUE (numero),
    CONSTRAINT commande_statut_connu CHECK (statut IN (
        'A_PAYER',          -- creee, stock reserve, en attente d'encaissement
        'PAYEE',            -- encaissement constate par Sankadi
        'PREPAREE',         -- la boutique a declare le colis pret
        'EXPEDIEE',         -- remis a la livraison
        'LIVREE',           -- livraison constatee : la fenetre de retour s'ouvre
        'CLOTUREE',         -- fenetre passee, somme payable a la boutique
        'RETOUR_EN_COURS',  -- un retour est demande ou accepte
        'REMBOURSEE',       -- retour abouti, l'acheteur est rembourse
        'ANNULEE'           -- avant encaissement, ou par decision de Sankadi
    )),
    CONSTRAINT commande_montants_positifs CHECK (
        montant_articles >= 0
        AND commission_montant >= 0
        AND montant_du_a_la_boutique >= 0
    ),
    CONSTRAINT commande_commission_bornee CHECK (
        commission_taux_pourcent >= 0 AND commission_taux_pourcent <= 100
    ),
    -- La commission ne peut pas depasser ce qui a ete encaisse, et la
    -- decomposition doit boucler. Sans cette contrainte, une erreur d'arrondi
    -- ou un calcul refait a la main produirait une commande dont les trois
    -- montants ne s'additionnent pas, et personne ne saurait lequel croire.
    CONSTRAINT commande_decomposition_boucle CHECK (
        montant_articles = commission_montant + montant_du_a_la_boutique
    ),
    -- Une commande payee porte une date d'encaissement, et l'inverse. Les deux
    -- disent la meme chose, et un etat qui contredit sa date est le genre
    -- d'incoherence qu'on decouvre six mois plus tard, en comptabilite.
    CONSTRAINT commande_payee_a_une_date CHECK (
        (statut IN ('A_PAYER', 'ANNULEE')) = (payee_le IS NULL)
    ),
    -- La fenetre de retour n'existe pas avant la livraison.
    CONSTRAINT commande_fenetre_apres_livraison CHECK (
        (retour_possible_jusqu_au IS NULL) = (livree_le IS NULL)
    )
);

CREATE INDEX commande_par_acheteur ON commande (auth_user_id, created_at DESC);
CREATE INDEX commande_par_boutique ON commande (boutique_id, created_at DESC);
CREATE INDEX commande_par_statut ON commande (statut);

-- Les deux balayages periodiques. Sans ces index, chacun lit la table entiere
-- a chaque passage.
CREATE INDEX commande_a_payer_echues
    ON commande (a_payer_jusqu_au) WHERE statut = 'A_PAYER';
CREATE INDEX commande_a_cloturer
    ON commande (retour_possible_jusqu_au) WHERE statut = 'LIVREE';

-- ============================================================================
-- LES LIGNES DE COMMANDE
-- ============================================================================
-- UN INSTANTANE, PAS UNE REFERENCE.
--
-- Le nom, la couleur, la taille, le SKU et le prix sont COPIES au moment de
-- l'achat. La boutique peut ensuite renommer son article, changer son prix, le
-- retirer ou le supprimer : la commande continue de dire ce qui a ete achete et
-- a quel prix.
--
-- Une ligne qui ne porterait que `variante_id` laisserait une facture
-- s'invalider toute seule, et un litige arbitre par Sankadi n'aurait plus
-- d'objet a examiner. `produit_id` et `variante_id` sont conserves en plus, pour
-- rattacher un retour au stock et pour les statistiques, mais ils ne sont pas
-- des cles etrangeres : la cible vit dans un AUTRE service, et une contrainte
-- entre deux bases n'existe pas.
CREATE TABLE commande_ligne (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    commande_id     UUID NOT NULL REFERENCES commande(id) ON DELETE CASCADE,

    produit_id      UUID NOT NULL,
    variante_id     UUID NOT NULL,

    nom_produit     VARCHAR(200) NOT NULL,
    couleur         VARCHAR(60),
    taille          VARCHAR(40),
    sku             VARCHAR(64),

    prix_unitaire   BIGINT NOT NULL,
    quantite        INTEGER NOT NULL,
    -- Le montant de la ligne est stocke, et la contrainte plus bas verifie
    -- qu'il correspond au produit. Le stocker permet de l'additionner en SQL
    -- sans refaire la multiplication, et la contrainte interdit qu'il mente.
    montant_ligne   BIGINT NOT NULL,

    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT commande_ligne_quantite_positive CHECK (quantite > 0),
    CONSTRAINT commande_ligne_prix_positif CHECK (prix_unitaire >= 0),
    CONSTRAINT commande_ligne_montant_coherent
        CHECK (montant_ligne = prix_unitaire * quantite),
    CONSTRAINT commande_ligne_variante_unique UNIQUE (commande_id, variante_id)
);

CREATE INDEX commande_ligne_par_commande ON commande_ligne (commande_id);

-- ============================================================================
-- LES RETOURS
-- ============================================================================
-- SANKADI ARBITRE. L'acheteur demande, Sankadi decide, la boutique subit la
-- decision. C'est tout l'interet de l'intermediaire : un retour ne depend pas du
-- bon vouloir du vendeur.
--
-- Le remboursement n'est pas execute ici : aucun argent ne circule dans ce
-- service. `montant_rembourse` et `rembourse_le` enregistrent ce que Sankadi
-- DOIT a l'acheteur et quand il l'a constate ; payment-service le versera.
CREATE TABLE retour (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    commande_id     UUID NOT NULL REFERENCES commande(id) ON DELETE RESTRICT,

    -- Qui demande. Controle a la demande : seul l'acheteur de la commande peut
    -- ouvrir un retour, et le filtre de portee le verifie. La colonne existe
    -- quand meme, parce qu'une demande ouverte par le support Sankadi pour le
    -- compte d'un acheteur doit se distinguer.
    demande_par     VARCHAR(64) NOT NULL,
    motif           VARCHAR(32) NOT NULL,
    commentaire     TEXT,

    statut          VARCHAR(16) NOT NULL DEFAULT 'DEMANDE',

    -- L'arbitrage de Sankadi.
    decide_par      VARCHAR(64),
    decide_le       TIMESTAMP,
    motif_decision  TEXT,

    -- L'article est revenu. Constate par la logistique, demain ; par
    -- l'administration Sankadi, aujourd'hui. C'est cette date qui autorise le
    -- remboursement : rembourser avant d'avoir recu, c'est payer deux fois.
    recu_le         TIMESTAMP,

    montant_rembourse   BIGINT,
    rembourse_le        TIMESTAMP,

    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT retour_statut_connu CHECK (statut IN (
        'DEMANDE',      -- l'acheteur a demande, Sankadi n'a pas tranche
        'ACCEPTE',      -- Sankadi accepte : l'article doit revenir
        'REFUSE',       -- Sankadi refuse, avec un motif
        'RECU',         -- l'article est revenu chez Sankadi ou la boutique
        'REMBOURSE'     -- l'acheteur est rembourse, le dossier est clos
    )),
    CONSTRAINT retour_motif_connu CHECK (motif IN (
        'NON_CONFORME',         -- ce n'est pas ce qui etait decrit
        'ENDOMMAGE',            -- casse a l'arrivee
        'TAILLE_INCORRECTE',
        'JAMAIS_RECU',          -- le colis n'est pas arrive
        'CHANGEMENT_D_AVIS',
        'AUTRE'
    )),
    -- Une decision porte un decideur ET une date. Un refus porte en plus un
    -- motif : refuser sans dire pourquoi fait revenir l'acheteur, et Sankadi
    -- traite deux fois le meme dossier.
    CONSTRAINT retour_decision_complete CHECK (
        (statut = 'DEMANDE') = (decide_le IS NULL)
    ),
    CONSTRAINT retour_refus_motive CHECK (
        statut <> 'REFUSE' OR motif_decision IS NOT NULL
    ),
    -- On ne rembourse pas ce qu'on n'a pas recu.
    CONSTRAINT retour_remboursement_apres_reception CHECK (
        statut <> 'REMBOURSE' OR (recu_le IS NOT NULL AND montant_rembourse IS NOT NULL)
    )
);

-- UN SEUL RETOUR OUVERT PAR COMMANDE.
--
-- Sans cet index, un acheteur ouvre dix demandes sur la meme commande et noie
-- la file d'arbitrage. Un retour refuse ou rembourse ne bloque pas : on peut
-- redemander apres un refus, ce qui est voulu, car un refus mal compris doit
-- pouvoir etre represente avec un meilleur motif.
CREATE UNIQUE INDEX retour_un_seul_ouvert_par_commande
    ON retour (commande_id) WHERE statut IN ('DEMANDE', 'ACCEPTE', 'RECU');

CREATE INDEX retour_par_commande ON retour (commande_id);
CREATE INDEX retour_par_statut ON retour (statut, created_at);

-- ============================================================================
-- LE REGISTRE DES TRANSITIONS
-- ============================================================================
-- UN INTERMEDIAIRE QUI NE PEUT RIEN PROUVER N'ARBITRE RIEN.
--
-- Sankadi se tient entre un acheteur et une boutique qui n'ont pas de relation
-- directe. Quand l'un affirme avoir expedie et l'autre n'avoir rien recu, le
-- seul recours est l'historique : qui a declare quoi, quand, et sous quel role.
--
-- Ce n'est pas un journal applicatif. Un journal se purge, se perd, et ne
-- repond pas a une requete SQL. C'est une table, et elle est en APPEND SEUL :
-- aucun service ne la modifie ni ne la supprime.
--
-- Meme esprit que le registre de preuve d'effacement RGPD : effacer n'est pas
-- supprimer la ligne, et arbitrer demande de garder la trace.
CREATE TABLE evenement_commande (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    commande_id     UUID NOT NULL REFERENCES commande(id) ON DELETE RESTRICT,

    -- Nul pour l'evenement de creation : il n'y a pas d'etat precedent.
    de_statut       VARCHAR(24),
    vers_statut     VARCHAR(24) NOT NULL,

    -- QUI, et a quel titre. Le role importe autant que l'identite : la meme
    -- personne peut etre acheteuse sur une commande et administratrice sur une
    -- autre, et c'est le role exerce qui legitime la transition.
    par_qui         VARCHAR(64) NOT NULL,
    role            VARCHAR(16) NOT NULL,

    motif           TEXT,

    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT evenement_role_connu CHECK (role IN (
        'ACHETEUR', 'BOUTIQUE', 'ADMIN', 'SYSTEME'
    )),
    -- Une transition va quelque part, et ailleurs. Un evenement qui boucle sur
    -- lui-meme est une ecriture en double, pas une transition.
    CONSTRAINT evenement_transition_reelle
        CHECK (de_statut IS NULL OR de_statut <> vers_statut)
);

CREATE INDEX evenement_par_commande ON evenement_commande (commande_id, created_at);
