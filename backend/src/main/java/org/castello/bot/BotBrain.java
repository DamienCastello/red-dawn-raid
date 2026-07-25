package org.castello.bot;

import org.castello.game.Action;
import org.castello.game.Game;
import org.castello.game.GameService;
import org.castello.game.Infra;
import org.castello.game.Location;
import org.castello.game.LocationEffectChoice;
import org.castello.game.Phase;
import org.castello.game.Potion;
import org.castello.game.StatMod;
import org.castello.game.WeatherStatus;
import org.castello.game.domain.ConstructionService;
import org.castello.game.domain.EquipmentService;
import org.castello.game.support.Dice;
import org.castello.player.Player;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Cerveau du bot (docs/BOT-DESIGN.md).
 *
 * Étape 1 : couvre tous les points de décision OBLIGATOIRES (§4) → la partie
 * ne se bloque jamais. Étape 2 (économie) : choix de lieu pondéré par les
 * besoins, construction du vampire Bâtisseur, boutique/banque/transmutation
 * en PHASE4, potions basiques en PREPHASE3. Chaque comportement suit le
 * {@link BotProfile} économique (Bâtisseur / Prudent). Les stratégies de
 * cartes et les archétypes agressifs arrivent à l'étape 3.
 *
 * Toutes les actions passent par la façade {@link GameService} : le bot subit
 * exactement les mêmes validations qu'un joueur humain. Les refus (409/403)
 * sont avalés par l'orchestrateur : le bot réessaie simplement au tick suivant.
 *
 * Équité : ce cerveau ne lit que l'état public + la main du bot lui-même
 * (jamais les mains ni les pioches adverses). Les services de domaine injectés
 * ({@link EquipmentService}, {@link ConstructionService}) ne servent qu'à des
 * lectures pures (tiers d'équipement, coût d'une infra).
 *
 * Rythme : chaque action est gardée par une durée de STABILITÉ de l'état
 * (mesurée par l'orchestrateur). Deux raisons :
 * - laisser au front des humains le temps de jouer ses animations (météo,
 *   résultats de combat = SPECTATE_HOLD_MS 5 s) et de faire lui-même les
 *   progressions automatiques (combat/continue, advance) — le bot n'est
 *   qu'un FILET DE SÉCURITÉ quand des humains sont présents ;
 * - donner un rythme lisible aux parties de bots (spectateurs).
 */
@Service
public class BotBrain {

    // ================================================================
    // RYTHME DU BOT — deux familles de délais très différentes :
    //
    // 1) « Temps de réflexion » : délai avant que le bot ne fasse SES
    //    actions de joueur (jets, choix de lieu, se dire prêt…). Pur
    //    confort — même valeur avec ou sans humains, ajustable librement.
    //
    // 2) « Progressions automatiques » (combat/continue, advance…) : ici
    //    la question n'est pas le confort mais QUI fait avancer la partie.
    //    Quand un humain est présent, SON front s'en charge après ses
    //    animations (résultats de combat = SPECTATE_HOLD_MS, séquence
    //    météo…) ; le bot n'est qu'un FILET DE SECOURS et doit donc
    //    attendre PLUS LONGTEMPS que le front — sinon il coupe les
    //    animations des humains (et provoque des courses 409).
    //    Règle : *_HUMANS > timing du front + marge. Sans humain, aucun
    //    front n'existe : le bot avance lui-même, au rythme choisi pour
    //    d'éventuels spectateurs (*_ALONE, libres).
    // ================================================================

    /** Miroir de SPECTATE_HOLD_MS (game.component.ts). Si tu changes l'un,
     *  change l'autre : les délais *_HUMANS en dépendent. */
    private static final long FRONT_DISPLAY_MS = 5_000;

    // -- 1) Temps de réflexion (réglage du game designer, 2026-07-14) :
    //    les ACTIONS du bot sont rapides (< 1 s) ; seul l'AFFICHAGE d'un choix
    //    déjà visible dans une modale (cible de Filet posée, draft monstre+lieu
    //    d'Expérimentation…) reste marqué (MODAL_DISPLAY = 2 s) pour que les
    //    joueurs voient la sélection avant l'application. Météo inchangée.
    private static final long ROLL_WEATHER = 1_000; // modale « dé non lancé » visible (météo — ne pas toucher)
    private static final long SELECT_LOCATION = 300; // choix de lieu P1/P2 : quasi immédiat
    // Rythme de préphase (réglage game designer, 4e passe) : jouer une carte est
    // « rapide » MAIS l'affichage de l'action précédente doit vivre ~2,5 s —
    // c'est la STABILITÉ qui porte cet espacement (chaque action la remet à 0).
    private static final long PREPHASE_ACTION = 2_500;
    // Faire un CHOIX dans ma modale ouverte (mode d'Eau bénite, cible de
    // Marque/Embuscade/Filet, lieu de Voile, choix d'effet de lieu…) : ~1 s.
    private static final long MODAL_CHOICE = 1_000;
    // Sélection AFFICHÉE avant son application (draft d'Expérimentation,
    // cible de Filet posée → jet) : ~2,5 s.
    private static final long MODAL_DISPLAY = 2_500;
    private static final long POTION_USE = 800;
    private static final long SKIP_READY = 1_000;
    // Jets de dé : rapides. 350 ms (< 1 tick de 400 ms) → le jet part au 2e tick
    // après l'événement, soit ~0,5-0,8 s réels (500 ms le faisait glisser au 3e).
    private static final long COMBAT_ROLL = 350;
    // Morsure du vampire — CYCLE COMPLET (couplé au front, ne pas dérégler) :
    // T0 = résolution du duel = création de la bite (même transaction).
    // 1. le front affiche le résumé du duel pendant 3,5 s
    //    (biteNotBeforeMillis, event BITE_STARTED dans game.component.ts) ;
    // 2. la modale de morsure devient visible à T0+3,5 s et doit rester
    //    affichée 2 s AVANT le jet (réglage game designer) ;
    // 3. le bot lance donc son d20 à 3,9 s de stabilité = 3 500 (front)
    //    + ~300 ms de modale visible avant le jet (réglage game designer ;
    //    la granularité du tick de 400 ms empêche plus précis) ;
    // 4. le RÉSULTAT reste affiché 3 s (BITE_NEXT) avant combatContinue.
    // Si tu changes le 3 500 du front, ajuste BITE_ROLL en conséquence.
    private static final long BITE_ROLL = 3_900;
    private static final long BITE_NEXT = 3_000;
    // Jet d'esquive d'un piège subi (Fosse) : un peu de suspense, sans traîner.
    private static final long TRAP_DODGE = 1_500;
    private static final long SHOP_ACTION = 300; // espacement des achats en PHASE4 (rapide)
    private static final long FINISH_PHASE4 = 200;

    // HUMANISATION (étape 4, contrainte game designer) : petite hésitation ALÉATOIRE
    // ajoutée UNIQUEMENT au délai de JEU D'UNE CARTE (via cardDelay), ≤ 2 s, pour que
    // le bot ne dégaine pas ses cartes comme un métronome. N'affecte JAMAIS les délais
    // d'AFFICHAGE (combat/morsure/météo : MODAL_DISPLAY, BITE_*, COMBAT_*), ni les
    // jets, ni le choix de lieu, ni les achats.
    private static final long HUMANIZE_MAX = 2_000;

    // -- 2) Progressions automatiques (contrainte : *_HUMANS > front)
    private static final long ADVANCE_P1_HUMANS = 15_000; // l'anim météo du front avance elle-même
    private static final long ADVANCE_P1_ALONE = 2_000;
    private static final long COMBAT_NEXT_HUMANS = FRONT_DISPLAY_MS + 1_000;
    // Résultats de combat lisibles AUSSI LONGTEMPS qu'en partie humaine :
    // miroir de SPECTATE_HOLD_MS du front (via FRONT_DISPLAY_MS).
    private static final long COMBAT_NEXT_ALONE = FRONT_DISPLAY_MS;
    private static final long ADVANCE_P4_HUMANS = FRONT_DISPLAY_MS + 1_000;
    private static final long ADVANCE_P4_ALONE = 2_500;

    // --- Cibles de ressources pour le scoring de récolte / achats (chasseur).
    //     « De combien ai-je besoin avant d'être à l'aise ? »
    private static final int TARGET_WOOD = 6; // ~2 upgrades d'équipement
    private static final int TARGET_IRON = 6;
    private static final int TARGET_STONE = 12; // banque
    private static final int TARGET_WATER = 8; // potions
    private static final int TARGET_HERBS = 8;

    /** Coût en âmes d'un monstre d'Expérimentation basique (Revenant/Chauve-souris).
     *  Sert aussi de réserve d'âmes par bâtiment défendable avant d'acheter des cartes. */
    private static final int EXPERIMENT_COST = 100;

    /** Bâtiments défendables VISÉS par le Bâtisseur (Labo + Scierie + Mine) : sert
     *  d'assiette à la réserve d'âmes de défense (cf. tryVampEconomy) — on réserve
     *  sur le PLAN, pas sur les bâtiments déjà construits, pour ne pas dilapider les
     *  âmes en cartes tôt et pouvoir défendre chaque bâtiment dès qu'il sort. */
    private static final int DEFENDABLE_PLAN = 3;

    /** PV en dessous desquels un chasseur est « finissable » — une proie que le
     *  vampire cible en priorité (Image miroir offensif, focus de corruption). */
    private static final int MIRROR_FINISH_HP = 8;

    /**
     * Résultat d'une tentative de jeu en préphase : rien à jouer (NONE — on
     * peut passer à la suite / se déclarer prêt), action jouée (DONE), ou
     * action à jouer mais délai de réflexion pas encore écoulé (WAIT — surtout
     * NE PAS se déclarer prêt : on va la jouer dans quelques ticks).
     *
     * Ce tri-état corrige un bug de rythme : SKIP_READY (1 s) < délais des
     * cartes/potions (1,5–3 s), ET chaque « prêt » d'un bot réinitialise la
     * stabilité (readyForPhase3 est dans l'empreinte de l'orchestrateur) → en
     * bots-seuls, la fenêtre des cartes n'arrivait JAMAIS : tous les bots se
     * déclaraient prêts en cascade et la préphase se terminait sans qu'aucune
     * carte ni potion ne soit jouée. Désormais les CONDITIONS sont évaluées
     * sans délai — s'il y a quelque chose à jouer, on attend le délai puis on
     * joue ; sinon seulement, on se déclare prêt.
     */
    private enum Play {
        NONE, DONE, WAIT
    }

    private final GameService games;
    private final EquipmentService equipment; // lecture seule : tiers / options de forge
    private final ConstructionService construction; // lecture seule : coût d'une infra
    private final Dice dice;
    private final OpponentModel opponent; // estimation info cachée (paris informés)

    public BotBrain(GameService games, EquipmentService equipment,
            ConstructionService construction, Dice dice, OpponentModel opponent) {
        this.games = games;
        this.equipment = equipment;
        this.construction = construction;
        this.dice = dice;
        this.opponent = opponent;
    }

    /**
     * Délai de jeu d'une CARTE, humanisé : le délai de base + une hésitation
     * ALÉATOIRE de 0 à {@link #HUMANIZE_MAX} ms. Re-tirée à chaque tick → la carte
     * part quelque part dans [base, base+HUMANIZE_MAX] (probabilité croissante avec
     * la stabilité), bornée à +2 s. À n'utiliser QUE pour un jeu de carte, jamais
     * pour un délai d'affichage, un jet, un choix de lieu ou un achat.
     */
    private long cardDelay(long baseDelay) {
        return baseDelay + dice.nextInt((int) HUMANIZE_MAX + 1);
    }

    /**
     * Joue AU PLUS une action obligatoire pour ce bot.
     *
     * @param stableMs depuis combien de temps l'état observable de la partie
     *                 n'a pas changé (fourni par l'orchestrateur)
     * @param humans   true si au moins un joueur humain (même mort — il
     *                 spectate) est encore dans la partie
     * @return true si un appel à la façade a été fait (l'orchestrateur passe
     *         alors à la partie suivante pour ce tick).
     */
    public boolean playOneAction(Game g, Player bot, long stableMs, boolean humans) {
        Phase phase = g.getPhase();
        if (phase == null)
            return false;

        return switch (phase) {
            case PHASE0 -> playPhase0(g, bot, stableMs, humans);
            case PHASE1 -> playPhase1(g, bot, stableMs);
            case PHASE2 -> playPhase2(g, bot, stableMs);
            case PREPHASE3 -> playPrephase3(g, bot, stableMs);
            case PHASE3 -> playPhase3(g, bot, stableMs, humans);
            case PHASE4 -> playPhase4(g, bot, stableMs);
        };
    }

    // ------------------------------------------------------------------
    // PHASE0 — météo : le vampire lance le d12, puis n'importe qui avance.
    // Avec des humains, leur front joue l'animation en 3 temps puis avance
    // lui-même : le bot ne roll qu'après un court délai (modale visible) et
    // ne sert que de filet de sécurité pour l'avance.
    // ------------------------------------------------------------------
    private boolean playPhase0(Game g, Player bot, long stableMs, boolean humans) {
        if (g.getWeatherRoll() == null) {
            if ("VAMPIRE".equals(bot.getRole()) && bot.isAlive()
                    && stableMs >= ROLL_WEATHER) {
                games.rollWeather(g.getId(), bot.getId());
                return true;
            }
            return false;
        }
        // Météo tirée : l'avance vers PHASE1 n'a pas de timer serveur
        if (stableMs < (humans ? ADVANCE_P1_HUMANS : ADVANCE_P1_ALONE))
            return false;
        games.advancePhase(g.getId(), bot.getId(), Phase.PHASE1);
        return true;
    }

    // ------------------------------------------------------------------
    // PHASE1 — chaque chasseur choisit son lieu (face cachée), pondéré par
    // ses besoins de ressources (§5 : choix de lieu du chasseur Prudent).
    // ------------------------------------------------------------------
    private boolean playPhase1(Game g, Player bot, long stableMs) {
        if (!"HUNTER".equals(bot.getRole()) || !bot.isAlive())
            return false;
        String botId = bot.getId();
        // Déjà en piste (Pisteur joué) : le pistage EST mon action de lieu.
        if (g.getTrackerHunters() != null && g.getTrackerHunters().contains(botId))
            return false;
        if (g.hasPlayed(botId))
            return false;
        if (stableMs < SELECT_LOCATION)
            return false;

        var acts = bot.getActions();

        // Reprise (rare) : j'ai joué la carte de fumigation mais pas encore posé mon
        // lieu (ex. selectLocation refusé au coup précédent) → poser maintenant.
        if (isFumigating(g, bot)) {
            String loc = fumigationTargetLocation(g, bot);
            if (loc != null)
                games.selectLocation(g.getId(), botId, loc);
            return true;
        }

        // Fumigation d'ail (AVANT le Pisteur) — jouée ATOMIQUEMENT avec la pose du
        // lieu (carte + selectLocation dans le même tryPlay) : le lieu est fumigé
        // IMMÉDIATEMENT, donc le chasseur suivant du même tick le voit déjà bloqué →
        // un SEUL chasseur dénie l'Autel / la Salle de bal (pas de course). Trois
        // motifs (priorité autel > salle de bal > protection), cf. fumigationTargetLocation.
        if (shouldFumigate(g, bot)) {
            String loc = fumigationTargetLocation(g, bot);
            if (loc != null) {
                games.useAction(g.getId(), botId, Action.FUMIGATION_AIL);
                games.selectLocation(g.getId(), botId, loc);
                return true;
            }
            // aucun lieu valable (cas limite) → on retombe sur le flux normal.
        }

        // Pisteur : traque PRÉCISE — suivre le vampire à la trace plutôt que
        // parier sur son lieu. Le serveur me déplacera sur sa carte en PHASE2.
        if (acts != null && acts.contains(Action.PISTEUR.name())
                && fitForHunt(bot)
                && g.vampire().map(Player::isAlive).orElse(false)) {
            games.useAction(g.getId(), botId, Action.PISTEUR);
            return true;
        }

        String card = pickHunterLocation(g, bot);
        if (card == null)
            return false;
        games.selectLocation(g.getId(), bot.getId(), card);
        return true;
    }

    /**
     * Lieu à FUMIGER (= à aller occuper) ce raid : d'abord un DÉNI (Autel corrompu /
     * Salle de bal, cf. {@link #fumigationDenialLocation}), sinon la PROTECTION — un
     * lieu de récolte SÛR (jamais la traque : Forge si un T3 y est forgeable, sinon
     * la meilleure récolte de ressources). Null si rien de jouable.
     */
    private String fumigationTargetLocation(Game g, Player bot) {
        String denial = fumigationDenialLocation(g, bot);
        if (denial != null)
            return denial;
        String forge = pickForgeCraftLocation(g, bot);
        if (forge != null)
            return forge;
        return pickResourceHarvestLocation(g, bot);
    }

    /** Ai-je déjà joué FUMIGATION_AIL ce raid, en attente de poser mon lieu ? */
    private boolean isFumigating(Game g, Player bot) {
        return g.getPendingGarlicPlayers() != null
                && g.getPendingGarlicPlayers().contains(bot.getId());
    }

    /**
     * Fumigation d'ail (PHASE1) : trois usages, dans l'ordre de priorité
     * (feedback game designer). Le lieu que je poserai ensuite devient INTERDIT au
     * vampire / serviteurs / clones (ni morsure directe ni contact), et révélé.
     * <ol>
     * <li><b>Déni de l'Autel corrompu</b> : dès qu'UN chasseur est en corruption 2,
     * n'importe quel chasseur ayant la carte va fumiger l'Autel corrompu (construit,
     * en main, non fumigé, sans gardien) pour couper le moteur de corruption à
     * distance ({@link #fumigationDenialLocation}).</li>
     * <li><b>Déni de la Salle de bal</b> : personne en urgence de corruption, des
     * alliés traquent (Pisteur) → je prive le vampire de son refuge Danse macabre.</li>
     * <li><b>Protection</b> (le plus fréquent) : je suis une cible du vampire
     * (corruption 2 sans pouvoir dénier l'Autel, ou PV finissables) → je fumige mon
     * propre lieu de récolte pour supprimer la morsure directe.</li>
     * </ol>
     * Requiert la carte et un vampire vivant. La pose est ATOMIQUE (carte +
     * selectLocation dans le même tryPlay) pour qu'un SEUL chasseur dénie.
     */
    private boolean shouldFumigate(Game g, Player bot) {
        if (!hasAction(bot, Action.FUMIGATION_AIL))
            return false;
        if (!g.vampire().map(Player::isAlive).orElse(false))
            return false;
        return fumigationDenialLocation(g, bot) != null
                || bot.getCorruption() >= 2
                || bot.getHp() <= MIRROR_FINISH_HP;
    }

    /**
     * Lieu-clé du vampire à aller FUMIGER pour le lui interdire ce raid (déni), ou
     * null. Priorité Autel puis Salle de bal. Le lieu doit être dans ma main, non
     * déjà fumigé et sans gardien vivant (sinon combat → pas d'effet). L'unicité du
     * dénieur est assurée en amont (pose ATOMIQUE : le 1er qui fumige bloque le lieu,
     * les suivants du même tick le voient déjà bloqué).
     */
    private String fumigationDenialLocation(Game g, Player bot) {
        List<String> hand = bot.getHand();
        if (hand == null)
            return null;
        var built = g.getBuiltInfras();
        if (built == null)
            return null;
        var garlic = g.getGarlicBlockedLocations();
        String botId = bot.getId();

        // 1) DÉNI DE L'AUTEL CORROMPU — dès qu'UN chasseur est en danger (corruption
        //    2), N'IMPORTE QUEL chasseur ayant la carte peut aller couper le moteur
        //    (ce n'est pas forcément le corrompu qui l'a — feedback game designer).
        //    Gate sur autel CORROMPU : lui seul corrompt à distance. La carte « altar »
        //    est en main de tous les chasseurs une fois l'Autel construit.
        if (Boolean.TRUE.equals(g.getAltarCorrupted()) && built.contains(Infra.ALTAR)
                && someHunterInCorruptionDanger(g)) {
            String altar = Infra.ALTAR.locationCode();
            if (hand.contains(altar)
                    && (garlic == null || !garlic.contains(altar))
                    && !locationHasLivingMonster(g, altar))
                return altar;
        }

        // 2) DÉNI DE LA SALLE DE BAL — quand PERSONNE n'est en urgence de corruption
        //    et que des ALLIÉS traquent : un non-traqueur prive le vampire de son
        //    refuge (effet Danse macabre) pour que la traque puisse l'accrocher.
        if (noHunterInCorruptionDanger(g)
                && built.contains(Infra.BALLROOM)) {
            var trackers = g.getTrackerHunters();
            boolean iTrack = trackers != null && trackers.contains(botId);
            boolean alliesTrack = trackers != null && trackers.stream()
                    .filter(id -> !id.equals(botId))
                    .map(g::findPlayer)
                    .anyMatch(pp -> pp != null && pp.isAlive());
            String ballroom = Infra.BALLROOM.locationCode();
            if (!iTrack && alliesTrack
                    && hand.contains(ballroom)
                    && (garlic == null || !garlic.contains(ballroom))
                    && !locationHasLivingMonster(g, ballroom))
                return ballroom;
        }

        return null;
    }

    /** Un chasseur vivant est-il en danger imminent de corruption (niveau 2) ? */
    private boolean someHunterInCorruptionDanger(Game g) {
        return !noHunterInCorruptionDanger(g);
    }

    /** Aucun chasseur vivant n'est en danger imminent de corruption (niveau 2) ? */
    private boolean noHunterInCorruptionDanger(Game g) {
        return g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .noneMatch(p -> p.getCorruption() >= 2);
    }

    // ------------------------------------------------------------------
    // PHASE2 — vampire + serviteurs. Le vampire Bâtisseur tente d'abord une
    // construction (qui joue AUSSI le lieu du chantier) ; sinon il vise le
    // Manoir pour les âmes. Les serviteurs se contentent d'un lieu à âmes.
    // ------------------------------------------------------------------
    private boolean playPhase2(Game g, Player bot, long stableMs) {
        boolean isVamp = "VAMPIRE".equals(bot.getRole());
        boolean vampSide = isVamp || "SERVANT".equals(bot.getRole());
        if (!vampSide || !bot.isAlive())
            return false;
        if (stableMs < SELECT_LOCATION)
            return false;

        // Cartes vampire jouées en PHASE2 — AVANT le choix de lieu.
        // Portail : poster un monstre gardien sur une Scierie/Mine construite
        // et exposée (défense du domaine — la carte que le game designer voulait).
        if (isVamp && tryPortalDefense(g, bot))
            return true;

        // Clones des ombres (étape 3c passe 2) : harcèlement/corruption parallèle.
        // Tri-état : tant que MA modale Clones est ouverte (WAIT), on n'enchaîne
        // PAS sur le choix de lieu — sinon il déclenche l'avancement de phase et
        // la modale meurt avant le placement des clones.
        if (isVamp) {
            Play pc = tryShadowClones(g, bot, stableMs);
            if (pc != Play.NONE)
                return pc == Play.DONE;
        }

        // Cataclysme (PHASE2) : setup d'un RAID D'ASSAUT — cumule 2 météos qui
        // boostent l'attaque du vampire. Tri-état comme les Clones (la modale ne
        // doit pas laisser le choix de lieu la tuer).
        if (isVamp) {
            Play pc = tryCataclysme(g, bot, stableMs);
            if (pc != Play.NONE)
                return pc == Play.DONE;
        }

        // Image miroir (PHASE2) : leurre ANTI-PISTAGE — quand des Pisteurs me
        // traquent et que je n'ai pas de meilleur refuge (Salle de bal), poser
        // un leurre pour me matérialiser ailleurs après la révélation. Simple
        // useAction ici (le leurre/choix se résolvent en PREPHASE3).
        if (isVamp && shouldPlayImageMiroir(g, bot)) {
            games.useAction(g.getId(), bot.getId(), Action.IMAGE_MIROIR);
            return true;
        }

        if (g.hasPlayed(bot.getId()))
            return false;

        if (isVamp) {
            // REFUGE ANTI-PISTAGE PRIORITAIRE (feedback game designer) : quand
            // une MEUTE me traque (≥ 2 Pisteurs) dans une partie à plusieurs
            // chasseurs, la Salle de bal passe AVANT la re-défense des monstres
            // (aller expérimenter au Labo m'exposerait à la meute sans l'effet
            // Danse macabre pour me couvrir).
            String refuge = ballroomRefugeLocation(g, bot);
            if (refuge != null) {
                games.selectLocation(g.getId(), bot.getId(), refuge);
                return true;
            }

            // Défense PRIORITAIRE via le Labo : si un bâtiment doit être (re)défendu,
            // que je n'ai pas de Portail sous la main et que je peux payer une
            // Expérimentation, je vais au Labo (au lieu de construire) — le monstre
            // sera posé en PREPHASE3. C'est ce qui donne la cadence
            // construire → défendre → construire → défendre.
            String labLoc = labDefenseLocation(g, bot);
            if (labLoc != null) {
                games.selectLocation(g.getId(), bot.getId(), labLoc);
                return true;
            }

            // Sinon, construire (planConstruction joue le lieu du chantier)
            Infra toBuild = chooseConstruction(g, bot);
            if (toBuild != null) {
                games.planConstruction(g.getId(), bot.getId(), toBuild);
                return true;
            }
        }

        String card = pickVampSideLocation(g, bot);
        if (card == null)
            return false;
        games.selectLocation(g.getId(), bot.getId(), card);
        return true;
    }

    /**
     * Salle de bal comme refuge PRIORITAIRE contre une MEUTE de Pisteurs
     * (feedback game designer) : ≥ 2 traqueurs vivants ce raid, dans une partie
     * à plusieurs chasseurs, Salle de bal construite, jouable et non fumigée.
     * Prime sur la re-défense (labDefenseLocation) et la construction.
     */
    private String ballroomRefugeLocation(Game g, Player bot) {
        var trackers = g.getTrackerHunters();
        if (trackers == null)
            return null;
        long liveTrackers = trackers.stream()
                .map(g::findPlayer)
                .filter(p -> p != null && p.isAlive())
                .count();
        if (liveTrackers < 2)
            return null;
        long aliveHunters = g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .count();
        if (aliveHunters < 2)
            return null; // en duel, autant les affronter ailleurs
        var built = g.getBuiltInfras();
        if (built == null || !built.contains(Infra.BALLROOM))
            return null;
        String code = Infra.BALLROOM.locationCode();
        List<String> hand = bot.getHand();
        if (hand == null || !hand.contains(code))
            return null;
        if (g.getGarlicBlockedLocations() != null && g.getGarlicBlockedLocations().contains(code))
            return null;
        return code;
    }

    /**
     * Lieu « laboratoire » à jouer pour aller Expérimenter-défendre ce raid, ou
     * null si ce n'est pas pertinent. Conditions : un bâtiment est à (re)défendre,
     * je n'ai pas de Portail pour le faire, le Labo est construit et jouable, et
     * je peux payer une Expérimentation.
     */
    private String labDefenseLocation(Game g, Player bot) {
        var built = g.getBuiltInfras();
        if (built == null || !built.contains(Infra.LABORATORY))
            return null;
        if (portalCardCount(bot) > 0)
            return null; // je défendrai via Portail (sans bouger au Labo)
        if (bot.getSouls() < EXPERIMENT_COST)
            return null;
        if (pickDefenseLocation(g) == null)
            return null; // rien à défendre
        String labLoc = Infra.LABORATORY.locationCode();
        List<String> hand = bot.getHand();
        if (hand == null || !hand.contains(labLoc))
            return null;
        if (g.getGarlicBlockedLocations() != null && g.getGarlicBlockedLocations().contains(labLoc))
            return null;
        return labLoc;
    }

    /**
     * Portail (Bâtisseur) : place un monstre gardien pour défendre un lieu de
     * production (Scierie/Mine) construit, exposé aux chasseurs et pas encore
     * gardé. Renvoie true si une action a été jouée ce tick (jeu de la carte
     * puis, au tick suivant, résolution du lieu).
     */
    private boolean tryPortalDefense(Game g, Player bot) {
        String botId = bot.getId();

        // 1) Un Portail en attente de résolution (choix du lieu) m'appartient ?
        Game.Action a = g.getCurrentAction();
        if (a != null && a.getMode() != null && a.getMode().startsWith("PORTAL_INVOCATION")
                && botId.equals(a.getOwnerId()) && a.getResolvedAtMillis() == null) {
            String loc = pickPortalLocation(g);
            if (loc == null)
                return false; // rien à garder (sécurité) — le serveur re-validera
            games.resolvePortalInvocation(g.getId(), botId, loc);
            return true;
        }

        // 2) Une autre action NON RÉSOLUE est en cours → on attend. (Une action
        //    résolue peut traîner dans currentAction — ex. la modale informative
        //    de Faim irrépressible — et ne doit PAS nous verrouiller.)
        if (a != null && a.getResolvedAtMillis() == null)
            return false;

        // 3) Jouer une carte Portail si j'en ai une ET qu'il y a un lieu à garder
        Action portalCard = portalCardInHand(bot);
        if (portalCard == null)
            return false;
        if (pickPortalLocation(g) == null)
            return false;
        games.useAction(g.getId(), botId, portalCard);
        return true;
    }

    /**
     * Cible d'un Portail (règle du game designer, 2026-07-14) : les bâtiments
     * construits à (re)défendre PRIORENT ; sinon, en début de partie, harceler
     * les lieux de récolte des chasseurs — Carrière PUIS Forêt (un monstre y
     * attaque les récolteurs chaque raid).
     */
    private String pickPortalLocation(Game g) {
        String def = pickDefenseLocation(g);
        if (def != null)
            return def;
        var garlic = g.getGarlicBlockedLocations();
        for (String code : List.of("quarry", "forest")) {
            if (garlic != null && garlic.contains(code))
                continue;
            if (!locationHasLivingMonster(g, code))
                return code;
        }
        return null;
    }

    /**
     * Clones des ombres (PHASE2, étape 3c passe 2) : d4 clones envoyés harceler
     * les lieux de récolte. En PHASE2 les positions des chasseurs sont ENCORE
     * CACHÉES (révélation en préphase) — comme un humain, on parie sur les lieux
     * de récolte fréquentés (Carrière/Forêt/Lac). Morsure activée (10 âmes/clone,
     * d20 > 12 → +1 corruption, +30 âmes) si le surplus d'âmes le permet :
     * c'est le moteur de corruption PARALLÈLE du §7.
     */
    private Play tryShadowClones(Game g, Player bot, long stableMs) {
        String botId = bot.getId();

        // 1) Ma modale Clones est ouverte : d4 (rapide), puis placement (choix
        //    visible → MODAL_DISPLAY). WAIT tant qu'elle n'est pas résolue : le
        //    choix de lieu du vampire ne doit PAS passer devant.
        Game.Action a = g.getCurrentAction();
        if (a != null && "CLONES_OMBRE".equals(a.getMode())
                && botId.equals(a.getOwnerId()) && a.getResolvedAtMillis() == null) {
            if (a.getRoll() == null) {
                if (stableMs < COMBAT_ROLL)
                    return Play.WAIT;
                games.rollShadowClones(g.getId(), botId);
                return Play.DONE;
            }
            int n = a.getRoll();
            var garlic = g.getGarlicBlockedLocations();
            // Pari INFORMÉ : lieux de récolte classés par affluence PROBABLE des
            // chasseurs (OpponentModel) → le round-robin ci-dessous met le clone
            // « en trop » sur le lieu le plus fréquenté (positions cachées en PHASE2).
            List<String> spots = new ArrayList<>();
            for (String code : predictedHarvestSpots(g)) {
                if (garlic == null || !garlic.contains(code))
                    spots.add(code);
            }
            if (spots.isEmpty())
                return Play.NONE; // tout est fumigé : cas limite, le serveur tranchera
            if (stableMs < MODAL_CHOICE)
                return Play.WAIT;
            List<String> locations = new ArrayList<>();
            for (int i = 0; i < n; i++)
                locations.add(spots.get(i % spots.size()));
            // Morsures : payées sur le SURPLUS au-dessus de la réserve de défense
            int soulReserve = Math.max(0, DEFENDABLE_PLAN - portalCardCount(bot)) * EXPERIMENT_COST;
            boolean bite = bot.getSouls() >= soulReserve + n * 10;
            List<Boolean> biteEnabled = new ArrayList<>();
            for (int i = 0; i < n; i++)
                biteEnabled.add(bite);
            games.resolveShadowClones(g.getId(), botId, locations, biteEnabled);
            return Play.DONE;
        }
        if (a != null && a.getResolvedAtMillis() == null)
            return Play.NONE; // une autre action NON RÉSOLUE est en cours

        // 2) Jouer la carte — UNE SEULE vague par raid, et GARDER la dernière
        //    carte en réserve pour un combo (feedback game designer : Clones est
        //    une carte puissante, la claquer d'entrée sans combo est un gâchis).
        var acts = bot.getActions();
        if (acts == null || !acts.contains(Action.CLONES_OMBRE.name()))
            return Play.NONE;
        // Une vague de clones est déjà dehors ce raid → pas de 2e carte.
        if (g.getClonesLocations() != null && !g.getClonesLocations().isEmpty())
            return Play.NONE;
        // Dernière carte en main : réservée aux COMBOS — météo déjà favorable
        // aux attaques du vampire, ou Éclipse en main pour la forcer.
        long cloneCards = acts.stream()
                .filter(Action.CLONES_OMBRE.name()::equals).count();
        boolean comboReady = acts.contains(Action.ECLIPSE.name())
                || isGoodWeatherForVampAttack(g.getWeatherStatus());
        if (cloneCards <= 1 && !comboReady)
            return Play.NONE;
        // Météo DÉFAVORABLE aux attaques du camp vampire : garder la carte pour
        // un meilleur raid (feedback game designer) — SAUF si l'Éclipse en main
        // peut forcer la Pleine lune en préphase (combo Éclipse + clones).
        if (isBadWeatherForVampAttack(g.getWeatherStatus())
                && !acts.contains(Action.ECLIPSE.name()))
            return Play.NONE;
        if (stableMs < cardDelay(PREPHASE_ACTION))
            return Play.WAIT;
        games.useAction(g.getId(), botId, Action.CLONES_OMBRE);
        return Play.DONE;
    }

    /**
     * Cataclysme (PHASE2, vampire) : setup d'un RAID D'ASSAUT — cumule 2 météos
     * qui boostent l'attaque du vampire (Pleine lune +2, Nuit obscure/claire +1).
     * Joué quand le vampire est en position d'attaquer (vampFitToHunt : PV sains
     * + une proie corruptible existe), et qu'aucune météo cumulée n'est déjà
     * posée. Flux 2 temps (carte → choix des 2 météos), comme les Clones.
     */
    private Play tryCataclysme(Game g, Player bot, long stableMs) {
        String botId = bot.getId();

        // 1) Ma modale Cataclysme est ouverte → choisir 2 météos de tempête.
        Game.Action a = g.getCurrentAction();
        if (a != null && "CATACLYSME".equals(a.getMode())
                && botId.equals(a.getOwnerId()) && a.getResolvedAtMillis() == null) {
            WeatherStatus[] picks = pickCataclysmeWeathers();
            if (stableMs < MODAL_CHOICE)
                return Play.WAIT;
            games.resolveCataclysme(g.getId(), botId, picks[0], picks[1]);
            return Play.DONE;
        }
        if (a != null)
            return Play.NONE; // une autre action bloquante est en cours

        // 2) Jouer la carte : assaut planifié, pas de météo cumulée déjà active.
        var acts = bot.getActions();
        if (acts == null || !acts.contains(Action.CATACLYSME.name()))
            return Play.NONE;
        if (g.getSecondaryWeatherStatus() != null) // déjà un Cataclysme actif ce raid
            return Play.NONE;
        if (!vampFitToHunt(g, bot))
            return Play.NONE;
        if (stableMs < cardDelay(PREPHASE_ACTION))
            return Play.WAIT;
        games.useAction(g.getId(), botId, Action.CATACLYSME);
        return Play.DONE;
    }

    /**
     * Les 2 météos de TEMPÊTE de l'assaut. Cataclysme est restreint à la famille
     * {@code WIND / STORM / RAIN / BLIZZARD} (aucune ne « booste » l'attaque du
     * vampire — ce sont des météos de champ de bataille) et REMPLACE la météo en
     * cours (le vampire y est de toute façon immunisé), donc pas d'exclusion de la
     * base. Combo offensif : STORM (−2 défense de tous → mes attaques et morsures
     * brisent la garde des chasseurs) + BLIZZARD (gèle les potions/élixirs → les
     * chasseurs ne peuvent plus se sauver, et −1 attaque).
     */
    private WeatherStatus[] pickCataclysmeWeathers() {
        return new WeatherStatus[] { WeatherStatus.STORM, WeatherStatus.BLIZZARD };
    }

    /**
     * Météo qui BOOSTE les attaques du camp vampire : Pleine lune, Lune de
     * sang, Nuit obscure, Nuit claire — le moment des combos de clones.
     */
    private boolean isGoodWeatherForVampAttack(WeatherStatus w) {
        return w == WeatherStatus.FULL_MOON || w == WeatherStatus.BLOOD_MOON
                || w == WeatherStatus.NIGHT_DARK || w == WeatherStatus.NIGHT_CLEAR;
    }

    /**
     * Météo qui pénalise les ATTAQUES du camp vampire (clones compris) ou
     * booste les chasseurs : Jour ensoleillé, Brouillard protecteur, Aurore,
     * Ciel couvert, Pluie diluvienne, Blizzard.
     */
    private boolean isBadWeatherForVampAttack(WeatherStatus w) {
        return w == WeatherStatus.SUNNY || w == WeatherStatus.FOG
                || w == WeatherStatus.AURORA || w == WeatherStatus.CLOUDY
                || w == WeatherStatus.RAIN || w == WeatherStatus.BLIZZARD;
    }

    /**
     * Mes potions/élixirs sont-ils GELÉS ce raid ? Un BLIZZARD actif gèle tout le
     * monde, SAUF le vampire lanceur quand ce BLIZZARD fait partie de son
     * Cataclysme. Cataclysme actif ⟺ une météo secondaire existe (base + secondaire
     * = les 2 météos choisies, qui remplacent la base — règle serveur).
     */
    private boolean potionsFrozenForBot(Game g, Player bot) {
        boolean blizzardActive = g.getWeatherStatus() == WeatherStatus.BLIZZARD
                || g.getSecondaryWeatherStatus() == WeatherStatus.BLIZZARD;
        if (!blizzardActive)
            return false;
        boolean cataclysme = g.getSecondaryWeatherStatus() != null;
        boolean vampExempt = cataclysme && "VAMPIRE".equals(bot.getRole());
        return !vampExempt;
    }

    /**
     * Bâtiment construit, non fumigé, sans monstre vivant → à (re)défendre.
     * Priorité (feedback game designer) : l'Autel (moteur de corruption, le
     * plus critique), puis le Laboratoire (moteur de défense), la Salle de bal
     * (les chasseurs y gagnent l'or ×2 !), puis Scierie et Mine.
     */
    private String pickDefenseLocation(Game g) {
        var built = g.getBuiltInfras();
        if (built == null)
            return null;
        var garlic = g.getGarlicBlockedLocations();
        for (Infra infra : List.of(Infra.ALTAR, Infra.LABORATORY, Infra.BALLROOM, Infra.SAWMILL, Infra.MINE)) {
            if (!built.contains(infra))
                continue;
            String loc = infra.locationCode();
            if (garlic != null && garlic.contains(loc))
                continue;
            if (locationHasLivingMonster(g, loc))
                continue;
            return loc;
        }
        return null;
    }

    private boolean locationHasLivingMonster(Game g, String loc) {
        return g.getMonsters() != null && g.getMonsters().stream()
                .anyMatch(m -> loc.equals(m.location) && m.hp > 0);
    }

    private Action portalCardInHand(Player bot) {
        var acts = bot.getActions();
        if (acts == null)
            return null;
        if (acts.contains(Action.PORTAL_INVOCATION_REVENANT.name()))
            return Action.PORTAL_INVOCATION_REVENANT;
        if (acts.contains(Action.PORTAL_INVOCATION_BAT.name()))
            return Action.PORTAL_INVOCATION_BAT;
        return null;
    }

    /**
     * Choix de lieu du chasseur : score chaque carte de la main selon le
     * déficit de ressources visé (récoltes §9 de REGLES.md). Le Manoir
     * (or) garde un attrait modéré ; un petit bruit évite la prévisibilité.
     */
    private String pickHunterLocation(Game g, Player bot) {
        List<String> hand = bot.getHand();
        if (hand == null || hand.isEmpty())
            return null;

        // TRAQUE (étape 3c passe 3, pivot de situation §3/§7) : quand le rapport
        // de force est favorable, l'escouade va chercher le vampire sur ses lieux
        // d'âmes au lieu d'attendre qu'il vienne. Prioritaire sur la récolte.
        String hunt = pickHuntLocation(g, bot);
        if (hunt != null && hand.contains(hunt))
            return hunt;

        // FORGE : la Forge crafte moins cher que la boutique (et seule au T3). Un
        // chasseur hors escouade de traque qui peut y forger un upgrade y va
        // (bâtiment partagé construit par le vampire).
        String forge = pickForgeCraftLocation(g, bot);
        if (forge != null)
            return forge;

        return pickResourceHarvestLocation(g, bot);
    }

    /**
     * Meilleure carte de RÉCOLTE de la main selon le déficit de ressources visé
     * (récoltes §9 de REGLES.md) — le Manoir (or) garde un attrait modéré, un petit
     * bruit évite la prévisibilité. Sans traque ni forge : c'est aussi le lieu SÛR
     * de repli quand je fumige pour protection.
     */
    private String pickResourceHarvestLocation(Game g, Player bot) {
        List<String> hand = bot.getHand();
        if (hand == null || hand.isEmpty())
            return null;

        int woodNeed = deficit(bot.getWood(), TARGET_WOOD);
        int ironNeed = deficit(bot.getIron(), TARGET_IRON);
        int stoneNeed = deficit(bot.getStone(), TARGET_STONE);
        int waterNeed = deficit(bot.getWater(), TARGET_WATER);
        int herbsNeed = deficit(bot.getHerbs(), TARGET_HERBS);

        String best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (String card : hand) {
            double s = switch (card) {
                case "forest" -> woodNeed * 2.0 + herbsNeed;
                case "quarry" -> ironNeed * 2.0 + stoneNeed;
                // Lac : l'eau (+4) est sa ressource PRINCIPALE (elle ne vient de
                // nulle part ailleurs) → même pondération ×2 que fer/bois. Sans ça
                // la carrière gagne toujours (la pierre déposée en banque retombe à
                // 0 → stoneNeed reste au max) et les bots n'ont jamais d'eau — or
                // l'eau est le prérequis des potions ET de l'Eau bénite (étape 3b).
                case "lake" -> waterNeed * 2.0 + herbsNeed;
                case "manor" -> 6.0; // or : utile mais jamais prioritaire sur un manque
                case "sawmill" -> woodNeed * 2.0;
                case "mine" -> ironNeed * 2.0;
                // autres bâtiments du Manoir : or (~100)
                case "library", "laboratory", "ballroom", "altar", "forge" -> 5.0;
                default -> 1.0;
            };
            s += dice.nextInt(3) * 0.5; // bruit léger (0 / 0,5 / 1)
            if (s > bestScore) {
                bestScore = s;
                best = card;
            }
        }
        return best;
    }

    /**
     * Lieu FORGE à jouer pour un chasseur qui veut CRAFTER son équipement. La Forge
     * fabrique MOINS CHER que la boutique (et seule à monter au T3, la boutique
     * plafonnant à T2) : on y va dès qu'un upgrade est forgeable MAINTENANT (T3 en
     * priorité, mais un &lt; T3 vaut aussi le détour). Conditions : Forge construite
     * et carte en main, au moins une option forgeable (tier atteint + ressources).
     * PAS de garde PV : si on croise le vampire/serviteur/monstre sur la forge,
     * l'effet est annulé (combat) — pas grave, on rachètera en boutique (≤ T2).
     * L'objet forgé est choisi par {@link #pickHunterForgeOption} (équilibrage
     * atk/def vs le vampire).
     */
    private String pickForgeCraftLocation(Game g, Player bot) {
        if (!"HUNTER".equals(bot.getRole()))
            return null;
        var built = g.getBuiltInfras();
        if (built == null || !built.contains(Infra.FORGE))
            return null;
        String forge = Infra.FORGE.locationCode();
        List<String> hand = bot.getHand();
        if (hand == null || !hand.contains(forge))
            return null;
        // Au moins un upgrade forgeable maintenant (sinon rien à faire ici).
        return equipment.listForgeOptionsForPlayer(g, bot).isEmpty() ? null : forge;
    }

    /**
     * Vampire / serviteur qui ne construit pas : jouer les EFFETS de ses
     * bâtiments au lieu de camper le Manoir (feedback game designer) :
     * 1. Forge si un équipement vampirique est forgeable (se stuffer !) ;
     * 2. « chasse » : des chasseurs déjà corrompus traînent → parier sur un
     *    lieu de récolte pour les mordre (1 raid sur 2, si PV corrects) ;
     * 3. Bibliothèque de temps en temps (effet Étude = pioche gratuite) ;
     * 4. sinon Manoir (le plus gros rendement d'âmes), puis le reste.
     * Exclut les lieux fumigés (interdits au camp du vampire).
     */
    private String pickVampSideLocation(Game g, Player bot) {
        List<String> hand = bot.getHand();
        if (hand == null || hand.isEmpty())
            return null;

        List<String> opts = new ArrayList<>(hand);
        if (g.getGarlicBlockedLocations() != null) {
            opts.removeIf(g.getGarlicBlockedLocations()::contains);
        }
        if (opts.isEmpty())
            opts = new ArrayList<>(hand); // dernier recours, le serveur tranchera

        // (La défense via le Labo — aller Expérimenter — est décidée AVANT la
        // construction dans playPhase2, cf. labDefenseLocation.)

        boolean isVamp = "VAMPIRE".equals(bot.getRole());

        // 0) PISTÉ (des Pisteurs me suivent ce raid) : refuge à la SALLE DE BAL
        //    si elle est construite (feedback game designer, corrigé) — l'effet
        //    Danse macabre m'avantage au combat contre les traqueurs.
        if (isVamp && g.getTrackerHunters() != null && !g.getTrackerHunters().isEmpty()
                && opts.contains("ballroom"))
            return "ballroom";

        // 0bis) Autel construit mais SANS gardien : y aller SOI-MÊME (feedback
        //       game designer) — ma présence le défend ET son effet corrompt
        //       les chasseurs qui s'y risquent.
        if (isVamp && opts.contains("altar") && !locationHasLivingMonster(g, "altar"))
            return "altar";

        // 1) Forge : un équipement vampirique est forgeable → y aller (l'effet
        //    Forge propose armes/armures V_* T1-T3 contre des ressources).
        if (isVamp && opts.contains("forge")
                && !equipment.listForgeOptionsForPlayer(g, bot).isEmpty())
            return "forge";

        // 2) Chasse aux corrompus : des chasseurs à corruption ≥ 1 existent et
        //    je suis en état de me battre → parier sur un lieu de récolte
        //    fréquenté (positions cachées en PHASE2 : pari, comme un humain).
        //    Un raid sur deux (dé) pour rester imprévisible et ne pas sacrifier
        //    toute l'économie d'âmes.
        if (isVamp && dice.nextInt(2) == 0 && vampFitToHunt(g, bot)) {
            // Pari INFORMÉ : viser le lieu de récolte HABITUEL d'une proie
            // corruptible (OpponentModel), sinon le lieu de récolte globalement le
            // plus fréquenté, sinon au hasard.
            String prey = pickCorruptionRushTarget(g);
            String preySpot = prey == null ? null : opponent.predictLocation(g.getId(), prey);
            if (preySpot != null && opts.contains(preySpot)
                    && List.of("quarry", "forest", "lake").contains(preySpot))
                return preySpot;
            List<String> spots = new ArrayList<>(predictedHarvestSpots(g));
            spots.retainAll(opts);
            if (!spots.isEmpty())
                return spots.get(0); // le plus fréquenté (pari informé)
        }

        // 3) Bibliothèque ~1 raid sur 3 : l'effet Étude pioche une carte
        //    d'action GRATUITE (le moteur du vampire), en plus des âmes.
        if (isVamp && opts.contains("library") && dice.nextInt(3) == 0)
            return "library";

        // 4) Manoir et ses bâtiments = récolte d'âmes (carburant du Bâtisseur)
        for (String c : List.of("manor", "laboratory", "altar", "ballroom", "library", "forge")) {
            if (opts.contains(c))
                return c;
        }
        return opts.get(dice.nextInt(opts.size()));
    }

    /**
     * Le vampire est-il en état d'aller au contact ? (PV > 50 % et au moins un
     * chasseur vivant encore corruptible déjà entamé — la proie du rush §7.)
     */
    private boolean vampFitToHunt(Game g, Player bot) {
        if (!vampHpAboveHalf(g, bot))
            return false;
        return g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .anyMatch(p -> p.getCorruption() >= 1 && p.getCorruption() < 3);
    }

    /** PV du vampire au-dessus de la moitié de son max (barème d'initialisation). */
    private boolean vampHpAboveHalf(Game g, Player bot) {
        int maxHp = 20 + Math.max(0, g.getInitialPlayerCount() - 1) * 10;
        return bot.getHp() * 2 > maxHp;
    }

    /**
     * Prochaine infra à bâtir selon l'ordre du profil, si toutes les
     * conditions serveur sont réunies (pas de cyclone, carte lieu en main
     * et non fumigée, ressources suffisantes). Sinon null → pas de
     * construction ce raid.
     */
    private Infra chooseConstruction(Game g, Player bot) {
        // Cyclone (WIND) : construction interdite ce raid — mais seul un WIND
        // NATUREL bloque : un WIND de mon propre Cataclysme (⟺ météo secondaire
        // posée) m'épargne (immunité).
        boolean cataclysme = g.getSecondaryWeatherStatus() != null;
        if (g.getWeatherStatus() == WeatherStatus.WIND && !cataclysme)
            return null;
        if (g.getPendingConstruction() != null)
            return null; // déjà planifiée (sécurité)

        var built = g.getBuiltInfras();
        if (built == null)
            built = java.util.EnumSet.noneOf(Infra.class);
        boolean sawmill = built.contains(Infra.SAWMILL);
        boolean mine = built.contains(Infra.MINE);
        boolean lab = built.contains(Infra.LABORATORY);
        int economyBuilt = (sawmill ? 1 : 0) + (mine ? 1 : 0);
        int portals = portalCardCount(bot);

        // Ordre adaptatif du Bâtisseur (§6, précisé par le game designer) :
        // - le Laboratoire est le moteur de défense DURABLE (Expérimentation) ;
        // - on n'expose une éco (Scierie/Mine) que si on peut la défendre avec un
        //   Portail → au plus min(Portails, 2) éco avant de sécuriser le Labo ;
        // - sinon Labo prioritaire (repli éco s'il n'est pas encore finançable).
        List<Infra> priority = new ArrayList<>();
        if (!lab) {
            if (economyBuilt < Math.min(portals, 2)) {
                priority.add(sawmill ? Infra.MINE : Infra.SAWMILL);
                priority.add(Infra.LABORATORY);
            } else {
                priority.add(Infra.LABORATORY);
                if (!sawmill)
                    priority.add(Infra.SAWMILL);
                if (!mine)
                    priority.add(Infra.MINE);
            }
        } else {
            if (!sawmill)
                priority.add(Infra.SAWMILL);
            if (!mine)
                priority.add(Infra.MINE);
            // Salle de bal puis Autel AVANT la Bibliothèque (feedback game
            // designer, corrigé) : la Salle de bal est le refuge ANTI-PISTAGE
            // (effet Danse macabre au combat) ; l'Autel est le moteur de
            // corruption rapide — tous deux à défendre.
            priority.add(Infra.BALLROOM);
            priority.add(Infra.ALTAR);
            // Bibliothèque : âmes + effet Étude (pioche gratuite) — le bot y VA
            // ensuite (pickVampSideLocation), il ne construit pas pour rien.
            priority.add(Infra.LIBRARY);
            // Forge : SEULEMENT quand les chasseurs prennent l'avance en
            // équipement (feedback game designer) — le vampire s'y équipera
            // (effet Forge : armes/armures vampiriques T1-T3).
            if (huntersGearAhead(g, bot))
                priority.add(Infra.FORGE);
        }

        List<String> hand = bot.getHand();
        var garlic = g.getGarlicBlockedLocations();
        for (Infra infra : priority) {
            if (built.contains(infra))
                continue;
            String siteCard = infraSiteCard(infra); // forest / quarry / manor
            if (hand == null || !hand.contains(siteCard))
                continue;
            if (garlic != null && garlic.contains(siteCard))
                continue;
            if (construction.hasResourcesForInfra(bot, infra))
                return infra;
        }
        return null;
    }

    private int portalCardCount(Player bot) {
        var acts = bot.getActions();
        if (acts == null)
            return 0;
        int n = 0;
        for (String a : acts)
            if (a.startsWith("PORTAL_INVOCATION"))
                n++;
        return n;
    }

    /**
     * Flux complet de la Transmutation avancée (PHASE4, vampire) : jouer la
     * carte (ouvre l'offre) → lancer le d6 (1-3 élixir, 4-6 équipement) →
     * acheter si payable. Une étape par tick ; renvoie true si un appel a
     * été fait. Les états (merchantPending / merchantRoll / shopBonusKind)
     * vivent sur MON Player — lecture légitime.
     */
    private boolean tryAdvancedTransmutation(Game g, Player bot) {
        String botId = bot.getId();
        int soulReserve = Math.max(0, DEFENDABLE_PLAN - portalCardCount(bot)) * EXPERIMENT_COST;

        // 2) Le d6 de l'offre est en attente
        if (bot.isMerchantPending()) {
            games.rollAdvancedTransmutation(g.getId(), botId);
            return true;
        }
        // 3) Une offre est posée → l'acheter si payable (ressources d'abord)
        String kind = bot.getShopBonusKind();
        if (kind != null) {
            switch (kind) {
                case "EQUIP_WEAPON", "EQUIP_ARMOR" -> {
                    if (bot.getWood() >= 2 && bot.getIron() >= 2) {
                        games.buyShopBonus(g.getId(), botId, "RESOURCE");
                        return true;
                    }
                    if (bot.getSouls() >= 150 + soulReserve) {
                        games.buyShopBonus(g.getId(), botId, "SOULS");
                        return true;
                    }
                }
                case "ELIXIR" -> {
                    if (bot.getWater() >= 2 && bot.getHerbs() >= 4) {
                        games.buyShopBonus(g.getId(), botId, "RESOURCE");
                        return true;
                    }
                    if (bot.getSouls() >= 50 + soulReserve) {
                        games.buyShopBonus(g.getId(), botId, "SOULS");
                        return true;
                    }
                }
                case "POTION" -> {
                    if (bot.getWater() >= 1 && bot.getHerbs() >= 2) {
                        games.buyShopBonus(g.getId(), botId, "RESOURCE");
                        return true;
                    }
                }
                default -> {
                    /* offre inconnue : on laisse expirer */ }
            }
            return false; // offre non payable : on n'insiste pas
        }
        // 1) Jouer la carte si en main et pas encore utilisée ce raid
        var acts = bot.getActions();
        if (acts != null && acts.contains(Action.ADVANCED_TRANSMUTATION.name())
                && !bot.isAdvancedTransmutationUsedThisRaid()
                && bot.getMerchantRoll() == null) {
            games.useAction(g.getId(), botId, Action.ADVANCED_TRANSMUTATION);
            return true;
        }
        return false;
    }

    /**
     * Marchand itinérant (PHASE4, chasseur — étape 3d passe 2) : mêmes 3 temps
     * que la Transmutation avancée du vampire (carte → d6 → achat de l'offre),
     * paiement en RESSOURCES d'abord puis en OR. C'est la seule SOURCE d'élixirs
     * du chasseur ; on l'appelle avec le surplus d'or (au-dessus de la réserve
     * Eau bénite), à partir du milieu de partie où les combos comptent.
     */
    private boolean tryMerchantItinerant(Game g, Player bot) {
        String botId = bot.getId();

        // 2) Le d6 de l'offre est en attente
        if (bot.isMerchantPending()) {
            games.rollMerchantItinerant(g.getId(), botId);
            return true;
        }
        // 3) Une offre est posée → l'acheter si utile et payable (ressources
        //    d'abord). L'équipement n'est pris que s'il me fait monter de tier.
        String kind = bot.getShopBonusKind();
        if (kind != null) {
            switch (kind) {
                case "EQUIP_WEAPON", "EQUIP_ARMOR" -> {
                    Integer tier = bot.getShopBonusEquipTier();
                    int myTier = "EQUIP_WEAPON".equals(kind)
                            ? equipment.hunterWeaponTier(bot.getWeapon())
                            : equipment.hunterArmorTier(bot.getArmor());
                    if (tier != null && tier > myTier) {
                        if (bot.getWood() >= 2 && bot.getIron() >= 2) {
                            games.buyShopBonus(g.getId(), botId, "RESOURCE");
                            return true;
                        }
                        if (bot.getGold() >= 150 + HUNTER_GOLD_RESERVE) {
                            games.buyShopBonus(g.getId(), botId, "GOLD");
                            return true;
                        }
                    }
                }
                case "ELIXIR" -> {
                    if (bot.getWater() >= 2 && bot.getHerbs() >= 4) {
                        games.buyShopBonus(g.getId(), botId, "RESOURCE");
                        return true;
                    }
                    if (bot.getGold() >= 60 + HUNTER_GOLD_RESERVE) {
                        games.buyShopBonus(g.getId(), botId, "GOLD");
                        return true;
                    }
                }
                case "POTION" -> {
                    if (bot.getWater() >= 1 && bot.getHerbs() >= 2) {
                        games.buyShopBonus(g.getId(), botId, "RESOURCE");
                        return true;
                    }
                }
                default -> {
                    /* offre inconnue : on laisse expirer */ }
            }
            return false; // offre non payable / sans intérêt : on n'insiste pas
        }
        // 1) Jouer la carte : milieu de partie, surplus d'or (garde la réserve
        //    Eau bénite), pas déjà utilisée ce raid.
        var acts = bot.getActions();
        if (acts != null && acts.contains(Action.MARCHAND_ITINERANT.name())
                && !bot.isMerchantUsedThisRaid()
                && bot.getMerchantRoll() == null
                && g.getRaid() >= 3
                && bot.getGold() >= 60 + HUNTER_GOLD_RESERVE) {
            games.useAction(g.getId(), botId, Action.MARCHAND_ITINERANT);
            return true;
        }
        return false;
    }

    /**
     * Les chasseurs prennent-ils l'avance en équipement ? (feedback game
     * designer) : soit TOUS les chasseurs vivants ont leurs deux équipements
     * T1 (boutique), soit le mieux équipé a ≥ 2 tiers d'écart sur le vampire
     * (cas « impératif »). Déclenche la construction de la Forge et le
     * ré-équipement du vampire.
     */
    private boolean huntersGearAhead(Game g, Player vamp) {
        var hunters = g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .toList();
        if (hunters.isEmpty())
            return false;
        boolean allT1 = hunters.stream().allMatch(h -> equipment.hunterWeaponTier(h.getWeapon()) >= 1
                && equipment.hunterArmorTier(h.getArmor()) >= 1);
        int bestHunterGear = hunters.stream()
                .mapToInt(h -> equipment.hunterWeaponTier(h.getWeapon())
                        + equipment.hunterArmorTier(h.getArmor()))
                .max().orElse(0);
        int vampGear = equipment.vampireWeaponTier(vamp.getWeapon())
                + equipment.vampireArmorTier(vamp.getArmor());
        return allT1 || bestHunterGear - vampGear >= 2;
    }

    /** Carte lieu que planConstruction fait jouer pour bâtir cette infra. */
    private String infraSiteCard(Infra infra) {
        return switch (infra) {
            case SAWMILL -> "forest";
            case MINE -> "quarry";
            default -> "manor";
        };
    }

    private static int deficit(int current, int target) {
        return Math.max(0, target - current);
    }

    /** Un chasseur est « apte à la chasse » : équipé (arme ≥ tier 1) et solide. */
    private boolean fitForHunt(Player p) {
        return p.isAlive() && "HUNTER".equals(p.getRole())
                && p.getHp() >= 12
                && equipment.hunterWeaponTier(p.getWeapon()) >= 1;
    }

    /**
     * TRAQUE (pivot de situation, §3/§7) : lieu de chasse pour ce chasseur, ou
     * null si le rapport de force ne s'y prête pas. Conditions :
     * - assez tard dans la partie (raid ≥ 3 : l'économie d'abord) ;
     * - JE suis apte (arme ≥ tier 1, PV ≥ 12) et l'ESCOUADE existe (≥ 2 aptes
     *   au total — chaque apte évalue le même critère public → convergence
     *   émergente sur le même lieu, sans coordination explicite) ;
     * - cible = le lieu d'âmes le plus probable du vampire : le Laboratoire
     *   s'il est construit (récolte + Expérimentation du Bâtisseur), sinon le
     *   Manoir. On évite les lieux fumigés (le vampire n'y sera pas).
     * Les inaptes (blessés, sous-équipés) continuent de récolter.
     */
    private String pickHuntLocation(Game g, Player bot) {
        if (g.getRaid() < 3)
            return null;
        if (!fitForHunt(bot))
            return null;
        boolean vampAlive = g.vampire().map(Player::isAlive).orElse(false);
        if (!vampAlive)
            return null;
        long fit = g.getPlayers().stream().filter(this::fitForHunt).count();
        if (fit < 2)
            return null; // pas d'assaut en solitaire

        var garlic = g.getGarlicBlockedLocations();
        var built = g.getBuiltInfras();

        // Pari INFORMÉ (OpponentModel) : si le vampire a une habitude nette, on
        // traque SON lieu le plus fréquent. Sinon, pari fixe : Labo (moteur d'âmes)
        // sinon Manoir. Toujours converge (tous les chasseurs lisent la même
        // habitude publique) → assaut coordonné sans coordination explicite.
        String vampId = g.vampire().map(Player::getId).orElse(null);
        String predicted = vampId == null ? null : opponent.predictLocation(g.getId(), vampId);

        boolean labBuilt = built != null && built.contains(Infra.LABORATORY);
        String target = (predicted != null) ? predicted : (labBuilt ? "laboratory" : "manor");

        if (garlic != null && garlic.contains(target))
            return null; // le vampire ne peut pas y aller : chasse inutile
        return target;
    }

    /**
     * Lieux de récolte (Carrière/Forêt/Lac) classés par affluence PROBABLE des
     * chasseurs vivants (OpponentModel), du plus fréquenté au moins fréquenté. Le
     * vampire vise ainsi ses clones / sa chasse là où les chasseurs vont vraiment
     * (positions cachées en PHASE2) plutôt qu'au hasard. Ordre stable si aucune
     * donnée (Carrière > Forêt > Lac).
     */
    private List<String> predictedHarvestSpots(Game g) {
        List<String> spots = new ArrayList<>(List.of("quarry", "forest", "lake"));
        java.util.Map<String, Integer> score = new java.util.HashMap<>();
        for (String s : spots) {
            int total = 0;
            for (Player p : g.getPlayers()) {
                if (!"HUNTER".equals(p.getRole()) || !p.isAlive())
                    continue;
                total += opponent.timesSeenAt(g.getId(), p.getId(), s);
            }
            score.put(s, total);
        }
        spots.sort((a, b) -> Integer.compare(score.getOrDefault(b, 0), score.getOrDefault(a, 0)));
        return spots;
    }

    // ------------------------------------------------------------------
    // PREPHASE3 — instables (vampire), effets de lieu, puis « j'ai fini »
    // ------------------------------------------------------------------
    private boolean playPrephase3(Game g, Player bot, long stableMs) {
        String botId = bot.getId();

        // 1) Vampire : assigner les instables succombés (bloque la phase sinon).
        //    Naïf : « aucun ordre » — le choix attaque/récolte viendra à l'étape 3.
        if ("VAMPIRE".equals(bot.getRole())) {
            String unstableId = firstPendingUnstable(g);
            if (unstableId != null) {
                if (stableMs < PREPHASE_ACTION) // assignation d'instable : PAS une carte → pas d'hésitation
                    return false;
                games.assignUnstableNothing(g.getId(), botId, unstableId);
                return true;
            }
        }

        // 2) Effet de lieu en attente : AUCUN timer côté serveur, le
        //    propriétaire DOIT répondre sous peine de bloquer la partie.
        if (Boolean.TRUE.equals(g.getLocationEffectPending())) {
            Game.LocationEffectInstance inst = currentEffectInstance(g);
            if (inst != null && botId.equals(inst.ownerId)) {
                if (stableMs < MODAL_CHOICE)
                    return false;
                if (inst.choice == null) {
                    LocationEffectChoice choice = naiveEffectChoice(g, bot, inst.infra);
                    if (choice == null)
                        return false;
                    games.chooseLocationEffect(g.getId(), botId, choice);
                    return true;
                }
                return resolvePendingEffect(g, bot, inst, stableMs);
            }
            // Effet d'un autre joueur : on attend.
            return false;
        }

        // 3→4) Cartes et potions : le tri-état Play garantit qu'un bot qui a
        //      quelque chose à jouer (WAIT) ne se déclare PAS prêt en dessous —
        //      cf. javadoc de Play pour le bug de cascade corrigé.

        // 3) Cartes de combat du vampire (Éclipse → Lune de sang, Faim) si un
        //    combat l'implique ce raid.
        if ("VAMPIRE".equals(bot.getRole())) {
            Play p = tryVampireCombatCards(g, bot, stableMs);
            if (p != Play.NONE)
                return p == Play.DONE;
        }

        // 3bis) Cartes du chasseur (survie passe 1 + pièges passe 2) avant le
        //        combat imminent — étape 3b.
        if ("HUNTER".equals(bot.getRole())) {
            Play p = tryHunterCombatCards(g, bot, stableMs);
            if (p != Play.NONE)
                return p == Play.DONE;
        }

        // 3ter) Élixir (1/raid, premium) — AVANT les potions basiques : réservé
        //        aux moments de combo/danger (étape 3d).
        if (bot.isAlive()) {
            Play p = maybeUseElixir(g, bot, stableMs);
            if (p != Play.NONE)
                return p == Play.DONE;
        }

        // 4) Potions basiques avant le combat imminent (une par tick).
        if (bot.isAlive()) {
            Play p = maybeUsePotion(g, bot, stableMs);
            if (p != Play.NONE)
                return p == Play.DONE;
        }

        // 5) Se déclarer prêt (accélère la préphase ; le timer 30 s couvre le reste)
        if (bot.isAlive()
                && (g.getReadyForPhase3() == null || !g.getReadyForPhase3().contains(botId))) {
            if (stableMs < SKIP_READY)
                return false;
            games.skipAction(g.getId(), botId);
            return true;
        }
        return false;
    }

    /**
     * Élixir (étape 3d) : consommable PREMIUM, 1 seul par raid, à RÉSERVER aux
     * moments décisifs (grille §8 : « à claquer avec le combo »). Mêmes gardes
     * que les potions (PREPHASE3, combat imminent, participant, pas de blizzard).
     *
     * Priorité : SURVIE d'abord (danger de mort → Invulnérabilité = 0 dégât,
     * sinon Résilience = défense ×2), puis BURST sur un vrai moment de combo
     * (Rage = attaque ×2 > Invisibilité = touche à coup sûr > Rapidité = attaque
     * 2 fois). Hors de ces cas, on GARDE l'élixir.
     */
    private Play maybeUseElixir(Game g, Player bot, long stableMs) {
        if (!g.isHasUpcomingCombat() || bot.isElixirUsedThisRaid())
            return Play.NONE;
        if (potionsFrozenForBot(g, bot))
            return Play.NONE;
        if (!hasEnemyOnMyLocation(g, bot))
            return Play.NONE;
        List<String> elixirs = bot.getElixirs();
        if (elixirs == null || elixirs.isEmpty())
            return Play.NONE;

        String botId = bot.getId();
        int maxHp = "VAMPIRE".equals(bot.getRole())
                ? 20 + Math.max(0, g.getInitialPlayerCount() - 1) * 10
                : 20;

        Potion pick = null;

        // 1) SURVIE : danger de mort ce raid (PV ≤ 1/3 max face à un adversaire
        //    dangereux), OU vampire EN SURNOMBRE (≥ 2 chasseurs) — souvent
        //    embusqué (riposte bloquée) : attaquer serait gaspillé, il faut
        //    encaisser (Invulnérabilité = 0 dégât, sinon Résilience).
        boolean vampAssaulted = "VAMPIRE".equals(bot.getRole())
                && huntersOnMyLocation(g, bot) >= 2;
        boolean lowHp = bot.getHp() * 3 <= maxHp;
        if ((lowHp && dangerousEnemyOnMyLocation(g, bot)) || vampAssaulted) {
            if (elixirs.contains(Potion.INVULNERABILITE.name()))
                pick = Potion.INVULNERABILITE;
            else if (elixirs.contains(Potion.RESILIENCE.name()))
                pick = Potion.RESILIENCE;
        }

        // 2) BURST : moment de combo (sinon on garde l'élixir).
        if (pick == null && isElixirComboMoment(g, bot)) {
            if (elixirs.contains(Potion.RAGE.name()))
                pick = Potion.RAGE;
            else if (elixirs.contains(Potion.INVISIBILITE.name()))
                pick = Potion.INVISIBILITE;
            else if (elixirs.contains(Potion.RAPIDITE.name()))
                pick = Potion.RAPIDITE;
        }

        if (pick == null)
            return Play.NONE;
        if (stableMs < POTION_USE)
            return Play.WAIT;
        games.usePotion(g.getId(), botId, pick);
        return Play.DONE;
    }

    /** Un adversaire capable de me faire mal est-il sur mon lieu ? (vampire/serviteur
     *  armé, ou monstre PUISSANT — pas une simple Chauve-souris). */
    private boolean dangerousEnemyOnMyLocation(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return false;
        boolean botIsHunter = "HUNTER".equals(bot.getRole());
        if (botIsHunter) {
            if (vampSideOnMyLocation(g, bot))
                return true;
            return g.monstersOn(loc).stream().anyMatch(m -> m.hp > 0
                    && m.type != Game.MonsterType.BAT && m.type != Game.MonsterType.REVENANT);
        }
        // Vampire : la meute elle-même est dangereuse en surnombre.
        return huntersOnMyLocation(g, bot) >= 2;
    }

    /**
     * Moment de COMBO justifiant un élixir offensif : je suis en position
     * d'attaquer fort. Chasseur : embuscade en cours sur mon lieu, OU vampire
     * au contact et affaibli/traqué en surnombre. Vampire : une cible
     * corruptible est sur mon lieu et je ne suis pas en danger.
     */
    private boolean isElixirComboMoment(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return false;
        if ("HUNTER".equals(bot.getRole())) {
            boolean ambushHere = g.getAmbushLocations() != null && g.getAmbushLocations().contains(loc);
            if (ambushHere)
                return true;
            // Vampire au contact + surnombre chasseur (assaut coordonné) ou vampire mourant.
            if (vampSideOnMyLocation(g, bot))
                return huntersOnMyLocation(g, bot) >= 2 || vampireNearDeath(g);
            return false;
        }
        // Vampire : sécuriser une morsure/kill sur un chasseur corruptible —
        // seulement s'il n'est PAS en surnombre (sinon il défend, cf. survie)
        // et qu'il est en forme.
        int maxHp = 20 + Math.max(0, g.getInitialPlayerCount() - 1) * 10;
        boolean healthy = bot.getHp() * 2 > maxHp;
        return healthy && huntersOnMyLocation(g, bot) < 2
                && hasCorruptibleHunterOnMyLocation(g, bot);
    }

    /**
     * Potion basique avant un combat imminent auquel le bot participe :
     * Vie si PV bas, sinon Force / Endurance (une seule de chaque par raid).
     * Le serveur re-valide la participation ; ici on approxime avec « un
     * ennemi est sur mon lieu » pour éviter les tentatives inutiles.
     */
    private Play maybeUsePotion(Game g, Player bot, long stableMs) {
        if (!g.isHasUpcomingCombat())
            return Play.NONE;
        List<String> potions = bot.getPotions();
        if (potions == null || potions.isEmpty())
            return Play.NONE;
        // Gelées sous blizzard (sauf le vampire lanceur d'un Cataclysme BLIZZARD)
        if (potionsFrozenForBot(g, bot))
            return Play.NONE;
        if (!hasEnemyOnMyLocation(g, bot))
            return Play.NONE;

        String botId = bot.getId();

        // 1) Vie si PV bas (auto-limitant : les PV remontent).
        //    Miroir de CombatService.maxHpFor (vampire = 20 + 10 * nb chasseurs).
        int maxHp = "VAMPIRE".equals(bot.getRole())
                ? 20 + Math.max(0, g.getInitialPlayerCount() - 1) * 10
                : 20;
        Potion pick = null;
        if (bot.getHp() * 2 < maxHp && potions.contains(Potion.VIE.name())) {
            pick = Potion.VIE;
        } else if (potions.contains(Potion.FORCE.name()) && !hasPotionMod(g, botId, "POTION:FORCE")) {
            // 2) Force (si pas déjà boosté ce raid)
            pick = Potion.FORCE;
        } else if (potions.contains(Potion.ENDURANCE.name())
                && !hasPotionMod(g, botId, "POTION:ENDURANCE")) {
            // 3) Endurance (si pas déjà boosté ce raid)
            pick = Potion.ENDURANCE;
        }
        if (pick == null)
            return Play.NONE;
        if (stableMs < POTION_USE)
            return Play.WAIT;
        games.usePotion(g.getId(), botId, pick);
        return Play.DONE;
    }

    /** Un ennemi (joueur adverse ou monstre pour un chasseur) est-il sur mon lieu ? */
    private boolean hasEnemyOnMyLocation(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return false;
        boolean botIsHunter = "HUNTER".equals(bot.getRole());

        boolean enemyPlayer = g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(pl -> loc.equals(g.locationOf(pl.getId())))
                .anyMatch(pl -> botIsHunter
                        ? ("VAMPIRE".equals(pl.getRole()) || "SERVANT".equals(pl.getRole()))
                        : "HUNTER".equals(pl.getRole()));
        if (enemyPlayer)
            return true;

        // Monstres : ennemis des chasseurs uniquement
        return botIsHunter && !g.monstersOn(loc).isEmpty();
    }

    private boolean hasPotionMod(Game g, String playerId, String source) {
        if (g.getRaidMods() == null)
            return false;
        List<StatMod> mods = g.getRaidMods().get(playerId);
        if (mods == null)
            return false;
        return mods.stream().anyMatch(m -> source.equals(m.getSource()));
    }

    /**
     * Cartes de combat du vampire en PREPHASE3, jouées uniquement s'il est
     * engagé dans un combat ce raid (chasseur sur son lieu) :
     * - Éclipse → Pleine lune (+2 ATK), puis Lune de sang (+4 ATK) ;
     * - Faim irrépressible → morsure possible même si l'attaque échoue, quand
     *   une cible corruptible est présente (amorce du rush corruption).
     * Combo signature Éclipse → Lune de sang le même raid.
     */
    private Play tryVampireCombatCards(Game g, Player bot, long stableMs) {
        String botId = bot.getId();

        // 0) Mes modales ouvertes (Marque / Affaiblissement / Voile — étape 3c) :
        //    à résoudre en PRIORITÉ, même sans combat (elles bloquent la préphase).
        Game.Action a = g.getCurrentAction();
        if (a != null && botId.equals(a.getOwnerId()) && a.getResolvedAtMillis() == null) {
            switch (a.getMode() == null ? "" : a.getMode()) {
                case "MARQUE_TENEBREUSE" -> {
                    String target = pickMarkTargetId(g, bot);
                    if (target == null)
                        return Play.NONE; // plus de cible : le serveur nettoiera
                    if (stableMs < MODAL_CHOICE)
                        return Play.WAIT;
                    games.resolveDarkMark(g.getId(), botId, target);
                    return Play.DONE;
                }
                case "AFFAIBLISSEMENT_OCCULTE" -> {
                    String target = pickWeakeningTargetId(g, bot);
                    if (target == null)
                        return Play.NONE;
                    if (stableMs < MODAL_CHOICE)
                        return Play.WAIT;
                    games.resolveOccultWeakening(g.getId(), botId, target);
                    return Play.DONE;
                }
                case "VOILE_DE_BRUME" -> {
                    String loc = pickFogLocation(g, bot);
                    if (loc == null)
                        return Play.NONE;
                    if (stableMs < MODAL_CHOICE)
                        return Play.WAIT;
                    games.resolveVoileDeBrume(g.getId(), botId, loc);
                    return Play.DONE;
                }
                case "PASSAGE_SECRET" -> {
                    // Modale SANS timer serveur, ouverte en FIN de préphase : le
                    // bot DOIT choisir une destination sous peine de bloquer la
                    // partie. Destination : lieu sûr (sans chasseurs), de
                    // préférence un bâtiment à âmes.
                    String dest = pickEscapeDestination(g, bot);
                    if (dest == null)
                        return Play.NONE; // le serveur re-validera (cas limite)
                    if (stableMs < MODAL_CHOICE)
                        return Play.WAIT;
                    games.resolveSecretPassage(g.getId(), botId, dest);
                    return Play.DONE;
                }
                case "IMAGE_MIROIR_SETUP" -> {
                    // 1er temps : poser un LEURRE (lieu de ma main ≠ mon lieu,
                    // non fumigé). Modale sans timer → à résoudre absolument.
                    String decoy = pickMirrorDecoy(g, bot);
                    if (decoy == null)
                        return Play.NONE; // le serveur re-validera (cas limite)
                    if (stableMs < MODAL_CHOICE)
                        return Play.WAIT;
                    games.resolveImageMiroirSetup(g.getId(), botId, decoy);
                    return Play.DONE;
                }
                case "IMAGE_MIROIR_RESOLVE" -> {
                    // 2e temps (fin de préphase) : me matérialiser là où il y a le
                    // MOINS de chasseurs (esquive de la traque), parmi mon lieu et
                    // les leurres posés.
                    String real = pickMirrorRealLocation(g, bot);
                    if (real == null)
                        return Play.NONE;
                    if (stableMs < MODAL_CHOICE)
                        return Play.WAIT;
                    games.resolveImageMiroirChoice(g.getId(), botId, real);
                    return Play.DONE;
                }
                default -> {
                    /* pas une modale vampire 3c : suite normale */ }
            }
        }

        var acts = bot.getActions();

        // Une action RÉSOLUE qui traîne dans currentAction (ex. la modale
        // informative de Faim irrépressible) ne bloque personne — c'est le bug
        // du R6 : le vampire se verrouillait derrière sa propre carte.
        if (a != null && a.getResolvedAtMillis() == null) {
            // Modale d'un AUTRE joueur en cours (ex. une Embuscade qui me vise).
            // Si je tiens une riposte potentielle (Présence écrasante) et que la
            // menace est déjà là (≥ 2 chasseurs sur mon lieu), on ATTEND la
            // résolution au lieu de se déclarer prêt — sinon la préphase se clôt
            // à l'instant où la modale se résout et la fenêtre de riposte
            // n'existe jamais.
            if (acts != null && acts.contains(Action.PRESENCE_ECRASANTE.name())
                    && !g.isHunterActionsBlockedThisRaid()
                    && huntersOnMyLocation(g, bot) >= 2)
                return Play.WAIT;
            return Play.NONE;
        }

        if (acts == null || acts.isEmpty())
            return Play.NONE;

        boolean inCombat = g.isHasUpcomingCombat() && hasEnemyOnMyLocation(g, bot);
        WeatherStatus w = g.getWeatherStatus();
        boolean provoked = g.getProvokedTargetByEnemy() != null
                && g.getProvokedTargetByEnemy().containsKey(botId);

        // 1) SURVIE D'ABORD (étape 3c passe 2 ; AVANT les combos — feedback game
        //    designer : sous embuscade, jouer Présence écrasante EN PREMIER, les
        //    combos ensuite une fois la riposte restaurée) — rapport de force
        //    défavorable sur mon lieu (≥ 2 chasseurs vivants) :
        //    a. Passage secret si je suis au Manoir et pas provoqué : la fuite
        //       se résout en FIN de préphase (les prépas adverses tombent à vide) ;
        //    b. sinon Présence écrasante : annule les prépas des chasseurs sur
        //       mon lieu (≥ 2 « poids » de prépas : filets/fosses = 1, embuscade = 2).
        int threat = huntersOnMyLocation(g, bot);
        if (threat >= 2) {
            if (acts.contains(Action.PASSAGE_SECRET.name())
                    && "manor".equals(g.locationOf(botId)) && !provoked
                    && g.getPendingVampireEscape() == null
                    && pickEscapeDestination(g, bot) != null) {
                if (stableMs < cardDelay(PREPHASE_ACTION))
                    return Play.WAIT;
                games.useAction(g.getId(), botId, Action.PASSAGE_SECRET);
                return Play.DONE;
            }
            if (acts.contains(Action.PRESENCE_ECRASANTE.name())
                    && !g.isHunterActionsBlockedThisRaid()) {
                if (prepsWeightOnMyLocation(g, bot) >= 2) {
                    if (stableMs < cardDelay(PREPHASE_ACTION))
                        return Play.WAIT;
                    games.useAction(g.getId(), botId, Action.PRESENCE_ECRASANTE);
                    return Play.DONE;
                }
                // ANTICIPATION : des chasseurs de mon lieu n'ont pas encore dit
                // « j'ai fini » → ils peuvent encore empiler des prépas. On ne se
                // déclare PAS prêt tant qu'ils n'ont pas montré leur jeu — sinon
                // la préphase se clôt sur leur dernière pose et la fenêtre de
                // riposte n'existe jamais.
                if (huntersOnMyLocationNotAllReady(g, bot))
                    return Play.WAIT;
            }
        }

        // Suis-je NEUTRALISÉ pour les combos offensifs ? (feedback game designer)
        // - Embusqué sans Présence écrasante : je ne peux riposter contre AUCUN
        //   des embusqueurs → Éclipse/Lune/Faim claquées pour rien.
        // - Provoqué : je n'attaque QUE le provocateur → Éclipse/Lune trop chères
        //   pour une seule cible (la Faim reste rentable : morsure sur lui).
        boolean ambushedNoCounter = isAmbushTarget(g, botId)
                && !acts.contains(Action.PRESENCE_ECRASANTE.name());

        // 2) Cartes de combat — seulement si un combat m'implique ce raid et que
        //    je ne suis pas neutralisé.
        if (inCombat && !ambushedNoCounter) {
            // Éclipse : impose la Pleine lune si on n'y est pas déjà
            if (!provoked && acts.contains(Action.ECLIPSE.name())
                    && w != WeatherStatus.FULL_MOON && w != WeatherStatus.BLOOD_MOON) {
                if (stableMs < cardDelay(PREPHASE_ACTION))
                    return Play.WAIT;
                games.useAction(g.getId(), botId, Action.ECLIPSE);
                return Play.DONE;
            }
            // Lune de sang : requiert la Pleine lune (après Éclipse ou naturelle)
            if (!provoked && acts.contains(Action.BLOOD_MOON.name()) && w == WeatherStatus.FULL_MOON) {
                if (stableMs < cardDelay(PREPHASE_ACTION))
                    return Play.WAIT;
                games.useAction(g.getId(), botId, Action.BLOOD_MOON);
                return Play.DONE;
            }
            // Faim irrépressible : si une cible corruptible est sur mon lieu
            if (acts.contains(Action.FAIM_IRREPRESSIBLE.name())
                    && !g.isHungerAllowsBiteThisRaid()
                    && hasCorruptibleHunterOnMyLocation(g, bot)) {
                if (stableMs < cardDelay(PREPHASE_ACTION))
                    return Play.WAIT;
                games.useAction(g.getId(), botId, Action.FAIM_IRREPRESSIBLE);
                return Play.DONE;
            }
        }
        // Affaiblissement occulte : DÉFENSIF (−2 ATK au meilleur attaquant) —
        // utile même neutralisé, dès qu'un combat m'attend.
        if (inCombat && acts.contains(Action.AFFAIBLISSEMENT_OCCULTE.name())
                && pickWeakeningTargetId(g, bot) != null) {
            if (stableMs < cardDelay(PREPHASE_ACTION))
                return Play.WAIT;
            games.useAction(g.getId(), botId, Action.AFFAIBLISSEMENT_OCCULTE);
            return Play.DONE;
        }

        // 2) Marque ténébreuse (étape 3c, §7) : se pose TÔT sur LA victime, sans
        //    attendre un combat — +1 corruption à chaque contact (1×/raid), jusqu'à
        //    purification. Une seule marque à la fois pour le cerveau naïf.
        if (acts.contains(Action.MARQUE_TENEBREUSE.name())
                && !hasLivingMarkedHunter(g) && pickMarkTargetId(g, bot) != null) {
            if (stableMs < cardDelay(PREPHASE_ACTION))
                return Play.WAIT;
            games.useAction(g.getId(), botId, Action.MARQUE_TENEBREUSE);
            return Play.DONE;
        }

        // 3) Voile de brume : débuff pré-combat sur MON lieu, sinon harcèlement
        //    éco (récolte /2) sur un lieu riche en chasseurs (≥ 2).
        if (acts.contains(Action.VOILE_DE_BRUME.name())
                && g.getFogAffectedLocation() == null && pickFogLocation(g, bot) != null) {
            if (stableMs < cardDelay(PREPHASE_ACTION))
                return Play.WAIT;
            games.useAction(g.getId(), botId, Action.VOILE_DE_BRUME);
            return Play.DONE;
        }
        return Play.NONE;
    }

    /** Suis-je la cible d'une Embuscade RÉSOLUE ce raid ? (riposte bloquée) */
    private boolean isAmbushTarget(Game g, String playerId) {
        var byEnemy = g.getAmbushHuntersByEnemy();
        if (byEnemy == null)
            return false;
        var hunters = byEnemy.get(playerId);
        return hunters != null && !hunters.isEmpty();
    }

    /** Au moins un chasseur vivant de MON lieu n'a pas encore dit « j'ai fini ». */
    private boolean huntersOnMyLocationNotAllReady(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return false;
        var ready = g.getReadyForPhase3();
        return g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .filter(p -> loc.equals(g.locationOf(p.getId())))
                .anyMatch(p -> ready == null || !ready.contains(p.getId()));
    }

    /** Nombre de chasseurs VIVANTS sur le lieu du bot. */
    private int huntersOnMyLocation(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return 0;
        return (int) g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .filter(p -> loc.equals(g.locationOf(p.getId())))
                .count();
    }

    /**
     * « Poids » des préparations de cartes chasseur posées sur MON lieu (info
     * publique — annoncées dans l'historique) : filet/fosse = 1 chacun,
     * embuscade = 2. Seuil de déclenchement de Présence écrasante.
     */
    private int prepsWeightOnMyLocation(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return 0;
        int w = 0;
        if (g.getNetHunters() != null)
            w += (int) g.getNetHunters().stream()
                    .filter(h -> loc.equals(g.locationOf(h))).count();
        if (g.getPitHunters() != null)
            w += (int) g.getPitHunters().stream()
                    .filter(h -> loc.equals(g.locationOf(h))).count();
        if (g.getAmbushLocations() != null && g.getAmbushLocations().contains(loc))
            w += 2;
        return w;
    }

    /**
     * Destination du Passage secret : un lieu autorisé (base + infras
     * construites, hors Manoir, non fumigé) SANS chasseur vivant — de
     * préférence un bâtiment à âmes (le Bâtisseur continue de récolter).
     */
    private String pickEscapeDestination(Game g, Player bot) {
        List<String> prefs = new ArrayList<>(List.of("laboratory", "library", "ballroom", "forge", "altar"));
        // Ne garder que les infras réellement construites
        var built = g.getBuiltInfras();
        prefs.removeIf(code -> built == null || built.stream()
                .noneMatch(i -> i.locationCode().equals(code)));
        prefs.addAll(List.of("forest", "quarry", "lake")); // lieux de base, toujours ouverts
        var garlic = g.getGarlicBlockedLocations();
        for (String code : prefs) {
            if (garlic != null && garlic.contains(code))
                continue;
            boolean hunterThere = g.getPlayers().stream()
                    .filter(Player::isAlive)
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .anyMatch(p -> code.equals(g.locationOf(p.getId())));
            if (!hunterThere)
                return code;
        }
        return null;
    }

    /**
     * Image miroir (PHASE2) : DEUX usages (feedback game designer).
     * <ul>
     * <li><b>Défensif (anti-pistage)</b> : des Pisteurs me traquent et je n'ai
     * PAS de refuge Salle de bal (sinon je préfère la Danse macabre) → poser un
     * leurre et me matérialiser ailleurs après révélation.</li>
     * <li><b>Offensif (chasse)</b> : je suis apte (PV > 50 %) et une proie
     * JUTEUSE existe (chasseur à corruption 1-2 ou PV bas) → surgir sur 1-2
     * chasseurs isolés vulnérables pour les achever ou les convertir.</li>
     * </ul>
     * En PHASE2 les positions adverses sont CACHÉES (équité) : le déclencheur
     * offensif est un PARI sur l'existence d'une proie ; le VRAI ciblage se fait
     * en PREPHASE3 (positions publiques) dans {@link #pickMirrorDecoy} et
     * {@link #pickMirrorRealLocation}. Gardes communes : carte en main, pas
     * provoqué, pas déjà en évasion, pas de modale ouverte, et — critique
     * anti-blocage — au moins un lieu-leurre en main.
     */
    private boolean shouldPlayImageMiroir(Game g, Player bot) {
        var acts = bot.getActions();
        if (acts == null || !acts.contains(Action.IMAGE_MIROIR.name()))
            return false;
        if (g.getPendingVampireEscape() != null)
            return false; // une évasion (Passage secret / autre miroir) est déjà amorcée
        if (g.getCurrentAction() != null)
            return false; // une modale est ouverte : le serveur refuserait
        if (g.getProvokedTargetByEnemy() != null
                && g.getProvokedTargetByEnemy().containsKey(bot.getId()))
            return false; // provoqué : Image miroir interdite
        // Garde ANTI-BLOCAGE : un lieu-leurre en main DOIT exister (sans lire les
        // positions, cachées en PHASE2).
        if (!hasAnyMirrorDecoyCard(g, bot))
            return false;

        // DÉFENSIF : traqué ce raid + pas de refuge Salle de bal.
        var trackers = g.getTrackerHunters();
        boolean tracked = trackers != null && trackers.stream()
                .map(g::findPlayer)
                .anyMatch(p -> p != null && p.isAlive());
        boolean defensive = tracked && ballroomRefugeLocation(g, bot) == null;

        // OFFENSIF : apte + une proie juteuse existe (pari, positions cachées ici).
        boolean offensive = vampHpAboveHalf(g, bot) && existsJuicyHunter(g);

        return defensive || offensive;
    }

    /**
     * 1er temps d'Image miroir (SETUP, PREPHASE3, positions révélées) : le
     * LEURRE. Un lieu de ma main (≠ mon lieu joué, non fumigé). Priorité
     * OFFENSIVE : poser le lieu d'une proie isolée vulnérable (pour pouvoir m'y
     * matérialiser ensuite) ; à défaut, le lieu le plus SÛR (défensif — leurre
     * d'esquive).
     */
    private String pickMirrorDecoy(Game g, Player bot) {
        List<String> cands = mirrorHandLocations(g, bot);
        if (cands.isEmpty())
            return null;
        // 1) OFFENSIF : la meilleure proie isolée parmi mes lieux de main.
        String bestOff = null;
        int bestScore = -1;
        for (String code : cands) {
            int s = mirrorOffensiveScore(g, code);
            if (s > bestScore) {
                bestScore = s;
                bestOff = code;
            }
        }
        if (bestOff != null && bestScore >= 0)
            return bestOff;
        // 2) DÉFENSIF : le plus sûr (le moins de chasseurs).
        return fewestHuntersLocation(g, cands);
    }

    /**
     * 2e temps d'Image miroir (RESOLVE, fin de préphase, positions révélées) : me
     * matérialiser, parmi mon lieu principal (carte du centre) et les leurres
     * posés. Priorité OFFENSIVE : surgir sur la proie isolée la plus juteuse
     * (achever / convertir) ; à défaut, le lieu avec le MOINS de chasseurs
     * (esquive de la traque).
     */
    private String pickMirrorRealLocation(Game g, Player bot) {
        String primary = g.locationOf(bot.getId());
        var alts = g.getMirrorAltLocations();
        var garlic = g.getGarlicBlockedLocations();
        List<String> candidates = new ArrayList<>();
        if (primary != null)
            candidates.add(primary);
        if (alts != null)
            for (String a : alts)
                if (!candidates.contains(a))
                    candidates.add(a);
        candidates.removeIf(c -> garlic != null && garlic.contains(c));
        if (candidates.isEmpty())
            return null;
        // 1) OFFENSIF : la proie isolée la plus juteuse accessible.
        String bestOff = null;
        int bestScore = -1;
        for (String code : candidates) {
            int s = mirrorOffensiveScore(g, code);
            if (s > bestScore) {
                bestScore = s;
                bestOff = code;
            }
        }
        if (bestOff != null && bestScore >= 0)
            return bestOff;
        // 2) DÉFENSIF : le moins de chasseurs.
        return fewestHuntersLocation(g, candidates);
    }

    /** Lieux VALIDES de ma main pour un leurre : carte de lieu, ≠ mon lieu, non fumigé. */
    private List<String> mirrorHandLocations(Game g, Player bot) {
        List<String> out = new ArrayList<>();
        List<String> hand = bot.getHand();
        if (hand == null)
            return out;
        String primary = g.locationOf(bot.getId());
        var garlic = g.getGarlicBlockedLocations();
        for (String code : hand) {
            if (code.equals(primary))
                continue;
            if (Location.fromCode(code) == null)
                continue; // pas une carte de lieu
            if (garlic != null && garlic.contains(code))
                continue;
            out.add(code);
        }
        return out;
    }

    /** Existe-t-il au moins un lieu-leurre en main ? (sans lire les positions adverses) */
    private boolean hasAnyMirrorDecoyCard(Game g, Player bot) {
        return !mirrorHandLocations(g, bot).isEmpty();
    }

    /** Le lieu de la liste avec le moins de chasseurs vivants (choix défensif). */
    private String fewestHuntersLocation(Game g, List<String> codes) {
        String best = null;
        int fewest = Integer.MAX_VALUE;
        for (String code : codes) {
            int h = huntersAtLocation(g, code);
            if (h < fewest) {
                fewest = h;
                best = code;
            }
        }
        return best;
    }

    /**
     * Score OFFENSIF d'un lieu pour Image miroir : −1 si ce n'est pas une bonne
     * cible (aucun chasseur, MEUTE de &gt; 2, ou aucune proie juteuse). Sinon,
     * valeur de la meilleure proie (corruption ×100 + bonus PV bas), pénalisée
     * par un 2e défenseur — on cible 1-2 chasseurs ISOLÉS vulnérables.
     */
    private int mirrorOffensiveScore(Game g, String loc) {
        if (loc == null)
            return -1;
        List<Player> hs = huntersOn(g, loc);
        if (hs.isEmpty() || hs.size() > 2)
            return -1; // proie ISOLÉE (1-2), pas une meute
        int best = -1;
        for (Player h : hs) {
            if (!isJuicyHunter(h))
                continue;
            int v = h.getCorruption() * 100 + Math.max(0, 30 - h.getHp());
            if (v > best)
                best = v;
        }
        if (best < 0)
            return -1; // aucune proie juteuse ici
        return best - (hs.size() - 1) * 20; // pénalise un 2e défenseur
    }

    /** Un chasseur « juteux » : convertible (corruption 1-2) ou finissable (PV bas). */
    private boolean isJuicyHunter(Player p) {
        return (p.getCorruption() >= 1 && p.getCorruption() < 3)
                || p.getHp() <= MIRROR_FINISH_HP;
    }

    /** Existe-t-il un chasseur vivant « juteux » ? (info publique : PV/corruption) */
    private boolean existsJuicyHunter(Game g) {
        return g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .anyMatch(this::isJuicyHunter);
    }

    /** Chasseurs VIVANTS sur un lieu donné. */
    private List<Player> huntersOn(Game g, String loc) {
        if (loc == null)
            return List.of();
        return g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .filter(p -> loc.equals(g.locationOf(p.getId())))
                .toList();
    }

    /** Nombre de chasseurs VIVANTS sur un lieu donné (code). */
    private int huntersAtLocation(Game g, String loc) {
        return huntersOn(g, loc).size();
    }

    /** Un chasseur vivant porte-t-il déjà la Marque ténébreuse ? */
    private boolean hasLivingMarkedHunter(Game g) {
        var marked = g.getDarkMarkedHunters();
        if (marked == null || marked.isEmpty())
            return false;
        return g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .anyMatch(p -> marked.contains(p.getId()));
    }

    /**
     * Victime de la Marque ténébreuse : un chasseur vivant NON marqué SUR MON
     * LIEU (feedback game designer : la marque ne déclenche qu'AU CONTACT —
     * la poser sur un absent ne rapporte rien, alors qu'au contact c'est
     * +1 corruption immédiat + morsure potentielle le même raid). Parmi les
     * présents : le plus corrompu, à égalité le plus faible en PV.
     */
    private String pickMarkTargetId(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return null;
        var marked = g.getDarkMarkedHunters();
        return g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .filter(p -> loc.equals(g.locationOf(p.getId())))
                .filter(p -> marked == null || !marked.contains(p.getId()))
                .max(java.util.Comparator
                        .comparingInt(Player::getCorruption)
                        .thenComparing(p -> -p.getHp()))
                .map(Player::getId).orElse(null);
    }

    /**
     * Cible d'Affaiblissement occulte : le chasseur le plus menaçant SUR MON
     * LIEU (meilleure arme, puis PV les plus hauts) — le malus −2 ATK ne vaut
     * que pour un combat imminent.
     */
    private String pickWeakeningTargetId(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return null;
        return g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .filter(p -> loc.equals(g.locationOf(p.getId())))
                .max(java.util.Comparator
                        .comparingInt((Player p) -> equipment.hunterWeaponTier(p.getWeapon()))
                        .thenComparingInt(Player::getHp))
                .map(Player::getId).orElse(null);
    }

    /**
     * Lieu du Voile de brume : MON lieu si un combat m'y attend (−1 DEF pour
     * les chasseurs présents), sinon le lieu le plus peuplé en chasseurs
     * (récolte /2) s'ils y sont au moins 2 — sinon on garde la carte.
     */
    private String pickFogLocation(Game g, Player bot) {
        String myLoc = g.locationOf(bot.getId());
        if (myLoc != null && g.isHasUpcomingCombat() && hasEnemyOnMyLocation(g, bot))
            return myLoc;
        var byLoc = new java.util.HashMap<String, Integer>();
        for (Player p : g.getPlayers()) {
            if (!p.isAlive() || !"HUNTER".equals(p.getRole()))
                continue;
            String loc = g.locationOf(p.getId());
            if (loc != null)
                byLoc.merge(loc, 1, Integer::sum);
        }
        return byLoc.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElse(null);
    }

    /** Un chasseur vivant, corruptible (corruption < 3), sur le lieu du vampire ? */
    private boolean hasCorruptibleHunterOnMyLocation(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return false;
        return g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .filter(p -> p.getCorruption() < 3)
                .anyMatch(p -> loc.equals(g.locationOf(p.getId())));
    }

    /**
     * Cartes de SURVIE du chasseur en PREPHASE3 (étape 3b, passe 1) : Chapelet
     * sacré, Eau bénite (3 modes) et Épieu béni. On joue AU PLUS une carte par
     * tick, priorité à la survie (corruption / morsure) avant l'offensif.
     *
     * L'Eau bénite ouvre une modale bloquante (currentAction) : on la RÉSOUT
     * d'abord si elle est déjà ouverte par ce bot, puis seulement on envisage
     * de jouer une nouvelle carte. Toutes les gardes serveur sont re-vérifiées
     * par la façade ; ici on approxime avec l'info publique + notre main.
     * (Embuscade et Filet/Fosse — cartes « coordonnées / pipeline de combat » —
     * arrivent en passe 2.)
     */
    private Play tryHunterCombatCards(Game g, Player bot, long stableMs) {
        if (!"HUNTER".equals(bot.getRole()) || !bot.isAlive())
            return Play.NONE;
        String botId = bot.getId();

        // 1) Ma modale Eau bénite est déjà ouverte → choisir le mode et résoudre.
        Game.Action a = g.getCurrentAction();
        if (a != null && "EAU_BENITE".equals(a.getMode())
                && botId.equals(a.getOwnerId()) && a.getResolvedAtMillis() == null) {
            if (stableMs < MODAL_CHOICE)
                return Play.WAIT;
            String mode = holyWaterMode(g, bot);
            if (mode == null)
                mode = "REDUCE"; // filet de sécurité : toujours valide, ne bloque pas la modale
            games.resolveHolyWater(g.getId(), botId, mode);
            return Play.DONE;
        }
        // 1bis) Ma modale Embuscade est ouverte → choisir la cible (vampire prioritaire).
        if (a != null && "AMBUSH".equals(a.getMode())
                && botId.equals(a.getOwnerId()) && a.getResolvedAtMillis() == null) {
            String target = pickAmbushTargetId(g, bot);
            if (target == null)
                return Play.NONE; // plus de cible (partie/mort) : le serveur nettoiera
            if (stableMs < MODAL_CHOICE)
                return Play.WAIT;
            games.resolveAmbush(g.getId(), botId, target);
            return Play.DONE;
        }
        // 1bis-2) Ma modale Provocation est ouverte → cibler le vampire/serviteur.
        if (a != null && "PROVOCATION".equals(a.getMode())
                && botId.equals(a.getOwnerId()) && a.getResolvedAtMillis() == null) {
            String target = pickProvocationTargetId(g, bot);
            if (target == null)
                return Play.NONE;
            if (stableMs < MODAL_CHOICE)
                return Play.WAIT;
            games.resolveProvocation(g.getId(), botId, target);
            return Play.DONE;
        }
        // 1ter) Ma caisse abandonnée est ouverte : lancer le d6, puis fermer
        //       après lecture du résultat (le serveur attend resolveCrateAction
        //       pour libérer la modale).
        if (a != null && ("CRATE_LAKE".equals(a.getMode()) || "CRATE_MANOR".equals(a.getMode()))
                && botId.equals(a.getOwnerId())) {
            if (a.getResolvedAtMillis() == null) {
                if (stableMs < MODAL_CHOICE)
                    return Play.WAIT;
                games.rollCrate(g.getId(), botId);
                return Play.DONE;
            }
            if (stableMs < PREPHASE_ACTION)
                return Play.WAIT; // laisser le résultat du d6 s'AFFICHER (délai d'affichage → pas d'hésitation)
            games.resolveCrateAction(g.getId(), botId);
            return Play.DONE;
        }

        // 2) Une autre action NON RÉSOLUE est en cours (la mienne ou celle d'un
        //    autre) → attendre. Une action résolue qui traîne dans currentAction
        //    (modale informative type Faim) ne bloque pas.
        if (a != null && a.getResolvedAtMillis() == null)
            return Play.NONE;

        var acts = bot.getActions();
        if (acts == null || acts.isEmpty())
            return Play.NONE;

        // 3) Jouer une nouvelle carte (une par tick), survie d'abord.
        // a0) Caisse abandonnée : ressources gratuites quand je suis au bon
        //     lieu (feedback game designer : les bots ne les jouaient jamais).
        if (!bot.isCrateUsedThisRaid()) {
            String myLoc = g.locationOf(botId);
            Action crate = "lake".equals(myLoc) && acts.contains(Action.CRATE_LAKE.name())
                    ? Action.CRATE_LAKE
                    : "manor".equals(myLoc) && acts.contains(Action.CRATE_MANOR.name())
                            ? Action.CRATE_MANOR
                            : null;
            if (crate != null) {
                if (stableMs < cardDelay(PREPHASE_ACTION))
                    return Play.WAIT;
                games.useAction(g.getId(), botId, crate);
                return Play.DONE;
            }
        }

        // a) Eau bénite : CLEANSE (marqué) > REDUCE (corruption ≥ 2) > ATTACK
        //    (duel contre le vampire, corruption saine). On l'ouvre seulement si
        //    un mode est réellement utile.
        if (acts.contains(Action.EAU_BENITE.name()) && holyWaterMode(g, bot) != null) {
            if (stableMs < cardDelay(PREPHASE_ACTION))
                return Play.WAIT;
            games.useAction(g.getId(), botId, Action.EAU_BENITE);
            return Play.DONE;
        }
        // b) Chapelet sacré (persistant) : assurance-vie du rush corruption —
        //    corruption déjà entamée ET vampire au contact (morsure probable).
        if (acts.contains(Action.SACRED_ROSARY.name()) && !bot.isSacredRosary()
                && bot.getCorruption() >= 1 && vampSideOnMyLocation(g, bot)) {
            if (stableMs < cardDelay(PREPHASE_ACTION))
                return Play.WAIT;
            games.useAction(g.getId(), botId, Action.SACRED_ROSARY);
            return Play.DONE;
        }
        // c) Épieu béni (persistant, se déclenche en PHASE3) : vampire/serviteur
        //    au contact et pas déjà équipé.
        if (acts.contains(Action.BLESSED_STAKE.name()) && !bot.isBlessedStake()
                && vampSideOnMyLocation(g, bot)) {
            if (stableMs < cardDelay(PREPHASE_ACTION))
                return Play.WAIT;
            games.useAction(g.getId(), botId, Action.BLESSED_STAKE);
            return Play.DONE;
        }

        // c2) Feu de camp : sous Crépuscule / Nuit obscure / Nuit claire, si un
        //     combat m'attend sur mon lieu — annule le malus météo ET ré-autorise
        //     mes pièges (prérequis avant Filet/Fosse sous Nuit obscure).
        if (acts.contains(Action.FEU_DE_CAMP.name())
                && isNightWeather(g.getWeatherStatus())
                && hasEnemyOnMyLocation(g, bot)
                && !campfireOnMyLocation(g, bot)) {
            if (stableMs < cardDelay(PREPHASE_ACTION))
                return Play.WAIT;
            games.useAction(g.getId(), botId, Action.FEU_DE_CAMP);
            return Play.DONE;
        }

        // 4) Pièges & coordination (passe 2) — combat certain sur mon lieu.
        // d) Embuscade : ≥ 2 chasseurs vivants + un ennemi JOUEUR sur mon lieu,
        //    lieu pas déjà embusqué ce raid. On la GARDE si la cible est presque
        //    morte (une attaque normale suffit — grille §8). useAction ouvre la
        //    modale de cible, résolue au tick suivant (1bis), délai visible.
        if (acts.contains(Action.AMBUSH.name()) && canAmbushHere(g, bot)) {
            if (stableMs < cardDelay(PREPHASE_ACTION))
                return Play.WAIT;
            games.useAction(g.getId(), botId, Action.AMBUSH);
            return Play.DONE;
        }
        // e) Fosse avant Filet (« si on n'a qu'un tour », grille §8) : cible qui
        //    VAUT le piège (vampire de préférence — voir trapWorthyTarget),
        //    interdit sous Nuit obscure sans feu de camp.
        boolean trapsAllowed = !trapsBlockedByNight(g, bot);
        if (trapsAllowed && acts.contains(Action.PIT.name()) && trapWorthyTargetOnMyLocation(g, bot)) {
            if (stableMs < cardDelay(PREPHASE_ACTION))
                return Play.WAIT;
            games.useAction(g.getId(), botId, Action.PIT);
            return Play.DONE;
        }
        // f) Filet : mêmes conditions (le ciblage/jet se fait en PHASE3).
        if (trapsAllowed && acts.contains(Action.NET.name()) && trapWorthyTargetOnMyLocation(g, bot)) {
            if (stableMs < cardDelay(PREPHASE_ACTION))
                return Play.WAIT;
            games.useAction(g.getId(), botId, Action.NET);
            return Play.DONE;
        }

        // g) Solitaire : je duelle le vampire ISOLÉ (≥ 2 chasseurs absents ce
        //    raid → gros bonus +N ATK/DEF). L'inverse de l'Embuscade — pour le
        //    traqueur/tank seul face au vampire.
        if (acts.contains(Action.LONELY.name()) && !hasLonelyMod(g, botId)
                && vampSideOnMyLocation(g, bot) && absentHuntersCount(g, bot) >= 2) {
            if (stableMs < cardDelay(PREPHASE_ACTION))
                return Play.WAIT;
            games.useAction(g.getId(), botId, Action.LONELY);
            return Play.DONE;
        }
        // h) Provocation : vampire au contact. Deux usages (game designer) :
        //    - TANK : à PLUSIEURS chasseurs (≥ 2 sur mon lieu — INUTILE en 1v1),
        //      le plus costaud provoque pour prendre les coups à la place des
        //      alliés fragiles ; seulement si aucune Embuscade n'est jouable
        //      (sinon elle prime, plus forte en groupe) ;
        //    - ANTI-FUITE : bloquer un Passage secret / Image miroir en cours.
        boolean provAntiEscape = g.getPendingVampireEscape() != null;
        boolean provTank = vampSideOnMyLocation(g, bot)
                && huntersOnMyLocation(g, bot) >= 2
                && iAmToughestHunterHere(g, bot)
                && !canAmbushHere(g, bot);
        if (acts.contains(Action.PROVOCATION.name())
                && (provAntiEscape || provTank)
                && pickProvocationTargetId(g, bot) != null) {
            if (stableMs < cardDelay(PREPHASE_ACTION))
                return Play.WAIT;
            games.useAction(g.getId(), botId, Action.PROVOCATION);
            return Play.DONE;
        }
        return Play.NONE;
    }

    /** Suis-je le chasseur le plus SOLIDE de mon lieu (PV, puis tier d'armure) ?
     *  → le mieux placé pour encaisser à la place des alliés (rôle de tank). */
    private boolean iAmToughestHunterHere(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return false;
        int myScore = bot.getHp() * 10 + equipment.hunterArmorTier(bot.getArmor());
        return g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()) && !p.getId().equals(bot.getId()))
                .filter(p -> loc.equals(g.locationOf(p.getId())))
                .noneMatch(p -> p.getHp() * 10 + equipment.hunterArmorTier(p.getArmor()) > myScore);
    }

    /** Météo « nuit » que le Feu de camp annule (Crépuscule / Nuit obscure / Nuit claire). */
    private boolean isNightWeather(WeatherStatus w) {
        return w == WeatherStatus.DUSK || w == WeatherStatus.NIGHT_DARK
                || w == WeatherStatus.NIGHT_CLEAR;
    }

    /** Un feu de camp brûle-t-il déjà sur mon lieu ? */
    private boolean campfireOnMyLocation(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        return loc != null && g.getCampfireLocations() != null
                && g.getCampfireLocations().contains(loc);
    }

    /** Chasseurs vivants ABSENTS de mon lieu (bonus de Solitaire). */
    private int absentHuntersCount(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return 0;
        return (int) g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .filter(p -> !loc.equals(g.locationOf(p.getId())))
                .count();
    }

    /** Ai-je déjà le mod Solitaire ce raid ? */
    private boolean hasLonelyMod(Game g, String playerId) {
        if (g.getRaidMods() == null)
            return false;
        var mods = g.getRaidMods().get(playerId);
        return mods != null && mods.stream()
                .anyMatch(m -> m.getSource() != null && m.getSource().startsWith("ACTION:LONELY"));
    }

    /** Cible de Provocation : le vampire de préférence, sinon un serviteur, sur mon lieu. */
    private String pickProvocationTargetId(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return null;
        Player best = null;
        for (Player p : g.getPlayers()) {
            if (!p.isAlive() || !loc.equals(g.locationOf(p.getId())))
                continue;
            boolean vampSide = "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole());
            if (!vampSide)
                continue;
            if (best == null || "VAMPIRE".equals(p.getRole()))
                best = p;
        }
        return best != null ? best.getId() : null;
    }

    /**
     * Une cible qui MÉRITE un piège est-elle sur mon lieu ? Préférence du game
     * designer : garder Filet/Fosse pour le VAMPIRE (ou un serviteur) — on ne
     * les « gaspille » sur un monstre que s'il est puissant (pas un simple
     * Revenant / Chauve-souris) ou si je risque d'y rester (PV bas : tout
     * malus de défense adverse devient vital).
     */
    private boolean trapWorthyTargetOnMyLocation(Game g, Player bot) {
        if (vampSideOnMyLocation(g, bot))
            return true;
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return false;
        boolean monsterHere = g.monstersOn(loc).stream().anyMatch(m -> m.hp > 0);
        if (!monsterHere)
            return false;
        boolean strongMonster = g.monstersOn(loc).stream()
                .filter(m -> m.hp > 0)
                .anyMatch(m -> m.type != Game.MonsterType.BAT && m.type != Game.MonsterType.REVENANT);
        boolean inDanger = bot.getHp() <= 6; // ~1 attaque de la mort (max 20 PV)
        return strongMonster || inDanger;
    }

    /** Nuit obscure sans feu de camp sur mon lieu → Filet/Fosse interdits (miroir serveur). */
    private boolean trapsBlockedByNight(Game g, Player bot) {
        if (g.getWeatherStatus() != WeatherStatus.NIGHT_DARK)
            return false;
        String loc = g.locationOf(bot.getId());
        return loc == null || g.getCampfireLocations() == null
                || !g.getCampfireLocations().contains(loc);
    }

    /**
     * Conditions d'Embuscade (miroir de useAction AMBUSH). Le cœur de la carte
     * (game designer) : sa valeur monte avec le NOMBRE de chasseurs présents
     * (+N ATK chacun — l'inverse de Solitaire). On ne l'exige pas « au complet »
     * (plus la partie compte de joueurs, plus c'est dur à réunir) : on demande
     * au moins la MOITIÉ des chasseurs vivants, minimum 2 (minimum serveur).
     */
    private boolean canAmbushHere(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return false;
        if (g.getAmbushLocations() != null && g.getAmbushLocations().contains(loc))
            return false; // une seule embuscade par lieu et par raid
        long aliveHunters = g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .count();
        long huntersHere = g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> "HUNTER".equals(p.getRole()))
                .filter(p -> loc.equals(g.locationOf(p.getId())))
                .count();
        long required = Math.max(2, (aliveHunters + 1) / 2); // ⌈vivants/2⌉, min 2
        if (huntersHere < required)
            return false;
        return pickAmbushTargetId(g, bot) != null;
    }

    /**
     * Cible d'Embuscade sur mon lieu : le vampire en priorité, sinon un serviteur.
     * On ne « gaspille » pas la carte sur une cible presque morte (≤ 4 PV) : une
     * attaque normale suffit, on garde le finisher pour plus tard.
     */
    private String pickAmbushTargetId(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return null;
        Player best = null;
        for (Player p : g.getPlayers()) {
            if (!p.isAlive() || !loc.equals(g.locationOf(p.getId())))
                continue;
            boolean vampSide = "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole());
            if (!vampSide || p.getHp() <= 4)
                continue;
            if (best == null || "VAMPIRE".equals(p.getRole()))
                best = p;
        }
        return best != null ? best.getId() : null;
    }

    /**
     * Cible de Filet sur mon lieu : vampire > serviteur > monstre vivant
     * (miroir des cibles valides de resolveNet).
     */
    private String pickNetTargetId(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return null;
        Player bestPlayer = null;
        for (Player p : g.getPlayers()) {
            if (!p.isAlive() || !loc.equals(g.locationOf(p.getId())))
                continue;
            boolean vampSide = "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole());
            if (!vampSide)
                continue;
            if (bestPlayer == null || "VAMPIRE".equals(p.getRole()))
                bestPlayer = p;
        }
        if (bestPlayer != null)
            return bestPlayer.getId();
        return g.monstersOn(loc).stream()
                .filter(m -> m.hp > 0) // monstersOn ne filtre pas les morts
                .map(m -> m.id)
                .findFirst().orElse(null);
    }

    /**
     * Mode d'Eau bénite le plus utile MAINTENANT, ou null s'il n'y a rien à en
     * tirer (on garde alors la carte). Priorité survie : dissiper la Marque,
     * puis purifier la corruption. Miroir des canHolyWater* du front et des
     * gardes de resolveHolyWater.
     */
    private String holyWaterMode(Game g, Player bot) {
        String botId = bot.getId();
        if (g.getDarkMarkedHunters() != null && g.getDarkMarkedHunters().contains(botId))
            return "CLEANSE";
        // Purifier dès le niveau 1 (feedback game designer) : le mod « Affaibli »
        // (−1 ATK/DEF) pèse sur toute la partie — ne pas rester corrompu.
        if (bot.getCorruption() >= 1)
            return "REDUCE";
        // ATTACK : uniquement pour ACHEVER un vampire très entamé (≤ 20 % PV).
        // Sinon c'est la PIRE utilisation (feedback game designer) : on brûle la
        // purification alors que la corruption va probablement monter (morsures,
        // Faim irrépressible…) — on garde la fiole.
        var fx = g.getRaidEffects() != null ? g.getRaidEffects().get(botId) : null;
        boolean alreadyHoly = fx != null && fx.isHolyWaterAttack();
        if (!alreadyHoly && g.isHasUpcomingCombat() && vampSideOnMyLocation(g, bot)
                && vampireNearDeath(g))
            return "ATTACK";
        return null;
    }

    /**
     * Monstre d'Expérimentation selon le LIEU à défendre (feedback game
     * designer) : les gros monstres (Aberration 600 raid ≥ 10, Gargouille 400
     * raid ≥ 5) sont réservés aux bâtiments critiques (Autel, Laboratoire) ;
     * ailleurs (Salle de bal, Scierie, Mine) un monstre BAS NIVEAU suffit.
     * Marge de 2 défenses basiques conservée pour les re-défenses.
     */
    private Game.MonsterType pickExperimentMonster(Game g, Player bot, String loc) {
        boolean critical = "altar".equals(loc) || "laboratory".equals(loc);
        if (!critical)
            return Game.MonsterType.BAT;
        // Sur les lieux CRITIQUES, PRIVILÉGIER les monstres moyens/forts dès
        // leur déblocage (feedback game designer) : marge minimale d'une seule
        // défense basique — les ressources ne permettront pas d'en mettre
        // partout, donc Labo et Autel d'abord, « s'il peut ».
        int margin = EXPERIMENT_COST;
        if (g.getRaid() >= 10 && bot.getSouls() >= 600 + margin)
            return Game.MonsterType.ABERRATION;
        if (g.getRaid() >= 5 && bot.getSouls() >= 300 + margin)
            return Game.MonsterType.GARGOYLE;
        return Game.MonsterType.BAT;
    }

    /** Le vampire est-il à ≤ 20 % de ses PV max ? (fenêtre d'exécution) */
    private boolean vampireNearDeath(Game g) {
        int maxHp = 20 + Math.max(0, g.getInitialPlayerCount() - 1) * 10;
        return g.vampire()
                .filter(Player::isAlive)
                .map(v -> v.getHp() * 5 <= maxHp)
                .orElse(false);
    }

    /** Un vampire ou serviteur VIVANT est-il sur le lieu du bot ? (≠ hasEnemyOnMyLocation, qui inclut les monstres). */
    private boolean vampSideOnMyLocation(Game g, Player bot) {
        String loc = g.locationOf(bot.getId());
        if (loc == null)
            return false;
        return g.getPlayers().stream()
                .filter(Player::isAlive)
                .filter(p -> loc.equals(g.locationOf(p.getId())))
                .anyMatch(p -> "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()));
    }

    private String firstPendingUnstable(Game g) {
        var targets = g.getUnstableEligibleTargets();
        if (targets != null && !targets.isEmpty())
            return targets.keySet().iterator().next();
        var locations = g.getUnstableEligibleLocations();
        if (locations != null && !locations.isEmpty())
            return locations.keySet().iterator().next();
        return null;
    }

    private Game.LocationEffectInstance currentEffectInstance(Game g) {
        var queue = g.getLocationEffectsQueue();
        Integer idx = g.getCurrentLocationEffectIndex();
        if (queue == null || idx == null || idx < 0 || idx >= queue.size())
            return null;
        return queue.get(idx);
    }

    /**
     * Choix d'effet « sûr » par (infra, rôle) : uniquement des effets immédiats
     * qui log-et-continuent quand les ressources manquent — sauf HEAL/CORRUPT
     * (autel) et FORGE, dont l'étape interactive est gérée par
     * {@link #resolvePendingEffect}.
     */
    private LocationEffectChoice naiveEffectChoice(Game g, Player bot, Infra infra) {
        boolean vamp = "VAMPIRE".equals(bot.getRole());
        boolean hunter = "HUNTER".equals(bot.getRole());

        return switch (infra) {
            case LIBRARY -> LocationEffectChoice.STUDY;
            case LABORATORY -> {
                // Vampire : Expérimentation pour poster un monstre gardien si un
                // bâtiment doit être (re)défendu et qu'on peut payer ; sinon Alchimie.
                if (vamp && pickDefenseLocation(g) != null && bot.getSouls() >= EXPERIMENT_COST)
                    yield LocationEffectChoice.EXPERIMENT;
                yield LocationEffectChoice.ALCHEMY;
            }
            case BALLROOM -> hunter ? LocationEffectChoice.LOOTING : LocationEffectChoice.SNEAK_ATTACK;
            case ALTAR -> {
                boolean corrupted = Boolean.TRUE.equals(g.getAltarCorrupted());
                // NB : un SERVANT sur l'autel n'a aucune option valide côté serveur ;
                // on tente le choix chasseur et l'orchestrateur avalera le refus.
                if (corrupted)
                    yield vamp ? LocationEffectChoice.CORRUPT : LocationEffectChoice.PURIFY_WATER;
                yield vamp ? LocationEffectChoice.CORRUPT_SOULS : LocationEffectChoice.HEAL;
            }
            case FORGE -> LocationEffectChoice.FORGE;
            default -> null;
        };
    }

    /** Étape interactive d'un effet déjà choisi par ce bot. */
    private boolean resolvePendingEffect(Game g, Player bot, Game.LocationEffectInstance inst,
            long stableMs) {
        String botId = bot.getId();
        LocationEffectChoice choice = inst.choice;
        if (choice == null)
            return false;

        switch (choice) {
            case CORRUPT -> {
                // Rush serviteur : concentrer la corruption sur le chasseur le PLUS
                // corrompu (< 3) pour le convertir au plus vite — un serviteur de
                // plus fait pencher la balance. Départage : PV les plus bas.
                String target = pickCorruptionRushTarget(g);
                if (target == null)
                    return false;
                games.resolveAltarCorrupt(g.getId(), botId, target);
                return true;
            }
            case HEAL -> {
                String target = (bot.getCorruption() > 0)
                        ? botId
                        : firstHunter(g, p -> p.getCorruption() > 0);
                if (target == null)
                    return false;
                games.resolveAltarHeal(g.getId(), botId, target);
                return true;
            }
            case FORGE -> {
                var options = equipment.listForgeOptionsForPlayer(g, bot);
                if (options.isEmpty())
                    return false;
                String forgeChoice = "HUNTER".equals(bot.getRole())
                        ? pickHunterForgeOption(g, bot, options) // équilibrage vs le vampire
                        : pickBestForgeOption(options); // vampire : son meilleur tier
                games.resolveForge(g.getId(), botId, forgeChoice);
                return true;
            }
            case EXPLOSION -> {
                games.resolveLaboratoryExplosion(g.getId(), botId);
                return true;
            }
            case EXPERIMENT -> {
                // Garde-fou : ne pas retenter si on ne peut plus payer (fenêtre
                // post-résolution) — on renvoie false et on laisse le flux avancer.
                if (bot.getSouls() < EXPERIMENT_COST)
                    return false;
                // Cible : le bâtiment le plus vulnérable (Labo prioritaire, puis
                // Scierie/Mine).
                String loc = pickDefenseLocation(g);
                if (loc == null)
                    return false;
                // Étape 1 : AFFICHER la sélection (monstre + lieu) dans la modale —
                // comme un humain qui choisit avant de valider (visible spectateurs).
                if (g.getLaboratoryDraftLocation() == null) {
                    games.updateLaboratoryExperimentDraft(g.getId(), botId,
                            pickExperimentMonster(g, bot, loc), loc);
                    return true;
                }
                // Étape 2 : laisser la sélection s'afficher (MODAL_DISPLAY — le
                // draft a réinitialisé la stabilité), puis appliquer (avec le type
                // affiché dans le draft, pour rester cohérent avec la modale).
                if (stableMs < MODAL_DISPLAY)
                    return false;
                Game.MonsterType type = g.getLaboratoryDraftMonsterType() != null
                        ? g.getLaboratoryDraftMonsterType()
                        : Game.MonsterType.BAT;
                games.resolveLaboratoryExperiment(g.getId(), botId,
                        type, g.getLaboratoryDraftLocation());
                return true;
            }
            // THEFT / OMEN : jamais choisis par le cerveau naïf
            default -> {
                return false;
            }
        }
    }

    private String firstHunter(Game g, java.util.function.Predicate<Player> extra) {
        return g.getPlayers().stream()
                .filter(p -> "HUNTER".equals(p.getRole()))
                .filter(Player::isAlive)
                .filter(extra)
                .map(Player::getId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Meilleure option de forge : le tier le plus HAUT disponible (T3 &gt; T2 &gt;
     * T1), en conservant l'ordre d'origine (armes avant armures) pour départager.
     * Utilisé par le VAMPIRE (il forge simplement son meilleur équipement).
     */
    private String pickBestForgeOption(List<String> options) {
        for (String tag : new String[] { "_T3_", "_T2_", "_T1_" }) {
            for (String code : options) {
                if (code.contains(tag))
                    return code;
            }
        }
        return options.get(0);
    }

    /**
     * Objet à forger pour un CHASSEUR : équilibrage atk/def vs le vampire. S'il
     * frappe fort (son arme &gt; son armure) je privilégie mon ARMURE (survivre à
     * ses coups) ; s'il est tanky (armure &gt; arme) je privilégie mon ARME (percer
     * sa défense). À égalité, je rattrape MON slot en retard (léger biais défensif
     * face à un mordeur). Dans le slot retenu je prends le tier le plus haut (donc
     * le T3 en priorité s'il y est) ; si ce slot n'a rien de forgeable, je bascule
     * sur l'autre.
     */
    private String pickHunterForgeOption(Game g, Player bot, List<String> options) {
        int vAtk = g.vampire().map(v -> equipment.vampireWeaponTier(v.getWeapon())).orElse(0);
        int vDef = g.vampire().map(v -> equipment.vampireArmorTier(v.getArmor())).orElse(0);
        boolean preferArmor;
        if (vAtk != vDef) {
            preferArmor = vAtk > vDef; // vampire ATK-lourd → je monte ma DEF
        } else {
            int myAtk = equipment.hunterWeaponTier(bot.getWeapon());
            int myDef = equipment.hunterArmorTier(bot.getArmor());
            preferArmor = myDef <= myAtk; // ma def en retard (ou à égalité) → armure
        }
        String armor = bestForgeOfSlot(options, false);
        String weapon = bestForgeOfSlot(options, true);
        if (preferArmor)
            return armor != null ? armor : weapon;
        return weapon != null ? weapon : armor;
    }

    /** Meilleure option (tier le plus haut) d'un slot donné parmi les options de forge. */
    private String bestForgeOfSlot(List<String> options, boolean weapon) {
        String best = null;
        int bestTier = -1;
        for (String code : options) {
            boolean isWeapon = code.contains("_WEAPON_");
            boolean isArmor = code.contains("_ARMOR_");
            if (weapon ? !isWeapon : !isArmor)
                continue;
            int t = forgeTierOf(code);
            if (t > bestTier) {
                bestTier = t;
                best = code;
            }
        }
        return best;
    }

    /** Tier (1/2/3) d'un code d'équipement, 0 si indéterminé. */
    private int forgeTierOf(String code) {
        if (code.contains("_T3_"))
            return 3;
        if (code.contains("_T2_"))
            return 2;
        if (code.contains("_T1_"))
            return 1;
        return 0;
    }

    /**
     * Cible du rush corruption (Autel corrompu) : le chasseur vivant le plus
     * proche du niveau 3 (corruption la plus haute, &lt; 3), départage par les PV
     * les plus bas — celui qu'on peut convertir le plus vite en serviteur.
     */
    private String pickCorruptionRushTarget(Game g) {
        return g.getPlayers().stream()
                .filter(p -> "HUNTER".equals(p.getRole()))
                .filter(Player::isAlive)
                .filter(p -> p.getCorruption() < 3)
                .sorted(java.util.Comparator
                        .comparingInt(Player::getCorruption).reversed()
                        .thenComparingInt(Player::getHp))
                .map(Player::getId)
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // PHASE3 — jets de combat, morsures, pièges subis, pompage du pipeline
    // ------------------------------------------------------------------
    private boolean playPhase3(Game g, Player bot, long stableMs, boolean humans) {
        String botId = bot.getId();
        long nextDelay = humans ? COMBAT_NEXT_HUMANS : COMBAT_NEXT_ALONE;

        // 1) Morsure en cours : d20 du vampire, puis éventuel d4 de la cible
        //    (armure de plates en argent). Le Chapelet, lui, est automatique.
        Game.BiteAttempt bite = g.getCurrentBite();
        if (bite != null) {
            if (bite.getResolvedAtMillis() == null) {
                if (bite.getRoll() == null) {
                    // Le d20 de morsure attend BITE_ROLL (2 s) : la modale doit
                    // rester visible avant le jet, comme avec un vampire humain.
                    if (botId.equals(bite.getAttackerId()) && stableMs >= BITE_ROLL) {
                        games.rollCorruption(g.getId(), botId);
                        return true;
                    }
                    return false;
                }
                if (botId.equals(bite.getTargetId()) && bite.getArmorRoll() == null
                        && stableMs >= COMBAT_ROLL) {
                    games.rollCorruption(g.getId(), botId);
                    return true;
                }
                return false;
            }
            // Morsure close : résultat affiché 2,5 s (réglage game designer),
            // puis combatContinue nettoie et enchaîne.
            if (stableMs < BITE_NEXT)
                return false;
            games.combatContinue(g.getId(), botId);
            return true;
        }

        // 2) Action de raid en cours (Filet/Fosse/Incendiaire/Épieu posés en
        //    préphase). Le bot peut en être VICTIME (Fosse = jet d20 de la
        //    victime) ou ACTEUR (Épieu béni = jet du pieu, déclenché après son duel).
        Game.Action action = g.getCurrentAction();
        if (action != null && action.getResolvedAtMillis() == null) {
            if ("PIT".equals(action.getMode())
                    && botId.equals(action.getTargetId())
                    && action.getRoll() == null) {
                if (stableMs < TRAP_DODGE)
                    return false;
                games.resolvePit(g.getId(), botId);
                return true;
            }
            // Épieu béni : je suis l'attaquant qui portait le pieu, je lance le dé.
            if ("BLESSED_STAKE".equals(action.getMode())
                    && botId.equals(action.getOwnerId())
                    && action.getRoll() == null) {
                if (stableMs < COMBAT_ROLL)
                    return false;
                games.resolveBlessedStake(g.getId(), botId);
                return true;
            }
            // Filet : je suis le poseur → d'abord choisir la cible (visible dans
            // la modale de tous, targetId entre dans l'empreinte de stabilité),
            // puis laisser la sélection s'afficher (MODAL_DISPLAY) avant le jet.
            if ("NET".equals(action.getMode())
                    && botId.equals(action.getOwnerId())
                    && action.getRoll() == null) {
                if (action.getTargetId() == null) {
                    if (stableMs < MODAL_CHOICE)
                        return false;
                    String target = pickNetTargetId(g, bot);
                    if (target == null)
                        return false; // plus de cible sur mon lieu : le pipeline nettoiera
                    games.chooseNetTarget(g.getId(), botId, target);
                    return true;
                }
                if (stableMs < MODAL_DISPLAY)
                    return false;
                games.resolveNet(g.getId(), botId, action.getTargetId());
                return true;
            }
            if (action.getRoll() != null) {
                // Jet fait : temps de lecture, puis combatContinue clôture
                if (stableMs < nextDelay)
                    return false;
                games.combatContinue(g.getId(), botId);
                return true;
            }
            return false; // action d'un humain, on attend
        }

        // 3) Duel en cours : poser mon jet, ou enchaîner
        var fight = g.getCurrentCombat();
        if (fight != null) {
            if (fight.getResolvedAtMillis() == null) {
                if (botId.equals(fight.getAttackerId()) && fight.getAttackerRoll() == null) {
                    if (stableMs < COMBAT_ROLL)
                        return false;
                    games.rollDice(g.getId(), botId);
                    return true;
                }
                if (botId.equals(fight.getDefenderId()) && fight.getDefenderRoll() == null) {
                    if (stableMs < COMBAT_ROLL)
                        return false;
                    games.rollDice(g.getId(), botId);
                    return true;
                }
                if (fight.getAttackerRoll() != null && fight.getDefenderRoll() != null) {
                    if (stableMs < nextDelay)
                        return false;
                    games.combatContinue(g.getId(), botId);
                    return true;
                }
                return false; // on attend le jet d'un humain
            }
            // Duel résolu : temps de lecture des résultats, puis combatContinue
            // ouvre le duel suivant (la riposte, par ex.) ou vide le pointeur.
            if (stableMs < nextDelay)
                return false;
            games.combatContinue(g.getId(), botId);
            return true;
        }

        // 4) Rien en cours (pas de morsure, pas d'action, pas de duel ouvert) :
        //    la file est finie ou vide → avancer en PHASE4, comme le fait le
        //    front humain (l'auto-PHASE4 de combatContinue ne se déclenche pas
        //    après de vrais combats : il relit la file, qui est une copie
        //    jamais mise à jour des jets — ils vivent sur currentCombat).
        //    Avec des humains, leur front avance à 5 s : le bot n'est que le
        //    filet de sécurité.
        if (stableMs < (humans ? ADVANCE_P4_HUMANS : ADVANCE_P4_ALONE))
            return false;
        games.advancePhase(g.getId(), botId, Phase.PHASE4);
        return true;
    }

    // ------------------------------------------------------------------
    // PHASE4 — économie : le bot fait ses achats/dépôts (une action par tick)
    // AVANT de se déclarer prêt. finishTrade annule au passage les échanges
    // qui le visent et marque le bot prêt pour le raid suivant.
    // ------------------------------------------------------------------
    private boolean playPhase4(Game g, Player bot, long stableMs) {
        if (!bot.isAlive())
            return false;
        String botId = bot.getId();
        var ready = g.getReadyForNextRaid();
        if (ready != null && ready.contains(botId))
            return false;

        // 1) Actions économiques (une par tick), espacées pour rester lisibles
        if (stableMs >= SHOP_ACTION) {
            if ("HUNTER".equals(bot.getRole())) {
                if (tryHunterEconomy(g, bot))
                    return true;
            } else if ("VAMPIRE".equals(bot.getRole()) || "SERVANT".equals(bot.getRole())) {
                if (tryVampEconomy(g, bot))
                    return true;
            }
        }

        // 2) Plus rien à faire → finir (ne pas faire attendre les humains)
        if (stableMs < FINISH_PHASE4)
            return false;
        games.finishTrade(g.getId(), botId);
        return true;
    }

    /**
     * Boutique du chasseur Prudent : équipement d'abord (gros gain ponctuel),
     * puis Eau bénite anti-corruption si menace, banque (revenu d'équipe),
     * potion de réserve, et enfin une carte d'action avec le SURPLUS d'or.
     * Renvoie true dès qu'un achat est lancé (le serveur re-valide coûts et
     * ressources).
     */
    private boolean tryHunterEconomy(Game g, Player bot) {
        String botId = bot.getId();

        // a-1) Charismatique : réduction des prix en or (−20/achat) AVANT les
        //      emplettes du raid — si j'ai de quoi acheter (≥ 150 or) et pas
        //      encore joué ce raid. Bloqué par Présence écrasante (serveur).
        if (hasAction(bot, Action.CHARISMATIQUE) && !bot.isCharismaticThisRaid()
                && !g.isHunterActionsBlockedThisRaid() && bot.getGold() >= 150) {
            games.useAction(g.getId(), botId, Action.CHARISMATIQUE);
            return true;
        }

        // a0) Marchand itinérant (étape 3d passe 2) : source d'ÉLIXIRS du
        //     chasseur (+ potion/équipement). Résout d'abord une offre en cours,
        //     sinon joue la carte avec le SURPLUS d'or.
        if (tryMerchantItinerant(g, bot))
            return true;

        // a) Amélioration d'arme (D4→D6→D8) : 2/2 puis 3/3 bois/fer
        int wTier = equipment.hunterWeaponTier(bot.getWeapon());
        if (wTier < 2) {
            int cost = (wTier == 0) ? 2 : 3;
            if (bot.getWood() >= cost && bot.getIron() >= cost) {
                games.buyUpgradeWeapon(g.getId(), botId, Math.min(2, wTier + 1), pickWeaponType());
                return true;
            }
        }

        // b) Amélioration d'armure : mêmes coûts
        int aTier = equipment.hunterArmorTier(bot.getArmor());
        if (aTier < 2) {
            int cost = (aTier == 0) ? 2 : 3;
            if (bot.getWood() >= cost && bot.getIron() >= cost) {
                games.buyUpgradeArmor(g.getId(), botId);
                return true;
            }
        }

        // b2) Eau bénite (150 or + 4 eau) : anti-corruption. On en achète une dès
        //     que le vampire dispose d'un moteur de corruption (Autel corrompu ou
        //     Marque en jeu) ou que je suis déjà entamé, si je n'en ai pas déjà.
        //     C'est la carte de survie clé de l'étape 3b (jouée en PREPHASE3).
        if (!hasAction(bot, Action.EAU_BENITE) && vampireHasCorruptionEngine(g, bot)
                && bot.getGold() >= 150 && bot.getWater() >= 4) {
            games.buyHolyWaterAction(g.getId(), botId);
            return true;
        }

        // b3) Pisteur (100 or) : la traque PRÉCISE (feedback game designer — les
        //     chasseurs sous-utilisaient le pistage). Acheté quand je suis apte à
        //     la chasse, en gardant la réserve d'Eau bénite. Joué en PHASE1.
        if (!hasAction(bot, Action.PISTEUR) && fitForHunt(bot)
                && g.vampire().map(Player::isAlive).orElse(false)
                && bot.getGold() >= 100 + HUNTER_GOLD_RESERVE) {
            games.buyTrackingAction(g.getId(), botId);
            return true;
        }

        // c) Banque : déposer 1 pierre (le Prudent alimente le revenu d'équipe)
        int bankLevel = (g.getBankLevel() == null) ? 0 : g.getBankLevel();
        if (bankLevel < 3 && bot.getStone() > 0) {
            games.contributeBankStone(g.getId(), botId);
            return true;
        }

        // d) Potion de réserve : garde une marge d'eau/herbes
        if (bot.getWater() >= 6 && bot.getHerbs() >= 5 && potionCount(bot) < 2) {
            games.buyPotion(g.getId(), botId);
            return true;
        }

        // e) Carte d'action (aléatoire) avec le SURPLUS d'or : l'or ne sert
        //    quasiment qu'à ça côté chasseur. On garde une réserve pour une
        //    éventuelle Eau bénite, puis on pioche 1 carte/raid pour se
        //    constituer une main (Chapelet, Épieu, Filet…). Coût (n+1)×50 or.
        int boughtThisRaid = (g.getActionCardsBoughtThisRaid() != null)
                ? g.getActionCardsBoughtThisRaid().getOrDefault(botId, 0)
                : 0;
        int cardCost = (boughtThisRaid + 1) * 50;
        if (boughtThisRaid < 1 && bot.getGold() >= cardCost + HUNTER_GOLD_RESERVE) {
            games.buyAction(g.getId(), botId);
            return true;
        }
        return false;
    }

    /** L'or que le chasseur garde en réserve (pour une Eau bénite au besoin)
     *  avant de dépenser le surplus en cartes d'action aléatoires. */
    private static final int HUNTER_GOLD_RESERVE = 150;

    /** Le vampire dispose-t-il d'un moteur de corruption menaçant (Autel corrompu
     *  ou Marque ténébreuse en jeu), ou suis-je déjà entamé ? → justifie une Eau bénite. */
    private boolean vampireHasCorruptionEngine(Game g, Player bot) {
        boolean altar = Boolean.TRUE.equals(g.getAltarCorrupted());
        boolean marks = g.getDarkMarkedHunters() != null && !g.getDarkMarkedHunters().isEmpty();
        return altar || marks || bot.getCorruption() >= 1;
    }

    private boolean hasAction(Player bot, Action type) {
        var acts = bot.getActions();
        return acts != null && acts.contains(type.name());
    }

    /**
     * PHASE4 du vampire Bâtisseur : rééquilibre bois/fer par transmutation
     * pour préparer les prochaines constructions (les âmes viennent du Manoir,
     * on ne les fabrique pas ici). Un seul type d'action par tick.
     */
    private boolean tryVampEconomy(Game g, Player bot) {
        String botId = bot.getId();

        // a0) Avidité nocturne (étape 3c) : +50 sur tous les prix en or de la
        //     boutique ce raid. À jouer TÔT dans la PHASE4 (avant les achats des
        //     chasseurs) et seulement quand ils sont riches — l'or est public.
        var vampActs = bot.getActions();
        if (vampActs != null && vampActs.contains(Action.AVIDITE_NOCTURNE.name())
                && !g.isShopPricesIncreasedThisRaid()) {
            var richHunters = g.getPlayers().stream()
                    .filter(Player::isAlive)
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .mapToInt(Player::getGold)
                    .average().orElse(0);
            if (richHunters >= 150) {
                games.useAction(g.getId(), botId, Action.AVIDITE_NOCTURNE);
                return true;
            }
        }

        // a1) Transmutation avancée (feedback game designer : le vampire doit se
        //     stuffer « dès qu'il peut ») : carte → d6 → acheter l'offre
        //     (équipement vampirique, élixir ou potion) en ressources d'abord,
        //     en âmes sinon (au-dessus de la réserve de défense).
        if (tryAdvancedTransmutation(g, bot))
            return true;

        // a) Acheter des cartes Action avec le SURPLUS d'âmes : on RÉSERVE d'abord
        //    de quoi Expérimenter-défendre les bâtiments VISÉS par le Bâtisseur
        //    (Labo + Scierie + Mine = 3 → 100 âmes chacun = 300 au max), moins ceux
        //    qu'un Portail en main peut couvrir sans âmes ; puis on achète au-dessus.
        //    IMPORTANT : la réserve se base sur le PLAN (3 bâtiments), pas sur les
        //    bâtiments DÉJÀ construits — sinon en début de partie (0-1 bâtiment bâti
        //    → réserve ~0) le bot dilapide ses âmes en cartes, et comme les raids de
        //    construction ne récoltent aucune âme, il ne peut plus payer
        //    l'Expérimentation quand les 3 bâtiments existent enfin (bug du log R5 :
        //    ~60 âmes < 100 → construit la Bibliothèque au lieu de défendre la Mine).
        //    Ainsi un gros stock (début vs 6 chasseurs ≈ 600 âmes) achète tout en
        //    gardant la défense finançable ; peu d'âmes (vs 3 chasseurs ≈ 300) → tout
        //    pour la défense. Coût croissant (n+1)×50, 2 achats/raid max.
        int soulReserve = Math.max(0, DEFENDABLE_PLAN - portalCardCount(bot)) * EXPERIMENT_COST;
        int bought = (g.getActionCardsBoughtThisRaid() != null)
                ? g.getActionCardsBoughtThisRaid().getOrDefault(botId, 0)
                : 0;
        int cardCost = (bought + 1) * 50;
        if (bought < 2 && bot.getSouls() >= cardCost + soulReserve) {
            games.buyAction(g.getId(), botId);
            return true;
        }

        // b) Rééquilibrage bois/fer par transmutation (prépare les constructions),
        //    en gardant une marge (on ne transmute que si l'écart est net).
        if (bot.getWater() >= 1) {
            if (bot.getWood() - bot.getIron() >= 4) {
                games.transmute(g.getId(), botId, "WOOD_TO_IRON");
                return true;
            }
            if (bot.getIron() - bot.getWood() >= 4) {
                games.transmute(g.getId(), botId, "IRON_TO_WOOD");
                return true;
            }
        }
        return false;
    }

    private String pickWeaponType() {
        return switch (dice.nextInt(3)) {
            case 0 -> "BLEED";
            case 1 -> "RANGE";
            default -> "STUN";
        };
    }

    private int potionCount(Player bot) {
        return bot.getPotions() == null ? 0 : bot.getPotions().size();
    }
}
