package org.castello.game;

import org.castello.game.support.Dice;
import org.castello.game.support.GameStore;
import org.castello.player.Player;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.castello.web.dto.GameSnapshot;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.castello.web.dto.EndedGameSummary;

import org.castello.persistence.GameRepository;
import org.castello.persistence.PlayerRepository;
import org.castello.player.PlayerService;

import java.util.*;

@Service
public class GameService {

    private final TaskScheduler raidScheduler;
    private final TransactionTemplate tx;

    // ----- PERSISTENCE -----
    private final GameStore store;
    private final Dice dice;
    private final GameRepository gameRepo;
    private final PlayerRepository playerRepo;
    private final org.castello.live.LiveEvents live;
    private final PlayerService playerService;

    public GameService(GameStore store, Dice dice, GameRepository gameRepo, PlayerRepository playerRepo,
            @Qualifier("raidTaskScheduler") TaskScheduler raidScheduler, PlatformTransactionManager tm,
            org.castello.live.LiveEvents live, PlayerService playerService) {
        this.store = store;
        this.dice = dice;
        this.gameRepo = gameRepo;
        this.playerRepo = playerRepo;
        this.raidScheduler = raidScheduler;
        this.tx = new TransactionTemplate(tm);
        this.live = live;
        this.playerService = playerService;
    }

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    // Plomberie déléguée à GameStore (voir game/support/GameStore.java).
    private Game findOr404(String id) {
        return store.read(id);
    }

    private Game findOr404ForUpdate(String id) {
        return store.loadForUpdate(id);
    }

    private void afterCommit(Runnable r) {
        store.afterCommit(r);
    }

    private static Map<String, Integer> copyMap(Map<String, Integer> m) {
        return (m == null) ? new java.util.HashMap<>() : new java.util.HashMap<>(m);
    }

    private java.util.List<String> safeList(java.util.List<String> xs) {
        return (xs != null) ? new java.util.ArrayList<>(xs) : java.util.List.of();
    }

    private int deckSize(List<String> deck) {
        return (deck != null ? deck.size() : 0);
    }

    /**
     * Taille "effective" du deck : ce qu’on considère comme
     * encore piochable en tenant compte de la défausse.
     *
     * - Si deck non vide -> on renvoie deck.size()
     * - Sinon -> on renvoie discard.size()
     */
    private int deckAvailableSize(List<String> deck, List<String> discard) {
        int deckSize = (deck != null ? deck.size() : 0);
        if (deckSize > 0)
            return deckSize;

        return (discard != null ? discard.size() : 0);
    }

    private int discardSize(List<String> discard) {
        return (discard != null ? discard.size() : 0);
    }

    public GameSnapshot viewSnapshot(String gameId, String userId) {
        Game g = findOr404(gameId);

        boolean isVamp = g.getPlayers().stream()
                .anyMatch(p -> userId.equals(p.getId()) && "VAMPIRE".equals(p.getRole()));

        // Wrappers null-safe (raccourcis locaux)
        List<Player> playersSrc = (g.getPlayers() != null) ? g.getPlayers() : java.util.Collections.emptyList();
        List<CenterBoard> centerSrc = (g.getCenter() != null) ? g.getCenter() : java.util.Collections.emptyList();
        Map<String, List<StatMod>> raidModsSrc = (g.getRaidMods() != null) ? g.getRaidMods()
                : java.util.Collections.emptyMap();
        java.util.Set<String> readySet = (g.getReadyForPhase3() != null) ? g.getReadyForPhase3()
                : java.util.Collections.emptySet();
        List<String> readyForNextRaid = new java.util.ArrayList<>(g.getReadyForNextRaid());
        List<Game.HistoryItem> historySrc = (g.getHistory() != null) ? g.getHistory()
                : java.util.Collections.emptyList();
        List<RoundFight> combatsQueueSrc = (g.getCombatsQueue() != null) ? g.getCombatsQueue()
                : java.util.Collections.emptyList();
        List<String> messagesSrc = (g.getMessages() != null) ? g.getMessages() : java.util.Collections.emptyList();

        Map<String, List<String>> uTargets = g.getUnstableEligibleTargets() != null
                ? new java.util.HashMap<>(g.getUnstableEligibleTargets())
                : java.util.Collections.emptyMap();

        Map<String, List<String>> uLocs = g.getUnstableEligibleLocations() != null
                ? new java.util.HashMap<>(g.getUnstableEligibleLocations())
                : java.util.Collections.emptyMap();

        Map<String, String> uChosenTargets = g.getUnstableTargetByPlayer() != null
                ? new java.util.HashMap<>(g.getUnstableTargetByPlayer())
                : java.util.Collections.emptyMap();

        Map<String, String> uChosenHarvests = g.getUnstableHarvestLocByPlayer() != null
                ? new java.util.HashMap<>(g.getUnstableHarvestLocByPlayer())
                : java.util.Collections.emptyMap();

        // Weather
        var weather = new GameSnapshot.WeatherView(
                g.getWeatherRoll(),
                (g.getWeatherStatus() != null ? g.getWeatherStatus().name() : null),
                g.getWeatherStatusNameFr(),
                g.getWeatherDescriptionFr(),
                (g.getSecondaryWeatherStatus() != null ? g.getSecondaryWeatherStatus().name() : null),
                g.getSecondaryWeatherStatusNameFr(),
                g.getSecondaryWeatherDescriptionFr(),
                (g.getThirdWeatherStatus() != null ? g.getThirdWeatherStatus().name() : null),
                g.getThirdWeatherStatusNameFr(),
                g.getThirdWeatherDescriptionFr());

        // Players
        List<GameSnapshot.PlayerView> players = playersSrc.stream().map(p -> {
            List<String> hand = (p.getHand() != null ? p.getHand() : List.of());
            List<String> potions = (p.getPotions() != null ? p.getPotions() : List.of());
            List<String> elixirs = (p.getElixirs() != null ? p.getElixirs() : List.of());
            List<String> actions = (p.getActions() != null ? p.getActions() : List.of());

            boolean isSelf = p.getId().equals(userId);

            List<String> handView;
            List<String> potionsView;
            List<String> elixirsView;
            List<String> actionsView;

            if (isSelf) {
                // Moi : je vois tout normalement
                handView = hand;
                potionsView = potions;
                elixirsView = elixirs;
                actionsView = actions;
            } else {
                // Les autres :
                // - main de lieux cachée
                // - potions cachées
                // - actions : on cache l’ID mais on garde la taille de la main
                handView = Collections.nCopies(hand.size(), "HIDDEN");
                potionsView = Collections.nCopies(potions.size(), "HIDDEN");
                elixirsView = Collections.nCopies(elixirs.size(), "HIDDEN");
                actionsView = Collections.nCopies(actions.size(), "HIDDEN");
            }

            return new GameSnapshot.PlayerView(
                    p.getId(),
                    p.getUsername(),
                    p.isLeftGame(),
                    p.getRole(),
                    handView,
                    potionsView,
                    elixirsView,
                    actionsView,
                    p.getHp(),
                    p.getCorruption(),
                    p.getAttackDice() != null ? p.getAttackDice() : "D4",
                    p.getDefenseDice() != null ? p.getDefenseDice() : "D4",
                    p.getWeapon(),
                    p.getArmor(),
                    p.getWood(), p.getHerbs(), p.getStone(), p.getIron(),
                    p.getWater(), p.getGold(), p.getSouls(), p.getSilver(),
                    p.isBlessedStake(),
                    p.isSacredRosary(),
                    p.isCharismaticThisRaid(),
                    p.isMerchantPending(),
                    p.getMerchantRoll(),
                    p.getShopBonusKind(),
                    p.getShopBonusEquipId(),
                    p.getShopBonusEquipTier(),
                    p.isShopBonusBuyPending(),
                    p.isElixirUsedThisRaid(),
                    p.isCrateUsedThisRaid(),
                    p.isResourceBoughtThisRaid(),
                    p.isAdvancedTransmutationUsedThisRaid(),
                    p.isMerchantUsedThisRaid());
        }).toList();

        // Center
        List<GameSnapshot.CenterView> center = centerSrc.stream()
                .map(cb -> new GameSnapshot.CenterView(cb.getPlayerId(), cb.getCard(), cb.isFaceUp()))
                .toList();

        // Raid mods
        Map<String, List<GameSnapshot.StatModView>> raidMods = new java.util.HashMap<>();
        for (var e : raidModsSrc.entrySet()) {
            var list = (e.getValue() != null) ? e.getValue() : java.util.Collections.<StatMod>emptyList();
            List<GameSnapshot.StatModView> mapped = list.stream()
                    .map(m -> new GameSnapshot.StatModView(m.getStat(), m.getAmount(), m.getSource()))
                    .toList();
            raidMods.put(e.getKey(), mapped);
        }

        // Raid effects
        Map<String, GameSnapshot.RaidEffectsView> raidEffects = new java.util.HashMap<>();
        if (g.getRaidEffects() != null) {
            for (var e : g.getRaidEffects().entrySet()) {
                String playerId = e.getKey();
                RaidEffects fx = e.getValue();
                if (fx == null)
                    continue;

                raidEffects.put(playerId,
                        new GameSnapshot.RaidEffectsView(
                                fx.isFocus(),
                                fx.isLeech(),
                                fx.isInvulnerable(),
                                fx.isDoubleAttack(),
                                fx.isDoubleDefense(),
                                fx.isInvisible(),
                                fx.isRapid()));
            }
        }

        // Decks
        var decks = new GameSnapshot.DecksView(
                new GameSnapshot.DecksView.Pile(
                        deckSize(g.getVampActionsDeck()),
                        discardSize(g.getVampActionsDiscard()),
                        safeList(g.getVampActionsDiscard())),
                new GameSnapshot.DecksView.Pile(
                        deckSize(g.getHunterActionsDeck()),
                        discardSize(g.getHunterActionsDiscard()),
                        safeList(g.getHunterActionsDiscard())),
                new GameSnapshot.DecksView.Pile(
                        deckSize(g.getPotionDeck()),
                        discardSize(g.getPotionDiscard()),
                        safeList(g.getPotionDiscard())),
                new GameSnapshot.DecksView.Pile(
                        deckSize(g.getElixirDeck()),
                        discardSize(g.getElixirDiscard()),
                        safeList(g.getElixirDiscard())));

        // Bite
        GameSnapshot.BiteView bite = null;
        if (g.getCurrentBite() != null) {
            var b = g.getCurrentBite();
            bite = new GameSnapshot.BiteView(
                    b.getAttackerId(), b.getTargetId(), b.getLocation(),
                    b.getRoll(), b.getArmorRoll(), b.getResolvedAtMillis(),
                    b.getBecameServant());
        }

        // Combats queue + current
        List<GameSnapshot.RoundFightView> combatsQueue = combatsQueueSrc.stream()
                .map(r -> new GameSnapshot.RoundFightView(
                        r.getId(), r.getLocation(),
                        r.getAttackerId(), r.getDefenderId(),
                        r.getAttackerRoll(), r.getDefenderRoll(),
                        r.getAttackerFirstRoll(), r.getDefenderFirstRoll(),
                        r.getAttackerReroll(), r.getDefenderReroll(),
                        r.getResolvedAtMillis(),
                        (r.getBreakdownLines() != null ? r.getBreakdownLines() : java.util.List.of()),
                        r.isCloneAttack(),
                        r.isCanBite()))
                .toList();

        GameSnapshot.RoundFightView currentCombat = null;
        if (g.getCurrentCombat() != null) {
            var r = g.getCurrentCombat();
            currentCombat = new GameSnapshot.RoundFightView(
                    r.getId(), r.getLocation(),
                    r.getAttackerId(), r.getDefenderId(),
                    r.getAttackerRoll(), r.getDefenderRoll(),
                    r.getAttackerFirstRoll(), r.getDefenderFirstRoll(),
                    r.getAttackerReroll(), r.getDefenderReroll(),
                    r.getResolvedAtMillis(),
                    (r.getBreakdownLines() != null ? r.getBreakdownLines() : java.util.List.of()),
                    r.isCloneAttack(),
                    r.isCanBite());
        }

        List<GameSnapshot.TradeView> trades = (g.getTrades() == null ? java.util.List.<Game.Trade>of() : g.getTrades())
                .stream()
                .filter(t -> java.util.Objects.equals(t.getAId(), userId)
                        || java.util.Objects.equals(t.getBId(), userId))
                .map(t -> new GameSnapshot.TradeView(
                        t.getId(),
                        t.getSide(),
                        t.getAId(),
                        t.getBId(),
                        copyMap(t.getOfferA()),
                        copyMap(t.getOfferB()),
                        t.getStatusA(),
                        t.getStatusB(),
                        t.getUpdatedAt() // <- primitive long
                ))
                // tri du plus récent au plus ancien (pas de null-check sur un long)
                .sorted((x, y) -> Long.compare(y.updatedAt(), x.updatedAt()))
                .toList();

        // History
        List<GameSnapshot.HistoryItemView> history = historySrc.stream().map(h -> new GameSnapshot.HistoryItemView(
                h.getTs(),
                h.getRaid(),
                (h.getPhase() != null ? h.getPhase().name() : null),
                h.getText())).toList();

        // Ready → liste (copie) pour ne pas exposer la Set interne
        java.util.List<String> readyList = new java.util.ArrayList<>(readySet);

        GameSnapshot.ActionView action = null;
        Game.Action a = g.getCurrentAction();

        if (a != null && ("NET".equals(a.getMode())
                || "PIT".equals(a.getMode())
                || "INCENDIAIRE".equals(a.getMode())
                || "PROVOCATION".equals(a.getMode())
                || "AMBUSH".equals(a.getMode())
                || "LONELY".equals(a.getMode())
                || "BLESSED_STAKE".equals(a.getMode())
                || "SACRED_ROSARY".equals(a.getMode())
                || "CHARISMATIQUE".equals(a.getMode())
                || "MARCHAND_ITINERANT".equals(a.getMode())
                || "MARCHAND_BONUS_BUY".equals(a.getMode())
                || "CRATE_LAKE".equals(a.getMode())
                || "CRATE_MANOR".equals(a.getMode())
                || "PRESENCE_ECRASANTE".equals(a.getMode())
                || "CATACLYSME".equals(a.getMode())
                || "CLONES_OMBRE".equals(a.getMode())
                || "IMAGE_MIROIR_SETUP".equals(a.getMode())
                || "IMAGE_MIROIR_RESOLVE".equals(a.getMode())
                || "ECLIPSE".equals(a.getMode())
                || "BLOOD_MOON".equals(a.getMode())
                || "VOILE_DE_BRUME".equals(a.getMode())
                || "FAIM_IRREPRESSIBLE".equals(a.getMode())
                || "MARQUE_TENEBREUSE".equals(a.getMode())
                || "AFFAIBLISSEMENT_OCCULTE".equals(a.getMode())
                || "PASSAGE_SECRET".equals(a.getMode())
                || "AVIDITE_NOCTURNE".equals(a.getMode())
                || "ADVANCED_TRANSMUTATION".equals(a.getMode())
                || "EAU_BENITE".equals(a.getMode()))) {
            action = new GameSnapshot.ActionView(
                    a.getMode(),
                    a.getOwnerId(),
                    a.getLocation(),
                    a.getTargetId(),
                    a.getRoll(),
                    a.getBreakdownLines(),
                    a.getResolvedAtMillis());
        }

        // Lieux fumigés
        java.util.List<String> garlicBlocked = (g.getGarlicBlockedLocations() != null)
                ? new java.util.ArrayList<>(g.getGarlicBlockedLocations())
                : java.util.List.of();

        // Hunters pisteur
        java.util.List<String> trackerHunters = (g.getTrackerHunters() != null)
                ? new java.util.ArrayList<>(g.getTrackerHunters())
                : java.util.List.of();

        // Feux de camp chasseur
        java.util.List<String> campfireLocations = (g.getCampfireLocations() != null)
                ? new java.util.ArrayList<>(g.getCampfireLocations())
                : java.util.List.of();

        java.util.List<String> netHunters = (g.getNetHunters() != null)
                ? new java.util.ArrayList<>(g.getNetHunters())
                : java.util.List.of();

        java.util.List<String> pitHunters = (g.getPitHunters() != null)
                ? new java.util.ArrayList<>(g.getPitHunters())
                : java.util.List.of();

        boolean hunterActionsBlockedThisRaid = g.isHunterActionsBlockedThisRaid();

        java.util.List<String> clonesLocations = null;
        if (g.getClonesLocations() != null && !g.getClonesLocations().isEmpty()) {
            clonesLocations = new java.util.ArrayList<>(g.getClonesLocations());
        }
        Boolean clonesFaceUp = g.isClonesFaceUp();

        String mirrorOwnerId = null;
        if (g.getMirrorOwnerId() != null) {
            mirrorOwnerId = g.getMirrorOwnerId();
        }
        List<String> mirrorAltLocations = null;
        if (g.getMirrorAltLocations() != null && !g.getMirrorAltLocations().isEmpty()) {
            mirrorAltLocations = new java.util.ArrayList<>(g.getMirrorAltLocations());
        }
        String mirrorChosenLocation = null;
        if (g.getMirrorChosenLocation() != null) {
            mirrorChosenLocation = g.getMirrorChosenLocation();
        }

        String pendingConstructionInfra = null;
        if (g.getPendingConstruction() != null) {
            pendingConstructionInfra = g.getPendingConstruction().infra.name();
        }

        boolean shopPricesIncreasedThisRaid = g.isShopPricesIncreasedThisRaid();

        // Conversions pour builtInfras
        List<String> builtInfras = (g.getBuiltInfras() != null)
                ? g.getBuiltInfras().stream().map(Infra::name).toList()
                : java.util.List.of();

        // Effet de lieu courant (s'il y en a un)
        Game.LocationEffectInstance currentLocEffect = null;
        if (g.getLocationEffectsQueue() != null && g.getCurrentLocationEffectIndex() != null) {
            int idx = g.getCurrentLocationEffectIndex();
            if (idx >= 0 && idx < g.getLocationEffectsQueue().size()) {
                currentLocEffect = g.getLocationEffectsQueue().get(idx);
            }
        }

        String locationEffectOwnerId = (currentLocEffect != null) ? currentLocEffect.ownerId : null;
        String locationEffectInfra = (currentLocEffect != null) ? currentLocEffect.infra.name() : null;
        String locationEffectChoice = (g.getLocationEffectChoice() != null)
                ? g.getLocationEffectChoice().name()
                : null;
        boolean locationEffectPending = g.getLocationEffectPending();

        // OMEN : cartes visibles uniquement pour le joueur qui résout
        java.util.List<String> omenCards = java.util.List.of();
        var omenState = g.getLibraryOmenState();
        if (omenState != null
                && omenState.ownerId != null
                && omenState.ownerId.equals(userId)
                && omenState.cards != null) {
            omenCards = new java.util.ArrayList<>(omenState.cards);
        }

        // EXPERIMENT
        List<GameSnapshot.MonsterView> monsters = java.util.List.of();
        if (g.getMonsters() != null && !g.getMonsters().isEmpty()) {
            monsters = g.getMonsters().stream()
                    .map(m -> new GameSnapshot.MonsterView(
                            m.id, // champs publics de Game.Monster
                            (m.type != null ? m.type.name() : null),
                            m.location,
                            m.hp,
                            m.attackDice,
                            m.defenseDice))
                    .toList();
        }

        String draftType = (g.getLaboratoryDraftMonsterType() != null ? g.getLaboratoryDraftMonsterType().name()
                : null);
        String draftLoc = g.getLaboratoryDraftLocation();

        Integer laboratoryExplosionRoll = g.getLaboratoryExplosionRoll();

        // --- Valse sanguinaire (état global du raid) ---
        boolean ballroomBloodWaltz = g.isBallroomBloodWaltz();

        List<Integer> ballroomWaltzRolls = (g.getBallroomBloodWaltzRolls() != null)
                ? new java.util.ArrayList<>(g.getBallroomBloodWaltzRolls())
                : java.util.List.of();

        Integer ballroomWaltzBest = g.getBallroomBloodWaltzBestRoll();

        boolean altarCorrupted = Boolean.TRUE.equals(g.getAltarCorrupted());

        Integer bankLevel = g.getBankLevel();
        Integer bankStoneProgress = g.getBankStoneProgress();

        // Offres boutique (armes hunters) - copie safe
        java.util.Map<String, String> shopWeaponOfferTypeByHunter = (g.getShopWeaponOfferTypeByHunter() != null)
                ? new java.util.HashMap<>(g.getShopWeaponOfferTypeByHunter())
                : java.util.Map.of();

        java.util.Map<String, Integer> shopWeaponOfferTierByHunter = (g.getShopWeaponOfferTierByHunter() != null)
                ? new java.util.HashMap<>(g.getShopWeaponOfferTierByHunter())
                : java.util.Map.of();

        return new GameSnapshot(
                g.getId(),
                (g.getStatus() != null ? g.getStatus().name() : "CREATED"),
                g.getWinnerSide(),
                g.getRaid(),
                (g.getPhase() != null ? g.getPhase().name() : "PHASE0"),
                weather,
                players,
                center,
                raidEffects,
                raidMods,
                g.isHasUpcomingCombat(),
                readyList,
                g.getPhase4DeadlineMillis(),
                readyForNextRaid,
                trades,
                decks,
                bite,
                combatsQueue,
                g.getCurrentCombatIndex(),
                currentCombat,
                uTargets,
                uLocs,
                uChosenTargets,
                uChosenHarvests,
                action,
                new java.util.ArrayList<>(g.getAmbushLocations()),
                garlicBlocked,
                trackerHunters,
                campfireLocations,
                netHunters,
                pitHunters,
                hunterActionsBlockedThisRaid,
                clonesLocations,
                clonesFaceUp,
                mirrorOwnerId,
                mirrorAltLocations,
                mirrorChosenLocation,
                shopPricesIncreasedThisRaid,
                (g.getActionCardsBoughtThisRaid() != null ? new java.util.HashMap<>(g.getActionCardsBoughtThisRaid())
                        : java.util.Map.of()),
                pendingConstructionInfra,
                builtInfras,
                locationEffectPending,
                locationEffectChoice,
                locationEffectOwnerId,
                locationEffectInfra,
                omenCards,
                monsters,
                draftType,
                draftLoc,
                laboratoryExplosionRoll,
                ballroomBloodWaltz,
                ballroomWaltzRolls,
                ballroomWaltzBest,
                altarCorrupted,
                bankLevel,
                bankStoneProgress,
                shopWeaponOfferTypeByHunter,
                shopWeaponOfferTierByHunter,
                history,
                messagesSrc,
                g.getInitialPlayerCount(),
                System.currentTimeMillis(),
                userId);
    }

    private void initPhase0Structures(Game g) {
        if (g.getRaidMods() == null)
            g.setRaidMods(new java.util.HashMap<>());
        if (g.getRaidEffects() == null)
            g.setRaidEffects(new java.util.HashMap<>());
        if (g.getMessages() == null)
            g.setMessages(new java.util.ArrayList<>());
        if (g.getHistory() == null)
            g.setHistory(new java.util.ArrayList<>());
        g.getReadyForPhase3().clear();
        g.getReadyForNextRaid().clear();
        if (g.getTrades() != null)
            g.getTrades().clear();

        if (g.getActionCardsBoughtThisRaid() != null) {
            g.getActionCardsBoughtThisRaid().clear();
        }

        g.setPhase4DeadlineMillis(null);
        if (g.getUnstableEligibleTargets() == null)
            g.setUnstableEligibleTargets(new java.util.HashMap<>());
        if (g.getUnstableTargetByPlayer() == null)
            g.setUnstableTargetByPlayer(new java.util.HashMap<>());
        if (g.getUnstableEligibleLocations() == null)
            g.setUnstableEligibleLocations(new java.util.HashMap<>());
        if (g.getUnstableHarvestLocByPlayer() == null)
            g.setUnstableHarvestLocByPlayer(new java.util.HashMap<>());
        if (g.getCombatsQueue() == null)
            g.setCombatsQueue(new java.util.ArrayList<>());
        if (g.getCenter() == null)
            g.setCenter(new java.util.ArrayList<>());
        if (g.getGarlicBlockedLocations() == null)
            g.setGarlicBlockedLocations(new java.util.HashSet<>());
        g.getGarlicBlockedLocations().clear();
        if (g.getPendingGarlicPlayers() == null)
            g.setPendingGarlicPlayers(new java.util.HashSet<>());
        g.getPendingGarlicPlayers().clear();
        if (g.getTrackerHunters() == null)
            g.setTrackerHunters(new java.util.HashSet<>());
        g.getTrackerHunters().clear();
        if (g.getCampfireLocations() == null)
            g.setCampfireLocations(new java.util.HashSet<>());
        g.getCampfireLocations().clear();
        if (g.getNetHunters() == null)
            g.setNetHunters(new java.util.HashSet<>());
        if (g.getNetCardsRemaining() == null)
            g.setNetCardsRemaining(new java.util.HashMap<>());
        if (g.getPitHunters() == null)
            g.setPitHunters(new java.util.HashSet<>());
        if (g.getPitCardsCount() == null)
            g.setPitCardsCount(new java.util.HashMap<>());
        if (g.getIncendiaireLocationByHunter() == null) {
            g.setIncendiaireLocationByHunter(new java.util.HashMap<>());
        } else {
            g.getIncendiaireLocationByHunter().clear();
        }
        if (g.getPitTargetsByHunter() == null)
            g.setPitTargetsByHunter(new java.util.HashMap<>());
        else
            g.getPitTargetsByHunter().clear();
        if (g.getPotionDeck() == null)
            g.setPotionDeck(new java.util.ArrayList<>());
        if (g.getPotionDiscard() == null)
            g.setPotionDiscard(new java.util.ArrayList<>());

        if (g.getElixirDeck() == null)
            g.setElixirDeck(new java.util.ArrayList<>());
        if (g.getElixirDiscard() == null)
            g.setElixirDiscard(new java.util.ArrayList<>());

        if (g.getHunterActionsDeck() == null)
            g.setHunterActionsDeck(new java.util.ArrayList<>());
        if (g.getHunterActionsDiscard() == null)
            g.setHunterActionsDiscard(new java.util.ArrayList<>());

        if (g.getVampActionsDeck() == null)
            g.setVampActionsDeck(new java.util.ArrayList<>());
        if (g.getVampActionsDiscard() == null)
            g.setVampActionsDiscard(new java.util.ArrayList<>());

        if (g.getPitIndexByHunter() == null)
            g.setPitIndexByHunter(new java.util.HashMap<>());
        else
            g.getPitIndexByHunter().clear();

        if (g.getLocationEffectsQueue() == null)
            g.setLocationEffectsQueue(new java.util.ArrayList<>());
        else
            g.getLocationEffectsQueue().clear();
        g.setCurrentLocationEffectIndex(null);
        g.setLocationEffectPending(false);
        g.setLocationEffectChoice(null);
        g.setLibraryOmenState(null);
        if (g.getMonsters() == null)
            g.setMonsters(new java.util.ArrayList<>());

        // Reset Valse sanguinaire (état global du raid)
        g.setBallroomBloodWaltzRolls(null);
        g.setBallroomBloodWaltzBestRoll(null);
        // (on laisse g.setBallroomBloodWaltz(...) géré par la carte/infra au moment où
        // on active l'effet pour ce raid.)

        // Bite/combat reset explicite
        g.setCurrentBite(null);
        g.setCurrentCombatIndex(null);
        g.setCurrentCombat(null);
        g.setHasUpcomingCombat(false);
        // Pour chaque joueur, on s'assure que la main est non nulle
        for (var p : g.getPlayers()) {
            if (p.getHand() == null)
                p.setHand(new java.util.ArrayList<>());
            if (p.getAttackDice() == null)
                p.setAttackDice("D4");
            if (p.getDefenseDice() == null)
                p.setDefenseDice("D4");
        }
    }

    /** Sauvegarde en préservant la version (évite les inserts involontaires). */
    private void save(@NonNull Game g) {
        store.save(g);
    }

    // ---------- utilitaires ----------

    private boolean computeHasUpcomingCombat(Game g) {
        // Face-up uniquement
        var faceUp = (g.getCenter() != null ? g.getCenter() : java.util.List.<CenterBoard>of())
                .stream()
                .filter(CenterBoard::isFaceUp)
                .toList();
        if (faceUp.isEmpty())
            return false;

        // Instables affectés à la récolte => NE COMBATTENT PAS ce raid
        java.util.Set<String> harvesters = (g.getUnstableHarvestLocByPlayer() != null)
                ? g.getUnstableHarvestLocByPlayer().keySet()
                : java.util.Set.of();

        // --- 1) Combats "classiques" (vampire / serviteurs / monstres) ---
        java.util.Set<String> locs = new java.util.HashSet<>();
        for (var cb : faceUp)
            locs.add(cb.getCard());

        for (String loc : locs) {
            var idsOnLoc = faceUp.stream()
                    .filter(cb -> loc.equals(cb.getCard()))
                    .map(CenterBoard::getPlayerId)
                    .toList();

            var playersOnLoc = idsOnLoc.stream()
                    .map(pid -> g.getPlayers().stream()
                            .filter(p -> p.getId().equals(pid))
                            .findFirst()
                            .orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .toList();

            // Ennemis "joueurs" (vivants)
            boolean hasEnemyPlayer = playersOnLoc.stream()
                    .anyMatch(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                            && isAlive(p));

            // Monstres présents sur ce lieu (vivants)
            var monstersHere = monstersOnLocation(g, loc).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            boolean hasMonsterEnemy = !monstersHere.isEmpty();
            boolean hasEnemy = hasEnemyPlayer || hasMonsterEnemy;

            boolean hasEligibleHunter = playersOnLoc.stream()
                    .anyMatch(p -> "HUNTER".equals(p.getRole())
                            && isAlive(p)
                            && !harvesters.contains(p.getId()));

            if (hasEnemy && hasEligibleHunter) {
                return true;
            }
        }

        // --- 2) Duels instables explicites => combat de toute façon ---
        if (g.getUnstableTargetByPlayer() != null && !g.getUnstableTargetByPlayer().isEmpty()) {
            return true;
        }

        // --- 3) Clones des ombres : s'il y a des chasseurs vivants sur un lieu ciblé
        // ---
        if (g.getClonesLocations() != null && !g.getClonesLocations().isEmpty()) {

            var groups = groupPlayersByLocation(g); // loc -> List<Player>

            for (String loc : g.getClonesLocations()) {
                var onLoc = groups.get(loc);
                if (onLoc == null || onLoc.isEmpty())
                    continue;

                boolean hasEligibleHunter = onLoc.stream()
                        .anyMatch(p -> "HUNTER".equals(p.getRole())
                                && p.getHp() > 0
                                && !harvesters.contains(p.getId()));

                if (hasEligibleHunter) {
                    // Même s'il n'y a pas le vampire physiquement sur ce lieu,
                    // le fait qu'un clone soit là contre un chasseur = combat pour la préphase.
                    return true;
                }
            }
        }

        return false;
    }

    // Helpers délégués au modèle de domaine (Game / Player).
    private boolean isAlive(Player p) {
        return p.isAlive();
    }

    private void addHistory(@NonNull Game g, @NonNull String text) {
        g.addHistory(text);
    }

    private boolean hasPlayed(@NonNull Game g, String playerId) {
        return g.hasPlayed(playerId);
    }

    @NonNull
    private Optional<Player> getVamp(@NonNull Game g) {
        return g.vampire();
    }

    @NonNull
    public List<Player> getHunters(@NonNull Game g) {
        return g.hunters();
    }

    private int diceSides(String d) {
        if (d == null)
            return 6;
        return switch (d.toUpperCase()) {
            case "D4" -> 4;
            case "D6" -> 6;
            case "D8" -> 8;
            case "D10" -> 10;
            case "D12" -> 12;
            case "D20" -> 20;
            default -> 6;
        };
    }

    private String nameOf(Game g, String playerId) {
        return g.nameOf(playerId);
    }

    private WeatherStatus mapRollToWeather(int roll) {
        return switch (roll) {
            case 1 -> WeatherStatus.SUNNY;
            case 2 -> WeatherStatus.FOG;
            case 3 -> WeatherStatus.AURORA;
            case 4 -> WeatherStatus.CLOUDY;
            case 5 -> WeatherStatus.WIND;
            case 6 -> WeatherStatus.STORM;
            case 7 -> WeatherStatus.RAIN;
            case 8 -> WeatherStatus.BLIZZARD;
            case 9 -> WeatherStatus.DUSK;
            case 10 -> WeatherStatus.NIGHT_DARK;
            case 11 -> WeatherStatus.NIGHT_CLEAR;
            case 12 -> WeatherStatus.FULL_MOON;
            default -> null;
        };
    }

    private String weatherNameFr(WeatherStatus ws) {
        return switch (ws) {
            case SUNNY -> "Jour ensoleillé";
            case FOG -> "Brouillard protecteur";
            case AURORA -> "Aurore";
            case WIND -> "Cyclone";
            case CLOUDY -> "Ciel couvert";
            case STORM -> "Orage";
            case RAIN -> "Pluie diluvienne";
            case BLIZZARD -> "Blizzard";
            case DUSK -> "Crépuscule";
            case NIGHT_DARK -> "Nuit obscure";
            case NIGHT_CLEAR -> "Nuit claire";
            case FULL_MOON -> "Pleine lune";
            case BLOOD_MOON -> "Lune sanglante";
        };
    }

    private String weatherDescFr(WeatherStatus ws) {
        return switch (ws) {
            case SUNNY -> "La lumière domine.\n+1 attaque pour les chasseurs et –1 défense pour le vampire.";
            case FOG -> "La brume étouffe les sons et couvre l'approche.\n+1 attaque des chasseurs.";
            case AURORA -> "La lumière progresse.\n-1 défense pour le vampire.";
            case WIND -> "Un cyclone dévaste la région.\n" +
                    "Le vampire ne peut construire ce raid.\n" +
                    "Chaque chasseur perd 1 ressource de construction aléatoire.\n" +
                    "Le domaine perd ensuite 1 ressource de construction aléatoire par construction.";
            case CLOUDY -> "Lumière terne.\n-1 attaque pour le vampire.";
            case STORM -> "La foudre déstabilise au combat.\n-2 défense pour tous.";
            case RAIN -> "La pluie torrentielle alourdit chaque geste.\n-2 attaque pour tous.";
            case BLIZZARD -> "Froid mordant.\nPotions gelées et -1 attaque pour tous.";
            case DUSK -> "Les ombres progressent.\n+1 défense du vampire.";
            case NIGHT_DARK ->
                "Les ombres dominent.\n+1 attaque du vampire. Les chasseurs ne peuvent utiliser de pièges.";
            case NIGHT_CLEAR ->
                "La lune éclaire légèrement et le vampire gagne en puissance.\n+1 attaque du vampire et –1 défense pour les chasseurs.";
            case FULL_MOON -> "La pleine lune exalte le sang ancien.\n+2 attaque du vampire.";
            case BLOOD_MOON -> "La Pleine lune devient Lune sanglante.\n+4 Attaque du vampire.";
        };
    }

    /**
     * Ajoute (ou remplace par source) un mod de raid pour un joueur.
     * Idempotent par 'source' : si un mod avec la même source existe, il est retiré
     * avant ajout.
     */
    private void addRaidMod(Game g, String playerId, String stat, int amount, String source) {
        if (g.getRaidMods() == null)
            g.setRaidMods(new HashMap<>());
        var list = g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>());

        // On remplace toute entrée existante portant la même source (idempotent)
        if (source != null && stat != null) {
            list.removeIf(m -> source.equals(m.getSource()) && stat.equals(m.getStat()));
        } else if (source != null) {
            list.removeIf(m -> source.equals(m.getSource()));
        }
        list.add(new StatMod(stat, amount, source));
    }

    /**
     * Construit la liste des modificateurs “moteur” d’un joueur pour le raid
     * courant.
     * On renvoie uniquement les mods chiffrés (ATTACK / DEFENSE) utiles pour le
     * calcul,
     * en appliquant éventuellement l’annulation météo par Feu de camp.
     *
     * Pour les monstres :
     * - on NE garde que les mods d'actions (source "ACTION:...")
     * - pas de météo / corruption / potions.
     */
    private List<StatMod> modsAppliedFor(Game g, String playerId, String stat, String location) {
        // 0) pas de mods → liste vide
        if (g.getRaidMods() == null || stat == null)
            return java.util.List.of();

        var list = g.getRaidMods().get(playerId);
        if (list == null)
            return java.util.List.of();

        // Est-ce un monstre ?
        boolean isMonster = (findMonster(g, playerId) != null);

        // On filtre d'abord par stat (ATTACK ou DEFENSE)
        var stream = list.stream()
                .filter(m -> stat.equalsIgnoreCase(m.getStat()));

        // CAS MONSTRE : uniquement les actions (Filet, Fosse, etc.)
        if (isMonster) {
            return stream
                    .filter(m -> {
                        String s = m.getSource();
                        // on ne garde pour l’instant que les sources ACTION:*
                        return s != null && s.startsWith("ACTION:");
                    })
                    .toList();
        }

        // CAS JOUEUR : comportement historique inchangé

        // 1) base = tous les mods pour ce joueur et cette stat
        var base = stream.toList();

        // 2) pas de lieu ou pas de feu de camp qui annule la météo → on renvoie tout
        if (location == null || !isCampfireCancellingWeather(g, location)) {
            return base;
        }

        // 3) Feu de camp sur ce lieu : on retire seulement les mods météo
        return base.stream()
                .filter(m -> {
                    String s = m.getSource();
                    return s == null || !s.startsWith("WEATHER:");
                })
                .toList();
    }

    /**
     * Additionne les modificateurs d’un joueur pour une statistique donnée.
     *
     * Typiquement appelé au moment de résoudre un combat :
     * score = dX + totalModFor(g, playerId, ATTACK|DEFENSE)
     *
     * NB : ne crée rien, ne modifie rien — ne fait qu’agréger ce qui a été
     * préalablement construit (ex: par rebuildRaidModsForAll / modsAppliedFor).
     *
     */

    private int totalModFor(Game g, String playerId, String stat, String location) {
        return totalModForAt(g, playerId, stat, location);
    }

    private int totalModForAt(Game g, String playerId, String stat, String location) {
        return modsAppliedFor(g, playerId, stat, location)
                .stream().mapToInt(StatMod::getAmount).sum();
    }

    /**
     * Construit les logs liés aux mods (affichés dans la modale spectateur ET
     * poussés dans l'historique).
     */
    private List<String> buildModBreakdownLines(Game g, String playerId, String stat, int baseRoll, String location) {
        List<String> out = new ArrayList<>();
        int cur = baseRoll;
        String sideLabel = "ATTACK".equalsIgnoreCase(stat) ? "L’attaque" : "La défense";
        String name = nameOf(g, playerId);

        for (var m : modsAppliedFor(g, playerId, stat, location)) {
            int delta = m.getAmount();
            if (delta == 0)
                continue;

            String verb = (delta >= 0) ? "augmente" : "diminue";
            int abs = Math.abs(delta);

            String src = "";
            String s = (m.getSource() == null) ? "" : m.getSource();
            if (m.getSource() != null && m.getSource().startsWith("WEATHER:")
                    && !isCampfireCancellingWeather(g, location)) {
                try {
                    var wsStr = m.getSource().substring("WEATHER:".length());
                    var ws = WeatherStatus.valueOf(wsStr);
                    src = "par l’effet " + weatherNameFr(ws).toLowerCase();
                } catch (Exception ignored) {
                    /* fallback simple */ }
            } else if (s.startsWith("POTION:")) {
                String type = s.substring("POTION:".length());
                src = switch (type) {
                    case "FORCE" -> "par l'effet potion de force";
                    case "ENDURANCE" -> "par l'effet potion d’endurance";
                    case "VIE" -> "par l'effet potion de vie";
                    default -> "par l'effet potion";
                };
            } else if (s.startsWith("ACTION:")) {
                String type = s.substring("ACTION:".length());
                src = switch (type) {
                    case "NET", "NET:ENG" -> "par l'effet du filet";
                    case "PIT", "PIT:ENG" -> "par l'effet de la fosse";
                    case "LONELY", "LONELY:ENG" -> "par l'effet de solitaire";
                    case "AFFAIBLISSEMENT_OCCULTE", "AFFAIBLISSEMENT_OCCULTE:ENG" ->
                        "par l'effet d'affaiblissement occulte";
                    default -> "par l'effet action";
                };
            } else if (s.startsWith("CORRUPTION:")) {
                // L1 moteur (−1 ATK/DEF) => libellé clair
                if (s.contains(":L1:")) {
                    src = "par l’effet Affaibli (corruption)";
                }
                // L2 ("Instable") n’a pas de mod chiffré => pas de ligne ici (géré en chip côté
                // front)
            } else if (s.startsWith("EQUIP:")) {
                if ("EQUIP:VAMP_ARMOR_DEF".equals(s)) {
                    src = "grâce à son armure vampirique";
                }
            } else if (s.startsWith("HIT:")) {
                if ("HIT:STUN_WEAPON:ENG".equals(s)) {
                    src = "par l’effet étourdissement";
                }
            }

            cur += delta;
            out.add(String.format("%s de %s %s de %d %s et passe à %d", sideLabel, name, verb, abs, src, cur));
        }
        return out;
    }

    private void appendModBreakdownForSide(Game g,
            RoundFight r,
            String playerId,
            String stat, // "ATTACK" ou "DEFENSE"
            int baseRoll) { // valeur brute du dé (ou de la Valse/Foca)
        // On construit les lignes de breakdown pour ce côté
        List<String> lines = buildModBreakdownLines(
                g,
                playerId,
                stat,
                baseRoll,
                r.getLocation());

        if (lines == null || lines.isEmpty()) {
            return;
        }

        // On s'assure que la liste breakdownLines existe
        if (r.getBreakdownLines() == null) {
            r.setBreakdownLines(new java.util.ArrayList<>());
        }

        // On ajoute les lignes au breakdown du duel
        r.getBreakdownLines().addAll(lines);

        // Et on pousse aussi dans l'historique global
        for (String ln : lines) {
            addHistory(g, ln);
        }
    }

    // ---------- CRUD ----------
    @Transactional
    public Game create() {
        String id = UUID.randomUUID().toString();
        Game game = new Game(id, GameStatus.CREATED, 0);
        save(game);

        afterCommit(() -> {
            live.gameCreated(game);
        });

        return game;
    }

    public Collection<Game> list() {
        return store.readAll();
    }

    @Transactional
    public Game join(String gameId, String userId, String username) {
        // 1) D'abord: source de vérité + LOCK + limite max
        Game g = addOrUpdatePlayer(gameId, userId, username);

        // 2) Ensuite: mapping SQL (si ça échoue -> rollback de (1))
        playerService.joinGame(userId, gameId, username);

        return g;
    }

    // ---------- LOBBY ----------
    private static final long STARTING_MAX_MS = 60_000; // 60s

    private boolean ensureStartingTimeout(Game g, long now) {
        if (g.getStatus() == GameStatus.STARTING && g.getStartingAtTs() != null) {
            if (now - g.getStartingAtTs() > STARTING_MAX_MS) {
                g.setStatus(GameStatus.CREATED);
                g.setStartingAtTs(null);
                if (g.getReadyForStart() != null)
                    g.getReadyForStart().clear();
                return true;
            }
        }
        return false;
    }

    private static final int MAX_PLAYERS = 7;

    @Transactional
    public Game addOrUpdatePlayer(String gameId, String playerId, String username) {
        if (username == null || username.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username required");

        Game g = findOr404ForUpdate(gameId); // lock pessimiste => join sérialisées
        long now = System.currentTimeMillis();
        boolean changed = false;

        if (ensureStartingTimeout(g, now))
            changed = true;

        if (g.getStatus() != GameStatus.CREATED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game already started/ended");

        // cleanup ghosts (met leftGame=true)
        Set<String> stale = cleanupStaleLobbyPlayers(g, now);
        if (!stale.isEmpty())
            changed = true;

        // ---- LIMITE MAX JOUEURS (après cleanup) ----
        var existingOpt = g.getPlayers().stream()
                .filter(p -> p.getId().equals(playerId))
                .findFirst();

        long activeCount = g.getPlayers().stream()
                .filter(p -> !p.isLeftGame())
                .count();

        boolean alreadyActive = existingOpt.isPresent() && !existingOpt.get().isLeftGame();
        boolean wouldConsumeSlot = !alreadyActive; // nouveau joueur OU retour d’un leftGame

        if (wouldConsumeSlot && activeCount >= MAX_PLAYERS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "game is full (max " + MAX_PLAYERS + ")");
        }
        // -------------------------------------------

        // upsert + lastSeen
        if (existingOpt.isPresent()) {
            Player p = existingOpt.get();
            p.setUsername(username);
            p.setLeftGame(false);
            p.setLastSeenTs(now);
        } else {
            Player p = new Player(playerId, username);
            p.setLeftGame(false);
            p.setLastSeenTs(now);
            g.getPlayers().add(p);
        }
        changed = true;

        save(g);
        if (changed)
            afterCommit(() -> live.lobbyUpdated(g));
        return g;
    }

    @Transactional
    public void requestStart(String id) {
        Game g = findOr404ForUpdate(id);

        if (g.getStatus() != GameStatus.CREATED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already started/ended");

        long now = System.currentTimeMillis();

        // 1) marque leftGame=true pour les ghosts
        Set<String> stale = cleanupStaleLobbyPlayers(g, now);

        // 2) on retire tous les leftGame du roster (ghosts + gens qui avaient leave)
        var removedIds = g.getPlayers().stream()
                .filter(Player::isLeftGame)
                .map(Player::getId)
                .toList();

        if (!removedIds.isEmpty()) {
            g.getPlayers().removeIf(Player::isLeftGame);
            if (g.getReadyForStart() != null)
                g.getReadyForStart().removeAll(removedIds);

            // mapping SQL : on libère ces users (ils ne font plus partie du roster)
            for (String uid : removedIds) {
                try {
                    playerService.leaveGame(uid, id);
                } catch (Exception ignored) {
                }
            }
        }

        // 3) check players count (plus besoin de filter leftGame, ils sont déjà
        // retirés)
        if (g.getPlayers().size() < 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "need at least 2 players");

        // 4) garde-fou max players (très important)
        if (g.getPlayers().size() > MAX_PLAYERS)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "too many players (max " + MAX_PLAYERS + ")");

        g.setStatus(GameStatus.STARTING);
        g.setStartingAtTs(now);

        if (g.getReadyForStart() == null)
            g.setReadyForStart(new HashSet<>());
        else
            g.getReadyForStart().clear();

        save(g);
        afterCommit(() -> live.lobbyUpdated(g));
    }

    @Transactional
    public void presence(String gameId, String userId) {
        Game g = findOr404ForUpdate(gameId);
        long now = System.currentTimeMillis();

        boolean changed = false;

        if (ensureStartingTimeout(g, now))
            changed = true;

        if (g.getStatus() != GameStatus.CREATED && g.getStatus() != GameStatus.STARTING)
            return;

        // lastSeen du caller
        g.getPlayers().stream()
                .filter(p -> p.getId().equals(userId) && !p.isLeftGame())
                .findFirst()
                .ifPresent(p -> {
                    p.setLastSeenTs(now);
                });

        // cleanup ghosts
        Set<String> stale = cleanupStaleLobbyPlayers(g, now);
        if (!stale.isEmpty())
            changed = true;

        // si on était STARTING et qu'on a kick quelqu’un => retour CREATED
        if (!stale.isEmpty() && g.getStatus() == GameStatus.STARTING) {
            g.setStatus(GameStatus.CREATED);
            g.setStartingAtTs(null);
            if (g.getReadyForStart() != null)
                g.getReadyForStart().clear();
            changed = true;
        }

        save(g);
        if (changed || g.getStatus() == GameStatus.STARTING) {
            afterCommit(() -> live.lobbyUpdated(g));
        }
    }

    private static final long LOBBY_TTL_MS = 60_000; // 1 minute
    private static final long STARTING_GRACE_MS = 8_000; // 8s pour répondre après start

    // retourne les ids mis en leftGame
    private Set<String> cleanupStaleLobbyPlayers(Game g, long now) {
        if (g.getStatus() != GameStatus.CREATED && g.getStatus() != GameStatus.STARTING) {
            return Set.of();
        }

        Set<String> staleIds = new HashSet<>();
        Long startingAt = g.getStartingAtTs();

        for (Player p : g.getPlayers()) {
            if (p.isLeftGame())
                continue;

            Long ts = p.getLastSeenTs();

            // règle normale lobby
            boolean ttlStale = (ts == null) || (now - ts > LOBBY_TTL_MS);

            // règle STARTING : si le joueur n’a jamais ping depuis le début de STARTING,
            // et qu’on a dépassé la fenêtre de grâce, c’est un ghost.
            boolean startingStale = g.getStatus() == GameStatus.STARTING
                    && startingAt != null
                    && (now - startingAt > STARTING_GRACE_MS)
                    && (ts == null || ts < startingAt);

            if (ttlStale || startingStale) {
                p.setLeftGame(true);
                staleIds.add(p.getId());
            }
        }

        if (!staleIds.isEmpty() && g.getReadyForStart() != null) {
            g.getReadyForStart().removeAll(staleIds);
        }

        return staleIds;
    }

    @Transactional
    public void bootReady(String gameId, String userId) {
        Game g = findOr404ForUpdate(gameId);
        long now = System.currentTimeMillis();

        boolean changed = false;

        if (ensureStartingTimeout(g, now))
            changed = true;

        // caller actif
        g.getPlayers().stream()
                .filter(p -> p.getId().equals(userId) && !p.isLeftGame())
                .findFirst()
                .ifPresent(p -> {
                    p.setLastSeenTs(now);
                });

        // si déjà ACTIVE -> nothing
        if (g.getStatus() == GameStatus.ACTIVE)
            return;

        // si plus STARTING (ex: repassée CREATED par timeout), on sort sans erreur
        if (g.getStatus() != GameStatus.STARTING) {
            if (changed) {
                save(g);
                afterCommit(() -> live.lobbyUpdated(g));
            }
            return;
        }

        // cleanup ghosts
        Set<String> stale = cleanupStaleLobbyPlayers(g, now);
        if (!stale.isEmpty()) {
            // si on kick en STARTING => retour CREATED
            g.setStatus(GameStatus.CREATED);
            g.setStartingAtTs(null);
            if (g.getReadyForStart() != null)
                g.getReadyForStart().clear();

            save(g);
            afterCommit(() -> live.lobbyUpdated(g));
            return;
        }

        if (g.getReadyForStart() == null)
            g.setReadyForStart(new HashSet<>());
        boolean added = g.getReadyForStart().add(userId);
        if (added)
            changed = true;

        var activeIds = g.getPlayers().stream()
                .filter(p -> !p.isLeftGame())
                .map(Player::getId)
                .toList();

        boolean allReady = activeIds.stream().allMatch(pid -> g.getReadyForStart().contains(pid));

        if (!allReady) {
            save(g);
            afterCommit(() -> live.lobbyUpdated(g));
            return;
        }

        startReal(g);
    }

    @Transactional
    public Game startReal(Game g) {
        if (g.getStatus() == GameStatus.ACTIVE)
            return g;
        if (g.getStatus() != GameStatus.STARTING)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in STARTING");

        // sécurité: au cas où
        g.getPlayers().removeIf(Player::isLeftGame);

        if (g.getPlayers().size() < 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "need at least 2 players");
        if (g.getPlayers().size() > MAX_PLAYERS)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "too many players (max " + MAX_PLAYERS + ")");

        // === Etat global ===
        g.setInitialPlayerCount(g.getPlayers().size());
        g.setStatus(GameStatus.ACTIVE);
        g.setRaid(1);
        g.setPhase(Phase.PHASE0);
        g.setStartingAtTs(null);

        // === PHASE0 : météo (reset complet, sans push d’events) ===
        g.setWeatherRoll(null);
        g.setWeatherStatus(null);
        g.setWeatherStatusNameFr(null);
        g.setWeatherDescriptionFr(null);

        // Messages persistés (le feed "live" sera émis après commit)
        g.setMessages(new ArrayList<>(List.of("Tirage météo ...")));

        // --- Rôles + mains (répartition initiale) ---
        int vampIndex = dice.nextInt(g.getPlayers().size());
        for (int i = 0; i < g.getPlayers().size(); i++) {
            Player p = g.getPlayers().get(i);
            p.setRole(i == vampIndex ? "VAMPIRE" : "HUNTER");
            p.setHand(new ArrayList<>(List.of("forest", "quarry", "lake", "manor")));

            // dés de base
            p.setAttackDice("D4");
            p.setDefenseDice("D4");
        }

        // PV init (vamp = 20 + 5 * nb chasseurs)
        int huntersCount = (int) g.getPlayers().stream().filter(p -> !"VAMPIRE".equals(p.getRole())).count();
        for (var p : g.getPlayers()) {
            p.setHp("VAMPIRE".equals(p.getRole()) ? 20 + huntersCount * 5 : 20);
        }
        /*
         * // startReal : ajout de 2 GARGOYLE à forest pour tester
         * if (g.getMonsters() == null)
         * g.setMonsters(new java.util.ArrayList<>());
         * Game.Monster m1 = new Game.Monster();
         * m1.id = java.util.UUID.randomUUID().toString();
         * m1.type = Game.MonsterType.GARGOYLE;
         * m1.location = "forest";
         * m1.hp = 10;
         * m1.attackDice = "D6";
         * m1.defenseDice = "D8";
         * g.getMonsters().add(m1);
         * 
         * Game.Monster m2 = new Game.Monster();
         * m2.id = java.util.UUID.randomUUID().toString();
         * m2.type = Game.MonsterType.GARGOYLE;
         * m2.location = "forest";
         * m2.hp = 10;
         * m2.attackDice = "D6";
         * m2.defenseDice = "D8";
         * g.getMonsters().add(m2);
         */
        /*
         * // ============================
         * // MODE TEST : 1v1 + 2 morts
         * // ============================
         * // À utiliser en dev uniquement pour tester la gestion de joueurs morts.
         * if (g.getPlayers().size() >= 4) {
         * // On récupère les chasseurs après assignation aléatoire du vampire
         * java.util.List<Player> hunters = g.getPlayers().stream()
         * .filter(p -> "HUNTER".equals(p.getRole()))
         * .toList();
         * 
         * // On veut au moins 3 chasseurs pour faire :
         * // - 1 serviteur mort (ex-chasseur)
         * // - 1 chasseur mort
         * // - 1 chasseur vivant
         * if (hunters.size() >= 3) {
         * Player servantDead = hunters.get(0);
         * Player hunterDead = hunters.get(1);
         * Player hunterAlive = hunters.get(2);
         * 
         * // Le premier chasseur devient SERVANT mort
         * servantDead.setRole("SERVANT");
         * servantDead.setHp(0);
         * 
         * // Le deuxième reste HUNTER mais mort
         * hunterDead.setHp(0);
         * 
         * // On laisse le vampire et hunterAlive avec leurs PV init.
         * // Pas besoin de plus pour ce test : les méthodes isAlive() utiliseront hp>0
         * }
         * }
         * // ============================
         */

        // --- Inventaire ressources (dev/test) ---
        for (var p : g.getPlayers()) {
            if ("VAMPIRE".equals(p.getRole())) {
                p.setSouls(100 * huntersCount);
                if (huntersCount < 3) {
                    p.setHerbs(10);
                    p.setWater(10);
                    p.setWood(5);
                    p.setIron(5);
                } else if (huntersCount > 4) {
                    p.setHerbs(20);
                    p.setWater(20);
                    p.setWood(15);
                    p.setIron(15);
                } else {
                    p.setHerbs(15);
                    p.setWater(15);
                    p.setWood(10);
                    p.setIron(10);
                }
                p.setStone(10);
            }
            if ("HUNTER".equals(p.getRole())) {
                p.setGold(150);
                p.setWood(0);
                p.setHerbs(10);
                p.setWater(10);
                p.setStone(0);
                p.setIron(0);
            }
        }

        /*
         * // --- Inventaire potions (dev/test) ---
         * for (var p : g.getPlayers()) {
         * if ("HUNTER".equals(p.getRole())) {
         * p.getPotions().addAll(List.of("VIE"));
         * }
         * 
         * if ("VAMPIRE".equals(p.getRole())) {
         * p.getPotions().addAll(List.of("FOCALISATION", "FOCALISATION"));
         * }
         * 
         * }
         */

        for (var p : g.getPlayers()) {
            if ("HUNTER".equals(p.getRole())) {
                p.getActions().addAll(List.of(
                        "CRATE_MANOR", "CRATE_LAKE"
                // "AMBUSH", "AMBUSH", "PROVOCATION", "PROVOCATION",
                // "NET", "NET", "PIT", "PIT", "NET", "NET", "PIT", "PIT",
                // "NET", "NET", "PIT", "PIT", "NET", "NET", "PIT", "PIT"
                // "MARCHAND_ITINERANT", "MARCHAND_ITINERANT"
                ));
            }
            if ("VAMPIRE".equals(p.getRole())) {
                p.getActions().addAll(List.of(
                        "ADVANCED_TRANSMUTATION"
                // "VOILE_DE_BRUME", "VOILE_DE_BRUME", "VOILE_DE_BRUME",
                // "FAIM_IRREPRESSIBLE", "FAIM_IRREPRESSIBLE",
                // "FAIM_IRREPRESSIBLE", "MARQUE_TENEBREUSE",
                // "IMAGE_MIROIR", "IMAGE_MIROIR", "IMAGE_MIROIR",
                // "CLONES_OMBRE", "CLONES_OMBRE", "CLONES_OMBRE"
                // "PASSAGE_SECRET", "PASSAGE_SECRET", "PASSAGE_SECRET"
                ));
            }
        }

        /*
         * // --- Inventaire actions (dev/test) ---
         * for (var p : g.getPlayers()) {
         * if ("HUNTER".equals(p.getRole())) {
         * p.getActions().addAll(List.of(
         * "MARCHAND_ITINERANT", "MARCHAND_ITINERANT", "MARCHAND_ITINERANT",
         * "CHARISMATIQUE", "SACRED_ROSARY", "SACRED_ROSARY", "BLESSED_STAKE",
         * "BLESSED_STAKE",
         * "AMBUSH", "AMBUSH", "EAU_BENITE", "EAU_BENITE", "PROVOCATION", "PROVOCATION",
         * "EAU_BENITE", "INCENDIAIRE", "INCENDIAIRE", "NET", "PIT", "FUMIGATION_AIL",
         * "FUMIGATION_AIL", "PISTEUR", "PISTEUR"
         * ));
         * }
         * if ("VAMPIRE".equals(p.getRole())) {
         * p.getActions().addAll(List.of(
         * "AVIDITE_NOCTURNE", "AVIDITE_NOCTURNE", "VOILE_DE_BRUME", "BLOOD_MOON",
         * "AFFAIBLISSEMENT_OCCULTE", "IMAGE_MIROIR", "IMAGE_MIROIR", "PASSAGE_SECRET",
         * "PASSAGE_SECRET", "PASSAGE_SECRET", "FAIM_IRREPRESSIBLE", "CATACLYSME",
         * "CATACLYSME",
         * "CLONES_OMBRE", "CLONES_OMBRE", "MARQUE_TENEBREUSE", "PRESENCE_ECRASANTE",
         * "PRESENCE_ECRASANTE"
         * ));
         * }
         * }
         */

        initDecks(g);

        g.setCenter(new ArrayList<>());

        // --- Structures de raid (vides, prêtes) ---
        if (g.getRaidMods() == null)
            g.setRaidMods(new HashMap<>());
        else
            g.getRaidMods().clear();

        if (g.getRaidEffects() == null)
            g.setRaidEffects(new HashMap<>());
        g.getRaidEffects().clear();
        for (var p : g.getPlayers()) {
            g.getRaidEffects().put(p.getId(), new RaidEffects());
        }

        g.setHarvestedRaid(null);
        g.setHasUpcomingCombat(false);
        g.getReadyForPhase3().clear();

        // ====== COMMIT des changements ======
        save(g);

        // ====== EVENTS APRÈS COMMIT ======
        afterCommit(() -> {
            // (1) le lobby doit voir que le status passe à ACTIVE
            live.lobbyUpdated(g);

            // (2) Petite ligne de feed (indépendante des messages persistés)
            pushLive(g, "Préparation du tirage météo…");

            // (3) Optionnel mais propre: informer le front que les mods sont
            // (ré)initialisés
            live.raidModsUpdated(g);

            // (4) Phase visible côté clients → ils feront un GET propre après cet event
            live.phaseChanged(g);
        });

        return g;
    }

    @Transactional
    public void surrender(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }

        Player p = g.getPlayers().stream()
                .filter(x -> x.getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "not in game"));

        // surrender = mort, MAIS on ne quitte pas
        p.setHp(0);
        // IMPORTANT : ne pas faire p.setLeftGame(true) ici

        // si ça peut terminer la game (ex: vampire abandonne)
        handleDeathsAndVictory(g);

        save(g);

        afterCommit(() -> {
            Game fresh = findOr404(gameId);
            live.lobbyUpdated(fresh);
            live.phaseChanged(fresh); // pour forcer refresh snapshot chez les clients
        });
    }

    public void leave(String gameId, String userId) {
        tx.execute(status -> {
            Game g = findOr404ForUpdate(gameId);

            Player p = g.getPlayers().stream()
                    .filter(x -> x.getId().equals(userId))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "not in game"));

            // Si ACTIVE: quitte QUE si déjà mort (hp<=0)
            if (g.getStatus() == GameStatus.ACTIVE && isAlive(p)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "must surrender before leaving");
            }

            // Quitter réellement
            p.setLeftGame(true);

            // Si CREATED et 0 joueurs actifs => delete complet
            boolean shouldDelete = g.getStatus() == GameStatus.CREATED &&
                    g.getPlayers().stream().noneMatch(pl -> !pl.isLeftGame());

            if (shouldDelete) {
                // Supprime l'entity en base (JSONB)
                playerRepo.deleteByGameId(gameId);
                gameRepo.deleteById(gameId);
            } else {
                // si on quitte le lobby (CREATED/STARTING), on libère le mapping SQL
                if (g.getStatus() != GameStatus.ACTIVE) {
                    playerService.leaveGame(userId, gameId); // supprime PlayerEntity
                }
                if (g.getStatus() == GameStatus.ACTIVE)
                    handleDeathsAndVictory(g);
                save(g);
            }

            afterCommit(() -> {
                if (shouldDelete) {
                    live.gameDeleted(gameId);
                } else {
                    Game fresh = findOr404(gameId);
                    live.lobbyUpdated(fresh);
                    live.phaseChanged(fresh);
                }
            });

            return null;
        });
    }

    private void handleDeathsAndVictory(Game g) {
        // 1) Traitement "on death" (défausser actions chasseur, etc.)
        for (Player p : g.getPlayers()) {
            if (!isAlive(p)) {
                onPlayerDeath(g, p);
            }
        }

        // 2) Vérifier si la partie doit se terminer
        checkEndGame(g);
    }

    private void onPlayerDeath(Game g, Player p) {
        // Idempotent : si la main est déjà vide, discardAllActionsOf ne cassera rien.
        if ("HUNTER".equals(p.getRole())) {
            discardAllActionsOf(g, p);
            discardAllPotionsOf(g, p);
        }
        if ("SERVANT".equals(p.getRole())) {
            discardAllPotionsOf(g, p);
        }
    }

    private void checkEndGame(Game g) {
        // Si la partie n’est plus active, on ne touche à rien
        if (g.getStatus() != GameStatus.ACTIVE)
            return;

        boolean vampAlive = g.getPlayers().stream()
                .anyMatch(p -> "VAMPIRE".equals(p.getRole()) && isAlive(p));

        // IMPORTANT : tu passes déjà les chasseurs à role "SERVANT" quand corruption ==
        // 3
        // donc ici on veut uniquement les chasseurs encore "libres" (role HUNTER)
        boolean hunterAlive = g.getPlayers().stream()
                .anyMatch(p -> "HUNTER".equals(p.getRole()) && isAlive(p));

        if (!vampAlive) {
            g.setStatus(GameStatus.ENDED);
            g.setWinnerSide("HUNTERS");
            addHistory(g, "Victoire des chasseurs: le vampire est terrassé.");
            g.setMessages(java.util.List.of("Fin de partie — Les chasseurs triomphent."));
            return;
        }

        if (!hunterAlive) {
            g.setStatus(GameStatus.ENDED);
            g.setWinnerSide("VAMPIRE");
            addHistory(g, "Victoire du vampire: plus aucun chasseur n'est debout.");
            g.setMessages(java.util.List.of("Fin de partie — Le vampire règne sans partage."));
        }
    }

    public EndedGameSummary viewEndedSummary(String gameId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ENDED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not ended");
        }

        var players = g.getPlayers().stream()
                .map(p -> new EndedGameSummary.PlayerSummary(
                        p.getId(),
                        p.getUsername(),
                        p.getRole(),
                        p.getHp(),
                        p.isLeftGame()))
                .toList();

        return new EndedGameSummary(
                g.getId(),
                g.getStatus().name(),
                g.getWinnerSide(),
                players);
    }

    private List<String> buildDeckFromComposition(Map<String, Integer> composition) {
        List<String> deck = new ArrayList<>();
        for (var e : composition.entrySet()) {
            String cardId = e.getKey();
            int count = (e.getValue() != null ? e.getValue() : 0);
            for (int i = 0; i < count; i++) {
                deck.add(cardId);
            }
        }
        // Mélange initial, une seule fois
        dice.shuffle(deck);
        return deck;
    }

    private void initDecks(Game g) {
        // --- Potions ---
        Map<String, Integer> potionsComp = new HashMap<>();
        potionsComp.put("FORCE", 6);
        potionsComp.put("ENDURANCE", 6);
        potionsComp.put("VIE", 7);
        potionsComp.put("FOCALISATION", 4);
        potionsComp.put("SANGSUE", 4);

        g.setPotionDeck(buildDeckFromComposition(potionsComp));
        g.setPotionDiscard(new ArrayList<>());

        // --- Rare Potions ---
        Map<String, Integer> elixirsComp = new HashMap<>();
        elixirsComp.put("RESILIENCE", 3);
        elixirsComp.put("RAGE", 3);
        elixirsComp.put("RAPIDITE", 2);
        elixirsComp.put("INVISIBILITE", 2);
        elixirsComp.put("INVULNERABILITE", 1);

        g.setElixirDeck(buildDeckFromComposition(elixirsComp));
        g.setElixirDiscard(new ArrayList<>());

        // --- Actions chasseurs ---
        Map<String, Integer> hunterComp = new HashMap<>();
        hunterComp.put("FUMIGATION_AIL", 3);
        hunterComp.put("FEU_DE_CAMP", 2);
        hunterComp.put("NET", 8);
        hunterComp.put("PIT", 6);
        hunterComp.put("INCENDIAIRE", 2);
        hunterComp.put("PROVOCATION", 4);
        hunterComp.put("AMBUSH", 3);
        hunterComp.put("LONELY", 3);
        hunterComp.put("BLESSED_STAKE", 6);
        hunterComp.put("SACRED_ROSARY", 1);
        hunterComp.put("CHARISMATIQUE", 4);
        hunterComp.put("MARCHAND_ITINERANT", 8);
        hunterComp.put("CRATE_MANOR", 5);
        hunterComp.put("CRATE_LAKE", 5);

        g.setHunterActionsDeck(buildDeckFromComposition(hunterComp));
        g.setHunterActionsDiscard(new ArrayList<>());

        // --- Actions vampire ---
        Map<String, Integer> vampComp = new HashMap<>();
        vampComp.put("PRESENCE_ECRASANTE", 2);
        vampComp.put("CATACLYSME", 2);
        vampComp.put("CLONES_OMBRE", 4);
        vampComp.put("IMAGE_MIROIR", 2);
        vampComp.put("ECLIPSE", 3);
        vampComp.put("BLOOD_MOON", 1);
        vampComp.put("VOILE_DE_BRUME", 3);
        vampComp.put("FAIM_IRREPRESSIBLE", 4);
        vampComp.put("MARQUE_TENEBREUSE", 2);
        vampComp.put("AFFAIBLISSEMENT_OCCULTE", 3);
        vampComp.put("PASSAGE_SECRET", 2);
        vampComp.put("AVIDITE_NOCTURNE", 3);
        vampComp.put("ADVANCED_TRANSMUTATION", 4);

        g.setVampActionsDeck(buildDeckFromComposition(vampComp));
        g.setVampActionsDiscard(new ArrayList<>());
    }

    private boolean allHuntersSelected(@NonNull Game g) {
        // On ne regarde QUE les chasseurs vivants
        var aliveHunters = getHunters(g).stream()
                .filter(this::isAlive)
                .toList();

        // S'il n’y a plus aucun chasseur vivant, ils ne doivent pas bloquer
        if (aliveHunters.isEmpty())
            return true;

        for (var h : aliveHunters) {
            if (!hasPlayed(g, h.getId()))
                return false;
        }
        return true;
    }

    private boolean allVampSideSelected(Game g) {
        var vamp = getVamp(g).orElseThrow();

        // Si le vampire est mort, il ne bloque pas
        boolean vampireOk = !isAlive(vamp) || hasPlayed(g, vamp.getId());

        boolean allServantsOk = g.getPlayers().stream()
                .filter(p -> "SERVANT".equals(p.getRole()))
                .filter(this::isAlive) // on ignore les servants KO pour ne pas bloquer
                .allMatch(p -> hasPlayed(g, p.getId()));

        return vampireOk && allServantsOk;
    }

    private void applyPhaseEntry(@NonNull Game g, @NonNull Phase to) {
        g.setPhase(to);

        switch (to) {
            case PHASE0 -> {
                initPhase0Structures(g);

                // Reset météo + messages de démarrage de raid
                g.setWeatherRoll(null);
                g.setWeatherStatus(null);
                g.setSecondaryWeatherStatus(null);
                g.setThirdWeatherStatus(null);
                g.setWeatherStatusNameFr(null);
                g.setWeatherDescriptionFr(null);
                g.setSecondaryWeatherStatusNameFr(null);
                g.setSecondaryWeatherDescriptionFr(null);
                g.setThirdWeatherStatusNameFr(null);
                g.setThirdWeatherDescriptionFr(null);
                g.setMessages(new ArrayList<>(List.of("Tirage météo ...")));

                // reset des effets one-shot de raid
                if (g.getTrackerHunters() == null) {
                    g.setTrackerHunters(new java.util.HashSet<>());
                } else {
                    g.getTrackerHunters().clear();
                }
                g.setProvokedTargetByEnemy(null);
                g.setAmbushHuntersByEnemy(null);
                if (g.getAmbushLocations() == null) {
                    g.setAmbushLocations(new java.util.HashSet<>());
                } else {
                    g.getAmbushLocations().clear();
                }
                g.setLaboratoryExplosionRoll(null);
                if (g.getInfrasToDestroyEndOfRaid() == null) {
                    g.setInfrasToDestroyEndOfRaid(java.util.EnumSet.noneOf(Infra.class));
                } else {
                    g.getInfrasToDestroyEndOfRaid().clear();
                }
                // Reset du bonus Charismatique au début de chaque raid
                if (g.getPlayers() != null) {
                    for (Player pl : g.getPlayers()) {
                        pl.setCharismaticThisRaid(false);
                    }
                }

                // --- RESET MARCHAND (perso) ---
                for (var pl : g.getPlayers()) {
                    pl.setMerchantPending(false);
                    pl.setMerchantRoll(null);
                    pl.setShopBonusKind(null);
                    pl.setShopBonusEquipId(null);
                    pl.setShopBonusEquipTier(null);
                    pl.setShopBonusBuyPending(false);
                }

                // Sécurité : si une currentAction marchand traîne encore
                var a = g.getCurrentAction();
                if (a != null
                        && ("MARCHAND_ITINERANT".equals(a.getMode()) || "MARCHAND_BONUS_BUY".equals(a.getMode()) ||
                                "ADVANCED_TRANSMUTATION".equals(a.getMode()) ||
                                "ADVANCED_TRANSMUTATION_BUY".equals(a.getMode()))) {
                    g.setCurrentAction(null);
                }

                g.setHunterActionsBlockedThisRaid(false);
                g.setClonesLocations(new ArrayList<>());
                g.setClonesBiteCapabilities(new ArrayList<>());
                g.setClonesFaceUp(false);
                g.setMirrorOwnerId(null);
                g.setMirrorAltLocations(null);
                g.setMirrorChosenLocation(null);
                g.setFogBlocksHunterHarvestThisRaid(false);
                g.setFogAffectedLocation(null);
                g.setHungerAllowsBiteThisRaid(false);
                g.setShopPricesIncreasedThisRaid(false);

                // Marque ténébreuse : les marqués restent, mais on reset le suivi “ce raid”
                if (g.getDarkMarkedHunters() == null) {
                    g.setDarkMarkedHunters(new java.util.HashSet<>());
                }
                if (g.getDarkMarkCorruptedThisRaid() == null) {
                    g.setDarkMarkCorruptedThisRaid(new java.util.HashSet<>());
                } else {
                    g.getDarkMarkCorruptedThisRaid().clear();
                }

                if (g.getActionCardsBoughtThisRaid() == null) {
                    g.setActionCardsBoughtThisRaid(new java.util.HashMap<>());
                } else {
                    g.getActionCardsBoughtThisRaid().clear();
                }

                for (Player p : g.getPlayers()) {
                    p.setElixirUsedThisRaid(false);
                    p.setCrateUsedThisRaid(false);
                    p.setResourceBoughtThisRaid(false);
                    p.setAdvancedTransmutationUsedThisRaid(false);
                    p.setMerchantUsedThisRaid(false);
                }

                g.setCurrentAction(null);

                g.setVampireTookDamageThisRaid(false);
                g.setPendingConstruction(null);
                g.setLocationEffectPending(false);
                g.setLocationEffectChoice(null);
                g.setBallroomDeathDance(false);
                g.setBallroomSneakAttack(false);
                g.setBallroomBloodWaltz(false);
                g.setBallroomBloodWaltzBestRoll(null);
                g.setBallroomBloodWaltzRolls(new java.util.ArrayList<>());

                resetAltarRaidFlags(g);

                if (g.getNetHunters() == null)
                    g.setNetHunters(new java.util.HashSet<>());
                else
                    g.getNetHunters().clear();

                if (g.getPitHunters() == null)
                    g.setPitHunters(new java.util.HashSet<>());
                else
                    g.getPitHunters().clear();

                // Purges/rafs “début de raid”
                if (g.getRaidMods() == null)
                    g.setRaidMods(new HashMap<>());
                for (var list : g.getRaidMods().values()) {
                    if (list != null) {
                        list.removeIf(m -> {
                            String s = m.getSource();
                            return s != null && (s.startsWith("POTION:") || s.startsWith("ACTION:"));
                        });
                    }
                }

                // Reconstruit les effets DSP persistants (ex: Épieu béni chargé)
                if (g.getPlayers() != null) {
                    for (Player pl : g.getPlayers()) {
                        if (pl.isBlessedStake()) {
                            addRaidMod(g, pl.getId(), "ATTACK", 0, "ACTION:BLESSED_STAKE:DSP");
                        }
                        if (pl.isSacredRosary()) {
                            addRaidMod(g, pl.getId(), "DEFENSE", 0, "ACTION:SACRED_ROSARY:DSP");
                        }
                    }
                }

                rebuildCorruptionMods(g);
                rebuildWeatherMods(g);
                rebuildEquipmentMods(g);

                if (g.getRaidEffects() == null)
                    g.setRaidEffects(new HashMap<>());
                g.getRaidEffects().clear();
                for (var p : g.getPlayers()) {
                    g.getRaidEffects().put(p.getId(), new RaidEffects());
                }

                g.setHarvestedRaid(null);
            }

            case PHASE1 -> {
                g.setMessages(new ArrayList<>(List.of("Les chasseurs planifient un raid…")));
            }

            case PHASE2 -> {
                g.setMessages(new ArrayList<>(List.of("Le vampire s’éveille…")));
            }

            case PREPHASE3 -> {
                // 1) Révéler
                for (var cb : g.getCenter())
                    cb.setFaceUp(true);
                g.setClonesFaceUp(true);

                // 0) Si une Image miroir est en attente de SETUP (venant de Phase2),
                // on la déclenche IMMÉDIATEMENT au début de la préphase.
                if ("IMAGE_MIROIR_SETUP".equals(g.getPendingVampireEscape())) {
                    Game.Action a = new Game.Action();
                    a.setMode("IMAGE_MIROIR_SETUP");
                    a.setOwnerId(getVamp(g).map(Player::getId).orElse(null));
                    a.setLocation(null);
                    a.setTargetId(null);
                    a.setRoll(null);
                    a.setBreakdownLines(new java.util.ArrayList<>());
                    a.setResolvedAtMillis(null);

                    g.setCurrentAction(a);
                    g.setPendingVampireEscape(null); // on le consomme pour lancer l'action
                }
                // Si une Image miroir a été préparée et pas encore résolue (cas de reprise ?)
                else if (g.getMirrorOwnerId() != null
                        && g.getMirrorAltLocations() != null
                        && !g.getMirrorAltLocations().isEmpty()
                        && g.getMirrorChosenLocation() == null) {

                    Game.Action a = new Game.Action();
                    a.setMode("IMAGE_MIROIR_RESOLVE");
                    a.setOwnerId(g.getMirrorOwnerId());
                    a.setLocation(null);
                    a.setTargetId(null);
                    a.setRoll(null);
                    a.setBreakdownLines(new java.util.ArrayList<>());
                    a.setResolvedAtMillis(null);
                    g.setCurrentAction(a);
                }

                // 2) Réinit "instable"
                g.getUnstableTargetByPlayer().clear();
                g.getUnstableEligibleTargets().clear();
                g.getUnstableHarvestLocByPlayer().clear();
                g.getUnstableEligibleLocations().clear();

                // 3) Tirage "instable" + messages
                var center = new ArrayList<String>();
                var history = new ArrayList<String>();
                for (var p : getHunters(g)) {
                    if (p.getCorruption() == 2) {
                        int roll = dice.roll(6);
                        addHistory(g, nameOf(g, p.getId()) + " — Corruption (instable) jet de d6 = " + roll + ".");
                        if (roll <= 3) {
                            var eligibleHunters = getHunters(g).stream()
                                    .filter(h -> !h.getId().equals(p.getId()))
                                    .filter(h -> h.getHp() > 0)
                                    .map(Player::getId).toList();
                            if (!eligibleHunters.isEmpty()) {
                                g.getUnstableEligibleTargets().put(p.getId(), new ArrayList<>(eligibleHunters));
                            }
                            g.getUnstableEligibleLocations().put(p.getId(),
                                    new ArrayList<>(List.of("forest", "quarry", "lake", "manor")));

                            history.add(nameOf(g, p.getId()) + " succombe à la corruption.");
                            history.add(nameOf(g, p.getId()) + " est sous contrôle du vampire ...");
                            center.add(nameOf(g, p.getId()) + " est sous contrôle du vampire ...");
                        } else {
                            String infoC = nameOf(g, p.getId()) + " résiste à la corruption.";
                            history.add(infoC);
                            center.add(infoC);
                        }
                    }
                }

                // On pose d’abord les messages de corruption...
                g.setMessages(center);
                for (var m : history)
                    addHistory(g, m);

                refreshPrephaseRevealMessages(g);

                // 4) Comportement "classique" de préphase :
                // - calcule hasUpcomingCombat
                // - readyForPhase3 auto pour les non-participants
                // - timer 30s ou avance rapide
                setupUnstableAndPrephaseTimeout(g);
            }

            case PHASE3 -> {
                if (g.getHarvestedRaid() == null || !g.getHarvestedRaid().equals(g.getRaid())) {
                    applyHarvests(g);
                    g.setHarvestedRaid(g.getRaid());
                }
                buildCombatsQueue(g);
                prepareNextRaidAction(g);
            }

            case PHASE4 -> {
                applyBleed(g);
                resolveAltarEndOfRaid(g);

                // Reset du set "prêt pour raid suivant"
                g.getReadyForNextRaid().clear();

                // Les joueurs morts sont considérés auto-prêts
                for (Player p : g.getPlayers()) {
                    if (p.getHp() <= 0) { // ou isAlive(p) si tu as un helper
                        g.getReadyForNextRaid().add(p.getId());
                    }
                }

                for (var cb : g.getCenter()) {
                    var p = g.getPlayers().stream().filter(pp -> pp.getId().equals(cb.getPlayerId())).findFirst()
                            .orElse(null);
                    if (p != null) {
                        p.getHand().add(cb.getCard());
                    }
                }
                g.getCenter().clear();

                // Détruire le labo s'il a été marqué pour destruction
                destroyRaidInfrasAtEnd(g);

                // gain ressource auto sawmill & mine
                var vampOpt = getVamp(g);
                if (vampOpt.isPresent() && g.getBuiltInfras() != null) {
                    Player vamp = vampOpt.get();

                    java.util.List<String> gains = new java.util.ArrayList<>();

                    if (g.getBuiltInfras().contains(Infra.SAWMILL)) {
                        grant(vamp, "wood", 1);
                        gains.add("+1 bois");
                    }
                    if (g.getBuiltInfras().contains(Infra.MINE)) {
                        grant(vamp, "iron", 1);
                        gains.add("+1 fer");
                    }

                    if (!gains.isEmpty()) {
                        String line = "Infrastructures — " + nameOf(g, vamp.getId())
                                + " reçoit " + String.join(" et ", gains) + " (bonus PHASE4).";
                        addHistory(g, line);
                    }
                }

                g.setMessages(new ArrayList<>(List.of("Maintenance…")));
                addHistory(g, "Maintenance…");

                long deadline = System.currentTimeMillis() + 60_000L;
                g.setPhase4DeadlineMillis(deadline);

                int raidForTimeout = g.getRaid(); // raid actuel
                schedulePhase4Timeout(g.getId(), 60_000L, raidForTimeout);
            }

            default -> {
                /* rien */ }
        }
    }

    /**
     * Prépare la PREPHASE3 :
     * - calcule s'il y a combat à venir et/ou des actions de préphase
     * intéressantes,
     * - initialise readyForPhase3 en marquant "auto-prêt" ceux qui n'ont
     * ni combat ni action jouable,
     * - lance soit le timer long de préphase, soit l'avance rapide vers PHASE3.
     */
    private void setupUnstableAndPrephaseTimeout(Game g) {
        // on NE lance PAS la préphase si on est en attente de SETUP.
        // Si c'est IMAGE_MIROIR_RESOLVE, ça veut dire que le choix est fait,
        // donc on doit laisser filer le timer (attente de fin de préphase).
        boolean mirrorPending = g.getMirrorOwnerId() != null
                && g.getMirrorAltLocations() != null
                && !g.getMirrorAltLocations().isEmpty()
                && g.getMirrorChosenLocation() == null;

        // Si pending escape est RESOLVE, alors on n'est PAS en attente de Setup
        if ("IMAGE_MIROIR_RESOLVE".equals(g.getPendingVampireEscape())) {
            mirrorPending = false;
        }

        // OU l'action est déjà setup (cas du applyPhaseEntry modifié)
        Game.Action cur = g.getCurrentAction();
        if (cur != null && "IMAGE_MIROIR_SETUP".equals(cur.getMode())) {
            mirrorPending = true;
        }

        if (mirrorPending) {
            if (g.getReadyForPhase3() != null) {
                g.getReadyForPhase3().clear();
            }
            g.setHasUpcomingCombat(false);
            return;
        }

        if (g.getReadyForPhase3() == null) {
            g.setReadyForPhase3(new java.util.HashSet<>());
        }

        boolean hasPendingUnstable = !(g.getUnstableEligibleTargets().isEmpty()
                && g.getUnstableEligibleLocations().isEmpty());

        boolean upcoming = computeHasUpcomingCombat(g);

        // 2) Incendiaire (chasseurs)
        boolean hasIncendiaire = false;
        for (Player p : g.getPlayers()) {
            if (!"HUNTER".equals(p.getRole()))
                continue;
            List<String> actions = p.getActions();
            if (actions == null || !actions.contains(Action.INCENDIAIRE.name()))
                continue;

            String loc = locationOf(g, p.getId());
            if (loc == null)
                continue;

            if (hasIncendiaireTargetsOnLocation(g, loc)) {
                hasIncendiaire = true;
                break;
            }
        }

        // 3) Voile de brume (vampire)
        boolean hasFogOption = false;
        for (Player p : g.getPlayers()) {
            if (!"VAMPIRE".equals(p.getRole()))
                continue;
            List<String> actions = p.getActions();
            if (actions != null && actions.contains(Action.VOILE_DE_BRUME.name())) {
                hasFogOption = true;
                break;
            }
        }

        // 4) Marque ténébreuse (vampire)
        boolean hasDarkMarkOption = false;
        for (Player p : g.getPlayers()) {
            if (!"VAMPIRE".equals(p.getRole()))
                continue;
            List<String> actions = p.getActions();
            if (actions != null && actions.contains(Action.MARQUE_TENEBREUSE.name())) {
                hasDarkMarkOption = true;
                break;
            }
        }

        // 5) Eau bénite (nouvelle logique : par joueur)
        boolean hasHolyWaterOption = false;
        for (Player p : g.getPlayers()) {
            if (hasUsableHolyWaterForPlayer(g, p)) {
                hasHolyWaterOption = true;
                break;
            }
        }

        // 6) Pieu béni (chasseurs)
        boolean hasBlessedStake = false;
        for (Player p : g.getPlayers()) {
            if (!"HUNTER".equals(p.getRole()))
                continue;
            List<String> actions = p.getActions();
            if (actions == null)
                continue;
            if (actions.contains(Action.BLESSED_STAKE.name()) && !p.isBlessedStake()) {
                hasBlessedStake = true;
                break;
            }
        }

        // 7) Chapelet sacré (chasseurs)
        boolean hasSacredRosary = false;
        for (Player p : g.getPlayers()) {
            if (!"HUNTER".equals(p.getRole()))
                continue;
            List<String> actions = p.getActions();
            if (actions == null)
                continue;
            if (actions.contains(Action.SACRED_ROSARY.name()) && !p.isSacredRosary()) {
                hasSacredRosary = true;
                break;
            }
        }

        // 8) Caisse abandonnée (chasseurs)
        boolean hasCrate = false;
        for (Player p : g.getPlayers()) {
            if (!"HUNTER".equals(p.getRole()))
                continue;
            if (p.isCrateUsedThisRaid())
                continue;
            List<String> actions = p.getActions();
            if (actions == null)
                continue;

            String loc = locationOf(g, p.getId());
            if (actions.contains(Action.CRATE_LAKE.name()) && "lake".equals(loc)) {
                hasCrate = true;
                break;
            }
            if (actions.contains(Action.CRATE_MANOR.name()) && "manor".equals(loc)) {
                hasCrate = true;
                break;
            }
        }

        // 9) Passage secret (vampire)
        boolean hasSecretPassageOption = hasUsableSecretPassageOption(g);
        boolean pendingEscape = g.getPendingVampireEscape() != null;

        // "quelque chose à faire en préphase" ?
        boolean hasPrephaseActivity = hasPendingUnstable
                || upcoming
                || hasIncendiaire
                || hasFogOption
                || hasDarkMarkOption
                || hasHolyWaterOption
                || hasBlessedStake
                || hasSacredRosary
                || hasCrate
                || hasSecretPassageOption
                || pendingEscape
                || (cur != null && ("CRATE_LAKE".equals(cur.getMode()) || "CRATE_MANOR".equals(cur.getMode())));

        // Sémantique étendue : vrai combat OU au moins une carte de préphase
        // intéressante
        g.setHasUpcomingCombat(
                upcoming
                        || hasIncendiaire
                        || hasFogOption
                        || hasDarkMarkOption
                        || hasHolyWaterOption
                        || hasBlessedStake
                        || hasSacredRosary
                        || hasCrate
                        || hasSecretPassageOption
                        || pendingEscape
                        || (cur != null
                                && ("CRATE_LAKE".equals(cur.getMode()) || "CRATE_MANOR".equals(cur.getMode()))));

        // --- Initialisation de readyForPhase3 ---
        g.getReadyForPhase3().clear();

        java.util.Set<String> participants = upcoming ? participantsOfUpcomingCombat(g) : java.util.Set.of();

        for (Player p : g.getPlayers()) {
            String pid = p.getId();

            // Morts : jamais besoin de cliquer
            if (!isAlive(p)) {
                g.getReadyForPhase3().add(pid);
                continue;
            }

            boolean mustClick = false;

            // 1) S'il participe au combat, il doit cliquer
            if (upcoming && participants.contains(pid)) {
                mustClick = true;
            }
            // 1b) Si une fuite vampire est en attente (SETUP), tout le monde doit être
            // attentif.
            // Si c'est déjà en RESOLVE, on ne bloque plus tout le monde (seulement ceux en
            // combat).
            if (pendingEscape && !"IMAGE_MIROIR_RESOLVE".equals(g.getPendingVampireEscape())) {
                mustClick = true;
            }

            // 2) Regarder s'il a au moins UNE action de préphase jouable
            boolean hasPrephaseAction = false;
            List<String> acts = p.getActions();

            if (acts != null && !acts.isEmpty()) {
                // --- Côté CHASSEUR ---
                if ("HUNTER".equals(p.getRole())) {
                    String loc = locationOf(g, pid);

                    // INCENDIAIRE
                    if (!hasPrephaseAction
                            && loc != null
                            && acts.contains(Action.INCENDIAIRE.name())
                            && hasIncendiaireTargetsOnLocation(g, loc)) {
                        hasPrephaseAction = true;
                    }

                    // EAU_BENITE : version *par joueur*
                    if (!hasPrephaseAction
                            && hasUsableHolyWaterForPlayer(g, p)) {
                        hasPrephaseAction = true;
                    }

                    // PIEU BÉNI
                    if (!hasPrephaseAction
                            && acts.contains(Action.BLESSED_STAKE.name())
                            && !p.isBlessedStake()) {
                        if (loc != null && hasIncendiaireTargetsOnLocation(g, loc)) {
                            hasPrephaseAction = true;
                        }
                    }

                    // CHAPELET SACRÉ : jouable en PREPHASE3 tant qu’il n’est pas déjà actif
                    if (!hasPrephaseAction
                            && acts.contains(Action.SACRED_ROSARY.name())
                            && !p.isSacredRosary()) {
                        hasPrephaseAction = true;
                    }

                    // CRATE_LAKE / CRATE_MANOR
                    if (!hasPrephaseAction) {
                        if (!p.isCrateUsedThisRaid()) {
                            String locC = locationOf(g, pid);
                            if (acts.contains(Action.CRATE_LAKE.name()) && "lake".equals(locC)) {
                                hasPrephaseAction = true;
                            } else if (acts.contains(Action.CRATE_MANOR.name()) && "manor".equals(locC)) {
                                hasPrephaseAction = true;
                            }
                        }
                        // OU déjà en cours de résolution par ce joueur
                        if (!hasPrephaseAction && cur != null
                                && ("CRATE_LAKE".equals(cur.getMode()) || "CRATE_MANOR".equals(cur.getMode()))
                                && pid.equals(cur.getOwnerId())) {
                            hasPrephaseAction = true;
                        }
                    }
                }

                // --- Côté VAMPIRE ---
                if ("VAMPIRE".equals(p.getRole())) {
                    if (g.getPendingVampireEscape() != null) {
                        hasPrephaseAction = true;
                    }

                    if (!hasPrephaseAction
                            && acts.contains(Action.VOILE_DE_BRUME.name())) {
                        hasPrephaseAction = true;
                    }

                    if (!hasPrephaseAction
                            && acts.contains(Action.MARQUE_TENEBREUSE.name())) {
                        hasPrephaseAction = true;
                    }

                    if (!hasPrephaseAction
                            && acts.contains(Action.PASSAGE_SECRET.name())
                            && hasUsableSecretPassageOption(g)) {
                        hasPrephaseAction = true;
                    }
                }
            }

            if (hasPrephaseAction) {
                mustClick = true;
            }

            // 3) Si pas de combat pour lui ET aucune action jouable → auto-ready
            if (!mustClick) {
                g.getReadyForPhase3().add(pid);
            }
        }

        // --- Timer comme avant ---
        if (hasPrephaseActivity) {
            // Si une caisse est en cours (mais pas encore résolue), on ne lance PAS le
            // timer
            // -> on attend que le joueur roll.
            if (cur != null && ("CRATE_LAKE".equals(cur.getMode()) || "CRATE_MANOR".equals(cur.getMode()))
                    && cur.getResolvedAtMillis() == null) {
                return;
            }

            int newVersion = g.getPrephaseTimerVersion() + 1;
            g.setPrephaseTimerVersion(newVersion);
            schedulePrephaseTimeout(g.getId(), 30_000, newVersion);
        } else {
            // aucun combat, aucune action préphase : on saute vite vers PHASE3
            scheduleAdvance(g.getId(), Phase.PREPHASE3, Phase.PHASE3, 4000);
        }
    }

    public Game advancePhase(String gameId, String userId, Phase to) {
        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing user id");

        tx.execute(status -> {
            Game g = findOr404(gameId);

            Phase cur = g.getPhase();
            if (cur == null)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "no current phase");

            if (cur == to) {
                // no-op idempotent: on est déjà à la phase demandée
                save(g);
                return g;
            }

            switch (cur) {
                case PHASE0 -> {
                    if (to != Phase.PHASE1)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    if (g.getWeatherRoll() == null)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "weather not rolled");
                    applyPhaseEntry(g, Phase.PHASE1);
                    g.setCurrentAction(null);
                }
                case PHASE1 -> {
                    if (to != Phase.PHASE2)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    if (!allHuntersSelected(g))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "hunters not all selected");
                    applyPhaseEntry(g, Phase.PHASE2);
                    g.setCurrentAction(null);
                }
                case PHASE2 -> {
                    if (to != Phase.PREPHASE3)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    if (!allVampSideSelected(g))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "vamp/servants not all selected");
                    g.setHasUpcomingCombat(computeHasUpcomingCombat(g));
                    applyPhaseEntry(g, Phase.PREPHASE3);
                    g.setCurrentAction(null);
                }
                case PREPHASE3 -> {
                    if (to != Phase.PHASE3)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");

                    boolean hasPendingUnstable = !(g.getUnstableEligibleTargets().isEmpty()
                            && g.getUnstableEligibleLocations().isEmpty());
                    if (hasPendingUnstable)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "unstable choices pending");

                    if (!allReadyForPhase3(g))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "players not ready");

                    // Fin de PREPHASE3 unifiée (effets de lieu ou PHASE3 directe)
                    finishPrephaseAndMaybeStartActionResolve(g, gameId);

                    // Toute la persistance + events sont gérés dans le helper
                    return null;
                }
                case PHASE3 -> {
                    if (to != Phase.PHASE4)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    resolveInfraConstruction(g);
                    applyPhaseEntry(g, Phase.PHASE4);
                    applyBankBonusOnPhase4Entry(g);
                    g.setCurrentAction(null);
                    purgeTransientRaidMods(g);
                }
                case PHASE4 -> {
                    if (to != Phase.PHASE0)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");

                    if (g.getActionCardsBoughtThisRaid() != null) {
                        g.getActionCardsBoughtThisRaid().clear();
                    }

                    applyPhaseEntry(g, Phase.PHASE0);
                    g.setCurrentAction(null);
                }
                default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
            }

            save(g);

            // Variables capturées pour le post-commit
            final boolean flipCenter = (to == Phase.PREPHASE3);

            afterCommit(() -> {
                Game fresh = findOr404(gameId);
                if (flipCenter) {
                    // si tu utilises encore cet event pour l’anim de flip
                    live.centerRevealed(fresh);
                }
                // heartbeat pour forcer un GET propre chez tous les clients
                live.phaseChanged(fresh);
            });

            return null;
        });

        // Renvoie un état frais (optionnel, mais pratique côté API REST)
        return findOr404(gameId);
    }

    private void scheduleAdvance(String gameId, Phase expected, Phase target, long delayMs) {
        raidScheduler.schedule(() -> tx.execute(status -> {
            Game g = findOr404(gameId);

            // Garde-fous
            if (g.getStatus() != GameStatus.ACTIVE)
                return null;
            if (g.getPhase() != expected)
                return null;

            // Cas particulier : avance rapide PREPHASE3 -> PHASE3
            if (expected == Phase.PREPHASE3 && target == Phase.PHASE3) {
                // Ici on ne regarde PAS readyForPhase3 :
                // c'est le chemin "auto" quand pas de combats/instables.
                finishPrephaseAndMaybeStartActionResolve(g, gameId);
                return null;
            }

            // Mutations normales
            applyPhaseEntry(g, target);
            save(g);

            // Tous les events APRÈS COMMIT
            afterCommit(() -> {
                Game fresh = findOr404(gameId);

                if (target == Phase.PREPHASE3) {
                    // d'abord le flip
                    live.centerRevealed(fresh);
                }
                if (target == Phase.PHASE0) {
                    // on vient de purger les mods météo (weather=null)
                    live.raidModsUpdated(fresh);
                }

                // heartbeat pour déclencher le GET coté front
                live.phaseChanged(fresh);
            });

            return null;
        }), java.time.Instant.now().plusMillis(delayMs));
    }

    private void schedulePrephaseTimeout(String gameId, long millis, int expectedVersion) {
        raidScheduler.schedule(() -> tx.execute(status -> {
            Game g2 = findOr404(gameId);

            // Garde-fous : partie active + toujours en PREPHASE3 ?
            if (g2.getStatus() != GameStatus.ACTIVE || g2.getPhase() != Phase.PREPHASE3) {
                return null; // rien à faire
            }

            // Timer périmé ?
            if (g2.getPrephaseTimerVersion() != expectedVersion) {
                return null;
            }

            // Si des choix "instable" restent en attente, on NE force pas
            boolean hasPendingUnstable = !(g2.getUnstableEligibleTargets().isEmpty()
                    && g2.getUnstableEligibleLocations().isEmpty());
            if (hasPendingUnstable) {
                return null;
            }

            // Si un effet de lieu est déjà en cours, on ne touche à rien :
            // scheduleNextLocationEffect finira le travail.
            if (g2.getLocationEffectPending()
                    && g2.getLocationEffectsQueue() != null
                    && g2.getCurrentLocationEffectIndex() != null) {

                int idx = g2.getCurrentLocationEffectIndex();
                if (idx >= 0 && idx < g2.getLocationEffectsQueue().size()) {
                    return null;
                }
            }

            // À partir d'ici :
            // - plus d'instables en attente
            // - aucun effet de lieu en cours
            // → on applique la même logique que pour un clic manuel :
            // effets de lieu s'il y en a, sinon PHASE3 directe.
            finishPrephaseAndMaybeStartActionResolve(g2, gameId);

            return null;
        }), java.time.Instant.now().plusMillis(millis));
    }

    private void schedulePhase4Timeout(String gameId, long millis, int expectedRaid) {
        raidScheduler.schedule(() -> tx.execute(status -> {
            Game g2 = findOr404(gameId);

            // 1) Partie toujours active ?
            if (g2.getStatus() != GameStatus.ACTIVE)
                return null;

            // 2) Toujours en PHASE4 ?
            if (g2.getPhase() != Phase.PHASE4)
                return null;

            // 3) Toujours le même raid que celui pour lequel ce timer a été posé ?
            if (g2.getRaid() != expectedRaid)
                return null;

            // là seulement on clôt la maintenance de CE raid
            g2.setRaid(g2.getRaid() + 1);
            applyPhaseEntry(g2, Phase.PHASE0);
            save(g2);

            afterCommit(() -> {
                Game fresh = findOr404(gameId);
                live.phaseChanged(fresh);
            });

            return null;
        }),
                java.time.Instant.now().plusMillis(millis));
    }

    private void scheduleNextLocationEffect(String gameId) {
        raidScheduler.schedule(() -> tx.execute(status -> {
            Game g = findOr404(gameId);
            if (g.getStatus() != GameStatus.ACTIVE)
                return null;
            if (g.getPhase() != Phase.PREPHASE3)
                return null;
            if (g.getLocationEffectsQueue() == null)
                return null;

            Integer idxObj = g.getCurrentLocationEffectIndex();
            int idx = (idxObj == null ? -1 : idxObj);
            int nextIndex = idx + 1;

            if (nextIndex >= g.getLocationEffectsQueue().size()) {
                // Fini : plus aucun effet de lieu à traiter
                g.setCurrentLocationEffectIndex(null);
                g.setLocationEffectPending(false);
                g.setLocationEffectChoice(null);

                // Maintenant seulement, on passe en PHASE3
                applyPhaseEntry(g, Phase.PHASE3);

                save(g);

                afterCommit(() -> {
                    Game fresh = findOr404(gameId);
                    live.phaseChanged(fresh);
                });

                return null;
            }

            // Sinon : on passe à l'effet suivant
            g.setCurrentLocationEffectIndex(nextIndex);
            g.setLocationEffectPending(true);
            g.setLocationEffectChoice(null);

            Game.LocationEffectInstance inst = g.getLocationEffectsQueue().get(nextIndex);
            save(g);

            afterCommit(() -> {
                Game fresh = findOr404(gameId);
                live.locationEffectStarted(fresh, inst);
                live.phaseChanged(fresh);
            });

            return null;
        }),
                java.time.Instant.now().plusMillis(5000L) // 5 secondes d’affichage du choix précédent
        );
    }

    // ---------- Sélection lieu ----------
    @Transactional
    public Game selectLocation(String gameId, String playerId, String card) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        var p = g.getPlayers().stream()
                .filter(pp -> pp.getId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "not a player of this game"));

        // Vérifs phase / rôle
        switch (g.getPhase()) {
            case PHASE0 -> throw new ResponseStatusException(HttpStatus.CONFLICT, "weather selection in progress");
            case PHASE1 -> {
                if (!"HUNTER".equals(p.getRole()))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters phase");
            }
            case PHASE2 -> {
                String role = p.getRole();
                if (!"VAMPIRE".equals(role) && !"SERVANT".equals(role))
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire/servant phase");

                // Fumigation d'ail : vampire/serviteurs ne peuvent pas aller sur un lieu fumigé
                if (g.getGarlicBlockedLocations() != null
                        && g.getGarlicBlockedLocations().contains(card)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "ce lieu est protégé par une fumigation d'ail");
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "not a selection phase");
        }

        if (hasPlayed(g, playerId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already selected this round");

        var hand = p.getHand();
        if (hand == null || !hand.remove(card))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "card not in hand");

        // Faut-il appliquer une Fumigation sur ce lieu ?
        boolean fumigate = g.getPendingGarlicPlayers() != null
                && g.getPendingGarlicPlayers().remove(playerId);

        // Pose au centre : faceUp = fumigation OU corruption 1/2 (sinon cachée)
        boolean faceUp = fumigate;

        CenterBoard cb = new CenterBoard(playerId, card, faceUp);
        g.getCenter().add(cb);

        // Si fumigation : on bloque le lieu pour le vampire + historique lisible
        if (fumigate) {
            if (g.getGarlicBlockedLocations() == null) {
                g.setGarlicBlockedLocations(new java.util.HashSet<>());
            }
            g.getGarlicBlockedLocations().add(card);

            addHistory(g, nameOf(g, playerId) +
                    " protège " + labelLieuFr(card) + " par une fumigation d’ail.");
        }

        // ➜ Si c'est le vampire en PHASE2, on applique Pisteur
        if (g.getPhase() == Phase.PHASE2 && "VAMPIRE".equals(p.getRole())) {
            applyTrackerHuntersWhenVampirePlays(g, card);
        }

        // Flags d’auto-advance (calculés AVANT le commit)
        boolean advanceToP2 = (g.getPhase() == Phase.PHASE1) && allHuntersSelected(g);
        boolean advanceToPre3 = (g.getPhase() == Phase.PHASE2) && allVampSideSelected(g);

        if (advanceToPre3) {
            g.setHasUpcomingCombat(computeHasUpcomingCombat(g));
        }

        // ----- COMMIT -----
        save(g);

        final String gid = g.getId();
        afterCommit(() -> {
            live.locationSelected(g, playerId, card);
        });

        // ----- AUTO-ADVANCE (scheduler) -----
        if (advanceToP2) {
            scheduleAdvance(gid, Phase.PHASE1, Phase.PHASE2, 2500);
        } else if (advanceToPre3) {
            scheduleAdvance(gid, Phase.PHASE2, Phase.PREPHASE3, 2500);
        }

        return g;
    }

    // Regroupe les joueurs par lieu posé au centre (délégué au modèle).
    @NonNull
    private Map<String, List<Player>> groupPlayersByLocation(@NonNull Game g) {
        return g.playersByLocation();
    }

    private String locationOf(Game g, String playerId) {
        return g.locationOf(playerId);
    }

    private java.util.List<Player> playersOnLocation(Game g, String location) {
        return g.playersOn(location);
    }

    private java.util.List<Player> vampSideOnLocation(Game g, String location) {
        return g.vampSideOn(location);
    }

    @NonNull
    private List<String> buildRevealMessages(Game g) {
        List<String> out = new ArrayList<>();

        var groups = groupPlayersByLocation(g);

        // chasseurs instables à choix (ne récoltent pas ici)
        var instablePending = new java.util.HashSet<String>();
        if (g.getUnstableEligibleTargets() != null) {
            instablePending.addAll(g.getUnstableEligibleTargets().keySet());
        }
        if (g.getUnstableEligibleLocations() != null) {
            instablePending.addAll(g.getUnstableEligibleLocations().keySet());
        }

        // Lieux des clones
        java.util.Set<String> clonesSet = new java.util.HashSet<>();
        if (g.getClonesLocations() != null) {
            clonesSet.addAll(g.getClonesLocations());
        }

        for (var e : groups.entrySet()) {
            String loc = e.getKey();
            List<Player> onLoc = e.getValue();

            var enemiesHere = onLoc.stream()
                    .filter(p -> "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .toList();

            var huntersHere = onLoc.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .toList();

            // Monstres présents sur ce lieu
            var monstersHere = monstersOnLocation(g, loc).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            boolean clonesHere = clonesSet.contains(loc);

            boolean combatHere = (!enemiesHere.isEmpty() || !monstersHere.isEmpty() || clonesHere)
                    && !huntersHere.isEmpty();

            // Récoltes
            for (var p : onLoc) {
                boolean isHunter = "HUNTER".equals(p.getRole());
                if (isHunter && instablePending.contains(p.getId())) {
                    continue;
                }
                if (isHunter && g.isFogBlocksHunterHarvestThisRaid()) {
                    continue;
                }
                String who;
                if ("VAMPIRE".equals(p.getRole())) {
                    who = "le vampire (" + nameOf(g, p.getId()) + ")";
                } else if ("SERVANT".equals(p.getRole())) {
                    who = "le serviteur (" + nameOf(g, p.getId()) + ")";
                } else {
                    who = nameOf(g, p.getId());
                }
                out.add("Récolte de " + labelLieuFr(loc) + " par " + who);
            }
            if (combatHere) {
                String huntersNames = String.join(", ",
                        huntersHere.stream().map(h -> {
                            String n = h.getUsername();
                            return (n != null && !n.isBlank()) ? n : h.getId();
                        }).toList());

                // Combats contre vampires / serviteurs
                for (var enemy : enemiesHere) {
                    String enemyName = nameOf(g, enemy.getId());
                    out.add("Combat — " + enemyName + " VS " + huntersNames + " à " + labelLieuFr(loc));
                    out.add("Récolte de " + huntersNames + " divisée par 2");
                }

                // Combats contre monstres (les clones restent traités plus bas)
                for (var m : monstersHere) {
                    String enemyName = monsterNameFr(m.type);
                    out.add("Combat — " + enemyName + " VS " + huntersNames + " à " + labelLieuFr(loc));
                    out.add("Récolte de " + huntersNames + " divisée par 2");
                }
            }
        }

        // --- CLONES DES OMBRES : combats supplémentaires pour l’aperçu ---
        var clonesLocs = g.getClonesLocations();
        if (clonesLocs != null && !clonesLocs.isEmpty()) {

            for (String loc : clonesLocs) {
                var onLoc = groups.get(loc);
                if (onLoc == null || onLoc.isEmpty())
                    continue;

                var huntersHere = onLoc.stream()
                        .filter(p -> "HUNTER".equals(p.getRole()))
                        .filter(p -> p.getHp() > 0)
                        .toList();

                if (huntersHere.isEmpty())
                    continue;

                String huntersNames = String.join(", ",
                        huntersHere.stream().map(h -> {
                            String n = h.getUsername();
                            return (n != null && !n.isBlank()) ? n : h.getId();
                        }).toList());

                out.add("Combat — clones d'ombre VS "
                        + huntersNames + " à " + labelLieuFr(loc));
                out.add("Récolte de " + huntersNames + " divisée par 2");
            }
        }

        if (out.isEmpty())
            out.add("Aucune carte jouée.");
        return out;
    }

    private void refreshPrephaseRevealMessages(Game g) {
        if (g.getPhase() != Phase.PREPHASE3) {
            return; // sécurité
        }

        List<String> base = new ArrayList<>();

        if (g.getMessages() != null) {
            for (String m : g.getMessages()) {
                if (m == null)
                    continue;
                if (m.startsWith("Combat — ") || m.startsWith("Récolte de ")) {
                    // ancienne preview -> on la remplace
                    continue;
                }
                base.add(m);
            }
        }

        // Rebuild preview à partir de l'état courant
        base.addAll(buildRevealMessages(g));
        g.setMessages(base);
    }

    // Mini label FR pour l’affichage des lieux
    private String labelLieuFr(@NonNull String c) {
        Location loc = Location.fromCode(c);
        return (loc != null) ? loc.labelFr() : c;
    }

    // Tout le monde prêt pour PHASE3 ?
    /**
     * Tous les joueurs "vivants" sont-ils prêts pour passer en PHASE3 ?
     * On se base UNIQUEMENT sur readyForPhase3, pour éviter les divergences
     * avec la logique front.
     */
    private boolean allReadyForPhase3(@NonNull Game g) {
        if (g.getPlayers() == null || g.getPlayers().isEmpty()) {
            return true;
        }

        java.util.Set<String> ready = g.getReadyForPhase3();
        if (ready == null) {
            return false;
        }

        for (Player p : g.getPlayers()) {
            if (!isAlive(p)) {
                // mort -> ne doit PAS cliquer
                continue;
            }
            if (!ready.contains(p.getId())) {
                return false;
            }
        }

        return true;
    }

    @Transactional
    public Game skipAction(String gameId, String playerId) {
        Game g = findOr404(gameId);

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PREPHASE3");

        boolean present = g.getPlayers().stream().anyMatch(p -> p.getId().equals(playerId));
        if (!present)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not a player of this game");

        // 1) Marque le joueur comme "prêt"
        if (g.getReadyForPhase3() == null) {
            g.setReadyForPhase3(new java.util.HashSet<>());
        }
        g.getReadyForPhase3().add(playerId);
        int ready = g.getReadyForPhase3().size();
        int total = g.getPlayers().size();

        // 2) Décide si on avance maintenant
        boolean hasPendingUnstable = !(g.getUnstableEligibleTargets().isEmpty()
                && g.getUnstableEligibleLocations().isEmpty());

        boolean advanceNow = (g.getPhase() == Phase.PREPHASE3)
                // On ignore les instables si on attend la résolution Image Miroir (comme le
                // Timer)
                && (!hasPendingUnstable || "IMAGE_MIROIR_RESOLVE".equals(g.getPendingVampireEscape()))
                && allReadyForPhase3(g);

        if (advanceNow) {
            // NE PLUS appeler applyPhaseEntry(PHASE3) ici
            // → on passe par le helper unifié qui gère les effets de lieu
            finishPrephaseAndMaybeStartActionResolve(g, gameId);
            // Toute la persistance + events (phaseChanged, locationEffectStarted, etc.)
            // sont gérés dans ce helper, donc on n'ajoute PAS d'autres afterCommit ici.
            return g;
        }

        // 3) Personne n'est encore "le dernier prêt" → on ne fait qu'un heartbeat
        save(g);

        final int fReady = ready, fTotal = total;
        final String fPid = playerId;

        afterCommit(() -> {
            // On notifie juste la mise à jour du compteur "prêt"
            live.readyUpdated(g, fPid, fReady, fTotal);
            // Pas de phaseChanged ici, la phase reste PREPHASE3
        });

        return g;
    }

    @Transactional
    public Game finishTrade(String gameId, String userId) {
        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        // 1) Marquer le joueur prêt (pas de setter, on ajoute dans le set final)
        g.getReadyForNextRaid().add(userId);

        // 2) Annuler TOUTES les propositions où je suis impliqué
        var toDelete = new java.util.ArrayList<Game.Trade>();
        record DeletedTrade(String id, String aId, String bId) {
        }
        var deleted = new java.util.ArrayList<DeletedTrade>();

        for (var t : new java.util.ArrayList<>(g.getTrades())) {
            boolean iAmA = userId.equals(t.getAId());
            boolean iAmB = userId.equals(t.getBId());
            if (!iAmA && !iAmB)
                continue;

            if (iAmA)
                t.setStatusA("CANCELLED");
            else
                t.setStatusB("CANCELLED");
            t.setUpdatedAt(System.currentTimeMillis());

            boolean aFinal = isFinal(t.getStatusA());
            boolean bFinal = isFinal(t.getStatusB());
            if (aFinal && bFinal) {
                deleted.add(new DeletedTrade(t.getId(), t.getAId(), t.getBId()));
                toDelete.add(t);
            }
        }
        g.getTrades().removeAll(toDelete);

        // 3) Tout le monde est prêt → uniquement les joueurs VIVANTS
        java.util.Set<String> aliveIds = g.getPlayers().stream()
                .filter(p -> p.getHp() > 0) // ou isAlive(p)
                .map(Player::getId)
                .collect(java.util.stream.Collectors.toSet());

        boolean everyone = aliveIds.isEmpty()
                || aliveIds.stream().allMatch(pid -> g.getReadyForNextRaid().contains(pid));

        if (everyone) {
            g.setRaid(g.getRaid() + 1);
            applyPhaseEntry(g, Phase.PHASE0); // ta logique standard de réinit de raid
        }

        // 4) Commit
        save(g);

        // 5) Lives après commit
        final boolean fEveryone = everyone;
        afterCommit(() -> {
            // informer les paires touchées encore présentes (CANCELLED d'un seul côté)
            for (var t : g.getTrades()) {
                if (userId.equals(t.getAId()) || userId.equals(t.getBId())) {
                    live.tradeSync(g, t);
                }
            }
            // informer des suppressions (les 2 côtés finals)
            for (var dt : deleted) {
                live.tradeDeleted(
                        g, dt.id(), dt.aId(), dt.bId(),
                        "FINISH_PHASE4",
                        "CLOSED",
                        Map.of());
            }
            // notifier “prêt” pour PHASE4 (évènement distinct de la préphase)
            live.phase4ReadyUpdated(g, userId, g.getReadyForNextRaid().size(), g.getPlayers().size());

            if (fEveryone)
                live.phaseChanged(g);
        });

        return g;
    }

    // Fight
    /**
     * Construit la file de duels (PHASE3) :
     * - pour chaque lieu où vampire + ≥1 chasseur: push Hunter->Vamp puis
     * Vamp->Hunter.
     * - ajoute ensuite les duels "instable -> cible" enregistrés en PREPHASE3.
     * Initialise currentCombat et currentCombatIndex si la file n’est pas vide.
     */
    private void buildCombatsQueue(Game g) {
        g.getCombatsQueue().clear();

        // Ensemble des joueurs instables déjà réaffectés (attaque ou récolte)
        var unstableAssigned = new java.util.HashSet<String>();
        if (g.getUnstableTargetByPlayer() != null) {
            unstableAssigned.addAll(g.getUnstableTargetByPlayer().keySet());
        }
        if (g.getUnstableHarvestLocByPlayer() != null) {
            unstableAssigned.addAll(g.getUnstableHarvestLocByPlayer().keySet());
        }

        var groups = groupPlayersByLocation(g);

        // 1) Combats par défaut : (ennemi ∈ {VAMPIRE, SERVANT, MONSTRE}) × (HUNTER non
        // réaffecté)
        for (var e : groups.entrySet()) {
            String loc = e.getKey();
            java.util.List<Player> onLoc = e.getValue();

            var enemiesPlayers = onLoc.stream()
                    .filter(p -> "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                    .filter(p -> isAlive(p))
                    .toList();

            var huntersForDefault = onLoc.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> isAlive(p))
                    .filter(p -> !unstableAssigned.contains(p.getId()))
                    .toList();

            // Monstres présents sur ce lieu
            var monstersHere = monstersOnLocation(g, loc).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            // (A) Combats contre vampires/serviteurs (comme avant)
            for (var enemy : enemiesPlayers) {
                for (var h : huntersForDefault) {

                    // Marque ténébreuse : +1 corruption si chasseur marqué croise le vampire
                    if ("VAMPIRE".equals(enemy.getRole()) && isDarkMarked(g, h.getId())) {
                        applyDarkMarkCorruptionOncePerRaid(g, h);
                    }

                    boolean hunterInvisible = hasInvisibility(g, h.getId());
                    boolean enemyInvisible = hasInvisibility(g, enemy.getId());

                    if (hunterInvisible && !enemyInvisible) {
                        // Le chasseur invisible attaque une fois, défenseur sans dé
                        RoundFight r1 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, h.getId(), enemy.getId());
                        r1.setDefenderRoll(0); // l'ennemi ne lance pas de dé de défense
                        g.getCombatsQueue().add(r1);

                    } else if (enemyInvisible && !hunterInvisible) {
                        // L'ennemi invisible attaque une fois, chasseur sans dé
                        RoundFight r2 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, enemy.getId(), h.getId());
                        r2.setDefenderRoll(0);
                        g.getCombatsQueue().add(r2);

                    } else {
                        // Deux rounds aller/retour
                        RoundFight r1 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, h.getId(), enemy.getId());
                        if (hunterInvisible) {
                            r1.setDefenderRoll(0);
                        }
                        g.getCombatsQueue().add(r1);

                        RoundFight r2 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, enemy.getId(), h.getId());
                        if (enemyInvisible) {
                            r2.setDefenderRoll(0);
                        }
                        g.getCombatsQueue().add(r2);
                    }
                }
            }

            // (B) Combats contre monstres
            for (var m : monstersHere) {
                for (var h : huntersForDefault) {

                    boolean hunterInvisible = hasInvisibility(g, h.getId());
                    // Les monstres n'ont pas (encore) de potions → jamais invisibles

                    if (hunterInvisible) {
                        // Le chasseur est invisible :
                        // il attaque une fois, le monstre ne lance PAS de dé de défense.
                        RoundFight r1 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, h.getId(), m.id);
                        r1.setDefenderRoll(0);
                        g.getCombatsQueue().add(r1);

                    } else {
                        // Cas classique : 2 rounds aller/retour
                        RoundFight r1 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, h.getId(), m.id);
                        g.getCombatsQueue().add(r1);

                        RoundFight r2 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, m.id, h.getId());
                        g.getCombatsQueue().add(r2);
                    }
                }
            }
        }

        // 2) Duels "instable -> cible"
        if (g.getUnstableTargetByPlayer() != null) {
            for (var entry : g.getUnstableTargetByPlayer().entrySet()) {
                String unstableId = entry.getKey();
                String targetId = entry.getValue();

                String loc = g.getCenter().stream()
                        .filter(cb -> cb.getPlayerId().equals(targetId))
                        .map(CenterBoard::getCard)
                        .findFirst()
                        .orElse("forest");

                RoundFight duel = new RoundFight(
                        java.util.UUID.randomUUID().toString(), loc, unstableId, targetId);
                // Si l’instable a bu Invisibilité, sa cible ne lancera pas de dé
                if (hasInvisibility(g, unstableId)) {
                    duel.setDefenderRoll(0);
                }
                g.getCombatsQueue().add(duel);

                // Message lisible
                String info = "Combat — " + nameOf(g, unstableId) + " VS "
                        + nameOf(g, targetId) + " à " + labelLieuFr(loc);
                if (g.getMessages() == null)
                    g.setMessages(new java.util.ArrayList<>());
                g.getMessages().add(info);
                addHistory(g, info);
            }
        }

        // 3) Clones des ombres : attaques supplémentaires du vampire
        var clonesLocs = g.getClonesLocations();
        if (clonesLocs != null && !clonesLocs.isEmpty()) {

            var vampOpt = getVamp(g);
            if (vampOpt.isPresent()) {
                Player vamp = vampOpt.get();
                String vampId = vamp.getId();
                boolean vampInvisible = hasInvisibility(g, vampId);

                for (int i = 0; i < clonesLocs.size(); i++) {
                    String loc = clonesLocs.get(i);
                    boolean canBite = false;
                    if (g.getClonesBiteCapabilities() != null && i < g.getClonesBiteCapabilities().size()) {
                        canBite = Boolean.TRUE.equals(g.getClonesBiteCapabilities().get(i));
                    }

                    var onLoc = groups.get(loc);
                    if (onLoc == null || onLoc.isEmpty())
                        continue;

                    var huntersHere = onLoc.stream()
                            .filter(p -> "HUNTER".equals(p.getRole()))
                            .filter(p -> isAlive(p))
                            // on réutilise la même logique que pour les combats par défaut
                            .filter(p -> !unstableAssigned.contains(p.getId()))
                            .toList();

                    for (Player h : huntersHere) {
                        boolean hunterInvisible = hasInvisibility(g, h.getId());

                        RoundFight cloneFight = new RoundFight(
                                java.util.UUID.randomUUID().toString(),
                                loc,
                                vampId,
                                h.getId());
                        cloneFight.setCloneAttack(true); // on marque le round comme “clone”
                        cloneFight.setCanBite(canBite);

                        // si vampire invisible && pas le chasseur → pas de dé de défense
                        if (vampInvisible && !hunterInvisible) {
                            cloneFight.setDefenderRoll(0);
                        }

                        g.getCombatsQueue().add(cloneFight);
                    }
                }
            }
        }

        // 4) Provocation : certains ennemis ne peuvent attaquer qu'un chasseur précis
        var provokedMap = g.getProvokedTargetByEnemy();
        if (provokedMap != null && !provokedMap.isEmpty()) {
            java.util.List<RoundFight> filtered = new java.util.ArrayList<>();
            for (RoundFight rf : g.getCombatsQueue()) {
                String forcedDef = provokedMap.get(rf.getAttackerId());
                if (forcedDef == null) {
                    // attaquant non provoqué : round intact
                    filtered.add(rf);
                } else {
                    // attaquant provoqué : ne conserver que les attaques vers le chasseur
                    // provoquant
                    if (forcedDef.equals(rf.getDefenderId())) {
                        filtered.add(rf);
                    } else {
                        // attaque vers un autre défenseur : annulée
                    }
                }
            }
            g.setCombatsQueue(filtered);
        }

        // 5) Embuscade : la cible ne peut pas riposter contre les chasseurs embusqués
        var ambushMap = g.getAmbushHuntersByEnemy();
        if (ambushMap != null && !ambushMap.isEmpty()) {
            java.util.List<RoundFight> filtered2 = new java.util.ArrayList<>();
            for (RoundFight rf : g.getCombatsQueue()) {
                java.util.List<String> ambushHunters = ambushMap.get(rf.getAttackerId());
                if (ambushHunters == null) {
                    // attaquant non ciblé par Embuscade → round intact
                    filtered2.add(rf);
                } else {
                    // attaquant = la cible de l'Embuscade
                    boolean defIsAmbushHunter = ambushHunters.contains(rf.getDefenderId());

                    // On annule uniquement les ripostes "classiques" (pas les clones d'ombre)
                    if (defIsAmbushHunter && !rf.isCloneAttack()) {
                        // riposte annulée
                    } else {
                        filtered2.add(rf);
                    }
                }
            }
            g.setCombatsQueue(filtered2);
        }

        // 6) appliquer Potion de rapidité
        if (g.getRaidEffects() != null && !g.getRaidEffects().isEmpty()) {
            java.util.List<RoundFight> expanded = new java.util.ArrayList<>();
            for (RoundFight rf : g.getCombatsQueue()) {
                // round original inchangé
                expanded.add(rf);

                RaidEffects fx = g.getRaidEffects().get(rf.getAttackerId());
                if (fx != null && fx.isRapid()) {
                    // on duplique le round pour cet attaquant
                    RoundFight extra = new RoundFight(
                            java.util.UUID.randomUUID().toString(),
                            rf.getLocation(),
                            rf.getAttackerId(),
                            rf.getDefenderId());
                    // Marquer que c’est la 2ᵉ attaque (Potion de rapidité)
                    extra.setRapidExtra(true);

                    // Si l’attaquant a aussi Invisibilité, le round dupliqué
                    // a lui aussi un défenseur qui ne lance pas de dé
                    if (fx.isInvisible()) {
                        extra.setDefenderRoll(0);
                    }
                    expanded.add(extra);
                }
            }
            g.setCombatsQueue(expanded);
        }

        // 7) Pointeur sur le combat courant (plus aucun nextAdvanceAt/timer côté
        // serveur)
        if (!g.getCombatsQueue().isEmpty()) {
            g.setCurrentCombatIndex(0);
            g.setCurrentCombat(g.getCombatsQueue().get(0));
        } else {
            g.setCurrentCombatIndex(null);
            g.setCurrentCombat(null);
        }
    }

    private void appendBreakdown(RoundFight r, String line) {
        if (line == null || line.isBlank())
            return;
        if (r.getBreakdownLines() == null) {
            r.setBreakdownLines(new java.util.ArrayList<>());
        }
        r.getBreakdownLines().add(line);
    }

    @Transactional
    public Game rollDice(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE
                || g.getPhase() != Phase.PHASE3
                || g.getCurrentCombat() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in combat");
        }
        if (g.getCurrentBite() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "bite pending");
        }

        var r = g.getCurrentCombat();

        // --- Init du breakdown : on ne reset que si le duel démarre vraiment ---
        // (aucun jet encore posé pour ce RoundFight)
        boolean duelJustStarted = r.getAttackerRoll() == null
                && r.getDefenderRoll() == null
                && r.getAttackerFirstRoll() == null
                && r.getDefenderFirstRoll() == null;

        if (duelJustStarted) {
            if (r.getBreakdownLines() == null) {
                r.setBreakdownLines(new java.util.ArrayList<>());
            } else {
                r.getBreakdownLines().clear();
            }
        }

        // --- Joueur ou monstre ? ---
        Player attackerPlayer0 = g.getPlayers().stream()
                .filter(p -> p.getId().equals(r.getAttackerId()))
                .findFirst()
                .orElse(null);

        Player defenderPlayer0 = g.getPlayers().stream()
                .filter(p -> p.getId().equals(r.getDefenderId()))
                .findFirst()
                .orElse(null);

        Game.Monster attackerMonster = findMonster(g, r.getAttackerId());
        Game.Monster defenderMonster = findMonster(g, r.getDefenderId());

        boolean attackerIsPlayer = (attackerPlayer0 != null);
        boolean defenderIsPlayer = (defenderPlayer0 != null);
        boolean attackerIsMonster = (attackerMonster != null);
        boolean defenderIsMonster = (defenderMonster != null);

        // Effets de raid pour CE joueur (dont Potion de focalisation)
        RaidEffects fx = (g.getRaidEffects() != null) ? g.getRaidEffects().get(userId) : null;
        boolean hasFocus = (fx != null && fx.isFocus());

        // ---- Payloads d’événements à émettre APRÈS commit
        final class Ev {
            boolean sendAtk, sendDef, sendResolved, startBite;
            String roundId, atkId, defId, biteAtt, biteTgt, biteLoc;
            Integer atkRoll, defRoll, dmg, defenderHp;
            java.util.List<String> breakdown = java.util.List.of();
        }
        Ev ev = new Ev();
        ev.roundId = r.getId();

        // --- Pose du jet (attacker OU defender) avec gestion FOCALISATION
        if (userId.equals(r.getAttackerId())) {
            // ici : always un joueur (monstre n'appelle jamais /roll)
            if (attackerPlayer0 == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
            }

            // AUTEL — message "before" pour la purification par combat
            String altarCode = Infra.ALTAR.locationCode();
            boolean isAltarFight = altarCode != null && altarCode.equals(r.getLocation());

            if (isAltarFight
                    && isAltarBuilt(g)
                    && isAltarCorrupted(g) // on ne purifie que si l'autel est corrompu
                    && defenderPlayer0 != null
                    && "HUNTER".equals(attackerPlayer0.getRole()) // attaquant = chasseur
                    && "VAMPIRE".equals(defenderPlayer0.getRole())// défenseur = vampire
                    && !g.isAltarVampTookDamageThisRaid() // encore aucun dégât pris par le vampire sur l'autel
                    && !g.isAltarBiteOccurredThisRaid() // aucune morsure réussie sur l'autel
                    && r.getAttackerRoll() == null // premier jet pour ce duel
                    && r.getAttackerFirstRoll() == null) {

                String line = "Autel — "
                        + nameOf(g, r.getAttackerId())
                        + " invoque une lueur d'espoir: "
                        + "Il tente de repousser le vampire et mettre fin au rituel";

                addHistory(g, line);
                appendBreakdown(r, line);
            }

            int sides = diceSides(attackerPlayer0.getAttackDice());

            // --- Salle de bal : Valse sanguinaire ---
            boolean waltzFight = isBallroomBloodWaltzAttack(g, r, userId, attackerPlayer0, defenderPlayer0);

            int huntersCountOnBallroom = 0;
            if (waltzFight) {
                String ballroomCode = Infra.BALLROOM.locationCode();
                huntersCountOnBallroom = (int) g.getPlayers().stream()
                        .filter(pp -> "HUNTER".equals(pp.getRole()))
                        .filter(pp -> pp.getHp() > 0)
                        .filter(pp -> ballroomCode != null && ballroomCode.equals(locationOf(g, pp.getId())))
                        .count();
            }
            // Valse n’a un effet réel que s’il y a au moins 2 chasseurs
            boolean effectiveWaltz = waltzFight && huntersCountOnBallroom > 1;

            boolean handled = false;

            // ==========================
            // CAS 1 : Valse + Focalisation (2 étapes)
            // ==========================
            if (effectiveWaltz && hasFocus) {

                // Étape 1 : on tire tous les dés de Valse, on garde le meilleur comme "premier
                // résultat"
                if (r.getAttackerFirstRoll() == null && r.getAttackerRoll() == null) {

                    java.util.List<Integer> rolls = g.getBallroomBloodWaltzRolls();
                    Integer best = g.getBallroomBloodWaltzBestRoll();

                    if (rolls == null || best == null || rolls.size() != huntersCountOnBallroom) {
                        rolls = new java.util.ArrayList<>();
                        best = Integer.MIN_VALUE;
                        for (int i = 0; i < huntersCountOnBallroom; i++) {
                            int v = dice.roll(sides);
                            rolls.add(v);
                            if (v > best)
                                best = v;
                        }
                        g.setBallroomBloodWaltzRolls(rolls);
                        g.setBallroomBloodWaltzBestRoll(best); // meilleur dé de Valse pour tout le raid (base)
                    }

                    r.setBallroomWaltzRolls(new java.util.ArrayList<>(rolls));
                    r.setBallroomWaltzBest(best);
                    // Ce "best" devient le "premier résultat" de Focalisation
                    r.setAttackerFirstRoll(best);

                    addHistory(g,
                            "Salle de bal — Valse sanguinaire : " + nameOf(g, r.getAttackerId())
                                    + " lance " + rolls.size() + " dés d'attaque "
                                    + rolls + " (meilleur actuel = " + best + ").");

                    // On envoie quand même un event pour afficher ce premier résultat
                    ev.sendAtk = true;
                    ev.atkId = r.getAttackerId();
                    ev.atkRoll = best;

                    // Pas encore de résultat final : on attend un 2ᵉ /roll pour la Focalisation
                    handled = true;

                } else if (r.getAttackerFirstRoll() != null && r.getAttackerRoll() == null) {
                    // Étape 2 : dé de Focalisation, unique pour cette Valse
                    int focusRoll = dice.roll(sides);
                    r.setAttackerReroll(focusRoll);

                    int prevBest = r.getAttackerFirstRoll();
                    int finalBest = Math.max(prevBest, focusRoll);
                    r.setAttackerRoll(finalBest);

                    addHistory(g,
                            nameOf(g, r.getAttackerId())
                                    + " — relance d'attaque (Potion de focalisation) : "
                                    + prevBest + " → " + focusRoll
                                    + " (garde " + finalBest + ").");

                    ev.sendAtk = true;
                    ev.atkId = r.getAttackerId();
                    ev.atkRoll = finalBest;

                    appendModBreakdownForSide(g, r, r.getAttackerId(), "ATTACK", finalBest);

                    handled = true;
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                }
            }

            // ==========================
            // CAS 2 : Valse seule (pas de Focalisation)
            // ==========================
            if (!handled && effectiveWaltz && !hasFocus) {

                if (r.getAttackerRoll() != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                }

                java.util.List<Integer> rolls = g.getBallroomBloodWaltzRolls();
                Integer best = g.getBallroomBloodWaltzBestRoll();

                if (rolls == null || best == null || rolls.size() != huntersCountOnBallroom) {
                    rolls = new java.util.ArrayList<>();
                    best = Integer.MIN_VALUE;
                    for (int i = 0; i < huntersCountOnBallroom; i++) {
                        int v = dice.roll(sides);
                        rolls.add(v);
                        if (v > best)
                            best = v;
                    }
                    g.setBallroomBloodWaltzRolls(rolls);
                    g.setBallroomBloodWaltzBestRoll(best);
                }

                r.setBallroomWaltzRolls(new java.util.ArrayList<>(rolls));
                r.setBallroomWaltzBest(best);
                r.setAttackerRoll(best);

                addHistory(g,
                        "Salle de bal — Valse sanguinaire : " + nameOf(g, r.getAttackerId())
                                + " lance " + rolls.size() + " dés d'attaque "
                                + rolls + " et conserve le meilleur (" + best + ").");

                ev.sendAtk = true;
                ev.atkId = r.getAttackerId();
                ev.atkRoll = best;

                appendModBreakdownForSide(g, r, r.getAttackerId(), "ATTACK", best);

                handled = true;
            }

            // ==========================
            // CAS 3 : pas de Valse "effective" (≤1 chasseur) → comportement standard
            // ==========================
            if (!handled) {
                if (hasFocus) {
                    // FOCALISATION EXISTANT
                    if (r.getAttackerFirstRoll() == null && r.getAttackerRoll() == null) {
                        int roll1 = dice.roll(sides);
                        r.setAttackerFirstRoll(roll1);

                        addHistory(g, nameOf(g, r.getAttackerId())
                                + " — jet d'attaque (Potion de focalisation, premier dé) = " + roll1 + ".");

                        ev.sendAtk = true;
                        ev.atkId = r.getAttackerId();
                        ev.atkRoll = roll1;

                    } else if (r.getAttackerFirstRoll() != null && r.getAttackerRoll() == null) {
                        int roll2 = dice.roll(sides);
                        int first = r.getAttackerFirstRoll();
                        int best = Math.max(first, roll2);

                        r.setAttackerReroll(roll2);
                        r.setAttackerRoll(best);

                        String hist = nameOf(g, r.getAttackerId())
                                + " — relance d'attaque grâce à la Potion de focalisation : "
                                + first + " → " + roll2 + " (garde " + best + ").";
                        addHistory(g, hist);

                        // Breakdown "diff de Foca" dès maintenant (pas besoin d'attendre la résolution)
                        if (best > first) {
                            String diffLine = nameOf(g, r.getAttackerId())
                                    + " a gagné " + (best - first)
                                    + " de stat par l'effet Potion de focalisation.";
                            addHistory(g, diffLine);
                            appendBreakdown(r, diffLine);
                        }

                        ev.sendAtk = true;
                        ev.atkId = r.getAttackerId();
                        ev.atkRoll = best;

                        appendModBreakdownForSide(g, r, r.getAttackerId(), "ATTACK", best);
                    } else {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                    }

                } else {
                    // Pas de potion : jet simple
                    if (r.getAttackerRoll() == null) {
                        int roll = dice.roll(sides);
                        r.setAttackerRoll(roll);
                        addHistory(g, nameOf(g, r.getAttackerId()) + " — jet d'attaque = " + roll + ".");

                        ev.sendAtk = true;
                        ev.atkId = r.getAttackerId();
                        ev.atkRoll = roll;

                        appendModBreakdownForSide(g, r, r.getAttackerId(), "ATTACK", roll);
                    } else {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                    }
                }
            }
        } else if (userId.equals(r.getDefenderId())) {
            // ici : always un joueur (monstre n'appelle jamais /roll)
            var p = defenderPlayer0;
            if (p == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
            }
            int sides = diceSides(p.getDefenseDice());

            if (hasFocus) {
                if (r.getDefenderFirstRoll() == null && r.getDefenderRoll() == null) {
                    // 1er jet de défense, uniquement stocké comme "premier dé"
                    int roll1 = dice.roll(sides);
                    r.setDefenderFirstRoll(roll1);

                    addHistory(g, nameOf(g, r.getDefenderId())
                            + " — jet de défense (Potion de focalisation, premier dé) = " + roll1 + ".");

                    ev.sendDef = true;
                    ev.defId = r.getDefenderId();
                    ev.defRoll = roll1;

                } else if (r.getDefenderFirstRoll() != null && r.getDefenderRoll() == null) {
                    // 2e appel : on fixe le jet final (meilleur des deux)
                    int roll2 = dice.roll(sides);
                    int first = r.getDefenderFirstRoll();
                    int best = Math.max(first, roll2);

                    r.setDefenderReroll(roll2);
                    r.setDefenderRoll(best);

                    String hist = nameOf(g, r.getDefenderId())
                            + " — relance de défense grâce à la Potion de focalisation : "
                            + first + " → " + roll2 + " (garde " + best + ").";
                    addHistory(g, hist);

                    if (best > first) {
                        String diffLine = nameOf(g, r.getDefenderId())
                                + " a gagné " + (best - first)
                                + " de stat par l'effet Potion de focalisation.";
                        addHistory(g, diffLine);
                        appendBreakdown(r, diffLine);
                    }

                    ev.sendDef = true;
                    ev.defId = r.getDefenderId();
                    ev.defRoll = best;

                    appendModBreakdownForSide(g, r, r.getDefenderId(), "DEFENSE", best);
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                }

            } else {
                // Pas de potion : comportement initial
                if (r.getDefenderRoll() == null) {
                    int roll = dice.roll(sides);
                    r.setDefenderRoll(roll);
                    addHistory(g, nameOf(g, r.getDefenderId()) + " — jet de défense = " + roll + ".");
                    ev.sendDef = true;
                    ev.defId = r.getDefenderId();
                    ev.defRoll = roll;

                    appendModBreakdownForSide(g, r, r.getDefenderId(), "DEFENSE", roll);
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                }
            }

        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
        }

        // --- Auto-roll pour les monstres ---
        // Cas 1 : attaquant = monstre, défenseur = joueur
        if (attackerIsMonster && defenderIsPlayer
                && r.getDefenderRoll() != null && r.getAttackerRoll() == null) {

            int roll = rollMonsterAttack(attackerMonster);
            r.setAttackerRoll(roll);

            addHistory(g, entityName(g, r.getAttackerId())
                    + " — jet d'attaque automatique = " + roll + ".");

            appendModBreakdownForSide(g, r, r.getAttackerId(), "ATTACK", roll);

            ev.sendAtk = true;
            ev.atkId = r.getAttackerId();
            ev.atkRoll = roll;
        }

        // Cas 2 : défenseur = monstre, attaquant = joueur
        if (defenderIsMonster && attackerIsPlayer
                && r.getAttackerRoll() != null && r.getDefenderRoll() == null) {

            int roll = rollMonsterDefense(defenderMonster);
            r.setDefenderRoll(roll);

            addHistory(g, entityName(g, r.getDefenderId())
                    + " — jet de défense automatique = " + roll + ".");

            appendModBreakdownForSide(g, r, r.getDefenderId(), "DEFENSE", roll);

            ev.sendDef = true;
            ev.defId = r.getDefenderId();
            ev.defRoll = roll;
        }

        // --- Résolution si les 2 jets sont posés (et pas encore résolu)
        if (r.getAttackerRoll() != null && r.getDefenderRoll() != null && combatNotResolved(r)) {
            int rawAtk = r.getAttackerRoll();
            int rawDef = r.getDefenderRoll(); // 0 d’emblée si Invisibilité a joué lors de la création du duel

            String loc = r.getLocation();

            // Modificateurs pour joueurs ET monstres
            // - Pour un joueur : météo, corruption, potions, actions, etc.
            // - Pour un monstre : uniquement les ACTION:* (Filet, Fosse, etc.)
            // grâce au filtrage dans modsAppliedFor(...)
            int atkMod = totalModFor(g, r.getAttackerId(), "ATTACK", loc);
            int defMod = totalModFor(g, r.getDefenderId(), "DEFENSE", loc);

            // Effets de raid (potions) pour attaquant et défenseur (uniquement joueurs, de
            // fait)
            RaidEffects atkFx = (g.getRaidEffects() != null)
                    ? g.getRaidEffects().get(r.getAttackerId())
                    : null;

            RaidEffects defFx = (g.getRaidEffects() != null)
                    ? g.getRaidEffects().get(r.getDefenderId())
                    : null;

            var atkPlayer = g.getPlayers().stream()
                    .filter(p -> p.getId().equals(r.getAttackerId()))
                    .findFirst()
                    .orElse(null);

            var defPlayer = g.getPlayers().stream()
                    .filter(p -> p.getId().equals(r.getDefenderId()))
                    .findFirst()
                    .orElse(null);

            Game.Monster defMonster = findMonster(g, r.getDefenderId());

            boolean ecorceEvade = false;

            if (atkPlayer != null
                    && defPlayer != null
                    && "HUNTER".equals(atkPlayer.getRole())
                    && "VAMPIRE".equals(defPlayer.getRole())
                    && vampArmorHasEvasion(defPlayer.getArmor())
                    && rawAtk == 12) { // 12 sur le D12 du chasseur
                ecorceEvade = true;
            }

            int atkScoreBeforeMul = rawAtk + atkMod;
            int defScoreBeforeMul = rawDef + defMod;

            int atkScore = atkScoreBeforeMul;
            int defScore = defScoreBeforeMul;

            // Rage : x2 attaque
            if (atkFx != null && atkFx.isDoubleAttack()) {
                atkScore *= 2;
            }
            // Résilience : x2 défense
            if (defFx != null && defFx.isDoubleDefense()) {
                defScore *= 2;
            }

            int dmg = Math.max(0, atkScore - defScore);

            if (ecorceEvade) {
                dmg = 0;
                String line = "L'Écorce de la Nuit réagit au coup critique de "
                        + nameOf(g, atkPlayer.getId())
                        + " : le vampire se volatilise brièvement, l'attaque est entièrement esquivée.";
                addHistory(g, line);
                appendBreakdown(r, line);
            }

            // Invulnérabilité : annule les dégâts
            boolean preventedByInvuln = false;
            if (defFx != null && defFx.isInvulnerable() && dmg > 0) {
                preventedByInvuln = true;
                dmg = 0;
            }

            if (dmg > 0) {
                if (defPlayer != null) {
                    defPlayer.setHp(Math.max(0, defPlayer.getHp() - dmg));
                } else if (defMonster != null) {
                    defMonster.hp = Math.max(0, defMonster.hp - dmg);
                }

                // Effet Liche : Applique marque ténébreuse sur un hit
                if (attackerIsMonster
                        && attackerMonster.type == Game.MonsterType.LICHE
                        && defPlayer != null
                        && "HUNTER".equals(defPlayer.getRole())) {

                    if (g.getDarkMarkedHunters() == null) {
                        g.setDarkMarkedHunters(new java.util.HashSet<>());
                    }

                    if (!g.getDarkMarkedHunters().contains(defPlayer.getId())) {
                        g.getDarkMarkedHunters().add(defPlayer.getId());
                        addRaidMod(g, defPlayer.getId(), "MARKED", 0, "CORRUPTION:MARK:DSP");

                        String line = "Liche — " + nameOf(g, defPlayer.getId())
                                + " est frappé par la Liche et reçoit une Marque ténébreuse.";
                        addHistory(g, line);
                        appendBreakdown(r, line);
                    }
                }
            }

            // Si le vampire subit des dégâts "normaux" pendant la PHASE3
            if (defPlayer != null && dmg > 0 && "VAMPIRE".equals(defPlayer.getRole())) {
                g.setVampireTookDamageThisRaid(true);
            }

            // Eau bénite (mode ATTACK) : +4 dégâts sacrés sur le vampire
            if (dmg > 0
                    && atkPlayer != null
                    && defPlayer != null
                    && "HUNTER".equals(atkPlayer.getRole())
                    && "VAMPIRE".equals(defPlayer.getRole())
                    && atkFx != null
                    && atkFx.isHolyWaterAttack()) {

                int extra = 4;
                int beforeHp = defPlayer.getHp();
                defPlayer.setHp(Math.max(0, defPlayer.getHp() - extra));
                int realExtra = beforeHp - defPlayer.getHp();

                if (realExtra > 0) {
                    String line = "Eau bénite — " + nameOf(g, atkPlayer.getId())
                            + " inflige " + realExtra
                            + " dégâts sacrés supplémentaires au vampire.";
                    addHistory(g, line);
                    appendBreakdown(r, line);

                    // Ces dégâts comptent aussi comme "le vampire a pris des dégâts ce raid"
                    g.setVampireTookDamageThisRaid(true);
                }

                // Une seule utilisation
                atkFx.setHolyWaterAttack(false);
            }

            // Étourdissement par arme de chasseur (cible = vampire ou serviteur)
            if (atkPlayer != null
                    && defPlayer != null
                    && ("VAMPIRE".equals(defPlayer.getRole())
                            || "SERVANT".equals(defPlayer.getRole())
                            || "HUNTER".equals(defPlayer.getRole()))
                    && dmg > 0) {

                int penality = stunPenalityForWeapon(atkPlayer.getWeapon());
                if (penality > 0) {
                    // Mod ENGINE sur l'ATTACK de la cible
                    addRaidMod(g, defPlayer.getId(), "ATTACK", -penality, "HIT:STUN_WEAPON:ENG");

                    String line = nameOf(g, atkPlayer.getId())
                            + " étourdit " + nameOf(g, defPlayer.getId())
                            + " : -" + penality + " ATK à sa prochaine attaque.";
                    addHistory(g, line);
                    appendBreakdown(r, line);
                }
            }

            // Tenue à distance (armes à distance chasseur)
            if (atkPlayer != null
                    && defPlayer != null
                    && ("VAMPIRE".equals(defPlayer.getRole())
                            || "SERVANT".equals(defPlayer.getRole())
                            || "HUNTER".equals(defPlayer.getRole()))
                    && dmg > 0
                    && hunterWeaponIsRanged(atkPlayer.getWeapon())
                    && rangedKeepAwayTriggered(atkPlayer.getWeapon(), rawAtk)) {

                // Puce DISPLAY sur la cible : "tenu à distance"
                addRaidMod(g, defPlayer.getId(), "HIT", 0, "HIT:RANGED_WEAPON:DSP");

                String line = nameOf(g, defPlayer.getId())
                        + " est tenu à distance par " + nameOf(g, atkPlayer.getId())
                        + " : il ne pourra pas riposter ce raid contre lui.";
                addHistory(g, line);
                appendBreakdown(r, line);

                // On supprime de la file la riposte inverse (défenseur → attaquant)
                cancelReverseFight(g, r.getAttackerId(), r.getDefenderId());
            }

            // Sangsue : se soigne des dégâts infligés (après invulnérabilité)
            int healedByLeech = 0;
            if (atkPlayer != null && atkFx != null && atkFx.isLeech() && dmg > 0) {
                int beforeHp = atkPlayer.getHp();
                int maxHp = maxHpFor(g, atkPlayer);
                atkPlayer.setHp(Math.min(maxHp, atkPlayer.getHp() + dmg));
                healedByLeech = atkPlayer.getHp() - beforeHp;
            }

            // Régénération via arme vampirique (en plus ou à la place de la Potion sangsue)
            int healedByEquip = 0;
            if (atkPlayer != null
                    && ("VAMPIRE".equals(atkPlayer.getRole()) || "SERVANT".equals(atkPlayer.getRole()))
                    && dmg > 0
                    && r.getAttackerRoll() != null) {

                int regen = vampRegenAmount(atkPlayer.getWeapon(), r.getAttackerRoll());
                if (regen > 0) {
                    int beforeHp = atkPlayer.getHp();
                    int maxHp = maxHpFor(g, atkPlayer);
                    atkPlayer.setHp(Math.min(maxHp, atkPlayer.getHp() + regen));
                    healedByEquip = atkPlayer.getHp() - beforeHp;
                }
            }

            // -------------------
            // Vols de ressource par le vampire (jamais l'or ni l'argent)
            // - Vol de base : si dégâts > 0 sur un chasseur ET jet d'attaque au maximum du dé
            // - Salle de bal : Attaque sournoise = 1 vol supplémentaire
            // sur la cible, même si l’attaque échoue.
            // -------------------
            java.util.List<String> theftLines = new java.util.ArrayList<>();

            boolean vampVsHunter = atkPlayer != null && "VAMPIRE".equals(atkPlayer.getRole()) &&
                    defPlayer != null && "HUNTER".equals(defPlayer.getRole());

            String ballroomCode = Infra.BALLROOM.locationCode();
            boolean ballroomSneakActive = g.isBallroomSneakAttack()
                    && g.getBuiltInfras() != null
                    && g.getBuiltInfras().contains(Infra.BALLROOM)
                    && ballroomCode != null
                    && ballroomCode.equals(loc);

            // 1) Effet Salle de bal : Attaque sournoise
            // → 1 vol garanti sur le défenseur, même si dmg == 0
            if (vampVsHunter && ballroomSneakActive) {
                String line = vampStealOne(g, atkPlayer, defPlayer);
                if (line != null) {
                    theftLines.add("Salle de bal — Attaque sournoise : " + line);
                }
            }

            // 2) Vol de base : uniquement si l’attaque inflige des dégâts et dé max
            if (vampVsHunter && dmg > 0 && rawAtk == diceSides(atkPlayer.getAttackDice())) {
                String line = vampStealOne(g, atkPlayer, defPlayer);
                if (line != null)
                    theftLines.add(line);
            }

            List<String> atkBk = new ArrayList<>();
            List<String> defBk = new ArrayList<>();

            // Valse sanguinaire
            List<String> waltzLines = new ArrayList<>();
            if (r.getBallroomWaltzBest() != null
                    && r.getBallroomWaltzRolls() != null
                    && !r.getBallroomWaltzRolls().isEmpty()) {

                waltzLines.add("Salle de bal — Valse sanguinaire : "
                        + entityName(g, r.getAttackerId())
                        + " a lancé " + r.getBallroomWaltzRolls().size()
                        + " dés " + r.getBallroomWaltzRolls()
                        + " et utilisé le meilleur : " + r.getBallroomWaltzBest() + ".");
            }

            atkBk.addAll(waltzLines);

            // --- ALTAR : lignes de contexte pour la purification par combat ---
            String altarCode = Infra.ALTAR.locationCode();
            boolean isAltarFight = altarCode != null && altarCode.equals(loc);

            if (isAltarFight
                    && isAltarBuilt(g)
                    && isAltarCorrupted(g)
                    && atkPlayer != null
                    && defPlayer != null
                    && "HUNTER".equals(atkPlayer.getRole())
                    && "VAMPIRE".equals(defPlayer.getRole())
                    && !g.isAltarVampTookDamageThisRaid()
                    && !g.isAltarBiteOccurredThisRaid()) {

                // Si l'attaque inflige des dégâts au vampire, on ajoute la ligne "fait
                // vaciller..."
                if (dmg > 0) {
                    String line = "Autel — "
                            + nameOf(g, atkPlayer.getId())
                            + " fait vaciller le vampire:"
                            + " si aucun rituel de morsure ne réussit d'ici la fin du raid,"
                            + " l'autel sera lavé de sa corruption.";
                    addHistory(g, line); // reste dans l'historique global
                    atkBk.add(line); // ET dans le breakdown
                }
            }

            // --- Lignes supplémentaires pour les nouvelles potions ---
            if (atkFx != null && atkFx.isDoubleAttack()) {
                atkBk.add(nameOf(g, r.getAttackerId())
                        + " voit son score d'attaque doublé par la Potion de rage : "
                        + atkScoreBeforeMul + " → " + atkScore + ".");
            }
            if (defFx != null && defFx.isDoubleDefense()) {
                defBk.add(nameOf(g, r.getDefenderId())
                        + " voit son score de défense doublé par la Potion de résilience : "
                        + defScoreBeforeMul + " → " + defScore + ".");
            }
            if (atkFx != null && atkFx.isInvisible()) {
                atkBk.add(nameOf(g, r.getAttackerId())
                        + " est invisible : "
                        + nameOf(g, r.getDefenderId())
                        + " ne lance pas de dé de défense (0).");
            }
            if (preventedByInvuln && defPlayer != null) {
                defBk.add(nameOf(g, r.getDefenderId())
                        + " est protégé par une Potion d'invulnérabilité : les dégâts sont annulés.");
            }
            if (healedByLeech > 0 && atkPlayer != null) {
                atkBk.add(nameOf(g, r.getAttackerId())
                        + " récupère " + healedByLeech + " PV grâce à la Potion de sangsue.");
            }
            if (atkFx != null && atkFx.isRapid() && r.isRapidExtra()) {
                atkBk.add(
                        nameOf(g, r.getAttackerId())
                                + " attaque une deuxième fois par l'effet de la Potion de rapidité.");
            }

            if (healedByEquip > 0 && atkPlayer != null) {
                atkBk.add(nameOf(g, atkPlayer.getId())
                        + " récupère " + healedByEquip
                        + " PV grâce à son arme vampirique.");
            }

            // Historique détaillé
            for (String ln : atkBk)
                addHistory(g, ln);
            for (String ln : defBk)
                addHistory(g, ln);

            if (r.getBreakdownLines() == null)
                r.setBreakdownLines(new java.util.ArrayList<>());
            r.getBreakdownLines().addAll(atkBk);
            r.getBreakdownLines().addAll(defBk);
            r.getBreakdownLines().addAll(theftLines);

            String an = entityName(g, r.getAttackerId());
            String dn = entityName(g, r.getDefenderId());

            String resultLine;
            if (dmg > 0)
                resultLine = an + " inflige " + dmg + " dégâts à " + dn;
            else
                resultLine = dn + " pare l'attaque de " + an;

            // Historique (comme avant)
            addHistory(g, resultLine);

            // Breakdown (NOUVEAU) => la modale spectate affichera toujours la vérité
            // serveur
            appendBreakdown(r, resultLine);

            int bleed = 0;
            if (atkPlayer != null && dmg > 0) {
                bleed = bleedBonusForWeapon(atkPlayer.getWeapon());
            }
            if (bleed > 0) {
                g.getBleedDamageByTarget().merge(r.getDefenderId(), bleed, Integer::sum);

                // Puce DSP temporaire sur la cible : "saigne"
                addRaidMod(g, r.getDefenderId(), "HIT", 0, "HIT:BLEED_WEAPON:DSP");

                String line = nameOf(g, r.getDefenderId())
                        + " commence à saigner (" + bleed + " dégâts en fin de raid).";
                addHistory(g, line);
                appendBreakdown(r, line);
            }

            // Purify Altar
            var vamp = getVamp(g).get();
            if (vamp.getId().equals(r.getDefenderId())) {
                onAltarVampireDamaged(g, r, dmg);
            }

            // Si un monstre tombe à 0 PV ou moins, on le laisse dans la liste
            if (defMonster != null && defMonster.hp <= 0) {
                defMonster.hp = 0; // par sécurité, on le borne à 0
            }

            if (g.getUnstableTargetByPlayer().containsKey(r.getAttackerId())
                    && java.util.Objects.equals(g.getUnstableTargetByPlayer().get(r.getAttackerId()),
                            r.getDefenderId())) {
                addHistory(g, nameOf(g, r.getAttackerId()) + " revient à lui ...");
            }

            ev.sendResolved = true;
            ev.dmg = dmg;
            ev.defId = r.getDefenderId();

            if (defPlayer != null) {
                ev.defenderHp = defPlayer.getHp();
            } else if (defMonster != null) {
                ev.defenderHp = defMonster.hp;
            } else {
                ev.defenderHp = 0;
            }

            // Vampire vs chasseur ?
            boolean vampDamagedHunter = vampVsHunter && dmg > 0;

            // 1) Effet Salle de bal : Danse macabre → NE dépend que des dégâts
            if (vampDamagedHunter) {

                if (g.isBallroomDeathDance()
                        && g.getBuiltInfras() != null
                        && g.getBuiltInfras().contains(Infra.BALLROOM)) {

                    if (ballroomCode != null && ballroomCode.equals(r.getLocation())) {

                        int before = defPlayer.getCorruption();
                        if (before < 3) {
                            defPlayer.setCorruption(before + 1);
                            addHistory(g, "Salle de bal — Danse macabre : "
                                    + nameOf(g, defPlayer.getId())
                                    + " subit 1 point de corruption (niveau " + (before + 1) + ").");
                        }
                    }
                }
            }

            // 2) Tentative de morsure : dégâts OU Faim irrépressible
            boolean vampCanBiteHunter = vampVsHunter
                    && defPlayer != null
                    && defPlayer.getCorruption() < 3
                    && (dmg > 0 || g.isHungerAllowsBiteThisRaid())
                    && (!r.isCloneAttack() || r.isCanBite());

            if (vampCanBiteHunter) {
                Game.BiteAttempt b = new Game.BiteAttempt();
                b.setId(java.util.UUID.randomUUID().toString());
                b.setAttackerId(atkPlayer.getId());
                b.setTargetId(defPlayer.getId());
                b.setLocation(r.getLocation());
                g.setCurrentBite(b);

                ev.startBite = true;
                ev.biteAtt = atkPlayer.getId();
                ev.biteTgt = defPlayer.getId();
                ev.biteLoc = r.getLocation();
            }

            // 3) Pieu béni : marquer le duel comme devant déclencher l'action
            boolean canFlagBlessedStake = atkPlayer != null
                    && "HUNTER".equals(atkPlayer.getRole())
                    && atkPlayer.isBlessedStake()
                    && dmg > 0;

            if (canFlagBlessedStake) {
                r.setBlessedStakePending(true);

                String line = "Épieu béni — "
                        + nameOf(g, atkPlayer.getId())
                        + " prépare un coup sacré supplémentaire contre "
                        + entityName(g, r.getDefenderId()) + ".";
                addHistory(g, line);
                appendBreakdown(r, line);
            }

            r.setResolvedAtMillis(System.currentTimeMillis());
        }

        // --- Après les dégâts / corruption / etc. : gérer morts + fin de partie
        handleDeathsAndVictory(g);

        // --- Payload breakdown : on envoie toujours l'état courant
        if (r.getBreakdownLines() != null && !r.getBreakdownLines().isEmpty()) {
            ev.breakdown = new java.util.ArrayList<>(r.getBreakdownLines());
        } else {
            ev.breakdown = java.util.List.of();
        }

        // --- Commit
        save(g);

        // --- Events APRÈS COMMIT (ordre garanti)
        afterCommit(() -> {
            if (ev.sendAtk)
                live.diceRolled(g, ev.roundId, ev.atkId, "ATTACK", ev.atkRoll, ev.breakdown);
            if (ev.sendDef)
                live.diceRolled(g, ev.roundId, ev.defId, "DEFENSE", ev.defRoll, ev.breakdown);
            if (ev.sendResolved)
                live.combatResolved(g, ev.roundId, ev.dmg, ev.defId, ev.defenderHp, ev.breakdown);
            if (ev.startBite)
                live.biteStarted(g, ev.biteAtt, ev.biteTgt, ev.biteLoc);

            live.phaseChanged(g);
        });

        return g;
    }

    private static boolean combatNotResolved(RoundFight r) {
        return r == null || r.getResolvedAtMillis() == null || r.getResolvedAtMillis() == 0L;
    }

    private static boolean biteResolved(Game.BiteAttempt b) {
        return b != null
                && b.getRoll() != null
                && b.getResolvedAtMillis() != null
                && b.getResolvedAtMillis() != 0L;
    }

    public Game combatContinue(String gameId, String userId) {
        class Ev {
            boolean biteResolved;
            boolean advanced;
            boolean trapResolved;

            String att, tgt, loc; // pour la morsure
            String trapMode, trapOwnerId, trapTargetId; // pour Filet/Fosse/Incendiaire/Épieu béni
        }

        Ev ev = tx.execute(status -> {
            Game g = findOr404(gameId);
            if (g.getPhase() != Phase.PHASE3)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE3");

            Ev out = new Ev();

            // 0) GESTION DES ACTIONS DE RAID (NET / PIT / INCENDIAIRE / BLESSED_STAKE)
            // AVANT TOUT
            Game.Action ca = g.getCurrentAction();
            if (ca != null
                    && ca.getMode() != null
                    && ("NET".equals(ca.getMode())
                            || "PIT".equals(ca.getMode())
                            || "INCENDIAIRE".equals(ca.getMode())
                            || "BLESSED_STAKE".equals(ca.getMode()))) {

                // Tant que le jet n'est pas fait, on laisse la modale ouverte
                if (ca.getRoll() == null) {
                    save(g);
                    return out;
                }

                // L'action vient d'être résolue (roll != null) → on clôture cette action
                out.trapResolved = true;
                out.trapMode = ca.getMode();
                out.trapOwnerId = ca.getOwnerId();
                out.trapTargetId = ca.getTargetId(); // pour INCENDIAIRE = infra.name()

                // On "oublie" l'action courante
                g.setCurrentAction(null);

                // On tente de préparer l'action suivante (Filet/Fosse/Incendiaire)
                prepareNextRaidAction(g);

                // S'il reste une autre action, on s'arrête là :
                // - le front affichera la nouvelle modale via currentAction
                // - on NE touche PAS encore aux morsures / combats
                if (g.getCurrentAction() != null) {
                    save(g);
                    return out;
                }

                // Sinon : plus d'action de raid -> on laisse continuer la logique (morsure /
                // combats)
            }

            // 1) S'il y a une morsure en cours :
            if (g.getCurrentBite() != null) {
                var b = g.getCurrentBite();
                if (!biteResolved(b)) {
                    save(g);
                    return out;
                }
                out.biteResolved = true;
                out.att = b.getAttackerId();
                out.tgt = b.getTargetId();
                out.loc = b.getLocation();
                g.setCurrentBite(null);

            } else {
                // 2) Sinon on est sur un duel : s’il n’est pas encore résolu
                var r = g.getCurrentCombat();

                // on laisse filer jusqu'à la partie "hasAnyRemainingFight" + auto-PHASE4 plus
                // bas.
                if (r != null) {
                    if (combatNotResolved(r)) {
                        save(g);
                        return out;
                    }

                    boolean modsChanged = consumeHitModsAfterFight(g, r);
                    // pour forcer live.phaseChanged => snapshot frais côté front
                    if (modsChanged)
                        out.advanced = true;

                    // déclencheur Pieu béni
                    if (r.isBlessedStakePending() && g.getCurrentAction() == null) {
                        Game.Action a = new Game.Action();
                        a.setMode("BLESSED_STAKE");
                        a.setOwnerId(r.getAttackerId()); // le chasseur qui avait l’effet
                        a.setLocation(r.getLocation());
                        a.setTargetId(r.getDefenderId()); // même cible que le duel
                        a.setRoll(null); // d4 pas encore lancé
                        a.setBreakdownLines(new java.util.ArrayList<>());
                        a.setResolvedAtMillis(null);

                        g.setCurrentAction(a);
                        r.setBlessedStakePending(false); // flag consommé

                        save(g);
                        return out;
                    }
                }
            }

            // 3) Avancer la file uniquement quand c’est safe (morsure close ou duel résolu)
            if (g.getCombatsQueue() != null) {
                Integer idx = g.getCurrentCombatIndex();

                if (idx == null) {
                    // fin de file : par sécurité, on ne re-sélectionne rien
                    // (et si jamais currentCombat traîne, on nettoie)
                    if (g.getCurrentCombat() != null) {
                        g.setCurrentCombat(null);
                        out.advanced = true;
                    }
                } else {
                    int size = g.getCombatsQueue().size();
                    int next = idx + 1;

                    RoundFight nextFight = null;

                    while (next < size) {
                        RoundFight candidate = g.getCombatsQueue().get(next);

                        // ignorer les combats déjà résolus
                        if (!combatNotResolved(candidate)) {
                            next++;
                            continue;
                        }

                        boolean atkAlive = isEntityAlive(g, candidate.getAttackerId());
                        boolean defAlive = isEntityAlive(g, candidate.getDefenderId());

                        if (atkAlive && defAlive) {
                            nextFight = candidate;
                            break; // on a trouvé le prochain duel valide
                        }

                        // sinon on skip ce combat et on regarde le suivant
                        next++;
                    }

                    if (nextFight != null) {
                        g.setCurrentCombatIndex(next);
                        g.setCurrentCombat(nextFight);

                        // *** copie locale "finale" pour les lambdas ***
                        final RoundFight nf = nextFight;

                        // --- Pré-remplissage pour Salle de bal / Valse sanguinaire ---
                        if (g.isBallroomBloodWaltz()
                                && g.getBuiltInfras() != null
                                && g.getBuiltInfras().contains(Infra.BALLROOM)) {

                            var attPlayer = g.getPlayers().stream()
                                    .filter(pp -> pp.getId().equals(nf.getAttackerId()))
                                    .findFirst()
                                    .orElse(null);
                            var defPlayer = g.getPlayers().stream()
                                    .filter(pp -> pp.getId().equals(nf.getDefenderId()))
                                    .findFirst()
                                    .orElse(null);

                            String ballroomCode = Infra.BALLROOM.locationCode();
                            boolean isVampAttackOnBallroom = attPlayer != null && "VAMPIRE".equals(attPlayer.getRole())
                                    && defPlayer != null && "HUNTER".equals(defPlayer.getRole())
                                    && ballroomCode != null
                                    && ballroomCode.equals(nf.getLocation());

                            if (isVampAttackOnBallroom && nf.getAttackerRoll() == null) {

                                // Est-ce que la Valse a déjà été tirée pour ce raid ?
                                boolean waltzAlreadyRolled = g.getBallroomBloodWaltzRolls() != null
                                        && !g.getBallroomBloodWaltzRolls().isEmpty()
                                        && g.getBallroomBloodWaltzBestRoll() != null;

                                if (waltzAlreadyRolled) {
                                    // Toujours copier les dés de Valse pour l'affichage UI
                                    nf.setBallroomWaltzRolls(
                                            new java.util.ArrayList<>(g.getBallroomBloodWaltzRolls()));
                                    nf.setBallroomWaltzBest(g.getBallroomBloodWaltzBestRoll());

                                    // Effets de raid de l'attaquant, pour savoir s'il a Focalisation
                                    RaidEffects atkFx = (g.getRaidEffects() != null && attPlayer != null)
                                            ? g.getRaidEffects().get(attPlayer.getId())
                                            : null;
                                    boolean attackerHasFocus = (atkFx != null && atkFx.isFocus());

                                    if (!attackerHasFocus) {
                                        // 🎯 Valse seule :
                                        // le jet d'attaque de ce duel est directement fixé par la Valse globale
                                        nf.setAttackerRoll(g.getBallroomBloodWaltzBestRoll());

                                    } else {
                                        // 🎯 Valse + Foca, mais sur un duel APRÈS le premier :
                                        // best de Valse = "dé de base" de ce duel (attackerFirstRoll)
                                        nf.setAttackerFirstRoll(g.getBallroomBloodWaltzBestRoll());
                                        // attackerRoll reste null → le prochain /roll sera la Foca directement
                                    }
                                }
                            }
                        }
                    } else {
                        // plus aucun combat valide
                        g.setCurrentCombatIndex(null);
                        g.setCurrentCombat(null);
                    }

                    out.advanced = true;
                }
            }

            // Recalcule s'il reste un combat réellement jouable
            boolean hasAnyRemainingFight = false;

            if (g.getCombatsQueue() != null) {
                for (RoundFight candidate : g.getCombatsQueue()) {

                    // 1) ignorer les combats déjà résolus
                    if (!combatNotResolved(candidate))
                        continue;

                    // 2) ignorer ceux dont un des deux est mort (skip logique)
                    boolean atkAlive = isEntityAlive(g, candidate.getAttackerId());
                    boolean defAlive = isEntityAlive(g, candidate.getDefenderId());
                    if (!atkAlive || !defAlive)
                        continue;

                    // 3) il reste vraiment un combat à jouer
                    hasAnyRemainingFight = true;
                    break;
                }
            }

            if (!hasAnyRemainingFight && g.getCurrentBite() == null && g.getCurrentCombat() == null) {
                resolveInfraConstruction(g);
                applyPhaseEntry(g, Phase.PHASE4);
                g.setCurrentAction(null);
                out.advanced = true; // pour envoyer un phaseChanged derrière
                save(g);
                return out;
            }

            save(g);
            return out;
        });

        Game gAfter = findOr404(gameId);

        // Events après commit
        if (ev.biteResolved) {
            live.biteResolved(gAfter, ev.att, ev.tgt, ev.loc);
        }
        if (ev.trapResolved) {
            // C'est cet event que le front écoute (ACTION_RESOLVED)
            live.actionResolved(gAfter, ev.trapMode, ev.trapOwnerId, ev.trapTargetId);
        }
        if (ev.advanced) {
            // heartbeat pour faire faire un GET propre côté front (combats qui avancent /
            // fin de combats / passage phase4)
            live.phaseChanged(gAfter);
        }

        return gAfter;
    }

    private boolean isEntityAlive(Game g, String entityId) {
        if (entityId == null)
            return false;

        Player p = findPlayer(g, entityId);
        if (p != null) {
            return p.getHp() > 0;
        }

        Game.Monster m = findMonster(g, entityId);
        if (m != null) {
            return m.hp > 0;
        }

        return false;
    }

    // Météo
    // alimente raidMods
    private void rebuildWeatherMods(Game g) {
        // Sécurité : map toujours présente
        if (g.getRaidMods() == null)
            g.setRaidMods(new java.util.HashMap<>());

        // 1) retirer tous les effets météo précédents
        for (var entry : g.getRaidMods().entrySet()) {
            var list = entry.getValue();
            if (list == null)
                continue;
            list.removeIf(m -> m.getSource() != null && m.getSource().startsWith("WEATHER:"));
        }

        WeatherStatus ws1 = g.getWeatherStatus(); // base
        WeatherStatus ws2 = g.getSecondaryWeatherStatus(); // extra 2
        WeatherStatus ws3 = g.getThirdWeatherStatus(); // extra 3

        // 2) si pas de météo active => on s’arrête
        if (ws1 == null && ws2 == null && ws3 == null)
            return;

        // 3) s'assurer qu'il y a une liste pour chaque joueur
        for (var p : g.getPlayers()) {
            g.getRaidMods().computeIfAbsent(p.getId(), __ -> new java.util.ArrayList<>());
        }

        // 4) Appliquer les effets d'UN, DEUX ou TROIS statuts
        if (ws1 != null) {
            applyWeatherStatusMods(g, ws1);
        }
        if (ws2 != null) {
            applyWeatherStatusMods(g, ws2);
        }
        if (ws3 != null) {
            applyWeatherStatusMods(g, ws3);
        }
    }

    private void applyWeatherStatusMods(Game g, WeatherStatus ws) {
        switch (ws) {
            case SUNNY -> {
                for (var p : g.getPlayers()) {
                    if ("HUNTER".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +1, "WEATHER:SUNNY"));
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", -1, "WEATHER:SUNNY"));
                }
            }
            case FOG -> {
                for (var p : g.getPlayers())
                    if ("HUNTER".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +1, "WEATHER:FOG"));
            }
            case AURORA -> {
                for (var p : g.getPlayers())
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", -1, "WEATHER:AURORA"));
            }
            case CLOUDY -> {
                for (var p : g.getPlayers()) {
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", -1, "WEATHER:CLOUDY"));
                }
            }
            case WIND -> {
                for (var p : g.getPlayers()) {
                    if ("HUNTER".equals(p.getRole())) {
                        g.getRaidMods().get(p.getId())
                                .add(new StatMod("CONSTRUCTION", 0, "WEATHER:WIND:HUNTER:DSP"));
                    } else if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                        g.getRaidMods().get(p.getId())
                                .add(new StatMod("CONSTRUCTION", 0, "WEATHER:WIND:VAMP:DSP"));
                    }
                }
            }
            case STORM -> {
                for (var p : g.getPlayers())
                    g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", -2, "WEATHER:STORM"));
            }
            case RAIN -> {
                for (var p : g.getPlayers())
                    g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", -2, "WEATHER:RAIN"));
            }
            case BLIZZARD -> {
                for (var p : g.getPlayers())
                    g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", -1, "WEATHER:BLIZZARD"));
            }
            case DUSK -> {
                for (var p : g.getPlayers())
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", +1, "WEATHER:DUSK"));
            }
            case NIGHT_DARK -> {
                for (var p : g.getPlayers())
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +1, "WEATHER:NIGHT_DARK"));
            }
            case NIGHT_CLEAR -> {
                for (var p : g.getPlayers()) {
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +1, "WEATHER:NIGHT_CLEAR"));
                    if ("HUNTER".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", -1, "WEATHER:NIGHT_CLEAR"));
                }
            }
            case FULL_MOON -> {
                for (var p : g.getPlayers())
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +2, "WEATHER:FULL_MOON"));
            }
            case BLOOD_MOON -> {
                for (var p : g.getPlayers()) {
                    if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                        g.getRaidMods().get(p.getId())
                                .add(new StatMod("ATTACK", +4, "WEATHER:BLOOD_MOON"));
                    }
                }
            }
        }
    }

    @Transactional
    public Game rollWeather(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing user id");
        if (g.getPhase() != Phase.PHASE0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in weather phase");

        var vamp = getVamp(g).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "no vampire"));
        if (!vamp.getId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can roll weather");

        if (g.getWeatherRoll() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "weather already rolled");

        // Sécurité : structures non-null
        if (g.getRaidMods() == null)
            g.setRaidMods(new HashMap<>());

        // 1) Mutations PURES (aucun save / aucun event ici)
        int roll = dice.roll(12);
        applyWeatherRoll(g, roll);

        // 2) Commit
        save(g);

        // 3) Events APRÈS COMMIT (aucune course avec les GET)
        afterCommit(() -> {
            live.weatherRolled(g); // payload construit depuis g (déjà commité)
            live.raidModsUpdated(g); // pour rafraîchir les puces météo côté UI
        });

        return g;
    }

    private void applyWeatherRoll(@NonNull Game g, int roll) {
        g.setWeatherRoll(roll);

        WeatherStatus ws = mapRollToWeather(roll);
        g.setWeatherStatus(ws);

        g.setWeatherStatusNameFr(weatherNameFr(ws));
        g.setWeatherDescriptionFr(weatherDescFr(ws));

        // Recalcule les mods météo (affichage/combat)
        rebuildWeatherMods(g);

        if (ws == WeatherStatus.WIND) {
            applyWindRepairs(g);
        }

        // Historique
        addHistory(g, "Météo — " + g.getWeatherStatusNameFr());
        if (g.getWeatherDescriptionFr() != null && !g.getWeatherDescriptionFr().isBlank()) {
            addHistory(g, g.getWeatherDescriptionFr());
        }

        // Messages au centre (utilisés par le front)
        List<String> msgs = new ArrayList<>();
        msgs.add("Météo — " + (g.getWeatherStatusNameFr() != null ? g.getWeatherStatusNameFr() : ""));
        if (g.getWeatherDescriptionFr() != null && !g.getWeatherDescriptionFr().isBlank()) {
            msgs.add(g.getWeatherDescriptionFr());
        }
        g.setMessages(msgs);
    }

    private void applyWindRepairs(@NonNull Game g) {
        // 1) Chasseurs : -1 ressource aléatoire chacun (réparations du village)
        for (Player p : g.getPlayers()) {
            if (!"HUNTER".equals(p.getRole()))
                continue;

            String type = loseOneRandomBuildResource(p);
            if (type == null)
                continue;

            String who = nameOf(g, p.getId());
            String resFr = switch (type) {
                case "WOOD" -> "bois";
                case "IRON" -> "fer";
                case "STONE" -> "pierre";
                default -> "ressource";
            };

            addHistory(g,
                    "Cyclone — " + who
                            + " perd 1 " + resFr
                            + " pour réparer le village.");
        }

        // 2) Domaine : -1 ressource aléatoire par construction
        int nbInfras = countDomainConstructions(g); // à adapter à ta structure d'infras
        if (nbInfras <= 0) {
            return;
        }

        java.util.List<Player> vampSide = g.getPlayers().stream()
                .filter(p -> "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                .toList();

        if (vampSide.isEmpty()) {
            return;
        }

        for (int i = 0; i < nbInfras; i++) {
            Player payer = pickRandomWithBuildResources(vampSide);
            if (payer == null)
                break; // plus personne n’a de ressources de construction

            String type = loseOneRandomBuildResource(payer);
            if (type == null)
                break;

            String who = nameOf(g, payer.getId());
            String resFr = switch (type) {
                case "WOOD" -> "bois";
                case "IRON" -> "fer";
                case "STONE" -> "pierre";
                default -> "ressource";
            };

            addHistory(g,
                    "Cyclone — " + who
                            + " dépense 1 " + resFr
                            + " pour réparer le domaine.");
        }
    }

    /**
     * Retire 1 ressource de construction aléatoire (bois / fer, pierre plus tard).
     * 
     * @return "WOOD" | "IRON" | "STONE" | null si aucune ressource à retirer
     */
    private String loseOneRandomBuildResource(Player p) {
        java.util.List<String> pool = new java.util.ArrayList<>();

        if (p.getWood() > 0)
            pool.add("WOOD");
        if (p.getIron() > 0)
            pool.add("IRON");
        if (p.getStone() > 0)
            pool.add("STONE");

        if (pool.isEmpty())
            return null;

        String type = pool.get(dice.nextInt(pool.size()));
        switch (type) {
            case "WOOD" -> p.setWood(p.getWood() - 1);
            case "IRON" -> p.setIron(p.getIron() - 1);
            case "STONE" -> p.setStone(p.getStone() - 1);
        }

        return type;
    }

    /**
     * Choisit un joueur vamp-side ayant au moins 1 ressource de construction.
     */
    private Player pickRandomWithBuildResources(java.util.List<Player> players) {
        java.util.List<Player> candidates = players.stream()
                .filter(p -> p.getWood() > 0 || p.getIron() > 0 || p.getStone() > 0)
                .toList();
        if (candidates.isEmpty())
            return null;
        return candidates.get(dice.nextInt(candidates.size()));
    }

    /**
     * Nombre de constructions du domaine.
     */
    private int countDomainConstructions(Game g) {
        if (g.getBuiltInfras() == null)
            return 0;
        return g.getBuiltInfras().size();
    }

    // ressources
    private int rollD100Tens() {
        return dice.nextInt(10) * 10;
    }

    private void grant(Player p, String res, int qty) {
        if (p == null)
            return;
        p.grant(res, qty);
    }

    private String resLabelFr(String res) {
        return switch (res) {
            case "wood" -> "bois";
            case "herbs" -> "herbe médicinale";
            case "stone" -> "pierre";
            case "iron" -> "fer";
            case "water" -> "eau pure";
            case "gold" -> "or";
            case "souls" -> "âmes déchues";
            case "silver" -> "argent";
            default -> res;
        };
    }

    /**
     * Applique les récoltes pour les lieux SANS combat (une seule fois par raid).
     */
    private void applyHarvests(@NonNull Game g) {
        var vamp = getVamp(g).orElse(null);
        var groups = groupPlayersByLocation(g);

        // Lieux où au moins un monstre vivant est présent
        java.util.Set<String> monsterLocs = new java.util.HashSet<>();
        if (g.getMonsters() != null) {
            g.getMonsters().stream()
                    .filter(m -> m.hp > 0)
                    .forEach(m -> monsterLocs.add(m.location));
        }

        // Lieux des clones
        java.util.Set<String> cloneLocs = new java.util.HashSet<>();
        if (g.getClonesLocations() != null) {
            cloneLocs.addAll(g.getClonesLocations());
        }

        // Duels instable -> cible :
        // - l'instable ne récolte pas
        // - la cible récolte /2
        java.util.Set<String> duelUnstables = new java.util.HashSet<>();
        java.util.Set<String> duelTargets = new java.util.HashSet<>();
        if (g.getUnstableTargetByPlayer() != null) {
            g.getUnstableTargetByPlayer().forEach((unstableId, targetId) -> {
                if (unstableId != null)
                    duelUnstables.add(unstableId);
                if (targetId != null)
                    duelTargets.add(targetId);
            });
        }

        java.util.Set<String> unstableHarvesters = (g.getUnstableHarvestLocByPlayer() != null)
                ? g.getUnstableHarvestLocByPlayer().keySet()
                : java.util.Collections.emptySet();

        for (var e : groups.entrySet()) {
            String loc = e.getKey();
            var onLoc = e.getValue();

            boolean enemyHere = onLoc.stream()
                    .anyMatch(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                            && p.getHp() > 0);

            boolean monsterHere = monsterLocs.contains(loc);

            boolean hunterCausingCombatHere = onLoc.stream()
                    .anyMatch(p -> "HUNTER".equals(p.getRole())
                            && p.getHp() > 0
                            && !unstableHarvesters.contains(p.getId()));

            // Combat s’il y a au moins un chasseur + (ennemi joueur OU monstre)
            // NERF CLONES : on ne compte plus les clones ici pour ne pas impacter la
            // récolte
            boolean combatHere = (enemyHere || monsterHere) && hunterCausingCombatHere;

            for (var p : onLoc) {
                boolean harvestForVamp = g.getUnstableHarvestLocByPlayer() != null
                        && g.getUnstableHarvestLocByPlayer().containsKey(p.getId());

                boolean isVamp = "VAMPIRE".equals(p.getRole());
                boolean isHunter = "HUNTER".equals(p.getRole());

                // 1) Voile de brume : divise la récolte par 2 si chasseur sur le lieu affecté
                boolean fogAffected = false;
                if (g.getFogAffectedLocation() != null
                        && isHunter
                        && !harvestForVamp
                        && g.getFogAffectedLocation().equals(loc)) {
                    fogAffected = true;
                }

                // 2) Duel instable -> cible :
                // - instable ne récolte pas
                if (duelUnstables.contains(p.getId()))
                    continue;

                // 3) Détermine si la récolte doit être divisée par 2
                boolean halfHarvest = false;

                // - la cible du duel récolte /2
                if (duelTargets.contains(p.getId()))
                    halfHarvest = true;

                // - si combat prévu : chasseur et serviteur récoltent /2
                // (mais on garde les exceptions : instable harvestForVamp + vampire)
                if (combatHere && !harvestForVamp && !isVamp) {
                    halfHarvest = true;
                }

                // - Voile de brume : chasseur sur le lieu affecté récolte /2
                if (fogAffected) {
                    halfHarvest = true;
                }

                if (skipHarvestBecauseOfConstruction(g, p, loc)) {
                    continue;
                }

                Player recipient = (harvestForVamp && vamp != null) ? vamp : p;

                java.util.List<String> gains = new java.util.ArrayList<>();
                switch (loc) {
                    case "forest" -> {
                        int wood = halfHarvest ? (2 / 2) : 2;
                        int herbs = halfHarvest ? (4 / 2) : 4;

                        grant(recipient, "wood", wood);
                        gains.add("+" + wood + " bois"
                                + (fogAffected ? " (base: 2, divisé par 2 par Voile de brume)" : ""));
                        grant(recipient, "herbs", herbs);
                        gains.add("+" + herbs + " herbe médicinale"
                                + (fogAffected ? " (base: 4, divisé par 2 par Voile de brume)" : ""));
                    }
                    case "quarry" -> {
                        int iron = halfHarvest ? (2 / 2) : 2;
                        int stone = halfHarvest ? (4 / 2) : 4;

                        grant(recipient, "iron", iron);
                        gains.add("+" + iron + " fer"
                                + (fogAffected ? " (base: 2, divisé par 2 par Voile de brume)" : ""));
                        grant(recipient, "stone", stone);
                        gains.add("+" + stone + " pierre"
                                + (fogAffected ? " (base: 4, divisé par 2 par Voile de brume)" : ""));
                    }
                    case "lake" -> {
                        int herbs = halfHarvest ? (2 / 2) : 2;
                        int water = halfHarvest ? (4 / 2) : 4;

                        grant(recipient, "herbs", herbs);
                        gains.add("+" + herbs + " herbe médicinale"
                                + (fogAffected ? " (base: 2, divisé par 2 par Voile de brume)" : ""));
                        grant(recipient, "water", water);
                        gains.add("+" + water + " eau pure"
                                + (fogAffected ? " (base: 4, divisé par 2 par Voile de brume)" : ""));
                    }
                    case "manor" -> {
                        int roll = rollD100Tens();
                        int baseAmt = roll + 100;
                        int finalAmt = halfHarvest ? (baseAmt / 2) : baseAmt;
                        if (halfHarvest) {
                            // arrondir à la dizaine supérieure
                            finalAmt = (int) (Math.ceil(finalAmt / 10.0) * 10);
                        }

                        if (harvestForVamp && vamp != null) {
                            // Instable qui récolte pour le vampire au Manoir → conversion en âmes
                            // (Note: pour le vampire on ne divise pas, car harvestForVamp=true ->
                            // halfHarvest est false par défaut, sauf si Voile est sur lui?)
                            // fogAffected=false -> halfHarvest=false (sauf duelTargets)
                            // Donc ici on applique finalAmt, qui est égal à baseAmt si halfHarvest=false.
                            // Si par hasard halfHarvest=true (ex: duelTargets), alors oui on divise.
                            grant(vamp, "souls", finalAmt);
                            gains.add("+" + finalAmt + " âmes déchues (pour " + nameOf(g, vamp.getId()) + ")"
                                    + (halfHarvest
                                            ? " (base: " + baseAmt + ", divisé par 2"
                                                    + (fogAffected ? " par Voile de brume" : "") + ")"
                                            : ""));
                        } else {
                            if ("HUNTER".equals(p.getRole())) {
                                grant(p, "gold", finalAmt);
                                gains.add("+" + finalAmt + " or"
                                        + (halfHarvest
                                                ? " (base: " + baseAmt + ", divisé par 2"
                                                        + (fogAffected ? " par Voile de brume" : "") + ")"
                                                : ""));
                            } else if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                                grant(p, "souls", finalAmt);
                                gains.add("+" + finalAmt + " âmes déchues"
                                        + (halfHarvest
                                                ? " (base: " + baseAmt + ", divisé par 2"
                                                        + (fogAffected ? " par Voile de brume" : "") + ")"
                                                : ""));
                            }
                        }
                    }
                    case "sawmill" -> {
                        int base = 4;
                        int wood = halfHarvest ? (base / 2) : base;
                        if (wood <= 0)
                            wood = 1;

                        grant(recipient, "wood", wood);
                        gains.add("+" + wood + " bois"
                                + (fogAffected ? " (base: " + base + ", divisé par 2 par Voile de brume)" : ""));
                    }

                    case "mine" -> {
                        int base = 4;
                        int iron = halfHarvest ? (base / 2) : base;
                        if (iron <= 0)
                            iron = 1;

                        grant(recipient, "iron", iron);
                        gains.add("+" + iron + " fer"
                                + (fogAffected ? " (base: " + base + ", divisé par 2 par Voile de brume)" : ""));
                    }

                    case "library", "laboratory", "ballroom", "altar", "forge" -> {
                        int roll = rollD100Tens();
                        int baseAmt = roll + 50;
                        int finalAmt = halfHarvest ? (baseAmt / 2) : baseAmt;
                        if (halfHarvest) {
                            // arrondir à la dizaine supérieure
                            finalAmt = (int) (Math.ceil(finalAmt / 10.0) * 10);
                        }

                        if (harvestForVamp && vamp != null) {
                            // Instable qui récolte pour le vampire → âmes
                            grant(vamp, "souls", finalAmt);
                            gains.add("+" + finalAmt + " âmes déchues (pour " + nameOf(g, vamp.getId()) + ")"
                                    + (halfHarvest
                                            ? " (base: " + baseAmt + ", divisé par 2"
                                                    + (fogAffected ? " par Voile de brume" : "") + ")"
                                            : ""));
                        } else {
                            // Récolte normale sur les lieux spéciaux du Manoir :
                            // - Chasseur : or
                            // - Vampire / Serviteur : âmes
                            if ("HUNTER".equals(p.getRole())) {
                                grant(recipient, "gold", finalAmt);
                                gains.add("+" + finalAmt + " or"
                                        + (halfHarvest
                                                ? " (base: " + baseAmt + ", divisé par 2"
                                                        + (fogAffected ? " par Voile de brume" : "") + ")"
                                                : ""));
                            } else if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                                grant(recipient, "souls", finalAmt);
                                gains.add("+" + finalAmt + " âmes déchues"
                                        + (halfHarvest
                                                ? " (base: " + baseAmt + ", divisé par 2"
                                                        + (fogAffected ? " par Voile de brume" : "") + ")"
                                                : ""));
                            }
                        }
                    }
                    default -> {
                        /* plus tard */ }
                }

                if (!gains.isEmpty()) {
                    String who = (harvestForVamp && vamp != null)
                            ? (nameOf(g, p.getId()) + " pour " + nameOf(g, vamp.getId()))
                            : nameOf(g, p.getId());

                    String line = "Récoltes — " + who + " (" + labelLieuFr(loc) + ") : " + String.join(", ", gains);
                    addHistory(g, line);
                }
            }
        }
    }

    private @Nullable String pickStealableFromHunter(Player h) {
        // Ressources volables chez un chasseur (ni or, ni argent)
        java.util.List<String> pool = new java.util.ArrayList<>();
        if (h.getWood() > 0)
            pool.add("wood");
        if (h.getHerbs() > 0)
            pool.add("herbs");
        if (h.getStone() > 0)
            pool.add("stone");
        if (h.getIron() > 0)
            pool.add("iron");
        if (h.getWater() > 0)
            pool.add("water");
        return pool.isEmpty() ? null : pool.get(dice.nextInt(pool.size()));
    }

    private @Nullable String vampStealOne(Game g, Player vamp, Player hunter) {
        String res = pickStealableFromHunter(hunter);
        if (res == null) {
            addHistory(g, nameOf(g, vamp.getId()) + " tente de voler, mais " + nameOf(g, hunter.getId())
                    + " n'a rien à prendre.");
            return null; // rien à afficher en breakdown
        }
        // retire au chasseur
        switch (res) {
            case "wood" -> hunter.setWood(hunter.getWood() - 1);
            case "herbs" -> hunter.setHerbs(hunter.getHerbs() - 1);
            case "stone" -> hunter.setStone(hunter.getStone() - 1);
            case "iron" -> hunter.setIron(hunter.getIron() - 1);
            case "water" -> hunter.setWater(hunter.getWater() - 1);
        }
        // donne au vampire
        grant(vamp, res, 1);

        String line = "Larcin — " + nameOf(g, vamp.getId()) + " vole 1 " + resLabelFr(res) + " à "
                + nameOf(g, hunter.getId()) + ".";
        addHistory(g, line);

        return line; // ← on renvoie la ligne pour la modale spectateur
    }

    // actions & potions
    private RaidEffects raidFx(Game g, String playerId) {
        return g.getRaidEffects().computeIfAbsent(playerId, __ -> new RaidEffects());
    }

    /**
     * Tous les joueurs concernés par un combat imminent :
     * - chasseurs impliqués
     * - vampire / serviteurs impliqués
     * - instables + leurs cibles
     * - chasseurs attaqués par des clones d'ombre
     */
    private Set<String> participantsOfUpcomingCombat(Game g) {
        Set<String> ids = new java.util.HashSet<>();

        if (g.getPlayers() == null || g.getPlayers().isEmpty()) {
            return ids;
        }

        // Instables affectés à la RÉCOLTE => ne combattent pas
        java.util.Set<String> unstableHarvesters = (g.getUnstableHarvestLocByPlayer() != null)
                ? g.getUnstableHarvestLocByPlayer().keySet()
                : java.util.Set.of();

        // Monstres vivants
        java.util.List<Game.Monster> aliveMonsters = (g.getMonsters() != null)
                ? g.getMonsters().stream()
                        .filter(m -> m.hp > 0)
                        .toList()
                : java.util.List.of();

        var groups = groupPlayersByLocation(g); // loc -> List<Player>

        // --- 1) Combats "classiques" (vamp / serviteurs / monstres) ---
        for (var e : groups.entrySet()) {
            String loc = e.getKey();
            java.util.List<Player> onLoc = e.getValue();

            // chasseurs vivants non récolteurs
            var hunters = onLoc.stream()
                    .filter(p -> "HUNTER".equals(p.getRole())
                            && isAlive(p)
                            && !unstableHarvesters.contains(p.getId()))
                    .toList();

            // ennemis côté vampire (vamp + serviteurs)
            var enemies = onLoc.stream()
                    .filter(p -> isAlive(p) &&
                            ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())))
                    .toList();

            // monstre présent sur ce lieu ?
            boolean hasMonsterHere = aliveMonsters.stream()
                    .anyMatch(m -> loc.equals(m.location));

            boolean hasHunters = !hunters.isEmpty();
            boolean hasEnemyPlayers = !enemies.isEmpty();

            if (hasHunters && (hasEnemyPlayers || hasMonsterHere)) {
                // tous les chasseurs impliqués sur ce lieu
                hunters.forEach(p -> ids.add(p.getId()));
                // et les joueurs côté vampire présents (si il y en a)
                enemies.forEach(p -> ids.add(p.getId()));
            }
        }

        // --- 2) Duels instables explicites ---
        if (g.getUnstableTargetByPlayer() != null) {
            for (var entry : g.getUnstableTargetByPlayer().entrySet()) {
                String unstableId = entry.getKey();
                String targetId = entry.getValue();
                if (unstableId != null)
                    ids.add(unstableId);
                if (targetId != null)
                    ids.add(targetId);
            }
        }

        // --- 3) Clones des ombres : les chasseurs ciblés par un clone sont aussi
        // "participants" ---
        if (g.getClonesLocations() != null && !g.getClonesLocations().isEmpty()) {
            for (String loc : g.getClonesLocations()) {
                var onLoc = groups.get(loc);
                if (onLoc == null || onLoc.isEmpty())
                    continue;

                var hunters = onLoc.stream()
                        .filter(p -> "HUNTER".equals(p.getRole())
                                && isAlive(p)
                                && !unstableHarvesters.contains(p.getId()))
                        .toList();

                // On n'ajoute que les chasseurs : le vampire peut être ailleurs
                if (!hunters.isEmpty()) {
                    hunters.forEach(p -> ids.add(p.getId()));
                }
            }
        }

        return ids;
    }

    @Transactional
    public Game usePotion(String gameId, String playerId, Potion type) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        // Uniquement en PREPHASE3, s'il y a un combat imminent, et si je suis concerné
        if (g.getPhase() != Phase.PREPHASE3 || !g.isHasUpcomingCombat())
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "potions usable only during PREPHASE3 before combat");

        Set<String> allowed = participantsOfUpcomingCombat(g);
        if (!allowed.contains(playerId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "you are not part of the upcoming combat");

        WeatherStatus ws1 = g.getWeatherStatus();
        WeatherStatus ws2 = g.getSecondaryWeatherStatus();
        if (ws1 == WeatherStatus.BLIZZARD || ws2 == WeatherStatus.BLIZZARD) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Impossible d'utiliser des potions sous le blizzard: elles sont gelées.");
        }

        // bloque les instables qui succombent à la corruption
        if (g.getPhase() == Phase.PREPHASE3 && hasSuccumbedToCorruption(g, playerId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu succombes à la corruption : tu ne peux pas utiliser tes potions ce raid.");
        }

        // --- inventaire sur Player
        Player p = g.getPlayers().stream()
                .filter(pp -> pp.getId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game"));

        List<String> potionInv = p.getPotions();
        List<String> elixirInv = p.getElixirs();

        boolean hasPotion = potionInv != null && potionInv.contains(type.name());
        boolean hasElixir = elixirInv != null && elixirInv.contains(type.name());

        if (!hasPotion && !hasElixir) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "item not in inventory");
        }

        if (!isAlive(p)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }

        // --- mutations + historique (PAS d'events ici)
        String feedText;

        switch (type) {
            case FORCE -> {
                if (g.getRaidMods() == null)
                    g.setRaidMods(new HashMap<>());
                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("ATTACK", +1, "POTION:FORCE"));
                discardPotion(g, type.name());

                addHistory(g, nameOf(g, playerId) + " utilise une potion de force.");
                feedText = nameOf(g, playerId) + " boit une potion de force !";
            }
            case ENDURANCE -> {
                if (g.getRaidMods() == null)
                    g.setRaidMods(new HashMap<>());
                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("DEFENSE", +1, "POTION:ENDURANCE"));
                discardPotion(g, type.name());

                addHistory(g, nameOf(g, playerId) + " utilise une potion d’endurance.");
                feedText = nameOf(g, playerId) + " boit une potion d’endurance !";
            }
            case VIE -> {
                int before = p.getHp();
                int max = ("VAMPIRE".equals(p.getRole()))
                        ? (20 + (g.getInitialPlayerCount() - 1) * 5)
                        : 20;

                int d6 = dice.roll(6);
                int amount = 2 + d6;

                p.setHp(Math.min(max, p.getHp() + amount));
                int healed = p.getHp() - before;

                discardPotion(g, type.name());

                addHistory(g, nameOf(g, playerId)
                        + " utilise une potion de vie et régénère " + healed + " pv (2 + d6=" + d6 + ").");
                feedText = nameOf(g, playerId) + " boit une potion de vie !";
            }
            case FOCALISATION -> {
                var fx = raidFx(g, playerId);
                fx.setFocus(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("FOCALISATION", 0, "POTION:FOCALISATION:DSP"));
                discardPotion(g, type.name());

                addHistory(g, nameOf(g, playerId)
                        + " utilise une potion de focalisation.");
                feedText = nameOf(g, playerId) + " boit une potion de focalisation !";
            }
            case SANGSUE -> {
                var fx = raidFx(g, playerId);
                fx.setLeech(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("SANGSUE", 0, "POTION:SANGSUE:DSP"));
                discardPotion(g, type.name());

                addHistory(g, nameOf(g, playerId) +
                        " utilise une potion de sangsue.");
                feedText = nameOf(g, playerId) + " boit une potion de sangsue !";
            }
            case RAGE -> {
                if (p.isElixirUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà utilisé un élixir ce raid.");
                }
                var fx = raidFx(g, playerId);
                fx.setDoubleAttack(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("RAGE", 0, "POTION:RAGE:DSP"));

                discardElixir(g, type.name());
                p.setElixirUsedThisRaid(true);

                addHistory(g, nameOf(g, playerId)
                        + " utilise un elixir de rage.");
                feedText = nameOf(g, playerId) + " boit un elixir de rage !";
            }
            case RESILIENCE -> {
                if (p.isElixirUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà utilisé un élixir ce raid.");
                }
                var fx = raidFx(g, playerId);
                fx.setDoubleDefense(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("RESILIENCE", 0, "POTION:RESILIENCE:DSP"));

                discardElixir(g, type.name());
                p.setElixirUsedThisRaid(true);

                addHistory(g, nameOf(g, playerId)
                        + " utilise un elixir de résilience.");
                feedText = nameOf(g, playerId) + " boit un elixir de résilience !";
            }
            case RAPIDITE -> {
                if (p.isElixirUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà utilisé un élixir ce raid.");
                }
                var fx = raidFx(g, playerId);
                fx.setRapid(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("RAPIDITE", 0, "POTION:RAPIDITE:DSP"));

                discardElixir(g, type.name());
                p.setElixirUsedThisRaid(true);

                addHistory(g, nameOf(g, playerId)
                        + " utilise un elixir de rapidité.");
                feedText = nameOf(g, playerId) + " boit un elixir de rapidité !";
            }
            case INVISIBILITE -> {
                if (p.isElixirUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà utilisé un élixir ce raid.");
                }
                var fx = raidFx(g, playerId);
                fx.setInvisible(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("INVISIBILITE", 0, "POTION:INVISIBILITE:DSP"));

                discardElixir(g, type.name());
                p.setElixirUsedThisRaid(true);

                addHistory(g, nameOf(g, playerId)
                        + " utilise un elixir d’invisibilité.");
                feedText = nameOf(g, playerId) + " boit un elixir d’invisibilité !";
            }
            case INVULNERABILITE -> {
                if (p.isElixirUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà utilisé un élixir ce raid.");
                }
                var fx = raidFx(g, playerId);
                fx.setInvulnerable(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("INVULNERABILITE", 0, "POTION:INVULNERABILITE:DSP"));

                discardElixir(g, type.name());
                p.setElixirUsedThisRaid(true);

                addHistory(g, nameOf(g, playerId)
                        + " utilise un elixir d’invulnérabilité.");
                feedText = nameOf(g, playerId) + " boit un elixir d’invulnérabilité !";
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown potion");
        }

        // consommer l’item sur le Player
        if (hasPotion) {
            potionInv.remove(type.name());
        } else {
            elixirInv.remove(type.name());
        }

        // --- commit
        save(g);

        // --- events APRÈS COMMIT
        afterCommit(() -> {
            pushLive(g, feedText);
            live.potionUsed(g, playerId, type.name());
            live.raidModsUpdated(g);
        });

        return g;
    }

    private int maxHpFor(Game g, Player p) {
        if ("VAMPIRE".equals(p.getRole())) {
            return 20 + (g.getInitialPlayerCount() - 1) * 10;
        }
        return 20;
    }

    private boolean hasInvisibility(Game g, String playerId) {
        if (g.getRaidEffects() == null)
            return false;
        RaidEffects fx = g.getRaidEffects().get(playerId);
        return fx != null && fx.isInvisible();
    }

    private void pushLive(Game g, String msg) {
        if (g.getMessages() == null)
            g.setMessages(new ArrayList<>());
        g.getMessages().add(msg);
        // notifie le front (déjà géré dans onLiveEvent: MESSAGE)
        live.message(g, msg);
    }

    private boolean hasSuccumbedToCorruption(Game g, String playerId) {
        var eligTargets = g.getUnstableEligibleTargets();
        var eligLocs = g.getUnstableEligibleLocations();

        boolean markedAtk = (eligTargets != null && eligTargets.containsKey(playerId));
        boolean markedHarv = (eligLocs != null && eligLocs.containsKey(playerId));

        // si le jet de corruption a "foiré" pour ce joueur, on l'a mis dans l'une des
        // deux maps
        return markedAtk || markedHarv;
    }

    @Transactional
    public Game useAction(String gameId, String playerId, Action type) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        Player p = findPlayer(g, playerId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        if (!isAlive(p)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }

        // Bloque les serviteurs (corruption >= 3) d'utiliser des actions
        if (p.getCorruption() >= 3) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Les serviteurs ne peuvent pas utiliser d'actions.");
        }

        // --- FIX: Si une action non-bloquante (info seule) traîne, on la nettoie ---
        Game.Action current = g.getCurrentAction();
        if (current != null && !isBlockingAction(current)) {
            g.setCurrentAction(null);
        }

        boolean isHunter = "HUNTER".equals(p.getRole());
        boolean isVamp = "VAMPIRE".equals(p.getRole());

        // bloque les instables qui succombent à la corruption
        if (g.getPhase() == Phase.PREPHASE3 && hasSuccumbedToCorruption(g, playerId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu succombes à la corruption : tu ne peux pas utiliser tes cartes ce raid.");
        }

        // Présence écrasante du vampire bloque les actions des chasseurs
        if (isHunter
                && g.isHunterActionsBlockedThisRaid()
                && (g.getPhase() == Phase.PREPHASE3 || g.getPhase() == Phase.PHASE3)) {

            String hunterLoc = locationOf(g, playerId);
            String vampLoc = null;
            var vampOpt = getVamp(g);
            if (vampOpt.isPresent()) {
                vampLoc = locationOf(g, vampOpt.get().getId());
            }

            if (hunterLoc != null
                    && vampLoc != null
                    && java.util.Objects.equals(hunterLoc, vampLoc)) {

                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "La présence écrasante du vampire t'empêche d'utiliser des cartes d'action sur ce lieu ce raid.");
            }
        }

        // Inventaire sur Player
        List<String> inv = p.getActions();
        if (inv == null || !inv.contains(type.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
        }

        String feedText;

        switch (type) {
            case EAU_BENITE -> {
                if (!isHunter) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseur uniquement");
                }

                // Pas deux actions bloquantes en même temps
                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // currentAction = ouverture de la modale de choix
                Game.Action a = new Game.Action();
                a.setMode("EAU_BENITE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new java.util.ArrayList<>());
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                String msg = nameOf(g, playerId)
                        + " brandit une fiole d'eau bénite et doit choisir comment l'utiliser.";
                addHistory(g, msg);
                feedText = msg;

                if (g.getPhase() == Phase.PREPHASE3) {
                    g.setPrephaseTimerVersion(g.getPrephaseTimerVersion() + 1);
                }

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }
            case FUMIGATION_AIL -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PHASE1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "cette action est utilisable uniquement pendant la PHASE1");

                // Fumigation doit être jouée AVANT de choisir le lieu
                if (hasPlayed(g, playerId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu as déjà choisi ton lieu : Fumigation d’ail doit être jouée avant.");
                }

                applyFumigationAilPrepare(g, p);

                // consommer l’action sur le Player
                inv.remove(type.name());

                // 4) l’envoyer en défausse
                if (isHunter)
                    discardHunterAction(g, type.name());
                if (isVamp)
                    discardVampAction(g, type.name());

                save(g);

                final String fType = type.name();
                final String fUserId = playerId;

                afterCommit(() -> {
                    live.actionUsed(g, fUserId, fType);
                });
            }
            case PISTEUR -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PHASE1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "cette action est utilisable uniquement pendant la PHASE1");

                // si une fumigation est préparée, on ne peut PAS jouer Pisteur à la main
                if (g.getPendingGarlicPlayers() != null
                        && g.getPendingGarlicPlayers().contains(playerId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Impossible d'utiliser pisteur pendant une préparation de fumigation.");
                }

                // Est-ce que, AVANT Pisteur, je comptais déjà comme “ayant joué” ?
                boolean alreadyPlayed = hasPlayed(g, playerId);

                if (g.getTrackerHunters() == null) {
                    g.setTrackerHunters(new java.util.HashSet<>());
                }
                if (g.getTrackerHunters().contains(playerId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu suis déjà la piste du vampire.");
                }

                g.getTrackerHunters().add(playerId);
                addHistory(g, nameOf(g, playerId) + " joue Pisteur et traque le vampire.");
                feedText = nameOf(g, playerId) + " se met sur la piste du vampire.";

                // Si je n'avais pas encore “joué” avant, Pisteur peut finir ma phase.
                boolean advanceToP2 = (g.getPhase() == Phase.PHASE1)
                        && !alreadyPlayed
                        && allHuntersSelected(g);

                // consommer l’action sur le Player
                inv.remove(type.name());

                // si c'est une carte bonus boutique -> elle disparaît, PAS de discard
                boolean wasShopBonus = false;
                Integer c = p.getShopPisteurCount();
                if (c != null && c > 0) {
                    p.setShopPisteurCount(c - 1);
                    wasShopBonus = true;
                }

                // 4) envoyer en défausse
                if (!wasShopBonus) {
                    if (isHunter)
                        discardHunterAction(g, type.name());
                    if (isVamp)
                        discardVampAction(g, type.name());
                }

                save(g);

                final String gid = g.getId();
                final boolean fAdvance = advanceToP2;
                final String fFeed = feedText;

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, playerId, type.name());
                });

                if (advanceToP2) {
                    scheduleAdvance(gid, Phase.PHASE1, Phase.PHASE2, 2500);
                }
            }
            case FEU_DE_CAMP -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Cette action est utilisable juste avant les combats (PREPHASE3).");
                }

                // Météo doit être crépuscule / nuit obscure / nuit claire
                WeatherStatus ws = g.getWeatherStatus();
                if (ws == null
                        || (ws != WeatherStatus.DUSK
                                && ws != WeatherStatus.NIGHT_DARK
                                && ws != WeatherStatus.NIGHT_CLEAR)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Feu de camp n’a d’effet que par temps de Crépuscule, Nuit obscure ou Nuit claire.");
                }

                // Il faut que le chasseur soit sur un lieu (center)
                String loc = g.getCenter().stream()
                        .filter(cb -> playerId.equals(cb.getPlayerId()))
                        .map(CenterBoard::getCard)
                        .findFirst()
                        .orElse(null);

                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu dois être sur un lieu pour utiliser Feu de camp.");
                }

                if (g.getCampfireLocations() == null) {
                    g.setCampfireLocations(new java.util.HashSet<>());
                }
                if (g.getCampfireLocations().contains(loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "un Feu de camp est déjà allumé à " + labelLieuFr(loc) + ".");
                }

                // consommer l’action sur le Player
                inv.remove(type.name());

                // 4) l’envoyer en défausse
                if (isHunter)
                    discardHunterAction(g, type.name());
                if (isVamp)
                    discardVampAction(g, type.name());

                // Marque ce lieu comme protégé par un feu de camp
                g.getCampfireLocations().add(loc);

                String wsName = weatherNameFr(ws);
                String msg = nameOf(g, playerId)
                        + " allume un feu de camp à " + labelLieuFr(loc)
                        + " et annule les effets de " + wsName.toLowerCase() + " sur ce lieu.";
                addHistory(g, msg);
                feedText = msg;

                save(g);

                final String fFeed = feedText;

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, playerId, type.name());
                });
            }
            case NET -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }
                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Filet est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                // Doit participer à un combat, sinon on pourrait lui laisser la jouer pour
                // rien.
                String loc = locationOf(g, playerId);
                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu dois être sur un lieu pour utiliser Filet.");
                }

                if (!hasTrapTargetsOnLocation(g, loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Filet n’a d’effet que s’il y a au moins un adversaire sur ton lieu.");
                }

                if (g.getWeatherStatus() == WeatherStatus.NIGHT_DARK && !isCampfireCancellingWeather(g, loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Nuit obscure : piège interdit sans feu de camp sur ce lieu.");
                }

                // Marque ce chasseur comme ayant un Filet préparé pour ce raid
                g.getNetHunters().add(playerId);

                // Incrémenter le compteur de cartes NET pour ce chasseur
                if (g.getNetCardsRemaining() == null) {
                    g.setNetCardsRemaining(new java.util.HashMap<>());
                }
                int currentCount = g.getNetCardsRemaining().getOrDefault(playerId, 0);
                g.getNetCardsRemaining().put(playerId, currentCount + 1);

                // consommer l’action sur le Player
                inv.remove(type.name());

                // 4) l’envoyer en défausse
                if (isHunter)
                    discardHunterAction(g, type.name());
                if (isVamp)
                    discardVampAction(g, type.name());

                String msg = nameOf(g, playerId)
                        + " prépare un Filet pour piéger un adversaire.";
                addHistory(g, msg);
                feedText = msg;

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType); // pour refresh front
                });

                return g;
            }
            case PIT -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }
                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Fosse est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                String loc = locationOf(g, playerId);
                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu dois être sur un lieu pour utiliser Fosse.");
                }

                var enemies = vampSideOnLocation(g, loc);
                var monsters = monstersOnLocation(g, loc);

                if (enemies.isEmpty() && monsters.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Fosse n’a d’effet que s’il y a au moins un ennemi sur ton lieu.");
                }

                if (g.getWeatherStatus() == WeatherStatus.NIGHT_DARK && !isCampfireCancellingWeather(g, loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Nuit obscure : piège interdit sans feu de camp sur ce lieu.");
                }

                // Marque ce chasseur comme ayant une Fosse préparée
                g.getPitHunters().add(playerId);

                // Nouveau : enregistre les victimes pour ce chasseur (joueurs + monstres)
                if (g.getPitTargetsByHunter() == null)
                    g.setPitTargetsByHunter(new java.util.HashMap<>());
                if (g.getPitCardsCount() == null)
                    g.setPitCardsCount(new java.util.HashMap<>());
                if (g.getPitIndexByHunter() == null)
                    g.setPitIndexByHunter(new java.util.HashMap<>());

                var victimIds = new java.util.ArrayList<String>();
                enemies.forEach(vv -> victimIds.add(vv.getId()));
                monsters.forEach(mm -> victimIds.add(mm.id));

                // On remplace la liste de victimes (snapshot actue) pour ce hunter
                g.getPitTargetsByHunter().put(playerId, victimIds);
                g.getPitIndexByHunter().put(playerId, 0);

                // On incrémente le nombre de cartes PIT jouées
                if (g.getPitCardsCount() == null) {
                    g.setPitCardsCount(new java.util.HashMap<>());
                }
                int currentCount = g.getPitCardsCount().getOrDefault(playerId, 0);
                g.getPitCardsCount().put(playerId, currentCount + 1);

                // consommer l’action sur le Player
                inv.remove(type.name());

                // 4) l’envoyer en défausse
                if (isHunter)
                    discardHunterAction(g, type.name());
                if (isVamp)
                    discardVampAction(g, type.name());

                String msg = nameOf(g, playerId)
                        + " prépare une Fosse pour ses adversaires.";
                addHistory(g, msg);
                feedText = msg;

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case PROVOCATION -> {
                if (!isHunter) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Provocation est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                if (hasBlockingActionInProgress(g)) {
                    // Exception : Provocation peut interrompre Passage Secret ou Image Miroir
                    Game.Action cur = g.getCurrentAction();
                    boolean canInterrupt = cur != null && ("PASSAGE_SECRET".equals(cur.getMode())
                            || "IMAGE_MIROIR_SETUP".equals(cur.getMode())
                            || "IMAGE_MIROIR_RESOLVE".equals(cur.getMode()));

                    if (!canInterrupt) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Une autre action est déjà en cours de résolution.");
                    } else {
                        // On interrompt l'action en cours
                        addHistory(g, "Provocation — " + nameOf(g, playerId) + " interrompt la concentration de "
                                + nameOf(g, cur.getOwnerId()) + " !");
                        // L'action précédente est écrasée par la suite
                    }
                }

                String loc = locationOf(g, playerId);
                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu dois être sur un lieu pour utiliser Provocation.");
                }

                // Joueurs sur ce lieu
                java.util.List<Player> onLoc = g.getPlayers().stream()
                        .filter(pl -> loc.equals(locationOf(g, pl.getId())))
                        .toList();

                long huntersCount = onLoc.stream()
                        .filter(pl -> "HUNTER".equals(pl.getRole()))
                        .filter(pl -> pl.getHp() > 0)
                        .count();

                long enemyCount = onLoc.stream()
                        .filter(pl -> ("VAMPIRE".equals(pl.getRole()) || "SERVANT".equals(pl.getRole())))
                        .filter(pl -> pl.getHp() > 0)
                        .count();

                if (enemyCount < 1) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Provocation nécessite au moins 1 ennemi sur ton lieu.");
                }

                // Consommer la carte dans l'inventaire du chasseur
                if (inv == null || !inv.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                discardHunterAction(g, type.name());

                // currentAction : choix de l'ennemi dans la modale
                Game.Action a = new Game.Action();
                a.setMode("PROVOCATION");
                a.setOwnerId(playerId);
                a.setLocation(loc);
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new java.util.ArrayList<>());
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                String msg = "Provocation — "
                        + nameOf(g, playerId)
                        + " attire la colère de ses ennemis.";
                addHistory(g, msg);
                feedText = msg;

                // On annule/redémarre le timer de préphase (comme pour Marque /
                // Affaiblissement)
                if (g.getPhase() == Phase.PREPHASE3) {
                    g.setPrephaseTimerVersion(g.getPrephaseTimerVersion() + 1);
                }

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case INCENDIAIRE -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Incendiaire est utilisable uniquement pendant la PREPHASE3.");
                }

                String loc = locationOf(g, playerId);
                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu dois être sur un lieu pour utiliser Incendiaire.");
                }

                // 1) Consommer la carte
                inv.remove(type.name());
                discardHunterAction(g, type.name());

                // 2) Enregistrer la préparation : ce chasseur brûlera potentiellement ce lieu
                // en PHASE3
                g.getIncendiaireLocationByHunter().put(playerId, loc);

                String msg = nameOf(g, playerId)
                        + " prépare une action Incendiaire à " + labelLieuFr(loc) + ".";
                addHistory(g, msg);
                feedText = msg;

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case AMBUSH -> {
                if (!isHunter) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Embuscade est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                String loc = locationOf(g, playerId);
                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Tu dois être sur un lieu pour utiliser Embuscade.");
                }

                // Joueurs sur ce lieu
                java.util.List<Player> onLoc = g.getPlayers().stream()
                        .filter(pl -> loc.equals(locationOf(g, pl.getId())))
                        .toList();

                long huntersCount = onLoc.stream()
                        .filter(pl -> "HUNTER".equals(pl.getRole()))
                        .filter(pl -> pl.getHp() > 0)
                        .count();

                long enemyCount = onLoc.stream()
                        .filter(pl -> "VAMPIRE".equals(pl.getRole()) || "SERVANT".equals(pl.getRole()))
                        .filter(pl -> pl.getHp() > 0)
                        .count();

                if (huntersCount < 2 || enemyCount < 1) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Embuscade nécessite au moins 2 chasseurs et 1 ennemi (vampire ou serviteur) sur ton lieu.");
                }

                if (g.getAmbushLocations() != null && g.getAmbushLocations().contains(loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une embuscade a déjà été effectuée sur ce lieu ce raid.");
                }

                // Consommer la carte dans l'inventaire du chasseur
                if (inv == null || !inv.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                discardHunterAction(g, type.name());

                // currentAction : choix de l'ennemi dans la modale
                Game.Action a = new Game.Action();
                a.setMode("AMBUSH");
                a.setOwnerId(playerId);
                a.setLocation(loc);
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new java.util.ArrayList<>());
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                String msg = "Embuscade — "
                        + nameOf(g, playerId)
                        + " prépare une attaque coordonnée avec les chasseurs présents.";
                addHistory(g, msg);
                feedText = msg;

                // On annule/redémarre le timer de préphase (comme Marque / Affaiblissement)
                if (g.getPhase() == Phase.PREPHASE3) {
                    g.setPrephaseTimerVersion(g.getPrephaseTimerVersion() + 1);
                }

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case LONELY -> {
                if (!isHunter)
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                if (g.getPhase() != Phase.PREPHASE3)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Solitaire utilisable uniquement en PREPHASE3.");

                if (hasBlockingActionInProgress(g))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");

                String loc = locationOf(g, playerId);
                if (loc == null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Tu dois être sur un lieu pour utiliser Solitaire.");

                // consomme la carte
                inv.remove(type.name());
                discardHunterAction(g, type.name());

                // Hunters éligibles = vivants + pas récolteur instable
                var harvestMap = g.getUnstableHarvestLocByPlayer();
                java.util.function.Predicate<Player> eligibleHunter = pl -> "HUNTER".equals(pl.getRole())
                        && pl.getHp() > 0
                        && (harvestMap == null || !harvestMap.containsKey(pl.getId()));

                var eligibleHunters = g.getPlayers().stream().filter(eligibleHunter).toList();

                long huntersHere = eligibleHunters.stream()
                        .filter(pl -> loc.equals(locationOf(g, pl.getId())))
                        .count();

                int absent = Math.max(0, eligibleHunters.size() - (int) huntersHere);

                // mods
                addRaidMod(g, playerId, "ATTACK", absent, "ACTION:LONELY:ENG");
                addRaidMod(g, playerId, "DEFENSE", absent, "ACTION:LONELY:ENG");

                String msg = "Solitaire — " + nameOf(g, playerId)
                        + " gagne +" + absent + " ATK et +" + absent + " DEF (" + absent + " absent(s)).";
                addHistory(g, msg);
                feedText = msg;

                // currentAction informatif (comme ECLIPSE)
                Game.Action a = new Game.Action();
                a.setMode("LONELY");
                a.setOwnerId(playerId);
                a.setLocation(loc);
                a.setTargetId(null);
                a.setRoll(absent); // on réutilise roll pour passer le bonus au front
                a.setBreakdownLines(new java.util.ArrayList<>(java.util.List.of(
                        "Solitaire : +" + absent + " ATK et +" + absent + " DEF.",
                        absent + " chasseur(s) absent(s) sur ce lieu.")));
                a.setResolvedAtMillis(System.currentTimeMillis());
                g.setCurrentAction(a);

                // reset timer prephase (comme ECLIPSE)
                int newVersion = g.getPrephaseTimerVersion() + 1;
                g.setPrephaseTimerVersion(newVersion);

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();
                final int version = newVersion;

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.raidModsUpdated(g);
                    live.actionUsed(g, fUserId, fType);
                    schedulePrephaseTimeout(gameId, 30_000L, version);
                });

                return g;
            }

            case BLESSED_STAKE -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }
                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Pieu béni est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                // déjà un épieu actif ? on refuse
                if (p.isBlessedStake()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu as déjà un pieu béni prêt à être déclenché.");
                }

                String loc = locationOf(g, playerId);
                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu dois être sur un lieu pour utiliser pieu béni.");
                }

                // Il doit y avoir au moins un ennemi sur ce lieu (comme Filet)
                boolean hasEnemy = g.getPlayers().stream()
                        .anyMatch(pl -> pl.getHp() > 0
                                && loc.equals(locationOf(g, pl.getId()))
                                && ("VAMPIRE".equals(pl.getRole()) || "SERVANT".equals(pl.getRole())));

                // Consommer la carte dans l'inventaire
                if (inv == null || !inv.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                discardHunterAction(g, type.name());

                // Marquer l'effet PERSISTANT
                p.setBlessedStake(true);

                // Ajouter une puce DSP pour le raid courant
                addRaidMod(g, playerId, "ATTACK", 0, "ACTION:BLESSED_STAKE:DSP");

                String msg = "Pieu béni — "
                        + nameOf(g, playerId)
                        + " s'équipe d'un pieu béni en arme secondaire.";
                addHistory(g, msg);
                feedText = msg;

                save(g);

                final String fFeed = feedText;
                final String fUser = playerId;
                final String fType = type.name();
                afterCommit(() -> {
                    pushLive(g, fFeed);
                    // pour forcer un refresh de la main / mods côté front
                    live.actionUsed(g, fUser, fType);
                });

                return g;
            }

            case SACRED_ROSARY -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Chapelet sacré est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                // Si Présence écrasante bloque les actions sur ce lieu, le check global
                // au début de useAction fera déjà le boulot, comme pour les autres cartes.

                // Déjà protégé par un chapelet ?
                if (p.isSacredRosary()) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Tu bénéficies déjà de la protection d’un chapelet sacré.");
                }

                // Activer l’effet persistant sur ce chasseur
                p.setSacredRosary(true);

                // Puce DISPLAY pour ce raid (et suivants tant qu’on ne la retire pas)
                if (g.getRaidMods() == null) {
                    g.setRaidMods(new java.util.HashMap<>());
                }
                g.getRaidMods()
                        .computeIfAbsent(playerId, __ -> new java.util.ArrayList<>())
                        .add(new StatMod("DEFENSE", 0, "ACTION:SACRED_ROSARY:DSP"));

                // Consommer la carte d’action dans l’inventaire
                inv.remove(type.name());
                if (isHunter)
                    discardHunterAction(g, type.name());

                String msg = nameOf(g, playerId)
                        + " porte un chapelet sacré: il pourra annuler une morsure réussie du vampire.";
                addHistory(g, msg);
                feedText = msg;

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                    // Pour que la puce apparaisse tout de suite
                    live.raidModsUpdated(g);
                });

                return g;
            }

            case CHARISMATIQUE -> {
                if (!isHunter) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }
                if (g.getPhase() != Phase.PHASE4) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Charismatique est utilisable uniquement lors de la boutique (PHASE4).");
                }

                // Si Présence écrasante bloque les cartes d'action ce raid, on interdit
                if (g.isHunterActionsBlockedThisRaid()) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "La présence écrasante du vampire t'empêche d'utiliser des cartes d'action ce raid.");
                }

                if (p.isCharismaticThisRaid()) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "L'effet de Charismatique est déjà actif pour ce raid.");
                }

                // Activer le bonus pour ce raid
                p.setCharismaticThisRaid(true);

                // Consommer la carte
                inv.remove(type.name());
                discardHunterAction(g, type.name());

                String msg = nameOf(g, playerId)
                        + " charme les marchands: ses achats à la boutique coûtent 20 or de moins "
                        + "et ses ventes rapportent 20 or de plus par ressource pour ce raid.";
                addHistory(g, msg);
                feedText = msg;

                // currentAction purement informative pour la modale front
                Game.Action a = new Game.Action();
                a.setMode("CHARISMATIQUE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setResolvedAtMillis(System.currentTimeMillis());
                g.setCurrentAction(a);

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case CRATE_LAKE, CRATE_MANOR -> {
                if (!isHunter) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseur uniquement");
                }

                if (p.isCrateUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Vous avez déjà utilisé une caisse ce raid.");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            type.name() + " est utilisable uniquement pendant la PREPHASE3.");
                }

                String loc = locationOf(g, playerId);
                if (type == Action.CRATE_LAKE && !"lake".equals(loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Cette action nécessite d'être au Lac.");
                }
                if (type == Action.CRATE_MANOR && !"manor".equals(loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Cette action nécessite d'être au Manoir.");
                }

                // Consommer la carte dans la main du chasseur
                inv.remove(type.name());
                discardHunterAction(g, type.name());

                // Créer l'action interactive pour le front
                Game.Action action = new Game.Action();
                action.setMode(type.name());
                action.setOwnerId(playerId);
                action.setLocation(loc);
                g.setCurrentAction(action);
                p.setCrateUsedThisRaid(true);

                String msg = nameOf(g, playerId) + " découvre une caisse abandonnée au " + labelLieuFr(loc) + ".";
                addHistory(g, msg);
                feedText = msg;

                setupUnstableAndPrephaseTimeout(g);
                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case MARCHAND_ITINERANT -> {
                if (!isHunter) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseur uniquement");
                }

                if (g.getPhase() != Phase.PHASE4) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Marchand itinérant est utilisable uniquement pendant la maintenance (PHASE4).");
                }

                // perso : si CE joueur a déjà une offre / un jet / une modale achat ouverte ->
                // refuse
                if (p.isMerchantPending()
                        || p.getShopBonusKind() != null
                        || p.isShopBonusBuyPending()
                        || p.getMerchantRoll() != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu as déjà une offre du marchand en cours.");
                }

                if (p.isMerchantUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Tu as déjà appelé un marchand itinérant ce raid.");
                }

                // Consommer la carte dans la main du chasseur
                inv.remove(type.name());
                discardHunterAction(g, type.name());

                // init état marchand perso
                p.setMerchantPending(true);
                p.setMerchantRoll(null);
                p.setShopBonusKind(null);
                p.setShopBonusEquipId(null);
                p.setShopBonusEquipTier(null);
                p.setShopBonusBuyPending(false);
                p.setMerchantUsedThisRaid(true);

                String msg = nameOf(g, playerId) + " appelle un marchand itinérant à la boutique.";
                addHistory(g, msg);
                feedText = msg;

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case ADVANCED_TRANSMUTATION -> {
                if (!"VAMPIRE".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PHASE4) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Transmutation avancée est utilisable uniquement pendant la maintenance (PHASE4).");
                }

                // Si le joueur a déjà une offre / un jet / une modale achat ouverte -> refuse
                if (p.isMerchantPending()
                        || p.getShopBonusKind() != null
                        || p.isShopBonusBuyPending()
                        || p.getMerchantRoll() != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu as déjà une offre de transmutation en cours.");
                }

                if (p.isAdvancedTransmutationUsedThisRaid()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Tu as déjà utilisé la transmutation avancée ce raid.");
                }

                // Consommer la carte dans la main du vampire
                inv.remove(type.name());
                discardVampAction(g, type.name());

                // init état transmutation perso
                p.setMerchantPending(true);
                p.setMerchantRoll(null);
                p.setShopBonusKind(null);
                p.setShopBonusEquipId(null);
                p.setShopBonusEquipTier(null);
                p.setShopBonusBuyPending(false);
                p.setAdvancedTransmutationUsedThisRaid(true);

                String msg = nameOf(g, playerId) + " active la Transmutation avancée dans l'antre.";
                addHistory(g, msg);
                feedText = msg;

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case CATACLYSME -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PHASE2) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Cataclysme est utilisable uniquement pendant la PHASE2.");
                }

                // On ne veut qu'un Cataclysme en cours à la fois
                Game.Action existing = g.getCurrentAction();
                if (existing != null && "CATACLYSME".equals(existing.getMode())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Un Cataclysme est déjà en cours de résolution.");
                }

                // On consomme la carte dans la main du vampire
                inv.remove(type.name());
                discardVampAction(g, type.name());

                // currentAction pour piloter la modale
                Game.Action a = new Game.Action();
                a.setMode("CATACLYSME");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);

                java.util.List<String> breakdown = new java.util.ArrayList<>();
                a.setBreakdownLines(breakdown);

                a.setRoll(null);
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                // Historique / feed
                String msg = nameOf(g, playerId) + " joue Cataclysme et déchire le ciel.";
                addHistory(g, msg);
                feedText = msg;

                save(g);

                final String fType = type.name();
                final String fUserId = playerId;
                final String fFeed = feedText;

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    // Comme pour les autres actions, on notifie:
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case CLONES_OMBRE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }
                if (g.getPhase() != Phase.PHASE2) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Clones des ombres est utilisable uniquement pendant la PHASE2.");
                }

                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // Consommer la carte
                inv.remove(type.name());
                discardVampAction(g, type.name());

                Game.Action a = new Game.Action();
                a.setMode("CLONES_OMBRE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new ArrayList<>());
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                String msg = nameOf(g, playerId) + " invoque des clones des ombres.";
                addHistory(g, msg);
                feedText = msg;

                save(g);

                final String fType = type.name();
                final String fUserId = playerId;
                final String fFeed = feedText;

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case IMAGE_MIROIR -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }
                if (g.getPhase() != Phase.PHASE2) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Image miroir est utilisable uniquement pendant la PHASE2.");
                }

                if (g.getProvokedTargetByEnemy() != null && g.getProvokedTargetByEnemy().containsKey(playerId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Vous êtes provoqué et ne pouvez pas utiliser Image Miroir !");
                }

                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // Consommer la carte action
                inv.remove(type.name());
                discardVampAction(g, type.name());

                // DELAYED RESOLUTION: On stocke juste l'intention
                g.setPendingVampireEscape("IMAGE_MIROIR_SETUP");

                // On ne met PAS d'action courante tout de suite (pas de modale)
                // g.setCurrentAction(a);

                String msg = nameOf(g, playerId) + " prépare une image miroir...";
                addHistory(g, msg);
                feedText = msg;

                save(g);

                final String fType = type.name();
                final String fUserId = playerId;
                final String fFeed = feedText;

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case PRESENCE_ECRASANTE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Présence écrasante est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                // Lieu actuel du vampire
                String vampLoc = locationOf(g, playerId);

                // Annule toutes les prépas des chasseurs SUR CE LIEU + récupère la liste déjà
                // formatée pour le message
                java.util.List<String> parts = cancelHunterPrephase3ActionsOnVampLoc(g, vampLoc);

                // Bloque les cartes d'action chasseurs pour le reste du raid
                // (le blocage sera restreint au lieu du vampire dans la garde plus haut)
                g.setHunterActionsBlockedThisRaid(true);

                // Consommer la carte sur le vampire
                inv.remove(type.name());
                discardVampAction(g, type.name());

                String msg = nameOf(g, playerId) + " déchaîne une présence écrasante : ";

                if (parts.isEmpty()) {
                    msg += "aucune préparation de cartes des chasseurs n'était en cours sur ce lieu, "
                            + "mais les chasseurs prsénts sur " + labelLieuFr(vampLoc)
                            + " ne pourront plus utiliser de cartes d'action ce raid.";
                } else {
                    msg += "les préparations de cartes des chasseurs sur ce lieu sont annulées ("
                            + String.join(", ", parts)
                            + "). Les chasseurs présents sur " + labelLieuFr(vampLoc)
                            + " ne pourront plus utiliser de cartes d'action ce raid.";
                }

                addHistory(g, msg);
                feedText = msg;

                // currentAction purement informative pour la modale front
                Game.Action a = new Game.Action();
                a.setMode("PRESENCE_ECRASANTE");
                a.setOwnerId(playerId);
                a.setLocation(vampLoc);
                a.setTargetId(null);

                java.util.List<String> breakdown = new java.util.ArrayList<>();
                breakdown.add("Les cartes actions chasseurs sur ce lieu sont annulées.");

                a.setBreakdownLines(breakdown);
                a.setRoll(null);
                a.setResolvedAtMillis(System.currentTimeMillis());
                g.setCurrentAction(a);

                int newVersion = g.getPrephaseTimerVersion() + 1;
                g.setPrephaseTimerVersion(newVersion);

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();
                final int version = newVersion;

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.raidModsUpdated(g);
                    live.actionUsed(g, fUserId, fType);
                    schedulePrephaseTimeout(gameId, 30_000L, version);
                });

                return g;
            }

            case ECLIPSE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Éclipse est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                inv.remove(type.name());
                discardVampAction(g, type.name());

                g.setWeatherStatus(WeatherStatus.FULL_MOON);

                g.setWeatherStatusNameFr(weatherNameFr(WeatherStatus.FULL_MOON));
                g.setWeatherDescriptionFr(weatherDescFr(WeatherStatus.FULL_MOON));

                // Rebuild des mods météo (WEATHER:...)
                rebuildWeatherMods(g);

                // Historique + feed
                String msg = nameOf(g, playerId)
                        + " invoque une éclipse: la pleine lune obscurcit les lieux.";
                addHistory(g, msg);
                feedText = msg;

                // currentAction purement informative pour la modale front
                Game.Action a = new Game.Action();
                a.setMode("ECLIPSE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);

                java.util.List<String> breakdown = new java.util.ArrayList<>();
                breakdown.add("Une eclipse plonge les lieux dans l'obscurité.");
                a.setBreakdownLines(breakdown);
                a.setRoll(null);
                a.setResolvedAtMillis(System.currentTimeMillis());
                g.setCurrentAction(a);

                // --- RESET TIMER PREPHASE ---
                // Reset du timer PREPHASE3 via version
                int newVersion = g.getPrephaseTimerVersion() + 1;
                g.setPrephaseTimerVersion(newVersion);

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();
                final int version = newVersion;

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.raidModsUpdated(g);
                    live.actionUsed(g, fUserId, fType);

                    // On relance un timer PREPHASE3 complet de 30s
                    schedulePrephaseTimeout(gameId, 30_000L, version);
                });

                return g;
            }

            case BLOOD_MOON -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Lune sanglante est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                // Vérifier que la météo contient FULL_MOON (principal ou secondaire)
                WeatherStatus ws1 = g.getWeatherStatus();

                boolean hasFullMoon = (ws1 == WeatherStatus.FULL_MOON);

                if (!hasFullMoon) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Lune sanglante ne peut être utilisée que si la météo est Pleine lune.");
                }

                // Consommer la carte
                inv.remove(type.name());
                discardVampAction(g, type.name());

                // Transformer la Pleine lune concernée en Lune sanglante
                g.setWeatherStatus(WeatherStatus.BLOOD_MOON);
                g.setWeatherStatusNameFr(weatherNameFr(WeatherStatus.BLOOD_MOON));
                g.setWeatherDescriptionFr(weatherDescFr(WeatherStatus.BLOOD_MOON));

                // Rebuilder les mods météo
                rebuildWeatherMods(g);

                // Historique / feed
                String msg = nameOf(g, playerId)
                        + " invoque la Lune sanglante: la pleine lune devient rouge.";
                addHistory(g, msg);
                feedText = msg;

                // currentAction juste pour afficher la modale d'info
                Game.Action a = new Game.Action();
                a.setMode("BLOOD_MOON");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setResolvedAtMillis(System.currentTimeMillis());

                java.util.List<String> breakdown = new java.util.ArrayList<>();
                breakdown.add("+4 Attaque pour le vampire et serviteurs.");
                a.setBreakdownLines(breakdown);

                g.setCurrentAction(a);

                // --- RESET TIMER PREPHASE ---
                int newVersion = g.getPrephaseTimerVersion() + 1;
                g.setPrephaseTimerVersion(newVersion);

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();
                final int version = newVersion;

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.raidModsUpdated(g); // pour rafraîchir les mods WEATHER:*
                    live.actionUsed(g, fUserId, fType);

                    // On relance un timer PREPHASE3 complet de 30s
                    schedulePrephaseTimeout(gameId, 30_000L, version);
                });

                return g;
            }

            case VOILE_DE_BRUME -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Voile de brume est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // Consommer la carte
                inv.remove(type.name());
                discardVampAction(g, type.name());

                // Créer action en attente de choix de lieu
                Game.Action a = new Game.Action();
                a.setMode("VOILE_DE_BRUME");
                a.setOwnerId(playerId);
                a.setLocation(null); // Sera défini par le choix
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new ArrayList<>());
                a.setResolvedAtMillis(null); // Pas encore résolu
                g.setCurrentAction(a);

                String msg = nameOf(g, playerId) + " invoque un voile de brume.";
                addHistory(g, msg);
                feedText = msg;

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case FAIM_IRREPRESSIBLE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Faim irrépressible est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                // Consommer la carte sur le vampire
                List<String> actions = p.getActions();
                if (actions == null || !actions.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                discardVampAction(g, type.name());

                // Flag de raid : à partir de maintenant, le vamp peut mordre même sans dégâts
                g.setHungerAllowsBiteThisRaid(true);

                // Message d’historique
                String msg = nameOf(g, playerId)
                        + " succombe à une faim irrépressible: il pourra tenter une morsure"
                        + " même sans infliger de dégâts ce raid.";
                addHistory(g, msg);
                feedText = msg;

                // currentAction purement informative pour la modale front
                Game.Action a = new Game.Action();
                a.setMode("FAIM_IRREPRESSIBLE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);

                java.util.List<String> breakdown = new java.util.ArrayList<>();
                breakdown.add("Ce raid, le vampire pourra tenter une morsure même si l’attaque échoue.");
                a.setBreakdownLines(breakdown);

                a.setRoll(null);
                a.setResolvedAtMillis(System.currentTimeMillis());
                g.setCurrentAction(a);

                // Reset du timer PREPHASE3 (comme Présence écrasante)
                int newVersion = g.getPrephaseTimerVersion() + 1;
                g.setPrephaseTimerVersion(newVersion);

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();
                final int version = newVersion;

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                    schedulePrephaseTimeout(gameId, 30_000L, version);
                });

                return g;
            }

            case MARQUE_TENEBREUSE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Marque ténébreuse est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // Consommer la carte dans l'inventaire du vampire
                List<String> actions = p.getActions();
                if (actions == null || !actions.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                discardVampAction(g, type.name());

                // currentAction : choix de la cible dans la modale
                Game.Action a = new Game.Action();
                a.setMode("MARQUE_TENEBREUSE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new java.util.ArrayList<>());
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                String msg = nameOf(g, playerId)
                        + " invoque une Marque ténébreuse et s'apprête à maudire un chasseur.";
                addHistory(g, msg);
                feedText = msg;

                // On ANNULE le timer de préphase en cours (comme pour ton flag reset)
                if (g.getPhase() == Phase.PREPHASE3) {
                    g.setPrephaseTimerVersion(g.getPrephaseTimerVersion() + 1);
                }

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case AFFAIBLISSEMENT_OCCULTE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Affaiblissement occulte est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // Consommer la carte dans l'inventaire du vampire
                if (inv == null || !inv.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                discardVampAction(g, type.name());

                // currentAction : choix d'un chasseur dans la modale
                Game.Action a = new Game.Action();
                a.setMode("AFFAIBLISSEMENT_OCCULTE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setBreakdownLines(new java.util.ArrayList<>());
                a.setResolvedAtMillis(null);
                g.setCurrentAction(a);

                String msg = "Affaiblissement occulte — "
                        + nameOf(g, playerId)
                        + " prépare un rituel pour affaiblir un chasseur.";
                addHistory(g, msg);
                feedText = msg;

                if (g.getPhase() == Phase.PREPHASE3) {
                    g.setPrephaseTimerVersion(g.getPrephaseTimerVersion() + 1);
                }

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case PASSAGE_SECRET -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }

                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Passage secret est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                if (g.getProvokedTargetByEnemy() != null && g.getProvokedTargetByEnemy().containsKey(playerId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Vous êtes provoqué et ne pouvez pas utiliser Passage Secret !");
                }

                // Doit être au Manoir
                String loc = locationOf(g, playerId);
                if (!"manor".equals(loc)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Passage secret n'est utilisable que si le vampire est au Manoir.");
                }

                // Pas deux actions bloquantes en même temps
                if (hasBlockingActionInProgress(g)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Une autre action est déjà en cours de résolution.");
                }

                // Consommer la carte
                if (inv == null || !inv.remove(type.name())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
                }
                discardVampAction(g, type.name());

                // DELAYED RESOLUTION
                g.setPendingVampireEscape("PASSAGE_SECRET");
                // g.setCurrentAction(null); // Pas d'action immédiate

                String msg = nameOf(g, playerId) + " cherche un passage secret...";
                addHistory(g, msg);
                feedText = msg;

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            case AVIDITE_NOCTURNE -> {
                if (!isVamp) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
                }
                if (g.getPhase() != Phase.PHASE4) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Avidité nocturne est utilisable uniquement lors de la boutique (PHASE4).");
                }

                // déjà utilisée ce raid ?
                if (g.isShopPricesIncreasedThisRaid()) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Avidité nocturne a déjà été utilisée ce raid.");
                }

                // le vampire doit réellement avoir la carte
                if (inv == null || !inv.contains(type.name())) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Le vampire ne possède pas la carte Avidité nocturne.");
                }

                // activer l'effet global pour ce raid
                g.setShopPricesIncreasedThisRaid(true);

                // consommer la carte d'action vampire
                inv.remove(type.name());
                discardVampAction(g, type.name());

                String msg = nameOf(g, playerId)
                        + " joue Avidité nocturne: tous les prix en or à la boutique des chasseurs augmentent de 50 ce raid.";
                addHistory(g, msg);
                feedText = msg;

                // currentAction purement informative pour la modale front
                Game.Action a = new Game.Action();
                a.setMode("AVIDITE_NOCTURNE");
                a.setOwnerId(playerId);
                a.setLocation(null);
                a.setTargetId(null);
                a.setRoll(null);
                a.setResolvedAtMillis(System.currentTimeMillis());
                g.setCurrentAction(a);

                save(g);

                final String fFeed = feedText;
                final String fUserId = playerId;
                final String fType = type.name();

                afterCommit(() -> {
                    pushLive(g, fFeed);
                    live.actionUsed(g, fUserId, fType);
                });

                return g;
            }

            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown action");
        }

        return g;

    }

    private boolean isBlockingAction(Game.Action a) {
        if (a == null)
            return false;
        String m = a.getMode();
        if (m == null)
            return false;

        return switch (m) {
            case "NET",
                    "PIT",
                    "INCENDIAIRE",
                    "EAU_BENITE",
                    "PROVOCATION",
                    "AMBUSH",
                    "MARQUE_TENEBREUSE",
                    "AFFAIBLISSEMENT_OCCULTE",
                    "CLONES_OMBRE",
                    "IMAGE_MIROIR_SETUP",
                    "IMAGE_MIROIR_RESOLVE",
                    "PASSAGE_SECRET",
                    "VOILE_DE_BRUME",
                    "CRATE_LAKE",
                    "CRATE_MANOR",
                    "CATACLYSME" ->
                true;

            default -> false; // FAIM, PRESENCE, ECLIPSE, LONELY, BLOOD_MOON => non bloquantes
        };
    }

    private boolean hasBlockingActionInProgress(Game g) {
        return isBlockingAction(g.getCurrentAction());
    }

    // ACTIONS HUNTER
    /**
     * Applique l'effet des chasseurs Pisteur quand le vampire pose son lieu.
     * - si le chasseur avait déjà joué un lieu (ex : fumigé) :
     * - on lui rend ce lieu dans la main
     * - on consomme le lieu du vampire dans sa main
     * - on met sa carte au centre sur le lieu du vampire
     * - la fumigation reste active (garlicBlockedLocations inchangé)
     */
    private void applyTrackerHuntersWhenVampirePlays(Game g, String vampireLocation) {
        var trackers = g.getTrackerHunters();
        if (trackers == null || trackers.isEmpty())
            return;

        // Copie pour éviter ConcurrentModificationException si on modifie le Set
        var ids = new java.util.ArrayList<>(trackers);

        for (String hunterId : ids) {
            var opt = g.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(hunterId))
                    .findFirst();

            if (opt.isEmpty())
                continue;
            var hp = opt.get();

            // uniquement des chasseurs vivants
            if (!"HUNTER".equals(hp.getRole()) || hp.getHp() <= 0)
                continue;

            // === 1) récupérer l'ancien lieu joué par ce chasseur, s'il existe ===
            String oldLoc = null;
            for (CenterBoard cb : g.getCenter()) {
                if (hunterId.equals(cb.getPlayerId())) {
                    oldLoc = cb.getCard();
                    break;
                }
            }

            // === 2) retirer toutes ses cartes du centre (ancien lieu, etc.) ===
            g.getCenter().removeIf(cb -> hunterId.equals(cb.getPlayerId()));

            // === 3) ajuster SA main ===
            var hand = hp.getHand();
            if (hand == null) {
                hand = new java.util.ArrayList<>();
                hp.setHand(hand);
            }

            // 3.a) si il avait joué un lieu avant (ex : fumigé), on lui rend cette carte
            if (oldLoc != null && !hand.contains(oldLoc)) {
                hand.add(oldLoc);
            }

            // 3.b) on consomme le lieu du vampire dans sa main (si présent)
            // -> s'il ne l'a pas dans sa main (cas bizarre), remove ne fait rien
            hand.remove(vampireLocation);

            // === 4) poser au centre une nouvelle carte sur le lieu du vampire ===
            CenterBoard follow = new CenterBoard(hunterId, vampireLocation, /* faceUp= */false);
            g.getCenter().add(follow);

            // === 5) historique lisible ===
            addHistory(g, nameOf(g, hunterId) +
                    " suit la piste du vampire jusqu’à " + labelLieuFr(vampireLocation) + ".");
        }

        // effet consommé pour ce raid
        trackers.clear();
    }

    private void applyFumigationAilPrepare(Game g, Player hunter) {
        if (g.getPendingGarlicPlayers() == null) {
            g.setPendingGarlicPlayers(new java.util.HashSet<>());
        }

        // Empêche de spammer / rejouer : une seule fumigation en attente par raid
        if (g.getPendingGarlicPlayers().contains(hunter.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "tu as déjà préparé une Fumigation d’ail pour ce raid");
        }

        g.getPendingGarlicPlayers().add(hunter.getId());

        // Historique “préparation”
        addHistory(g, nameOf(g, hunter.getId()) +
                " prépare une fumigation d’ail pour son futur lieu.");
    }

    private boolean isCampfireCancellingWeather(@NonNull Game g, @NonNull String location) {
        if (g.getCampfireLocations() == null)
            return false;
        if (!g.getCampfireLocations().contains(location))
            return false;

        WeatherStatus ws = g.getWeatherStatus();
        if (ws == null)
            return false;

        // Carte Feu de camp : ne sert que pour ces 3 états
        return ws == WeatherStatus.DUSK
                || ws == WeatherStatus.NIGHT_DARK
                || ws == WeatherStatus.NIGHT_CLEAR;
    }

    /**
     * Prépare la prochaine action de raid à résoudre en PHASE3 :
     * - Filet
     * - Fosse
     * - Incendiaire
     * Un seul à la fois via currentAction.
     */
    private void prepareNextRaidAction(Game g) {
        // reset par sécurité
        g.setCurrentAction(null);

        // 1) NET en priorité
        if (g.getNetHunters() != null) {
            for (String hunterId : g.getNetHunters()) {
                String loc = locationOf(g, hunterId);
                if (loc == null)
                    continue;

                // Vérifier si ce chasseur a encore des cartes NET à résoudre
                int cardsRemaining = g.getNetCardsRemaining() != null
                        ? g.getNetCardsRemaining().getOrDefault(hunterId, 0)
                        : 0;
                if (cardsRemaining <= 0)
                    continue;

                // cible possible = vampire/serviteur ou monstre sur ce lieu
                if (!hasTrapTargetsOnLocation(g, loc))
                    continue;

                Game.Action a = new Game.Action();
                a.setMode("NET");
                a.setOwnerId(hunterId);
                a.setLocation(loc);
                a.setTargetId(null); // cible pas encore choisie (joueur choisit librement)
                a.setRoll(null); // dé pas encore lancé
                a.setBreakdownLines(java.util.List.of());
                a.setResolvedAtMillis(null);

                g.setCurrentAction(a);
                return; // un seul piège à la fois
            }
        }

        // 2) PIT ensuite
        if (g.getPitHunters() != null && g.getPitTargetsByHunter() != null) {
            // A) D'ABORD : Traiter les cibles JOUEURS (Manuel)
            // On veut que toutes les actions manuelles s'enchaînent avant les actions de
            // groupe (monstres)
            for (String hunterId : g.getPitHunters()) {
                String loc = locationOf(g, hunterId);
                if (loc == null)
                    continue;

                var victims = g.getPitTargetsByHunter().get(hunterId);
                if (victims == null || victims.isEmpty())
                    continue;

                int idx = g.getPitIndexByHunter() != null ? g.getPitIndexByHunter().getOrDefault(hunterId, 0) : 0;

                // Nettoyer les cibles mortes/parties
                boolean indexUpdated = false;
                while (idx < victims.size() && !isEntityOnLocation(g, victims.get(idx), loc)) {
                    idx++;
                    indexUpdated = true;
                }
                if (indexUpdated) {
                    if (g.getPitIndexByHunter() == null)
                        g.setPitIndexByHunter(new java.util.HashMap<>());
                    g.getPitIndexByHunter().put(hunterId, idx);
                }

                if (idx < victims.size()) {
                    String currentVictimId = victims.get(idx);
                    // DEBUG TRACE
                    // System.out.println("PIT_DEBUG: Hunter " + hunterId + " target " +
                    // currentVictimId + " idx " + idx);

                    // Si c'est un monstre, on l'ignore ici (sera traité par le batch ensuite)
                    if (findMonster(g, currentVictimId) != null) {
                        continue;
                    }

                    // C'est un joueur -> Action Manuelle Immédiate
                    Game.Action a = new Game.Action();
                    a.setMode("PIT");
                    a.setOwnerId(hunterId);
                    a.setLocation(loc);
                    a.setTargetId(currentVictimId);
                    a.setRoll(null);
                    a.setBreakdownLines(java.util.List.of());
                    a.setResolvedAtMillis(null);
                    g.setCurrentAction(a);
                    return;
                }
            }

            // B) ENSUITE : Identifier le premier monstre cible pour Batch
            String firstMonsterTarget = null;
            String monsterLocation = null;

            for (String hunterId : g.getPitHunters()) {
                String loc = locationOf(g, hunterId);
                if (loc == null)
                    continue;

                var victims = g.getPitTargetsByHunter().get(hunterId);
                if (victims == null || victims.isEmpty())
                    continue;

                int idx = g.getPitIndexByHunter() != null ? g.getPitIndexByHunter().getOrDefault(hunterId, 0) : 0;

                // Nettoyer les cibles qui ne sont plus là
                while (idx < victims.size()) {
                    String victimId = victims.get(idx);
                    if (!isEntityOnLocation(g, victimId, loc)) {
                        idx++;
                        continue;
                    }

                    // Trouver le premier monstre
                    if (findMonster(g, victimId) != null) {
                        firstMonsterTarget = victimId;
                        monsterLocation = loc;
                        break;
                    } else {
                        // C'est un joueur : on sort et on le traite en manuel
                        break;
                    }
                }

                if (firstMonsterTarget != null)
                    break;
            }

            // Si on a trouvé un monstre, grouper TOUS les monstres de ce lieu et TOUS les
            // chasseurs qui les ciblent
            if (firstMonsterTarget != null) {
                java.util.List<String> breakdownLines = new java.util.ArrayList<>();

                final String finalLoc = monsterLocation;
                // On récupère tous les monstres à cet endroit
                java.util.List<String> monstersAtLoc = g.getMonsters().stream()
                        .filter(m -> finalLoc.equals(m.location))
                        .map(m -> m.id)
                        .collect(java.util.stream.Collectors.toList());

                java.util.Set<String> resolvedMonsterIds = new java.util.HashSet<>();
                boolean anyResolved = false;

                for (String monsterId : monstersAtLoc) {
                    boolean monsterResolvedThisTurn = false;
                    for (String hunterId : new java.util.ArrayList<>(g.getPitHunters())) {
                        String loc = locationOf(g, hunterId);
                        if (!monsterLocation.equals(loc))
                            continue;

                        var victims = g.getPitTargetsByHunter().get(hunterId);
                        if (victims == null)
                            continue;

                        // Vérifier si ce monstre est dans la liste des victimes du chasseur
                        if (victims.contains(monsterId)) {
                            // Résoudre le PIT autant de fois que de cartes jouées par ce chasseur
                            int rollsCount = g.getPitCardsCount() != null
                                    ? g.getPitCardsCount().getOrDefault(hunterId, 0)
                                    : 0;

                            // Si le compteur est 0 mais qu'il y a des hunter/targets, c'est peut-être un
                            // vieux state
                            // on force au moins 1 si la logique précédente n'avait pas le compteur
                            if (rollsCount == 0 && g.getPitHunters().contains(hunterId))
                                rollsCount = 1;

                            for (int k = 0; k < rollsCount; k++) {
                                int roll = dice.roll(20);

                                String msg = entityName(g, monsterId) + " jette un dé pour éviter la Fosse de "
                                        + nameOf(g, hunterId) + " (1d20 = " + roll + "). ";

                                if (roll < 8) {
                                    if (g.getRaidMods() == null)
                                        g.setRaidMods(new java.util.HashMap<>());
                                    g.getRaidMods().computeIfAbsent(monsterId, __ -> new java.util.ArrayList<>())
                                            .add(new StatMod("DEFENSE", -2, "ACTION:PIT"));
                                    msg += "Il tombe dans la fosse : sa défense est réduite de 2.";
                                } else {
                                    msg += "Il évite la fosse.";
                                }
                                addHistory(g, msg);
                                // Format: hunterId:targetId:location:roll
                                breakdownLines.add(hunterId + ":" + monsterId + ":" + loc + ":" + roll);
                            }

                            anyResolved = true;
                            monsterResolvedThisTurn = true;

                            // Note: On ne retire pas le chasseur ici, on le laisse pour les autres monstres
                            // On ne gère pas l'index pour les monstres (car on résout tout d'un coup en
                            // batch)
                        }

                    }
                    if (monsterResolvedThisTurn) {
                        resolvedMonsterIds.add(monsterId);
                    }
                }

                // Ajouter le compte des monstres réellement résolus dans cet écran
                breakdownLines.add(0, "TOTAL_MONSTER_TARGETS:" + resolvedMonsterIds.size());

                // Créer l'action avec tous les résultats groupés
                if (anyResolved) {
                    Game.Action a = new Game.Action();
                    a.setMode("PIT");
                    a.setOwnerId(null); // Pas de owner unique, c'est un groupe
                    a.setLocation(monsterLocation);
                    a.setTargetId(firstMonsterTarget);
                    a.setRoll(1); // Dummy roll pour indiquer que c'est résolu
                    a.setBreakdownLines(breakdownLines);
                    a.setResolvedAtMillis(System.currentTimeMillis());

                    g.setCurrentAction(a);

                    final String finalMonsterId = firstMonsterTarget;
                    final String targetName = entityName(g, firstMonsterTarget);
                    afterCommit(() -> {
                        pushLive(g, (breakdownLines.size() - 1) + " fosse(s) résolue(s) contre " + targetName + ".");
                        live.raidModsUpdated(g);
                        live.actionRolled(g, "PIT", null, finalMonsterId, 0);
                    });

                    // Une fois résolu pour ce groupe de monstres, on nettoie les hunters QUI ONT
                    // ETE TRAITES
                    // Mais attention, un hunter peut avoir des cibles sur d'autres monstres ?
                    // En fait la logique ici est "Prepare Next Raid Action".
                    // Si on retourne une action, le frontend va l'afficher et attendre.
                    // Une fois l'action finie (auto close), on reviendra ici.
                    // Il faut donc marquer ces monstres comme "traités" pour ce hunter ?
                    // Ou simplement retirer le hunter de la liste ?
                    //
                    // Problème : on a résolu pour TOUS les monstres du lieu.
                    // Donc pour les hunters présents sur ce lieu, on a traité tous les monstres.
                    // Il reste éventuellement les cibles "Joueurs".
                    //
                    // Simplification : on retire les hunters si on a traité tous leurs monstres ?
                    // Non, on a juste fait un affichage. L'action ne modifie pas l'état
                    // "pitHunters"
                    // pour empêcher de refaire l'action ?
                    // SI ! Il faut que l'action soit consommée.

                    // Pour éviter de re-proposer la même action indéfiniment :
                    // On doit retirer les monstres de la liste des victimes des hunters concernés ?
                    // Ou marquer le hunter comme "a fini ses monstres sur ce lieu" ?

                    // Approche : on retire les monstres résolus des listes de victimes des hunters
                    for (String mId : resolvedMonsterIds) {
                        for (String hId : g.getPitHunters()) {
                            List<String> v = g.getPitTargetsByHunter().get(hId);
                            if (v != null) {
                                v.remove(mId); // On retire le monstre traité
                            }
                        }
                    }
                    // Si un hunter n'a plus de victimes, on le retire
                    g.getPitHunters().removeIf(hId -> {
                        List<String> v = g.getPitTargetsByHunter().get(hId);
                        return v == null || v.isEmpty();
                    });

                    return;
                }
            }

        }

        // 3) Enfin : INCENDIAIRE
        Map<String, String> incendiaires = g.getIncendiaireLocationByHunter();
        if (incendiaires != null && !incendiaires.isEmpty()) {
            // On itère sur une copie pour pouvoir remove dans la map
            for (var entry : new java.util.ArrayList<>(incendiaires.entrySet())) {
                String hunterId = entry.getKey();
                String loc = entry.getValue();
                if (loc == null) {
                    incendiaires.remove(hunterId);
                    continue;
                }

                // Est-ce qu'il reste au moins une infra brûlable sur ce lieu ?
                if (!hasIncendiaireTargetsOnLocation(g, loc)) {
                    String msg = nameOf(g, hunterId)
                            + " avait préparé Incendiaire à " + labelLieuFr(loc)
                            + ", mais aucune construction n'est présente: l'effet est perdu.";
                    addHistory(g, msg);
                    incendiaires.remove(hunterId);
                    continue;
                }

                Game.Action a = new Game.Action();
                a.setMode("INCENDIAIRE");
                a.setOwnerId(hunterId);
                a.setLocation(loc);
                a.setTargetId(null); // infra choisie côté front
                a.setRoll(null);
                a.setBreakdownLines(java.util.List.of());
                a.setResolvedAtMillis(null);

                g.setCurrentAction(a);
                return;
            }
        }

        // 4) Si on arrive ici : aucun Filet/Fosse/Incendiaire à résoudre ->
        // currentAction reste null
    }

    @Transactional
    public Game chooseNetTarget(String gameId, String hunterId, String targetId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Filet se cible en PHASE3, juste avant les duels.");
        }

        if (g.getNetHunters() == null || !g.getNetHunters().contains(hunterId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucun Filet préparé pour ce joueur.");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "aucune action Filet en cours (currentAction null)");
        }
        if (!"NET".equals(a.getMode())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "action courante n’est pas un Filet (mode=" + a.getMode() + ")");
        }
        if (!hunterId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Filet en cours appartient à " + a.getOwnerId() + ", pas à " + hunterId);
        }

        String loc = a.getLocation();
        if (loc == null) {
            loc = locationOf(g, hunterId);
            if (loc == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "le chasseur n’est sur aucun lieu.");
            }
        }

        // Validation cible : vampire/serviteur OU monstre sur ce lieu
        boolean ok = false;

        Player targetPlayer = findPlayer(g, targetId);
        if (targetPlayer != null) {
            String tLoc = locationOf(g, targetId);
            if (loc.equals(tLoc)
                    && ("VAMPIRE".equals(targetPlayer.getRole()) || "SERVANT".equals(targetPlayer.getRole()))) {
                ok = true;
            }
        }

        if (!ok) {
            Game.Monster targetMonster = findMonster(g, targetId);
            if (targetMonster != null && loc.equals(targetMonster.location)) {
                ok = true;
            }
        }

        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cible invalide pour Filet.");
        }

        // On enregistre simplement la cible, sans lancer le dé
        a.setTargetId(targetId);
        a.setRoll(null);
        a.setBreakdownLines(java.util.List.of());
        a.setResolvedAtMillis(null);

        save(g);

        final String fLoc = loc;
        final String fTarget = targetId;
        final String fOwner = hunterId;

        afterCommit(() -> {
            // Notifie tout le monde que l’action (Filet) a une cible
            live.actionStarted(g, "NET", fOwner, fLoc, fTarget);
        });

        return g;
    }

    @Transactional
    public Game resolveNet(String gameId, String hunterId, String targetId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Filet se résout en PHASE3, juste avant les duels.");
        }

        if (g.getNetHunters() == null || !g.getNetHunters().contains(hunterId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucun Filet préparé pour ce joueur.");
        }

        Player hunter = findPlayer(g, hunterId);
        if (hunter == null || !"HUNTER".equals(hunter.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "bad hunter");
        }

        String loc = locationOf(g, hunterId);
        if (loc == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "le chasseur n’est sur aucun lieu.");
        }

        // Validation cible : joueur vampire/serviteur OU monstre sur ce lieu
        boolean ok = false;
        Player targetPlayer = findPlayer(g, targetId);
        Game.Monster targetMonster = findMonster(g, targetId);

        if (targetPlayer != null) {
            String tLoc = locationOf(g, targetId);
            if (loc.equals(tLoc)
                    && ("VAMPIRE".equals(targetPlayer.getRole()) || "SERVANT".equals(targetPlayer.getRole()))) {
                ok = true;
            }
        }
        if (!ok) {
            if (targetMonster != null && loc.equals(targetMonster.location)) {
                ok = true;
            }
        }

        if (!ok) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cible invalide pour Filet.");
        }

        Game.Action a = g.getCurrentAction();
        if (a != null && "NET".equals(a.getMode()) && hunterId.equals(a.getOwnerId())) {
            // Si une cible avait déjà été posée via chooseNetTarget, on vérifie la
            // cohérence
            if (a.getTargetId() != null && !a.getTargetId().equals(targetId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "cible différente de celle choisie pour Filet.");
            }
        }

        int roll = dice.roll(20);

        String msg = nameOf(g, hunterId) + " jette un dé pour son Filet contre "
                + entityName(g, targetId) + " (1d20 = " + roll + "). ";

        java.util.List<String> breakdown = new java.util.ArrayList<>();
        breakdown.add("Jet de Filet : 1d20 = " + roll);

        if (roll > 12) {
            if (g.getRaidMods() == null)
                g.setRaidMods(new java.util.HashMap<>());

            if (targetMonster != null) {
                // Monstre : malus engine (ENG)
                g.getRaidMods().computeIfAbsent(targetId, __ -> new java.util.ArrayList<>())
                        .add(new StatMod("DEFENSE", -1, "ACTION:NET:ENG"));
                g.getRaidMods().computeIfAbsent(targetId, __ -> new java.util.ArrayList<>())
                        .add(new StatMod("ATTACK", -1, "ACTION:NET:ENG"));
                msg += "Le Filet se referme : l’attaque et la défense de " + entityName(g, targetId)
                        + " sont réduites.";
                breakdown.add("Succès : ATK -1, DEF -1");
            } else {
                // Joueur : malus de défense standard
                g.getRaidMods().computeIfAbsent(targetId, __ -> new java.util.ArrayList<>())
                        .add(new StatMod("DEFENSE", -1, "ACTION:NET"));
                msg += "Le Filet se referme : la défense de " + entityName(g, targetId) + " est réduite de 1.";
                breakdown.add("Succès : DEF -1");
            }
        } else {
            msg += "Le Filet échoue à piéger sa cible.";
            breakdown.add("Échec : aucun malus");
        }

        addHistory(g, msg);

        // Décrémenter le compteur de cartes NET pour ce chasseur
        if (g.getNetCardsRemaining() == null) {
            g.setNetCardsRemaining(new java.util.HashMap<>());
        }
        int cardsRemaining = g.getNetCardsRemaining().getOrDefault(hunterId, 1) - 1;
        g.getNetCardsRemaining().put(hunterId, cardsRemaining);

        // Retirer le chasseur uniquement s'il n'a plus de cartes NET
        if (cardsRemaining <= 0) {
            g.getNetHunters().remove(hunterId);
        }

        // Remplir currentAction pour le front
        Game.Action current = new Game.Action();
        current.setMode("NET");
        current.setOwnerId(hunterId);
        current.setLocation(loc);
        current.setTargetId(targetId);
        current.setRoll(roll);
        current.setBreakdownLines(breakdown);
        current.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(current);

        save(g);

        final String fMsg = msg;
        final int fRoll = roll;
        final String fHunterId = hunterId;
        final String fTargetId = targetId;

        afterCommit(() -> {
            pushLive(g, fMsg);
            live.raidModsUpdated(g);
            // event principal pour le front
            live.actionRolled(g, "NET", fHunterId, fTargetId, fRoll);
        });

        return g;
    }

    @Transactional
    public Game resolvePit(String gameId, String victimId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Fosse se résout en PHASE3, juste avant les duels.");
        }

        // joueur qui clique (pas forcément la future cible, si c'est un monstre)
        Player clicker = findPlayer(g, victimId);
        if (clicker == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"VAMPIRE".equals(clicker.getRole()) && !"SERVANT".equals(clicker.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "seuls vampire/serviteurs sont concernés par la Fosse.");
        }

        if (g.getPitHunters() == null || g.getPitHunters().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Fosse préparée.");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"PIT".equals(a.getMode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Fosse en cours pour ce joueur.");
        }

        String hunterId = a.getOwnerId();
        String loc = a.getLocation();
        if (loc == null) {
            loc = locationOf(g, hunterId);
            if (loc == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "aucune Fosse active sur ce lieu.");
            }
        }

        // le clickeur doit être sur le même lieu que la Fosse
        String clickerLoc = locationOf(g, clicker.getId());
        if (!loc.equals(clickerLoc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "tu n’es pas sur le lieu de la Fosse.");
        }

        var targetsMap = g.getPitTargetsByHunter();
        var indexMap = g.getPitIndexByHunter();
        if (targetsMap == null || indexMap == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Fosse mal initialisée.");
        }

        var victims = targetsMap.get(hunterId);
        if (victims == null || victims.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune cible pour cette Fosse.");
        }

        int idx = indexMap.getOrDefault(hunterId, 0);
        if (idx >= victims.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "plus aucune cible pour cette Fosse.");
        }

        String targetId = victims.get(idx);

        // cohérence avec currentAction
        if (a.getTargetId() != null && !a.getTargetId().equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "cible de Fosse incohérente.");
        }

        Player targetPlayer = findPlayer(g, targetId);
        Game.Monster targetMonster = findMonster(g, targetId);

        if (targetPlayer == null && targetMonster == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "cible de Fosse invalide.");
        }

        // si la cible est un joueur : on garde l’ancien comportement :
        // la "victime" doit cliquer elle-même.
        if (targetPlayer != null) {
            if (!clicker.getId().equals(targetId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "ce n’est pas encore ton tour de résoudre la Fosse.");
            }
        } else {
            // si la cible est un monstre : c’est le vampire qui résout pour lui (si on
            // passe
            // par ici)
            if (!"VAMPIRE".equals(clicker.getRole())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "seul le vampire peut résoudre la Fosse pour une créature.");
            }
        }

        resolvePitInternal(g, hunterId, targetId, loc, idx, victims);

        save(g);

        afterCommit(() -> {
            pushLive(g, "Une fosse a été résolue.");
            live.raidModsUpdated(g);
            live.actionRolled(g, "PIT", hunterId, targetId, g.getCurrentAction().getRoll());
        });

        return g;
    }

    private void resolvePitInternal(Game g, String hunterId, String targetId, String loc, int idx,
            java.util.List<String> victims) {
        // sécurité : la cible (joueur ou monstre) est-elle toujours sur ce lieu ?
        if (!isEntityOnLocation(g, targetId, loc)) {
            // ne devrait pas trop arriver en auto-roll
            return;
        }

        // Jet
        int roll = dice.roll(20);

        String msg = entityName(g, targetId) + " jette un dé pour éviter la Fosse (1d20 = " + roll + "). ";

        java.util.List<String> breakdown = new java.util.ArrayList<>();
        breakdown.add("Jet pour la Fosse : 1d20 = " + roll);

        if (roll < 8) {
            if (g.getRaidMods() == null)
                g.setRaidMods(new java.util.HashMap<>());
            g.getRaidMods().computeIfAbsent(targetId, __ -> new java.util.ArrayList<>())
                    .add(new StatMod("DEFENSE", -2, "ACTION:PIT"));

            msg += "Il tombe dans la fosse : sa défense est réduite de 2.";
            breakdown.add("Échec : DEF -2");
        } else {
            msg += "Il évite la fosse.";
            breakdown.add("Réussite : aucun malus");
        }

        addHistory(g, msg);

        // Met currentAction à l’état "résolu" pour le front
        Game.Action current = new Game.Action();
        current.setMode("PIT");
        current.setOwnerId(hunterId);
        current.setLocation(loc);
        current.setTargetId(targetId);
        current.setRoll(roll);
        current.setBreakdownLines(breakdown);
        current.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(current);

        // Avancer l’index pour ce hunter
        int newIdx = idx + 1;
        g.getPitIndexByHunter().put(hunterId, newIdx);

        // Si plus de cible pour ce "passage"
        if (newIdx >= victims.size()) {
            // Vérifier s'il reste des cartes PIT (pour faire un 2ème passage, etc.)
            int cardsRemaining = (g.getPitCardsCount() != null ? g.getPitCardsCount().getOrDefault(hunterId, 1) : 1)
                    - 1;

            if (cardsRemaining > 0) {
                // On repart pour un tour !
                g.getPitCardsCount().put(hunterId, cardsRemaining);
                g.getPitIndexByHunter().put(hunterId, 0); // Reset index au début
            } else {
                // Terminé pour de bon
                if (g.getPitCardsCount() != null)
                    g.getPitCardsCount().put(hunterId, 0);
                g.getPitHunters().remove(hunterId);
            }
        }
    }

    @Transactional
    public Game resolveIncendiaire(String gameId, String hunterId, String infraCode) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }

        // Incendiaire se résout en PHASE3 (comme Filet/Fosse)
        if (g.getPhase() != Phase.PHASE3) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Incendiaire se résout en PHASE3, juste avant les duels.");
        }

        Player hunter = findPlayer(g, hunterId);
        if (hunter == null || !"HUNTER".equals(hunter.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "bad hunter");
        }

        // On vérifie que currentAction correspond bien à cet Incendiaire
        Game.Action ca = g.getCurrentAction();
        if (ca == null || !"INCENDIAIRE".equals(ca.getMode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune action Incendiaire en cours de résolution.");
        }
        if (!hunterId.equals(ca.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ce n'est pas ton Incendiaire à résoudre.");
        }

        String loc = ca.getLocation();
        if (loc == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "lieu introuvable pour Incendiaire.");
        }

        // Parsing de l'infra choisie
        final Infra infra;
        try {
            infra = Infra.valueOf(infraCode);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "infrastructure inconnue pour Incendiaire.");
        }

        // Jet de dé
        int roll = dice.roll(20);

        java.util.List<String> breakdown = new java.util.ArrayList<>();
        breakdown.add("Jet d'Incendiaire : 1d20 = " + roll);

        String infraLocCode = infra.locationCode(); // ex:
                                                    // "sawmill","mine","library","laboratory","ballroom","altar","forge"
        String infraLabel = (infraLocCode != null ? labelLieuFr(infraLocCode) : infra.name());

        String msg;

        if (roll > 5) {
            boolean destroyedPending = false;
            boolean willDestroyBuilt = false;

            // 1) Annuler une construction en cours
            Game.PendingConstruction pc = g.getPendingConstruction();
            if (pc != null && pc.infra == infra) {
                g.setPendingConstruction(null);
                destroyedPending = true;
            }

            // 2) Si déjà construite → destruction différée en fin de raid
            if (g.getBuiltInfras() != null && g.getBuiltInfras().contains(infra)) {
                if (g.getInfrasToDestroyEndOfRaid() == null) {
                    g.setInfrasToDestroyEndOfRaid(java.util.EnumSet.noneOf(Infra.class));
                }
                g.getInfrasToDestroyEndOfRaid().add(infra);
                willDestroyBuilt = true;
            }

            if (destroyedPending) {
                msg = nameOf(g, hunterId)
                        + " fait échouer la construction de " + infraLabel
                        + " (1d20 -> " + roll + ").";
                breakdown.add("Succès : la construction de " + infraLabel + " est ravagée par les flammes.");
            } else if (willDestroyBuilt) {
                msg = nameOf(g, hunterId)
                        + " met le feu à " + infraLabel
                        + " (1d20 -> " + roll + ").";
                breakdown.add("Succès : " + infraLabel + " est ravagé par les flammes.");
            } else {
                msg = "Aucune construction à détruire.";
                breakdown.add("Aucune construction correspondant à " + infraLabel + " sur ce lieu.");
            }

            addHistory(g, msg);
        } else {
            msg = nameOf(g, hunterId)
                    + " tente d'incendier " + infraLabel
                    + " (1d20 -> " + roll + ") mais échoue.";
            breakdown.add("Échec : aucune construction n'est affectée.");
            addHistory(g, msg);
        }

        // On marque le résultat sur l'action courante
        ca.setTargetId(infra.name()); // pour que le front sache de quoi on parle
        ca.setRoll(roll);
        ca.setBreakdownLines(breakdown);
        ca.setResolvedAtMillis(System.currentTimeMillis());

        // On retire cet Incendiaire de la liste des préparés
        if (g.getIncendiaireLocationByHunter() != null) {
            g.getIncendiaireLocationByHunter().remove(hunterId);
        }

        save(g);

        final String fMsg = msg;
        final int fRoll = roll;
        final String fHunterId = hunterId;
        final String fInfra = infra.name();

        afterCommit(() -> {
            pushLive(g, fMsg);
            live.actionRolled(g, "INCENDIAIRE", fHunterId, fInfra, fRoll);
        });

        return g;
    }

    /**
     * Retourne true s'il reste au moins une infrastructure cible possible pour
     * Incendiaire
     * sur ce lieu (construite ou en construction).
     */
    private boolean hasIncendiaireTargetsOnLocation(Game g, String loc) {
        if (loc == null)
            return false;

        java.util.EnumSet<Infra> built = (g.getBuiltInfras() != null)
                ? g.getBuiltInfras()
                : java.util.EnumSet.noneOf(Infra.class);

        Game.PendingConstruction pc = g.getPendingConstruction();
        Infra pending = (pc != null ? pc.infra : null);

        java.util.function.Predicate<Infra> available = infra -> built.contains(infra) || (pending == infra);

        switch (loc) {
            case "forest":
            case "sawmill":
                return available.test(Infra.SAWMILL);

            case "quarry":
            case "mine":
                return available.test(Infra.MINE);

            case "manor":
                return available.test(Infra.LIBRARY)
                        || available.test(Infra.LABORATORY)
                        || available.test(Infra.BALLROOM)
                        || available.test(Infra.ALTAR)
                        || available.test(Infra.FORGE);

            case "library":
                return available.test(Infra.LIBRARY);

            case "laboratory":
                return available.test(Infra.LABORATORY);

            case "ballroom":
                return available.test(Infra.BALLROOM);

            case "altar":
                return available.test(Infra.ALTAR);

            case "forge":
                return available.test(Infra.FORGE);

            default:
                // lac ou autre
                return false;
        }
    }

    @Transactional
    public Game resolveProvocation(String gameId, String hunterId, String enemyId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Provocation se résout en PREPHASE3.");
        }

        Player hunter = findPlayer(g, hunterId);
        if (hunter == null || !"HUNTER".equals(hunter.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseur uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null
                || !"PROVOCATION".equals(a.getMode())
                || !hunterId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Provocation en cours pour ce joueur.");
        }

        if (enemyId == null || enemyId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetId required");
        }

        Player enemy = findPlayer(g, enemyId);
        if (enemy == null
                || (!"VAMPIRE".equals(enemy.getRole()) && !"SERVANT".equals(enemy.getRole()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "la cible doit être le vampire ou un serviteur.");
        }

        if (enemy.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "la cible doit être vivante.");
        }

        String hunterLoc = locationOf(g, hunterId);
        String enemyLoc = locationOf(g, enemyId);
        if (hunterLoc == null || !hunterLoc.equals(enemyLoc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "la cible doit être sur le même lieu que le chasseur.");
        }

        // On vérifie encore la condition 2 chasseurs + 1 ennemi (sécurité)
        java.util.List<Player> onLoc = g.getPlayers().stream()
                .filter(pl -> hunterLoc.equals(locationOf(g, pl.getId())))
                .toList();

        long huntersCount = onLoc.stream()
                .filter(pl -> "HUNTER".equals(pl.getRole()))
                .filter(pl -> pl.getHp() > 0)
                .count();

        long enemyCount = onLoc.stream()
                .filter(pl -> ("VAMPIRE".equals(pl.getRole()) || "SERVANT".equals(pl.getRole())))
                .filter(pl -> pl.getHp() > 0)
                .count();

        if (enemyCount < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Provocation nécessite au moins 1 ennemi sur le lieu.");
        }

        // Enregistrer la provocation pour ce raid
        if (g.getProvokedTargetByEnemy() == null) {
            g.setProvokedTargetByEnemy(new java.util.HashMap<>());
        }
        if (g.getPendingVampireEscape() != null) {
            addHistory(g, "Provocation — le stratagème d'évasion du vampire (" + g.getPendingVampireEscape()
                    + ") est annulé !");
            g.setPendingVampireEscape(null);
        }

        g.getProvokedTargetByEnemy().put(enemy.getId(), hunter.getId());

        // Puce display éventuelle
        addRaidMod(g, enemy.getId(), "ATTACK", 0, "ACTION:PROVOCATION:DSP");

        String hist = "Provocation — " + nameOf(g, hunter.getId())
                + " provoque " + nameOf(g, enemy.getId())
                + " : il devra le prendre pour cible en priorité ce raid.";
        addHistory(g, hist);

        a.setTargetId(enemy.getId());
        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null); // on ferme la modale comme Marque / Affaiblissement

        // Relancer la mécanique de préphase (30s ou passage phase3)
        setupUnstableAndPrephaseTimeout(g);

        save(g);

        Game gAfter = g;
        afterCommit(() -> {
            live.actionResolved(gAfter, "PROVOCATION", hunterId, enemy.getId());
            live.phaseChanged(gAfter);
        });

        return g;
    }

    @Transactional
    public Game resolveAmbush(String gameId, String playerId, String targetId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Embuscade se résout en PREPHASE3.");
        }

        Player hunter = findPlayer(g, playerId);
        if (hunter == null || !"HUNTER".equals(hunter.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseur uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null
                || !"AMBUSH".equals(a.getMode())
                || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Embuscade en cours pour ce joueur.");
        }

        if (targetId == null || targetId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetId required");
        }

        Player target = findPlayer(g, targetId);
        if (target == null
                || (!"VAMPIRE".equals(target.getRole()) && !"SERVANT".equals(target.getRole()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "la cible doit être un vampire ou un serviteur.");
        }

        if (target.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "la cible doit être vivante.");
        }

        String loc = a.getLocation();
        String targetLoc = locationOf(g, target.getId());
        if (targetLoc == null || !targetLoc.equals(loc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "la cible n'est plus sur le lieu de l'embuscade.");
        }

        // Tous les chasseurs vivants sur ce lieu
        java.util.List<Player> onLoc = g.getPlayers().stream()
                .filter(pl -> loc.equals(locationOf(g, pl.getId())))
                .toList();

        java.util.List<Player> huntersOnLoc = onLoc.stream()
                .filter(pl -> "HUNTER".equals(pl.getRole()))
                .filter(pl -> pl.getHp() > 0)
                .toList();

        if (huntersOnLoc.size() < 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "il n'y a plus assez de chasseurs groupés pour Embuscade.");
        }

        int huntersCount = huntersOnLoc.size();

        int bonus = huntersCount;

        for (Player h : huntersOnLoc) {
            addRaidMod(g, h.getId(), "ATTACK", bonus, "ACTION:AMBUSH:ENG");
        }

        // Blocage des ripostes de la cible contre ces chasseurs
        if (g.getAmbushHuntersByEnemy() == null) {
            g.setAmbushHuntersByEnemy(new java.util.HashMap<>());
        }
        java.util.List<String> hunterIds = huntersOnLoc.stream()
                .map(Player::getId)
                .toList();
        g.getAmbushHuntersByEnemy().put(target.getId(), hunterIds);

        // Enregistrer le lieu comme ayant eu une embuscade
        if (g.getAmbushLocations() == null) {
            g.setAmbushLocations(new java.util.HashSet<>());
        }
        g.getAmbushLocations().add(loc);

        String hist = "Embuscade — "
                + nameOf(g, hunter.getId())
                + " coordonne une attaque avec "
                + huntersOnLoc.size() + " chasseurs contre "
                + nameOf(g, target.getId())
                + " : chacun gagne +" + bonus + " ATK ce raid, et "
                + nameOf(g, target.getId())
                + " ne pourra pas riposter contre eux ce raid.";
        addHistory(g, hist);

        a.setTargetId(target.getId());
        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        // on relance la mécanique de préphase
        setupUnstableAndPrephaseTimeout(g);

        save(g);

        Game gAfter = g;
        afterCommit(() -> {
            live.actionResolved(gAfter, "AMBUSH", playerId, target.getId());
            live.phaseChanged(gAfter);
        });

        return g;
    }

    @Transactional
    public Game resolveBlessedStake(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Pieu béni se résout pendant les combats (PHASE3).");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"BLESSED_STAKE".equals(a.getMode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune action pieu béni en cours.");
        }

        if (!userId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ce pieu béni appartient à un autre chasseur.");
        }

        Player hunter = findPlayer(g, a.getOwnerId());
        if (hunter == null || !"HUNTER".equals(hunter.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "bad hunter");
        }

        String targetId = a.getTargetId();
        if (targetId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "cible manquante pour pieu béni.");
        }

        Player targetPlayer = findPlayer(g, targetId);
        Game.Monster targetMonster = (targetPlayer == null) ? findMonster(g, targetId) : null;
        if (targetPlayer == null && targetMonster == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cible invalide pour pieu béni.");
        }

        // --- Jet d4 ---
        int roll = dice.roll(4); // 1..4

        int beforeHp;
        if (targetPlayer != null) {
            beforeHp = targetPlayer.getHp();
            targetPlayer.setHp(Math.max(0, beforeHp - roll));
        } else {
            beforeHp = targetMonster.hp;
            targetMonster.hp = Math.max(0, beforeHp - roll);
        }

        int afterHp = (targetPlayer != null ? targetPlayer.getHp() : targetMonster.hp);
        int realExtra = beforeHp - afterHp;

        java.util.List<String> breakdown = new java.util.ArrayList<>();
        breakdown.add("Jet de pieu béni : 1d4 = " + roll + " → " + realExtra + " dégâts sacrés.");

        String msg = "Pieu béni — "
                + nameOf(g, hunter.getId())
                + " inflige " + realExtra
                + " dégâts sacrés supplémentaires à "
                + entityName(g, targetId)
                + " (1d4 = " + roll + ").";

        addHistory(g, msg);

        // Si la cible est le vampire et qu'il perd des PV, on garde le flag cohérent
        if (realExtra > 0 && targetPlayer != null && "VAMPIRE".equals(targetPlayer.getRole())) {
            g.setVampireTookDamageThisRaid(true);
        }

        // Effet persistant : consommé DEFINITIVEMENT une fois utilisé
        hunter.setBlessedStake(false);

        var list = g.getRaidMods().get(hunter.getId());

        list.removeIf(m -> {
            String src = m.getSource();
            return src != null && src.startsWith("ACTION:BLESSED_STAKE");
        });

        // Remplir currentAction pour le front (modale)
        a.setRoll(roll);
        a.setBreakdownLines(breakdown);
        a.setResolvedAtMillis(System.currentTimeMillis());

        // --- Après les dégâts: gérer morts + fin de partie
        handleDeathsAndVictory(g);

        save(g);

        final String fMsg = msg;
        final int fRoll = roll;
        final String fOwner = hunter.getId();
        final String fTarget = targetId;

        afterCommit(() -> {
            pushLive(g, fMsg);
            live.raidModsUpdated(g);
            // même pattern que Filet : pour mettre la modale à jour
            live.actionRolled(g, "BLESSED_STAKE", fOwner, fTarget, fRoll);
        });

        return g;
    }

    @Transactional
    public Game rollMerchantItinerant(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (g.getPhase() != Phase.PHASE4) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");
        }

        Player p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!isAlive(p)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");
        }
        if (!"HUNTER".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");
        }

        // le jet n'existe que si le joueur a déclenché le marchand
        if (!p.isMerchantPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no merchant roll pending");
        }
        if (p.getMerchantRoll() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "merchant already rolled");
        }

        int d6 = dice.roll(6);
        p.setMerchantRoll(d6);
        p.setMerchantPending(false); // plus en attente
        p.setShopBonusBuyPending(false); // reset sécurité

        // reset offer
        p.setShopBonusKind(null);
        p.setShopBonusEquipId(null);
        p.setShopBonusEquipTier(null);

        String kind;
        String line;

        if (d6 == 1 || d6 == 2) {
            kind = "POTION";
            line = "Marchand itinérant — une potion est disponible à la boutique pour " + nameOf(g, userId) + ".";
        } else if (d6 == 3 || d6 == 4) {
            kind = "ELIXIR";
            line = "Marchand itinérant — un élixir est disponible à la boutique pour " + nameOf(g, userId) + ".";
        } else {
            // 5-6 : équipement perso
            int wTier = hunterWeaponTier(p.getWeapon());
            int aTier = hunterArmorTier(p.getArmor());

            Integer wOffer = (wTier < 2) ? Math.min(2, wTier + 1) : null;
            Integer aOffer = (aTier < 2) ? Math.min(2, aTier + 1) : null;

            // Règle : si T0/T1 => on propose celui qui est le plus bas, sinon 50/50
            boolean preferWeapon;
            if (wTier < aTier)
                preferWeapon = true;
            else if (aTier < wTier)
                preferWeapon = false;
            else
                preferWeapon = dice.nextBoolean();

            boolean weapon;
            Integer tier;

            if (preferWeapon && wOffer != null) {
                weapon = true;
                tier = wOffer;
            } else if (!preferWeapon && aOffer != null) {
                weapon = false;
                tier = aOffer;
            } else if (wOffer != null) {
                weapon = true;
                tier = wOffer;
            } else if (aOffer != null) {
                weapon = false;
                tier = aOffer;
            } else {
                // déjà T2 partout => pas d’équipement (jamais T3) => fallback
                kind = "ELIXIR";
                p.setShopBonusKind(kind);
                addHistory(g, "Marchand itinérant — " + nameOf(g, userId)
                        + " est déjà équipé au maximum (T2). Offre remplacée par un élixir.");
                save(g);
                afterCommit(() -> live.actionResolved(g, "MARCHAND_ITINERANT", userId, null));
                return g;
            }

            kind = weapon ? "EQUIP_WEAPON" : "EQUIP_ARMOR";

            String equipId = weapon ? merchantWeaponIdForTier(tier) : merchantArmorIdForTier(tier);

            p.setShopBonusEquipTier(tier); // sert au prix
            p.setShopBonusEquipId(equipId); // offre stable
            line = "Marchand itinérant — " + (weapon ? "une arme" : "une armure")
                    + " T" + tier + " est disponible à la boutique pour " + nameOf(g, userId) + ".";
        }

        p.setShopBonusKind(kind);
        addHistory(g, line);

        save(g);

        afterCommit(() -> {
            // juste pour forcer les clients à refresh le snapshot (sans modale spectateur)
            live.actionResolved(g, "MARCHAND_ITINERANT", userId, null);
        });

        return g;
    }

    @Transactional
    public Game rollAdvancedTransmutation(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (g.getPhase() != Phase.PHASE4) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");
        }

        Player p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!isAlive(p)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");
        }
        if (!"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampires only");
        }

        // le jet n'existe que si le joueur a déclenché la carte
        if (!p.isMerchantPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no transmutation roll pending");
        }
        if (p.getMerchantRoll() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "transmutation already rolled");
        }

        int d6 = dice.roll(6);
        p.setMerchantRoll(d6);
        p.setMerchantPending(false); // plus en attente
        p.setShopBonusBuyPending(false); // reset sécurité

        // reset offer
        p.setShopBonusKind(null);
        p.setShopBonusEquipId(null);
        p.setShopBonusEquipTier(null);

        String kind;
        String line;

        if (d6 >= 1 && d6 <= 3) {
            // 1-3: élixir aléatoire
            kind = "ELIXIR";
            line = "Transmutation avancée — un élixir est disponible dans l'antre pour " + nameOf(g, userId) + ".";
        } else {
            // 4-6 : équipement perso (vampire)
            int wTier = vampireWeaponTier(p.getWeapon());
            int aTier = vampireArmorTier(p.getArmor());

            Integer wOffer = (wTier < 2) ? Math.min(2, wTier + 1) : null;
            Integer aOffer = (aTier < 2) ? Math.min(2, aTier + 1) : null;

            // Règle : si T0/T1 => on propose celui qui est le plus bas, sinon 50/50
            boolean preferWeapon;
            if (wTier < aTier)
                preferWeapon = true;
            else if (aTier < wTier)
                preferWeapon = false;
            else
                preferWeapon = dice.nextBoolean();

            boolean weapon;
            Integer tier;

            if (preferWeapon && wOffer != null) {
                weapon = true;
                tier = wOffer;
            } else if (!preferWeapon && aOffer != null) {
                weapon = false;
                tier = aOffer;
            } else if (wOffer != null) {
                weapon = true;
                tier = wOffer;
            } else if (aOffer != null) {
                weapon = false;
                tier = aOffer;
            } else {
                // déjà T2 partout => pas d'équipement (jamais T3) => fallback
                kind = "ELIXIR";
                p.setShopBonusKind(kind);
                addHistory(g, "Transmutation avancée — " + nameOf(g, userId)
                        + " est déjà équipé au maximum (T2). Offre remplacée par un élixir.");
                save(g);
                afterCommit(() -> live.actionResolved(g, "ADVANCED_TRANSMUTATION", userId, null));
                return g;
            }

            kind = weapon ? "EQUIP_WEAPON" : "EQUIP_ARMOR";

            String equipId = weapon ? vampireWeaponIdForTier(tier) : vampireArmorIdForTier(tier);

            p.setShopBonusEquipTier(tier); // sert au prix
            p.setShopBonusEquipId(equipId); // offre stable
            line = "Transmutation avancée — " + (weapon ? "une arme" : "une armure")
                    + " T" + tier + " est disponible dans l'antre pour " + nameOf(g, userId) + ".";
        }

        p.setShopBonusKind(kind);
        addHistory(g, line);

        save(g);

        afterCommit(() -> {
            // juste pour forcer les clients à refresh le snapshot (sans modale spectateur)
            live.actionResolved(g, "ADVANCED_TRANSMUTATION", userId, null);
        });

        return g;
    }

    @Transactional
    public Game rollCrate(String gameId, String userId) {
        Game g = findOr404(gameId);
        Player p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!isAlive(p)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat.");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || (!"CRATE_LAKE".equals(a.getMode()) && !"CRATE_MANOR".equals(a.getMode()))
                || !userId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pas de caisse à ouvrir.");
        }

        if (a.getRoll() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Caisse déjà ouverte.");
        }

        int d6 = dice.roll(6);
        a.setRoll(d6);

        java.util.List<String> breakdown = new java.util.ArrayList<>();
        String resMsg;
        if (d6 <= 2) {
            grant(p, "wood", 2);
            breakdown.add("Jet : " + d6 + " (1-2) → +2 bois.");
            resMsg = nameOf(g, userId) + " trouve 2 bois dans la caisse (1d6 = " + d6 + ").";
        } else if (d6 <= 4) {
            grant(p, "iron", 2);
            breakdown.add("Jet : " + d6 + " (3-4) → +2 fer.");
            resMsg = nameOf(g, userId) + " trouve 2 fer dans la caisse (1d6 = " + d6 + ").";
        } else {
            grant(p, "wood", 2);
            grant(p, "iron", 2);
            breakdown.add("Jet : " + d6 + " (5-6) → +2 bois et +2 fer.");
            resMsg = nameOf(g, userId) + " trouve 2 bois et 2 fer dans la caisse (1d6 = " + d6 + ").";
        }

        a.setBreakdownLines(breakdown);
        a.setResolvedAtMillis(System.currentTimeMillis());

        addHistory(g, resMsg);
        setupUnstableAndPrephaseTimeout(g);

        save(g);

        final int fRoll = d6;
        afterCommit(() -> {
            pushLive(g, resMsg);
            live.actionRolled(g, a.getMode(), userId, null, fRoll);
        });

        return g;
    }

    @Transactional
    public Game resolveCrateAction(String gameId, String userId) {
        Game g = findOr404(gameId);
        Game.Action a = g.getCurrentAction();
        if (a == null || (!"CRATE_LAKE".equals(a.getMode()) && !"CRATE_MANOR".equals(a.getMode()))
                || !userId.equals(a.getOwnerId())) {
            return g;
        }

        String oldMode = a.getMode();
        Player p = findPlayer(g, userId);
        if (p != null) {
            p.setCrateUsedThisRaid(true);
        }

        g.setCurrentAction(null);
        setupUnstableAndPrephaseTimeout(g);
        save(g);

        afterCommit(() -> live.actionResolved(g, oldMode, userId, null));

        return g;
    }

    private int hunterWeaponTier(String weaponId) {
        if (weaponId == null)
            return 0;
        if (weaponId.startsWith("H_WEAPON_T1_"))
            return 1;
        if (weaponId.startsWith("H_WEAPON_T2_"))
            return 2;
        if (weaponId.startsWith("H_WEAPON_T3_"))
            return 3;
        return 0;
    }

    private int hunterArmorTier(String armorId) {
        if (armorId == null)
            return 0;
        if (armorId.startsWith("H_ARMOR_T1_"))
            return 1;
        if (armorId.startsWith("H_ARMOR_T2_"))
            return 2;
        if (armorId.startsWith("H_ARMOR_T3_"))
            return 3;
        return 0;
    }

    private String merchantWeaponIdForTier(int tier) {
        // sécurité: clamp 1..2
        tier = Math.max(1, Math.min(2, tier));

        int r3 = dice.roll(3); // 1..3 → bleed / stun / ranged

        return switch (tier) {
            case 1 -> switch (r3) {
                case 1 -> H_WEAPON_T1_SWORD;
                case 2 -> H_WEAPON_T1_MACE;
                case 3 -> H_WEAPON_T1_SPEAR;
                default -> H_WEAPON_T1_SWORD;
            };
            case 2 -> switch (r3) {
                case 1 -> H_WEAPON_T2_HALBERD;
                case 2 -> H_WEAPON_T2_HAMMER;
                case 3 -> H_WEAPON_T2_CROSSBOW;
                default -> H_WEAPON_T2_HALBERD;
            };
            default -> H_WEAPON_T1_SWORD;
        };
    }

    private String merchantArmorIdForTier(int tier) {
        // sécurité: clamp 1..2
        tier = Math.max(1, Math.min(2, tier));

        return switch (tier) {
            case 1 -> H_ARMOR_T1_BRIGANDINE;
            case 2 -> H_ARMOR_T2_HAUBERT;
            default -> H_ARMOR_T1_BRIGANDINE;
        };
    }

    // === Vampire Equipment Helpers ===
    private int vampireWeaponTier(String weaponId) {
        if (weaponId == null)
            return 0;
        if (weaponId.startsWith("V_WEAPON_T1_"))
            return 1;
        if (weaponId.startsWith("V_WEAPON_T2_"))
            return 2;
        if (weaponId.startsWith("V_WEAPON_T3_"))
            return 3;
        return 0;
    }

    private int vampireArmorTier(String armorId) {
        if (armorId == null)
            return 0;
        if (armorId.startsWith("V_ARMOR_T1_"))
            return 1;
        if (armorId.startsWith("V_ARMOR_T2_"))
            return 2;
        if (armorId.startsWith("V_ARMOR_T3_"))
            return 3;
        return 0;
    }

    private String vampireWeaponIdForTier(int tier) {
        // sécurité: clamp 1..2 (never offer T3)
        tier = Math.max(1, Math.min(2, tier));
        return switch (tier) {
            case 1 -> V_WEAPON_T1_SCYTHE;
            case 2 -> V_WEAPON_T2_SWORD;
            default -> V_WEAPON_T1_SCYTHE;
        };
    }

    private String vampireArmorIdForTier(int tier) {
        // sécurité: clamp 1..2 (never offer T3)
        tier = Math.max(1, Math.min(2, tier));
        return switch (tier) {
            case 1 -> V_ARMOR_T1_CARAPACE;
            case 2 -> V_ARMOR_T2_HAUBERT;
            default -> V_ARMOR_T1_CARAPACE;
        };
    }

    @Transactional
    public Game buyResource(String gameId, String userId, String resourceType) {
        Game g = findOr404(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        Player p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!isAlive(p))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");

        // Check if already bought this raid
        if (p.isResourceBoughtThisRaid())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu as déjà acheté une ressource ce raid");

        // Check gold
        if (p.getGold() < 100)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pas assez d'or (100 requis)");

        // Validate resource type
        if (!"WOOD".equals(resourceType) && !"IRON".equals(resourceType)
                && !"WATER".equals(resourceType) && !"HERBS".equals(resourceType))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type de ressource invalide");

        // Deduct gold
        p.setGold(p.getGold() - 100);

        // Add resource
        switch (resourceType) {
            case "WOOD" -> p.setWood(p.getWood() + 1);
            case "IRON" -> p.setIron(p.getIron() + 1);
            case "WATER" -> p.setWater(p.getWater() + 1);
            case "HERBS" -> p.setHerbs(p.getHerbs() + 1);
        }

        // Mark as bought
        p.setResourceBoughtThisRaid(true);

        // Add history
        String resName = switch (resourceType) {
            case "WOOD" -> "bois";
            case "IRON" -> "fer";
            case "WATER" -> "eau";
            case "HERBS" -> "plantes";
            default -> resourceType;
        };
        String msg = nameOf(g, userId) + " a acheté 1 " + resName + " pour 100 pièces d'or.";
        addHistory(g, msg);

        save(g);
        afterCommit(() -> live.stuffBought(g, userId));

        return g;
    }

    @Transactional
    public Game startShopBonusPurchase(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        Player p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!isAlive(p))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");
        if (!"HUNTER".equals(p.getRole()) && !"VAMPIRE".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters and vampires only");

        String kind = p.getShopBonusKind();
        if (kind == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no bonus item available");

        // perso : ouvre la modale paiement seulement pour CE joueur
        p.setShopBonusBuyPending(true);

        save(g);

        afterCommit(() -> {
            // refresh clients (ne déclenche pas de modale chez les autres)
            String mode = "VAMPIRE".equals(p.getRole()) ? "ADVANCED_TRANSMUTATION_BUY" : "MARCHAND_BONUS_BUY";
            live.actionStarted(g, mode, userId, null, null);
        });

        return g;
    }

    @Transactional
    public Game buyShopBonus(String gameId, String userId, String payment) {
        Game g = findOr404(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        Player p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!isAlive(p))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");
        boolean isHunter = "HUNTER".equals(p.getRole());
        boolean isVampire = "VAMPIRE".equals(p.getRole());

        if (!isHunter && !isVampire)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters and vampires only");

        // Validate payment mode based on role
        if (payment == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payment mode required");
        }
        if (isHunter && !"RESOURCE".equals(payment) && !"GOLD".equals(payment)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hunters can pay with RESOURCE or GOLD");
        }
        if (isVampire && !"RESOURCE".equals(payment) && !"SOULS".equals(payment)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "vampires can pay with RESOURCE or SOULS");
        }

        String kind = p.getShopBonusKind();
        if (kind == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no bonus item available");
        }

        // prix adaptables
        Integer tier = p.getShopBonusEquipTier();
        boolean greedy = g.isShopPricesIncreasedThisRaid();
        boolean charismatic = p.isCharismaticThisRaid();

        java.util.function.IntUnaryOperator goldCost = (base) -> {
            int c = base;
            if (greedy)
                c += 50;
            if (charismatic)
                c = Math.max(0, c - 20);
            return c;
        };

        String message;

        switch (kind) {
            case "POTION" -> {
                if ("RESOURCE".equals(payment)) {
                    final int waterCost = 1, herbsCost = 2;
                    if (p.getWater() < waterCost || p.getHerbs() < herbsCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setWater(p.getWater() - waterCost);
                    p.setHerbs(p.getHerbs() - herbsCost);
                } else {
                    int cost = goldCost.applyAsInt(30);
                    if (p.getGold() < cost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setGold(p.getGold() - cost);
                }

                String potionId = drawFromDeck(g.getPotionDeck(), g.getPotionDiscard());
                if (potionId == null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no potions left");

                if (p.getPotions() == null)
                    p.setPotions(new java.util.ArrayList<>());
                p.getPotions().add(potionId);

                message = nameOf(g, userId) + " achète une potion (" + potionId + ") via le marchand itinérant ("
                        + ("RESOURCE".equals(payment) ? "ressources" : "or") + ").";
                addHistory(g, message);
            }

            case "ELIXIR" -> {
                if ("RESOURCE".equals(payment)) {
                    final int waterCost = 2, herbsCost = 4;
                    if (p.getWater() < waterCost || p.getHerbs() < herbsCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setWater(p.getWater() - waterCost);
                    p.setHerbs(p.getHerbs() - herbsCost);
                } else if ("GOLD".equals(payment)) {
                    int cost = goldCost.applyAsInt(60);
                    if (p.getGold() < cost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setGold(p.getGold() - cost);
                } else if ("SOULS".equals(payment)) {
                    final int soulsCost = 50;
                    if (p.getSouls() < soulsCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing souls");
                    p.setSouls(p.getSouls() - soulsCost);
                }

                String elixirId = drawFromDeck(g.getElixirDeck(), g.getElixirDiscard());
                if (elixirId == null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no elixirs left");

                // FIX: elixirs dans la bonne liste
                if (p.getElixirs() == null)
                    p.setElixirs(new java.util.ArrayList<>());
                p.getElixirs().add(elixirId);

                String source = isVampire ? "transmutation avancée" : "le marchand itinérant";
                String paymentLabel = "RESOURCE".equals(payment) ? "ressources"
                        : ("GOLD".equals(payment) ? "or" : "âmes déchues");
                message = nameOf(g, userId) + " achète un élixir (" + elixirId + ") via " + source + " (" + paymentLabel
                        + ").";
                addHistory(g, message);
            }

            case "EQUIP_WEAPON" -> {
                int t = (tier == null) ? 1 : Math.max(1, Math.min(2, tier)); // sécurité 1..2
                String weaponId = p.getShopBonusEquipId();
                if (weaponId == null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid offer");

                // coût selon tier
                int woodCost = (t == 2) ? 2 : 1;
                int ironCost = (t == 2) ? 2 : 1;
                int baseGold = (t == 2) ? 150 : 100;
                int soulsCost = (t == 2) ? 150 : 100;

                if ("RESOURCE".equals(payment)) {
                    if (p.getWood() < woodCost || p.getIron() < ironCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setWood(p.getWood() - woodCost);
                    p.setIron(p.getIron() - ironCost);
                } else if ("GOLD".equals(payment)) {
                    int cost = goldCost.applyAsInt(baseGold);
                    if (p.getGold() < cost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setGold(p.getGold() - cost);
                } else if ("SOULS".equals(payment)) {
                    if (p.getSouls() < soulsCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing souls");
                    p.setSouls(p.getSouls() - soulsCost);
                }

                int current = isVampire ? vampireWeaponTier(p.getWeapon()) : hunterWeaponTier(p.getWeapon());
                if (current >= t)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "tu as déjà une arme supérieure");

                p.setWeapon(weaponId);
                switch (t) {
                    case 1 -> p.setAttackDice("D6");
                    case 2 -> p.setAttackDice("D8");
                }

                String source = isVampire ? "transmutation avancée" : "le marchand";
                String paymentLabel = "RESOURCE".equals(payment) ? "ressources"
                        : ("GOLD".equals(payment) ? "or" : "âmes déchues");
                message = nameOf(g, userId) + " achète une arme T" + t + " (" + weaponId + ") via " + source + " ("
                        + paymentLabel + ").";
                addHistory(g, message);
            }

            case "EQUIP_ARMOR" -> {
                int t = (tier == null) ? 1 : Math.max(1, Math.min(2, tier));
                String armorId = p.getShopBonusEquipId();
                if (armorId == null)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid offer");

                int woodCost = (t == 2) ? 2 : 1;
                int ironCost = (t == 2) ? 2 : 1;
                int baseGold = (t == 2) ? 150 : 100;
                int soulsCost = (t == 2) ? 150 : 100;

                if ("RESOURCE".equals(payment)) {
                    if (p.getWood() < woodCost || p.getIron() < ironCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setWood(p.getWood() - woodCost);
                    p.setIron(p.getIron() - ironCost);
                } else if ("GOLD".equals(payment)) {
                    int cost = goldCost.applyAsInt(baseGold);
                    if (p.getGold() < cost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                    p.setGold(p.getGold() - cost);
                } else if ("SOULS".equals(payment)) {
                    if (p.getSouls() < soulsCost)
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "missing souls");
                    p.setSouls(p.getSouls() - soulsCost);
                }

                int current = isVampire ? vampireArmorTier(p.getArmor()) : hunterArmorTier(p.getArmor());
                if (current >= t)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "tu as déjà une armure supérieure");

                p.setArmor(armorId);
                switch (t) {
                    case 1 -> p.setDefenseDice("D6");
                    case 2 -> p.setDefenseDice("D8");
                }

                String source = isVampire ? "transmutation avancée" : "le marchand";
                String paymentLabel = "RESOURCE".equals(payment) ? "ressources"
                        : ("GOLD".equals(payment) ? "or" : "âmes déchues");
                message = nameOf(g, userId) + " achète une armure T" + t + " (" + armorId + ") via " + source + " ("
                        + paymentLabel + ").";
                addHistory(g, message);
            }

            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid bonus kind");
        }

        // consume l'offre perso + ferme modale paiement perso
        p.setShopBonusKind(null);
        p.setShopBonusEquipId(null);
        p.setShopBonusEquipTier(null);
        p.setShopBonusBuyPending(false);
        p.setMerchantRoll(null);

        rebuildEquipmentMods(g);
        save(g);

        final String fMessage = message;

        afterCommit(() -> {
            pushLive(g, fMessage);
            String mode = isVampire ? "ADVANCED_TRANSMUTATION_BUY" : "MARCHAND_BONUS_BUY";
            live.actionResolved(g, mode, userId, null);
        });

        return g;
    }

    @Transactional
    public Game cancelShopBonusPurchase(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");

        // annulation basée sur l'état joueur
        if (!p.isShopBonusBuyPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no merchant buy in progress");
        }

        // fermer la modale achat perso
        p.setShopBonusBuyPending(false);

        // sécurité : si un currentAction marchand traîne encore, on le ferme
        Game.Action a = g.getCurrentAction();
        if (a != null
                && "MARCHAND_BONUS_BUY".equals(a.getMode())
                && userId.equals(a.getOwnerId())) {
            g.setCurrentAction(null);
        }

        String msg = nameOf(g, userId) + " renonce à acheter l’objet du marchand itinérant.";
        addHistory(g, msg);

        save(g);

        afterCommit(() -> {
            pushLive(g, msg);
            live.actionResolved(g, "MARCHAND_BONUS_BUY", userId, null);
        });

        return g;
    }

    // ACTIONS VAMPIRE
    @Transactional
    public void resolveCataclysme(String gameId,
            String playerId,
            WeatherStatus secondChoice,
            WeatherStatus thirdChoice) {

        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }

        Player p = findPlayer(g, playerId);
        if (p == null || !"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        if (g.getPhase() != Phase.PHASE2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cataclysme ne peut être résolu qu'en PHASE2.");
        }

        Game.Action ca = g.getCurrentAction();
        if (ca == null || !"CATACLYSME".equals(ca.getMode()) || !playerId.equals(ca.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucun Cataclysme en cours pour ce joueur.");
        }

        if (secondChoice == null || thirdChoice == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "choix météo invalide");
        }
        if (secondChoice == thirdChoice) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "les deux statuts météo doivent être différents.");
        }

        // évite d'empiler plusieurs Cataclysmes
        if (g.getSecondaryWeatherStatus() != null || g.getThirdWeatherStatus() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cataclysme impossible : des statuts météo supplémentaires sont déjà actifs.");
        }

        WeatherStatus base = g.getWeatherStatus();

        // WIND déjà présent AVANT Cataclysme ? (base uniquement, car secondary/third
        // sont null ici)
        boolean windAlreadyApplied = (base == WeatherStatus.WIND);

        // extras = choix qui ne doublonnent pas la base
        var extras = new ArrayList<WeatherStatus>(2);
        if (base == null || secondChoice != base)
            extras.add(secondChoice);
        if (base == null || thirdChoice != base)
            extras.add(thirdChoice);

        WeatherStatus extra2 = extras.size() >= 1 ? extras.get(0) : null;
        WeatherStatus extra3 = extras.size() >= 2 ? extras.get(1) : null;

        g.setSecondaryWeatherStatus(extra2);
        g.setThirdWeatherStatus(extra3);

        // textes extras (base inchangée)
        if (extra2 != null) {
            g.setSecondaryWeatherStatusNameFr(weatherNameFr(extra2));
            g.setSecondaryWeatherDescriptionFr(weatherDescFr(extra2));
        } else {
            g.setSecondaryWeatherStatusNameFr(null);
            g.setSecondaryWeatherDescriptionFr(null);
        }

        if (extra3 != null) {
            g.setThirdWeatherStatusNameFr(weatherNameFr(extra3));
            g.setThirdWeatherDescriptionFr(weatherDescFr(extra3));
        } else {
            g.setThirdWeatherStatusNameFr(null);
            g.setThirdWeatherDescriptionFr(null);
        }

        // résumé lisible
        String baseName = (base != null ? weatherNameFr(base) : "Météo");
        String summaryName = baseName
                + (extra2 != null ? " + " + weatherNameFr(extra2) : "")
                + (extra3 != null ? " + " + weatherNameFr(extra3) : "");

        String hist = nameOf(g, playerId)
                + " déclenche un Cataclysme: "
                + weatherNameFr(secondChoice) + " + " + weatherNameFr(thirdChoice)
                + ".";
        addHistory(g, hist);

        // mods météo (doit prendre en compte thirdWeatherStatus)
        rebuildWeatherMods(g);

        // effet WIND si WIND arrive via extras et n'était pas déjà là via la base
        if (!windAlreadyApplied && (extra2 == WeatherStatus.WIND || extra3 == WeatherStatus.WIND)) {
            applyWindRepairs(g);
        }

        ca.setResolvedAtMillis(System.currentTimeMillis());
        ca.setTargetId(secondChoice.name() + "," + thirdChoice.name());

        // Libère currentAction pour permettre de jouer une autre carte
        g.setCurrentAction(null);

        var msgs = new ArrayList<String>();
        msgs.add("Météo — " + summaryName);
        msgs.add("Le vampire déchire le ciel.");
        g.setMessages(msgs);

        save(g);

        Game gAfter = g;
        afterCommit(() -> {
            live.actionResolved(gAfter, "CATACLYSME", playerId, null);
            live.raidModsUpdated(gAfter);
            live.phaseChanged(gAfter);
        });
    }

    @Transactional
    public Game rollShadowClones(String gameId, String playerId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PHASE2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE2");
        }

        Player p = findPlayer(g, playerId);
        if (p == null || !"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"CLONES_OMBRE".equals(a.getMode()) || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune action Clones des ombres en cours pour ce joueur.");
        }

        if (a.getRoll() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dé déjà lancé.");
        }

        int roll = dice.roll(4); // D4
        a.setRoll(roll);

        a.getBreakdownLines().add(
                nameOf(g, playerId) + " invoque " + roll + " clone"
                        + (roll > 1 ? "s" : "") + " des ombres.");

        addHistory(g, "Clones des ombres — " + nameOf(g, playerId)
                + " contrôle " + roll + " clone" + (roll > 1 ? "s" : "") + ".");

        save(g);

        Game gAfter = g;
        afterCommit(() -> {
            // juste pour forcer un GET propre + affichage du résultat
            live.actionResolved(gAfter, "CLONES_OMBRE", playerId, null);
            live.phaseChanged(gAfter);
        });

        return g;
    }

    @Transactional
    public Game resolveShadowClones(String gameId, String playerId, java.util.List<String> locations,
            java.util.List<Boolean> biteEnabled) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PHASE2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE2");
        }

        Player p = findPlayer(g, playerId);
        if (p == null || !"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"CLONES_OMBRE".equals(a.getMode()) || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune action Clones des ombres en cours pour ce joueur.");
        }

        if (a.getResolvedAtMillis() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Les clones des ombres ont déjà été résolus pour ce raid.");
        }

        if (a.getRoll() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "le dé n'a pas encore été lancé.");
        }

        int expected = a.getRoll();
        if (locations == null || locations.size() != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tu dois choisir exactement " + expected + " lieux.");
        }

        // Validation biteEnabled
        if (biteEnabled != null && biteEnabled.size() != expected) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La liste des capacités doit correspondre au nombre de clones.");
        }

        // Calcul coût et paiement
        int cost = 0;
        if (biteEnabled != null) {
            for (Boolean b : biteEnabled) {
                if (Boolean.TRUE.equals(b)) {
                    cost += 10;
                }
            }
        }

        if (cost > 0) {
            if (p.getSouls() < cost) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Pas assez d'âmes déchues pour les dons de capacités");
            }
            p.setSouls(p.getSouls() - cost);
            addHistory(g, nameOf(g, playerId) + " paie " + cost + " âmes pour activer la morsure sur ses clones.");
        }

        for (String loc : locations) {
            if (g.getGarlicBlockedLocations() != null
                    && g.getGarlicBlockedLocations().contains(loc)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Les clones ne peuvent pas attaquer un lieu protégé par une fumigation d'ail.");
            }
        }

        // CUMULER avec les clones déjà présents sur ce raid
        java.util.List<String> all = g.getClonesLocations();
        if (all == null) {
            all = new java.util.ArrayList<>();
        }
        all.addAll(locations); // on ajoute les nouveaux clones
        g.setClonesLocations(all);

        // CUMULER les flags de capacité morsure
        java.util.List<Boolean> allCaps = g.getClonesBiteCapabilities();
        if (allCaps == null) {
            allCaps = new java.util.ArrayList<>();
        }
        if (biteEnabled != null) {
            allCaps.addAll(biteEnabled);
        } else {
            // par défaut false
            for (int i = 0; i < locations.size(); i++)
                allCaps.add(false);
        }
        g.setClonesBiteCapabilities(allCaps);

        a.setResolvedAtMillis(System.currentTimeMillis());
        a.setTargetId(String.join(",", locations));

        String niceList = locations.stream()
                .map(this::labelLieuFr)
                .reduce((aa, bb) -> aa + ", " + bb)
                .orElse("");

        String hist = "Clones des ombres — " + nameOf(g, playerId)
                + " envoie ses clones attaquer : " + niceList + ".";
        addHistory(g, hist);

        g.setCurrentAction(null);

        refreshPrephaseRevealMessages(g);

        save(g);

        Game gAfter = g;
        afterCommit(() -> {
            // Notifie le front que l’action est terminée
            live.actionResolved(gAfter, "CLONES_OMBRE", playerId, null);
            // Et heartbeat classique
            live.phaseChanged(gAfter);
        });

        return g;
    }

    @Transactional
    public Game resolveVoileDeBrume(String gameId, String userId, String location) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PREPHASE3");
        }

        Player p = findPlayer(g, userId);
        if (p == null || !"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"VOILE_DE_BRUME".equals(a.getMode()) || !userId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune action Voile de brume en cours pour ce joueur.");
        }

        if (a.getResolvedAtMillis() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Le voile de brume a déjà été résolu.");
        }

        if (location == null || location.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lieu invalide");
        }

        a.setLocation(location);
        a.setResolvedAtMillis(System.currentTimeMillis());

        // Stocker le lieu affecté
        g.setFogAffectedLocation(location);

        // Appliquer -1 DEF via stat mod engine pour tous les chasseurs sur ce lieu
        var huntersOnLoc = g.getPlayers().stream()
                .filter(pl -> "HUNTER".equals(pl.getRole()))
                .filter(pl -> location.equals(locationOf(g, pl.getId())))
                .toList();

        for (var hunter : huntersOnLoc) {
            addRaidMod(g, hunter.getId(), "DEFENSE", -1, "ACTION:VOILE_DE_BRUME");
        }

        String locLabel = labelLieuFr(location);
        String line = "Voile de brume — " + nameOf(g, userId)
                + " enveloppe " + locLabel + " d'un brouillard mystique (-1 DEF, récolte /2).";
        addHistory(g, line);
        a.getBreakdownLines().add(line);

        save(g);

        Game gAfter = g;
        afterCommit(() -> {
            live.actionResolved(gAfter, "VOILE_DE_BRUME", userId, null);
            live.phaseChanged(gAfter);
        });

        return g;
    }

    /**
     * Annule toutes les préparations d'actions PREPHASE3 des chasseurs
     * pour le raid courant :
     */
    private java.util.List<String> cancelHunterPrephase3ActionsOnVampLoc(Game g, String vampLoc) {

        int cancelledCampfires = 0;
        int cancelledNets = 0;
        int cancelledPits = 0;
        int cancelledAmbush = 0;
        int cancelledLonely = 0;
        int cancelledIncendiaires = 0;
        int cancelledProvocations = 0;
        int cancelledBlessedStakes = 0;
        int cancelledSacredRosaries = 0;

        // ---------- Feux de camp ----------
        if (g.getCampfireLocations() != null) {
            for (String loc : g.getCampfireLocations()) {
                if (java.util.Objects.equals(loc, vampLoc)) {
                    cancelledCampfires++;
                }
            }
            // On supprime uniquement ceux sur ce lieu
            g.getCampfireLocations().removeIf(loc -> java.util.Objects.equals(loc, vampLoc));
        }

        // ---------- Filets ----------
        if (g.getNetHunters() != null && !g.getNetHunters().isEmpty()) {
            var it = g.getNetHunters().iterator();
            while (it.hasNext()) {
                String hunterId = it.next();
                String hLoc = locationOf(g, hunterId);
                if (java.util.Objects.equals(hLoc, vampLoc)) {
                    cancelledNets++;
                    it.remove();
                }
            }
        }

        // ---------- Fosses + maps associées ----------
        if (g.getPitHunters() != null && !g.getPitHunters().isEmpty()) {
            var it = g.getPitHunters().iterator();
            while (it.hasNext()) {
                String hunterId = it.next();
                String hLoc = locationOf(g, hunterId);
                if (java.util.Objects.equals(hLoc, vampLoc)) {
                    cancelledPits++;
                    it.remove();
                    if (g.getPitTargetsByHunter() != null) {
                        g.getPitTargetsByHunter().remove(hunterId);
                    }
                    if (g.getPitIndexByHunter() != null) {
                        g.getPitIndexByHunter().remove(hunterId);
                    }
                }
            }
        }

        // ---------- Provocation ----------
        // Map<enemyId, hunterIdProvocateur>
        if (g.getProvokedTargetByEnemy() != null && !g.getProvokedTargetByEnemy().isEmpty()) {
            var it = g.getProvokedTargetByEnemy().entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                String hunterId = e.getValue();
                String hLoc = locationOf(g, hunterId);
                if (java.util.Objects.equals(hLoc, vampLoc)) {
                    cancelledProvocations++;
                    it.remove();
                }
            }
        }

        // ---------- Embuscade ----------
        // Map<enemyId, List<hunterId>> : on enlève les chasseurs embusqués sur ce lieu
        if (g.getAmbushHuntersByEnemy() != null && !g.getAmbushHuntersByEnemy().isEmpty()) {
            var it = g.getAmbushHuntersByEnemy().entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                java.util.List<String> hunters = e.getValue();
                hunters.removeIf(hId -> java.util.Objects.equals(locationOf(g, hId), vampLoc));
                if (hunters.isEmpty()) {
                    cancelledAmbush++;
                    it.remove();
                }
            }
        }

        // ---------- Incendiaire ----------
        // Map<hunterId, locCible> : on annule seulement les chasseurs sur ce lieu
        if (g.getIncendiaireLocationByHunter() != null
                && !g.getIncendiaireLocationByHunter().isEmpty()) {

            var it = g.getIncendiaireLocationByHunter().entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                String hunterId = e.getKey();
                String hLoc = locationOf(g, hunterId);
                if (java.util.Objects.equals(hLoc, vampLoc)) {
                    cancelledIncendiaires++;
                    it.remove();
                }
            }
        }

        // ---------- Pieu béni / Chapelet sacré / Solitaire (flags + mods) ----------
        if (g.getPlayers() != null && vampLoc != null) {
            for (Player player : g.getPlayers()) {
                if (!"HUNTER".equals(player.getRole()))
                    continue;

                String hunterLoc = locationOf(g, player.getId());
                if (!vampLoc.equals(hunterLoc))
                    continue;

                // --- Pieu béni ---
                if (player.isBlessedStake()) {
                    cancelledBlessedStakes++;
                    player.setBlessedStake(false);

                    if (g.getRaidMods() != null) {
                        var mods = g.getRaidMods().get(player.getId());
                        if (mods != null) {
                            mods.removeIf(m -> {
                                String s = m.getSource();
                                return s != null && s.startsWith("ACTION:BLESSED_STAKE");
                            });
                        }
                    }
                }

                // --- Chapelet sacré ---
                if (player.isSacredRosary()) {
                    cancelledSacredRosaries++;
                    player.setSacredRosary(false);

                    if (g.getRaidMods() != null) {
                        var mods = g.getRaidMods().get(player.getId());
                        if (mods != null) {
                            mods.removeIf(m -> {
                                String s = m.getSource();
                                return s != null && s.startsWith("ACTION:SACRED_ROSARY");
                            });
                        }
                    }
                }

                // ---------- Lonely ----------
                if (g.getRaidMods() != null) {
                    var mods = g.getRaidMods().get(player.getId());
                    if (mods != null) {
                        boolean removed = mods.removeIf(m -> {
                            String s = m.getSource();
                            return s != null && s.startsWith("ACTION:LONELY");
                        });
                        if (removed)
                            cancelledLonely++;
                    }
                }
            }
        }

        // ---------- currentAction de chasseur sur ce lieu ----------
        Game.Action ca = g.getCurrentAction();
        if (ca != null && ("NET".equals(ca.getMode())
                || "PIT".equals(ca.getMode())
                || "INCENDIAIRE".equals(ca.getMode())
                || "PROVOCATION".equals(ca.getMode())
                || "AMBUSH".equals(ca.getMode())
                || "LONELY".equals(ca.getMode())
                || "EAU_BENITE".equals(ca.getMode())
                || "BLESSED_STAKE".equals(ca.getMode()))) {

            boolean sameLoc = java.util.Objects.equals(ca.getLocation(), vampLoc);
            boolean ownerOnLoc = ca.getOwnerId() != null
                    && java.util.Objects.equals(locationOf(g, ca.getOwnerId()), vampLoc);

            if (sameLoc || ownerOnLoc) {
                g.setCurrentAction(null);
            }
        }

        // ---------- parts (pour le message) ----------
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (cancelledCampfires > 0)
            parts.add(cancelledCampfires + " Feu de camp");
        if (cancelledNets > 0)
            parts.add(cancelledNets + " Filet");
        if (cancelledPits > 0)
            parts.add(cancelledPits + " Fosse");
        if (cancelledAmbush > 0)
            parts.add(cancelledAmbush + " Embuscade");
        if (cancelledLonely > 0)
            parts.add(cancelledLonely + " Solitaire");
        if (cancelledIncendiaires > 0)
            parts.add(cancelledIncendiaires + " Incendiaire");
        if (cancelledProvocations > 0)
            parts.add(cancelledProvocations + " Provocation");
        if (cancelledBlessedStakes > 0)
            parts.add(cancelledBlessedStakes + " Épieu béni");
        if (cancelledSacredRosaries > 0)
            parts.add(cancelledSacredRosaries + " Chapelet sacré");

        return parts;
    }

    @Transactional
    public Game resolveImageMiroirSetup(String gameId, String playerId, String loc) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PHASE2 && g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE2 or PREPHASE3");
        }

        Player p = findPlayer(g, playerId);
        if (p == null || !"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"IMAGE_MIROIR_SETUP".equals(a.getMode()) || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune action Image miroir en cours pour ce joueur.");
        }

        if (loc == null || loc.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "loc required");
        }

        if (p.getHand() == null || !p.getHand().contains(loc)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lieu non présent dans la main");
        }

        // Fumigation : on ne peut pas projeter l'image miroir sur un lieu fumigé
        if (g.getGarlicBlockedLocations() != null
                && g.getGarlicBlockedLocations().contains(loc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ce lieu est protégé par une fumigation d'ail.");
        }

        // Cumul des lieux alternatifs
        g.setMirrorOwnerId(playerId);
        java.util.List<String> alts = g.getMirrorAltLocations();
        if (alts == null) {
            alts = new java.util.ArrayList<>();
        }
        if (!alts.contains(loc)) {
            alts.add(loc);
        }
        g.setMirrorAltLocations(alts);

        // Tant qu’aucun choix final n’a été fait
        g.setMirrorChosenLocation(null);

        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        // on prépare la résolution finale pour la fin de préphase
        g.setPendingVampireEscape("IMAGE_MIROIR_RESOLVE");

        Game gAfter = g;
        afterCommit(() -> {
            live.actionResolved(gAfter, "IMAGE_MIROIR_SETUP", playerId, loc);
            live.phaseChanged(gAfter);
            // On ne force PAS l'avance de phase ici, c'est le timer/ready qui le fera
        });

        // Relancer la logique de timer pour la Préphase maintenant que le setup est
        // fait
        setupUnstableAndPrephaseTimeout(g);

        save(g);

        return g;
    }

    @Transactional
    public Game resolveImageMiroirChoice(String gameId, String playerId, String loc) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Image miroir se résout en PREPHASE3.");
        }

        Player p = findPlayer(g, playerId);
        if (p == null || !"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"IMAGE_MIROIR_RESOLVE".equals(a.getMode()) || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "aucune résolution Image miroir en cours.");
        }

        String ownerId = g.getMirrorOwnerId();
        java.util.List<String> alts = g.getMirrorAltLocations();

        if (ownerId == null || alts == null || alts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "aucune Image miroir active.");
        }

        // Lieu principal actuel sur le centre
        String primary = g.getCenter().stream()
                .filter(cb -> cb.getPlayerId().equals(ownerId))
                .map(CenterBoard::getCard)
                .findFirst()
                .orElse(null);

        boolean isPrimary = primary != null && primary.equals(loc);
        boolean isAlt = alts.contains(loc);

        if (!isPrimary && !isAlt) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "lieu choisi invalide pour Image miroir.");
        }

        // Fumigation : impossible de se matérialiser sur un lieu fumigé
        if (g.getGarlicBlockedLocations() != null
                && g.getGarlicBlockedLocations().contains(loc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ce lieu est protégé par une fumigation d'ail.");
        }

        g.setMirrorChosenLocation(loc);

        // Si le vampire change de lieu, on met à jour la carte du centre + on swappe
        // les cartes lieux
        if (primary != null && !loc.equals(primary)) {
            for (CenterBoard cb : g.getCenter()) {
                if (cb.getPlayerId().equals(ownerId)) {

                    String oldCard = cb.getCard(); // lieu joué au début du raid
                    String newLoc = loc; // primary ou alt choisi via Image miroir

                    if (!java.util.Objects.equals(oldCard, newLoc)) {
                        cb.setCard(newLoc);

                        var hand = p.getHand();
                        if (hand == null) {
                            hand = new java.util.ArrayList<>();
                            p.setHand(hand);
                        }

                        // newLoc vient de la main (vérifié côté setup), on la remet à la place de
                        // l'ancien
                        hand.remove(newLoc);
                        hand.add(oldCard);
                    }

                    break;
                }
            }
        }

        // Optionnel : nettoyer les alts une fois le choix fait
        if (g.getMirrorAltLocations() != null) {
            g.getMirrorAltLocations().clear();
        }

        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        String hist = "Image miroir — " + nameOf(g, playerId)
                + " choisit de se manifester sur " + labelLieuFr(loc) + ".";
        addHistory(g, hist);

        refreshPrephaseRevealMessages(g);
        // NE PAS relancer setupUnstableAndPrephaseTimeout ici

        maybeStartLocationEffects(g, gameId);

        afterCommit(() -> {
            try {
                // Notifier le front que l'action est résolue et la phase potentiellement
                // changée
                Game fresh = findOr404(gameId);
                live.actionResolved(fresh, "IMAGE_MIROIR_RESOLVE", playerId, loc);
                live.phaseChanged(fresh);
            } catch (Exception e) {
                log.error("Error in ImageMiroir afterCommit", e);
            }
        });

        return g;
    }

    @Transactional
    public Game resolveDarkMark(String gameId, String playerId, String targetId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Marque ténébreuse se résout en PREPHASE3.");
        }

        Player vamp = findPlayer(g, playerId);
        if (vamp == null || !"VAMPIRE".equals(vamp.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null
                || !"MARQUE_TENEBREUSE".equals(a.getMode())
                || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Marque ténébreuse en cours pour ce joueur.");
        }

        if (targetId == null || targetId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetId required");
        }

        Player target = findPlayer(g, targetId);
        if (target == null || !"HUNTER".equals(target.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "la cible doit être un chasseur.");
        }

        if (target.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "la cible doit être vivante.");
        }

        if (g.getDarkMarkedHunters() == null) {
            g.setDarkMarkedHunters(new java.util.HashSet<>());
        }

        // Marque permanente
        g.getDarkMarkedHunters().add(target.getId());

        // Puce DSP pour ce raid
        addRaidMod(g, target.getId(), "MARKED", 0, "CORRUPTION:MARK:DSP");

        String hist = "Marque ténébreuse — "
                + nameOf(g, vamp.getId())
                + " marque " + nameOf(g, target.getId())
                + ": il augmentera sa corruption de 1 à chaque raid où il croise le vampire, "
                + "tant qu'il n'est pas purifié.";
        addHistory(g, hist);

        a.setTargetId(target.getId());
        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        // On relance la mécanique de préphase (30s, etc.)
        setupUnstableAndPrephaseTimeout(g);

        save(g);

        Game gAfter = g;
        afterCommit(() -> {
            live.actionResolved(gAfter, "MARQUE_TENEBREUSE", playerId, target.getId());
            live.phaseChanged(gAfter);
        });

        return g;
    }

    private boolean isDarkMarked(Game g, String playerId) {
        return g.getDarkMarkedHunters() != null
                && g.getDarkMarkedHunters().contains(playerId);
    }

    private void applyDarkMarkCorruptionOncePerRaid(Game g, Player hunter) {
        if (g.getDarkMarkCorruptedThisRaid() == null) {
            g.setDarkMarkCorruptedThisRaid(new java.util.HashSet<>());
        }
        java.util.Set<String> done = g.getDarkMarkCorruptedThisRaid();
        if (done.contains(hunter.getId()))
            return; // déjà pris 1 corruption ce raid

        int before = hunter.getCorruption();
        if (before >= 3)
            return; // pas au-delà du max

        hunter.setCorruption(before + 1);

        done.add(hunter.getId());

        if (hunter.getCorruption() == 3) {
            discardAllActionsOf(g, hunter);
            hunter.setRole("SERVANT");
            convertHunterGearToServant(g, hunter);
            cancelPendingCombatsForPlayer(g, hunter);

            // Si une morsure est en cours pour ce joueur (ex: combat déclenché), on marque
            // la transformation
            if (g.getCurrentBite() != null && hunter.getId().equals(g.getCurrentBite().getTargetId())) {
                g.getCurrentBite().setBecameServant(true);
            }
        }

        String line = "Marque ténébreuse — "
                + nameOf(g, hunter.getId())
                + " subit la corruption de la marque qui augmente de 1 -> niveau " + (before + 1) + ".";
        addHistory(g, line);
    }

    /**
     * Annule tous les combats en attente impliquant un joueur qui vient de devenir
     * SERVANT. /**
     * Appelé après une transformation Hunter -> Servant pour nettoyer la queue de
     * combats.
     */
    private void cancelPendingCombatsForPlayer(Game g, Player player) {
        if (g.getCombatsQueue() == null || g.getCombatsQueue().isEmpty()) {
            return;
        }

        String playerId = player.getId();
        List<RoundFight> queue = g.getCombatsQueue();

        queue.removeIf(fight -> playerId.equals(fight.getAttackerId()) ||
                playerId.equals(fight.getDefenderId()));
    }

    private void cleanseDarkMark(Game g, Player hunter) {
        // 1) Supprimer le flag permanent
        if (g.getDarkMarkedHunters() != null) {
            g.getDarkMarkedHunters().remove(hunter.getId());
        }
        if (g.getDarkMarkCorruptedThisRaid() != null) {
            g.getDarkMarkCorruptedThisRaid().remove(hunter.getId());
        }

        // 2) Supprimer la puce DISPLAY
        if (g.getRaidMods() != null) {
            var list = g.getRaidMods().get(hunter.getId());
            if (list != null) {
                list.removeIf(m -> {
                    String src = m.getSource();
                    return src != null
                            && src.startsWith("CORRUPTION:MARK")
                            && src.endsWith(":DSP");
                });
            }
        }
    }

    @Transactional
    public Game resolveHolyWater(String gameId, String playerId, String mode) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }

        Player p = findPlayer(g, playerId);
        if (p == null || !"HUNTER".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseur uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"EAU_BENITE".equals(a.getMode()) || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Eau bénite en cours pour ce joueur.");
        }

        if (p.getActions() == null || !p.getActions().contains(Action.EAU_BENITE.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune carte Eau bénite dans votre inventaire.");
        }

        String choice = (mode == null ? "" : mode.trim().toUpperCase());
        if (choice.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode required");
        }

        String hist;

        switch (choice) {
            case "REDUCE" -> {
                int before = p.getCorruption();
                if (before > 0) {
                    p.setCorruption(before - 1);
                    hist = "Eau bénite — " + nameOf(g, p.getId())
                            + " purifie une partie de sa corruption (niveau "
                            + p.getCorruption() + ").";
                } else {
                    hist = "Eau bénite — " + nameOf(g, p.getId())
                            + " est déjà indemne de corruption, la purification est sans effet.";
                }
                addHistory(g, hist);
                // on met à jour les mods de corruption pour le raid en cours
                rebuildCorruptionMods(g);
            }
            case "CLEANSE" -> {
                if (!isDarkMarked(g, p.getId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "aucune Marque ténébreuse à purifier.");
                }
                cleanseDarkMark(g, p);
                hist = "Eau bénite — " + nameOf(g, p.getId())
                        + " dissipe la Marque ténébreuse.";
                addHistory(g, hist);
            }
            case "ATTACK" -> {
                // +4 dégâts sacrés lors de la prochaine attaque contre le vampire
                RaidEffects fx = (g.getRaidEffects() != null)
                        ? g.getRaidEffects().get(p.getId())
                        : null;
                if (fx == null) {
                    fx = new RaidEffects();
                    if (g.getRaidEffects() == null) {
                        g.setRaidEffects(new java.util.HashMap<>());
                    }
                    g.getRaidEffects().put(p.getId(), fx);
                }
                fx.setHolyWaterAttack(true);

                hist = "Eau bénite — " + nameOf(g, p.getId())
                        + " consacre ses armes pour ce raid: ses attaques brûleront le vampire.";
                addHistory(g, hist);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode invalide");
        }

        // La carte est consommée quel que soit le choix
        p.getActions().remove(Action.EAU_BENITE.name());

        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        setupUnstableAndPrephaseTimeout(g);

        save(g);

        Game gAfter = g;
        final String fChoice = choice;

        afterCommit(() -> {
            live.actionResolved(gAfter, "EAU_BENITE", playerId, fChoice);
            live.phaseChanged(gAfter);
        });

        return g;
    }

    /**
     * Est-ce que ce joueur a vraiment une Eau bénite jouable MAINTENANT ?
     * (équivalent back de canPlayHolyWaterkHere côté front)
     */
    private boolean hasUsableHolyWaterForPlayer(@NonNull Game g, @NonNull Player p) {
        // 1) Il lui faut la carte
        List<String> acts = p.getActions();
        if (acts == null || !acts.contains(Action.EAU_BENITE.name())) {
            return false;
        }

        // 2) Rôle + phase
        if (!"HUNTER".equals(p.getRole()))
            return false;
        if (g.getPhase() != Phase.PREPHASE3)
            return false;

        // 3) Lieu du chasseur
        String loc = locationOf(g, p.getId());
        if (loc == null)
            return false;

        // 4) Présence écrasante peut bloquer les actions des chasseurs sur le même lieu
        // que le vampire
        if (g.isHunterActionsBlockedThisRaid()) {
            Player vamp = g.getPlayers().stream()
                    .filter(pl -> "VAMPIRE".equals(pl.getRole()))
                    .findFirst()
                    .orElse(null);
            if (vamp != null) {
                String vampLoc = locationOf(g, vamp.getId());
                if (vampLoc != null && vampLoc.equals(loc)) {
                    // Bloqué sur ce lieu
                    return false;
                }
            }
        }

        // 5) Y a-t-il au moins UNE cible valide sur ce lieu ?
        // (ici : un chasseur vivant avec corruption > 0 ; tu peux étendre à "marqué" si
        // tu as un helper)
        var groups = groupPlayersByLocation(g); // loc -> List<Player>
        var onLoc = groups.get(loc);
        if (onLoc == null || onLoc.isEmpty())
            return false;

        for (Player target : onLoc) {
            if (!"HUNTER".equals(target.getRole()))
                continue;
            if (target.getHp() <= 0)
                continue;

            Integer corr = target.getCorruption();
            if (corr != null && corr > 0) {
                return true;
            }

            // Si tu as un helper du genre hasDarkMarkThisRaid(g, targetId), tu peux ajouter
            // :
            // if (hasDarkMarkThisRaid(g, target.getId())) return true;
        }

        return false;
    }

    @Transactional
    public Game resolveOccultWeakening(String gameId, String playerId, String targetId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Affaiblissement occulte se résout en PREPHASE3.");
        }

        Player vamp = findPlayer(g, playerId);
        if (vamp == null || !"VAMPIRE".equals(vamp.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null
                || !"AFFAIBLISSEMENT_OCCULTE".equals(a.getMode())
                || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucun Affaiblissement occulte en cours pour ce joueur.");
        }

        if (targetId == null || targetId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetId required");
        }

        Player target = findPlayer(g, targetId);
        if (target == null || !"HUNTER".equals(target.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "la cible doit être un chasseur.");
        }

        if (target.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "la cible doit être vivante.");
        }

        // --- Appliquer le malus d'attaque -2 pour CE raid ---
        addRaidMod(g, target.getId(), "ATTACK", -2, "ACTION:WEAKENING:ENG");

        String hist = "Affaiblissement occulte — "
                + nameOf(g, vamp.getId())
                + " sape la force de " + nameOf(g, target.getId())
                + " : -2 à son jet d'attaque ce raid.";
        addHistory(g, hist);

        a.setTargetId(target.getId());
        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        // Réévalue la préphase (relance 30s ou avance vers PHASE3 si plus rien à faire)
        setupUnstableAndPrephaseTimeout(g);

        save(g);

        Game gAfter = g;
        afterCommit(() -> {
            live.actionResolved(gAfter, "AFFAIBLISSEMENT_OCCULTE", playerId, target.getId());
            live.phaseChanged(gAfter);
        });

        return g;
    }

    private boolean hasUsableSecretPassageOption(Game g) {
        for (Player p : g.getPlayers()) {
            if (!"VAMPIRE".equals(p.getRole()))
                continue;

            List<String> actions = p.getActions();
            if (actions == null || !actions.contains(Action.PASSAGE_SECRET.name()))
                continue;

            String loc = locationOf(g, p.getId());
            // Passage secret n’est jouable que si le vampire est au Manoir
            if ("manor".equals(loc)) {
                return true;
            }
        }
        return false;
    }

    @Transactional
    public Game resolveSecretPassage(String gameId, String playerId, String destination) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }
        if (g.getPhase() != Phase.PREPHASE3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Passage secret se résout en PREPHASE3.");
        }

        Player vamp = findPlayer(g, playerId);
        if (vamp == null || !"VAMPIRE".equals(vamp.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire uniquement");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null
                || !"PASSAGE_SECRET".equals(a.getMode())
                || !playerId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucun Passage secret en cours pour ce joueur.");
        }

        String currentLoc = locationOf(g, playerId);
        if (!"manor".equals(currentLoc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Passage secret n'est utilisable que depuis le Manoir.");
        }

        if (destination == null || destination.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "destination required");
        }
        final String targetLoc = destination.trim().toLowerCase();

        // Construire la liste des lieux autorisés
        java.util.Set<String> allowed = new java.util.HashSet<>();
        // Lieux de base
        allowed.add("forest");
        allowed.add("quarry");
        allowed.add("lake");
        allowed.add("manor");

        // Infras construites -> lieux associés
        if (g.getBuiltInfras() != null) {
            for (Infra infra : g.getBuiltInfras()) {
                switch (infra) {
                    case SAWMILL -> allowed.add("sawmill");
                    case MINE -> allowed.add("mine");
                    case LIBRARY -> allowed.add("library");
                    case LABORATORY -> allowed.add("laboratory");
                    case BALLROOM -> allowed.add("ballroom");
                    case ALTAR -> allowed.add("altar");
                    case FORGE -> allowed.add("forge");
                }
            }
        }

        if (!allowed.contains(targetLoc)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "destination invalide pour Passage secret.");
        }
        if (targetLoc.equals(currentLoc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Passage secret doit mener à un autre lieu que le Manoir.");
        }

        // Fumigation : impossible d'utiliser Passage secret vers un lieu fumigé
        if (g.getGarlicBlockedLocations() != null
                && g.getGarlicBlockedLocations().contains(targetLoc)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ce lieu est protégé par une fumigation d'ail.");
        }

        // Mettre à jour le centre : le vampire change de lieu
        CenterBoard cb = g.getCenter().stream()
                .filter(c -> playerId.equals(c.getPlayerId()))
                .findFirst()
                .orElse(null);

        if (cb == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Impossible de trouver la carte de lieu du vampire.");
        }

        // même logique que pour assignTarget / assignHarvest : on swappe les cartes
        // lieu
        String oldCard = cb.getCard();
        String newLoc = targetLoc;

        if (!java.util.Objects.equals(oldCard, newLoc)) {
            cb.setCard(newLoc); // le pion passe sur newLoc
            vamp.getHand().remove(newLoc); // on "dépense" la carte de destination
            vamp.getHand().add(oldCard); // on récupère l’ancienne carte de lieu dans la main
        }

        // Historique
        String msg = "Passage secret — "
                + nameOf(g, playerId)
                + " quitte le Manoir et rejoint " + labelLieuFr(targetLoc) + ".";
        addHistory(g, msg);

        // On clôt l'action
        a.setLocation(targetLoc);
        a.setTargetId(null);
        a.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(null);

        refreshPrephaseRevealMessages(g);

        // On relance la mécanique de préphase (combats à venir potentiellement
        // modifiés)
        // NE PAS relancer setupUnstableAndPrephaseTimeout ici (on veut finir la phase)

        maybeStartLocationEffects(g, gameId);

        afterCommit(() -> {
            try {
                // Notifier le front
                Game fresh = findOr404(gameId);
                live.actionResolved(fresh, "PASSAGE_SECRET", playerId, targetLoc);
                live.phaseChanged(fresh);
            } catch (Exception e) {
                log.error("Error in PassageSecret afterCommit", e);
            }
        });

        return g;
    }

    // Corruption
    /**
     * Reconstruit entièrement les mods de corruption dans raidMods à chaque début
     * de raid (PHASE0),
     * en purgeant d’abord les anciennes entrées "CORRUPTION:*".
     * Règles:
     * - L1: applique -1 ATK / -1 DEF (effet moteur) + 1 chip "affichage"
     * - L2: aucune stat modifiée, 1 chip "instable"
     * - L3: rôle=SERVANT géré lors de la morsure, aucune stat modifiée, 1 chip
     * "serviteur"
     */
    private void rebuildCorruptionMods(@NonNull Game g) {
        if (g.getRaidMods() == null)
            g.setRaidMods(new HashMap<>());

        // Purge des anciennes entrées de corruption
        for (var list : g.getRaidMods().values()) {
            if (list != null) {
                list.removeIf(m -> {
                    String s = m.getSource();
                    return s != null && s.startsWith("CORRUPTION:");
                });
            }
        }

        // Réinjection selon le niveau
        for (var p : g.getPlayers()) {
            int lvl = p.getCorruption();

            if (lvl == 1) {
                // Effet moteur L1 : −1 ATK / −1 DEF
                addRaidMod(g, p.getId(), "ATTACK", -1, "CORRUPTION:L1:ENG");
                addRaidMod(g, p.getId(), "DEFENSE", -1, "CORRUPTION:L1:ENG");
                // Une seule puce d’affichage
                addRaidMod(g, p.getId(), "MULTIPLE", 0, "CORRUPTION:L1:DSP");
            } else if (lvl == 2) {
                // L2 : pas de debuff chiffré — seulement la puce “instable”
                addRaidMod(g, p.getId(), "INSTABLE", 0, "CORRUPTION:L2:DSP");
            } else if (lvl == 3) {
                // L3 : pas de debuff chiffré — seulement la puce “serviteur”
                addRaidMod(g, p.getId(), "SERVITEUR", 0, "CORRUPTION:L3:DSP");
            }
        }
        // --- Marque ténébreuse : puce DSP permanente tant que le chasseur est marqué
        // ---
        if (g.getDarkMarkedHunters() != null && !g.getDarkMarkedHunters().isEmpty()) {
            for (String pid : g.getDarkMarkedHunters()) {
                // sécurité : s'assurer que le joueur existe
                Player h = findPlayer(g, pid);
                if (h == null)
                    continue;

                addRaidMod(g, pid, "MARKED", 0, "CORRUPTION:MARK:DSP");
            }
        }
    }

    /**
     * Redirige un chasseur instable (corruption=2, jet 1–3) vers une cible choisie
     * par le vampire.
     * - Valide la phase (PREPHASE3) et les droits (vampire uniquement).
     * - Vérifie que la cible fait partie des éligibles calculés à la révélation.
     * - Remplace la carte posée au centre par celle de la cible ET rend l’ancienne
     * carte à la main de l’instable.
     * - Enregistre la décision pour planifier un duel instable -> cible lors de
     * PHASE3.
     */
    @Transactional
    public Game assignUnstableTarget(String gameId, String userId, String unstableId, String targetId) {
        Game g = findOr404(gameId);

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PREPHASE3");

        var vamp = getVamp(g).orElseThrow();
        if (!vamp.getId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can assign");

        boolean eligibleNow = g.getUnstableEligibleTargets().containsKey(unstableId)
                || g.getUnstableEligibleLocations().containsKey(unstableId);
        if (!eligibleNow)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no pending unstable choice");

        var elig = g.getUnstableEligibleTargets().get(unstableId);
        if (elig == null || !elig.contains(targetId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid target");

        // --- Mutations (aucun event ici) ---
        // 1) Enregistre la cible et invalide l’autre choix
        g.getUnstableTargetByPlayer().put(unstableId, targetId);
        g.getUnstableHarvestLocByPlayer().remove(unstableId);

        // 2) Redirige la carte de l’instable vers la localisation de la cible
        String targetLoc = g.getCenter().stream()
                .filter(cb -> cb.getPlayerId().equals(targetId))
                .map(CenterBoard::getCard).findFirst()
                .orElse(null);

        if (targetLoc != null) {
            g.getCenter().stream()
                    .filter(cb -> cb.getPlayerId().equals(unstableId))
                    .findFirst()
                    .ifPresent(cb -> {
                        String oldCard = cb.getCard();
                        String newLoc = targetLoc;
                        if (!java.util.Objects.equals(oldCard, newLoc)) {
                            cb.setCard(newLoc);
                            var unstable = g.getPlayers().stream()
                                    .filter(pp -> pp.getId().equals(unstableId)).findFirst().orElse(null);
                            if (unstable != null) {
                                unstable.getHand().add(oldCard);
                                unstable.getHand().remove(newLoc);
                            }
                        }
                    });

            String targetName = nameOf(g, targetId);
            String unstableName = nameOf(g, unstableId);
            String locLabel = labelLieuFr(targetLoc);

            String interruptionLine = "Récolte de " + targetName + " perturbée par " + unstableName
                    + " (récolte réduite).";
            String combatLine = "Combat — " + unstableName + " VS " + targetName + " à " + locLabel + ".";

            if (g.getMessages() == null)
                g.setMessages(new ArrayList<>());
            g.getMessages().add(combatLine);
            addHistory(g, interruptionLine);
            addHistory(g, combatLine);
        }

        // Marque ténébreuse : +1 corruption si chasseur marqué instable assigné sur le
        // lieu du vampire
        Player unstablePlayer = findPlayer(g, unstableId);
        if (unstablePlayer != null && isDarkMarked(g, unstableId)) {
            String vampLoc = locationOf(g, vamp.getId());
            if (vampLoc != null && vampLoc.equals(targetLoc)) {
                applyDarkMarkCorruptionOncePerRaid(g, unstablePlayer);
            }
        }

        // 3) Consomme toute l’éligibilité pour cet instable
        g.getUnstableEligibleTargets().remove(unstableId);
        g.getUnstableEligibleLocations().remove(unstableId);

        // 4) (RE)calcule APRÈS mutation
        boolean hasPendingAfter = !(g.getUnstableEligibleTargets().isEmpty()
                && g.getUnstableEligibleLocations().isEmpty());
        boolean upcomingAfter = computeHasUpcomingCombat(g);
        g.setHasUpcomingCombat(upcomingAfter);

        // --- Commit
        save(g);

        // --- Events APRÈS COMMIT
        afterCommit(() -> {
            live.unstableAssigned(g, unstableId, "TARGET", targetId);

            // S’il n’y a PLUS de choix instables ET PAS de combat → avance rapide (4s)
            if (!hasPendingAfter && !upcomingAfter) {
                scheduleAdvance(g.getId(), Phase.PREPHASE3, Phase.PHASE3, 4000);
            } else {
                // sinon, on laisse vivre la fenêtre (potions, autres instables…) / ou timer
                // long déjà en place
                live.phaseChanged(g); // petit heartbeat pour rafraîchir les onglets
            }
        });

        return g;
    }

    @Transactional
    public Game assignUnstableHarvest(String gameId, String userId, String unstableId, String loc) {
        Game g = findOr404(gameId);

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PREPHASE3");

        var vamp = getVamp(g).orElseThrow();
        if (!vamp.getId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can assign");

        boolean eligibleNow = g.getUnstableEligibleTargets().containsKey(unstableId)
                || g.getUnstableEligibleLocations().containsKey(unstableId);
        if (!eligibleNow)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no pending unstable choice");

        var eligLocs = g.getUnstableEligibleLocations().get(unstableId);
        if (eligLocs == null || !eligLocs.contains(loc))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid location");

        // --- Mutations (aucun event ici) ---
        // 1) Enregistre le lieu et invalide l’autre choix
        g.getUnstableHarvestLocByPlayer().put(unstableId, loc);
        g.getUnstableTargetByPlayer().remove(unstableId);

        // 2) Redirige la carte au centre (et rend l’ancienne)
        g.getCenter().stream()
                .filter(cb -> cb.getPlayerId().equals(unstableId))
                .findFirst()
                .ifPresent(cb -> {
                    String oldCard = cb.getCard();
                    String newLoc = loc;
                    if (!java.util.Objects.equals(oldCard, newLoc)) {
                        cb.setCard(newLoc);
                        var unstable = g.getPlayers().stream()
                                .filter(pp -> pp.getId().equals(unstableId)).findFirst().orElse(null);
                        if (unstable != null) {
                            unstable.getHand().add(oldCard);
                            unstable.getHand().remove(newLoc);
                        }
                    }
                });

        String line = nameOf(g, unstableId) + " récoltera à " + labelLieuFr(loc) + " pour " + nameOf(g, vamp.getId())
                + ".";
        if (g.getMessages() == null)
            g.setMessages(new ArrayList<>());
        g.getMessages().add(line);
        addHistory(g, line);

        // Marque ténébreuse : +1 corruption si chasseur marqué instable assigné sur le
        // lieu du vampire
        Player unstablePlayer = findPlayer(g, unstableId);
        if (unstablePlayer != null && isDarkMarked(g, unstableId)) {
            String vampLoc = locationOf(g, vamp.getId());
            if (vampLoc != null && vampLoc.equals(loc)) {
                applyDarkMarkCorruptionOncePerRaid(g, unstablePlayer);
            }
        }

        // 3) Consomme l’éligibilité
        g.getUnstableEligibleTargets().remove(unstableId);
        g.getUnstableEligibleLocations().remove(unstableId);

        // 4) (RE)calcule APRÈS mutation
        boolean hasPendingAfter = !(g.getUnstableEligibleTargets().isEmpty()
                && g.getUnstableEligibleLocations().isEmpty());
        boolean upcomingAfter = computeHasUpcomingCombat(g);
        g.setHasUpcomingCombat(upcomingAfter);

        // --- Commit
        save(g);

        // --- Events APRÈS COMMIT
        afterCommit(() -> {
            live.unstableAssigned(g, unstableId, "HARVEST", loc);

            if (!hasPendingAfter && !upcomingAfter) {
                // pas d’autres choix, pas de combat → passe en PHASE3 rapidement (récoltes)
                scheduleAdvance(g.getId(), Phase.PREPHASE3, Phase.PHASE3, 4000);
            } else {
                live.phaseChanged(g);
            }
        });

        return g;
    }

    @Transactional
    public Game assignUnstableNothing(String gameId, String userId, String unstableId) {
        Game g = findOr404(gameId);

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PREPHASE3");

        var vamp = getVamp(g).orElseThrow();
        if (!vamp.getId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can assign");

        // --- Debug précis : ce qu’on a au moment T
        var eligT = g.getUnstableEligibleTargets();
        var eligL = g.getUnstableEligibleLocations();
        boolean inTargets = eligT != null && eligT.containsKey(unstableId);
        boolean inLocations = eligL != null && eligL.containsKey(unstableId);

        boolean eligibleNow = inTargets || inLocations;

        // --- Idempotence : si déjà consommé/expiré, on répond OK et on force juste un
        // refresh
        if (!eligibleNow) {
            save(g);
            afterCommit(() -> live.phaseChanged(g));
            return g;
        }

        // --- Mutations : consomme l’option sans target/harvest
        g.getUnstableEligibleTargets().remove(unstableId);
        g.getUnstableEligibleLocations().remove(unstableId);
        g.getUnstableTargetByPlayer().remove(unstableId); // sécurité
        g.getUnstableHarvestLocByPlayer().remove(unstableId); // sécurité

        boolean hasPendingAfter = !(g.getUnstableEligibleTargets().isEmpty()
                && g.getUnstableEligibleLocations().isEmpty());
        boolean upcomingAfter = computeHasUpcomingCombat(g);
        g.setHasUpcomingCombat(upcomingAfter);

        addHistory(g, nameOf(g, unstableId) + " n’a reçu aucun ordre du vampire.");

        save(g);

        afterCommit(() -> {
            // AVANT: live.unstableAssigned(g, unstableId, "NOTHING", null);
            live.unstableAssigned(g, unstableId, "NOTHING", "");
            if (!hasPendingAfter && !upcomingAfter) {
                scheduleAdvance(g.getId(), Phase.PREPHASE3, Phase.PHASE3, 4000);
            } else {
                live.phaseChanged(g);
            }
        });

        return g;
    }

    /**
     * Tente / résout une morsure.
     *
     * Deux étapes possibles :
     * - Étape 1 (vampire) : jet de morsure (d20, réussite sur > 8 ; clone : d20 > 12).
     * * si échec → on clôt la morsure comme avant.
     * * si réussite :
     * - cible sans armure de plates argent → corruption appliquée immédiatement
     * (comme avant).
     * - cible avec armure de plates argent → on NE modifie pas la corruption,
     * la cible pourra lancer un D4 sur un second appel.
     *
     * - Étape 2 (cible, si armure de plates argent) : jet d’armure (D4).
     * * si 4 → la morsure est entièrement annulée (pas de corruption).
     * * sinon → la corruption est appliquée maintenant.
     */
    @Transactional
    public Game rollCorruption(String gameId, String userId) {
        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE3 || g.getCurrentBite() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no bite to resolve");
        }

        var b = g.getCurrentBite();

        // Cible actuelle de la morsure
        var target = g.getPlayers().stream()
                .filter(p -> p.getId().equals(b.getTargetId()))
                .findFirst()
                .orElse(null);

        boolean targetHasSilverPlate = (target != null
                && H_ARMOR_T3_PLATE_SILVER.equals(target.getArmor()));

        boolean isSacredRosaryUsed = false;

        String altarCode = Infra.ALTAR.locationCode();
        RoundFight fight = g.getCurrentCombat();

        // ======================
        // ÉTAPE 1 : d20 de morsure par le vampire
        // ======================
        if (b.getRoll() == null) {
            // Seul le vampire (attaquant) peut faire le premier jet
            if (!userId.equals(b.getAttackerId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can roll the bite");
            }

            // --- MORSURE DE CLONE ---
            if (fight != null && fight.isCloneAttack()) {
                // D20, seuil 12, +30 âmes + 1 corruption
                int roll = dice.roll(20);
                b.setRoll(roll);
                addHistory(g, nameOf(g, b.getAttackerId()) + " — jet de morsure (Clone) = " + roll + ".");

                if (roll > 12) {
                    Player att = findPlayer(g, b.getAttackerId());
                    if (att != null) {
                        att.setSouls(att.getSouls() + 30);
                        addHistory(g, "Morsure réussie ! (Clone) — " + nameOf(g, att.getId())
                                + " draine 30 âmes à " + nameOf(g, target != null ? target.getId() : "la cible") + ".");
                    }

                    if (target != null && target.getCorruption() < 3) {
                        target.setCorruption(target.getCorruption() + 1);
                        addHistory(g, nameOf(g, target.getId()) + " subit 1 corruption (Morsure de clone).");
                    }

                } else {
                    addHistory(g, "Morsure ratée (Clone).");
                }

                // On laisse le roll s'afficher, la résolution se fera au prochain appel
                b.setResolvedAtMillis(System.currentTimeMillis());
                save(g);

                final int rollF = roll;
                final String attF = b.getAttackerId();
                final String tgtF = b.getTargetId();
                final Integer newCF = (target != null) ? target.getCorruption() : null;

                afterCommit(() -> {
                    live.biteRolled(g, rollF, attF, tgtF, newCF, false, false);
                    live.phaseChanged(g);
                });
                return g;
            }

            // AUTEL — message "before" pour la corruption par morsure (premier jet
            // uniquement)
            if (fight != null
                    && altarCode != null
                    && altarCode.equals(fight.getLocation())
                    && isAltarBuilt(g)
                    && !isAltarCorrupted(g) // sanctuaire encore pur
                    && !g.isAltarBiteOccurredThisRaid()) // aucune morsure réussie sur ce lieu ce raid
            {
                addHistory(g, "Autel — "
                        + nameOf(g, b.getAttackerId())
                        + " prépare un rituel sanglant: "
                        + "si cette morsure réussit sur le sanctuaire, l'autel sera profané.");
            }

            int roll = dice.roll(20);
            b.setRoll(roll);
            addHistory(g, nameOf(g, b.getAttackerId()) + " — jet de morsure = " + roll + ".");

            boolean sendMods = false;
            boolean becameServant = false;

            if (target != null && roll > 8) {
                // Morsure réussie côté d20

                boolean targetHasSacredRosary = (target != null && target.isSacredRosary());

                if (targetHasSacredRosary) {
                    // Chapelet sacré : la morsure est entièrement annulée

                    // Consommer l'effet persistant
                    target.setSacredRosary(false);

                    // Retirer la puce DISPLAY ACTION:SACRED_ROSARY:DSP
                    if (g.getRaidMods() != null) {
                        var mods = g.getRaidMods().get(target.getId());
                        if (mods != null) {
                            mods.removeIf(m -> {
                                String s = m.getSource();
                                return s != null && s.startsWith("ACTION:SACRED_ROSARY");
                            });
                        }
                    }

                    isSacredRosaryUsed = true;

                    String line = "Chapelet sacré — "
                            + nameOf(g, target.getId())
                            + " invoque une protection divine: la morsure du vampire est annulée.";
                    addHistory(g, line);

                    // Pas de corruption, pas de transformation en serviteur, pas d'autel profané
                    // On marque simplement la morsure comme résolue
                    b.setResolvedAtMillis(System.currentTimeMillis());

                    // On a touché aux raidMods → forcer un refresh côté front
                    sendMods = true;

                } else if (targetHasSilverPlate) {
                    // Armure de plates en argent : comportement existant
                    addHistory(g, "Armure de plates en argent — "
                            + nameOf(g, target.getId())
                            + " peut tenter de repousser la morsure en lançant un d4.");
                    // Pas de corruption ici, pas de onAltarBite, pas de resolvedAtMillis
                    // (on attend le D4)
                } else {
                    // Comportement d'origine : on applique la corruption tout de suite
                    int before = target.getCorruption();
                    int after = Math.min(3, before + 1);
                    target.setCorruption(after);
                    addHistory(g, nameOf(g, target.getId())
                            + " se fait mordre... sa corruption passe de " + before + " à " + after + ".");
                    if (after == 3) {
                        discardAllActionsOf(g, target);
                        target.setRole("SERVANT");
                        convertHunterGearToServant(g, target);
                        target.setSouls(target.getSouls() + target.getGold());
                        target.setGold(0);
                        becameServant = true;
                        b.setBecameServant(true);
                        cancelPendingCombatsForPlayer(g, target);
                    }

                    var attacker = g.getPlayers().stream()
                            .filter(pp -> pp.getId().equals(b.getAttackerId()))
                            .findFirst()
                            .orElse(null);

                    if (fight != null
                            && altarCode != null
                            && altarCode.equals(fight.getLocation())) {
                        onAltarBite(g, fight, attacker, target);
                    }

                    rebuildCorruptionMods(g);
                    sendMods = true;

                    // Bonus : morsure réussie => +50 âmes au vampire (ou +30 si clone)
                    var vampOpt2 = getVamp(g);
                    if (vampOpt2.isPresent()) {
                        Player vamp2 = vampOpt2.get();
                        int reward = 50;
                        if (fight != null && fight.isCloneAttack()) {
                            reward = 30;
                        }

                        vamp2.setSouls(vamp2.getSouls() + reward);
                        addHistory(g, "Morsure — le sang versé nourrit le vampire : +" + reward + " âmes déchues.");
                    }

                    b.setResolvedAtMillis(System.currentTimeMillis());
                }

            } else {
                // Échec de la morsure (ou pas de cible pour une raison X)
                addHistory(g, nameOf(g, b.getAttackerId()) + " échoue sa tentative de morsure.");
                b.setResolvedAtMillis(System.currentTimeMillis());
            }

            save(g);

            final boolean sendModsF = sendMods;
            final int rollF = roll;
            final String attF = b.getAttackerId();
            final String tgtF = b.getTargetId();
            final Integer newCF = (target != null) ? target.getCorruption() : null;
            final boolean becameServantF = becameServant;
            final boolean isSacredRosaryUsedF = isSacredRosaryUsed;
            final String locF = b.getLocation();
            final Long resolvedAtF = b.getResolvedAtMillis();

            afterCommit(() -> {
                if (sendModsF) {
                    live.raidModsUpdated(g);
                }
                // Ici rollF = d20 (morsure)
                live.biteRolled(g, rollF, attF, tgtF, newCF, becameServantF, isSacredRosaryUsedF);

                // Si le bite est résolu immédiatement (e.g. transformation Servant, chapelet
                // sacré),
                // émettre BITE_RESOLVED pour que les spectateurs ferment aussi le modal
                if (resolvedAtF != null) {
                    live.biteResolved(g, attF, tgtF, locF);
                }
            });

            return g;
        }

        // ======================
        // ÉTAPE 2 : D4 par la cible (si armure de plates en argent)
        // ======================

        // On ne doit arriver ici que si :
        // - un d20 a déjà été lancé (b.getRoll() != null),
        // - la morsure n'est pas encore résolue (resolvedAtMillis == null),
        // - la cible possède l'armure de plates argent,
        // - l'appel vient de la cible.
        if (!targetHasSilverPlate
                || target == null
                || b.getResolvedAtMillis() != null
                || !userId.equals(b.getTargetId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no armor roll expected from you now");
        }

        if (b.getArmorRoll() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "armor roll already done");
        }

        int d4 = dice.roll(4);
        b.setArmorRoll(d4);
        addHistory(g, "Armure de plates en argent — "
                + nameOf(g, target.getId())
                + " jette un d4 contre la morsure (" + d4 + ").");

        boolean sendMods2 = false;
        boolean becameServant2 = false;

        if (d4 == 4) {
            // L'armure repousse complètement le vampire : pas de corruption
            addHistory(g, "L'armure de plates en argent repousse le vampire : "
                    + "la morsure est annulée.");
            // Pas de onAltarBite, pas de rebuildCorruptionMods
        } else {
            // La morsure "passe" malgré l'armure : on applique la corruption maintenant
            int before = target.getCorruption();
            int after = Math.min(3, before + 1);
            target.setCorruption(after);
            addHistory(g, nameOf(g, target.getId())
                    + " se fait finalement corrompre... sa corruption passe de "
                    + before + " à " + after + ".");
            if (after == 3) {
                discardAllActionsOf(g, target);
                target.setRole("SERVANT");
                convertHunterGearToServant(g, target);
                target.setSouls(target.getSouls() + target.getGold());
                target.setGold(0);
                becameServant2 = true;
                b.setBecameServant(true);
                cancelPendingCombatsForPlayer(g, target);
            }

            var attacker = g.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(b.getAttackerId()))
                    .findFirst()
                    .orElse(null);

            if (fight != null
                    && altarCode != null
                    && altarCode.equals(fight.getLocation())) {
                onAltarBite(g, fight, attacker, target);
            }

            rebuildCorruptionMods(g);
            sendMods2 = true;

            // Bonus : morsure réussie malgré armure => +50 âmes au vampire
            var vampOpt2 = getVamp(g);
            if (vampOpt2.isPresent()) {
                Player vamp2 = vampOpt2.get();
                vamp2.setSouls(vamp2.getSouls() + 50);
                addHistory(g, "Morsure — le sang versé nourrit le vampire : +50 âmes déchues.");
            }
        }

        b.setResolvedAtMillis(System.currentTimeMillis());
        save(g);

        final boolean sendModsF2 = sendMods2;
        final int rollF2 = d4; // ici on envoie le D4
        final String attF2 = b.getAttackerId();
        final String tgtF2 = b.getTargetId();
        final Integer newCF2 = (target != null) ? target.getCorruption() : null;
        final boolean becameServantF2 = becameServant2;
        final boolean isSacredRosaryUsedF = isSacredRosaryUsed;

        afterCommit(() -> {
            if (sendModsF2) {
                live.raidModsUpdated(g);
            }
            // Ici rollF2 = D4
            live.biteRolled(g, rollF2, attF2, tgtF2, newCF2, becameServantF2, isSacredRosaryUsedF);
        });

        return g;
    }

    /**
     * Défausse TOUTES les cartes d'action du joueur, puis vide sa main d'actions.
     * À appeler AVANT de changer son rôle si on veut savoir s'il était chasseur ou
     * vampire.
     */
    private void discardAllActionsOf(Game g, Player p) {
        List<String> inv = p.getActions();
        if (inv == null || inv.isEmpty())
            return;

        boolean wasHunter = "HUNTER".equals(p.getRole());

        // On travaille sur une copie pour éviter les soucis pendant le clear()
        var copy = new java.util.ArrayList<>(inv);

        for (String card : copy) {
            if (wasHunter) {
                discardHunterAction(g, card);
            }
            // on ne supprime pas ici élément par élément, on videra la liste à la fin
        }

        inv.clear(); // la main d'actions du joueur est vide
    }

    /**
     * Défausse TOUTES les cartes potions du joueur, puis vide sa main d'actions.
     * À appeler AVANT de changer son rôle si on veut savoir s'il était chasseur ou
     * vampire.
     */
    private void discardAllPotionsOf(Game g, Player p) {
        List<String> inv = p.getPotions();
        if (inv == null || inv.isEmpty())
            return;

        // On travaille sur une copie pour éviter les soucis pendant le clear()
        var copy = new java.util.ArrayList<>(inv);

        for (String card : copy) {
            discardPotion(g, card);
        }

        inv.clear(); // la main d'actions du joueur est vide
    }

    // Maintenance
    @Nullable
    private Player findPlayer(Game g, String id) {
        return g.findPlayer(id);
    }

    // Pioche dans un deck ordonné, avec reshuffle auto depuis la défausse
    private String drawFromDeck(List<String> deck, List<String> discard) {
        if (deck == null)
            return null;

        // 1) Si deck vide AVANT pioche, on recrée depuis la défausse
        if (deck.isEmpty() && discard != null && !discard.isEmpty()) {
            dice.shuffle(discard);
            deck.addAll(discard);
            discard.clear();
        }

        if (deck.isEmpty())
            return null;

        // 2) Pioche (top = fin)
        String card = deck.remove(deck.size() - 1);

        // 3) NOUVEAU : si le deck vient de tomber à 0, on reshuffle TOUT DE SUITE
        if (deck.isEmpty() && discard != null && !discard.isEmpty()) {
            dice.shuffle(discard);
            deck.addAll(discard);
            discard.clear();
        }

        return card;
    }

    private void putOnTop(List<String> deck, String cardId) {
        if (deck == null || cardId == null)
            return;
        deck.add(cardId); // top = fin
    }

    private void putOnBottom(List<String> deck, String cardId) {
        if (deck == null || cardId == null)
            return;
        deck.add(0, cardId); // bottom = début
    }

    private void discardCard(List<String> discard, String cardId) {
        if (discard == null || cardId == null)
            return;
        discard.add(cardId);
    }

    private String drawHunterAction(Game g) {
        return drawFromDeck(g.getHunterActionsDeck(), g.getHunterActionsDiscard());
    }

    private String drawVampAction(Game g) {
        return drawFromDeck(g.getVampActionsDeck(), g.getVampActionsDiscard());
    }

    // Potions communes
    private String drawPotion(Game g) {
        return drawFromDeck(g.getPotionDeck(), g.getPotionDiscard());
    }

    // Potions rares
    private String drawElixir(Game g) {
        return drawFromDeck(g.getElixirDeck(), g.getElixirDiscard());
    }

    private void discardHunterAction(Game g, String cardId) {
        discardCard(g.getHunterActionsDiscard(), cardId);
    }

    private void discardVampAction(Game g, String cardId) {
        discardCard(g.getVampActionsDiscard(), cardId);
    }

    private void discardPotion(Game g, String cardId) {
        discardCard(g.getPotionDiscard(), cardId);
    }

    private void discardElixir(Game g, String cardId) {
        discardCard(g.getElixirDiscard(), cardId);
    }

    @Transactional
    public Game buyPotion(String gameId, String userId) {
        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        if (!isAlive(p)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }

        int potionsLeft = deckAvailableSize(g.getPotionDeck(), g.getPotionDiscard());
        if (potionsLeft <= 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no potions left");

        if (p.getWater() < 4 || p.getHerbs() < 3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");

        // paiement
        p.setWater(p.getWater() - 4);
        p.setHerbs(p.getHerbs() - 3);

        // tirage depuis un VRAI deck (avec reshuffle auto depuis la défausse)
        String type = drawPotion(g);
        if (type == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no potions left");

        if (p.getPotions() == null) {
            p.setPotions(new java.util.ArrayList<>());
        }
        p.getPotions().add(type);

        addHistory(g, nameOf(g, userId) + " achète une potion.");

        // nb restant réellement piochable (deck ou futur reshuffle)
        int remaining = deckAvailableSize(g.getPotionDeck(), g.getPotionDiscard());

        save(g);

        final String fType = type;
        final int fRemaining = remaining;
        afterCommit(() -> live.potionBought(g, userId, fType, fRemaining));

        return g;
    }

    @Transactional
    public Game buyAction(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        if (!isAlive(p)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }

        boolean isVamp = "VAMPIRE".equals(p.getRole());
        boolean isHunter = "HUNTER".equals(p.getRole());

        List<String> deck = isVamp ? g.getVampActionsDeck() : g.getHunterActionsDeck();
        List<String> discard = isVamp ? g.getVampActionsDiscard() : g.getHunterActionsDiscard();

        int actionsLeft = deckAvailableSize(deck, discard);
        if (actionsLeft <= 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no actions left");

        if (g.getActionCardsBoughtThisRaid() == null) {
            g.setActionCardsBoughtThisRaid(new java.util.HashMap<>());
        }
        int boughtCount = g.getActionCardsBoughtThisRaid().getOrDefault(userId, 0);

        final int baseCost = (boughtCount + 1) * 50;
        int costSouls = baseCost;
        int costGold = baseCost;

        if (isHunter) {
            // +50 si Avidité nocturne
            if (g.isShopPricesIncreasedThisRaid()) {
                costGold += 50;
            }
            // -20 si ce chasseur est charismatique
            if (p.isCharismaticThisRaid()) {
                costGold = Math.max(0, costGold - 20);
            }
        }

        // paiement
        if (isVamp) {
            if (p.getSouls() < costSouls)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
            p.setSouls(p.getSouls() - costSouls);
        } else if (isHunter) {
            if (p.getGold() < costGold)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
            p.setGold(p.getGold() - costGold);
        }

        // Tirage (gère le reshuffle auto si deck vide + défausse non vide)
        String type = isVamp ? drawVampAction(g) : drawHunterAction(g);
        if (type == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no actions left");

        if (p.getActions() == null) {
            p.setActions(new java.util.ArrayList<>());
        }
        p.getActions().add(type);

        addHistory(g,
                nameOf(g, userId) + " pioche une carte d'action (" +
                        (isVamp ? "camp vampire" : "camp chasseurs") + ").");

        int remaining = deckAvailableSize(deck, discard);

        g.getActionCardsBoughtThisRaid().put(userId, boughtCount + 1);

        save(g);

        final String fType = type;
        final int fRemaining = remaining;
        afterCommit(() -> {
            live.actionBought(g, userId, fType, fRemaining);
        });

        return g;
    }

    @Transactional
    public Game buySilver(String gameId, String userId, int qty) {
        if (qty <= 0)
            qty = 1;

        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        if (!isAlive(p)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");

        final int baseUnitCost = 50;
        int unitCost = baseUnitCost;

        if (g.isShopPricesIncreasedThisRaid()) {
            unitCost += 50;
        }
        if (p.isCharismaticThisRaid()) {
            unitCost = Math.max(0, unitCost - 20);
        }

        int cost = unitCost * qty;
        if (p.getGold() < cost)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not enough gold");

        p.setGold(p.getGold() - cost);
        p.setSilver(p.getSilver() + qty);
        addHistory(g, nameOf(g, userId) + " achète " + qty + " argent (" + cost + " or).");

        save(g);

        final int fQty = qty;
        final int fCost = cost;

        afterCommit(() -> live.silverBought(g, userId, fQty, fCost));
        return g;
    }

    @Transactional
    public Game buyHolyWaterAction(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        if (!isAlive(p)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }

        if (!"HUNTER".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");
        }

        // Coût de base
        final int baseGoldCost = 150;
        final int waterCost = 4;

        int costGold = baseGoldCost;

        if (g.isShopPricesIncreasedThisRaid()) {
            costGold += 50;
        }
        if (p.isCharismaticThisRaid()) {
            costGold = Math.max(0, costGold - 20);
        }

        // Vérif ressources
        if (p.getWater() < waterCost || p.getGold() < costGold) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
        }

        // Paiement
        p.setWater(p.getWater() - waterCost);
        p.setGold(p.getGold() - costGold);

        // Ajout direct de la carte dans la main
        if (p.getActions() == null) {
            p.setActions(new java.util.ArrayList<>());
        }
        p.getActions().add("EAU_BENITE");

        addHistory(g,
                nameOf(g, userId)
                        + " achète une carte Eau bénite ("
                        + waterCost + " eaux pures, " + costGold + " or).");

        save(g);

        final int fCostGold = costGold;
        final int fCostWater = waterCost;
        afterCommit(() -> {
            live.holyWaterActionBought(g, userId, fCostWater, fCostGold);
        });

        return g;
    }

    @Transactional
    public Game buyTrackingAction(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        if (!isAlive(p)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu es hors de combat pour le reste de la partie.");
        }

        if (!"HUNTER".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");
        }

        // Coût de base
        final int baseGoldCost = 100;

        int costGold = baseGoldCost;

        if (g.isShopPricesIncreasedThisRaid()) {
            costGold += 50;
        }
        if (p.isCharismaticThisRaid()) {
            costGold = Math.max(0, costGold - 20);
        }

        // Vérif ressources
        if (p.getGold() < costGold) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
        }

        // Paiement
        p.setGold(p.getGold() - costGold);

        int cur = (p.getShopPisteurCount() == null) ? 0 : p.getShopPisteurCount();
        p.setShopPisteurCount(cur + 1);

        // Ajout direct de la carte dans la main
        if (p.getActions() == null) {
            p.setActions(new java.util.ArrayList<>());
        }
        p.getActions().add("PISTEUR");

        addHistory(g,
                nameOf(g, userId)
                        + " achète une carte Pisteur (" + costGold + " or).");

        save(g);

        final int fCostGold = costGold;
        afterCommit(() -> {
            live.trackingActionBought(g, userId, fCostGold);
        });

        return g;
    }

    @Transactional
    public Game buyUpgradeWeapon(String gameId, String userId, int expectedTier, String expectedType) {
        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");
        if (!isAlive(p))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");

        int cur = hunterWeaponTier(p.getWeapon());
        if (cur >= 2)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "arme déjà au maximum");

        int tier = Math.min(2, cur + 1); // 0->1, 1->2

        // Le front DOIT envoyer ce qu'il affiche
        if (expectedTier != tier) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "offer tier mismatch (refresh)");
        }

        // Normalisation type
        String type = (expectedType == null) ? null : expectedType.trim().toUpperCase();
        if (!"BLEED".equals(type) && !"RANGE".equals(type) && !"STUN".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid offer type");
        }

        // Maps (canon serveur)
        if (g.getShopWeaponOfferTypeByHunter() == null) {
            g.setShopWeaponOfferTypeByHunter(new java.util.HashMap<>());
        }
        if (g.getShopWeaponOfferTierByHunter() == null) {
            g.setShopWeaponOfferTierByHunter(new java.util.HashMap<>());
        }

        var typeMap = g.getShopWeaponOfferTypeByHunter();
        var tierMap = g.getShopWeaponOfferTierByHunter();

        // on aligne l’offre serveur sur ce que le client affiche
        // (ça supprime définitivement le “j’ai acheté spear mais j’ai mace”)
        tierMap.put(userId, tier);
        typeMap.put(userId, type);

        // Mapping type -> weaponId (doit matcher tes assets)
        String weaponId;
        if (tier == 1) {
            weaponId = switch (type) {
                case "BLEED" -> H_WEAPON_T1_SWORD;
                case "RANGE" -> H_WEAPON_T1_SPEAR;
                case "STUN" -> H_WEAPON_T1_MACE;
                default -> H_WEAPON_T1_SWORD;
            };
        } else { // tier == 2
            weaponId = switch (type) {
                case "BLEED" -> H_WEAPON_T2_HALBERD;
                case "RANGE" -> H_WEAPON_T2_CROSSBOW;
                case "STUN" -> H_WEAPON_T2_HAMMER;
                default -> H_WEAPON_T2_HALBERD;
            };
        }

        // Coûts (doit matcher le front)
        int woodCost = (tier == 1) ? 2 : 3;
        int ironCost = (tier == 1) ? 2 : 3;

        if (p.getWood() < woodCost || p.getIron() < ironCost)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");

        p.setWood(p.getWood() - woodCost);
        p.setIron(p.getIron() - ironCost);

        p.setWeapon(weaponId);
        if (tier == 1)
            p.setAttackDice("D6");
        if (tier == 2)
            p.setAttackDice("D8");

        String msg = nameOf(g, userId)
                + " forge une arme de tier " + tier
                + " (" + weaponId + ").";
        addHistory(g, msg);

        // Préparer l’offre suivante (T2) ou clear (si T2 acheté)
        if (tier >= 2) {
            typeMap.remove(userId);
            tierMap.remove(userId);
        } else {
            int nextTier = 2;
            int r2 = dice.nextInt(3);
            String nextType = (r2 == 0) ? "BLEED" : (r2 == 1) ? "RANGE" : "STUN";
            tierMap.put(userId, nextTier);
            typeMap.put(userId, nextType);
        }

        rebuildEquipmentMods(g);
        save(g);

        afterCommit(() -> {
            pushLive(g, msg);
            live.stuffBought(g, userId);
        });

        return g;
    }

    @Transactional
    public Game buyUpgradeArmor(String gameId, String userId) {
        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");
        if (!isAlive(p))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tu es hors de combat pour le reste de la partie.");

        int cur = hunterArmorTier(p.getArmor());
        if (cur >= 2)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "armure déjà au maximum");

        int tier = cur + 1;
        tier = Math.min(2, tier);

        String armorId = (tier == 1) ? H_ARMOR_T1_BRIGANDINE : H_ARMOR_T2_HAUBERT;

        int woodCost = (tier == 1) ? 2 : 3;
        int ironCost = (tier == 1) ? 2 : 3;

        if (p.getWood() < woodCost || p.getIron() < ironCost)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");

        p.setWood(p.getWood() - woodCost);
        p.setIron(p.getIron() - ironCost);

        p.setArmor(armorId);
        if (tier == 1)
            p.setDefenseDice("D6");
        if (tier == 2)
            p.setDefenseDice("D8");

        String msg = nameOf(g, userId)
                + " achète une armure de tier " + tier
                + " (" + armorId + ").";
        addHistory(g, msg);

        rebuildEquipmentMods(g);
        save(g);

        afterCommit(() -> {
            pushLive(g, msg);
            live.stuffBought(g, userId);
        });

        return g;
    }

    @Transactional
    public Game sellResource(String gameId, String userId, String res, int qty) {
        if (qty <= 0)
            qty = 1;
        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");

        Set<String> allowed = Set.of("wood", "herbs", "stone", "iron", "water");
        if (!allowed.contains(res))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid resource");

        // vérifier stock
        int have = switch (res) {
            case "wood" -> p.getWood();
            case "herbs" -> p.getHerbs();
            case "stone" -> p.getStone();
            case "iron" -> p.getIron();
            case "water" -> p.getWater();
            default -> 0;
        };
        if (have < qty)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not enough resource");

        // débiter
        switch (res) {
            case "wood" -> p.setWood(have - qty);
            case "herbs" -> p.setHerbs(have - qty);
            case "stone" -> p.setStone(have - qty);
            case "iron" -> p.setIron(have - qty);
            case "water" -> p.setWater(have - qty);
        }
        int base = 10;
        boolean charismatic = p.isCharismaticThisRaid();
        if (charismatic)
            base += 10;

        int gain = base * qty;
        p.setGold(p.getGold() + gain);
        addHistory(g, nameOf(g, userId) + " vend " + qty + " " + resLabelFr(res) + " (+" + gain + " or).");

        save(g);

        final String fRes = res;
        final int fQty = qty;
        final int fGain = gain;

        afterCommit(() -> live.resourceSold(g, userId, fRes, fQty, fGain));
        return g;
    }

    @Transactional
    public Game transmute(String gameId, String userId, String recipe) {
        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"VAMPIRE".equals(p.getRole()) && !"SERVANT".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire/servants only");

        switch (recipe) {
            case "WOOD_TO_IRON" -> { // 2 bois + 1 eau → +2 fer
                if (p.getWood() < 2 || p.getWater() < 1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                p.setWood(p.getWood() - 2);
                p.setWater(p.getWater() - 1);
                p.setIron(p.getIron() + 2);
                addHistory(g, nameOf(g, userId) + " transmute: 2 bois + 1 eau → +2 fer.");
            }
            case "IRON_TO_WOOD" -> { // 2 fer + 1 eau → +2 bois
                if (p.getIron() < 2 || p.getWater() < 1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                p.setIron(p.getIron() - 2);
                p.setWater(p.getWater() - 1);
                p.setWood(p.getWood() + 2);
                addHistory(g, nameOf(g, userId) + " transmute: 2 fer + 1 eau → +2 bois.");
            }
            case "TRINITY_TO_SOULS" -> { // 1 bois + 1 fer + 1 eau → +30 âmes
                if (p.getWood() < 1 || p.getIron() < 1 || p.getWater() < 1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                p.setWood(p.getWood() - 1);
                p.setIron(p.getIron() - 1);
                p.setWater(p.getWater() - 1);
                p.setSouls(p.getSouls() + 30);
                addHistory(g, nameOf(g, userId) + " transmute: 1 bois + 1 fer + 1 eau → +30 âmes.");
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown recipe");
        }

        save(g);
        afterCommit(() -> live.transmuted(g, userId, recipe));
        return g;
    }

    private boolean sameSideCanTrade(Player a, Player b) {
        if ("HUNTER".equals(a.getRole()) && "HUNTER".equals(b.getRole()))
            return true;
        // vamp side: vamp <-> servant uniquement
        if ("VAMPIRE".equals(a.getRole()) && "SERVANT".equals(b.getRole()))
            return true;
        if ("SERVANT".equals(a.getRole()) && "VAMPIRE".equals(b.getRole()))
            return true;
        return false;
    }

    private String sideOf(Player a, Player b) {
        return "HUNTER".equals(a.getRole()) && "HUNTER".equals(b.getRole()) ? "HUNTERS" : "VAMP_SIDE";
    }

    private Game.Trade getOrCreateTrade(Game g, String aId, String bId) {
        String lo = aId.compareTo(bId) <= 0 ? aId : bId;
        String hi = aId.compareTo(bId) <= 0 ? bId : aId;

        for (var t : g.getTrades()) {
            if ((t.getAId().equals(lo) && t.getBId().equals(hi)))
                return t;
        }
        var t = new Game.Trade();
        t.setId(java.util.UUID.randomUUID().toString());
        t.setAId(lo);
        t.setBId(hi);
        var pa = findPlayer(g, lo);
        var pb = findPlayer(g, hi);
        t.setSide(sideOf(pa, pb));
        g.getTrades().add(t);
        return t;
    }

    @Transactional
    public Game tradeSetMyOffer(String gameId, String userId, String targetId, Map<String, Integer> offer) {
        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var me = findPlayer(g, userId);
        var you = findPlayer(g, targetId);
        if (me == null || you == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "player not found");
        if (!sameSideCanTrade(me, you))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "pair not eligible");

        var t = getOrCreateTrade(g, userId, targetId);

        // sanitize
        Map<String, Integer> sanitized = new java.util.HashMap<>();
        if (offer != null) {
            for (var e : offer.entrySet()) {
                int q = Math.max(0, e.getValue() == null ? 0 : e.getValue());
                if (q > 0)
                    sanitized.put(e.getKey(), q);
            }
        }

        boolean iAmA = userId.equals(t.getAId());
        if (iAmA)
            t.setOfferA(sanitized);
        else
            t.setOfferB(sanitized);

        // UX : toute modif remet les deux côtés à PENDING
        t.setStatusA("PENDING");
        t.setStatusB("PENDING");
        t.setUpdatedAt(System.currentTimeMillis());

        save(g);
        afterCommit(() -> live.tradeSync(g, t));
        return g;
    }

    @Transactional
    public Game tradeAction(String gameId, String userId, String targetId, String action) {
        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var me = findPlayer(g, userId);
        var you = findPlayer(g, targetId);
        if (me == null || you == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "player not found");
        if (!sameSideCanTrade(me, you))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "pair not eligible");

        var t = getOrCreateTrade(g, userId, targetId);
        boolean iAmA = userId.equals(t.getAId());

        String st = switch (action) {
            case "confirm" -> "CONFIRMED";
            case "refuse" -> "REFUSED";
            case "cancel" -> "CANCELLED";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid action");
        };

        if (iAmA)
            t.setStatusA(st);
        else
            t.setStatusB(st);
        t.setUpdatedAt(System.currentTimeMillis());

        String tId = t.getId();
        String aId = t.getAId();
        String bId = t.getBId();
        Map<String, Integer> offerA = t.getOfferA() == null ? java.util.Map.of()
                : new java.util.HashMap<>(t.getOfferA());
        Map<String, Integer> offerB = t.getOfferB() == null ? java.util.Map.of()
                : new java.util.HashMap<>(t.getOfferB());

        boolean deleted = false;
        boolean success = false;

        if ("CONFIRMED".equals(t.getStatusA()) && "CONFIRMED".equals(t.getStatusB())) {
            success = true;
            applyTradeExchange(g, t);
            g.getTrades().remove(t);
            deleted = true;
        } else if (isFinal(t.getStatusA()) && isFinal(t.getStatusB())) {
            g.getTrades().remove(t);
            deleted = true;
        }

        save(g);
        if (deleted) {
            final boolean fSuccess = success;
            final var fOfferA = offerA;
            final var fOfferB = offerB;

            final String fResult = fSuccess ? "SUCCESS" : "CLOSED";
            final java.util.Map<String, Object> extra = fSuccess
                    ? new java.util.HashMap<>(java.util.Map.of(
                            "offerA", fOfferA,
                            "offerB", fOfferB))
                    : java.util.Map.of();

            afterCommit(() -> {
                live.tradeDeleted(
                        g, tId, aId, bId,
                        "FINAL",
                        fResult,
                        extra);
            });
        } else {
            afterCommit(() -> live.tradeSync(g, t));
        }
        return g;
    }

    private boolean isFinal(String s) {
        return "REFUSED".equals(s) || "CANCELLED".equals(s);
    }

    // Exécution de l'échange (débits puis crédits)
    private void applyTradeExchange(Game g, Game.Trade t) {
        var a = findPlayer(g, t.getAId());
        var b = findPlayer(g, t.getBId());
        if (a == null || b == null)
            return;

        // vérif stocks côté A et B
        if (!hasAll(a, t.getOfferA()) || !hasAll(b, t.getOfferB()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "insufficient resources");

        debit(a, t.getOfferA());
        debit(b, t.getOfferB());
        credit(a, t.getOfferB());
        credit(b, t.getOfferA());

        addHistory(g, nameOf(g, a.getId()) + " et " + nameOf(g, b.getId()) + " concluent un échange.");
    }

    // helpers
    private boolean hasAll(Player p, Map<String, Integer> pack) {
        if (pack == null)
            return true;
        for (var e : pack.entrySet()) {
            int need = Math.max(0, e.getValue() == null ? 0 : e.getValue());
            switch (e.getKey()) {
                case "wood" -> {
                    if (p.getWood() < need)
                        return false;
                }
                case "herbs" -> {
                    if (p.getHerbs() < need)
                        return false;
                }
                case "stone" -> {
                    if (p.getStone() < need)
                        return false;
                }
                case "iron" -> {
                    if (p.getIron() < need)
                        return false;
                }
                case "water" -> {
                    if (p.getWater() < need)
                        return false;
                }
                case "gold" -> {
                    if (p.getGold() < need)
                        return false;
                } // boutique dit or permis côté hunters
                case "souls" -> {
                    if (p.getSouls() < need)
                        return false;
                } // transmutation côté vamp
                case "silver" -> {
                    if (p.getSilver() < need)
                        return false;
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid resource: " + e.getKey());
            }
        }
        return true;
    }

    private void debit(Player p, Map<String, Integer> pack) {
        if (pack == null)
            return;
        for (var e : pack.entrySet()) {
            int q = Math.max(0, e.getValue() == null ? 0 : e.getValue());
            switch (e.getKey()) {
                case "wood" -> p.setWood(p.getWood() - q);
                case "herbs" -> p.setHerbs(p.getHerbs() - q);
                case "stone" -> p.setStone(p.getStone() - q);
                case "iron" -> p.setIron(p.getIron() - q);
                case "water" -> p.setWater(p.getWater() - q);
                case "gold" -> p.setGold(p.getGold() - q);
                case "souls" -> p.setSouls(p.getSouls() - q);
                case "silver" -> p.setSilver(p.getSilver() - q);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid resource: " + e.getKey());
            }
        }
    }

    private void credit(Player p, Map<String, Integer> pack) {
        if (pack == null)
            return;
        for (var e : pack.entrySet()) {
            int q = Math.max(0, e.getValue() == null ? 0 : e.getValue());
            switch (e.getKey()) {
                case "wood" -> p.setWood(p.getWood() + q);
                case "herbs" -> p.setHerbs(p.getHerbs() + q);
                case "stone" -> p.setStone(p.getStone() + q);
                case "iron" -> p.setIron(p.getIron() + q);
                case "water" -> p.setWater(p.getWater() + q);
                case "gold" -> p.setGold(p.getGold() + q);
                case "souls" -> p.setSouls(p.getSouls() + q);
                case "silver" -> p.setSilver(p.getSilver() + q);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid resource: " + e.getKey());
            }
        }
    }

    // Construction
    @Transactional
    public Game planConstruction(String gameId, String playerId, Infra infra) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        var p = findPlayer(g, playerId);

        // --- PHASE + RÔLE ---
        if (g.getPhase() != Phase.PHASE2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "construction possible uniquement en phase 2");
        }

        if (g.getWeatherStatus() == WeatherStatus.WIND
                || g.getSecondaryWeatherStatus() == WeatherStatus.WIND) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Impossible de construire ce raid à cause du cyclone.");
        }

        if (!"VAMPIRE".equals(p.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Seul le vampire peut construire.");
        }

        // --- PAS 2 CONSTRUCTIONS EN PARALLÈLE ---
        if (g.getPendingConstruction() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Une construction est déjà en cours pour ce raid.");
        }

        // --- DÉJÀ CONSTRUIT ? ---
        if (g.getBuiltInfras() != null && g.getBuiltInfras().contains(infra)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ce lieu a déjà été construit.");
        }

        // --- RESSOURCES MINIMALES ---
        if (!hasResourcesForInfra(p, infra)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ressources insuffisantes pour construire " + infra);
        }

        // --- QUELLE CARTE LIEU DOIT-IL JOUER ? ---
        final String card;
        switch (infra) {
            case SAWMILL -> card = "forest";
            case MINE -> card = "quarry";
            case LIBRARY -> card = "manor";
            case LABORATORY -> card = "manor";
            case BALLROOM -> card = "manor";
            case ALTAR -> card = "manor";
            case FORGE -> card = "manor";
            default -> throw new IllegalArgumentException("Infra non supportée: " + infra);
        }

        // --- FUMIGATION : vampire/servant ne peuvent pas aller sur un lieu fumigé ---
        if (g.getGarlicBlockedLocations() != null
                && g.getGarlicBlockedLocations().contains(card)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ce lieu est protégé par une fumigation d'ail");
        }

        // --- DÉJÀ JOUÉ CE ROUND ? ---
        if (hasPlayed(g, playerId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already selected this round");

        // --- LA CARTE DOIT ÊTRE DANS SA MAIN ---
        var hand = p.getHand();
        if (hand == null || !hand.remove(card)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "card not in hand");
        }

        // Pas de fumigation ici : on joue juste le lieu pour se déplacer
        boolean fumigate = false;

        // Pose au centre (comme selectLocation)
        CenterBoard cb = new CenterBoard(playerId, card, /* faceUp= */fumigate);
        g.getCenter().add(cb);

        // Pisteur : même comportement que quand le vampire joue un lieu
        applyTrackerHuntersWhenVampirePlays(g, card);

        // Auto-advance vers PREPHASE3 si tout le camp vampire/servants a joué
        boolean advanceToPre3 = allVampSideSelected(g);
        if (advanceToPre3) {
            g.setHasUpcomingCombat(computeHasUpcomingCombat(g));
        }

        // --- ENREGISTRER LA CONSTRUCTION EN ATTENTE ---
        Game.PendingConstruction pc = new Game.PendingConstruction();
        pc.infra = infra;
        pc.builderId = p.getId();
        g.setPendingConstruction(pc);

        // ----- COMMIT -----
        save(g);

        final String gid = g.getId();
        // final Infra infraFinal = infra;

        afterCommit(() -> {
            live.locationSelected(g, playerId, card);
            // live.constructionPlanned(gid, playerId, infraFinal.name());
        });

        // ----- AUTO-ADVANCE (scheduler) -----
        if (advanceToPre3) {
            scheduleAdvance(gid, Phase.PHASE2, Phase.PREPHASE3, 2500);
        }

        return g;
    }

    private boolean hasResourcesForInfra(Player p, Infra infra) {
        return switch (infra) {
            case SAWMILL -> p.getStone() >= 2 && p.getIron() >= 2;
            case MINE -> p.getWood() >= 3 && p.getIron() >= 1;
            case LIBRARY -> p.getWood() >= 4 && p.getStone() >= 2 && p.getIron() >= 1;
            case LABORATORY -> p.getWater() >= 2
                    && p.getHerbs() >= 2
                    && p.getStone() >= 3
                    && p.getSouls() >= 50;
            case BALLROOM -> p.getStone() >= 5 && p.getIron() >= 2 && p.getSouls() >= 50;
            case ALTAR -> p.getStone() >= 4
                    && p.getWood() >= 1
                    && p.getIron() >= 2
                    && p.getSouls() >= 50;
            case FORGE -> p.getIron() >= 5
                    && p.getStone() >= 4
                    && p.getWood() >= 2;
        };
    }

    private void payResourcesForInfra(Player p, Infra infra) {
        switch (infra) {
            case SAWMILL -> {
                p.setStone(p.getStone() - 2);
                p.setIron(p.getIron() - 2);
            }
            case MINE -> {
                p.setWood(p.getWood() - 3);
                p.setIron(p.getIron() - 1);
            }
            case LIBRARY -> {
                p.setWood(p.getWood() - 4);
                p.setStone(p.getStone() - 2);
                p.setIron(p.getIron() - 1);
            }
            case LABORATORY -> {
                p.setWater(p.getWater() - 2);
                p.setHerbs(p.getHerbs() - 2);
                p.setStone(p.getStone() - 3);
                p.setSouls(p.getSouls() - 50);
            }
            case BALLROOM -> {
                p.setStone(p.getStone() - 5);
                p.setIron(p.getIron() - 2);
                p.setSouls(p.getSouls() - 50);
            }
            case ALTAR -> {
                p.setStone(p.getStone() - 4);
                p.setWood(p.getWood() - 1);
                p.setIron(p.getIron() - 2);
                p.setSouls(p.getSouls() - 50);
            }
            case FORGE -> {
                p.setIron(p.getIron() - 5);
                p.setStone(p.getStone() - 4);
                p.setWood(p.getWood() - 2);
            }
        }
    }

    /** Récolte du lieu construit (au lieu de Forêt/Carrière). */
    private void applyInfraHarvest(Game g, Player vamp, Infra infra) {
        java.util.List<String> gains = new java.util.ArrayList<>();
        switch (infra) {
            case SAWMILL -> {
                grant(vamp, "wood", 6);
                gains.add("+6 bois");
            }
            case MINE -> {
                grant(vamp, "iron", 6);
                gains.add("+6 fer");
            }
            case LIBRARY, LABORATORY, BALLROOM, ALTAR, FORGE -> {
                // Pour l’instant : même logique que Manoir, tu ajusteras si tu as déjà un case
                // "manor"
                // Exemple : +1d100 or OU +1d100 âmes déchues selon rôle
                int d100 = rollD100Tens();
                if ("VAMPIRE".equals(vamp.getRole())) {
                    grant(vamp, "souls", d100);
                    gains.add("+" + d100 + " âmes déchues");
                } else {
                    grant(vamp, "gold", d100);
                    gains.add("+" + d100 + " or");
                }
            }
        }

        if (!gains.isEmpty()) {
            String who = nameOf(g, vamp.getId());
            String line = "Récoltes — " + who + " (" + infra.labelFr() + ") : "
                    + String.join(", ", gains);
            addHistory(g, line);
        }
    }

    private boolean skipHarvestBecauseOfConstruction(Game g, Player p, String loc) {
        var pc = g.getPendingConstruction();
        if (pc == null)
            return false;

        var vampOpt = getVamp(g);
        if (vampOpt.isEmpty())
            return false;
        var vamp = vampOpt.get();

        // On ne bloque que la récolte du vampire lui-même
        if (!p.getId().equals(vamp.getId()))
            return false;

        // Mapping infra -> lieu de base
        return switch (pc.infra) {
            case SAWMILL -> "forest".equals(loc);
            case MINE -> "quarry".equals(loc);
            case LIBRARY -> "manor".equals(loc);
            case LABORATORY -> "manor".equals(loc);
            case BALLROOM -> "manor".equals(loc);
            case ALTAR -> "manor".equals(loc);
            case FORGE -> "manor".equals(loc);
        };
    }

    private void giveInfraCardToAllPlayers(Game g, Infra infra) {
        String cardCode = infra.locationCode(); // "sawmill" ou "mine"

        for (var p : g.getPlayers()) {
            p.getHand().add(cardCode);
        }
    }

    /** À appeler à la fin de la PHASE3, avant de passer en PHASE4. */
    private void resolveInfraConstruction(Game g) {
        var pc = g.getPendingConstruction();
        if (pc == null)
            return;

        // On consomme la construction en attente dans tous les cas
        g.setPendingConstruction(null);

        var vampOpt = getVamp(g);
        if (vampOpt.isEmpty())
            return;
        var vamp = vampOpt.get();

        /*
         * // 1) Si le vampire a pris des dégâts, la construction échoue,
         * // mais il récolte le lieu d'origine (forêt/carrière)
         * if (g.isVampireTookDamageThisRaid()) {
         * addHistory(g, "La construction de " + pc.infra
         * + " échoue : le vampire a subi des dégâts durant le raid.");
         * applyBaseLocationHarvestForInfra(g, vamp, pc.infra);
         * return;
         * }
         */

        // 2) Vérifier qu'il a encore les ressources
        if (!hasResourcesForInfra(vamp, pc.infra)) {
            addHistory(g, "La construction de " + pc.infra
                    + " échoue : le vampire n'a plus les ressources nécessaires.");
            applyBaseLocationHarvestForInfra(g, vamp, pc.infra);
            return;
        }

        // 3) Payer les ressources
        payResourcesForInfra(vamp, pc.infra);

        // 4) Marquer l'infrastructure comme construite (une seule fois par partie)
        g.getBuiltInfras().add(pc.infra);

        if (pc.infra == Infra.ALTAR) {
            // Première fois qu'on le construit → autel corrompu
            g.setAltarCorrupted(Boolean.TRUE);
        }

        addHistory(g, "La construction de " + pc.infra + " est achevée.");

        // 5) Donner la carte Lieu correspondante à tous les joueurs
        giveInfraCardToAllPlayers(g, pc.infra);

        // 6) Récolte du nouveau lieu pour ce raid
        applyInfraHarvest(g, vamp, pc.infra);

        // persiste
        save(g);

        // events après commit
        final String vampId = vamp.getId();
        final String infraName = pc.infra.name();

        afterCommit(() -> {
            live.infraBuilt(g, vampId, infraName);
        });
    }

    /** Récolte du lieu d'origine (FOREST/QUARRY) quand la construction échoue. */
    private void applyBaseLocationHarvestForInfra(Game g, Player vamp, Infra infra) {
        java.util.List<String> gains = new java.util.ArrayList<>();
        String locKey;
        String locLabel;

        switch (infra) {
            case SAWMILL -> {
                // même logique que case "forest" de applyHarvests pour le vampire
                grant(vamp, "wood", 2);
                gains.add("+1 bois");
                grant(vamp, "herbs", 4);
                gains.add("+2 herbe médicinale");
                locKey = "forest";
                locLabel = labelLieuFr("forest");
            }
            case MINE -> {
                // même logique que case "quarry"
                grant(vamp, "iron", 2);
                gains.add("+1 fer");
                grant(vamp, "stone", 4);
                gains.add("+2 pierre");
                locKey = "quarry";
                locLabel = labelLieuFr("quarry");
            }
            case LIBRARY, LABORATORY, BALLROOM, ALTAR, FORGE -> {
                // même logique que "manor" quand la construction échoue
                int d100 = rollD100Tens();
                if ("VAMPIRE".equals(vamp.getRole())) {
                    grant(vamp, "souls", d100 + 100);
                    gains.add("+" + (d100 + 100) + " âmes déchues");
                } else {
                    grant(vamp, "gold", d100 + 100);
                    gains.add("+" + (d100 + 100) + " or");
                }
                locKey = "manor";
                locLabel = labelLieuFr("manor");
            }
            default -> {
                return; // au cas où d'autres infras plus tard
            }
        }

        if (!gains.isEmpty()) {
            String who = nameOf(g, vamp.getId());
            String line = "Récoltes — " + who + " (" + locLabel + ") : " + String.join(", ", gains);
            addHistory(g, line);
        }
    }

    /**
     * Fin de PREPHASE3 :
     * - soit on démarre / poursuit la file d'effets de lieu,
     * - soit on bascule en PHASE3 (récoltes + combats + pièges).
     *
     * Cette méthode :
     * - suppose qu'on est encore en PREPHASE3,
     * - suppose qu'il n'y a plus de choix "instable" en attente,
     * - ne vérifie PAS readyForPhase3 (utile pour les timers serveur).
     */
    /**
     * Fin de PREPHASE3 : Etape 1 - Résolution d'actions différées
     *
     * Si le vampire a une action en attente (Passage Secret, Image Miroir...),
     * on la déclenche maintenant (pause du flux).
     * Sinon, on passe à l'étape 2 (Effets de lieu ou PHASE3).
     */
    private void finishPrephaseAndMaybeStartActionResolve(Game g, String gameId) {
        // INTERCEPTION : Résolution différée Passage Secret / Image Miroir
        if (g.getPendingVampireEscape() != null) {
            String mode = g.getPendingVampireEscape();
            var vamp = getVamp(g).orElseThrow();

            // ═══════════════════════════════════════════════════════════════════════════
            // IMPORTANT : La PREPHASE est maintenant TERMINÉE
            // ═══════════════════════════════════════════════════════════════════════════
            // On a atteint ce point soit parce que :
            // - Le timer de 30s est arrivé à 0, OU
            // - Tous les joueurs ont cliqué "J'ai fini"
            //
            // Dans les deux cas, la prephase est FINIE. On va maintenant ouvrir la modale
            // de sélection de lieu pour le vampire, qui doit rester ouverte INDÉFINIMENT
            // (pas de timer) jusqu'à ce que le vampire fasse son choix.
            //
            // Pour éviter que le timer continue de tourner en arrière-plan ou redémarre,
            // on doit signaler clairement que la prephase est terminée :
            //
            // 1) Vider readyForPhase3 : plus personne n'a besoin d'être "prêt"
            // (empêche le timer serveur planifié de continuer à progresser)
            //
            // 2) Mettre hasUpcomingCombat = false : signal au frontend d'arrêter le timer
            // (le frontend ne doit PAS relancer le timer après résolution de l'action)
            // ═══════════════════════════════════════════════════════════════════════════

            if (g.getReadyForPhase3() != null) {
                g.getReadyForPhase3().clear();
            }
            g.setHasUpcomingCombat(false);

            // 3) Invalider le timer précédent (version++) pour qu'il ne s'exécute PAS
            // si on avait attendu la fin du timer (ou s'il tournait encore).
            g.setPrephaseTimerVersion(g.getPrephaseTimerVersion() + 1);

            Game.Action a = new Game.Action();
            a.setMode(mode);
            a.setOwnerId(vamp.getId());
            a.setLocation(null);
            a.setTargetId(null);
            a.setRoll(null);
            a.setBreakdownLines(new ArrayList<>());

            g.setCurrentAction(a);
            g.setPendingVampireEscape(null); // Consommé

            addHistory(g, "Action (" + mode + ") déclenchée pour " + nameOf(g, vamp.getId()));
            save(g);

            afterCommit(() -> {
                Game fresh = findOr404(gameId);
                // 5 args requis : Game, msg, ownerId, location, targetId
                live.actionStarted(fresh, mode, vamp.getId(), null, null);
            });
            return;
        }

        // Pas d'action en attente -> on enchaîne sur les effets de lieu
        maybeStartLocationEffects(g, gameId);
    }

    private void maybeStartLocationEffects(Game g, String gameId) {
        // 1) Si un effet de lieu est DÉJÀ en cours de résolution (pending=true),
        // on ne touche à rien (c'est le process normal qui enchaine).
        if (Boolean.TRUE.equals(g.getLocationEffectPending())
                && g.getLocationEffectsQueue() != null
                && !g.getLocationEffectsQueue().isEmpty()) {
            return;
        }

        // Sinon (pas pending), on part du principe qu'on doit (re)lancer la séquence.
        // On nettoie toute queue existante potentiellement périmée ou incomplète
        // pour forcer un rebuild propre.
        if (g.getLocationEffectsQueue() != null) {
            g.getLocationEffectsQueue().clear();
        }

        // 2) (Re)construire la file d'effets à partir de l'état FINAL de PREPHASE3
        buildLocationEffectsQueue(g);

        if (g.getLocationEffectsQueue() != null && !g.getLocationEffectsQueue().isEmpty()) {
            // Il y a au moins un effet de lieu à résoudre → on reste en PREPHASE3
            g.setCurrentLocationEffectIndex(0);
            g.setLocationEffectPending(true);
            g.setLocationEffectChoice(null);

            save(g);

            afterCommit(() -> {
                Game fresh = findOr404(gameId);

                Game.LocationEffectInstance inst = null;
                if (fresh.getLocationEffectsQueue() != null
                        && fresh.getCurrentLocationEffectIndex() != null) {
                    int idx = fresh.getCurrentLocationEffectIndex();
                    if (idx >= 0 && idx < fresh.getLocationEffectsQueue().size()) {
                        inst = fresh.getLocationEffectsQueue().get(idx);
                    }
                }

                if (inst != null) {
                    live.locationEffectStarted(fresh, inst);
                }
                live.phaseChanged(fresh);
            });
        } else {
            // 3) Aucun effet de lieu → PHASE3 classique
            g.setPhase(Phase.PHASE3);
            applyPhaseEntry(g, Phase.PHASE3);

            save(g);

            afterCommit(() -> {
                Game fresh = findOr404(gameId);
                live.phaseChanged(fresh);
            });
        }
    }

    /**
     * (Re)construit la file des effets de lieu pour le raid courant.
     *
     * - Réinitialise la structure (queue, index courant, flags).
     * - Ajoute une entrée par joueur éligible pour chaque lieu à effet
     * (actuellement LIBRARY et LABORATORY).
     * - Utilise l'état final de PREPHASE3 (positions, instables, combats)
     * pour décider qui a droit à un effet.
     */
    private void buildLocationEffectsQueue(Game g) {
        // Réinit de la structure
        if (g.getLocationEffectsQueue() == null) {
            g.setLocationEffectsQueue(new java.util.ArrayList<>());
        } else {
            g.getLocationEffectsQueue().clear();
        }
        g.setCurrentLocationEffectIndex(null);
        g.setLocationEffectPending(false);
        g.setLocationEffectChoice(null);

        // Si aucune infra à effet n'est construite, rien à faire.
        if (g.getBuiltInfras() == null || g.getBuiltInfras().isEmpty()) {
            return;
        }

        boolean hasLibrary = g.getBuiltInfras().contains(Infra.LIBRARY);
        boolean hasLaboratory = g.getBuiltInfras().contains(Infra.LABORATORY);
        boolean hasBallroom = g.getBuiltInfras().contains(Infra.BALLROOM);
        boolean hasAltar = g.getBuiltInfras().contains(Infra.ALTAR);
        boolean hasForge = g.getBuiltInfras().contains(Infra.FORGE);

        if (!hasLibrary && !hasLaboratory && !hasBallroom && !hasAltar && !hasForge) {
            return; // aucune infra à effet
        }

        // Positions finales des joueurs par lieu (après instables)
        var groups = groupPlayersByLocation(g);

        // Instables déjà réaffectés (attaque OU récolte)
        var unstableAssigned = new java.util.HashSet<String>();
        if (g.getUnstableTargetByPlayer() != null) {
            unstableAssigned.addAll(g.getUnstableTargetByPlayer().keySet());
        }
        if (g.getUnstableHarvestLocByPlayer() != null) {
            unstableAssigned.addAll(g.getUnstableHarvestLocByPlayer().keySet());
        }

        // Joueurs impliqués dans des duels instables par lieu
        java.util.Map<String, java.util.Set<String>> unstableCombatPlayersByLoc = new java.util.HashMap<>();
        if (g.getUnstableTargetByPlayer() != null) {
            for (var entry : g.getUnstableTargetByPlayer().entrySet()) {
                String unstableId = entry.getKey();
                String targetId = entry.getValue();

                String loc = g.getCenter().stream()
                        .filter(cb -> cb.getPlayerId().equals(targetId))
                        .map(CenterBoard::getCard)
                        .findFirst()
                        .orElse(null);
                if (loc == null)
                    continue;

                var set = unstableCombatPlayersByLoc
                        .computeIfAbsent(loc, __ -> new java.util.HashSet<>());
                set.add(unstableId);
                set.add(targetId);
            }
        }

        // --- Construction de la file pour chaque joueur qui a joué "library" ---
        if (hasLibrary) {
            // Code de la carte associée à LIBRARY (normalement "library")
            String libraryCardCode = Infra.LIBRARY.locationCode();

            // Tous les joueurs physiquement sur "library"
            var onLibrary = groups.getOrDefault(libraryCardCode, java.util.List.<Player>of());

            // Ennemis (VAMPIRE/SERVANT) présents
            var enemiesOnLibrary = onLibrary.stream()
                    .filter(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                            && p.getHp() > 0)
                    .toList();

            // Chasseurs "par défaut" (comme dans buildCombatsQueue)
            var huntersDefaultOnLibrary = onLibrary.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .filter(p -> !unstableAssigned.contains(p.getId()))
                    .toList();

            // Monstres vivants sur ce lieu
            var monstersOnLibrary = monstersOnLocation(g, libraryCardCode).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            // Joueurs impliqués dans des duels instables SUR "library"
            java.util.Set<String> unstableCombatOnLibrary = unstableCombatPlayersByLoc.getOrDefault(libraryCardCode,
                    java.util.Set.of());

            for (var cb : g.getCenter()) {
                if (!libraryCardCode.equals(cb.getCard()))
                    continue;

                var owner = g.getPlayers().stream()
                        .filter(p -> p.getId().equals(cb.getPlayerId()))
                        .findFirst()
                        .orElse(null);
                if (owner == null)
                    continue;
                if (owner.getHp() <= 0)
                    continue; // mort → pas d'effet

                boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());

                // Vampire : garde toujours l'effet de lieu
                if (!ownerIsVamp) {
                    boolean willFightHere = false;

                    // 1) Duel instable sur library ?
                    if (unstableCombatOnLibrary.contains(owner.getId())) {
                        willFightHere = true;
                    } else {
                        // 2) Combats classiques comme dans buildCombatsQueue

                        if ("HUNTER".equals(owner.getRole())) {
                            boolean isHunterDefault = huntersDefaultOnLibrary.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isHunterDefault &&
                                    (!enemiesOnLibrary.isEmpty() || !monstersOnLibrary.isEmpty())) {
                                // chasseur vs (vampire/serviteur ou monstre)
                                willFightHere = true;
                            }
                        } else if ("SERVANT".equals(owner.getRole())) {
                            boolean isServantEnemy = enemiesOnLibrary.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId())); // il est dans enemiesOnLibrary
                            if (isServantEnemy && !huntersDefaultOnLibrary.isEmpty()) {
                                // serviteur vs chasseur
                                willFightHere = true;
                            }
                        }
                    }

                    // Chasseur / Serviteur qui va combattre sur la Bibliothèque → pas d'effet
                    if (willFightHere) {
                        continue;
                    }
                }

                Game.LocationEffectInstance inst = new Game.LocationEffectInstance();
                inst.ownerId = owner.getId();
                inst.infra = Infra.LIBRARY;
                inst.choice = null;
                g.getLocationEffectsQueue().add(inst);
            }

            // --- Sécurité : s'assurer que le vampire a bien une entrée s'il a joué
            // Bibliothèque ---
            var vampOpt = getVamp(g);
            if (vampOpt.isPresent()) {
                var vamp = vampOpt.get();

                boolean vampPlayedLibrary = g.getCenter().stream()
                        .anyMatch(cb -> libraryCardCode.equals(cb.getCard())
                                && vamp.getId().equals(cb.getPlayerId()));

                if (vampPlayedLibrary) {
                    boolean alreadyQueued = g.getLocationEffectsQueue().stream()
                            .anyMatch(inst -> inst.infra == Infra.LIBRARY
                                    && vamp.getId().equals(inst.ownerId));
                    if (!alreadyQueued) {
                        Game.LocationEffectInstance inst = new Game.LocationEffectInstance();
                        inst.ownerId = vamp.getId();
                        inst.infra = Infra.LIBRARY;
                        inst.choice = null;
                        g.getLocationEffectsQueue().add(inst);
                    }
                }
            }
        }

        // --- Effets du Laboratoire occulte ---
        if (hasLaboratory) {
            String labCardCode = Infra.LABORATORY.locationCode(); // ex : "laboratory"

            // Tous les joueurs physiquement sur "laboratory"
            var onLab = groups.getOrDefault(labCardCode, java.util.List.<Player>of());

            var enemiesOnLab = onLab.stream()
                    .filter(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                            && p.getHp() > 0)
                    .toList();

            var huntersDefaultOnLab = onLab.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .filter(p -> !unstableAssigned.contains(p.getId()))
                    .toList();

            var monstersOnLab = monstersOnLocation(g, labCardCode).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            java.util.Set<String> unstableCombatOnLab = unstableCombatPlayersByLoc.getOrDefault(labCardCode,
                    java.util.Set.of());

            for (var cb : g.getCenter()) {
                if (!labCardCode.equals(cb.getCard()))
                    continue;

                var owner = g.getPlayers().stream()
                        .filter(p -> p.getId().equals(cb.getPlayerId()))
                        .findFirst()
                        .orElse(null);

                if (owner == null)
                    continue;
                if (owner.getHp() <= 0)
                    continue; // mort → pas d'effet

                boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());

                if (!ownerIsVamp) {
                    boolean willFightHere = false;

                    if (unstableCombatOnLab.contains(owner.getId())) {
                        willFightHere = true;
                    } else {
                        if ("HUNTER".equals(owner.getRole())) {
                            boolean isHunterDefault = huntersDefaultOnLab.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isHunterDefault &&
                                    (!enemiesOnLab.isEmpty() || !monstersOnLab.isEmpty())) {
                                willFightHere = true;
                            }
                        } else if ("SERVANT".equals(owner.getRole())) {
                            boolean isServantEnemy = enemiesOnLab.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isServantEnemy && !huntersDefaultOnLab.isEmpty()) {
                                willFightHere = true;
                            }
                        }
                    }

                    if (willFightHere) {
                        continue;
                    }
                }

                Game.LocationEffectInstance inst = new Game.LocationEffectInstance();
                inst.ownerId = owner.getId(); // VAMPIRE, HUNTER ou SERVANT
                inst.infra = Infra.LABORATORY;
                inst.choice = null;
                g.getLocationEffectsQueue().add(inst);
            }
        }

        // --- Effets de la Salle de bal (BALLROOM) ---
        if (hasBallroom) {
            String ballroomCode = Infra.BALLROOM.locationCode(); // ex: "ballroom"

            var onBallroom = groups.getOrDefault(ballroomCode, java.util.List.<Player>of());

            var enemiesOnBallroom = onBallroom.stream()
                    .filter(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                            && p.getHp() > 0)
                    .toList();

            var huntersDefaultOnBallroom = onBallroom.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .filter(p -> !unstableAssigned.contains(p.getId()))
                    .toList();

            var monstersOnBallroom = monstersOnLocation(g, ballroomCode).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            java.util.Set<String> unstableCombatOnBallroom = unstableCombatPlayersByLoc.getOrDefault(ballroomCode,
                    java.util.Set.of());

            for (var cb : g.getCenter()) {
                if (!ballroomCode.equals(cb.getCard()))
                    continue;

                var owner = g.getPlayers().stream()
                        .filter(p -> p.getId().equals(cb.getPlayerId()))
                        .findFirst()
                        .orElse(null);

                if (owner == null)
                    continue;
                if (owner.getHp() <= 0)
                    continue; // mort → pas d'effet

                boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());

                if (!ownerIsVamp) {
                    boolean willFightHere = false;

                    if (unstableCombatOnBallroom.contains(owner.getId())) {
                        willFightHere = true;
                    } else {
                        if ("HUNTER".equals(owner.getRole())) {
                            boolean isHunterDefault = huntersDefaultOnBallroom.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isHunterDefault &&
                                    (!enemiesOnBallroom.isEmpty() || !monstersOnBallroom.isEmpty())) {
                                willFightHere = true;
                            }
                        } else if ("SERVANT".equals(owner.getRole())) {
                            boolean isServantEnemy = enemiesOnBallroom.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isServantEnemy && !huntersDefaultOnBallroom.isEmpty()) {
                                willFightHere = true;
                            }
                        }
                    }

                    if (willFightHere) {
                        continue;
                    }
                }

                Game.LocationEffectInstance inst = new Game.LocationEffectInstance();
                inst.ownerId = owner.getId();
                inst.infra = Infra.BALLROOM;
                inst.choice = null;
                g.getLocationEffectsQueue().add(inst);
            }
        }

        // --- Effets de autel ---
        if (hasAltar) {
            String altarCode = Infra.ALTAR.locationCode();

            var onAltar = groups.getOrDefault(altarCode, java.util.List.<Player>of());

            var enemiesOnAltar = onAltar.stream()
                    .filter(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                            && p.getHp() > 0)
                    .toList();

            var huntersDefaultOnAltar = onAltar.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .filter(p -> !unstableAssigned.contains(p.getId()))
                    .toList();

            var monstersOnAltar = monstersOnLocation(g, altarCode).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            java.util.Set<String> unstableCombatOnAltar = unstableCombatPlayersByLoc.getOrDefault(altarCode,
                    java.util.Set.of());

            for (var cb : g.getCenter()) {
                if (!altarCode.equals(cb.getCard()))
                    continue;

                var owner = g.getPlayers().stream()
                        .filter(p -> p.getId().equals(cb.getPlayerId()))
                        .findFirst()
                        .orElse(null);

                if (owner == null)
                    continue;
                if (owner.getHp() <= 0)
                    continue; // mort → pas d'effet de lieu
                // Sur l'autel : les serviteurs n'ont JAMAIS accès à l'effet de lieu
                if ("SERVANT".equals(owner.getRole())) {
                    continue;
                }

                boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());

                if (!ownerIsVamp) {
                    boolean willFightHere = false;

                    if (unstableCombatOnAltar.contains(owner.getId())) {
                        willFightHere = true;
                    } else {
                        if ("HUNTER".equals(owner.getRole())) {
                            boolean isHunterDefault = huntersDefaultOnAltar.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isHunterDefault &&
                                    (!enemiesOnAltar.isEmpty() || !monstersOnAltar.isEmpty())) {
                                willFightHere = true;
                            }
                        } else if ("SERVANT".equals(owner.getRole())) {
                            boolean isServantEnemy = enemiesOnAltar.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isServantEnemy && !huntersDefaultOnAltar.isEmpty()) {
                                willFightHere = true;
                            }
                        }
                    }

                    if (willFightHere) {
                        continue;
                    }
                }

                Game.LocationEffectInstance inst = new Game.LocationEffectInstance();
                inst.ownerId = owner.getId();
                inst.infra = Infra.ALTAR;
                inst.choice = null;
                g.getLocationEffectsQueue().add(inst);
            }
        }

        // --- Effets de la Forge ---
        if (hasForge) {
            String forgeCode = Infra.FORGE.locationCode(); // ex : "forge"

            var onForge = groups.getOrDefault(forgeCode, java.util.List.<Player>of());

            var enemiesOnForge = onForge.stream()
                    .filter(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                            && p.getHp() > 0)
                    .toList();

            var huntersDefaultOnForge = onForge.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .filter(p -> !unstableAssigned.contains(p.getId()))
                    .toList();

            var monstersOnForge = monstersOnLocation(g, forgeCode).stream()
                    .filter(m -> m.hp > 0)
                    .toList();

            java.util.Set<String> unstableCombatOnForge = unstableCombatPlayersByLoc.getOrDefault(forgeCode,
                    java.util.Set.of());

            for (var cb : g.getCenter()) {
                if (!forgeCode.equals(cb.getCard()))
                    continue;

                var owner = g.getPlayers().stream()
                        .filter(p -> p.getId().equals(cb.getPlayerId()))
                        .findFirst()
                        .orElse(null);

                if (owner == null)
                    continue;
                if (owner.getHp() <= 0)
                    continue; // mort → pas d'effet

                // Règle générique : chasseur/serviteur qui VA combattre sur ce lieu → pas
                // d'effet
                boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());

                if (!ownerIsVamp) {
                    boolean willFightHere = false;

                    if (unstableCombatOnForge.contains(owner.getId())) {
                        willFightHere = true;
                    } else {
                        if ("HUNTER".equals(owner.getRole())) {
                            boolean isHunterDefault = huntersDefaultOnForge.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isHunterDefault &&
                                    (!enemiesOnForge.isEmpty() || !monstersOnForge.isEmpty())) {
                                willFightHere = true;
                            }
                        } else if ("SERVANT".equals(owner.getRole())) {
                            boolean isServantEnemy = enemiesOnForge.stream()
                                    .anyMatch(p -> p.getId().equals(owner.getId()));
                            if (isServantEnemy && !huntersDefaultOnForge.isEmpty()) {
                                willFightHere = true;
                            }
                        }
                    }

                    if (willFightHere) {
                        continue;
                    }
                }

                // Règle spécifique Forge : si le joueur ne peut rien forger (aucun choix
                // dispo),
                // on NE l'ajoute PAS à la queue d'effets.
                if (!canForgeAnything(g, owner)) {
                    continue;
                }

                Game.LocationEffectInstance inst = new Game.LocationEffectInstance();
                inst.ownerId = owner.getId();
                inst.infra = Infra.FORGE;
                inst.choice = null;
                g.getLocationEffectsQueue().add(inst);
            }
        }
    }

    /**
     * Traite le choix d’effet de lieu pour l’instance en cours.
     *
     * - Vérifie que l’on est bien en PREPHASE3 et sur un effet en attente.
     * - Contrôle que le joueur appelant est le propriétaire de l’effet.
     * - Applique ou prépare l’effet selon le choix (STUDY / THEFT / OMEN, pour
     * LIBRARY).
     * - Marque l’effet comme interactif si une étape supplémentaire est nécessaire
     * (THEFT / OMEN), sinon enchaîne directement sur l’effet suivant.
     */
    @Transactional
    public Game chooseLocationEffect(String gameId, String playerId, LocationEffectChoice choice) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "location effect only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ce n'est pas votre effet de lieu.");
        }

        var p = findPlayer(g, playerId);

        // Flag générique : est-ce qu'on DOIT attendre une étape supplémentaire
        // avant de passer à l'effet suivant ?
        boolean waitForExtraResolution = false;

        // --- Branche selon l'infrastructure concernée ---
        if (inst.infra == Infra.LIBRARY) {
            // Pour l’instant: seulement LIBRARY gérée, avec les 3 effets.
            switch (choice) {
                case STUDY -> {
                    // effet immédiat
                    applyLibraryStudyEffect(g, p);
                }
                case THEFT -> {
                    // THEFT interactif : on vérifie d'abord s'il y a au moins une cible possible
                    if (!canUseLibraryTheft(g, p)) {
                        addHistory(g, "Bibliothèque — " + nameOf(g, p.getId())
                                + " tente de subtiliser un manuscrit, mais aucun adversaire ne possède de carte Action.");
                        // pas d'étape supplémentaire → on laissera enchaîner la file
                    } else {
                        // On va ouvrir la modale d'action côté front
                        waitForExtraResolution = true;
                    }
                }
                case OMEN -> {
                    // 1) Vérif serveur : deck adverse doit avoir > 3 cartes (en tenant compte du
                    // reshuffle)
                    if (!canUseLibraryOmen(g, p)) {
                        // On NE throw plus : on log et on consomme l'effet
                        addHistory(g, "Bibliothèque — " + nameOf(g, p.getId())
                                + " ne peut pas utiliser la Prédiction occulte "
                                + "(la pioche adverse contient 3 cartes ou moins).");
                        // pas d'étape supplémentaire → waitForExtraResolution reste false
                    } else {
                        // 2) Prépare les 3 cartes dans libraryOmenState (sans avancer la file)
                        int drawn = applyLibraryOmenEffect(g, p);

                        if (drawn > 0) {
                            // On a bien des cartes en attente → on attend /effect-omen
                            waitForExtraResolution = true;
                        } else {
                            // Cas très rare (course condition) : on n'a finalement rien pu préparer
                            addHistory(g, "Bibliothèque — " + nameOf(g, p.getId())
                                    + " tente une Prédiction occulte, mais aucune carte n'a pu être préparée.");
                        }
                    }
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid library choice");
            }
        } else if (inst.infra == Infra.LABORATORY) {
            switch (choice) {
                case EXPERIMENT -> {
                    if (!"VAMPIRE".equals(p.getRole())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Seul le vampire peut utiliser le Laboratoire occulte.");
                    }

                    // Vérif "est-ce qu'au moins une expérience est possible ?"
                    if (!canUseLaboratoryExperiment(g, p)) {
                        // IMPORTANT : on NE throw plus, on log et on consomme l'effet
                        addHistory(g, "Laboratoire occulte — " + nameOf(g, p.getId())
                                + " voudrait expérimenter, mais il n'a pas assez d'âmes pour invoquer une créature.");
                        // pas d'étape supplémentaire → waitForExtraResolution reste false
                    } else {
                        g.setLaboratoryDraftMonsterType(null);
                        g.setLaboratoryDraftLocation(null);

                        // Ici on NE crée PAS encore le monstre : on attend le POST /effect-experiment
                        waitForExtraResolution = true;
                    }
                }
                case ALCHEMY -> {
                    applyLaboratoryAlchemyEffect(g, p);
                }

                case RARE_ALCHEMY -> {
                    applyLaboratoryRareAlchemyEffect(g, p);
                }
                case EXPLOSION -> {
                    if (!"HUNTER".equals(p.getRole())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Seul un chasseur peut tenter de faire exploser le Laboratoire.");
                    }

                    // On prépare une résolution interactive (jet requis)
                    // (pas obligatoire, mais utile pour UI/anti double roll)
                    g.setLaboratoryExplosionRoll(null);

                    // IMPORTANT : on n'applique pas l'effet ici
                    // Et on n'avance pas la file d'effets ici : on attend
                    // resolveLaboratoryExplosion()
                    waitForExtraResolution = true;

                    addHistory(g, "Laboratoire occulte — " + nameOf(g, playerId)
                            + " tente de déclencher une explosion alchimique… (jet de d20 requis).");
                }
            }
        } else if (inst.infra == Infra.BALLROOM) {
            switch (choice) {
                case DEATH_DANCE -> {
                    if (!"VAMPIRE".equals(p.getRole())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Seul le vampire peut utiliser la Salle de bal pour la Danse macabre.");
                    }

                    g.setBallroomDeathDance(true);

                    addHistory(g, "Salle de bal — " + nameOf(g, p.getId())
                            + " déclenche la Danse macabre : chaque attaque réussie "
                            + "contre un chasseur sur ce lieu lui infligera aussi 1 point de corruption.");
                }
                case SNEAK_ATTACK -> {
                    g.setBallroomSneakAttack(true);
                    addHistory(g, "Salle de bal — "
                            + nameOf(g, p.getId())
                            + " se prépare pour une attaque sournoise.");
                }
                case BLOOD_WALTZ -> {
                    g.setBallroomBloodWaltz(true);
                    g.setBallroomBloodWaltzBestRoll(null);
                    g.setBallroomBloodWaltzRolls(new java.util.ArrayList<>());

                    addHistory(g, "Salle de bal — "
                            + nameOf(g, p.getId())
                            + " prépare une Valse sanguinaire pour ce raid.");
                }
                case LOOTING -> {
                    if (!"HUNTER".equals(p.getRole())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Seuls les chasseurs peuvent utiliser l'effet Pillage.");
                    }

                    resolveHunterPillage(g, inst);
                }

                default -> throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "invalid ballroom choice");
            }
        } else if (inst.infra == Infra.ALTAR) {
            boolean isVamp = "VAMPIRE".equals(p.getRole());
            boolean isHunter = "HUNTER".equals(p.getRole());

            boolean corrupted = isAltarCorrupted(g);

            if (!corrupted) {
                // === ÉTAT pur ===
                switch (choice) {
                    case HEAL -> {
                        if (!isHunter) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Seul un chasseur peut utiliser l'autel pour apaiser la corruption.");
                        }

                        if (!canUseAltarHeal(g, p)) {
                            addHistory(g, "Autel — "
                                    + nameOf(g, p.getId())
                                    + " voudrait apaiser la corruption d'un chasseur, "
                                    + "mais aucun chasseur n'est corrompu.");
                            // pas d'étape interactive
                        } else {
                            // on attend /resolve-altar-heal pour choisir la cible
                            waitForExtraResolution = true;
                        }
                    }

                    case CORRUPT_SOULS -> {
                        if (!isVamp) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Seul le vampire peut corrompre l'autel avec des âmes.");
                        }

                        applyAltarCorruptWithSouls(g, p);
                    }

                    default -> throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "invalid altar choice in altar state");
                }
            } else {
                // === ÉTAT corrompu ===
                switch (choice) {
                    case CORRUPT -> {
                        if (!isVamp) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Seul le vampire peut exploiter l'autel pour corrompre un chasseur.");
                        }

                        if (!canUseAltarCorrupt(g, p)) {
                            addHistory(g, "Autel sanglant — "
                                    + nameOf(g, p.getId())
                                    + " voudrait corrompre un chasseur, mais aucune cible valide n'est présente.");
                            // pas d'étape interactive
                        } else {
                            // étape interactive : choisir quel chasseur gagne +1 corruption
                            waitForExtraResolution = true;
                        }
                    }

                    case PURIFY_WATER -> {
                        if (!isHunter) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Seul un chasseur peut purifier l'autel avec de l'eau bénite.");
                        }

                        applyAltarPurifyWithHolyWater(g, p);
                    }

                    default -> throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "invalid sanctualtar choice in altar state");
                }
            }
        } else if (inst.infra == Infra.FORGE) {
            // Pour l’instant : un seul "type" de choix au niveau de l’infra :
            // utiliser la Forge. Le détail (arme/armure/quel item) sera dans un
            // endpoint dédié, comme pour EXPERIMENT ou HEAL.

            if (choice != LocationEffectChoice.FORGE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid forge choice");
            }

            if (!canForgeAnything(g, p)) {
                addHistory(g, "Forge — " + nameOf(g, p.getId())
                        + " voudrait fabriquer un équipement, mais il n'a pas assez de ressources.");
                // Pas d'étape interactive, on avance simplement la file
                // (waitForExtraResolution reste false).
            } else {
                // Étape interactive : le front affichera la liste des équipements permis
                // en se basant sur gameSnapshot (weapon/armor, ressources, etc.)
                // puis appellera un endpoint /effect-forge avec le code choisi.
                waitForExtraResolution = true;
            }
        } else {
            // S'il y a d'autres infras plus tard, tu pourras les gérer ici
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "unknown location effect infra: " + inst.infra);
        }

        // On mémorise le choix pour l'effet courant
        inst.choice = choice;
        g.setLocationEffectChoice(choice); // pour affichage côté front

        save(g);

        final LocationEffectChoice fChoice = choice;
        final boolean fWaitExtra = waitForExtraResolution;

        afterCommit(() -> {
            Game fresh = findOr404(gameId);
            Player freshOwner = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            // Notifie le front que l'effet a été choisi (ouvre la modale correspondante)
            live.locationEffectUsed(fresh, fChoice, freshOwner);

            // STUDY = immédiat ; THEFT/OMEN/EXPERIMENT interactifs → on attend la
            // résolution dédiée
            if (!fWaitExtra) {
                scheduleNextLocationEffect(fresh.getId());
            }
        });

        return g;
    }

    /**
     * Effet de Bibliothèque : STUDY.
     *
     * Fait piocher 1 carte Action au joueur (dans son deck de camp)
     * et l’ajoute à sa main, si possible. Ajoute aussi un message dans
     * l’historique.
     */
    private void applyLibraryStudyEffect(Game g, Player user) {
        boolean isVamp = "VAMPIRE".equals(user.getRole());

        String cardId;
        if (isVamp) {
            cardId = drawVampAction(g);
        } else {
            cardId = drawHunterAction(g);
        }

        if (cardId == null) {
            addHistory(g, "Bibliothèque — " + nameOf(g, user.getId())
                    + " n'a pas pu piocher (pioche Action vide).");
            return;
        }

        if (user.getActions() == null) {
            user.setActions(new java.util.ArrayList<>());
        }
        user.getActions().add(cardId);

        String who = nameOf(g, user.getId());
        addHistory(g, "Bibliothèque — " + who
                + " étudie les grimoires et pioche 1 carte Action.");
    }

    /**
     * Effet de Bibliothèque : THEFT — application concrète du vol.
     *
     * - Retire une carte Action dans la main de la cible (slotIndex).
     * - Replace cette carte dans le deck d’Actions du camp de la cible
     * (côté adverse du lanceur), puis mélange le deck.
     * - Log l’action dans l’historique.
     *
     * Les validations (cible, index, rôles, phase…) sont supposées faites en amont.
     */
    private void applyLibraryTheftEffect(Game g, Player owner, Player target, int slotIndex) {
        var hand = target.getActions();
        if (hand == null || hand.isEmpty()) {
            throw new IllegalStateException("target has no action cards");
        }
        if (slotIndex < 0 || slotIndex >= hand.size()) {
            throw new IllegalStateException("invalid slot index");
        }

        // 1) On enlève la carte choisie
        String stolen = hand.remove(slotIndex);

        // 2) On remet la carte dans le DECK de la cible (camp adverse)
        List<String> deck;
        boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());

        if (ownerIsVamp) {
            // Vampire vole une carte à un chasseur → carte remise dans le deck Actions
            // CHASSEURS
            deck = g.getHunterActionsDeck();
            if (deck == null) {
                deck = new java.util.ArrayList<>();
                g.setHunterActionsDeck(deck);
            }
        } else {
            // Chasseur vole une carte au vampire → carte remise dans le deck Actions
            // VAMPIRE
            deck = g.getVampActionsDeck();
            if (deck == null) {
                deck = new java.util.ArrayList<>();
                g.setVampActionsDeck(deck);
            }
        }

        deck.add(stolen);
        dice.shuffle(deck);

        // 3) Historique
        String who = nameOf(g, owner.getId());
        String who2 = nameOf(g, target.getId());
        addHistory(g, "Bibliothèque — " + who
                + " subtilise un manuscrit à " + who2
                + " et le replace dans la pioche Action.");
    }

    private boolean canUseLibraryTheft(Game g, Player user) {
        boolean isVamp = "VAMPIRE".equals(user.getRole());
        boolean isHunter = "HUNTER".equals(user.getRole());

        if (isVamp) {
            // Au moins un chasseur vivant avec ≥1 carte Action
            return g.getPlayers().stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)
                    .anyMatch(p -> p.getActions() != null && !p.getActions().isEmpty());
        }

        if (isHunter) {
            // Cible unique = vampire
            var vampOpt = getVamp(g);
            if (vampOpt.isEmpty())
                return false;
            Player vamp = vampOpt.get();
            if (vamp.getHp() <= 0)
                return false;
            return vamp.getActions() != null && !vamp.getActions().isEmpty();
        }

        return false;
    }

    /**
     * Résout l’effet interactif de Bibliothèque THEFT côté serveur.
     *
     * - Valide le contexte (phase, effet en cours, propriétaire, cible, index).
     * - Applique le vol via applyLibraryTheftEffect().
     * - Sauvegarde la partie, notifie le front et enchaîne sur
     * le prochain effet de lieu via scheduleNextLocationEffect().
     */
    @Transactional
    public Game resolveLibraryTheft(String gameId, String playerId, String targetId, int slotIndex) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "theft only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        // On ne résout que THEFT (Bibliothèque)
        if (inst.infra != Infra.LIBRARY || inst.choice != LocationEffectChoice.THEFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no theft effect to resolve");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your theft effect");
        }

        var owner = findPlayer(g, playerId);
        var target = findPlayer(g, targetId);

        if (target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid target");
        }

        boolean ownerIsVamp = "VAMPIRE".equals(owner.getRole());
        boolean ownerIsHunter = "HUNTER".equals(owner.getRole());

        if (!ownerIsVamp && !ownerIsHunter) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid role for theft");
        }

        if (ownerIsVamp && !"HUNTER".equals(target.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "vampire must target a hunter");
        }
        if (ownerIsHunter && !"VAMPIRE".equals(target.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "hunter must target the vampire");
        }

        if (target.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target is dead");
        }

        var hand = target.getActions();
        if (hand == null || hand.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target has no action cards");
        }

        if (slotIndex < 0 || slotIndex >= hand.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid slot index");
        }

        // ---- Ici on applique enfin l'effet ----
        applyLibraryTheftEffect(g, owner, target, slotIndex);

        save(g);

        afterCommit(() -> {
            Game fresh = findOr404(gameId);
            Player freshOwner = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            // Effet complètement résolu (THEFT)
            live.locationEffectUsed(fresh, LocationEffectChoice.THEFT, freshOwner);
            scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    /**
     * Prédiction occulte :
     * - prépare libraryOmenState avec jusqu'à 3 cartes du deck adverse
     * - ne fait PAS avancer la file d'effets (résolution en 2 temps).
     * 
     * @return nombre de cartes réellement préparées (0 si impossibilité)
     */
    private int applyLibraryOmenEffect(Game g, Player user) {
        boolean isVamp = "VAMPIRE".equals(user.getRole());

        List<String> deck = isVamp ? g.getHunterActionsDeck() : g.getVampActionsDeck();
        List<String> discard = isVamp ? g.getHunterActionsDiscard() : g.getVampActionsDiscard();

        if (deck == null) {
            deck = new java.util.ArrayList<>();
            if (isVamp)
                g.setHunterActionsDeck(deck);
            else
                g.setVampActionsDeck(deck);
        }
        if (discard == null) {
            discard = new java.util.ArrayList<>();
            if (isVamp)
                g.setHunterActionsDiscard(discard);
            else
                g.setVampActionsDiscard(discard);
        }

        // Si deck vide mais discard non vide → on recrée le deck maintenant
        if (deck.isEmpty() && !discard.isEmpty()) {
            dice.shuffle(discard);
            deck.addAll(discard);
            discard.clear();
        }

        Game.LibraryOmenState state = new Game.LibraryOmenState();
        state.ownerId = user.getId();
        state.targetSide = isVamp ? "HUNTERS" : "VAMPIRE";
        state.cards = new java.util.ArrayList<>();

        for (int i = 0; i < 3; i++) {
            String c = drawFromDeck(deck, discard);
            if (c == null)
                break;
            state.cards.add(c);
        }

        if (state.cards.isEmpty()) {
            // Rien à prévisualiser
            g.setLibraryOmenState(null);
            return 0;
        }

        g.setLibraryOmenState(state);
        return state.cards.size(); // normalement 3
    }

    private boolean canUseLibraryOmen(Game g, Player user) {
        boolean isVamp = "VAMPIRE".equals(user.getRole());

        List<String> deck = isVamp ? g.getHunterActionsDeck() : g.getVampActionsDeck();
        List<String> discard = isVamp ? g.getHunterActionsDiscard() : g.getVampActionsDiscard();

        int available = deckAvailableSize(deck, discard);

        // OMEN interdit si 3 cartes ou moins "piochables"
        return available > 3;
    }

    /**
     * Résout l’effet interactif de Bibliothèque OMEN côté serveur.
     *
     * - Utilise l’état temporaire libraryOmenState préparé auparavant.
     * - Replace chaque carte préparée en haut ou en bas du deck adverse
     * selon la liste placements.
     * - Ajoute un message d’historique, nettoie l’état OMEN,
     * sauvegarde et enchaîne sur le prochain effet de lieu.
     */
    @Transactional
    public Game resolveLibraryOmen(String gameId, String playerId, java.util.List<String> placements) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "omen only in PREPHASE3");

        var omen = g.getLibraryOmenState();
        if (omen == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no omen pending");

        if (!playerId.equals(omen.ownerId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your omen");

        if (omen.cards == null || omen.cards.isEmpty()) {
            g.setLibraryOmenState(null);
            save(g);
            return g;
        }

        if (placements == null || placements.size() != omen.cards.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid placements");

        boolean targetIsVamp = "VAMPIRE".equals(omen.targetSide);
        List<String> deck = targetIsVamp ? g.getVampActionsDeck() : g.getHunterActionsDeck();
        List<String> discard = targetIsVamp ? g.getVampActionsDiscard() : g.getHunterActionsDiscard();

        if (deck == null) {
            deck = new java.util.ArrayList<>();
            if (targetIsVamp)
                g.setVampActionsDeck(deck);
            else
                g.setHunterActionsDeck(deck);
        }
        if (discard == null) {
            discard = new java.util.ArrayList<>();
            if (targetIsVamp)
                g.setVampActionsDiscard(discard);
            else
                g.setHunterActionsDiscard(discard);
        }

        for (int i = 0; i < omen.cards.size(); i++) {
            String cardId = omen.cards.get(i);
            String where = placements.get(i);

            if ("BOTTOM".equalsIgnoreCase(where)) {
                putOnBottom(deck, cardId);
            } else {
                putOnTop(deck, cardId);
            }
        }

        String who = nameOf(g, playerId);
        addHistory(g, "Bibliothèque — " + who
                + " manipule secrètement le futur des cartes d'action (Prédiction occulte).");

        g.setLibraryOmenState(null);

        save(g);

        afterCommit(() -> {
            Game fresh = findOr404(gameId);
            Player freshOwner = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            // Effet complètement résolu
            live.locationEffectUsed(fresh, LocationEffectChoice.OMEN, freshOwner);
            scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    /**
     * Effet de Laboratoire occulte : EXPERIMENT.
     *
     * - Consomme un certain nombre d'âmes sur le vampire
     * en fonction du type de monstre.
     * - Crée le monstre et l'ajoute à g.getMonsters().
     * - Ajoute un message d'historique.
     *
     * Les validations "générales" (phase, propriétaire, etc.)
     * sont faites dans resolveLaboratoryExperiment().
     */
    private void applyLaboratoryExperimentEffect(Game g,
            Player vamp,
            Game.MonsterType type,
            String location) {

        if (!"VAMPIRE".equals(vamp.getRole())) {
            throw new IllegalStateException("only vampire can experiment");
        }

        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("monster location required");
        }

        // --- Coût et stats du monstre selon le type ---
        int soulsCost;
        int hp;
        String atkDice;
        String defDice;

        switch (type) {
            case REVENANT -> {
                soulsCost = 100;
                hp = 5;
                atkDice = "D6";
                defDice = "D4";
            }
            case BAT -> {
                soulsCost = 100;
                hp = 5;
                atkDice = "D4";
                defDice = "D6";
            }
            case GARGOYLE -> {
                soulsCost = 400;
                hp = 10;
                atkDice = "D6";
                defDice = "D8";
            }
            case WOLF -> {
                soulsCost = 400;
                hp = 10;
                atkDice = "D8";
                defDice = "D6";
            }
            case ABERRATION -> {
                soulsCost = 600;
                hp = 15;
                atkDice = "D8";
                defDice = "D8";
            }
            case LICHE -> {
                soulsCost = 600;
                hp = 10;
                atkDice = "D8";
                defDice = "D8";
            }
            default -> throw new IllegalArgumentException("unsupported monster type: " + type);
        }

        // Par sécurité : ne devrait pas arriver si canUseLaboratoryExperiment a été
        // testé
        if (vamp.getSouls() < soulsCost) {
            throw new IllegalStateException("not enough souls for " + type);
        }

        // Paiement
        vamp.setSouls(vamp.getSouls() - soulsCost);

        // Création du monstre
        if (g.getMonsters() == null) {
            g.setMonsters(new java.util.ArrayList<>());
        }

        Game.Monster m = new Game.Monster();
        m.id = java.util.UUID.randomUUID().toString();
        m.type = type;
        m.location = location;
        m.hp = hp;
        m.attackDice = atkDice;
        m.defenseDice = defDice;

        g.getMonsters().add(m);

        // Historique
        String who = nameOf(g, vamp.getId());
        addHistory(g, "Laboratoire occulte — " + who
                + " engendre une " + monsterNameFr(type)
                + " pour défendre " + location + ".");
    }

    private boolean canUseLaboratoryExperiment(Game g, Player user) {
        // Seul le vampire peut expérimenter
        if (!"VAMPIRE".equals(user.getRole())) {
            return false;
        }

        return user.getSouls() >= 100;
    }

    @Transactional
    public void updateLaboratoryExperimentDraft(String gameId,
            String playerId,
            Game.MonsterType type,
            String location) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "experiment only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        if (inst.infra != Infra.LABORATORY || inst.choice != LocationEffectChoice.EXPERIMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no experiment effect to draft");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your laboratory effect");
        }

        var vamp = findPlayer(g, playerId);
        if (!"VAMPIRE".equals(vamp.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only vampire can experiment");
        }

        // normalize : blank => null
        String loc = (location != null && !location.isBlank()) ? location : null;

        // stocke le draft
        g.setLaboratoryDraftMonsterType(type);
        g.setLaboratoryDraftLocation(loc);

        save(g);

        afterCommit(() -> {
            Game fresh = findOr404(gameId);

            // déclencher un event WS qui force les clients à refresh snapshot
            live.draftUpdated(fresh);

        });
    }

    @Transactional
    public Game resolveLaboratoryExperiment(String gameId,
            String playerId,
            Game.MonsterType type,
            String location) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "experiment only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        // On ne résout que EXPERIMENT pour LABORATORY
        if (inst.infra != Infra.LABORATORY || inst.choice != LocationEffectChoice.EXPERIMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no experiment effect to resolve");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your laboratory effect");
        }

        var vamp = findPlayer(g, playerId);
        if (!"VAMPIRE".equals(vamp.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only vampire can experiment");
        }

        if (type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "monster type required");
        }

        if (location == null || location.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid monster location");
        }

        // --- Restriction Raid ---
        int currentRaid = g.getRaid();
        if (type == Game.MonsterType.GARGOYLE || type == Game.MonsterType.WOLF) {
            if (currentRaid < 5) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Gargouille/Loup débloqués au raid 5 (Raid actuel: " + currentRaid + ")");
            }
        }
        if (type == Game.MonsterType.ABERRATION || type == Game.MonsterType.LICHE) {
            if (currentRaid < 10) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Liche/Aberration débloqués au raid 10 (Raid actuel: " + currentRaid + ")");
            }
        }

        // ---- Ici on applique enfin l'effet, comme pour
        // applyLibraryTheftEffect/applyLibraryOmenEffect ----
        applyLaboratoryExperimentEffect(g, vamp, type, location);

        save(g);

        afterCommit(() -> {
            Game fresh = findOr404(gameId);
            Player freshVamp = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            // Effet complètement résolu (EXPERIMENT)
            live.locationEffectUsed(fresh, LocationEffectChoice.EXPERIMENT, freshVamp);
            scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    private String monsterNameFr(Game.MonsterType type) {
        return switch (type) {
            case REVENANT -> "Revenant";
            case BAT -> "Chauve-souris";
            case GARGOYLE -> "Gargouille";
            case WOLF -> "Loup";
            case ABERRATION -> "Aberration";
            case LICHE -> "Liche";
        };
    }

    private String entityName(Game g, String id) {
        Player p = findPlayer(g, id);
        if (p != null) {
            return nameOf(g, p.getId()); // ton helper existant (username, etc.)
        }
        Game.Monster m = findMonster(g, id);
        if (m != null) {
            return monsterNameFr(m.type);
        }
        return id;
    }

    private List<Game.Monster> monstersOnLocation(Game g, String loc) {
        if (g.getMonsters() == null || loc == null) {
            return List.of();
        }
        return g.getMonsters().stream()
                .filter(m -> loc.equals(m.location))
                .toList();
    }

    private Game.Monster findMonster(Game g, String monsterId) {
        if (g.getMonsters() == null || monsterId == null) {
            return null;
        }
        return g.getMonsters().stream()
                .filter(m -> monsterId.equals(m.id))
                .findFirst()
                .orElse(null);
    }

    private boolean isEntityOnLocation(Game g, String entityId, String loc) {
        Player p = findPlayer(g, entityId);
        if (p != null) {
            String pLoc = locationOf(g, p.getId());
            return loc.equals(pLoc);
        }
        Game.Monster m = findMonster(g, entityId);
        if (m != null) {
            return loc.equals(m.location);
        }
        return false;
    }

    private boolean hasTrapTargetsOnLocation(Game g, String loc) {
        // Joueurs côté vampire/serviteurs déjà gérés par ta fonction existante
        var enemies = vampSideOnLocation(g, loc);
        boolean hasPlayers = enemies != null && !enemies.isEmpty();

        boolean hasMonsters = g.getMonsters() != null
                && g.getMonsters().stream().anyMatch(m -> loc.equals(m.location));

        return hasPlayers || hasMonsters;
    }

    private int rollMonsterAttack(Game.Monster m) {
        int sides = diceSides(m.attackDice); // même helper que pour les joueurs
        if (sides <= 0)
            return 0;
        return dice.roll(sides);
    }

    private int rollMonsterDefense(Game.Monster m) {
        int sides = diceSides(m.defenseDice);
        if (sides <= 0)
            return 0;
        return dice.roll(sides);
    }

    /**
     * Laboratoire : Alchimie simple (ALCHEMY).
     * - Le vampire gagne 1 potion commune gratuite.
     */
    private void applyLaboratoryAlchemyEffect(Game g, Player user) {
        String cardId = drawPotion(g);
        if (cardId == null) {
            addHistory(g, "Laboratoire occulte — " + nameOf(g, user.getId())
                    + " tente de préparer une potion, mais la réserve de potions communes est épuisée.");
            return;
        }

        if (user.getPotions() == null) {
            user.setPotions(new java.util.ArrayList<>());
        }
        user.getPotions().add(cardId);

        addHistory(g, "Laboratoire occulte — " + nameOf(g, user.getId())
                + " distille une potion alchimique.");
    }

    /**
     * Laboratoire : Alchimie rare (RARE_ALCHEMY).
     * - Coût : 6 eau + 6 herbes
     * - Pioche 1 potion rare depuis le deck rare.
     */
    private void applyLaboratoryRareAlchemyEffect(Game g, Player player) {
        // Vérification ressources
        if (player.getWater() < 6 || player.getHerbs() < 6) {
            addHistory(g, "Laboratoire occulte — " + nameOf(g, player.getId())
                    + " voudrait préparer une potion rare, mais manque d'ingrédients (6 eau, 6 herbes).");
            return;
        }

        // Paiement
        player.setWater(player.getWater() - 6);
        player.setHerbs(player.getHerbs() - 6);

        String cardId = drawElixir(g);
        if (cardId == null) {
            addHistory(g, "Laboratoire occulte — " + nameOf(g, player.getId())
                    + " consacre des ingrédients à une potion rare, mais la réserve de potions rares est épuisée.");
            return;
        }

        if (player.getElixirs() == null) {
            player.setElixirs(new java.util.ArrayList<>());
        }
        player.getElixirs().add(cardId);

        addHistory(g, "Laboratoire occulte — " + nameOf(g, player.getId())
                + " prépare une potion rare d'alchimie.");
    }

    @Transactional
    public Game resolveLaboratoryExplosion(String gameId, String playerId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "explosion only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        // On ne résout que EXPLOSION pour LABORATORY
        if (inst.infra != Infra.LABORATORY || inst.choice != LocationEffectChoice.EXPLOSION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no explosion effect to resolve");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your laboratory effect");
        }

        var hunter = findPlayer(g, playerId);
        if (hunter == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid player");
        }
        if (!"HUNTER".equals(hunter.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only hunter can roll explosion");
        }

        // Anti double roll
        if (g.getLaboratoryExplosionRoll() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "explosion already resolved");
        }

        int roll = dice.roll(20);
        g.setLaboratoryExplosionRoll(roll);

        String who = nameOf(g, hunter.getId());

        if (roll >= 15) {
            addHistory(g, "Laboratoire occulte — " + who
                    + " jette un d20 (" + roll + ") : explosion réussie !");
            applyLaboratoryExplosionEffect(g, hunter);
        } else {
            int before = hunter.getHp();
            int after = Math.max(0, before - 2);
            hunter.setHp(after);

            addHistory(g, "Laboratoire occulte — " + who
                    + " jette un d20 (" + roll + ") : échec. Il subit 2 dégâts (" + before + " → " + after + ").");

            // si tu as un helper pour gérer mort/conséquences, appelle-le ici si after == 0
        }

        save(g);

        afterCommit(() -> {
            Game fresh = findOr404(gameId);
            Player freshOwner = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            // Effet complètement résolu (EXPLOSION)
            live.locationEffectUsed(fresh, LocationEffectChoice.EXPLOSION, freshOwner);
            scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    /**
     * Effet de Laboratoire : EXPLOSION.
     *
     * - Utilisable uniquement par un chasseur.
     * - Immédiat en PREPHASE3 : le vampire perd 1 ressource aléatoire (si possible)
     * et 20 âmes déchues.
     * - La destruction effective du Laboratoire est différée : elle sera appliquée
     * à la fin des combats, juste avant de passer en PHASE4 (via
     * laboratoryToDestroy).
     */
    private void applyLaboratoryExplosionEffect(Game g, Player hunter) {
        if (!"HUNTER".equals(hunter.getRole())) {
            throw new IllegalStateException("only hunter can trigger lab explosion");
        }

        var vampOpt = getVamp(g);
        if (vampOpt.isEmpty()) {
            return;
        }
        Player vamp = vampOpt.get();

        // 1) Ressource aléatoire à -1 (si possible)
        List<String> resourceKeys = new ArrayList<>();
        if (vamp.getWood() > 0)
            resourceKeys.add("wood");
        if (vamp.getHerbs() > 0)
            resourceKeys.add("herbs");
        if (vamp.getStone() > 0)
            resourceKeys.add("stone");
        if (vamp.getIron() > 0)
            resourceKeys.add("iron");
        if (vamp.getWater() > 0)
            resourceKeys.add("water");

        String lostResKey = null;
        if (!resourceKeys.isEmpty()) {
            lostResKey = resourceKeys.get(dice.nextInt(resourceKeys.size()));
            switch (lostResKey) {
                case "wood" -> vamp.setWood(vamp.getWood() - 1);
                case "herbs" -> vamp.setHerbs(vamp.getHerbs() - 1);
                case "stone" -> vamp.setStone(vamp.getStone() - 1);
                case "iron" -> vamp.setIron(vamp.getIron() - 1);
                case "water" -> vamp.setWater(vamp.getWater() - 1);
            }
        }

        // 2) Perte de 20 âmes (ou moins si pas assez)
        int soulsBefore = vamp.getSouls();
        int soulsLost = 20;
        if (soulsBefore >= 20) {
            vamp.setSouls(soulsBefore - soulsLost);
        } else {
            soulsLost = soulsBefore;
            vamp.setSouls(0);
        }

        String hunterName = nameOf(g, hunter.getId());
        String vampName = nameOf(g, vamp.getId());

        if (lostResKey != null && soulsLost > 0) {
            addHistory(g, "Laboratoire occulte — " + hunterName
                    + " provoque l'explosion du laboratoire : " + vampName
                    + " perd 1 " + resLabelFr(lostResKey)
                    + " et " + soulsLost + " âmes déchues.");
        } else if (lostResKey != null) {
            addHistory(g, "Laboratoire occulte — " + hunterName
                    + " provoque l'explosion du laboratoire : " + vampName
                    + " perd 1 " + resLabelFr(lostResKey) + ".");
        } else if (soulsLost > 0) {
            addHistory(g, "Laboratoire occulte — " + hunterName
                    + " provoque l'explosion du laboratoire : " + vampName
                    + " perd " + soulsLost + " âmes déchues.");
        } else {
            addHistory(g, "Laboratoire occulte — " + hunterName
                    + " provoque l'explosion du laboratoire, mais " + vampName
                    + " n'avait plus de ressources à perdre.");
        }

        // 3) On marque que le labo devra être détruit après les combats
        g.setLaboratoryToDestroy(true);
    }

    private void destroyRaidInfrasAtEnd(Game g) {
        // 1) Construire l'ensemble des infras à détruire
        java.util.EnumSet<Infra> toDestroy = java.util.EnumSet.noneOf(Infra.class);

        if (g.getInfrasToDestroyEndOfRaid() != null) {
            toDestroy.addAll(g.getInfrasToDestroyEndOfRaid());
        }
        if (g.isLaboratoryToDestroy()) {
            toDestroy.add(Infra.LABORATORY);
        }

        if (toDestroy.isEmpty()) {
            return;
        }

        // 2) Retirer les cartes de lieux correspondantes de la main des joueurs
        for (var p : g.getPlayers()) {
            var hand = p.getHand();

            for (Infra infra : toDestroy) {
                String locCode = infra.locationCode();
                if (locCode == null)
                    continue;
                hand.removeIf(card -> card.equals(locCode));
            }
        }

        // 3) Enlever les infras construites + effets permanents associés
        if (g.getBuiltInfras() != null) {
            for (Infra infra : toDestroy) {
                if (!g.getBuiltInfras().contains(infra))
                    continue;

                g.getBuiltInfras().remove(infra);

                String locCode = infra.locationCode();
                String label = (locCode != null ? labelLieuFr(locCode) : infra.name());

                switch (infra) {
                    case LABORATORY -> {
                        // Explosion du labo / destruction via Incendiaire
                        addHistory(g, "Laboratoire occulte — le laboratoire est réduit en ruines.");
                    }
                    case BALLROOM -> {
                        g.setBallroomDeathDance(false);
                        g.setBallroomSneakAttack(false);
                        g.setBallroomBloodWaltz(false);
                        g.setBallroomBloodWaltzBestRoll(null);
                        g.setBallroomBloodWaltzRolls(new java.util.ArrayList<>());
                        addHistory(g, label + " est détruit par les flammes.");
                    }
                    case ALTAR -> {
                        g.setAltarCorrupted(null);
                        g.setAltarBiteOccurredThisRaid(false);
                        g.setAltarVampTookDamageThisRaid(false);
                        addHistory(g, "L'autel est réduit en cendres.");
                    }
                    case SAWMILL, MINE, LIBRARY, FORGE -> {
                        addHistory(g, label + " est détruit par les flammes.");
                    }
                    default -> {
                        addHistory(g, label + " est détruit.");
                    }
                }
            }
        }

        // 4) Reset des flags pour le prochain raid
        if (g.getInfrasToDestroyEndOfRaid() != null) {
            g.getInfrasToDestroyEndOfRaid().clear();
        }
        g.setLaboratoryToDestroy(false);
    }

    private boolean isBallroomBloodWaltzAttack(Game g,
            RoundFight r,
            String userId,
            Player attackerPlayer0,
            Player defenderPlayer0) {
        if (!g.isBallroomBloodWaltz())
            return false;
        if (attackerPlayer0 == null || defenderPlayer0 == null)
            return false;
        if (!"VAMPIRE".equals(attackerPlayer0.getRole()))
            return false;
        if (!"HUNTER".equals(defenderPlayer0.getRole()))
            return false;
        if (!userId.equals(r.getAttackerId()))
            return false;

        if (g.getBuiltInfras() == null || !g.getBuiltInfras().contains(Infra.BALLROOM))
            return false;

        String ballroomCode = Infra.BALLROOM.locationCode();
        return ballroomCode != null && ballroomCode.equals(r.getLocation());
    }

    /**
     * Effet de lieu : Pillage
     * - utilisable uniquement par un chasseur
     * - donne 1 jet de récolte d'or (D100×10) au chasseur qui a choisi l'effet
     */
    private void resolveHunterPillage(Game g, Game.LocationEffectInstance eff) {
        // 1) Récupérer le propriétaire de l'effet
        Player owner = g.getPlayers().stream()
                .filter(p -> p.getId().equals(eff.ownerId))
                .findFirst()
                .orElse(null);

        if (owner == null) {
            // Sécurité : si jamais l'instance est cassée, on log et on sort
            addHistory(g, "Pillage — effet sans propriétaire valide.");
            return;
        }

        // 2) Vérifier que c'est bien un chasseur vivant
        if (!"HUNTER".equals(owner.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Seuls les chasseurs peuvent déclencher Pillage.");
        }
        if (owner.getHp() <= 0) {
            addHistory(g, "Pillage — " + nameOf(g, owner.getId())
                    + " est à terre et ne peut pas piller.");
            return;
        }

        // 3) Vérifier qu'il est bien sur le lieu de l'effet (optionnel mais propre)
        String locCode = (eff.infra != null ? eff.infra.locationCode() : null);
        if (locCode != null) {
            String currentLoc = locationOf(g, owner.getId());
            if (!locCode.equals(currentLoc)) {
                // Au cas où : l’effet est mal configuré / joueur déplacé.
                addHistory(g, "Pillage — " + nameOf(g, owner.getId())
                        + " n'est plus sur " + labelLieuFr(locCode)
                        + " : aucun butin supplémentaire.");
                return;
            }
        }

        // 4) Jet d'or garanti pour CE chasseur uniquement
        int roll = rollD100Tens();
        grant(owner, "gold", roll);

        String locLabel = (locCode != null ? labelLieuFr(locCode) : "ce lieu");
        addHistory(g, "Pillage — " + nameOf(g, owner.getId())
                + " obtient +" + roll + " or sur " + locLabel + ".");
    }

    private static final int ALTAR_SOULS_COST_TO_CORRUPT = 30;

    private boolean isAltarBuilt(Game g) {
        return g.getBuiltInfras() != null && g.getBuiltInfras().contains(Infra.ALTAR);
    }

    private boolean isAltarCorrupted(Game g) {
        // si jamais null -> on considère "corrupted" par défaut
        return Boolean.TRUE.equals(g.getAltarCorrupted());
    }

    private void resetAltarRaidFlags(Game g) {
        g.setAltarBiteOccurredThisRaid(false);
        g.setAltarVampTookDamageThisRaid(false);
    }

    private boolean canUseAltarHeal(Game g, Player user) {
        if (!"HUNTER".equals(user.getRole()))
            return false;

        return g.getPlayers().stream()
                .filter(p -> "HUNTER".equals(p.getRole()))
                .filter(p -> p.getHp() > 0)
                .anyMatch(p -> p.getCorruption() > 0);
    }

    private boolean canUseAltarCorrupt(Game g, Player user) {
        if (!"VAMPIRE".equals(user.getRole()))
            return false;

        return g.getPlayers().stream()
                .filter(p -> "HUNTER".equals(p.getRole()))
                .anyMatch(p -> p.getHp() > 0);
    }

    private void applyAltarCorruptWithSouls(Game g, Player vamp) {
        if (!"VAMPIRE".equals(vamp.getRole())) {
            addHistory(g, "Autel — "
                    + nameOf(g, vamp.getId())
                    + " tente de corrompre l'autel, mais ce n'est pas le vampire.");
            return;
        }

        if (isAltarCorrupted(g)) {
            addHistory(g, "Autel — "
                    + nameOf(g, vamp.getId())
                    + " tente de corrompre un autel déjà profané.");
            return;
        }

        if (vamp.getSouls() < ALTAR_SOULS_COST_TO_CORRUPT) {
            addHistory(g, "Autel — "
                    + nameOf(g, vamp.getId())
                    + " n'a pas assez d'âmes déchues pour profaner l'autel "
                    + "(" + ALTAR_SOULS_COST_TO_CORRUPT + " requises).");
            return;
        }

        vamp.setSouls(vamp.getSouls() - ALTAR_SOULS_COST_TO_CORRUPT);
        g.setAltarCorrupted(Boolean.TRUE);

        addHistory(g, "Autel — "
                + nameOf(g, vamp.getId())
                + " sacrifie " + ALTAR_SOULS_COST_TO_CORRUPT
                + " âmes déchues : l'autel est profané.");
    }

    private static final String HOLY_WATER_ACTION_ID = "EAU_BENITE";

    private void applyAltarPurifyWithHolyWater(Game g, Player hunter) {
        if (!"HUNTER".equals(hunter.getRole())) {
            addHistory(g, "Autel — "
                    + nameOf(g, hunter.getId())
                    + " tente de purifier l'autel, mais ce n'est pas un chasseur.");
            return;
        }

        if (!isAltarCorrupted(g)) {
            addHistory(g, "Autel — "
                    + nameOf(g, hunter.getId())
                    + " tente de purifier un autel déjà pur.");
            return;
        }

        List<String> actions = hunter.getActions();
        if (actions == null || !actions.contains(HOLY_WATER_ACTION_ID)) {
            addHistory(g, "Autel — "
                    + nameOf(g, hunter.getId())
                    + " voudrait utiliser de l'eau bénite, mais n'en possède pas.");
            return;
        }

        // On retire UNE carte EAU_BENITE de la main du chasseur
        actions.remove(HOLY_WATER_ACTION_ID);

        // Purification
        g.setAltarCorrupted(Boolean.FALSE);

        addHistory(g, "Autel — "
                + nameOf(g, hunter.getId())
                + " consume une fiole d'eau bénite : l'autel est purifié.");
    }

    @Transactional
    public Game resolveAltarHeal(String gameId, String playerId, String targetId) {
        Game g = findOr404ForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "altar heal only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        if (inst.infra != Infra.ALTAR || inst.choice != LocationEffectChoice.HEAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no altar-heal effect to resolve");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your altar effect");
        }

        var user = findPlayer(g, playerId);
        var target = findPlayer(g, targetId);

        if (user == null || target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid player/target");
        }

        if (!"HUNTER".equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only hunter can resolve altar heal");
        }
        if (!"HUNTER".equals(target.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target must be a hunter");
        }

        if (target.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target is dead");
        }

        int currentCorruption = target.getCorruption();

        // 0 min
        if (currentCorruption <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target has no corruption to heal");
        }

        // Verrou max: si le chasseur a déjà atteint 3, l’autel ne peut plus l’alléger
        if (currentCorruption >= 3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "max corruption cannot be healed at the altar");
        }

        int newCorruption = currentCorruption - 1;
        target.setCorruption(newCorruption);

        addHistory(g, "Autel — "
                + nameOf(g, user.getId())
                + " apaise la corruption de "
                + nameOf(g, target.getId())
                + " (-1 corruption, niveau " + newCorruption + ").");

        // important : mettre à jour les mods liés à la corruption
        rebuildCorruptionMods(g);

        // ---- CONSOMMER L'EFFET MAINTENANT (anti double resolve) ----
        g.setLocationEffectPending(false);
        g.setLocationEffectChoice(null);

        // Très important : invalider l'instance courante
        inst.choice = null;

        save(g);

        afterCommit(() -> {
            Game fresh = findOr404(gameId);
            Player freshUser = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            live.locationEffectUsed(fresh, LocationEffectChoice.HEAL, freshUser);
            scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    @Transactional
    public Game resolveAltarCorrupt(String gameId, String playerId, String targetId) {
        Game g = findOr404ForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "altar corrupt only in PREPHASE3");

        if (!g.getLocationEffectPending())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no location effect pending");

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        if (inst.infra != Infra.ALTAR || inst.choice != LocationEffectChoice.CORRUPT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no altar-corrupt effect to resolve");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your altar effect");
        }

        var user = findPlayer(g, playerId);
        var target = findPlayer(g, targetId);

        if (user == null || target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid player/target");
        }

        if (!"VAMPIRE".equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only vampire can corrupt from altar");
        }
        if (!"HUNTER".equals(target.getRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target must be a hunter");
        }

        if (target.getHp() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target is dead");
        }

        int currentCorruption = target.getCorruption();

        // 3 max : impossible d’aller plus haut
        if (currentCorruption >= 3) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "target already at max corruption");
        }

        int newCorruption = currentCorruption + 1;
        target.setCorruption(newCorruption);

        addHistory(g, "Autel — "
                + nameOf(g, user.getId())
                + " renforce la corruption de "
                + nameOf(g, target.getId())
                + " (+1 corruption, niveau " + newCorruption + ").");

        // Si on atteint 3 par l’autel -> transformation immédiate en serviteur
        if (newCorruption == 3) {
            discardAllActionsOf(g, target);
            target.setRole("SERVANT");
            convertHunterGearToServant(g, target);
            cancelPendingCombatsForPlayer(g, target);
            target.setSouls(target.getSouls() + target.getGold());
            target.setGold(0);

            addHistory(g, "Autel — "
                    + nameOf(g, target.getId())
                    + " succombe à la corruption: il rejoint le vampire en tant que serviteur.");
        }

        rebuildCorruptionMods(g);

        // ---- CONSOMMER L'EFFET MAINTENANT (anti double resolve) ----
        g.setLocationEffectPending(false);
        g.setLocationEffectChoice(null);

        // Très important : invalider l'instance courante
        inst.choice = null;

        save(g);

        afterCommit(() -> {
            Game fresh = findOr404(gameId);
            Player freshUser = fresh.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(playerId))
                    .findFirst().orElse(null);

            live.locationEffectUsed(fresh, LocationEffectChoice.CORRUPT, freshUser);
            scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    private void onAltarBite(Game g, RoundFight r, Player attacker, Player defender) {
        if (!isAltarBuilt(g))
            return;

        String code = Infra.ALTAR.locationCode();
        if (!code.equals(r.getLocation()))
            return;

        // On ne considère que les morsures du vampire sur un chasseur
        if (attacker == null || defender == null)
            return;
        if (!"VAMPIRE".equals(attacker.getRole()))
            return;
        if (!"HUNTER".equals(defender.getRole()))
            return;

        // Au moins une morsure a eu lieu sur l'autel ce raid
        g.setAltarBiteOccurredThisRaid(true);

        // Effet passif : si l'autel était encore pur, il devient corrompu immédiatement
        if (!isAltarCorrupted(g)) {
            g.setAltarCorrupted(Boolean.TRUE);

            addHistory(g, "Autel — "
                    + nameOf(g, attacker.getId())
                    + " accompli le rituel: "
                    + "l'autel est profané.");
        }
    }

    private void onAltarVampireDamaged(Game g, RoundFight r, int damage) {
        if (!isAltarBuilt(g))
            return;
        if (damage <= 0)
            return;

        String code = Infra.ALTAR.locationCode();
        if (!code.equals(r.getLocation()))
            return;

        g.setAltarVampTookDamageThisRaid(true);
    }

    private void resolveAltarEndOfRaid(Game g) {
        if (!isAltarBuilt(g)) {
            resetAltarRaidFlags(g);
            return;
        }

        // Si l'autel est déjà pur, on reset juste les flags
        if (!isAltarCorrupted(g)) {
            resetAltarRaidFlags(g);
            return;
        }

        // Règle voulue :
        // - l'autel est corrompu
        // - le vampire a pris au moins 1 dégât sur l'autel pendant ce raid
        // - aucun rituel de morsure n'a réussi sur l'autel ce raid
        if (g.isAltarVampTookDamageThisRaid()
                && !g.isAltarBiteOccurredThisRaid()) {

            g.setAltarCorrupted(Boolean.FALSE);

            addHistory(g, "Autel — "
                    + "Le vampire est repoussé sans accomplir le rituel: "
                    + "l'autel est purifié.");
        }

        // Dans tous les cas, on reset les flags pour le raid suivant
        resetAltarRaidFlags(g);
    }

    // --- ÉQUIPEMENTS FORGE (codes) ---

    // CHASSEURS — armes T1
    private static final String H_WEAPON_T1_SWORD = "H_WEAPON_T1_SWORD"; // épée fer, bleed +1
    private static final String H_WEAPON_T1_MACE = "H_WEAPON_T1_MACE"; // masse fer, stun -1
    private static final String H_WEAPON_T1_SPEAR = "H_WEAPON_T1_SPEAR"; // lance fer, range 1

    // CHASSEURS — armure T1
    private static final String H_ARMOR_T1_BRIGANDINE = "H_ARMOR_T1_BRIGANDINE"; // D6

    // CHASSEURS — armes T2
    private static final String H_WEAPON_T2_HALBERD = "H_WEAPON_T2_HALBERD"; // bleed +2
    private static final String H_WEAPON_T2_HAMMER = "H_WEAPON_T2_HAMMER"; // stun -2
    private static final String H_WEAPON_T2_CROSSBOW = "H_WEAPON_T2_CROSSBOW"; // range 1-2

    // CHASSEURS — armure T2
    private static final String H_ARMOR_T2_HAUBERT = "H_ARMOR_T2_HAUBERT"; // D8

    // CHASSEURS — armes T3
    private static final String H_WEAPON_T3_WRIST_BLADES = "H_WEAPON_T3_WRIST_BLADES"; // bleed +3
    private static final String H_WEAPON_T3_FLAIL = "H_WEAPON_T3_FLAIL"; // stun -3
    private static final String H_WEAPON_T3_PISTOL = "H_WEAPON_T3_PISTOL"; // range 1-3

    // CHASSEURS — armure T3
    private static final String H_ARMOR_T3_PLATE_SILVER = "H_ARMOR_T3_PLATE_SILVER"; // D20 + effet morsure

    // VAMPIRE/SERVITEUR — armes
    private static final String V_WEAPON_T1_SCYTHE = "V_WEAPON_T1_SCYTHE"; // D6, regen 1 sur roll 7-8
    private static final String V_WEAPON_T2_SWORD = "V_WEAPON_T2_SWORD"; // D8, regen 2 sur roll 10-12
    private static final String V_WEAPON_T3_CLAWS = "V_WEAPON_T3_CLAWS"; // D12, regen 3 sur roll >=10

    // VAMPIRE/SERVITEUR — armures
    private static final String V_ARMOR_T1_CARAPACE = "V_ARMOR_T1_CARAPACE"; // D6 +1 DEF
    private static final String V_ARMOR_T2_HAUBERT = "V_ARMOR_T2_HAUBERT"; // D8 +1 DEF
    private static final String V_ARMOR_T3_ECORCE = "V_ARMOR_T3_ECORCE"; // D12 + esquive sur 12

    private int currentWeaponTier(Player p) {
        String w = p.getWeapon();
        if (w == null)
            return 0;
        if (w.contains("_T1_"))
            return 1;
        if (w.contains("_T2_"))
            return 2;
        if (w.contains("_T3_"))
            return 3;
        return 0;
    }

    private int currentArmorTier(Player p) {
        String a = p.getArmor();
        if (a == null)
            return 0;
        if (a.contains("_T1_"))
            return 1;
        if (a.contains("_T2_"))
            return 2;
        if (a.contains("_T3_"))
            return 3;
        return 0;
    }

    private boolean hasResourcesForEquipment(Player p, String equipCode) {
        // CHASSEURS
        switch (equipCode) {
            // --- T1 hunters ---
            case H_WEAPON_T1_SWORD -> {
                return p.getWood() >= 1 && p.getIron() >= 2;
            }
            case H_WEAPON_T1_MACE -> {
                return p.getWood() >= 1 && p.getIron() >= 2;
            }
            case H_WEAPON_T1_SPEAR -> {
                return p.getWood() >= 2 && p.getIron() >= 1;
            }
            case H_ARMOR_T1_BRIGANDINE -> {
                return p.getIron() >= 3;
            }

            // --- T2 hunters ---
            case H_WEAPON_T2_HALBERD -> {
                return p.getWood() >= 2 && p.getIron() >= 2;
            }
            case H_WEAPON_T2_HAMMER -> {
                return p.getWood() >= 1 && p.getIron() >= 3;
            }
            case H_WEAPON_T2_CROSSBOW -> {
                return p.getWood() >= 3 && p.getIron() >= 1;
            }
            case H_ARMOR_T2_HAUBERT -> {
                return p.getIron() >= 4;
            }

            // --- T3 hunters ---
            case H_WEAPON_T3_WRIST_BLADES -> {
                return p.getIron() >= 4 && p.getSilver() >= 3;
            }
            case H_WEAPON_T3_FLAIL -> {
                return p.getWood() >= 2 && p.getIron() >= 2 && p.getSilver() >= 3;
            }
            case H_WEAPON_T3_PISTOL -> {
                return p.getWood() >= 4 && p.getSilver() >= 3;
            }
            case H_ARMOR_T3_PLATE_SILVER -> {
                return p.getIron() >= 4 && p.getSilver() >= 4;
            }

            // --- VAMPIRE / SERVITEUR ---
            case V_WEAPON_T1_SCYTHE -> {
                return p.getWood() >= 2 && p.getIron() >= 2 && p.getSouls() >= 40;
            }
            case V_ARMOR_T1_CARAPACE -> {
                return p.getIron() >= 3 && p.getSouls() >= 40;
            }

            case V_WEAPON_T2_SWORD -> {
                return p.getWood() >= 2 && p.getIron() >= 4 && p.getSouls() >= 60;
            }
            case V_ARMOR_T2_HAUBERT -> {
                return p.getIron() >= 4 && p.getSouls() >= 60;
            }

            case V_WEAPON_T3_CLAWS -> {
                return p.getWood() >= 5 && p.getIron() >= 5 && p.getSouls() >= 100;
            }
            case V_ARMOR_T3_ECORCE -> {
                return p.getWood() >= 2 && p.getIron() >= 4 && p.getSouls() >= 100;
            }

            default -> {
                return false;
            }
        }
    }

    private void payResourcesForEquipment(Player p, String equipCode) {
        switch (equipCode) {
            // CHASSEURS T1
            case H_WEAPON_T1_SWORD -> {
                p.setWood(p.getWood() - 1);
                p.setIron(p.getIron() - 2);
            }
            case H_WEAPON_T1_MACE -> {
                p.setWood(p.getWood() - 1);
                p.setIron(p.getIron() - 2);
            }
            case H_WEAPON_T1_SPEAR -> {
                p.setWood(p.getWood() - 2);
                p.setIron(p.getIron() - 1);
            }
            case H_ARMOR_T1_BRIGANDINE -> {
                p.setIron(p.getIron() - 3);
            }

            // CHASSEURS T2
            case H_WEAPON_T2_HALBERD -> {
                p.setWood(p.getWood() - 2);
                p.setIron(p.getIron() - 2);
            }
            case H_WEAPON_T2_HAMMER -> {
                p.setWood(p.getWood() - 1);
                p.setIron(p.getIron() - 3);
            }
            case H_WEAPON_T2_CROSSBOW -> {
                p.setWood(p.getWood() - 3);
                p.setIron(p.getIron() - 1);
            }
            case H_ARMOR_T2_HAUBERT -> {
                p.setIron(p.getIron() - 4);
            }

            // CHASSEURS T3
            case H_WEAPON_T3_WRIST_BLADES -> {
                p.setIron(p.getIron() - 4);
                p.setSilver(p.getSilver() - 3);
            }
            case H_WEAPON_T3_FLAIL -> {
                p.setWood(p.getWood() - 2);
                p.setIron(p.getIron() - 2);
                p.setSilver(p.getSilver() - 3);
            }
            case H_WEAPON_T3_PISTOL -> {
                p.setWood(p.getWood() - 4);
                p.setSilver(p.getSilver() - 3);
            }
            case H_ARMOR_T3_PLATE_SILVER -> {
                p.setIron(p.getIron() - 4);
                p.setSilver(p.getSilver() - 4);
            }

            // VAMPIRE / SERVITEUR
            case V_WEAPON_T1_SCYTHE -> {
                p.setWood(p.getWood() - 2);
                p.setIron(p.getIron() - 2);
                p.setSouls(p.getSouls() - 40);
            }
            case V_ARMOR_T1_CARAPACE -> {
                p.setIron(p.getIron() - 3);
                p.setSouls(p.getSouls() - 40);
            }
            case V_WEAPON_T2_SWORD -> {
                p.setWood(p.getWood() - 2);
                p.setIron(p.getIron() - 4);
                p.setSouls(p.getSouls() - 60);
            }
            case V_ARMOR_T2_HAUBERT -> {
                p.setIron(p.getIron() - 4);
                p.setSouls(p.getSouls() - 60);
            }
            case V_WEAPON_T3_CLAWS -> {
                p.setWood(p.getWood() - 5);
                p.setIron(p.getIron() - 5);
                p.setSouls(p.getSouls() - 100);
            }
            case V_ARMOR_T3_ECORCE -> {
                p.setWood(p.getWood() - 2);
                p.setIron(p.getIron() - 4);
                p.setSouls(p.getSouls() - 100);
            }
        }
    }

    private boolean canForgeAnything(Game g, Player p) {
        return !listForgeOptionsForPlayer(g, p).isEmpty();
    }

    /**
     * Retourne la liste des codes d'équipement que ce joueur PEUT forger
     * maintenant (Tier suivant dispo + ressources suffisantes).
     */
    private java.util.List<String> listForgeOptionsForPlayer(Game g, Player p) {
        java.util.List<String> opts = new java.util.ArrayList<>();

        boolean isHunter = "HUNTER".equals(p.getRole());
        boolean isVSide = "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole());

        if (!isHunter && !isVSide) {
            return opts;
        }

        int wTier = currentWeaponTier(p);
        int aTier = currentArmorTier(p);

        if (isHunter) {
            // --- Armes chasseur ---
            if (wTier == 0) {
                // accès T1
                for (String code : new String[] {
                        H_WEAPON_T1_SWORD, H_WEAPON_T1_MACE, H_WEAPON_T1_SPEAR
                }) {
                    if (hasResourcesForEquipment(p, code))
                        opts.add(code);
                }
            } else if (wTier == 1) {
                for (String code : new String[] {
                        H_WEAPON_T2_HALBERD, H_WEAPON_T2_HAMMER, H_WEAPON_T2_CROSSBOW
                }) {
                    if (hasResourcesForEquipment(p, code))
                        opts.add(code);
                }
            } else if (wTier == 2) {
                for (String code : new String[] {
                        H_WEAPON_T3_WRIST_BLADES, H_WEAPON_T3_FLAIL, H_WEAPON_T3_PISTOL
                }) {
                    if (hasResourcesForEquipment(p, code))
                        opts.add(code);
                }
            }
            // pas de T4

            // --- Armures chasseur ---
            if (aTier == 0) {
                if (hasResourcesForEquipment(p, H_ARMOR_T1_BRIGANDINE)) {
                    opts.add(H_ARMOR_T1_BRIGANDINE);
                }
            } else if (aTier == 1) {
                if (hasResourcesForEquipment(p, H_ARMOR_T2_HAUBERT)) {
                    opts.add(H_ARMOR_T2_HAUBERT);
                }
            } else if (aTier == 2) {
                if (hasResourcesForEquipment(p, H_ARMOR_T3_PLATE_SILVER)) {
                    opts.add(H_ARMOR_T3_PLATE_SILVER);
                }
            }
        } else if (isVSide) {
            // --- Armes vampire/serviteur ---
            if (wTier == 0) {
                if (hasResourcesForEquipment(p, V_WEAPON_T1_SCYTHE)) {
                    opts.add(V_WEAPON_T1_SCYTHE);
                }
            } else if (wTier == 1) {
                if (hasResourcesForEquipment(p, V_WEAPON_T2_SWORD)) {
                    opts.add(V_WEAPON_T2_SWORD);
                }
            } else if (wTier == 2) {
                if (hasResourcesForEquipment(p, V_WEAPON_T3_CLAWS)) {
                    opts.add(V_WEAPON_T3_CLAWS);
                }
            }

            // --- Armures vampire/serviteur ---
            if (aTier == 0) {
                if (hasResourcesForEquipment(p, V_ARMOR_T1_CARAPACE)) {
                    opts.add(V_ARMOR_T1_CARAPACE);
                }
            } else if (aTier == 1) {
                if (hasResourcesForEquipment(p, V_ARMOR_T2_HAUBERT)) {
                    opts.add(V_ARMOR_T2_HAUBERT);
                }
            } else if (aTier == 2) {
                if (hasResourcesForEquipment(p, V_ARMOR_T3_ECORCE)) {
                    opts.add(V_ARMOR_T3_ECORCE);
                }
            }
        }

        return opts;
    }

    private void applyForge(Game g, Player p, String equipCode) {
        // sécurité : vérifier que c'est bien une option valide
        if (!listForgeOptionsForPlayer(g, p).contains(equipCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "forge option not allowed for player");
        }

        // payer
        payResourcesForEquipment(p, equipCode);

        // appliquer stats
        switch (equipCode) {
            // --- weapons ---
            case H_WEAPON_T1_SWORD, H_WEAPON_T1_MACE, H_WEAPON_T1_SPEAR, V_WEAPON_T1_SCYTHE -> {
                p.setWeapon(equipCode);
                p.setAttackDice("D6");
            }
            case H_WEAPON_T2_HALBERD, H_WEAPON_T2_HAMMER, H_WEAPON_T2_CROSSBOW, V_WEAPON_T2_SWORD -> {
                p.setWeapon(equipCode);
                p.setAttackDice("D8");
            }
            case H_WEAPON_T3_WRIST_BLADES, H_WEAPON_T3_FLAIL, H_WEAPON_T3_PISTOL, V_WEAPON_T3_CLAWS -> {
                p.setWeapon(equipCode);
                p.setAttackDice("D12");
            }

            // --- armors ---
            case H_ARMOR_T1_BRIGANDINE, V_ARMOR_T1_CARAPACE -> {
                p.setArmor(equipCode);
                p.setDefenseDice("D6");
            }
            case H_ARMOR_T2_HAUBERT, V_ARMOR_T2_HAUBERT -> {
                p.setArmor(equipCode);
                p.setDefenseDice("D8");
            }
            case H_ARMOR_T3_PLATE_SILVER, V_ARMOR_T3_ECORCE -> {
                p.setArmor(equipCode);
                p.setDefenseDice("D12");
            }

            default -> throw new IllegalArgumentException("unknown equipment: " + equipCode);
        }

        String who = nameOf(g, p.getId());
        addHistory(g, "Forge — " + who + " fabrique " + labelEquipmentFr(equipCode) + " et s'en équipe.");
    }

    private String labelEquipmentFr(String equipCode) {
        return switch (equipCode) {
            // Hunters
            case H_WEAPON_T1_SWORD -> "une épée en fer";
            case H_WEAPON_T1_MACE -> "une masse en fer";
            case H_WEAPON_T1_SPEAR -> "une lance en fer";
            case H_ARMOR_T1_BRIGANDINE -> "une brigandine en fer";

            case H_WEAPON_T2_HALBERD -> "une hallebarde en fer";
            case H_WEAPON_T2_HAMMER -> "un marteau à deux mains en fer";
            case H_WEAPON_T2_CROSSBOW -> "une arbalète en fer";
            case H_ARMOR_T2_HAUBERT -> "un haubert de fer";

            case H_WEAPON_T3_WRIST_BLADES -> "des lames de poignet en argent";
            case H_WEAPON_T3_FLAIL -> "un fléau en argent";
            case H_WEAPON_T3_PISTOL -> "un pistolet en argent";
            case H_ARMOR_T3_PLATE_SILVER -> "une armure de plates en argent";

            // Vampire side
            case V_WEAPON_T1_SCYTHE -> "une faux occulte";
            case V_ARMOR_T1_CARAPACE -> "une carapace nocturne";
            case V_WEAPON_T2_SWORD -> "une épée vampirique";
            case V_ARMOR_T2_HAUBERT -> "un haubert impie";
            case V_WEAPON_T3_CLAWS -> "les griffes des damnés";
            case V_ARMOR_T3_ECORCE -> "l'écorce de la Nuit";

            default -> equipCode;
        };
    }

    @Transactional
    public Game resolveForge(String gameId, String playerId, String equipCode) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE
                || g.getPhase() != Phase.PREPHASE3
                || !g.getLocationEffectPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no forge effect pending");
        }

        if (g.getLocationEffectsQueue() == null || g.getCurrentLocationEffectIndex() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no current location effect");
        }

        int idx = g.getCurrentLocationEffectIndex();
        if (idx < 0 || idx >= g.getLocationEffectsQueue().size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid location effect index");
        }

        var inst = g.getLocationEffectsQueue().get(idx);

        if (inst.infra != Infra.FORGE || inst.choice != LocationEffectChoice.FORGE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "current effect is not FORGE");
        }

        if (!inst.ownerId.equals(playerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ce n'est pas votre effet de Forge.");
        }

        var p = findPlayer(g, playerId);

        // sécurité : vérifier que equipCode fait partie des options actuelles
        var opts = listForgeOptionsForPlayer(g, p);
        if (!opts.contains(equipCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "equipement non disponible pour ce joueur");
        }

        applyForge(g, p, equipCode);

        rebuildEquipmentMods(g);

        save(g);

        afterCommit(() -> {
            Game fresh = findOr404(gameId);
            // Notifier le front, puis passer à l'effet de lieu suivant
            // (même pattern que pour les autres effets interactifs)
            scheduleNextLocationEffect(fresh.getId());
        });

        return g;
    }

    /**
     * Reconstruit entièrement les mods d'équipement (EQUIP:*) dans raidMods.
     *
     * Pour l’instant :
     * - V_ARMOR_T1_CARAPACE et V_ARMOR_T2_HAUBERT donnent +1 DEF
     * via un mod moteur persistant "EQUIP:VAMP_ARMOR_DEF".
     *
     * Appelée :
     * - au début de raid (PHASE0),
     * - après un changement d'équipement (Forge, loot plus tard, etc.).
     */
    private void rebuildEquipmentMods(@NonNull Game g) {
        if (g.getRaidMods() == null) {
            g.setRaidMods(new HashMap<>());
        }

        // 1) Purge des anciens mods EQUIP:*
        for (var list : g.getRaidMods().values()) {
            if (list != null) {
                list.removeIf(m -> {
                    String s = m.getSource();
                    return s != null && s.startsWith("EQUIP:");
                });
            }
        }

        // 2) Réinjection en fonction de l'équipement actuel
        for (var p : g.getPlayers()) {

            // --- AURA ARMURE VAMP T1/T2 (ENGINE + chip implicite via
            // buildModBreakdownLines) ---
            if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                int bonus = vampArmorDefenseBonus(p.getArmor()); // 0 ou 1
                if (bonus != 0) {
                    addRaidMod(g, p.getId(), "DEFENSE", bonus, "EQUIP:VAMPIRE_ARMOR:ENG");
                }
            }

            // --- HUNTERS : puces DISPLAY permanentes en fonction de l’arme/armure ---
            if ("HUNTER".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                String w = p.getWeapon();
                String a = p.getArmor();

                if (hunterWeaponHasBleed(w)) {
                    addRaidMod(g, p.getId(), "ATTACK", 0, "EQUIP:BLEED_WEAPON:DSP");
                }
                if (hunterWeaponHasStun(w)) {
                    addRaidMod(g, p.getId(), "ATTACK", 0, "EQUIP:STUN_WEAPON:DSP");
                }
                if (hunterWeaponIsRanged(w)) {
                    addRaidMod(g, p.getId(), "ATTACK", 0, "EQUIP:RANGED_WEAPON:DSP");
                }
                if (hunterArmorIsAntiBite(a)) {
                    addRaidMod(g, p.getId(), "DEFENSE", 0, "EQUIP:HUNTER_ARMOR:DSP");
                }
            }

            // --- VAMPIRE : puces DISPLAY permanentes ---
            if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                String w = p.getWeapon();
                String a = p.getArmor();

                if (vampWeaponHasRegen(w)) {
                    addRaidMod(g, p.getId(), "ATTACK", 0, "EQUIP:VAMPIRE_WEAPON:DSP");
                }
                if (vampArmorHasEvasion(a)) {
                    addRaidMod(g, p.getId(), "DEFENSE", 0, "EQUIP:VAMPIRE_ARMOR_T3:DSP");
                }
            }
        }
    }

    // --- Hunters : saignement ---
    private int bleedBonusForWeapon(String weaponCode) {
        if (weaponCode == null)
            return 0;
        return switch (weaponCode) {
            case H_WEAPON_T1_SWORD -> 1;
            case H_WEAPON_T2_HALBERD -> 2;
            case H_WEAPON_T3_WRIST_BLADES -> 3;
            default -> 0;
        };
    }

    // --- Hunters : étourdissement ---
    private int stunPenalityForWeapon(String weaponCode) {
        if (weaponCode == null)
            return 0;
        return switch (weaponCode) {
            case H_WEAPON_T1_MACE -> 1;
            case H_WEAPON_T2_HAMMER -> 2;
            case H_WEAPON_T3_FLAIL -> 3;
            default -> 0;
        };
    }

    // --- Hunters : tenue à distance ---
    /**
     * Retourne true si le jet d'attaque brut d'un chasseur avec une arme à distance
     * déclenche l'effet "tenu à distance".
     *
     * - Lance en fer (1d8) : 8
     * - Arbalète (1d12) : 11 ou 12
     * - Pistolet (1d20) : 18, 19 ou 20
     */
    private boolean rangedKeepAwayTriggered(String weaponCode, int rawAtk) {
        if (weaponCode == null)
            return false;
        return switch (weaponCode) {
            case H_WEAPON_T1_SPEAR -> (rawAtk == 6);
            case H_WEAPON_T2_CROSSBOW -> (rawAtk == 7 || rawAtk == 8);
            case H_WEAPON_T3_PISTOL -> (rawAtk == 10 || rawAtk == 11 || rawAtk == 12);
            default -> false;
        };
    }

    // --- Vampire : régénération via arme ---
    private int vampRegenAmount(String weaponCode, int attackRoll) {
        if (weaponCode == null)
            return 0;

        return switch (weaponCode) {
            case V_WEAPON_T1_SCYTHE -> (attackRoll == 6) ? 1 : 0;
            case V_WEAPON_T2_SWORD -> (attackRoll >= 7 && attackRoll <= 8) ? 2 : 0;
            case V_WEAPON_T3_CLAWS -> (attackRoll >= 10) ? 3 : 0;
            default -> 0;
        };
    }

    // --- Conversion équipement Hunter -> Servant
    private void convertHunterGearToServant(Game g, Player p) {
        String w = p.getWeapon();
        String a = p.getArmor();

        // 1) Armes
        if (H_WEAPON_T1_SWORD.equals(w) || H_WEAPON_T1_MACE.equals(w) || H_WEAPON_T1_SPEAR.equals(w)) {
            p.setWeapon(V_WEAPON_T1_SCYTHE);
        } else if (H_WEAPON_T2_HALBERD.equals(w) || H_WEAPON_T2_HAMMER.equals(w) || H_WEAPON_T2_CROSSBOW.equals(w)) {
            p.setWeapon(V_WEAPON_T2_SWORD);
        } else if (H_WEAPON_T3_WRIST_BLADES.equals(w) || H_WEAPON_T3_FLAIL.equals(w) || H_WEAPON_T3_PISTOL.equals(w)) {
            p.setWeapon(V_WEAPON_T3_CLAWS);
        }

        // 2) Armures
        if (H_ARMOR_T1_BRIGANDINE.equals(a)) {
            p.setArmor(V_ARMOR_T1_CARAPACE);
        } else if (H_ARMOR_T2_HAUBERT.equals(a)) {
            p.setArmor(V_ARMOR_T2_HAUBERT);
        } else if (H_ARMOR_T3_PLATE_SILVER.equals(a)) {
            p.setArmor(V_ARMOR_T3_ECORCE);
        }

        // Forcer le refresh des mods
        rebuildEquipmentMods(g);
    }

    // --- Vampire : bonus défensifs d'armure ---
    private int vampArmorDefenseBonus(String armorCode) {
        if (armorCode == null)
            return 0;
        return switch (armorCode) {
            case V_ARMOR_T1_CARAPACE, V_ARMOR_T2_HAUBERT -> 1;
            default -> 0;
        };
    }

    private boolean hunterWeaponHasBleed(String w) {
        return H_WEAPON_T1_SWORD.equals(w)
                || H_WEAPON_T2_HALBERD.equals(w)
                || H_WEAPON_T3_WRIST_BLADES.equals(w);
    }

    private boolean hunterWeaponHasStun(String w) {
        return H_WEAPON_T1_MACE.equals(w)
                || H_WEAPON_T2_HAMMER.equals(w)
                || H_WEAPON_T3_FLAIL.equals(w);
    }

    private boolean hunterWeaponIsRanged(String w) {
        return H_WEAPON_T1_SPEAR.equals(w)
                || H_WEAPON_T2_CROSSBOW.equals(w)
                || H_WEAPON_T3_PISTOL.equals(w);
    }

    private boolean vampWeaponHasRegen(String w) {
        return V_WEAPON_T1_SCYTHE.equals(w)
                || V_WEAPON_T2_SWORD.equals(w)
                || V_WEAPON_T3_CLAWS.equals(w);
    }

    private boolean hunterArmorIsAntiBite(String armor) {
        return H_ARMOR_T3_PLATE_SILVER.equals(armor);
    }

    private boolean vampArmorHasEvasion(String armorCode) {
        return V_ARMOR_T3_ECORCE.equals(armorCode);
    }

    private void applyBleed(Game g) {
        if (g.getBleedDamageByTarget() == null)
            return;
        for (var e : g.getBleedDamageByTarget().entrySet()) {
            String targetId = e.getKey();
            int bleed = e.getValue();
            if (bleed <= 0)
                continue;

            Player p = g.getPlayers().stream()
                    .filter(pl -> pl.getId().equals(targetId))
                    .findFirst().orElse(null);
            Game.Monster m = findMonster(g, targetId);

            if (p != null) {
                p.setHp(Math.max(0, p.getHp() - bleed));
                addHistory(g, "Phase 4 — " + nameOf(g, p.getId())
                        + " subit " + bleed + " dégâts de saignement.");
            } else if (m != null && m.hp > 0) {
                m.hp = Math.max(0, m.hp - bleed);
                addHistory(g, "Phase 4 — "
                        + monsterNameFr(m.type) + " saigne encore et perd " + bleed + " PV.");
            }
            // suppression de la puce DSP temporaire
            var mods = g.getRaidMods().get(targetId);
            if (mods != null) {
                mods.removeIf(mm -> "HIT:BLEED_WEAPON:DSP".equals(mm.getSource()));
            }
        }
        g.getBleedDamageByTarget().clear();

        // --- Après les dégâts: gérer morts + fin de partie
        handleDeathsAndVictory(g);
    }

    /**
     * Consomme les effets one-shot posés sur un HIT :
     * - HIT:STUN_WEAPON:ENG (malus d'attaque sur vampire/serviteur, posé sur la
     * cible)
     * - HIT:RANGED_WEAPON:DSP (puce "tenu à distance" sur la cible
     * vampire/serviteur)
     *
     * Appelé après la résolution d'un duel, juste avant de passer au combat
     * suivant.
     */
    private boolean consumeHitModsAfterFight(Game g, RoundFight r) {
        boolean changed = false;

        // 1) Étourdissement (sur l'attaquant vampire/serviteur)
        var atkPlayer = g.getPlayers().stream()
                .filter(p -> p.getId().equals(r.getAttackerId()))
                .findFirst()
                .orElse(null);

        if (atkPlayer != null
                && ("VAMPIRE".equals(atkPlayer.getRole()) || "SERVANT".equals(atkPlayer.getRole()))) {

            var mods = (g.getRaidMods() != null) ? g.getRaidMods().get(atkPlayer.getId()) : null;
            if (mods != null && !mods.isEmpty()) {
                boolean removed = mods.removeIf(m -> "HIT:STUN_WEAPON:ENG".equals(m.getSource()));
                if (removed) {
                    changed = true;
                    addHistory(g, nameOf(g, atkPlayer.getId()) + " se remet de l'étourdissement.");
                }
                if (mods.isEmpty())
                    g.getRaidMods().remove(atkPlayer.getId()); // optionnel mais propre
            }
        }

        // 2) Tenu à distance (sur le défenseur)
        var defMods = (g.getRaidMods() != null) ? g.getRaidMods().get(r.getDefenderId()) : null;
        if (defMods != null && !defMods.isEmpty()) {
            boolean removed = defMods.removeIf(m -> "HIT:RANGED_WEAPON:DSP".equals(m.getSource()));
            if (removed) {
                changed = true;
                addHistory(g, entityName(g, r.getDefenderId()) + " n'est plus tenu à distance.");
            }
            if (defMods.isEmpty())
                g.getRaidMods().remove(r.getDefenderId());
        }

        return changed;
    }

    private void purgeTransientRaidMods(Game g) {
        if (g.getRaidMods() == null)
            return;

        for (var e : g.getRaidMods().entrySet()) {
            var mods = e.getValue();
            if (mods == null)
                continue;
            mods.removeIf(m -> {
                String s = m.getSource();
                return "HIT:STUN_WEAPON:ENG".equals(s) || "HIT:RANGED_WEAPON:DSP".equals(s);
            });
        }
        g.getRaidMods().entrySet().removeIf(e -> e.getValue() == null || e.getValue().isEmpty());
    }

    /**
     * Supprime de la file des combats le duel "inverse"
     * (riposte) où attaquant = defId et défenseur = attId.
     */
    private void cancelReverseFight(Game g, String attId, String defId) {
        if (g.getCombatsQueue() == null || g.getCurrentCombatIndex() == null)
            return;

        int idx = g.getCurrentCombatIndex();
        var queue = g.getCombatsQueue();

        // On ne touche qu’aux combats APRÈS le duel courant
        for (int i = idx + 1; i < queue.size(); i++) {
            RoundFight rf = queue.get(i);
            if (defId.equals(rf.getAttackerId()) && attId.equals(rf.getDefenderId())) {
                queue.remove(i);
                break;
            }
        }
    }

    @Transactional
    public void contributeBankStone(String gameId, String playerId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "bank only in PHASE4");

        Player p = findPlayer(g, playerId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid player");

        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");

        if (p.getHp() <= 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "dead player");

        int lvl = (g.getBankLevel() == null ? 0 : g.getBankLevel());
        int prog = (g.getBankStoneProgress() == null ? 0 : g.getBankStoneProgress());

        if (lvl >= 3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "bank already max");

        if (p.getStone() <= 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not enough stone");

        // 1) payer 1 pierre
        p.setStone(p.getStone() - 1);

        // 2) progresser
        prog += 1;

        // 3) coût requis selon niveau actuel -> prochain niveau
        int required = switch (lvl + 1) {
            case 1 -> 10;
            case 2 -> 15;
            case 3 -> 20;
            default -> Integer.MAX_VALUE;
        };

        boolean leveledUp = false;
        if (prog >= required) {
            lvl += 1;
            prog = 0; // reset pour le prochain palier
            leveledUp = true;

            addHistory(g, "Banque — amélioration réussie : la banque passe au niveau " + lvl + ".");
        } else {
            addHistory(g, "Banque — dépôt d’1 pierre (" + prog + "/" + required + ").");
        }

        g.setBankLevel(lvl);
        g.setBankStoneProgress(prog);

        save(g);

        afterCommit(() -> {
            Game fresh = findOr404(gameId);
            live.bankUpdated(fresh, playerId); // WS (refresh front)
        });
    }

    private void applyBankBonusOnPhase4Entry(Game g) {
        int lvl = (g.getBankLevel() == null ? 0 : g.getBankLevel());
        if (lvl <= 0)
            return;

        for (Player p : g.getPlayers()) {
            if (!"HUNTER".equals(p.getRole()))
                continue;
            if (p.getHp() <= 0)
                continue;

            int goldGain = (lvl >= 3) ? 100 : 50;
            p.setGold(p.getGold() + goldGain);

            if (lvl >= 2) {
                int r = dice.nextInt(4); // 0..3
                switch (r) {
                    case 0 -> p.setWood(p.getWood() + 1);
                    case 1 -> p.setIron(p.getIron() + 1);
                    case 2 -> p.setHerbs(p.getHerbs() + 1);
                    case 3 -> p.setWater(p.getWater() + 1);
                }
            }
        }

        addHistory(g, "Banque — bonus appliqué (niveau " + lvl + ") au début de la phase 4.");
    }
}
