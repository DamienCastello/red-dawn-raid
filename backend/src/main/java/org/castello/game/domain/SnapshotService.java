package org.castello.game.domain;

import org.castello.game.CenterBoard;
import org.castello.game.Game;
import org.castello.game.GameStatus;
import org.castello.game.Infra;
import org.castello.game.RaidEffects;
import org.castello.game.RoundFight;
import org.castello.game.StatMod;
import org.castello.game.support.GameStore;
import org.castello.player.Player;
import org.castello.web.dto.EndedGameSummary;
import org.castello.web.dto.GameSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Projections en lecture seule de l'état d'une partie.
 *
 * viewSnapshot est LA vue envoyée au client (GET /api/games/{id}) : elle
 * masque les informations cachées (mains, potions et actions des autres
 * joueurs remplacées par "HIDDEN").
 */
@Service
public class SnapshotService {

    private final GameStore store;
    private final DeckService decks;

    public SnapshotService(GameStore store, DeckService decks) {
        this.store = store;
        this.decks = decks;
    }

    public GameSnapshot viewSnapshot(String gameId, String userId) {
        Game g = store.read(gameId);

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
                    p.isMerchantUsedThisRaid(),
                    p.isBot());
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
        var decksView = new GameSnapshot.DecksView(
                new GameSnapshot.DecksView.Pile(
                        decks.deckSize(g.getVampActionsDeck()),
                        decks.discardSize(g.getVampActionsDiscard()),
                        safeList(g.getVampActionsDiscard())),
                new GameSnapshot.DecksView.Pile(
                        decks.deckSize(g.getHunterActionsDeck()),
                        decks.discardSize(g.getHunterActionsDiscard()),
                        safeList(g.getHunterActionsDiscard())),
                new GameSnapshot.DecksView.Pile(
                        decks.deckSize(g.getPotionDeck()),
                        decks.discardSize(g.getPotionDiscard()),
                        safeList(g.getPotionDiscard())),
                new GameSnapshot.DecksView.Pile(
                        decks.deckSize(g.getElixirDeck()),
                        decks.discardSize(g.getElixirDiscard()),
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
                || "PORTAL_INVOCATION_REVENANT".equals(a.getMode())
                || "PORTAL_INVOCATION_BAT".equals(a.getMode())
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
                decksView,
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

    public EndedGameSummary viewEndedSummary(String gameId) {
        Game g = store.read(gameId);

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

    private static Map<String, Integer> copyMap(Map<String, Integer> m) {
        return (m == null) ? new java.util.HashMap<>() : new java.util.HashMap<>(m);
    }

    private java.util.List<String> safeList(java.util.List<String> xs) {
        return (xs != null) ? new java.util.ArrayList<>(xs) : java.util.List.of();
    }
}
