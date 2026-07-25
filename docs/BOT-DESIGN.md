# Bot — Document de conception

> Conception du bot « joue comme un humain » de Red Dawn Raid. Chaque règle citée ici a été
> vérifiée dans le code (`backend/.../game/domain/`) et dans [REGLES.md](REGLES.md).
> Ce document sert de référence pour l'implémentation du package `bot/`.

---

## 1. Objectif et principes

Le bot doit remplacer un joueur humain (chasseur **ou** vampire) et jouer de manière
**contextuelle** : garder ses cartes pour le bon moment, monter des combos, adapter sa
stratégie à l'état de la partie. Trois principes non négociables :

1. **Utility AI** — chaque action possible reçoit un **score** calculé à partir de l'état
   de la partie ; le bot joue la mieux notée si elle dépasse un seuil, sinon il attend.
   Pas de machine learning, pas d'arbre de recherche : des heuristiques lisibles et
   réglables à la main.
2. **Équité (pas de triche)** — le bot vit côté serveur et pourrait lire tout le `Game`
   (mains adverses, ordre des pioches, lieux face cachée). Il ne doit raisonner **que sur
   ce qu'un humain verrait** : l'équivalent du snapshot masqué produit par
   `SnapshotService`. Concrètement, le bot construit son état de décision via une classe
   `BotView` qui n'expose que l'information publique + sa propre main. Toute heuristique
   qui aurait besoin d'une info cachée doit se contenter d'une **estimation** (voir §5,
   modèle d'adversaire).
3. **Humanisation** — délais de réaction aléatoires (2 à 8 s via le `raidTaskScheduler`),
   léger bruit sur les scores (via `Dice`) pour ne pas être prévisible, et respect des
   fenêtres temporisées existantes (~30 s en PREPHASE3, ~60 s en PHASE4).

Le bot appelle **directement la façade `GameService`** (jamais le contrôleur REST) : il
bénéficie ainsi des mêmes validations que les joueurs humains et n'a besoin ni de compte
ni de token. Un bot est un `Player` ordinaire dans le blob `Game`, avec un id synthétique
(ex. `bot:<uuid>`) et un flag `bot=true`.

---

## 2. Squelette du package `bot/`

```
backend/src/main/java/org/castello/bot/
├── BotManager.java          # cycle de vie : créer un bot (lobby), adopter le Player
│                            # d'un humain qui abandonne, retirer un bot ; nommage
├── BotOrchestrator.java     # écoute les événements de partie (même source que LiveEvents)
│                            # + tick de sécurité périodique ; décide QUEL bot doit agir
│                            # MAINTENANT et déclenche son cerveau avec un délai humanisé
├── BotView.java             # vue « équitable » de la partie pour un bot : info publique
│                            # + sa main ; AUCUN accès aux mains/pioches adverses
├── BotProfile.java          # personnalité : poids (agressivité, économie, prise de
│                            # risque, focalisation corruption) + archétype de départ
├── brain/
│   ├── HunterBrain.java     # décisions du bot chasseur, phase par phase
│   ├── VampireBrain.java    # décisions du bot vampire, phase par phase
│   └── ServantBrain.java    # chasseur devenu serviteur (corruption 3)
├── eval/
│   ├── CardEvaluator.java   # interface : score(BotView, BotProfile) -> double [0..1]
│   ├── hunter/…             # 1 évaluateur par carte chasseur (16)
│   └── vampire/…            # 1 évaluateur par carte vampire (13)
└── model/
    ├── ThreatAssessment.java  # rapport de force sur un lieu (voir §5)
    └── OpponentModel.java     # stats simples sur les habitudes adverses (voir §5)
```

Points d'intégration avec l'existant :
- **Déclenchement** : `BotOrchestrator` s'abonne aux mêmes points d'émission que
  `LiveEvents` (après commit). Un tick de secours (toutes les ~5 s) rattrape tout état
  où un bot doit agir et ne l'a pas fait — c'est la garantie « ne bloque jamais la partie ».
- **Lobby** : bouton « Ajouter un bot » → `BotManager` crée le joueur bot dans la partie
  (sans passer par `PlayerService.joinGame`, qui exige un compte).
- **Présence** : les bots doivent être exemptés du suivi `lastSeenTs`/`leftGame`
  (ou `BotOrchestrator` rafraîchit leur présence).
- **Serviteur** : si un bot chasseur atteint corruption 3, son cerveau bascule sur
  `ServantBrain` (il joue alors pour le vampire — comme un humain converti).

---

## 3. Les profils de personnalité

Un `BotProfile` est un petit jeu de **poids** consulté par tous les évaluateurs. Au
démarrage, tous les bots partent sur le préréglage **économique** (vampire Bâtisseur,
chasseur Prudent) — l'archétype n'est pas sélectionnable au lobby ; ce sont les **pivots
de situation** ci-dessous qui font émerger les autres comportements. Les archétypes
restent néanmoins définis comme préréglages : en activer d'autres plus tard (au hasard
ou via le lobby) sera trivial.

| Poids | Effet principal |
|-------|-----------------|
| `aggression` | Chercher/éviter le combat ; jouer les buffs offensifs tôt |
| `economy` | Prioriser récolte, constructions, boutique |
| `risk` | Seuils : attaquer à PV moyens, garder ou claquer les combos |
| `corruptionFocus` (vampire) | Prioriser morsures/marque/autel vs dégâts directs |

Archétypes de départ (des presets de ces poids, pas des bots séparés) :

- **Vampire Bâtisseur** (éco) : Scierie → Mine tôt, revenus auto, monte en puissance,
  évite le combat les premiers raids.
- **Vampire Corrupteur** : Autel tôt + Marque ténébreuse + morsures — transformer un
  chasseur en serviteur le plus vite possible (voir §7).
- **Vampire Prédateur** : Laboratoire → monstres + combos offensifs de toutes sortes :
  Éclipse → Lune de sang, Passage secret pour surgir sur un isolé, Clones d'ombre +
  potion/élixir de Rage… Cherche l'élimination directe des chasseurs.
- **Chasseur Traqueur** : oriente tous ses choix (lieu, Pisteur, cartes, achats) vers
  une seule cible — trouver le vampire et concentrer les attaques de l'équipe sur lui.
- **Chasseur Prudent** : économie, banque, équipement. On ne « choisit » pas vraiment
  ses combats (la révélation surprend) : prudent = minimiser la probabilité de croiser
  le vampire (choix de lieux, jamais de Pisteur) et ne rejoindre un plan offensif
  (embuscade coordonnée) que si l'équipe est en surnombre clair.
- **Chasseur Soutien** : échanges généreux, fumigations, purifications à l'autel.

**Pivot en cours de partie** (le cœur du système) : les poids sont modulés en continu
par l'état de la partie, dans les deux sens :
- pivots **défensifs** — vampire à PV bas → `aggression` baisse, fuite/défense montent ;
  chasseur corruption 2 → priorité absolue à la purification quel que soit le profil ;
  chasseur à PV bas → éviter le contact, potion de Vie ;
- pivots **opportunistes** — un chasseur atteint corruption 1 → le `corruptionFocus` du
  vampire monte (la proie est entamée, le rush serviteur devient rentable) ; un chasseur
  à PV bas repéré → le vampire presse l'attaque ; vampire visiblement blessé → les
  chasseurs basculent en chasse groupée.

---

## 4. Points de décision à couvrir (garantie anti-blocage)

Inventaire par phase, tiré des ~70 endpoints de `GameController`. **[O]** = obligatoire
(la partie attend ce joueur), **[F]** = facultatif (fenêtre d'opportunité).

### Lobby / vie de partie
- [O] rejoindre (`join` — via `BotManager`), se déclarer prêt (`boot-ready`), démarrage.
- [O] ne jamais déclencher `surrender`/`leave` ; présence rafraîchie automatiquement.

### PHASE0 — Météo
- [O] `weather/roll` si c'est au bot de lancer.

### PHASE1 — Planification (chasseurs)
- [F] `FUMIGATION_AIL` (avant de choisir son lieu), `PISTEUR`.
- [O] `select-location` (choix du lieu, voir §5).

### PHASE2 — Vampire
- [F] `CATACLYSME`, `CLONES_OMBRE` (+ `clones/roll`, `clones/confirm`), `IMAGE_MIROIR`.
- [F] `plan-construction` (voir §6) — **joue aussi la carte Lieu du chantier**
  (Forêt/Carrière/Manoir) : le vampire qui construit a donc déjà choisi son lieu.
- [O] `select-location` — seulement s'il n'a pas planifié de construction.

### PREPHASE3 — Révélation
- [O] `corruption/roll` (jet d6 du chasseur instable) ; si le bot est le **vampire** :
  `unstable/assign-target` / `assign-harvest` / `assign-nothing` pour chaque succombé.
- [O] répondre aux **effets de lieu** en file (`location-effect` + `effect-*` : vol,
  prédiction, expérimentation, soin, corruption, forge, explosion, pillage…).
- [O] résolutions de cartes en attente me ciblant ou m'appartenant
  (`actions/*/resolve`, `net/target`, `image-miroir/choose`, `secret-passage/resolve`…).
- [F] cartes d'action de combat (voir §8), `potions/use`, caisses (`crate/roll|resolve`).
- [O] `skip` / laisser expirer le timer 30 s quand rien ne vaut la peine — **le bot doit
  savoir dire « je passe »**, sinon il ralentit chaque raid.

### PHASE3 — Récolte & combat
- [O] `roll` (chaque jet d'attaque/défense qui m'appartient).
- [O] `corruption/roll` — la morsure se déclenche automatiquement côté serveur
  (`currentBite`) ; il reste deux jets à cliquer : le **d20 du vampire**, puis, si la
  cible porte l'armure de plates en argent, son **d4 de parade**. Le Chapelet sacré,
  lui, s'applique et se consume tout seul (à condition d'avoir été posé avant, en
  PREPHASE3).
- `combat/continue` — simple clic de progression (le front l'envoie après les jets),
  pas une décision : le tick de sécurité de l'orchestrateur l'émet si la PHASE3 stagne
  (indispensable quand tous les participants d'un combat sont des bots).
- [O] `actions/pit/resolve`, `blessed-stake/resolve`… quand le serveur attend mon jet.

### PHASE4 — Maintenance
- [F] boutique (`shop/buy-*`, `shop/sell`, `shop-upgrade-weapon`, `buy-upgrade-armor`),
  banque (`bank-contribute`), transmutation (`transmutation/do`), échanges
  (`trade/offer|accept|decline` — répondre aux offres reçues !), cartes de phase 4
  (`CHARISMATIQUE`, `MARCHAND_ITINERANT`, `AVIDITE_NOCTURNE`, `ADVANCED_TRANSMUTATION`).
- [O] `phase4/finish` quand le bot a terminé (ne pas faire attendre les humains).

> **Règle d'or de l'étape 1** : implémenter d'abord tous les **[O]** avec des choix
> naïfs (lieu aléatoire pondéré, toujours lancer les dés, toujours finir la phase).
> Un bot bête mais qui ne bloque jamais est immédiatement jouable.

---

## 5. Choix du lieu (la décision la plus importante)

Les lieux sont choisis **face cachée** (chasseurs en PHASE1, vampire en PHASE2) : personne
ne voit les choix adverses avant la révélation. Le bot raisonne donc en probabilités.

**`ThreatAssessment` (rapport de force sur un lieu)** — utilisé partout :
`force(camp, lieu) = Σ joueurs [PV × poids] + tiers d'équipement + mods de raid visibles
(météo, corruption, buffs annoncés) + monstres présents`. Le ratio
`force(alliés)/force(ennemis)` alimente presque toutes les heuristiques de combat.

**`OpponentModel` (estimation de l'info cachée)** — fréquences simples observées :
où chaque adversaire est allé les derniers raids, combien de cartes il a achetées
(info publique via l'historique), s'il a une eau bénite probable (achat vu en boutique).
Rien d'autre — pas de lecture des mains.

**Bot chasseur** choisit son lieu selon :
1. **Besoins de ressources** (manque pour potion/équipement/banque → lieu correspondant,
   cf. récoltes §9 de REGLES.md ; Manoir = or mais = territoire du vampire).
2. **Chasse** : si profil agressif et rapport de force favorable estimé → lieu probable
   du vampire (ou `PISTEUR` pour le suivre à coup sûr).
3. **Fuite/corruption** : corruption ≥ 2 ou PV bas → éviter le vampire, viser l'Autel
   purifié (retirer 1 corruption) ou un lieu calme.
4. **Coordination** : si d'autres bots chasseurs existent, converger vers la même cible
   quand une embuscade se prépare (les bots d'un même camp peuvent partager leurs
   *intentions*, jamais l'info cachée).

**Bot vampire** choisit son lieu selon :
1. **Âmes** : Manoir/bâtiments = récolte d'âmes (carburant du Labo, de l'Autel, des clones).
2. **Chasse** : lieu où un chasseur isolé/affaibli est probable (OpponentModel).
3. **Défense du domaine** : si un chasseur rôde avec Incendiaire probable, défendre.
4. **Setup de fuite** : rester au Manoir permet `PASSAGE_SECRET` (voir §8) —
   le vampire Corrupteur/Prédateur y gagne une flexibilité énorme après révélation.
5. **Fumigation** : lieux fumigés interdits (le bot doit filtrer ses options légales).

---

## 6. Stratégie de construction (vampire)

Vérifié dans `ConstructionService` : 1 construction/raid (planifiée en PHASE2, finalisée
fin de PHASE3 si les ressources sont encore là), chaque infra une seule fois, impossible
sous Cyclone. La carte Lieu construite va dans **la main de tous les joueurs** — construire
ouvre donc aussi des options aux chasseurs (à intégrer au score !).

| Infra | Coût | Intérêt stratégique |
|-------|------|---------------------|
| Scierie | 2 pierre, 2 fer | Éco : pas d'inauguration ; +3 bois/récolte de lieu, +1 bois auto/raid (vampire) |
| Mine | 3 bois, 1 fer | Éco : pas d'inauguration ; +3 fer/récolte de lieu, +1 fer auto/raid (vampire) |
| Bibliothèque | 4 bois, 2 pierre, 1 fer | Contrôle : pioche, sabotage de cartes, prédiction |
| Laboratoire | 2 eau, 2 herbes, 3 pierre, 50 âmes | Agression : monstres (100–600 âmes), alchimie |
| Salle de bal | 5 pierre, 2 fer, 50 âmes | Agression/corruption : danse macabre, valse, vols |
| Autel | 4 pierre, 1 bois, 2 fer, 50 âmes | Corruption : naît **corrompu** → +1 corruption/raid sur un chasseur |
| Forge | 5 fer, 4 pierre, 2 bois | Équipement tier 3 — à double tranchant, voir note |

> **Forge, un timing délicat** : elle profite aussi aux chasseurs (craft du tier 3 moins
> cher qu'en boutique, dont l'armure de plates en argent anti-morsure). Trop tôt, elle
> équipe l'ennemi ; trop tard, le vampire — qui n'a guère que la Transmutation avancée
> pour s'équiper sans elle — se prive d'options alors que les chasseurs auront déjà
> monté leur équipement en boutique. Le score doit viser le milieu de partie.

Ordres de construction par archétype (le bot suit l'ordre tant que les ressources et la
menace le permettent, sinon il décale d'un raid) :

- **Bâtisseur** : Scierie → Mine → Bibliothèque/Forge. Les +1 auto s'accumulent ; les
  récoltes améliorées (+3) financent le reste. Risque assumé : Incendiaire adverse —
  d'où défense du domaine si la menace monte.

  > ⚠️ **Défaut connu du Bâtisseur naïf (à corriger à l'étape 3, dit par le game
  > designer le 2026-07-10)** : construire Scierie/Mine **sans défense** offre ces lieux
  > (et leurs cartes, distribuées à *tous*) aux chasseurs, qui s'y équipent trop vite.
  > Correctif visé : le Bâtisseur doit **défendre ses lieux de production avec un
  > monstre** avant de les exposer. Deux voies :
  > 1. **Laboratoire d'abord** puis, le raid suivant, jouer Laboratoire →
  >    *Expérimentation* → monstre gardien envoyé sur la Scierie/Mine.
  > 2. **Cartes « Portail »** ✅ *(livrées le 2026-07-11)* — `PORTAL_INVOCATION_REVENANT`
  >    et `PORTAL_INVOCATION_BAT` : en PHASE2, invoquent un monstre gardien (Revenant
  >    5pv D6/D4, Chauve-souris 5pv D4/D6) sur un lieu choisi, **gratuitement** (l'effet
  >    Expérimentation du Labo sans construire le Labo), 4 copies de chaque dans le deck
  >    vampire. Monstre **persistant**. Permet le start Scierie → carte Portail →
  >    défendre. **Reste à faire (étape 3)** : que le bot Bâtisseur JOUE ces cartes pour
  >    protéger ses lieux de production — le câblage backend/UI est fait, l'évaluateur
  >    bot arrive avec la grille §8.
- **Corrupteur** : l'Autel est finançable dès le raid 1 si besoin (coût : 4 pierre,
  1 bois, 2 fer, 50 âmes — le vampire démarre avec 10 pierre et 100 âmes × nb de
  chasseurs). Le déclencheur n'est donc pas la ressource mais la **stratégie** : le
  score monte quand la victime est choisie et la Marque posée/en main, pas forcément
  en début de partie. Une fois construit, l'Autel est très « collant » : tant que le
  vampire s'y rend, le combat y bloque la purification à l'eau bénite — il ne reste
  aux chasseurs que la purification par le combat (≥ 1 dégât au vampire et aucune
  morsure réussie sur place ce raid). Ensuite Salle de bal (danse macabre = corruption
  sur attaque réussie).
- **Prédateur** : Laboratoire (eau/herbes à récolter au Lac/Forêt d'abord, ou
  transmutation) → monstres pour harceler pendant que le vampire chasse. Ensuite
  Salle de bal.

Entrées du score de construction : ressources actuelles vs coût, revenu attendu par raid
restant estimé, pression chasseur (Incendiaire déjà vu ?), météo (Cyclone), et le
`corruptionFocus`/`economy` du profil.

---

## 7. Stratégie de corruption (vampire) et contre-jeu (chasseur)

Mécanique vérifiée (`CorruptionService`, `CombatService`, `LocationEffectService`) :
- Niveaux 0→3 ; L1 = −1 ATK/DEF ; L2 = instable (d6 en préphase : 1–3 → succombe ce raid,
  le vampire peut l'assigner à attaquer un allié ou récolter pour lui) ; L3 = **serviteur
  définitif** (perd ses cartes, son or devient des âmes, change de camp).
- Sources de +1 corruption : **morsure** (d20 > 8 après un combat ; +50 âmes ; clone :
  d20 > 12, +30 âmes), **Autel corrompu** (action du vampire sur place), **Marque
  ténébreuse** (1×/raid quand le marqué croise le vampire — combat ou assignation
  d'instable), **Danse macabre** (Salle de bal : attaque réussie → +1).
- Contres chasseur : **Eau bénite** mode REDUCE (−1 corruption) et mode CLEANSE (dissipe
  la Marque), **Chapelet sacré** (annule une morsure réussie), **armure de plates argent**
  (jet de d4 pour repousser), **Autel purifié** (−1 corruption sur place).

**Plan du bot Corrupteur — « rush serviteur »** (exactement le scénario décrit par le
game designer) :

1. **Choisir une victime** et s'y tenir : déjà corrompue > faible (PV, équipement) >
   sans eau bénite estimée (aucun achat d'eau bénite observé) > isolée souvent.
2. **Empiler les sources** sur la même cible et le même raid quand possible :
   Marque ténébreuse (PREPHASE3) + aller à son contact (combat → morsure, la Marque
   déclenche en plus son +1 au contact) + Autel corrompu si construit + Faim
   irrépressible si l'attaque risque d'échouer (morsure quand même possible).
3. **Vitesse avant tout** : chaque raid où la cible reste à corruption 1–2 est une
   fenêtre pour une Eau bénite adverse. D'où le combo « 2 corruptions dans le même
   raid » (Autel + morsure, ou Marque + morsure) pour passer de 1 à 3 sans fenêtre de
   purification.
4. **À corruption 2, exploiter l'instable** : sur un jet raté (1–3), assigner la cible
   contre le chasseur le plus dangereux, ou en récolteur pour affamer l'équipe.

**Contre-jeu du bot chasseur** (symétrique, toute stratégie confondue) :
- corruption 1 + carte Eau bénite en main → REDUCE **avant** d'atteindre 2 si le vampire
  est en stratégie corruption (Marque vue, Autel construit) ;
- corruption 2 → priorité absolue : REDUCE immédiat, sinon Autel purifié, sinon éviter
  tout contact avec le vampire (le d6 d'instable ne se déclenche qu'en préphase, mais la
  morsure ne peut arriver qu'au contact) ;
- marqué → CLEANSE tôt ; Chapelet sacré prophylactique si on prévoit de croiser le
  vampire ; acheter de l'eau bénite en boutique (150 or + 4 eau) dès que le vampire
  révèle une stratégie corruption.

---

## 8. Grille des 29 cartes Action

Format : **Fenêtre** (vérifiée dans `ActionCardService`) · **Jouer quand** (heuristique,
facteurs de score) · **Retenir/Combo** (pourquoi attendre).

### Chasseurs (16)

| Carte | Fenêtre & conditions (code) | Jouer quand | Retenir / Combo |
|-------|------------------------------|-------------|------------------|
| **EAU_BENITE** | modale 3 modes : REDUCE (−1 corruption), CLEANSE (dissipe Marque), ATTACK (+4 dégâts sacrés ce raid) | REDUCE si corruption ≥ 2 (urgence max) ou ≥ 1 face à un vampire Corrupteur ; CLEANSE si marqué ; ATTACK si combat vs vampire imminent et corruption saine | La carte la plus polyvalente : en garder **toujours une** si le vampire a l'Autel ou la Marque. ATTACK + Embuscade = burst maximal |
| **FUMIGATION_AIL** | PHASE1, avant de choisir son lieu | Protéger une grosse récolte (Manoir) ou un allié faible ; bloquer l'accès du vampire à son propre Autel corrompu | Peu utile si le vampire est loin ; à jouer sur la base de l'OpponentModel |
| **PISTEUR** | PHASE1 (aussi achetable 100 or) | Profil Traqueur + rapport de force favorable (équipe boostée, vampire blessé) | Combo naturel : plusieurs chasseurs pistent → Embuscade quasi garantie au raid suivant |
| **FEU_DE_CAMP** | PREPHASE3, météo Crépuscule/Nuit obscure/Nuit claire, sur son lieu | Annuler un malus météo qui gêne MON combat ; **indispensable sous Nuit obscure pour ré-autoriser Filet/Fosse** | Prérequis de combo pièges sous Nuit obscure |
| **NET** (Filet) | PREPHASE3, adversaire sur mon lieu, interdit Nuit obscure sans feu | Combat certain sur mon lieu : d20 > 12 → −1 DEF cible (monstre : −1 ATK et DEF) | S'empile avec Fosse + Embuscade sur la même cible avant le burst |
| **PIT** (Fosse) | PREPHASE3, ennemi/monstre sur mon lieu, interdit Nuit obscure sans feu | Comme Filet, en plus fort (−2 DEF si la victime rate d20 < 8) | Même combo ; jouer Fosse avant Filet si on n'a qu'un tour |
| **PROVOCATION** | PREPHASE3, ≥ 1 ennemi sur mon lieu ; **peut interrompre Passage secret / Image miroir** | Tank (PV hauts, armure) protège un allié fragile ; ou **contre-fuite** : le vampire tente de s'échapper → le provoquer l'en empêche | L'anti-PASSAGE_SECRET officiel : à garder si le vampire campe au Manoir |
| **INCENDIAIRE** | PREPHASE3, sur le lieu à brûler ; détruit l'infra en fin de raid | Score = valeur de l'infra pour le vampire : Autel corrompu/Labo avec monstres > Salle de bal > Mine/Scierie | L'arme anti-Bâtisseur ; anticiper la défense du lieu |
| **AMBUSH** (Embuscade) | PREPHASE3, ≥ 2 chasseurs vivants + ≥ 1 ennemi sur mon lieu, 1×/lieu/raid | Score ∝ nb de chasseurs présents et leurs buffs ; **sauf** si PV vampire très bas (une attaque normale suffit, garder la carte) | Le finisher d'équipe : synchroniser avec potions/élixirs/Eau bénite ATTACK/Filet/Fosse le même raid |
| **LONELY** (Solitaire) | PREPHASE3, sur un lieu | +1 ATK/DEF **par chasseur absent** : excellent en duel seul contre le vampire | Anti-synergie avec Embuscade (qui veut du monde) ; parfait pour le tank provocateur isolé |
| **BLESSED_STAKE** (Épieu béni) | PREPHASE3, persistant, un seul actif | Combat vs vampire probable ; se déclenche contre un ennemi présent | Se pose à l'avance (persistant) : à jouer dès qu'on part en chasse, pas au dernier moment |
| **SACRED_ROSARY** (Chapelet) | PREPHASE3, persistant | Annule une morsure réussie : corruption 1–2 + contact vampire probable = score max ; réponse à Faim irrépressible | Assurance-vie du rush corruption adverse ; le poser AVANT le combat risqué |
| **CHARISMATIQUE** | PHASE4, 1×/raid, bloqué par Présence écrasante | Juste avant une grosse séance d'achats (prix réduits, revente améliorée) | À combiner avec la revente massive de ressources excédentaires |
| **MARCHAND_ITINERANT** | PHASE4, 1×/raid | Or disponible et rien d'urgent : bonus aléatoire à bon rapport | Après Charismatique si les deux sont en main |
| **CRATE_LAKE / CRATE_MANOR** | PREPHASE3, être au Lac / au Manoir, 1 caisse/raid | Quasi toujours quand on est au bon endroit et pas en danger | Influence le choix de lieu en PHASE1 (« j'ai la caisse du Lac → Lac ») |

### Vampire (13)

| Carte | Fenêtre & conditions (code) | Jouer quand | Retenir / Combo |
|-------|------------------------------|-------------|------------------|
| **CATACLYSME** | PHASE2 | REMPLACE la météo en cours par 2 météos de TEMPÊTE, à choisir dans `WIND / STORM / RAIN / BLIZZARD` (**2 statuts max**, plus de base naturelle). **Le vampire lanceur n'est PAS affecté par ces 2 météos ce raid** (immunité, humain comme bot) | Arme OFFENSIVE à sens unique : STORM (−2 déf. des chasseurs → mes coups portent, sans −2 pour moi) + BLIZZARD (gèle les élixirs de survie des chasseurs, mais pas les miens). Efface une météo défavorable au passage (Jour ensoleillé, etc.) |
| **CLONES_OMBRE** | PHASE2 | Chasseurs dispersés + stock d'âmes (option morsure payée en âmes par clone, réussite d20 > 12, +30 âmes, +1 corruption) | Corrupteur : clones mordeurs = corruption en parallèle sur plusieurs cibles |
| **IMAGE_MIROIR** | PHASE2, interdit si provoqué | DÉFENSIF (chasse groupée sur moi probable : poser un leurre, choisir mon vrai lieu après révélation) OU OFFENSIF (surgir sur 1-2 chasseurs isolés vulnérables — corruption 1-2 ou PV bas — pour les achever/convertir) | Vulnérable à Provocation ; **contre Pisteur** (le traqueur suit la carte posée en PHASE2, la matérialisation se décide après révélation) ; alterner avec Passage secret pour rester illisible |
| **PRESENCE_ECRASANTE** | PREPHASE3 | Les chasseurs empilent des prépas (Filet/Fosse/Embuscade) sur MON lieu → tout annuler + bloquer leurs cartes ce raid | Le contre direct de l'Embuscade : à garder tant que les chasseurs jouent groupé |
| **ECLIPSE** | PREPHASE3 | Je veux combattre ce raid : météo → Pleine lune (+2 ATK) | **Combo signature : Éclipse → Lune de sang** le même raid (+4 ATK au total) |
| **BLOOD_MOON** (Lune de sang) | PREPHASE3, requiert Pleine lune | Après Éclipse, ou météo naturelle 12 | Le pic d'attaque à synchroniser avec Faim irrépressible / Valse sanguinaire |
| **VOILE_DE_BRUME** | PREPHASE3, cible un lieu | Récolte ÷2 + −1 DEF chasseurs sur le lieu : harcèlement éco (lieu riche et peuplé) ou débuff pré-combat | Bâtisseur : ralentir l'éco adverse sans combattre |
| **FAIM_IRREPRESSIBLE** | PREPHASE3 | Morsure possible même sans dégâts ce raid : cible à corruption 1–2 présente, ou chasseur trop coriace pour être blessé | Cœur du rush corruption ; combo Marque + Autel le même raid |
| **MARQUE_TENEBREUSE** | PREPHASE3, cible un chasseur | Poser tôt sur LA victime choisie (+1 corruption à chaque contact, 1×/raid) | Se dissipe à l'Eau bénite CLEANSE → forcer le tempo après la pose ; la Liche peut aussi marquer |
| **AFFAIBLISSEMENT_OCCULTE** | PREPHASE3, cible un chasseur vivant | −2 ATK ce raid sur le meilleur attaquant adverse, juste avant un combat que je ne peux pas éviter | Défausse défensive de dernier recours quand l'Embuscade est inévitable |
| **PASSAGE_SECRET** | PREPHASE3, **être au Manoir**, interdit si provoqué ; destination choisie après révélation | Fuir un rapport de force défavorable (plusieurs chasseurs boostés sur moi) **ou** frapper par surprise un isolé | Justifie de camper au Manoir ; **contre Pisteur** (le traqueur est déjà déplacé quand la fuite se résout) ; contré par Provocation → varier avec Image miroir |
| **AVIDITE_NOCTURNE** | PHASE4, 1×/raid | Les chasseurs sont riches (grosse récolte d'or au Manoir observée) et vont acheter | Anti-boutique ; synergie avec Voile de brume (éco totale) |
| **ADVANCED_TRANSMUTATION** | PHASE4, 1×/raid | Excédent de ressources brutes à convertir (offre améliorée type marchand) | Outil du Bâtisseur pour financer âmes/constructions |

---

## 9. Potions, élixirs, boutique (heuristiques rapides)

- **Potions** (PREPHASE3, participant à un combat imminent, gelées sous Blizzard) :
  Vie si PV < ~50 % ; Force/Endurance selon rôle attaquant/défenseur ; Focalisation
  gardée pour les jets critiques (embuscade, morsure défensive).
- **Élixirs** (1/raid) : à **claquer avec le combo** — Rage sur le raid d'Embuscade,
  Résilience/Invulnérabilité quand le vampire Prédateur fond sur moi, Invisibilité pour
  frapper sans riposte. Ne jamais les boire « parce qu'on les a ».
- **Boutique chasseur** : priorité équipement (D4→D6→D8 : 2 bois+2 fer puis 3+3) >
  eau bénite si menace corruption > potions. **Argent** : ne sert qu'au craft du
  tier 3 à la Forge (3–4 argent par pièce, dont l'armure de plates anti-morsure :
  4 fer + 4 argent) — n'en acheter que si la Forge est construite ou imminente.
  Achats de cartes : coût croissant (n+1)×50 → 1 à 2 par raid max.
- **Banque** (chasseurs) : le Prudent y met ses pierres tôt (10/15/20 → +50 à +100 or/raid
  pour toute l'équipe) ; les autres profils y contribuent l'excédent.
- **Vampire PHASE4** : transmutation pour compléter un coût de construction ;
  acheter des cartes Action quand les âmes débordent.

---

## 10. Plan d'implémentation incrémental

1. **Bot légal** ✅ *(implémenté le 2026-07-09)* — `BotManager` + `BotOrchestrator` +
   tous les points **[O]** du §4 avec des choix naïfs. Livrable : une partie complète
   humain vs bot sans blocage. *Réalisation : un unique `BotBrain` naïf (les cerveaux
   par rôle arrivent à l'étape 3) + `BotController` (endpoints lobby) ; validé par une
   partie bots-seuls de 16+ raids (combats, morsures, instables) sans blocage.
   Découverte au passage : `combatsQueue` ne reflète jamais les jets (copies JSON,
   les jets vivent sur le miroir `currentCombat`) → le bot avance en PHASE4 via
   `advance` explicite, comme le front. Après les premiers retours de jeu réel :
   ajout d'un **rythme par stabilité d'état** (l'orchestrateur mesure depuis quand
   l'état n'a pas changé, le cerveau exige un délai par type d'action, doublé d'un
   mode « humains présents » où le bot laisse le front faire les progressions
   automatiques et ne sert que de filet de sécurité).*
   *Piège de rythme découvert à l'étape 3b (tri-état `Play` dans BotBrain) : en
   préphase, SKIP_READY (1 s) était INFÉRIEUR aux délais des cartes/potions
   (1,5–3 s) et chaque « prêt » d'un bot réinitialise la stabilité → en bots-seuls
   les bots se déclaraient tous prêts en cascade et la fenêtre des cartes
   n'arrivait JAMAIS : aucune carte ni potion n'était jouée en préphase. Règle :
   les CONDITIONS de jeu s'évaluent sans délai (`Play.WAIT` bloque le « j'ai
   fini ») et le délai de réflexion ne s'applique qu'au moment d'agir.*
   *Réglage des délais (game designer, 2026-07-14, affiné après son test) : les
   ACTIONS du bot sont rapides — choix de lieu P1/P2 : 300 ms
   (`SELECT_LOCATION`), achats PHASE4 : 300 ms (`SHOP_ACTION`) + l'orchestrateur
   autorise en PHASE4 UNE ACTION PAR BOT ET PAR TICK (les achats sont
   indépendants, sans animation — sinon 15-20 dépôts de banque en série font
   traîner la phase), cartes/effets : 800 ms, jets de dé : 500 ms. Deux
   exceptions « comme un humain » : l'AFFICHAGE d'un choix visible en modale
   (cible de Filet, draft d'Expérimentation, cible d'Embuscade) =
   `MODAL_DISPLAY` 2 s. Morsure (réglage affiné) : jet rapide
   (`BITE_ROLL` 0,5 s) puis RÉSULTAT affiché 2,5 s (`BITE_NEXT`) avant
   d'enchaîner. Les résultats de duel restent affichés comme en partie
   humaine (`COMBAT_NEXT_ALONE` = `FRONT_DISPLAY_MS`, miroir de
   SPECTATE_HOLD_MS). Météo inchangée.*
2. **Économie** ✅ *(implémentée le 2026-07-10)* — choix de lieu pondéré (§5),
   constructions par archétype (§6), boutique, banque, transmutation, potions basiques.
   *Réalisation : `BotProfile` (presets économiques Bâtisseur/Prudent — les archétypes
   agressifs et les pivots viennent à l'étape 3) ; dans `BotBrain` : `pickHunterLocation`
   (score par déficit de ressources vers des cibles TARGET_*), `chooseConstruction`
   (ordre Scierie→Mine→Bibliothèque→Forge, garde cyclone/fumigation/ressources ;
   planConstruction joue aussi le lieu), `pickVampSideLocation` (Manoir pour les âmes),
   `maybeUsePotion` en PREPHASE3 (Vie si PV bas, sinon Force/Endurance une fois/raid,
   proxy « ennemi sur mon lieu »), et une PHASE4 économique : chasseur = équipement →
   banque → potion de réserve ; vampire = rééquilibrage bois/fer par transmutation.
   Validé bots-seuls : vampire construit les 4 infras dans l'ordre, banque niveau 2,
   chasseurs qui forgent/achètent/déposent, aucun blocage ni exception sur 6+ raids.
   Non couvert (renvoyé à l'étape 3) : achat de cartes Action, argent/forge tier 3,
   choix d'effet de lieu non triviaux (vol/prédiction/expérimentation).*
3. **Cartes & combos** — les 29 évaluateurs (§8), corruption/contre-corruption (§7),
   élixirs synchronisés, `ThreatAssessment` complet. *Fait par lots testables :*
   - *3a — Cartes vampire + défense du Bâtisseur ✅ (2026-07-12) :*
     - *Achat de cartes Action en PHASE4 avec le SURPLUS d'âmes : on réserve d'abord
       de quoi Expérimenter-défendre les 3 bâtiments VISÉS par le Bâtisseur (Labo +
       Scierie + Mine → 100 âmes chacun = 300 au max), moins ceux qu'un Portail en
       main peut couvrir sans âmes ; puis on achète au-dessus. **La réserve se base
       sur le PLAN (3 bâtiments), pas sur les bâtiments déjà construits** : sinon en
       début de partie (0-1 bâtiment bâti → réserve ~0) le bot dilapide ses âmes en
       cartes, et comme les raids de construction ne récoltent aucune âme, il ne peut
       plus payer l'Expérimentation quand les 3 bâtiments existent enfin. Gros stock
       (début vs 6 chasseurs ≈ 600 âmes) → défend ET achète 2 cartes/raid ; peu d'âmes
       (vs 3 chasseurs ≈ 300) → tout pour la défense, cadence construire→défendre
       →construire→défendre sans jamais tomber sous 100. (Bug du 2026-07-13, log R5 :
       ~60 âmes < 100 → le vampire construisait la Bibliothèque au lieu de défendre
       la Mine.)*
     - *Construction ADAPTATIVE (précisée par le game designer) : le **Laboratoire**
       est le moteur de défense durable ; on n'expose une éco (Scierie/Mine) que si
       on peut la défendre avec un Portail → au plus min(Portails,2) éco avant le
       Labo, sinon Labo prioritaire. Sans Portail (cas de départ), ouverture
       Labo-d'abord. (Prêt pour la future règle « pioche N cartes au départ ».)*
     - *Défense : **Portail** (PHASE2, ne bouge pas le vampire → défend + construit le
       même raid) et **Expérimentation du Labo** (PREPHASE3, sans Portail : le vampire
       VA au Labo au lieu de construire → cadence construire/défendre alternée). La
       défense-via-Labo est décidée AVANT la construction dans playPhase2 (sinon le
       vampire construit chaque raid et n'Expérimente jamais — bug corrigé le 2026-07-13).
       Cible le bâtiment le plus vulnérable — **priorité au Labo lui-même**, puis Scierie,
       Mine. Re-défense auto quand un gardien meurt (pickDefenseLocation saute les gardés).
       Validé bots-seuls sur les deux chemins : avec Portails (défend Labo+Scierie+Mine en
       construisant), et sans (R1 Labo → R2 Expérimentation sur le Labo → R3 Scierie…).
       L'Expérimentation du bot passe par le **draft** (updateLaboratoryExperimentDraft →
       événement DRAFT_UPDATED, avec un délai) AVANT la résolution, pour que les
       spectateurs voient le monstre + le lieu se sélectionner comme le ferait un humain.*
     - *Combat : **Éclipse → Lune de sang** et **Faim irrépressible** en PREPHASE3
       quand un combat implique le vampire (câblés, conditionnels à la pioche + combat).*
     - *Fix serveur au passage : `resolveLaboratoryExperiment` marque désormais l'effet
       résolu SYNCHRONIQUEMENT (comme `resolveAltarCorrupt`) — sinon il restait « pending »
       ~5 s et un client re-résolvant dans la fenêtre échouait (bénéfique aussi aux humains).*
   - *3b — Cartes de combat chasseur, en deux passes.* **Passe 1 (implémentée) —
     cartes de SURVIE** :
     - *Acquisition : `tryHunterEconomy` achète une **Eau bénite** (150 or + 4 eau)
       dès que le vampire a un moteur de corruption visible (Autel corrompu, Marque
       en jeu) ou que le bot est déjà corrompu ; sinon il pioche 1 carte d'action
       aléatoire par raid avec le SURPLUS d'or au-dessus d'une réserve de 150
       (l'or ne sert quasiment qu'à ça côté chasseur).*
     - *Fix scoring de lieu au passage : le **Lac** pèse désormais `eau×2 + herbes`
       (l'eau, sa ressource principale, ne vient de nulle part ailleurs) — avant ça
       la Carrière gagnait toujours (la pierre déposée en banque retombe à 0 → son
       besoin restait au max) et les bots n'avaient JAMAIS d'eau, donc jamais
       d'Eau bénite ni de potions.*
     - *Jeu en PREPHASE3 (`tryHunterCombatCards`, priorité survie) : Eau bénite
       **CLEANSE** si marqué > **REDUCE** si corruption ≥ 2 (instable) > **ATTACK**
       si duel contre le vampire à venir — on n'ouvre la modale que si un mode est
       utile, et un repli REDUCE garantit de ne jamais la laisser bloquée ;
       **Chapelet sacré** si corruption ≥ 1 et vampire au contact ; **Épieu béni**
       si vampire/serviteur au contact. En PHASE3, le bot lance le jet de son
       Épieu quand `currentAction BLESSED_STAKE` lui appartient.*
     - *Validé bots-seuls : morsure → corruption 1 → détour par le Lac → achat
       Eau bénite R5 → carte GARDÉE en main tant que corruption < 2 (rétention
       voulue). Chapelet/Épieu ne sortent que sur contact vampire — rare tant que
       les chasseurs ne traquent pas (étape 3c).*
     **Passe 2 (implémentée) — pièges & coordination : Filet, Fosse, Embuscade** :
     - *Jeu en PREPHASE3 (suite de `tryHunterCombatCards`) : **Embuscade** — le
       cœur de la carte (game designer, 2026-07-14) est le NOMBRE de chasseurs
       présents (+N ATK chacun, l'inverse de Solitaire) : on exige au moins la
       MOITIÉ des chasseurs vivants (min 2 — pas « au complet », trop dur à
       réunir quand la partie compte beaucoup de joueurs), un ennemi JOUEUR sur
       mon lieu et un lieu pas déjà embusqué ; modale de cible résolue au tick
       suivant (vampire prioritaire) ; rétention secondaire si la cible est
       presque morte (≤ 4 PV : une attaque normale suffit). **Fosse avant Filet**
       (« si on n'a qu'un tour ») — les pièges sont RÉSERVÉS au vampire/serviteur
       (game designer) : on ne les joue contre un monstre que s'il est PUISSANT
       (pas un simple Revenant/Chauve-souris) ou si le chasseur risque d'y rester
       (≤ 6 PV) — `trapWorthyTargetOnMyLocation` ; interdit Nuit obscure sans feu
       de camp respecté (`trapsBlockedByNight`, miroir serveur).*
     - *Résolutions en PHASE3 (`playPhase3`) : **Filet** en deux temps quand le
       `currentAction NET` m'appartient — `chooseNetTarget` (sélection visible dans
       la modale de tous ; le `targetId` entre dans l'empreinte de stabilité de
       l'orchestrateur → délai « humain » avant le jet) puis `resolveNet` ;
       **Épieu béni** : jet quand `currentAction BLESSED_STAKE` m'appartient.
       La **Fosse** ne demande rien de plus : victime joueur = déjà géré (passe 1
       de l'étape 1), victimes monstres = batch auto-résolu par le serveur
       (`prepareNextRaidAction`), le bot n'a que le `combatContinue` de lecture.*
     - *Cible des pièges : vampire > serviteur > monstre (`pickNetTargetId`),
       embuscade : vampire > serviteur (`pickAmbushTargetId`).*
     - *Validé bots-seuls (raid 7-8, cartes injectées en SQL pour le test) :
       4 chasseurs posent Fosses ×2 + Filets ×2 face aux gardiens (Revenant,
       Chauve-souris), jets de Filet en deux temps (cible visible puis d20),
       Fosses batch auto-résolues, mods empilés au combat (DEF −2/fosse, −1
       ATK+DEF/filet). 0 exception. Embuscade/Épieu suivent le même patron
       (modale → résolution) mais exigent le VAMPIRE au contact — à observer en
       partie réelle (les chasseurs ne traquent pas avant 3c).*
     - *Fix serveur au passage : `buildModBreakdownLines` (CombatService) utilise
       `entityName` et plus `nameOf` — les mods d'un MONSTRE piégé affichaient
       son UUID brut dans l'historique.*
     - *Fixes du test réel du game designer (2026-07-14) : (1) **Présence
       écrasante n'annulait pas le bonus d'une Embuscade déjà résolue** — elle
       retirait `ambushHuntersByEnemy` (riposte restaurée) mais pas les
       `raidMods ACTION:AMBUSH:ENG` (+N ATK) → corrigé dans
       `cancelHunterPrephase3ActionsOnVampLoc` ; (2) **NaN sur le dé du batch
       de Fosses** (front) : `parsePitLine` splittait
       « hunterId:targetId:location:roll » sur ':' mais l'id d'un BOT contient
       déjà un ':' (« bot:<uuid> ») → champs décalés, `parseInt("sawmill")` =
       NaN → parsing depuis la FIN de la ligne.*
   - *Cartes utilitaires chasseur (implémentées 2026-07-15, dans
     tryHunterCombatCards / tryHunterEconomy) : **Feu de camp** (sous
     Crépuscule/Nuit obscure/Nuit claire + combat sur mon lieu → annule le malus
     météo ET ré-autorise mes pièges, joué AVANT eux) ; **Solitaire** (duel
     vampire ISOLÉ : ≥ 2 chasseurs absents → +N ATK/DEF, l'inverse de
     l'Embuscade) ; **Provocation** — deux usages (INUTILE en 1v1) :
     TANK (≥ 2 chasseurs sur mon lieu, je suis le plus costaud, aucune Embuscade
     jouable → je prends les coups à la place des alliés fragiles) ou ANTI-FUITE
     (bloquer un Passage secret / Image miroir en cours) ; modale de cible
     résolue comme l'Embuscade ;
     **Charismatique** (PHASE4, −20/achat, joué avant les emplettes si ≥ 150
     or).*
     **Fumigation d'ail (implémentée — 3 usages, priorité autel > salle de bal >
     protection) :** jouée en PHASE1 AVANT le Pisteur, de façon ATOMIQUE (carte +
     `selectLocation` dans le MÊME `tryPlay`) → le lieu est fumigé IMMÉDIATEMENT
     (`shouldFumigate` / `fumigationTargetLocation` / `fumigationDenialLocation`). Le
     lieu posé devient INTERDIT au vampire / serviteurs / clones.
     (1) **Déni de l'Autel CORROMPU** : dès qu'UN chasseur est en corruption 2,
     N'IMPORTE QUEL chasseur ayant la carte (elle n'est pas forcément au corrompu) va
     fumiger l'Autel corrompu (en main — donnée à tous —, non fumigé, sans gardien) →
     coupe le moteur de corruption à distance. (2) **Déni de la Salle de bal** :
     personne en urgence de corruption + des alliés traquent (Pisteur) → un
     non-traqueur va fumiger la Salle de bal (refuge Danse macabre). (3) **Protection**
     (le plus fréquent) : cible du vampire (corruption 2 sans déni d'Autel possible,
     ou PV ≤ 8) → je fumige mon propre lieu de récolte (Forge si un T3 y est forgeable,
     sinon récolte ; jamais la traque) → supprime la morsure DIRECTE.
     *UNICITÉ du dénieur garantie par la pose ATOMIQUE : PHASE1 est `multiPerTick` et
     l'orchestrateur boucle sur les chasseurs SÉQUENTIELLEMENT (chaque `tryPlay` =
     `loadForUpdate → save`) → le 1er qui fumige un lieu le bloque, les suivants du
     même tick le voient déjà fumigé → un SEUL dénie (résout l'ancien pile-on du flux
     2 ticks). Alerte front existante : `window.confirm` « aussi utiliser Pisteur ? »
     quand un humain a préparé une fumigation ET a la carte Pisteur (game.component.ts
     ~3450) — fumigation + Pisteur NON exclusifs pour un humain.*
   - *3c — Corruption & ciblage (§7), en trois passes.* **Passe 1 (implémentée) —
     moteur de corruption & harcèlement vampire** :
     - *`tryVampireCombatCards` étendu (PREPHASE3) : **Marque ténébreuse** posée
       TÔT sans attendre un combat (victime = chasseur non marqué le plus
       corrompu, puis PV les plus bas — info publique ; une seule marque à la
       fois) ; **Affaiblissement occulte** en combat inévitable (−2 ATK sur le
       chasseur le mieux armé de mon lieu) ; **Voile de brume** : mon lieu si
       combat (−1 DEF), sinon harcèlement éco (récolte /2) sur le lieu à ≥ 2
       chasseurs. Toutes en modale 2 temps (useAction → MODAL_DISPLAY 2 s →
       resolve), résolution des modales prioritaire même sans combat.*
     - *`tryVampEconomy` : **Avidité nocturne** jouée tôt en PHASE4 quand l'or
       moyen des chasseurs vivants ≥ 150 (l'or est public).*
     - *Contre-jeu chasseur affiné (§7) : Eau bénite **REDUCE dès corruption 1**
       quand le vampire révèle une stratégie corruption (Autel corrompu ou
       Marque en jeu) — avant, on attendait le niveau 2.*
     - *Validé bots-seuls (cartes injectées) : R1 Marque + Voile (Carrière ×4
       chasseurs, récoltes /2) + Avidité en PHASE4 → R2 les 5 chasseurs achètent
       l'Eau bénite (prophylaxie) → R4/R6 CLEANSE dissipe, le vampire re-marque
       (2ᵉ exemplaire) : le duel de tempo Marque ↔ purification de la grille
       fonctionne en boucle. 0 exception. Course bénigne connue : une modale
       ouverte en toute fin de préphase peut mourir à la transition (carte NON
       consommée, rejouée au raid suivant — pareil pour un humain au timeout).*
     **Passe 2 (implémentée) — survie vampire & clones** :
     - *`tryShadowClones` (PHASE2, tri-état) : d4 clones envoyés sur les lieux de
       récolte (Carrière/Forêt/Lac — les positions adverses sont cachées en
       PHASE2, on parie comme un humain), morsure activée (10 âmes/clone) si le
       surplus au-dessus de la réserve de défense le permet. Le WAIT de la modale
       bloque le choix de lieu du vampire (sinon il déclenche l'avancement de
       phase et la modale meurt).*
     - *Survie (PREPHASE3, ≥ 2 chasseurs sur mon lieu) : **Passage secret** si au
       Manoir et pas provoqué (destination = lieu sûr, bâtiments à âmes d'abord ;
       modale SANS timer en fin de préphase → le bot DOIT la résoudre) ; sinon
       **Présence écrasante** dès ≥ 2 « poids » de prépas posées sur mon lieu
       (filet/fosse = 1, embuscade = 2), avec ANTICIPATION : tant que des
       chasseurs de mon lieu n'ont pas dit « j'ai fini », le vampire ne se
       déclare pas prêt (il attend de voir leur jeu).*
     - *Image miroir et Cataclysme → reportés avec le raid d'assaut (post-3c),
       implémentés en 3d passe 3 ci-dessous.*
     - *Validé bots-seuls : R7, 5 chasseurs traquent le vampire au Labo,
       empilent Embuscade+4 Fosses+Épieu → le vampire ATTEND puis « déchaîne une
       présence écrasante » : tout annulé + cartes bloquées ce raid.*
     **Règles stratégiques du game designer (test réel du 2026-07-14)** :
     - *Marque ténébreuse : SEULEMENT au contact (chasseur sur mon lieu) — le +1
       ne déclenche qu'au croisement, et un combat = marque + morsure possible
       le même raid. La poser sur un absent est un gâchis.*
     - *Eau bénite : REDUCE dès corruption 1 (le mod Affaibli −1 ATK/DEF pèse
       toute la partie) ; ATTACK est la PIRE utilisation sauf pour ACHEVER un
       vampire à ≤ 20 % PV — sinon garder la fiole (la corruption va monter).*
     - *Forge (vampire) : à construire quand les chasseurs prennent l'avance en
       équipement (tous 2×T1 boutique ; impératif à 2 tiers d'écart) — et
       l'UTILISER : l'effet Forge équipe le vampire (V_* T1-T3). Le vampire se
       stuffe aussi via la Transmutation avancée dès que possible (carte → d6 →
       achat de l'offre, ressources d'abord puis âmes au-dessus de la réserve).*
     - *Bibliothèque : à construire ET utiliser — l'effet Étude pioche une carte
       d'action GRATUITE. Le vampire y va ~1 raid sur 3.*
     - *Le vampire ne campe plus le Manoir : Forge si craft dispo > chasse aux
       corrompus (1 raid sur 2 si PV > 50 % et une proie corruptible entamée
       existe — pari sur un lieu de récolte, positions cachées en PHASE2) >
       Bibliothèque (1/3) > Manoir.*
     - *Expérimentation : le monstre le plus fort PAYABLE — Aberration (600,
       raid ≥ 10) > Gargouille (400, raid ≥ 5) > Chauve-souris (100), marge de
       2 défenses basiques conservée.*
     - *Pisteur : les chasseurs aptes l'achètent (100 or, réserve Eau bénite
       gardée) et le jouent en PHASE1 — traque PRÉCISE, prioritaire sur le pari
       Labo/Manoir. (Anti-pistage vampire — Salle de bal — reporté.)*
     - *Combos NEUTRALISÉS (3e retour de test) : sous Embuscade résolue SANS
       Présence écrasante en main, la riposte est impossible → retenir
       Éclipse/Lune de sang/Faim (claquées pour rien) ; sous Provocation, une
       seule cible → retenir Éclipse/Lune (la Faim reste rentable : morsure du
       provocateur). La SURVIE passe désormais AVANT les combos : embusqué avec
       Présence écrasante en main → l'annulation part en premier, les combos
       suivent une fois la riposte restaurée. L'Affaiblissement (défensif)
       reste jouable même neutralisé.*
     - *RÈGLE DE JEU ajoutée (serveur, GameLifecycleService) : pioche initiale —
       chaque chasseur pioche 1 carte Action au départ, le vampire en pioche
       1 PAR CHASSEUR (l'ouverture « min(Portails,2) éco avant le Labo » de 3a
       devient effective).*
     - *Portail d'OUVERTURE : sans bâtiment à défendre, le Portail poste son
       monstre sur la Carrière PUIS la Forêt (harcèlement des récolteurs) ;
       dès que des bâtiments construits sont à (re)défendre, ils REPRENNENT la
       priorité (pickPortalLocation).*
     - *Rythme de préphase (4e réglage) : jouer une carte = rapide ; CHOIX dans
       une modale = 1 s (`MODAL_CHOICE`) ; AFFICHAGE d'une action/d'un choix =
       ~2,5 s (`PREPHASE_ACTION` = 2 500 ms : la stabilité espace les actions,
       chaque affichage vit 2,5 s — y compris après le choix d'un humain) ;
       sélection en 2 temps (draft, cible de Filet) = 2,5 s (`MODAL_DISPLAY`).*
     - *Constructions (corrigé par le game designer) : après la Mine →
       **Salle de bal** puis **Autel** puis Bibliothèque. La SALLE DE BAL est le
       refuge ANTI-PISTAGE (pisté → le vampire y va, l'effet Danse macabre
       l'avantage au combat) ET un lieu à défendre (les chasseurs y gagnent
       l'or ×2) — monstre BAS NIVEAU suffit. L'AUTEL est le moteur de
       corruption rapide, LE plus critique à défendre : gros monstre
       (Gargouille/Aberration réservés à Autel+Labo dans
       pickExperimentMonster), et si l'Autel n'a pas de gardien le vampire y
       va LUI-MÊME (défense par présence + corruption des visiteurs).
       pickDefenseLocation : Autel > Labo > Salle de bal > Scierie > Mine.*
     - *Deux fixes serveur : `resolveForge` consomme désormais l'effet
       SYNCHRONIQUEMENT (sinon fenêtre ~5 s → le vampire forgeait T2 puis T3 le
       même raid — règle : tout resolve* d'effet de lieu doit consommer l'effet
       avant store.save) ; `hasTrapTargetsOnLocation` ne compte plus les
       monstres morts (un Filet se préparait sans cible vivante → modale
       « choix de la cible » bloquée pour tout le monde).*
     - *5e retour de test : la récolte face à un CLONE est divisée par 2
       (HarvestService — annule l'ancien « nerf clones », confirmé par le game
       designer) ; les CLONES sont retenus sous météo défavorable aux attaques
       du camp vampire, sauf Éclipse en main (combo).*
     - *7e retour de test : face à une MEUTE de Pisteurs (≥ 2 traqueurs vivants,
       partie à plusieurs chasseurs), la Salle de bal construite prime sur TOUT
       en PHASE2 — y compris la re-défense par Expérimentation et la
       construction (`ballroomRefugeLocation`, évaluée avant
       `labDefenseLocation` dans playPhase2). Et sur les lieux critiques
       (Autel, Labo), les monstres moyens/forts sont PRIVILÉGIÉS dès leur
       déblocage (marge réduite à 1 défense basique dans
       pickExperimentMonster) — pas les moyens d'en mettre partout, donc
       Labo/Autel d'abord.*
     - *9e retour de test : morsure — fenêtre pré-jet ~0,3 s (BITE_ROLL 3,9 s),
       résultat 3 s inchangé. BUG DU TIMER DE PRÉPHASE résolu : le recalcul de
       readyForPhase3 (rappelé après chaque résolution) EFFAÇAIT les « j'ai
       fini » déjà donnés → la préphase attendait le timeout 30 s ; désormais
       les prêts du raid sont préservés (reset uniquement à l'entrée en
       préphase) et un recalcul qui aboutit à « tous prêts » raccourcit le
       timeout à 800 ms. Mains de départ : plus AUCUNE carte fixe — uniquement
       la pioche initiale (caisses et Transmutation avancée via la pioche).*
     - *8e retour de test : CLONES — le nombre est un d4 serveur (pas un
       choix) ; politique bot : UNE vague par raid, la DERNIÈRE carte réservée
       aux combos (Éclipse en main ou météo favorable). CAISSES abandonnées :
       enfin câblées côté bot (jouer au bon lieu → d6 → resolveCrateAction
       après lecture — le serveur attend ce resolve pour fermer la modale).*
     - *Modale de morsure « trop brève » (6 retours !) — VRAI bug enfin trouvé :
       `showBiteModal` compare `Date.now() >= biteNotBeforeMillis` dans un
       GETTER Angular, non réactif — aucun rendu ne survient au franchissement
       du seuil, la modale n'apparaissait qu'au PROCHAIN événement (le jet du
       bot lui-même), quels que soient les délais réglés. Fix : `setTimeout`
       armé à BITE_STARTED qui force un rendu au seuil (`biteRevealTimer`).
       Cycle final : résumé du duel 3,5 s → modale visible 2 s → d20 du bot
       (BITE_ROLL 5,9 s) → résultat 3 s (BITE_NEXT). Règle : tout getter
       comparant Date.now() à un seuil doit être doublé d'un timer de rendu.*
     **Passe 3 (implémentée) — traque du vampire (pivot de situation §3)** :
     - *`pickHuntLocation` : dès le raid 3, un chasseur APTE (arme ≥ tier 1,
       PV ≥ 12) rejoint l'escouade si ≥ 2 aptes au total — chacun évalue le même
       critère PUBLIC → convergence émergente sans coordination explicite. Cible
       = le lieu d'âmes probable du vampire : Laboratoire s'il est construit,
       sinon Manoir (fumigation exclue). Les inaptes continuent de récolter.
       Prioritaire sur le scoring de récolte dans `pickHunterLocation`.*
     - *C'est la traque qui déclenche en pratique Embuscade/pièges/Épieu (3b) et
       la survie vampire (3c passe 2) : validée bots-seuls (R5 : 2 aptes
       convergent sur le Labo ; R6-R7 : assauts à 4-5 chasseurs).*
     **Trois bugs de rythme découverts et corrigés au passage** (en plus du
     tri-état de 3b) :
     - *FAMINE d'orchestrateur : « une action par tick » + ordre FIXE de la liste
       → le vampire jamais évalué pendant que 5 chasseurs enchaînent leurs poses
       → round-robin sur le point de départ de la boucle (BotOrchestrator).*
     - *PRÊT TROP TÔT : le vampire skippait avant l'assaut → anticipation
       (Play.WAIT tant que les chasseurs de mon lieu n'ont pas fini).*
     - *ACTION RÉSOLUE BLOQUANTE : un `currentAction` résolu qui traîne (modale
       informative de Faim irrépressible…) verrouillait le bot derrière sa
       propre carte → toutes les gardes `a != null` exigent désormais
       `resolvedAtMillis == null`.*
   - *3d — Élixirs synchronisés + réglage.* **Passe 1 (implémentée) — jeu des
     élixirs (`maybeUseElixir`, PREPHASE3, avant les potions basiques)** :
     consommable premium 1/raid, RÉSERVÉ aux moments décisifs. Priorité
     SURVIE (danger de mort — PV ≤ ⅓ max face à un adversaire dangereux, ou
     vampire sous assaut de meute : Invulnérabilité = 0 dégât > Résilience =
     DEF ×2) puis BURST sur un vrai combo (chasseur : embuscade sur mon lieu,
     ou vampire au contact affaibli/en surnombre ; vampire : cible corruptible
     au contact et PV sains → Rage = ATK ×2 > Invisibilité = touche à coup sûr >
     Rapidité = 2 attaques). Hors de ces cas, l'élixir est GARDÉ. Acquisition :
     le vampire via l'Alchimie du Labo (6 eau + 6 herbes) et la Transmutation
     avancée. **Passe 2 (implémentée) — acquisition chasseur via le Marchand
     itinérant** (`tryMerchantItinerant`, PHASE4 : carte → d6 → achat en
     ressources puis or ; d6 3-4 = élixir, 1-2 = potion, 5-6 = équipement pris
     seulement s'il monte un tier ; joué dès le milieu de partie avec le surplus
     d'or au-dessus de la réserve Eau bénite). Raffinage 1er test : le vampire
     en SURNOMBRE (≥ 2 chasseurs, souvent embusqué) prend TOUJOURS un défensif,
     le burst n'est que pour le 1v1. Reste : réglage des seuils/rapport de
     force (à la charge du game designer en jouant).*
     **Forge chasseur (implémentée) :** la Forge fabrique **moins cher** que la
     boutique et est le **SEUL accès au T3** (la boutique plafonne à T2 ; armes/plates
     « argent » `H_*_T3_*`). C'est un bâtiment PARTAGÉ : construit par le vampire mais
     la carte « forge » est donnée à TOUS (`giveInfraCardToAllPlayers`) et l'effet
     Forge est agnostique au rôle (chasseur sur la forge SANS combat + ressources →
     instance d'effet). Le handler d'effet du bot forgeait déjà pour tout rôle ; il
     manquait le CHOIX du lieu et le CHOIX de l'objet. Ajouts :
     `pickForgeCraftLocation` (chasseur, forge construite & en main, **au moins un
     upgrade forgeable maintenant** — pas de garde PV : un échec par combat se
     rattrape en boutique ≤ T2) branché dans `pickHunterLocation` après la traque /
     avant la récolte ; **choix de l'objet = équilibrage atk/def vs le vampire**
     (`pickHunterForgeOption` : vampire ATK-lourd → je monte mon ARMURE, tanky → mon
     ARME, à égalité je rattrape mon slot en retard ; tier le plus haut dans le slot
     retenu, donc T3 en priorité). Le vampire, lui, garde `pickBestForgeOption`
     (son meilleur tier).*
     **Passe 3 (implémentée) — bloc vampire d'assaut (les cartes reportées
     post-3c)** :
     - *`tryCataclysme` (PHASE2, tri-état comme les Clones) : setup du RAID
       D'ASSAUT. **Cataclysme est restreint à la famille TEMPÊTE**
       `WIND / STORM / RAIN / BLIZZARD` (liste du front — AUCUNE ne « booste »
       l'attaque du vampire, ce sont des météos de champ de bataille). Le bot joue
       le combo offensif fixe **STORM + BLIZZARD** (`pickCataclysmeWeathers`) :
       STORM (−2 défense de TOUS → mes attaques/morsures brisent la garde des
       chasseurs) + BLIZZARD (gèle potions/élixirs → les chasseurs ne peuvent plus
       se sauver, et −1 attaque). Joué quand le vampire est apte à chasser
       (`vampFitToHunt`) et qu'aucune météo secondaire n'est déjà posée.
       **RÈGLES CORE-GAME ajoutées (game designer, humain + bot)** :
       1. **Les 2 météos REMPLACENT la météo en cours** (2 statuts MAX, plus de
          base naturelle) : `resolveCataclysme(first, second)` pose le 1er choix en
          base et le 2e en secondaire. Un Cataclysme actif se reconnaît désormais à
          « météo secondaire != null » (aucune autre source n'en pose) — inférence
          utilisée par toutes les gardes ci-dessous, sans nouveau champ. La notion
          de TROISIÈME météo a été entièrement supprimée du code (champ Game,
          record DTO, resets, front) — cf. mémoire.
       2. **Le vampire lanceur est IMMUNISÉ aux 2 météos** de son Cataclysme pour
          ce raid → arme à sens unique. Appliqué partout : mods de stats
          (`WeatherService.applyCataclysmeImmunity` retire du vampire les mods
          sourcés des 2 météos actives quand secondaire != null), gel des potions
          BLIZZARD (`CombatService.usePotion` + gardes bot/front exemptent le
          vampire), blocage construction WIND (WIND NATUREL seulement — seul le
          vampire construit), perte de ressources WIND (`applyWindRepairs(g,false)`
          : le village des chasseurs paie, pas le domaine du vampire).*
     - *`tryImageMiroir` (PHASE2, `shouldPlayImageMiroir`) — DEUX usages : (a)
       **DÉFENSIF anti-pistage** (traqué + pas de refuge Salle de bal) ; (b)
       **OFFENSIF chasse** (apte, PV > 50 %, et une proie JUTEUSE existe —
       chasseur à corruption 1-2 ou PV ≤ 8 : surgir sur 1-2 chasseurs isolés
       vulnérables pour les achever/convertir). En PHASE2 les positions sont
       CACHÉES (équité) → le déclencheur offensif est un PARI sur l'existence
       d'une proie ; le VRAI ciblage se fait en PREPHASE3 (positions publiques).
       Gardes communes : pas provoqué, pas déjà en évasion, et — critique
       anti-blocage — un lieu-leurre valide en main (`hasAnyMirrorDecoyCard`, sans
       lire les positions). Résolution en DEUX modales, comme Passage secret :
       SETUP (`pickMirrorDecoy` = OFFENSIF d'abord — poser le lieu d'une proie
       isolée via `mirrorOffensiveScore` — sinon le plus sûr) puis RESOLVE en fin
       de préphase (`pickMirrorRealLocation` = surgir sur la meilleure proie
       isolée parmi mon lieu + leurres, sinon le lieu avec le MOINS de chasseurs =
       esquive). Le vampire peut se déclarer prêt malgré le `pendingVampireEscape`
       (skipAction l'accepte) → la modale RESOLVE est créée à la fin de préphase,
       jamais de blocage.*
     - *Rush serviteur (Autel corrupteur) : le moteur était déjà câblé
       (`naiveEffectChoice` ALTAR → CORRUPT_SOULS pour profaner l'autel pur, puis
       CORRUPT sur autel profané) ; le CIBLAGE est désormais un vrai rush —
       `pickCorruptionRushTarget` concentre le +1 sur le chasseur le PLUS
       corrompu (< 3, départage PV bas) pour le convertir en SERVANT au plus vite
       (§7), au lieu du premier chasseur venu.*
4. **Réglage** — `OpponentModel`, bruit/délais humanisés, équilibrage des seuils en
   jouant.
   > **`OpponentModel` v1 implémenté (2026-07-24).** Service `bot/OpponentModel.java`
   > (mémoire en RAM par partie, comme `rotationByGame` — RIEN dans le blob `Game`,
   > zéro fuite snapshot). `observe(g)` (appelé chaque tick par l'orchestrateur)
   > mémorise UNE fois par raid les lieux RÉVÉLÉS (centre face visible dès la
   > PREPHASE3 = info publique) → fréquences par joueur. `predictLocation` = lieu le
   > plus habituel (≥ 2 observations, sinon null → repli sur le pari fixe). Trois
   > paris rendus informés : traque chasseur (`pickHuntLocation` vise le lieu
   > habituel du vampire au lieu de « Labo sinon Manoir » → convergence coordonnée),
   > clones (`tryShadowClones` classe les lieux de récolte par affluence probable),
   > chasse vampire (`pickVampSideLocation` vise le lieu d'une proie corruptible).
   > Équité respectée : ne lit que le centre révélé (jamais mains/pioches). Reste :
   > prédiction de cartes (spéculatif), humanisation, adoption d'un humain qui part.
   > Les **niveaux de difficulté** ont été ÉCARTÉS (décision game designer, 2026-07-24) :
   > jugés non pertinents pour ce jeu. Ne pas les réintroduire.
   > **Contrainte délais humanisés (game designer, 2026-07-24)** : le hasard sur les
   > délais de réaction est **≤ 2 s** (et NON 2–8 s), appliqué **UNIQUEMENT au jeu de
   > cartes**, et ne doit **PAS impacter les délais d'AFFICHAGE** (combat, morsure,
   > météo — soigneusement réglés). Concrètement : une petite hésitation aléatoire
   > avant de jouer une carte, rien d'autre.
   > **Implémenté (2026-07-24)** : constante `HUMANIZE_MAX=2000` + helper
   > `cardDelay(base) = base + dice.nextInt(2001)` (re-tiré chaque tick → la carte
   > part dans [base, base+2 s], borné). Appliqué aux **20 gates de jeu de carte de
   > PREPHASE3 + Cataclysme + Clones** (tous gatés par `PREPHASE_ACTION`, le délai de
   > RÉFLEXION du bot — pas un délai d'affichage). EXCLUS explicitement : l'assignation
   > d'instable (pas une carte) et la RÉSOLUTION de la caisse (délai d'AFFICHAGE du
   > d6). NON touchés : MODAL_DISPLAY, BITE_*, COMBAT_*, jets, choix de lieu, achats.
   > *Reste (gaté par des délais PARTAGÉS avec le lieu/la boutique, donc à faire par un
   > gate PAR-CARTE si voulu) : Fumigation, Pisteur, Image miroir, Portails (PHASE1/2,
   > `SELECT_LOCATION`) et les cartes éco PHASE4 (Charismatique, Avidité nocturne,
   > Marchand, Transmutation, `SHOP_ACTION`).*

Chaque étape est jouable et testable indépendamment ; on n'aborde l'étape suivante
qu'une fois la précédente validée en partie réelle.

## 11. Décisions produit (tranchées)

- **6 bots max** par partie, vampire compris ; archétype de départ **non sélectionnable** :
  préréglage **économique** par défaut (quand les profils seront implémentés, étape 2+),
  les pivots de situation (§3) faisant émerger les autres comportements.
- Bot **vampire possible dès la v1**.
- **Noms** de bots : oui ; avatars plus tard (pas encore implémentés, même pour les
  humains).
- **Reprise sur abandon** ✅ *(implémenté le 2026-07-24)* : quand un humain abandonne
  une partie ACTIVE (`surrender`, `GameLifecycleService`) et qu'au moins un AUTRE
  humain joue encore, un bot **reprend son personnage VIVANT** (`p.setBot(true)`) au
  lieu de le laisser mourir → la partie reste équilibrée. Sinon (dernier humain, ou
  perso déjà à terre) : comportement historique = mort. Dans les deux cas l'humain
  est détaché au niveau SQL (`playerService.leaveGame` → il peut rejoindre ailleurs)
  et le front le renvoie au lobby. Pas besoin d'un id « bot: » : un Player adopté
  garde l'id de son compte et est reconnu partout par le flag `isBot` (le snapshot
  l'expose → les autres voient le perso passer en bot + une ligne d'historique).
  *NB : la logique vit dans `GameLifecycleService.surrender` (qui a déjà `playerService`)
  plutôt que dans `BotManager` — évite un cycle de dépendances ; ce n'est qu'un flag
  à poser sur un Player existant.*
