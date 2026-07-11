package org.castello.bot;

import org.castello.game.Game;
import org.castello.game.GameService;
import org.castello.game.Infra;
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

    // -- 1) Temps de réflexion (libres)
    private static final long ROLL_WEATHER = 1_000; // modale « dé non lancé » visible
    private static final long SELECT_LOCATION = 500;
    private static final long PREPHASE_ACTION = 3_000; // instables / effets de lieu
    private static final long POTION_USE = 1_500; // laisser voir l'anim d'une potion
    private static final long SKIP_READY = 1_000;
    private static final long COMBAT_ROLL = 500;
    private static final long SHOP_ACTION = 800; // espacement des achats en PHASE4
    private static final long FINISH_PHASE4 = 200;

    // -- 2) Progressions automatiques (contrainte : *_HUMANS > front)
    private static final long ADVANCE_P1_HUMANS = 15_000; // l'anim météo du front avance elle-même
    private static final long ADVANCE_P1_ALONE = 2_000;
    private static final long COMBAT_NEXT_HUMANS = FRONT_DISPLAY_MS + 1_000;
    private static final long COMBAT_NEXT_ALONE = 2_500; // temps de lecture spectateur
    private static final long ADVANCE_P4_HUMANS = FRONT_DISPLAY_MS + 1_000;
    private static final long ADVANCE_P4_ALONE = 2_500;

    // --- Cibles de ressources pour le scoring de récolte / achats (chasseur).
    //     « De combien ai-je besoin avant d'être à l'aise ? »
    private static final int TARGET_WOOD = 6; // ~2 upgrades d'équipement
    private static final int TARGET_IRON = 6;
    private static final int TARGET_STONE = 12; // banque
    private static final int TARGET_WATER = 8; // potions
    private static final int TARGET_HERBS = 8;

    private final GameService games;
    private final EquipmentService equipment; // lecture seule : tiers / options de forge
    private final ConstructionService construction; // lecture seule : coût d'une infra
    private final Dice dice;

    public BotBrain(GameService games, EquipmentService equipment,
            ConstructionService construction, Dice dice) {
        this.games = games;
        this.equipment = equipment;
        this.construction = construction;
        this.dice = dice;
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
        if (g.hasPlayed(bot.getId()))
            return false;
        if (stableMs < SELECT_LOCATION)
            return false;

        String card = pickHunterLocation(bot);
        if (card == null)
            return false;
        games.selectLocation(g.getId(), bot.getId(), card);
        return true;
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
        if (g.hasPlayed(bot.getId()))
            return false;
        if (stableMs < SELECT_LOCATION)
            return false;

        // Construction (Bâtisseur) : planConstruction joue le lieu du chantier
        if (isVamp) {
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
     * Choix de lieu du chasseur : score chaque carte de la main selon le
     * déficit de ressources visé (récoltes §9 de REGLES.md). Le Manoir
     * (or) garde un attrait modéré ; un petit bruit évite la prévisibilité.
     */
    private String pickHunterLocation(Player bot) {
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
                case "lake" -> waterNeed + herbsNeed;
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
     * Vampire / serviteur qui ne construit pas : privilégie le Manoir (âmes),
     * puis les bâtiments du Manoir, sinon un lieu jouable au hasard. Exclut
     * les lieux fumigés (interdits au camp du vampire).
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

        // Manoir et ses bâtiments = récolte d'âmes (carburant du Bâtisseur)
        for (String c : List.of("manor", "laboratory", "altar", "ballroom", "library", "forge")) {
            if (opts.contains(c))
                return c;
        }
        return opts.get(dice.nextInt(opts.size()));
    }

    /**
     * Prochaine infra à bâtir selon l'ordre du profil, si toutes les
     * conditions serveur sont réunies (pas de cyclone, carte lieu en main
     * et non fumigée, ressources suffisantes). Sinon null → pas de
     * construction ce raid.
     */
    private Infra chooseConstruction(Game g, Player bot) {
        // Cyclone (WIND) : construction interdite ce raid
        if (g.getWeatherStatus() == WeatherStatus.WIND
                || g.getSecondaryWeatherStatus() == WeatherStatus.WIND)
            return null;
        if (g.getPendingConstruction() != null)
            return null; // déjà planifiée (sécurité)

        var built = g.getBuiltInfras();
        List<String> hand = bot.getHand();
        var garlic = g.getGarlicBlockedLocations();

        for (Infra infra : BotProfile.forRole(bot.getRole()).constructionOrder()) {
            if (built != null && built.contains(infra))
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
                if (stableMs < PREPHASE_ACTION)
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
                if (stableMs < PREPHASE_ACTION)
                    return false;
                if (inst.choice == null) {
                    LocationEffectChoice choice = naiveEffectChoice(g, bot, inst.infra);
                    if (choice == null)
                        return false;
                    games.chooseLocationEffect(g.getId(), botId, choice);
                    return true;
                }
                return resolvePendingEffect(g, bot, inst);
            }
            // Effet d'un autre joueur : on attend.
            return false;
        }

        // 3) Potions basiques avant le combat imminent (une par tick).
        if (bot.isAlive() && stableMs >= POTION_USE && maybeUsePotion(g, bot))
            return true;

        // 4) Se déclarer prêt (accélère la préphase ; le timer 30 s couvre le reste)
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
     * Potion basique avant un combat imminent auquel le bot participe :
     * Vie si PV bas, sinon Force / Endurance (une seule de chaque par raid).
     * Le serveur re-valide la participation ; ici on approxime avec « un
     * ennemi est sur mon lieu » pour éviter les tentatives inutiles.
     */
    private boolean maybeUsePotion(Game g, Player bot) {
        if (!g.isHasUpcomingCombat())
            return false;
        List<String> potions = bot.getPotions();
        if (potions == null || potions.isEmpty())
            return false;
        // Gelées sous blizzard
        if (g.getWeatherStatus() == WeatherStatus.BLIZZARD
                || g.getSecondaryWeatherStatus() == WeatherStatus.BLIZZARD)
            return false;
        if (!hasEnemyOnMyLocation(g, bot))
            return false;

        String botId = bot.getId();

        // 1) Vie si PV bas (auto-limitant : les PV remontent).
        //    Miroir de CombatService.maxHpFor (vampire = 20 + 10 * nb chasseurs).
        int maxHp = "VAMPIRE".equals(bot.getRole())
                ? 20 + Math.max(0, g.getInitialPlayerCount() - 1) * 10
                : 20;
        if (bot.getHp() * 2 < maxHp && potions.contains(Potion.VIE.name())) {
            games.usePotion(g.getId(), botId, Potion.VIE);
            return true;
        }
        // 2) Force (si pas déjà boosté ce raid)
        if (potions.contains(Potion.FORCE.name()) && !hasPotionMod(g, botId, "POTION:FORCE")) {
            games.usePotion(g.getId(), botId, Potion.FORCE);
            return true;
        }
        // 3) Endurance (si pas déjà boosté ce raid)
        if (potions.contains(Potion.ENDURANCE.name()) && !hasPotionMod(g, botId, "POTION:ENDURANCE")) {
            games.usePotion(g.getId(), botId, Potion.ENDURANCE);
            return true;
        }
        return false;
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
            case LABORATORY -> LocationEffectChoice.ALCHEMY;
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
    private boolean resolvePendingEffect(Game g, Player bot, Game.LocationEffectInstance inst) {
        String botId = bot.getId();
        LocationEffectChoice choice = inst.choice;
        if (choice == null)
            return false;

        switch (choice) {
            case CORRUPT -> {
                String target = firstHunter(g, p -> p.getCorruption() < 3);
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
                games.resolveForge(g.getId(), botId, options.get(0));
                return true;
            }
            case EXPLOSION -> {
                games.resolveLaboratoryExplosion(g.getId(), botId);
                return true;
            }
            // THEFT / OMEN / EXPERIMENT : jamais choisis par le cerveau naïf
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
                    if (botId.equals(bite.getAttackerId()) && stableMs >= COMBAT_ROLL) {
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
            // Morsure close : temps de lecture, puis combatContinue nettoie
            // et enchaîne (le front des humains le fait à 5 s — il a priorité)
            if (stableMs < nextDelay)
                return false;
            games.combatContinue(g.getId(), botId);
            return true;
        }

        // 2) Action de raid en cours (Filet/Fosse/Incendiaire/Épieu posés en
        //    préphase). Le bot naïf n'en joue pas, mais peut en être VICTIME :
        //    Fosse = jet d20 de la victime.
        Game.Action action = g.getCurrentAction();
        if (action != null && action.getResolvedAtMillis() == null) {
            if ("PIT".equals(action.getMode())
                    && botId.equals(action.getTargetId())
                    && action.getRoll() == null) {
                if (stableMs < COMBAT_ROLL)
                    return false;
                games.resolvePit(g.getId(), botId);
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
     * puis banque (revenu d'équipe), puis potion de réserve. Renvoie true dès
     * qu'un achat est lancé (le serveur re-valide coûts et ressources).
     */
    private boolean tryHunterEconomy(Game g, Player bot) {
        String botId = bot.getId();

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
        return false;
    }

    /**
     * PHASE4 du vampire Bâtisseur : rééquilibre bois/fer par transmutation
     * pour préparer les prochaines constructions (les âmes viennent du Manoir,
     * on ne les fabrique pas ici). Un seul type d'action par tick.
     */
    private boolean tryVampEconomy(Game g, Player bot) {
        String botId = bot.getId();
        if (bot.getWater() < 1)
            return false;

        // Rééquilibrage : convertir l'excédent vers la ressource manquante,
        // en gardant une marge (on ne transmute que si l'écart est net).
        if (bot.getWood() - bot.getIron() >= 4) {
            games.transmute(g.getId(), botId, "WOOD_TO_IRON");
            return true;
        }
        if (bot.getIron() - bot.getWood() >= 4) {
            games.transmute(g.getId(), botId, "IRON_TO_WOOD");
            return true;
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
