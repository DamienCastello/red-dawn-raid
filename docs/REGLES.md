# Red Dawn Raid — Livre de règles complet

> *Chasseurs contre Vampire.* Un affrontement asymétrique où une meute de chasseurs
> traque une créature ancienne qui, nuit après nuit (les **raids**), gagne en puissance,
> corrompt les âmes et bâtit son domaine.
>
> Ce document décrit **toutes** les règles telles qu'implémentées dans le jeu. Pour une
> première partie, préférez le [Guide rapide](GUIDE-RAPIDE.md).

---

## 1. But du jeu

Le jeu oppose **deux camps** :

- **Les Chasseurs** (plusieurs joueurs) — ils gagnent s'ils **terrassent le vampire**
  (le réduisent à 0 point de vie).
- **Le Vampire** (un seul joueur) — il gagne quand **plus aucun chasseur n'est debout** :
  tués et/ou transformés en **serviteurs** par la corruption.

Un chasseur corrompu jusqu'au bout (**corruption 3**) rejoint définitivement le camp du
vampire en tant que **serviteur**. Un chasseur à 0 PV est hors de combat pour le reste de
la partie.

---

## 2. Mise en place

- **Nombre de joueurs :** 2 minimum. Un joueur est désigné **vampire au hasard**, tous les
  autres sont **chasseurs**.
- **Main de départ :** chaque joueur reçoit les 4 cartes **Lieu de base** : Forêt,
  Carrière, Lac, Manoir.
- **Dés de départ :** dé d'**attaque D4** et dé de **défense D4** pour tout le monde.
- **Points de vie :**
  - Vampire : **20 + 5 × (nombre de chasseurs)**.
  - Chaque chasseur : **20**.
- **Ressources de départ :**
  - **Vampire :** `âmes = 100 × (nb chasseurs)`, `pierre = 10`, et bois/fer/herbes/eau
    variables selon le nombre de chasseurs (5/5/10/10 à 2 chasseurs, 10/10/15/15 à 3‑4,
    15/15/20/20 à 5+).
  - **Chaque chasseur :** `150 or`, `10 herbes`, `10 eau`.

> Note : le deck de cartes Action distribue aussi quelques cartes de départ selon la
> configuration en cours ; référez‑vous à l'écran de jeu qui affiche toujours votre main
> réelle.

---

## 3. Les ressources

| Ressource | Symbole | Qui l'utilise |
|-----------|---------|---------------|
| **Bois** | 🪵 | Constructions, forge |
| **Herbes médicinales** | 🌿 | Potions |
| **Pierre** | 🪨 | Constructions, banque |
| **Fer** | ⚙️ | Constructions, forge |
| **Eau pure** | 💧 | Potions, eau bénite, transmutation |
| **Or** | 🪙 | Monnaie des chasseurs |
| **Âmes déchues** | 👁️ | Monnaie du vampire |
| **Argent** | 🥈 | Métal anti‑vampire (chasseurs) |

Au Manoir, les chasseurs récoltent de l'**or** et le vampire y récolte des **âmes**. Les
ressources brutes (bois, herbes, pierre, fer, eau) sont récoltées dans la nature.

---

## 4. Structure d'un raid (le tour de jeu)

Chaque raid se déroule en **6 phases**. Toutes se jouent simultanément quand c'est possible ;
le jeu enchaîne automatiquement quand tous les joueurs concernés ont agi.

### Phase 0 — Météo
On lance un **d12** qui détermine la **météo du raid** (voir §5). Les effets de la météo
s'appliquent à tous les combats du raid — **sauf aux monstres, qui y sont insensibles**. Les
effets « une fois par raid » sont réinitialisés.

### Phase 1 — Planification des chasseurs
Chaque **chasseur** choisit **secrètement** un lieu où se rendre en jouant une carte Lieu de
sa main, **face cachée** au centre.

### Phase 2 — Le vampire s'éveille
Le **vampire** (et ses éventuels serviteurs) choisit à son tour un lieu, face cachée. C'est
aussi le moment où le vampire peut **construire une infrastructure** (voir §8) — sauf sous
un **Cyclone**.

### Pré‑phase 3 — Révélation
Toutes les cartes Lieu sont **retournées face visible**. On sait désormais qui se trouve où.
Cette fenêtre sert à :
- résoudre la **corruption des chasseurs instables** (voir §7),
- **préparer des actions** de combat (Filet, Fosse, Épieu béni, Solitaire…),
- **boire des potions** avant un combat imminent (voir §6).

Il y a une fenêtre de temps (~30 s) pour agir ; le jeu avance ensuite tout seul.

### Phase 3 — Récolte & Combat
1. **Récolte :** chaque joueur récolte les ressources de son lieu (voir §9).
2. **Combats :** sur chaque lieu où se croisent au moins un chasseur et un ennemi
   (vampire, serviteur ou monstre), un combat éclate (voir §10).
3. **Morsures :** à l'issue d'un combat, le vampire peut **mordre** un chasseur présent
   (voir §7).

### Phase 4 — Maintenance
Fenêtre de gestion (~60 s), pendant laquelle on peut :
- **Acheter** à la boutique (potions, cartes actions, argent, équipement… voir §11),
- **Échanger** des ressources avec les alliés (voir §12),
- **Transmuter** des ressources (vampire, voir §13),
- **Contribuer à la banque** (chasseurs, voir §14).

Les cartes Lieu jouées reviennent en main, le domaine produit ses ressources
automatiques, puis un **nouveau raid** commence à la Phase 0.

---

## 5. La météo (d12)

| Jet | Météo | Effet |
|----:|-------|-------|
| 1 | Jour ensoleillé | +1 attaque chasseurs, −1 défense vampire |
| 2 | Brouillard protecteur | +1 attaque des chasseurs |
| 3 | Aurore | −1 défense du vampire |
| 4 | Ciel couvert | −1 attaque du vampire |
| 5 | Cyclone | Le vampire ne peut pas construire ce raid ; chaque chasseur perd 1 ressource de construction au hasard ; le domaine perd 1 ressource par construction |
| 6 | Orage | −2 défense pour **tous** |
| 7 | Pluie diluvienne | −2 attaque pour **tous** |
| 8 | Blizzard | **Potions gelées** (inutilisables) et −1 attaque pour tous |
| 9 | Crépuscule | +1 défense du vampire |
| 10 | Nuit obscure | +1 attaque du vampire ; les chasseurs **ne peuvent pas** utiliser de pièges |
| 11 | Nuit claire | +1 attaque du vampire, −1 défense des chasseurs |
| 12 | Pleine lune | +2 attaque du vampire |
| — | Lune sanglante | +4 attaque du vampire (météo spéciale, via carte) |

---

## 6. Les potions et élixirs

Les potions se boivent **en pré‑phase 3, juste avant un combat imminent**, et **uniquement
par un participant** à ce combat. Elles sont **gelées sous Blizzard**. Un chasseur qui
succombe à la corruption ne peut pas en boire ce raid.

### Potions communes
| Potion | Effet |
|--------|-------|
| **Force** | +1 attaque ce raid |
| **Endurance** | +1 défense ce raid |
| **Vie** | Régénère **2 + d6** points de vie |
| **Focalisation** | Permet de **relancer** un dé et de garder le meilleur résultat |
| **Sangsue** | Vol de vie : rend des PV en infligeant des dégâts |

### Élixirs (rares — **un seul par raid**)
| Élixir | Effet |
|--------|-------|
| **Rage** | **Double** le score d'attaque |
| **Résilience** | **Double** le score de défense |
| **Rapidité** | Attaque supplémentaire |
| **Invisibilité** | Frappe sans que l'adversaire ne riposte (et évite d'être ciblé) |
| **Invulnérabilité** | Annule tous les dégâts subis |

**Composition du deck de potions :** Force ×6, Endurance ×6, Vie ×7, Focalisation ×4,
Sangsue ×4. **Élixirs :** Résilience ×3, Rage ×3, Rapidité ×2, Invisibilité ×2,
Invulnérabilité ×1.

---

## 7. La corruption

La corruption est la grande menace qui pèse sur les chasseurs. Elle se mesure sur une
échelle **de 0 à 3** :

| Niveau | État | Conséquence |
|-------:|------|-------------|
| 0 | Sain | Aucun effet |
| 1 | Affaibli | Malus léger |
| 2 | Instable | Chaque raid, jet de **d6** en pré‑phase 3 : sur **1‑3**, le chasseur **succombe** et passe sous contrôle du vampire pour le raid (il ne combat plus pour son camp, ne peut ni utiliser cartes ni potions, et peut être forcé de récolter pour le vampire ou d'attaquer un allié) ; sur **4‑6** il résiste |
| 3 | **Serviteur** | Le chasseur **rejoint le vampire** définitivement : il perd ses cartes Action, son or se convertit en âmes, il combat désormais dans le camp du vampire |

### La morsure
Après un combat, le vampire (ou un **clone**) peut tenter de **mordre** un chasseur présent
sur son lieu :
- **Jet de d20.** Sur **plus de 8**, la morsure réussit : la cible gagne **+1 corruption** et
  le vampire draine **+50 âmes** (une morsure de clone réussit sur **plus de 12** et draine
  **+30 âmes**).
- Une morsure qui fait atteindre **corruption 3** transforme la cible en serviteur.
- **Chapelet sacré** (carte) : annule entièrement une morsure.
- **Armure de plates en argent** : permet de tenter de repousser la morsure (jet de d4).

---

## 8. Les constructions (le domaine du vampire)

En **phase 2**, le vampire peut bâtir **une infrastructure par raid** (impossible sous
Cyclone, et chaque lieu ne peut être construit qu'une fois). Construire dépense les
ressources indiquées, mais surtout : **le lieu construit devient une nouvelle carte Lieu
ajoutée à la main de *tous* les joueurs**. Chacun (chasseurs comme vampire) pourra donc
désormais choisir de s'y rendre lors des prochains raids — avec les récoltes et les effets
propres à ce bâtiment.

| Infrastructure | Sur le lieu | Coût | Effet |
|----------------|-------------|------|-------|
| **Scierie** | Forêt | 2 pierre, 2 fer | Récolte de bois améliorée (+4) ; +1 bois auto en phase 4 |
| **Mine** | Carrière | 3 bois, 1 fer | Récolte de fer améliorée (+4) ; +1 fer auto en phase 4 |

> Le raid de sa construction, le bâtiment est **immédiatement récolté par le vampire** (à la
> place du lieu de base) : **+6 bois** (Scierie), **+6 fer** (Mine), ou **d100 par dizaines
> en âmes** pour les bâtiments du Manoir.
| **Bibliothèque** | Manoir | 4 bois, 2 pierre, 1 fer | Étude, Subtilisation, Prédiction occulte |
| **Laboratoire occulte** | Manoir | 2 eau, 2 herbes, 3 pierre, 50 âmes | Invoque des monstres, alchimie, explosion |
| **Salle de bal** | Manoir | 5 pierre, 2 fer, 50 âmes | Danse macabre, attaque sournoise, valse sanguinaire |
| **Autel** | Manoir | 4 pierre, 1 bois, 2 fer, 50 âmes | Manipulation de la corruption (voir §15) |
| **Forge** | Manoir | 5 fer, 4 pierre, 2 bois | Fabrication d'équipement |

---

## 9. La récolte (phase 3)

Chaque joueur récolte les ressources du lieu où il se trouve.

| Lieu | Récolte de base |
|------|-----------------|
| **Forêt** | +2 bois, +4 herbes |
| **Carrière** | +2 fer, +4 pierre |
| **Lac** | +2 herbes, +4 eau |
| **Manoir** | **d100 (par dizaines) + 100** → **or** (chasseur) ou **âmes** (vampire) |
| **Scierie** | +4 bois |
| **Mine** | +4 fer |
| **Bibliothèque / Labo / Salle de bal / Autel / Forge** | **d100 (par dizaines) + 50** → or / âmes |

**Récolte divisée par deux** (arrondie à la dizaine supérieure pour l'or/les âmes) pour un
chasseur ou serviteur si un **combat** a lieu sur son lieu, s'il est **cible d'un duel
d'instable**, ou sous **Voile de brume**.

---

## 10. Le combat (phase 3)

Un combat éclate sur **chaque lieu** réunissant au moins un **chasseur** et un **ennemi**
(vampire, serviteur ou monstre).

**Déroulement d'une passe :**
1. L'attaquant lance son **dé d'attaque** ; le défenseur lance son **dé de défense**.
2. On ajoute les **modificateurs** (météo, corruption, potions, cartes, équipement).
3. **Dégâts = max(0, score d'attaque − score de défense)**, retirés aux PV du défenseur.

Chaque paire chasseur/ennemi échange **deux passes** (le chasseur attaque, puis l'ennemi
riposte). Ordre des dés selon l'équipement : **D4** (base) → **D6** (tier 1) → **D8**
(tier 2).

Modificateurs de combat notables :
- **Rage** ×2 attaque, **Résilience** ×2 défense, **Invulnérabilité** annule les dégâts.
- **Focalisation** : relance d'un dé, on garde le meilleur.
- **Invisibilité** : l'attaquant frappe sans riposte adverse.
- **Eau bénite** (mode attaque) : +4 dégâts sacrés contre le vampire.

**Vol de ressource :** si le vampire inflige des dégâts à un chasseur **avec un jet
d'attaque au maximum de son dé**, il lui vole en plus **1 ressource au hasard** (jamais
l'or ni l'argent).

---

## 11. La boutique (phase 4)

| Achat | Qui | Coût |
|-------|-----|------|
| **Potion** | Tous | 4 eau + 3 herbes |
| **Carte Action** | Tous | **(n+1) × 50** (or pour chasseurs, âmes pour vampire), où *n* = cartes déjà achetées ce raid |
| **Argent** (par unité) | Chasseurs | 50 or |
| **Carte Eau bénite** | Chasseurs | 150 or + 4 eau |
| **Carte Pisteur** | Chasseurs | 100 or |
| **Amélioration d'arme** (D4→D6→D8) | Chasseurs | tier 1 : 2 bois + 2 fer ; tier 2 : 3 bois + 3 fer |
| **Amélioration d'armure** (D4→D6→D8) | Chasseurs | tier 1 : 2 bois + 2 fer ; tier 2 : 3 bois + 3 fer |

Modificateurs de prix : **Avidité nocturne** (carte vampire) augmente les prix de +50 ;
**Charismatique** (carte chasseur) les réduit de −20 et augmente la revente de +10.

**Revente de ressources** (chasseurs) : 10 or l'unité (bois, herbes, pierre, fer, eau) —
20 or si Charismatique.

Les armes ont un **type** au tier 1‑2 : Tranchant (saignement), Distance (portée), Contondant
(étourdissement).

---

## 12. Les échanges (phase 4)

Deux alliés du **même camp** (chasseur↔chasseur, ou vampire↔serviteur) peuvent s'échanger
des ressources. Chaque partie propose une offre ; l'échange n'a lieu que si **les deux
confirment**.

---

## 13. La transmutation (vampire / serviteurs, phase 4)

| Recette | Coût | Gain |
|---------|------|------|
| Bois → Fer | 2 bois + 1 eau | +2 fer |
| Fer → Bois | 2 fer + 1 eau | +2 bois |
| Trinité → Âmes | 1 bois + 1 fer + 1 eau | +30 âmes |

---

## 14. La banque (chasseurs, phase 4)

Les chasseurs déposent des **pierres** pour améliorer une banque commune (niveaux 0 → 3).
Coût cumulé : **10 pierres** pour le niveau 1, **15** pour le niveau 2, **20** pour le niveau 3.

Au début de chaque phase 4, la banque verse à **chaque chasseur vivant** :
- **+50 or** (niveaux 1‑2) ou **+100 or** (niveau 3),
- **+1 ressource au hasard** (bois/fer/herbes/eau) à partir du niveau 2.

---

## 15. Les cartes Action

Les cartes Action se **préparent** (souvent en pré‑phase 3) ou s'**activent** à des moments
précis. Chaque camp a son propre deck.

### Actions des chasseurs
| Carte | Effet résumé |
|-------|--------------|
| **Eau bénite** | +4 dégâts sacrés contre le vampire en combat ; peut aussi **purifier l'autel** |
| **Fumigation d'ail** | Protège un lieu : le vampire et ses serviteurs ne peuvent s'y rendre |
| **Pisteur** | Suit le vampire : le chasseur se déplace sur le lieu joué par le vampire |
| **Feu de camp** | Jouable uniquement sous **Crépuscule, Nuit obscure ou Nuit claire** : annule les effets de la météo **sur le lieu du chasseur** (et ré‑autorise les pièges sous Nuit obscure) |
| **Filet** | Piège une cible sur le lieu : jet de d20 > 12 → **−1 défense** à la cible ce raid (contre un monstre : −1 attaque **et** −1 défense) |
| **Fosse** | Piège plus handicapant que le Filet : la cible rate un jet de d20 (< 8) → **−2 défense** ce raid |
| **Provocation** | Force un ennemi à cibler le chasseur |
| **Incendiaire** | Détruit une infrastructure du domaine en fin de raid |
| **Embuscade** | Plusieurs chasseurs tendent une embuscade à un ennemi |
| **Solitaire** | +1 attaque **et** +1 défense par chasseur **absent** du lieu |
| **Épieu béni** | Arme secondaire qui se déclenche contre un ennemi présent |
| **Chapelet sacré** | Annule la prochaine morsure subie |
| **Charismatique** | Réduit les prix d'achat (−20) et augmente la revente (+10) ce raid |
| **Marchand itinérant** | Jet de dé pour obtenir un bonus d'achat aléatoire |
| **Caisse** (Lac / Manoir) | Ouvre une caisse pour un butin lié au lieu |

### Actions du vampire
| Carte | Effet résumé |
|-------|--------------|
| **Présence écrasante** | Annule les préparations d'actions des chasseurs sur le lieu du vampire et bloque leurs cartes ce raid |
| **Cataclysme** | Ajoute **2 conditions météo supplémentaires** au choix du vampire, qui se cumulent à la météo du raid |
| **Clones d'ombre** | Dispose plusieurs clones sur des lieux : ils **attaquent** les chasseurs présents et **peuvent aussi mordre** (capacité optionnelle, payée en âmes par clone) |
| **Image miroir** | Pose un **lieu supplémentaire** (leurre) ; en phase de résolution, le vampire choisit sur lequel des deux lieux il se **matérialise** réellement |
| **Éclipse** | Impose la **Pleine lune** (+2 attaque du vampire) ce raid |
| **Lune de sang** | Impose la **Lune sanglante** (+4 attaque du vampire) |
| **Voile de brume** | Sur un lieu : récolte ÷2 et −1 défense des chasseurs présents |
| **Faim irrépressible** | Autorise une morsure garantie si l'attaque échoue |
| **Marque ténébreuse** | Marque un chasseur : +1 corruption chaque fois qu'il croise le vampire |
| **Affaiblissement occulte** | **−2 attaque** à un chasseur ciblé pour ce raid |
| **Passage secret** | Depuis le Manoir, téléporte le vampire vers un autre lieu après la révélation : pour **fuir une embuscade** ou pour **surgir en attaque surprise** sur des chasseurs |
| **Avidité nocturne** | Augmente les prix de la boutique (+50) ce raid |
| **Transmutation avancée** | Transmutation de ressources améliorée |
| **Portail : Revenant** | En **phase 2**, invoque un **Revenant** (monstre gardien : 5 PV, ATK D6, DEF D4) sur un lieu choisi. Gratuit (la carte est le coût). Le monstre **persiste** raid après raid jusqu'à sa mort |
| **Portail : Chauve-souris** | En **phase 2**, invoque une **Chauve-souris** (monstre gardien : 5 PV, ATK D4, DEF D6) sur un lieu choisi. Gratuit ; persistant comme ci-dessus |

---

## 16. Effets des bâtiments du Manoir

Quand un joueur se rend sur un Manoir **construit**, il peut activer l'effet du bâtiment :

- **Bibliothèque :**
  - *Étude des grimoires* : pioche une carte Action.
  - *Subtilisation de manuscrit* : retire une carte Action de la main d'un adversaire —
    choisie à l'aveugle parmi ses cartes face cachée (le vampire choisit quel chasseur ;
    un chasseur cible le vampire) — et la **remélange dans la pioche du camp adverse**.
    C'est un sabotage : on ne gagne pas la carte, on la renvoie dans le deck de sa victime.
  - *Prédiction occulte* : révèle (pour soi) la prochaine carte Action de l'adversaire et
    choisit de la placer **au-dessus ou au-dessous** de la pioche.
- **Laboratoire occulte :** *Expérimentation* (dépense des âmes pour invoquer un monstre
  gardien sur un lieu), *Alchimie* (dépense eau + herbes pour piocher une potion ou un
  élixir), *Explosion alchimique* (un chasseur peut tenter de détruire le labo sur un d20 ≥ 15 :
  échec → il perd 2 PV ; réussite → le vampire perd 1 ressource au hasard et 20 âmes).
- **Salle de bal :**
  - *Danse macabre* : quand le vampire réussit une attaque sur ce lieu, le chasseur visé
    subit aussi **+1 corruption**.
  - *Attaque sournoise (Charme du vampire)* : le vampire **vole une ressource au hasard à
    chaque chasseur présent** sur ce lieu, **en plus** d'attaquer (le vol a lieu même si
    l'attaque n'inflige aucun dégât).
  - *Valse sanguinaire* : le vampire lance autant de dés d'attaque qu'il y a de chasseurs
    présents, garde le **meilleur** et l'applique à **tous** les chasseurs du lieu.
  - *Pillage* (chasseurs) : un chasseur présent sur la Salle de bal **sans combat sur le
    lieu** peut la piller : **+10 à +100 or** (d100 par dizaines), en plus de sa récolte
    normale.
- **Autel :** sanctuaire qui bascule entre deux états, **purifié** ou **corrompu**. À sa
  construction, l'autel est **corrompu**.
  - *Autel purifié* : un **chasseur** peut y **retirer 1 corruption**. Le vampire peut le
    **corrompre** par une **morsure réussie** sur ce lieu, ou en **sacrifiant 30 âmes**.
  - *Autel corrompu* : le **vampire** peut y **ajouter +1 corruption** à un chasseur. Les
    chasseurs peuvent le **purifier** en dépensant de l'**eau bénite**, ou par le combat :
    si le vampire a subi **au moins 1 dégât** sur l'autel ce raid **et qu'aucune morsure
    n'y a réussi**, l'autel est purifié en fin de raid.
- **Forge :** fabrique des armes et armures (équipement de tier supérieur).

---

## 17. Monstres

Le Laboratoire occulte permet au vampire d'invoquer des **monstres** gardiens
(Revenant, Chauve‑souris, Gargouille, Loup, Aberration, Liche). Ils occupent un lieu, ont
leurs propres PV et dés, et **combattent les chasseurs** qui s'y aventurent. La **Liche**
peut appliquer la Marque ténébreuse sur un coup au but.

---

*Bonne chasse — ou bon festin.*