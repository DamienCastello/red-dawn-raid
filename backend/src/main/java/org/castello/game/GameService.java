package org.castello.game;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import org.castello.persistence.GameEntity;
import org.castello.persistence.GameRepository;

import java.util.*;

@Service
public class GameService {

    private final TaskScheduler raidScheduler;
    private final TransactionTemplate tx;

// ----- PERSISTENCE -----
    private final GameRepository repo;
    private final ObjectMapper mapper; // Jackson fourni par Spring Boot
    private final org.castello.live.LiveEvents live;

    public GameService(GameRepository repo, @Qualifier("raidTaskScheduler") TaskScheduler raidScheduler, PlatformTransactionManager tm, ObjectMapper mapper,
                       org.castello.live.LiveEvents live) {
        this.repo = repo;
        this.raidScheduler = raidScheduler;
        this.tx = new TransactionTemplate(tm);
        this.mapper = mapper;
        this.live = live;
    }
    private static final Logger log = LoggerFactory.getLogger(GameService.class);


    private String toJson(Game g) {
        try { return mapper.writeValueAsString(g); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private Game fromJson(String json) {
        try { return mapper.readValue(json, Game.class); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private Game findOr404(String id) {
        var e = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found"));
        return fromJson(e.getStateJson());
    }

    private void afterCommit(Runnable r) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { r.run(); }
                    });
        } else {
            // au cas où on l’appelle hors transaction (no-op de tx) : on exécute quand même
            r.run();
        }
    }

    private static Map<String,Integer> copyMap(Map<String,Integer> m){
        return (m == null) ? new java.util.HashMap<>() : new java.util.HashMap<>(m);
    }

    public GameSnapshot viewSnapshot(String gameId, String userId) {
        Game g = findOr404(gameId);

        boolean isVamp = g.getPlayers().stream()
                .anyMatch(p -> userId.equals(p.getId()) && "VAMPIRE".equals(p.getRole()));

        // Wrappers null-safe (raccourcis locaux)
        List<Player> playersSrc                      = (g.getPlayers()               != null) ? g.getPlayers()               : java.util.Collections.emptyList();
        List<CenterBoard> centerSrc                  = (g.getCenter()                != null) ? g.getCenter()                : java.util.Collections.emptyList();
        Map<String, List<StatMod>> raidModsSrc       = (g.getRaidMods()              != null) ? g.getRaidMods()              : java.util.Collections.emptyMap();
        java.util.Set<String> readySet               = (g.getReadyForPhase3()        != null) ? g.getReadyForPhase3()        : java.util.Collections.emptySet();
        List<String> readyForNextRaid                = new java.util.ArrayList<>(g.getReadyForNextRaid());
        List<Game.HistoryItem> historySrc            = (g.getHistory()               != null) ? g.getHistory()               : java.util.Collections.emptyList();
        List<RoundFight> combatsQueueSrc             = (g.getCombatsQueue()          != null) ? g.getCombatsQueue()          : java.util.Collections.emptyList();
        List<String> messagesSrc                     = (g.getMessages()              != null) ? g.getMessages()              : java.util.Collections.emptyList();

        Map<String, List<String>> uTargets =
                isVamp && g.getUnstableEligibleTargets()!=null
                        ? new java.util.HashMap<>(g.getUnstableEligibleTargets())
                        : java.util.Collections.emptyMap();

        Map<String, List<String>> uLocs =
                isVamp && g.getUnstableEligibleLocations()!=null
                        ? new java.util.HashMap<>(g.getUnstableEligibleLocations())
                        : java.util.Collections.emptyMap();

        Map<String, String> uChosenTargets =
                isVamp && g.getUnstableTargetByPlayer()!=null
                        ? new java.util.HashMap<>(g.getUnstableTargetByPlayer())
                        : java.util.Collections.emptyMap();

        Map<String, String> uChosenHarvests =
                isVamp && g.getUnstableHarvestLocByPlayer()!=null
                        ? new java.util.HashMap<>(g.getUnstableHarvestLocByPlayer())
                        : java.util.Collections.emptyMap();

        // Weather
        var weather = new GameSnapshot.WeatherView(
                g.getWeatherRoll(),
                (g.getWeatherStatus() != null ? g.getWeatherStatus().name() : null),
                g.getWeatherStatusNameFr(),
                g.getWeatherDescriptionFr()
        );

        // Players
        List<GameSnapshot.PlayerView> players = g.getPlayers().stream().map(p -> {
            List<String> hand    = (p.getHand()    != null ? p.getHand()    : java.util.List.of());
            List<String> potions = (p.getPotions() != null ? p.getPotions() : java.util.List.of());
            List<String> actions = (p.getActions() != null ? p.getActions() : java.util.List.of());

            return new GameSnapshot.PlayerView(
                    p.getId(),
                    p.getUsername(),
                    p.getRole(),
                    // Main (lieux) visible seulement pour soi
                    p.getId().equals(userId) ? hand : java.util.List.of(),

                    // Actions visibles seulement pour soi
                    p.getId().equals(userId) ? potions : java.util.List.of(),

                    // Actions visibles seulement pour soi
                    p.getId().equals(userId) ? actions : java.util.List.of(),
                    p.getHp(),
                    p.getCorruption(),
                    p.getAttackDice() != null ? p.getAttackDice() : "D6",
                    p.getDefenseDice() != null ? p.getDefenseDice() : "D6",
                    p.getWood(), p.getHerbs(), p.getStone(), p.getIron(),
                    p.getWater(), p.getGold(), p.getSouls(), p.getSilver()
            );
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
                RaidEffects fx  = e.getValue();
                if (fx == null) continue;

                raidEffects.put(playerId,
                        new GameSnapshot.RaidEffectsView(
                                fx.isFocus(),
                                fx.isLeech(),
                                fx.isInvulnerable(),
                                fx.isDoubleAttack(),
                                fx.isDoubleDefense(),
                                fx.isInvisible(),
                                fx.isRapid()
                        )
                );
            }
        }

        // Decks
        var decks = new GameSnapshot.DecksView(
                new GameSnapshot.DecksView.Pile(
                        g.getVampActionsPool(),
                        g.getVampActionsDiscardPool()
                ),
                new GameSnapshot.DecksView.Pile(
                        g.getHunterActionsPool(),
                        g.getHunterActionsDiscardPool()
                ),
                new GameSnapshot.DecksView.Pile(
                        g.getPotionsPool(),
                        g.getPotionsDiscardPool()
                )
        );

        // Bite
        GameSnapshot.BiteView bite = null;
        if (g.getCurrentBite() != null) {
            var b = g.getCurrentBite();
            bite = new GameSnapshot.BiteView(
                    b.getAttackerId(), b.getTargetId(), b.getLocation(),
                    b.getRoll(), b.getResolvedAtMillis()
            );
        }

        // Combats queue + current
        List<GameSnapshot.RoundFightView> combatsQueue = combatsQueueSrc.stream().map(r ->
                new GameSnapshot.RoundFightView(
                        r.getId(), r.getLocation(),
                        r.getAttackerId(), r.getDefenderId(),
                        r.getAttackerRoll(), r.getDefenderRoll(),
                        r.getAttackerFirstRoll(), r.getDefenderFirstRoll(),
                        r.getAttackerReroll(), r.getDefenderReroll(),
                        r.getResolvedAtMillis(),
                        (r.getBreakdownLines() != null ? r.getBreakdownLines() : java.util.List.of())
                )
        ).toList();

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
                    (r.getBreakdownLines() != null ? r.getBreakdownLines() : java.util.List.of())
            );
        }

        List<GameSnapshot.TradeView> trades =
                (g.getTrades() == null ? java.util.List.<Game.Trade>of() : g.getTrades())
                        .stream()
                        .filter(t -> java.util.Objects.equals(t.getAId(), userId) || java.util.Objects.equals(t.getBId(), userId))
                        .map(t -> new GameSnapshot.TradeView(
                                t.getId(),
                                t.getSide(),
                                t.getAId(),
                                t.getBId(),
                                copyMap(t.getOfferA()),
                                copyMap(t.getOfferB()),
                                t.getStatusA(),
                                t.getStatusB(),
                                t.getUpdatedAt()         // <- primitive long
                        ))
                        // tri du plus récent au plus ancien (pas de null-check sur un long)
                        .sorted((x, y) -> Long.compare(y.updatedAt(), x.updatedAt()))
                        .toList();

        // History
        List<GameSnapshot.HistoryItemView> history = historySrc.stream().map(h ->
                new GameSnapshot.HistoryItemView(
                        h.getTs(),
                        h.getRaid(),
                        (h.getPhase() != null ? h.getPhase().name() : null),
                        h.getText()
                )
        ).toList();

        // Ready → liste (copie) pour ne pas exposer la Set interne
        java.util.List<String> readyList = new java.util.ArrayList<>(readySet);

        // Action (Filet / Fosse) pilotée uniquement par currentAction
        GameSnapshot.ActionView action = null;
        Game.Action a = g.getCurrentAction();

        if (a != null && ("NET".equals(a.getMode()) || "PIT".equals(a.getMode()))) {
            action = new GameSnapshot.ActionView(
                    a.getMode(),
                    a.getOwnerId(),
                    a.getLocation(),
                    a.getTargetId(),
                    a.getRoll(),
                    a.getBreakdownLines(),
                    a.getResolvedAtMillis()
            );
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

        return new GameSnapshot(
                g.getId(),
                (g.getStatus() != null ? g.getStatus().name() : "CREATED"),
                g.getRaid(),
                (g.getPhase()  != null ? g.getPhase().name()  : "PHASE0"),
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
                garlicBlocked,
                trackerHunters,
                campfireLocations,
                netHunters,
                pitHunters,
                history,
                messagesSrc,
                System.currentTimeMillis(),
                userId
        );
    }

    private void initPhase0Structures(Game g) {
        if (g.getRaidMods() == null)             g.setRaidMods(new java.util.HashMap<>());
        if (g.getRaidEffects() == null)          g.setRaidEffects(new java.util.HashMap<>());
        if (g.getMessages() == null)             g.setMessages(new java.util.ArrayList<>());
        if (g.getHistory() == null)              g.setHistory(new java.util.ArrayList<>());
        g.getReadyForPhase3().clear();
        g.getReadyForNextRaid().clear();
        if (g.getTrades()!=null) g.getTrades().clear();
        g.setPhase4DeadlineMillis(null);
        if (g.getUnstableEligibleTargets() == null)   g.setUnstableEligibleTargets(new java.util.HashMap<>());
        if (g.getUnstableTargetByPlayer() == null)    g.setUnstableTargetByPlayer(new java.util.HashMap<>());
        if (g.getUnstableEligibleLocations() == null) g.setUnstableEligibleLocations(new java.util.HashMap<>());
        if (g.getUnstableHarvestLocByPlayer() == null)g.setUnstableHarvestLocByPlayer(new java.util.HashMap<>());
        if (g.getCombatsQueue() == null)         g.setCombatsQueue(new java.util.ArrayList<>());
        if (g.getCenter() == null)               g.setCenter(new java.util.ArrayList<>());
        if (g.getGarlicBlockedLocations() == null)g.setGarlicBlockedLocations(new java.util.HashSet<>());
        g.getGarlicBlockedLocations().clear();
        if (g.getPendingGarlicPlayers() == null) g.setPendingGarlicPlayers(new java.util.HashSet<>());
        g.getPendingGarlicPlayers().clear();
        if (g.getTrackerHunters() == null) g.setTrackerHunters(new java.util.HashSet<>());
        g.getTrackerHunters().clear();
        if (g.getCampfireLocations() == null) g.setCampfireLocations(new java.util.HashSet<>());
        g.getCampfireLocations().clear();
        if (g.getNetHunters() == null) g.setNetHunters(new java.util.HashSet<>());
        if (g.getPitHunters() == null) g.setPitHunters(new java.util.HashSet<>());
        if (g.getPitTargetsByHunter() == null) g.setPitTargetsByHunter(new java.util.HashMap<>());
        else g.getPitTargetsByHunter().clear();
        if (g.getPotionsPool() == null)             g.setPotionsPool(new java.util.HashMap<>());
        if (g.getPotionsDiscardPool() == null)      g.setPotionsDiscardPool(new java.util.HashMap<>());
        if (g.getHunterActionsPool() == null)       g.setHunterActionsPool(new java.util.HashMap<>());
        if (g.getHunterActionsDiscardPool() == null)g.setHunterActionsDiscardPool(new java.util.HashMap<>());
        if (g.getVampActionsPool() == null)         g.setVampActionsPool(new java.util.HashMap<>());
        if (g.getVampActionsDiscardPool() == null)  g.setVampActionsDiscardPool(new java.util.HashMap<>());

        if (g.getPitIndexByHunter() == null) g.setPitIndexByHunter(new java.util.HashMap<>());
        else g.getPitIndexByHunter().clear();
        // Bite/combat reset explicite
        g.setCurrentBite(null);
        g.setCurrentCombatIndex(null);
        g.setCurrentCombat(null);
        g.setHasUpcomingCombat(false);
        // Pour chaque joueur, on s'assure que la main est non nulle
        for (var p : g.getPlayers()) {
            if (p.getHand() == null) p.setHand(new java.util.ArrayList<>());
            if (p.getAttackDice() == null)  p.setAttackDice("D6");
            if (p.getDefenseDice() == null) p.setDefenseDice("D6");
        }
    }

    /** Sauvegarde en préservant la version (évite les inserts involontaires). */
    private void save(@NonNull Game g) {
        repo.findById(g.getId()).ifPresentOrElse(existing -> {
            existing.setStateJson(toJson(g));
            repo.save(existing);
        }, () -> {
            GameEntity ne = new GameEntity();
            ne.setId(g.getId());
            ne.setStateJson(toJson(g));
            repo.save(ne);
        });
    }


// ---------- utilitaires ----------
    private static final Random RND = new Random();

    private boolean computeHasUpcomingCombat(Game g) {
        // Face-up uniquement
        var faceUp = (g.getCenter() != null ? g.getCenter() : java.util.List.<CenterBoard>of())
                .stream()
                .filter(CenterBoard::isFaceUp)
                .toList();
        if (faceUp.isEmpty()) return false;

        // Instables affectés à la récolte => NE COMBATTENT PAS ce raid
        java.util.Set<String> harvesters = (g.getUnstableHarvestLocByPlayer() != null)
                ? g.getUnstableHarvestLocByPlayer().keySet()
                : java.util.Set.of();

        // Parcourt les lieux révélés
        java.util.Set<String> locs = new java.util.HashSet<>();
        for (var cb : faceUp) locs.add(cb.getCard());

        for (String loc : locs) {
            var idsOnLoc = faceUp.stream()
                    .filter(cb -> loc.equals(cb.getCard()))
                    .map(CenterBoard::getPlayerId)
                    .toList();

            var playersOnLoc = idsOnLoc.stream()
                    .map(pid -> g.getPlayers().stream().filter(p -> p.getId().equals(pid)).findFirst().orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .toList();

            boolean hasEnemy = playersOnLoc.stream()
                    .anyMatch(p -> "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()));

            boolean hasEligibleHunter = playersOnLoc.stream()
                    .anyMatch(p -> "HUNTER".equals(p.getRole())
                            && p.getHp() > 0
                            && !harvesters.contains(p.getId())); // <-- exclusion clé

            if (hasEnemy && hasEligibleHunter) return true;
        }

        // (optionnel : si un instable a été explicitement assigné sur une cible, il y aura combat)
        if (g.getUnstableTargetByPlayer() != null && !g.getUnstableTargetByPlayer().isEmpty()) return true;

        return false;
    }

    private void addHistory(@NonNull Game g, @NonNull String text) {
        if (g.getHistory() == null) g.setHistory(new ArrayList<>());
        var hi = new Game.HistoryItem();
        hi.setRaid(g.getRaid());
        hi.setPhase(g.getPhase());
        hi.setTs(System.currentTimeMillis());
        hi.setText(text);
        g.getHistory().add(hi);
    }

    private boolean hasPlayed(@NonNull Game g, String playerId) {
        // 1) carte lieu au centre ?
        boolean hasLocation = g.getCenter() != null &&
                g.getCenter().stream().anyMatch(cb -> playerId.equals(cb.getPlayerId()));

        // 2) suivi Pisteur ?
        boolean isTracker = g.getTrackerHunters() != null &&
                g.getTrackerHunters().contains(playerId);

        return hasLocation || isTracker;
    }

    @NonNull
    private Optional<Player> getVamp(@NonNull Game g) {
        return g.getPlayers().stream().filter(p -> "VAMPIRE".equals(p.getRole())).findFirst();
    }

    @NonNull
    public List<Player> getHunters(@NonNull Game g) {
        return g.getPlayers().stream()
                .filter(p -> "HUNTER".equals(p.getRole()))
                .toList();
    }

    private int diceSides(String d) {
        if (d == null) return 6;
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
        return g.getPlayers().stream()
                .filter(p -> p.getId().equals(playerId))
                .map(p -> (p.getUsername()!=null && !p.getUsername().isBlank()) ? p.getUsername() : p.getId())
                .findFirst().orElse(playerId);
    }

    private WeatherStatus mapRollToWeather(int roll){
        return switch (roll) {
            case 1  -> WeatherStatus.SUNNY;
            case 2  -> WeatherStatus.FOG;
            case 3  -> WeatherStatus.AURORA;
            case 4  -> WeatherStatus.WIND;
            case 5  -> WeatherStatus.CLOUDY;
            case 6  -> WeatherStatus.STORM;
            case 7  -> WeatherStatus.RAIN;
            case 8  -> WeatherStatus.BLIZZARD;
            case 9  -> WeatherStatus.DUSK;
            case 10 -> WeatherStatus.NIGHT_DARK;
            case 11 -> WeatherStatus.NIGHT_CLEAR;
            case 12 -> WeatherStatus.FULL_MOON;
            default -> null;
        };
    }

    private String weatherNameFr(WeatherStatus ws){
        return switch (ws) {
            case SUNNY      -> "Jour ensoleillé";
            case FOG        -> "Brouillard protecteur";
            case AURORA     -> "Aurore";
            case WIND       -> "Vent violent";
            case CLOUDY     -> "Ciel couvert";
            case STORM      -> "Orage";
            case RAIN       -> "Pluie diluvienne";
            case BLIZZARD   -> "Blizzard";
            case DUSK       -> "Crépuscule";
            case NIGHT_DARK -> "Nuit obscure";
            case NIGHT_CLEAR-> "Nuit claire";
            case FULL_MOON  -> "Pleine lune";
        };
    }

    private String weatherDescFr(WeatherStatus ws){
        return switch (ws) {
            case SUNNY      -> "La lumière domine. +1 attaque pour les chasseurs et –1 défense pour le vampire.";
            case FOG        -> "La brume étouffe les sons et couvre l'approche. +1 attaque des chasseurs.";
            case AURORA     -> "La lumière progresse. -1 défense pour le vampire.";
            case WIND       -> "Les rafales dispersent le matériel. +1 de coût en ressource pour les constructions.";
            case CLOUDY     -> "Lumière terne, ombres sans mordant. Aucun effet.";
            case STORM      -> "La foudre déstabilise au combat. -2 défense pour tous.";
            case RAIN       -> "La pluie torrentielle alourdit chaque geste. -2 attaque pour tous.";
            case BLIZZARD   -> "Froid mordant. Potions gelées et -1 attaque pour tous.";
            case DUSK       -> "Les ombres progressent. +1 défense du vampire.";
            case NIGHT_DARK -> "Les ombres dominent. +1 attaque du vampire. Les chasseurs ne peuvent utiliser de pièges.";
            case NIGHT_CLEAR-> "La lune éclaire légèrement et le vampire gagne en puissance. +1 attaque du vampire et –1 défense pour les chasseurs.";
            case FULL_MOON  -> "La pleine lune exalte le sang ancien. +2 attaque du vampire.";
        };
    }

    /**
     * Ajoute (ou remplace par source) un mod de raid pour un joueur.
     * Idempotent par 'source' : si un mod avec la même source existe, il est retiré avant ajout.
     */
    private void addRaidMod(Game g, String playerId, String stat, int amount, String source) {
        if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());
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
     * Construit la liste des modificateurs “moteur” d’un joueur pour le raid courant.
     * On renvoie uniquement les mods chiffrés (ATTACK / DEFENSE) utiles pour le calcul,
     * en appliquant éventuellement l’annulation météo par Feu de camp.
     */
    private List<StatMod> modsAppliedFor(Game g, String playerId, String stat, String location) {
        // 1) base = tous les mods pour ce joueur et cette stat
        if (g.getRaidMods() == null) return java.util.List.of();

        var list = g.getRaidMods().get(playerId);
        if (list == null) return java.util.List.of();

        // on ne garde que les mods qui correspondent à la stat demandée (ATTACK ou DEFENSE)
        var base = list.stream()
                .filter(m -> stat != null && stat.equalsIgnoreCase(m.getStat()))
                .toList();

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
     *   score = dX + totalModFor(g, playerId, ATTACK|DEFENSE)
     *
     * NB : ne crée rien, ne modifie rien — ne fait qu’agréger ce qui a été
     *      préalablement construit (ex: par rebuildRaidModsForAll / modsAppliedFor).
     *
     */

    private int totalModFor(Game g, String playerId, String stat, String location){
        return totalModForAt(g, playerId, stat, location);
    }

    private int totalModForAt(Game g, String playerId, String stat, String location){
        return modsAppliedFor(g, playerId, stat, location)
                .stream().mapToInt(StatMod::getAmount).sum();
    }

    /** Construit les logs liés aux mods (affichés dans la modale spectateur ET poussés dans l'historique). */
    private List<String> buildModBreakdownLines(Game g, String playerId, String stat, int baseRoll, String location){
        List<String> out = new ArrayList<>();
        int cur = baseRoll;
        String sideLabel = "ATTACK".equalsIgnoreCase(stat) ? "L’attaque" : "La défense";
        String name = nameOf(g, playerId);

        for (var m : modsAppliedFor(g, playerId, stat, location)) {
            int delta = m.getAmount();
            if (delta == 0) continue;

            String verb = (delta >= 0) ? "augmente" : "diminue";
            int abs = Math.abs(delta);

            String src = "";
            String s = (m.getSource() == null) ? "" : m.getSource();
            if (m.getSource() != null && m.getSource().startsWith("WEATHER:") && !isCampfireCancellingWeather(g, location)) {
                try {
                    var wsStr = m.getSource().substring("WEATHER:".length());
                    var ws = WeatherStatus.valueOf(wsStr);
                    src = "par l’effet " + weatherNameFr(ws).toLowerCase();
                } catch (Exception ignored) { /* fallback simple */ }
            } else if (s.startsWith("POTION:")) {
                String type = s.substring("POTION:".length());
                src = switch (type) {
                    case "FORCE"     -> "par l'effet potion de force";
                    case "ENDURANCE" -> "par l'effet potion d’endurance";
                    case "VIE"       -> "par l'effet potion de vie";
                    default          -> "par l'effet potion";
                };
            } else if (s.startsWith("ACTION:")) {
                String type = s.substring("ACTION:".length());
                src = switch (type) {
                    case "FILET"     -> "par l'effet d'action filet";
                    case "FOSSE" -> "par l'effet d'action fosse";
                    default          -> "par l'effet action";
                };
            } else if (s.startsWith("CORRUPTION:")) {
                // L1 moteur (−1 ATK/DEF) => libellé clair
                if (s.contains(":L1:")) {
                    src = "par l’effet Affaibli (corruption)";
                }
                // L2 ("Instable") n’a pas de mod chiffré => pas de ligne ici (géré en chip côté front)
            }

            cur += delta;
            out.add(String.format("%s de %s %s de %d %s et passe à %d", sideLabel, name, verb, abs, src, cur));
        }
        return out;
    }


// ---------- CRUD ----------
    @Transactional
    public Game create() {
        String id = UUID.randomUUID().toString();
        Game game = new Game(id, GameStatus.CREATED, 0);
        save(game);
        return game;
    }

    public Collection<Game> list() {
        return repo.findAll().stream()
                .map(ge -> fromJson(ge.getStateJson()))
                .toList();
    }

    // REM: findOr404(id) déjà défini ci-dessus (JSONB -> Game)

// ---------- LOBBY ----------
    @Transactional
    public Game addOrUpdatePlayer(String gameId, String playerId, String username) {
        if (username == null || username.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username required");
        Game g = findOr404(gameId);
        if (g.getStatus() != GameStatus.CREATED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game already started/ended");

        g.getPlayers().stream()
                .filter(p -> p.getId().equals(playerId))
                .findFirst()
                .ifPresentOrElse(
                        p -> p.setUsername(username),
                        () -> g.getPlayers().add(new Player(playerId, username))
                );

        save(g);
        live.lobbyUpdated(g);
        return g;
    }

    @Transactional
    public Game start(String id) {
        Game g = findOr404(id);
        if (g.getStatus() != GameStatus.CREATED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already started/ended");
        if (g.getPlayers().size() < 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "need at least 2 players");

        // === Etat global ===
        g.setStatus(GameStatus.ACTIVE);
        g.setRaid(1);
        g.setPhase(Phase.PHASE0);

        // === PHASE0 : météo (reset complet, sans push d’events) ===
        g.setWeatherRoll(null);
        g.setWeatherStatus(null);
        g.setWeatherStatusNameFr(null);
        g.setWeatherDescriptionFr(null);

        // Messages persistés (le feed "live" sera émis après commit)
        g.setMessages(new ArrayList<>(List.of("Tirage météo ...")));

        // --- Rôles + mains (répartition initiale) ---
        int vampIndex = RND.nextInt(g.getPlayers().size());
        for (int i = 0; i < g.getPlayers().size(); i++) {
            Player p = g.getPlayers().get(i);
            p.setRole(i == vampIndex ? "VAMPIRE" : "HUNTER");
            p.setHand(new ArrayList<>(List.of("forest", "quarry", "lake", "manor")));

            // dés de base
            p.setAttackDice("D6");
            p.setDefenseDice("D6");
        }

        // PV init (vamp = 20 + 10 * nb chasseurs)
        int huntersCount = (int) g.getPlayers().stream().filter(p -> !"VAMPIRE".equals(p.getRole())).count();
        for (var p : g.getPlayers()) {
            p.setHp("VAMPIRE".equals(p.getRole()) ? 20 + huntersCount * 10 : 20);
        }


        // --- Inventaire potions (dev/test) ---
        for (var p : g.getPlayers()) {
            if ("HUNTER".equals(p.getRole())) {
                p.getPotions().addAll(List.of("VIE"));
            }
        /*
        if ("VAMPIRE".equals(p.getRole())) {
            p.getPotions().addAll(List.of("FOCALISATION", "RESILIENCE", "RAGE", "RAPIDITE", "INVISIBILITE", "INVULNERABILITE"));
        }
        */
        }

        // --- Inventaire actions (dev/test) ---
        for (var p : g.getPlayers()) {
            if ("HUNTER".equals(p.getRole())) {
                p.getActions().addAll(List.of(
                        "FUMIGATION_AIL", "FUMIGATION_AIL",
                        "PISTEUR", "PISTEUR",
                        "FEU_DE_CAMP", "FEU_DE_CAMP",
                        "FILET", "FILET",
                        "FOSSE", "FOSSE"
                ));
            }
        }

        // --- Deck actions chasseurs ---
        Map<String,Integer> hunterPool = new HashMap<>();
        hunterPool.put("FUMIGATION_AIL",  4);
        hunterPool.put("PISTEUR",         4);
        hunterPool.put("FEU_DE_CAMP",     4);
        hunterPool.put("FILET",           2);
        hunterPool.put("FOSSE",           2);
        g.setHunterActionsPool(hunterPool);
        g.setHunterActionsDiscardPool(new HashMap<>());

        // --- Deck actions vampire ---
        Map<String,Integer> vampPool = new HashMap<>();
        // vampPool.put("TOTOSTORY", 3);
        g.setVampActionsPool(vampPool);
        g.setVampActionsDiscardPool(new HashMap<>());

        // --- Deck potions : composition initiale ---
        Map<String,Integer> potionsPool = new HashMap<>();
        potionsPool.put("FORCE",           3);
        potionsPool.put("ENDURANCE",       3);
        potionsPool.put("VIE",             4);
        potionsPool.put("FOCALISATION",    2);
        potionsPool.put("SANGSUE",         2);
        potionsPool.put("RESILIENCE",      2);
        potionsPool.put("RAGE",            2);
        potionsPool.put("RAPIDITE",        2);
        potionsPool.put("INVISIBILITE",    1);
        potionsPool.put("INVULNERABILITE", 1);

        g.setPotionsPool(potionsPool);
        g.setPotionsDiscardPool(new HashMap<>());

        g.setCenter(new ArrayList<>());

        // --- Structures de raid (vides, prêtes) ---
        if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());
        else g.getRaidMods().clear();

        if (g.getRaidEffects() == null) g.setRaidEffects(new HashMap<>());
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
            // (1) Notifier le lobby (si tu l’utilises)
            live.gameCreated(g);

            // (2) Petite ligne de feed (indépendante des messages persistés)
            pushLive(g, "Préparation du tirage météo…");

            // (3) Optionnel mais propre: informer le front que les mods sont (ré)initialisés
            live.raidModsUpdated(g);

            // (4) Phase visible côté clients → ils feront un GET propre après cet event
            live.phaseChanged(g);
        });

        return g;
    }

    private boolean allHuntersSelected(@NonNull Game g) {
        var hunters = getHunters(g);
        if (hunters.isEmpty()) return false;
        for (var h : hunters) {
            if (!hasPlayed(g, h.getId())) return false;
        }
        return true;
    }

    private boolean vampireSelected(@NonNull Game g) {
        var vamp = getVamp(g);
        return vamp.isPresent() && hasPlayed(g, vamp.get().getId());
    }

    private boolean allVampSideSelected(Game g) {
        // le vampire doit toujours avoir joué (même s'il y a 0 servant)
        var vamp = getVamp(g).orElseThrow();

        boolean vampireOk = hasPlayed(g, vamp.getId());

        boolean allServantsOk = g.getPlayers().stream()
                .filter(p -> "SERVANT".equals(p.getRole()))
                .filter(p -> p.getHp() > 0) // on ignore les servants KO pour ne pas bloquer
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
                g.setWeatherStatusNameFr(null);
                g.setWeatherDescriptionFr(null);
                g.setMessages(new ArrayList<>(List.of("Tirage météo ...")));

                // reset des effets one-shot de raid
                if (g.getTrackerHunters() == null) {
                    g.setTrackerHunters(new java.util.HashSet<>());
                } else {
                    g.getTrackerHunters().clear();
                }

                g.setCurrentAction(null);

                if (g.getNetHunters() == null) g.setNetHunters(new java.util.HashSet<>());
                else g.getNetHunters().clear();

                if (g.getPitHunters() == null) g.setPitHunters(new java.util.HashSet<>());
                else g.getPitHunters().clear();

                // Purges/rafs “début de raid”
                if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());
                for (var list : g.getRaidMods().values()) {
                    if (list != null) {
                        list.removeIf(m -> {
                            String s = m.getSource();
                            return s != null && (s.startsWith("POTION:") || s.startsWith("ACTION:"));
                        });
                    }
                }

                // ⚠️ mutation OK ici (aucun event) :
                rebuildCorruptionMods(g);
                rebuildWeatherMods(g);

                if (g.getRaidEffects() == null) g.setRaidEffects(new HashMap<>());
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
                for (var cb : g.getCenter()) cb.setFaceUp(true);

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
                        int roll = 1 + RND.nextInt(6);
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
                                    new ArrayList<>(List.of("forest","quarry","lake","manor")));

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

                center.addAll(buildRevealMessages(g));
                g.setMessages(center);
                for (var m : history) addHistory(g, m);

                // 4) Flags et liste des participants
                boolean hasPendingUnstable =
                        !(g.getUnstableEligibleTargets().isEmpty() && g.getUnstableEligibleLocations().isEmpty());
                boolean upcoming = computeHasUpcomingCombat(g); // <-- déjà corrigé pour ignorer les récolteurs
                g.setHasUpcomingCombat(upcoming);

                // 5) "Prêt" pour non-participants (Vampire/Serviteurs/Chasseurs hors combat)
                g.getReadyForPhase3().clear();
                if (g.isHasUpcomingCombat()) {
                    var participants = participantsOfUpcomingCombat(g); // <-- version corrigée ci-dessus
                    for (var p : g.getPlayers()) {
                        if (!participants.contains(p.getId())) {
                            g.getReadyForPhase3().add(p.getId()); // auto-prêt si hors combat
                        }
                    }
                }

                // 6) Cadence : long si instables en attente OU combats ; sinon avance courte
                if (hasPendingUnstable || g.isHasUpcomingCombat()) {
                    schedulePrephaseTimeout(g.getId(), 30_000);
                } else {
                    scheduleAdvance(g.getId(), Phase.PREPHASE3, Phase.PHASE3, 4000);
                }
            }

            case PHASE3 -> {
                if (g.getHarvestedRaid() == null || !g.getHarvestedRaid().equals(g.getRaid())) {
                    applyHarvests(g);
                    g.setHarvestedRaid(g.getRaid());
                }
                buildCombatsQueue(g);
                prepareFirstTrapAction(g);
            }

            case PHASE4 -> {
                for (var cb : g.getCenter()) {
                    var p = g.getPlayers().stream().filter(pp -> pp.getId().equals(cb.getPlayerId())).findFirst().orElse(null);
                    if (p != null) {
                        if (p.getHand() == null) p.setHand(new ArrayList<>());
                        p.getHand().add(cb.getCard());
                    }
                }
                g.getCenter().clear();
                g.setMessages(new ArrayList<>(List.of("Maintenance…")));
                addHistory(g, "Maintenance…");

                // reset modale
                g.getReadyForNextRaid().clear();
                long deadline = System.currentTimeMillis() + 120_000L;
                g.setPhase4DeadlineMillis(deadline);

                int raidForTimeout = g.getRaid(); // raid actuel
                schedulePhase4Timeout(g.getId(), 120_000L, raidForTimeout);
            }

            default -> { /* rien */ }
        }
    }

    public Game advancePhase(String gameId, String userId, Phase to) {
        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing user id");

        tx.execute(status -> {
            Game g = findOr404(gameId);

            Phase cur = g.getPhase();
            if (cur == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "no current phase");

            if (cur == to) {
                // no-op idempotent: on est déjà à la phase demandée
                save(g);
                return g;
            }

            switch (cur) {
                case PHASE0 -> {
                    if (to != Phase.PHASE1) throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    if (g.getWeatherRoll() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "weather not rolled");
                    applyPhaseEntry(g, Phase.PHASE1);
                    g.setCurrentAction(null);
                }
                case PHASE1 -> {
                    if (to != Phase.PHASE2) throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    if (!allHuntersSelected(g)) throw new ResponseStatusException(HttpStatus.CONFLICT, "hunters not all selected");
                    applyPhaseEntry(g, Phase.PHASE2);
                    g.setCurrentAction(null);
                }
                case PHASE2 -> {
                    if (to != Phase.PREPHASE3) throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    if (!allVampSideSelected(g)) throw new ResponseStatusException(HttpStatus.CONFLICT, "vamp/servants not all selected");
                    g.setHasUpcomingCombat(computeHasUpcomingCombat(g));
                    applyPhaseEntry(g, Phase.PREPHASE3);
                    g.setCurrentAction(null);
                }
                case PREPHASE3 -> {
                    if (to != Phase.PHASE3) throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    boolean hasPendingUnstable =
                            !(g.getUnstableEligibleTargets().isEmpty() && g.getUnstableEligibleLocations().isEmpty());
                    if (hasPendingUnstable) throw new ResponseStatusException(HttpStatus.CONFLICT, "unstable choices pending");
                    if (!allReadyForPhase3(g)) throw new ResponseStatusException(HttpStatus.CONFLICT, "players not ready");
                    applyPhaseEntry(g, Phase.PHASE3);
                    prepareFirstTrapAction(g);
                }
                case PHASE3 -> {
                    if (to != Phase.PHASE4) throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    applyPhaseEntry(g, Phase.PHASE4);
                    g.setCurrentAction(null);
                }
                case PHASE4 -> {
                    if (to != Phase.PHASE0) throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
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
        raidScheduler.schedule(() ->
                        tx.execute(status -> {
                            Game g = findOr404(gameId);

                            // Garde-fous
                            if (g.getStatus() != GameStatus.ACTIVE) return null;
                            if (g.getPhase() != expected)           return null;

                            // Mutation + persist
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
                        })
                , java.time.Instant.now().plusMillis(delayMs));
    }

    private void schedulePrephaseTimeout(String gameId, long millis) {
        raidScheduler.schedule(() ->
                        tx.execute(status -> {
                            Game g2 = findOr404(gameId);

                            // Garde-fous : partie active + toujours en PREPHASE3 ?
                            if (g2.getStatus() != GameStatus.ACTIVE || g2.getPhase() != Phase.PREPHASE3) {
                                return null; // rien à faire
                            }

                            // Si des choix "instable" restent en attente, on NE force pas
                            boolean hasPendingUnstable =
                                    !(g2.getUnstableEligibleTargets().isEmpty() && g2.getUnstableEligibleLocations().isEmpty());
                            if (hasPendingUnstable) {
                                return null;
                            }

                            // OK, on avance vers PHASE3
                            applyPhaseEntry(g2, Phase.PHASE3);
                            save(g2);

                            // Tous les events après COMMIT uniquement
                            afterCommit(() -> {
                                Game fresh = findOr404(gameId);    // état frais et commité
                                live.phaseChanged(fresh);
                            });

                            return null;
                        })
                , java.time.Instant.now().plusMillis(millis));
    }

    private void schedulePhase4Timeout(String gameId, long millis, int expectedRaid) {
        raidScheduler.schedule(() ->
                        tx.execute(status -> {
                            Game g2 = findOr404(gameId);

                            // 1) Partie toujours active ?
                            if (g2.getStatus() != GameStatus.ACTIVE) return null;

                            // 2) Toujours en PHASE4 ?
                            if (g2.getPhase() != Phase.PHASE4) return null;

                            // 3) Toujours le même raid que celui pour lequel ce timer a été posé ?
                            if (g2.getRaid() != expectedRaid) return null;

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
                java.time.Instant.now().plusMillis(millis)
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

        // Pose au centre : faceUp = true si fumigation active, sinon face cachée
        CenterBoard cb = new CenterBoard(playerId, card, /*faceUp=*/fumigate);
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
        boolean advanceToP2   = (g.getPhase() == Phase.PHASE1) && allHuntersSelected(g);
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

    // Regroupe les joueurs par lieu posé au centre (faceUp n’a pas d’importance ici)
    @NonNull
    private Map<String, List<Player>> groupPlayersByLocation(@NonNull Game g) {
        Map<String, List<Player>> map = new HashMap<>();
        for (var cb : g.getCenter()) {
            String loc = cb.getCard();
            var p = g.getPlayers().stream().filter(pp -> pp.getId().equals(cb.getPlayerId())).findFirst().orElse(null);
            if (p == null) continue;
            map.computeIfAbsent(loc, __ -> new ArrayList<>()).add(p);
        }
        return map;
    }

    private String locationOf(Game g, String playerId) {
        if (g.getCenter() == null) return null;
        return g.getCenter().stream()
                .filter(cb -> playerId.equals(cb.getPlayerId()))
                .map(CenterBoard::getCard)
                .findFirst()
                .orElse(null);
    }

    private java.util.List<Player> playersOnLocation(Game g, String location) {
        if (location == null) return java.util.List.of();
        return g.getPlayers().stream()
                .filter(p -> location.equals(locationOf(g, p.getId())))
                .toList();
    }

    private java.util.List<Player> vampSideOnLocation(Game g, String location) {
        return playersOnLocation(g, location).stream()
                .filter(p -> "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                .toList();
    }

    // Construit les messages pour la révélation (combat / récolte)
    @NonNull
    private List<String> buildRevealMessages(Game g) {
        List<String> out = new ArrayList<>();

        var groups = groupPlayersByLocation(g);

        // chasseurs instables à choix (ne récoltent pas ici)
        var instablePending = new java.util.HashSet<String>();
        instablePending.addAll(g.getUnstableEligibleTargets().keySet());
        instablePending.addAll(g.getUnstableEligibleLocations().keySet());

        boolean anyCombat = false;

        for (var e : groups.entrySet()) {
            String loc = e.getKey();
            List<Player> onLoc = e.getValue();

            var enemiesHere = onLoc.stream()
                    .filter(p -> "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                    .toList();

            var huntersHere = onLoc.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .toList();

            boolean combatHere = !enemiesHere.isEmpty() && !huntersHere.isEmpty();

            if (!combatHere) {
                // Récoltes indépendantes
                for (var p : onLoc) {
                    boolean isHunter = "HUNTER".equals(p.getRole());
                    if (isHunter && instablePending.contains(p.getId())) {
                        // On n’affiche pas sa récolte ici : son cas sera résolu par la décision du vampire.
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
            } else {
                // Messages combat “par défaut” (instables exclus par la suite dans la file de combats)
                anyCombat = true;

                // chasseurs affichés
                String huntersNames = String.join(", ",
                        huntersHere.stream().map(h -> {
                            String n = h.getUsername();
                            return (n != null && !n.isBlank()) ? n : h.getId();
                        }).toList()
                );
                for (var enemy : enemiesHere) {
                    String enemyName = nameOf(g, enemy.getId());
                    out.add("Combat — " + enemyName + " VS " + huntersNames + " à " + labelLieuFr(loc));
                }
            }
        }

        // on n'ajoute pas ici les combats instable -> cible.
        // Ces messages sont poussés au moment du choix (assignUnstableTarget),
        // ce qui garantit qu’ils arrivent après "redirigé vers ..." et donc en dernier.

        if (out.isEmpty()) out.add("Aucune carte jouée.");

        // expose à PREPHASE3 s’il y a au moins un combat “par défaut”
        g.setHasUpcomingCombat(anyCombat || !g.getUnstableTargetByPlayer().isEmpty());

        return out;
    }

    // Mini label FR pour l’affichage des lieux
    private String labelLieuFr(@NonNull String c){
        return switch (c) {
            case "forest" -> "Forêt";
            case "quarry" -> "Carrière";
            case "lake" -> "Lac";
            case "manor" -> "Manoir";
            default -> c;
        };
    }

    // Tout le monde prêt pour PHASE3 ?
    private boolean allReadyForPhase3(@NonNull Game g) {
        if (!g.isHasUpcomingCombat()) return true;
        var needed = participantsOfUpcomingCombat(g);
        return g.getReadyForPhase3().containsAll(needed);
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
        g.getReadyForPhase3().add(playerId);
        int ready = g.getReadyForPhase3().size();
        int total = g.getPlayers().size();

        // 2) Décide si on avance maintenant (dans CETTE transaction)
        boolean hasPendingUnstable =
                !(g.getUnstableEligibleTargets().isEmpty() && g.getUnstableEligibleLocations().isEmpty());

        boolean advanceNow =
                (g.getPhase() == Phase.PREPHASE3) && !hasPendingUnstable && allReadyForPhase3(g);

        if (advanceNow) {
            // Mutation uniquement (pas d'event ici)
            applyPhaseEntry(g, Phase.PHASE3);
        }

        // 3) Commit de l'état
        save(g);

        // 4) Events APRÈS COMMIT (zéro course avec les GET côté front)
        final int fReady = ready, fTotal = total;
        final String fPid = playerId;
        final boolean fAdvance = advanceNow;

        afterCommit(() -> {
            // Notifie le compteur (utile même si déjà prêt, pour resync robuste)
            live.readyUpdated(g, fPid, fReady, fTotal);

            // Si tout le monde était prêt, on vient d'entrer en PHASE3 → push
            if (fAdvance) {
                live.phaseChanged(g);
            }
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
        record DeletedTrade(String id, String aId, String bId) {}
        var deleted = new java.util.ArrayList<DeletedTrade>();

        for (var t : new java.util.ArrayList<>(g.getTrades())) {
            boolean iAmA = userId.equals(t.getAId());
            boolean iAmB = userId.equals(t.getBId());
            if (!iAmA && !iAmB) continue;

            if (iAmA) t.setStatusA("CANCELLED"); else t.setStatusB("CANCELLED");
            t.setUpdatedAt(System.currentTimeMillis());

            boolean aFinal = isFinal(t.getStatusA());
            boolean bFinal = isFinal(t.getStatusB());
            if (aFinal && bFinal) {
                deleted.add(new DeletedTrade(t.getId(), t.getAId(), t.getBId()));
                toDelete.add(t);
            }
        }
        g.getTrades().removeAll(toDelete);

        // 3) Tout le monde est prêt → on déclenche la phase suivante immédiatement
        boolean everyone = g.getReadyForNextRaid().size() >= g.getPlayers().size();
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
                        Map.of()
                );
            }
            // notifier “prêt” pour PHASE4 (évènement distinct de la préphase)
            live.phase4ReadyUpdated(g, userId, g.getReadyForNextRaid().size(), g.getPlayers().size());

            if (fEveryone) live.phaseChanged(g);
        });

        return g;
    }

// Fight
    /**
     * Construit la file de duels (PHASE3) :
     *  - pour chaque lieu où vampire + ≥1 chasseur: push Hunter->Vamp puis Vamp->Hunter.
     *  - ajoute ensuite les duels "instable -> cible" enregistrés en PREPHASE3.
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

        // 1) Combats par défaut : (ennemi ∈ {VAMPIRE,SERVANT}) × (HUNTER non réaffecté)
        for (var e : groups.entrySet()) {
            String loc = e.getKey();
            java.util.List<Player> onLoc = e.getValue();

            var enemies = onLoc.stream()
                    .filter(p -> "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                    .toList();

            var huntersForDefault = onLoc.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> !unstableAssigned.contains(p.getId()))
                    .toList();

            for (var enemy : enemies) {
                for (var h : huntersForDefault) {

                    boolean hunterInvisible = hasInvisibility(g, h.getId());
                    boolean enemyInvisible  = hasInvisibility(g, enemy.getId());

                    if (hunterInvisible && !enemyInvisible) {
                        // Le chasseur est invisible : il attaque, mais l’ennemi
                        // n’aura PAS de round inverse en tant qu’attaquant.
                        RoundFight r1 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, h.getId(), enemy.getId()
                        );
                        r1.setDefenderRoll(0); // l’ennemi ne lance pas de dé de défense
                        g.getCombatsQueue().add(r1);

                    } else if (enemyInvisible && !hunterInvisible) {
                        // L’ennemi est invisible : il attaque, mais le chasseur
                        // n’aura PAS de round inverse en tant qu’attaquant.
                        RoundFight r2 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, enemy.getId(), h.getId()
                        );
                        r2.setDefenderRoll(0); // le chasseur ne lance pas de dé de défense
                        g.getCombatsQueue().add(r2);

                    } else {
                        // Cas "classique" (personne ou les deux invisibles) :
                        // on garde les deux rounds aller/retour comme avant.
                        RoundFight r1 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, h.getId(), enemy.getId()
                        );
                        if (hunterInvisible) {
                            r1.setDefenderRoll(0);
                        }
                        g.getCombatsQueue().add(r1);

                        RoundFight r2 = new RoundFight(
                                java.util.UUID.randomUUID().toString(), loc, enemy.getId(), h.getId()
                        );
                        if (enemyInvisible) {
                            r2.setDefenderRoll(0);
                        }
                        g.getCombatsQueue().add(r2);
                    }
                }
            }
        }

        // 2) Duels "instable -> cible"
        if (g.getUnstableTargetByPlayer() != null) {
            for (var entry : g.getUnstableTargetByPlayer().entrySet()) {
                String unstableId = entry.getKey();
                String targetId   = entry.getValue();

                String loc = g.getCenter().stream()
                        .filter(cb -> cb.getPlayerId().equals(targetId))
                        .map(CenterBoard::getCard)
                        .findFirst()
                        .orElse("forest");

                RoundFight duel = new RoundFight(
                        java.util.UUID.randomUUID().toString(), loc, unstableId, targetId
                );
                // Si l’instable a bu Invisibilité, sa cible ne lancera pas de dé
                if (hasInvisibility(g, unstableId)) {
                    duel.setDefenderRoll(0);
                }
                g.getCombatsQueue().add(duel);

                // Message lisible
                String info = "Combat — " + nameOf(g, unstableId) + " VS "
                        + nameOf(g, targetId) + " à " + labelLieuFr(loc);
                if (g.getMessages() == null) g.setMessages(new java.util.ArrayList<>());
                g.getMessages().add(info);
                addHistory(g, info);
            }
        }

// 3) appliquer Potion de rapidité
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
                            rf.getDefenderId()
                    );
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


        // 4) Pointeur sur le combat courant (plus aucun nextAdvanceAt/timer côté serveur)
        if (!g.getCombatsQueue().isEmpty()) {
            g.setCurrentCombatIndex(0);
            g.setCurrentCombat(g.getCombatsQueue().get(0));
        } else {
            g.setCurrentCombatIndex(null);
            g.setCurrentCombat(null);
        }
    }

    @Transactional
    public Game rollDice(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE || g.getPhase() != Phase.PHASE3 || g.getCurrentCombat() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in combat");
        }
        if (g.getCurrentBite() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "bite pending");
        }

        var r = g.getCurrentCombat();

        // Effets de raid pour ce joueur (dont Potion de focalisation)
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
            var p = g.getPlayers().stream().filter(pp -> pp.getId().equals(userId)).findFirst().orElseThrow();
            int sides = diceSides(p.getAttackDice());

            if (hasFocus) {
                // Potion de focalisation : 2 appels possibles

                if (r.getAttackerFirstRoll() == null && r.getAttackerRoll() == null) {
                    // 1er appel : on stocke le 1er résultat, mais PAS de roll final
                    int roll1 = 1 + RND.nextInt(sides);
                    r.setAttackerFirstRoll(roll1);

                    addHistory(g, nameOf(g, r.getAttackerId())
                            + " — jet d'attaque (Potion de focalisation, premier dé) = " + roll1 + ".");

                    // on envoie quand même un event pour afficher ce 1er dé
                    ev.sendAtk = true; ev.atkId = r.getAttackerId(); ev.atkRoll = roll1;

                    // NOTE : pas de r.setAttackerRoll(...) ici -> pas de résolution possible
                }
                else if (r.getAttackerFirstRoll() != null && r.getAttackerRoll() == null) {
                    // 2e appel : on lance un 2e dé, on garde le meilleur, et C'EST le jet final
                    int roll2 = 1 + RND.nextInt(sides);
                    int first = r.getAttackerFirstRoll();
                    int best  = Math.max(first, roll2);

                    r.setAttackerReroll(roll2);
                    r.setAttackerRoll(best);

                    addHistory(g, nameOf(g, r.getAttackerId())
                            + " — relance d'attaque grâce à la Potion de focalisation : "
                            + first + " → " + roll2 + " (garde " + best + ").");

                    ev.sendAtk = true; ev.atkId = r.getAttackerId(); ev.atkRoll = best;
                }
                else {
                    // on a déjà un jet final => plus rien à lancer
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                }

            } else {
                // Pas de potion de focalisation : comportement identique à avant
                if (r.getAttackerRoll() == null) {
                    int roll = 1 + RND.nextInt(sides);
                    r.setAttackerRoll(roll);
                    addHistory(g, nameOf(g, r.getAttackerId()) + " — jet d'attaque = " + roll + ".");
                    ev.sendAtk = true; ev.atkId = r.getAttackerId(); ev.atkRoll = roll;

                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                }
            }

        } else if (userId.equals(r.getDefenderId())) {
            var p = g.getPlayers().stream().filter(pp -> pp.getId().equals(userId)).findFirst().orElseThrow();
            int sides = diceSides(p.getDefenseDice());

            if (hasFocus) {
                if (r.getDefenderFirstRoll() == null && r.getDefenderRoll() == null) {
                    // 1er jet de défense, uniquement stocké comme "premier dé"
                    int roll1 = 1 + RND.nextInt(sides);
                    r.setDefenderFirstRoll(roll1);

                    addHistory(g, nameOf(g, r.getDefenderId())
                            + " — jet de défense (Potion de focalisation, premier dé) = " + roll1 + ".");

                    ev.sendDef = true; ev.defId = r.getDefenderId(); ev.defRoll = roll1;

                } else if (r.getDefenderFirstRoll() != null && r.getDefenderRoll() == null) {
                    // 2e appel : on fixe le jet final (meilleur des deux)
                    int roll2 = 1 + RND.nextInt(sides);
                    int first = r.getDefenderFirstRoll();
                    int best  = Math.max(first, roll2);

                    r.setDefenderReroll(roll2);
                    r.setDefenderRoll(best);

                    addHistory(g, nameOf(g, r.getDefenderId())
                            + " — relance de défense grâce à la Potion de focalisation : "
                            + first + " → " + roll2 + " (garde " + best + ").");

                    ev.sendDef = true; ev.defId = r.getDefenderId(); ev.defRoll = best;

                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                }

            } else {
                // Pas de potion : comportement initial
                if (r.getDefenderRoll() == null) {
                    int roll = 1 + RND.nextInt(sides);
                    r.setDefenderRoll(roll);
                    addHistory(g, nameOf(g, r.getDefenderId()) + " — jet de défense = " + roll + ".");
                    ev.sendDef = true; ev.defId = r.getDefenderId(); ev.defRoll = roll;

                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
                }
            }

        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
        }

        // --- Résolution si les 2 jets sont posés (et pas encore résolu)
        if (r.getAttackerRoll() != null && r.getDefenderRoll() != null && combatNotResolved(r)) {
            int rawAtk = r.getAttackerRoll();
            int rawDef = r.getDefenderRoll();   // 0 d’emblée si Invisibilité a joué lors de la création du duel

            String loc = r.getLocation();

            int atkMod = totalModFor(g, r.getAttackerId(), "ATTACK", loc);
            int defMod = totalModFor(g, r.getDefenderId(), "DEFENSE", loc);

            // Effets de raid (potions) pour attaquant et défenseur
            RaidEffects atkFx = (g.getRaidEffects() != null)
                    ? g.getRaidEffects().get(r.getAttackerId())
                    : null;

            RaidEffects defFx = (g.getRaidEffects() != null)
                    ? g.getRaidEffects().get(r.getDefenderId())
                    : null;

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

            // Invulnérabilité : annule les dégâts
            boolean preventedByInvuln = false;
            if (defFx != null && defFx.isInvulnerable() && dmg > 0) {
                preventedByInvuln = true;
                dmg = 0;
            }

            var defPlayer = g.getPlayers().stream()
                    .filter(p -> p.getId().equals(r.getDefenderId()))
                    .findFirst().orElse(null);
            if (defPlayer != null && dmg > 0) {
                defPlayer.setHp(Math.max(0, defPlayer.getHp() - dmg));
            }

            var atkPlayer = g.getPlayers().stream()
                    .filter(p -> p.getId().equals(r.getAttackerId()))
                    .findFirst().orElse(null);

            // Sangsue : se soigne des dégâts infligés (après invulnérabilité)
            int healedByLeech = 0;
            if (atkPlayer != null && atkFx != null && atkFx.isLeech() && dmg > 0) {
                int beforeHp = atkPlayer.getHp();
                int maxHp    = maxHpFor(g, atkPlayer);
                atkPlayer.setHp(Math.min(maxHp, atkPlayer.getHp() + dmg));
                healedByLeech = atkPlayer.getHp() - beforeHp;
            }

            // Vol d’objet par le vampire (si dégâts finaux > 0)
            String theftLine = null;
            if (atkPlayer != null && "VAMPIRE".equals(atkPlayer.getRole())
                    && defPlayer != null && "HUNTER".equals(defPlayer.getRole()) && dmg > 0) {
                theftLine = vampStealOne(g, atkPlayer, defPlayer);
            }

            int defRollForBreakdown = rawDef;

            var atkBk = buildModBreakdownLines(g, r.getAttackerId(), "ATTACK", rawAtk, loc);
            var defBk = buildModBreakdownLines(g, r.getDefenderId(), "DEFENSE", defRollForBreakdown, loc);

            // --- Bonus de la Potion de focalisation (inchangé) ---
            if (atkFx != null && atkFx.isFocus()
                    && r.getAttackerFirstRoll() != null
                    && r.getAttackerRoll() != null
                    && r.getAttackerRoll() > r.getAttackerFirstRoll()) {

                int diff = r.getAttackerRoll() - r.getAttackerFirstRoll();
                atkBk.add(nameOf(g, r.getAttackerId())
                        + " a gagné " + diff + " de stat par l'effet Potion de focalisation.");
            }

            if (defFx != null && defFx.isFocus()
                    && r.getDefenderFirstRoll() != null
                    && r.getDefenderRoll() != null
                    && r.getDefenderRoll() > r.getDefenderFirstRoll()) {

                int diff = r.getDefenderRoll() - r.getDefenderFirstRoll();
                defBk.add(nameOf(g, r.getDefenderId())
                        + " a gagné " + diff + " de stat par l'effet Potion de focalisation.");
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
                                + " attaque une deuxième fois par l'effet de la Potion de rapidité."
                );
            }

            // Historique détaillé
            for (String ln : atkBk) addHistory(g, ln);
            for (String ln : defBk) addHistory(g, ln);

            if (r.getBreakdownLines() == null) r.setBreakdownLines(new java.util.ArrayList<>());
            r.getBreakdownLines().clear();
            r.getBreakdownLines().addAll(atkBk);
            r.getBreakdownLines().addAll(defBk);
            if (theftLine != null) r.getBreakdownLines().add(theftLine);

            String an = nameOf(g, r.getAttackerId());
            String dn = nameOf(g, r.getDefenderId());
            if (dmg > 0) addHistory(g, an + " inflige " + dmg + " dégâts à " + dn);
            else         addHistory(g, dn + " pare l'attaque de " + an);

            if (g.getUnstableTargetByPlayer().containsKey(r.getAttackerId())
                    && java.util.Objects.equals(g.getUnstableTargetByPlayer().get(r.getAttackerId()), r.getDefenderId())) {
                addHistory(g, nameOf(g, r.getAttackerId()) + " revient à lui ...");
            }

            ev.sendResolved = true;
            ev.dmg = dmg;
            ev.defId = r.getDefenderId();
            ev.defenderHp = (defPlayer != null) ? defPlayer.getHp() : 0;
            ev.breakdown = new java.util.ArrayList<>(r.getBreakdownLines());

            boolean vampInflicted = (atkPlayer != null && "VAMPIRE".equals(atkPlayer.getRole())
                    && defPlayer != null && "HUNTER".equals(defPlayer.getRole()) && dmg > 0);
            if (vampInflicted) {
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

            r.setResolvedAtMillis(System.currentTimeMillis());
        }


        // --- Commit
        save(g);

        // --- Events APRÈS COMMIT (ordre garanti)
        afterCommit(() -> {
            if (ev.sendAtk)      live.diceRolled(g, ev.roundId, ev.atkId, "ATTACK",  ev.atkRoll);
            if (ev.sendDef)      live.diceRolled(g, ev.roundId, ev.defId, "DEFENSE", ev.defRoll);
            if (ev.sendResolved) live.combatResolved(g, ev.roundId, ev.dmg, ev.defId, ev.defenderHp, ev.breakdown);
            if (ev.startBite)    live.biteStarted(g, ev.biteAtt, ev.biteTgt, ev.biteLoc);

            // “heartbeat” minimal pour forcer un GET propre chez tous (si tu veux le garder)
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

            String att, tgt, loc;          // pour la morsure
            String trapMode, trapOwnerId, trapTargetId; // pour Filet/Fosse
        }

        Ev ev = tx.execute(status -> {
            Game g = findOr404(gameId);
            if (g.getPhase() != Phase.PHASE3)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE3");

            Ev out = new Ev();

            // 0) GESTION DES PIÈGES (NET / PIT) AVANT TOUT
            Game.Action ca = g.getCurrentAction();
            if (ca != null
                    && ca.getMode() != null
                    && ("NET".equals(ca.getMode()) || "PIT".equals(ca.getMode()))) {

                // Si le jet n'est pas encore fait, on ne fait rien : on laisse la modale ouverte
                if (ca.getRoll() == null) {
                    save(g);
                    return out;
                }

                // Le piège vient d'être résolu (roll != null) → on clôture cette action
                out.trapResolved   = true;
                out.trapMode       = ca.getMode();
                out.trapOwnerId    = ca.getOwnerId();
                out.trapTargetId   = ca.getTargetId();

                // On "oublie" l'action courante
                g.setCurrentAction(null);

                // On tente de préparer le piège suivant (si un autre chasseur a Filet/Fosse)
                prepareFirstTrapAction(g); // peut remettre un currentAction, ou laisser null

                // S'il reste un autre piège, on s'arrête là :
                //   - le front affichera le nouveau piège via currentAction
                //   - on NE touche PAS encore aux morsures / combats
                if (g.getCurrentAction() != null) {
                    save(g);
                    return out;
                }

                // Sinon : plus de pièges → on laisse continuer la logique classique
                // (morsure / combats) ci-dessous dans le même appel.
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
                if (r == null) {
                    save(g);
                    return out;
                }
                if (combatNotResolved(r)) {
                    save(g);
                    return out;
                }
            }

            // 3) Avancer la file uniquement quand c’est safe (morsure close ou duel résolu)
            Integer idx = g.getCurrentCombatIndex();
            if (idx == null) idx = 0;
            int next = idx + 1;

            if (g.getCombatsQueue() != null && next < g.getCombatsQueue().size()) {
                g.setCurrentCombatIndex(next);
                g.setCurrentCombat(g.getCombatsQueue().get(next));
            } else {
                g.setCurrentCombatIndex(null);
                g.setCurrentCombat(null);
            }
            out.advanced = true;

            save(g);
            return out;
        });

        Game gAfter = findOr404(gameId);

        // Events après commit
        if (ev.biteResolved) {
            live.biteResolved(gAfter, ev.att, ev.tgt, ev.loc);
        }
        if (ev.trapResolved) {
            // ⬅️ C'est cet event que ton front écoute (ACTION_RESOLVED)
            live.actionResolved(gAfter, ev.trapMode, ev.trapOwnerId, ev.trapTargetId);
        }
        if (ev.advanced) {
            // heartbeat pour faire faire un GET propre côté front (combats qui avancent)
            live.phaseChanged(gAfter);
        }

        return gAfter;
    }

// Météo
    // alimente raidMods
    private void rebuildWeatherMods(Game g){
        // Sécurité : map toujours présente
        if (g.getRaidMods() == null) g.setRaidMods(new java.util.HashMap<>());

        // 1) retirer tous les effets météo précédents
        for (var entry : g.getRaidMods().entrySet()) {
            var list = entry.getValue();
            if (list == null) continue;
            list.removeIf(m -> m.getSource() != null && m.getSource().startsWith("WEATHER:"));
        }

        // 2) si pas de météo active (PHASE0 juste avant tirage) => on s’arrête là
        if (g.getWeatherStatus() == null) return;

        // 3) s'assurer qu'il y a une liste pour chaque joueur
        for (var p : g.getPlayers())
            g.getRaidMods().computeIfAbsent(p.getId(), __ -> new java.util.ArrayList<>());

        WeatherStatus ws = g.getWeatherStatus();
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
            case WIND     -> { /* pas de mod de combat */ }
            case CLOUDY   -> { /* aucun mod */ }
            case STORM    -> { for (var p : g.getPlayers()) g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", -2, "WEATHER:STORM")); }
            case RAIN     -> { for (var p : g.getPlayers()) g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", -2, "WEATHER:RAIN")); }
            case BLIZZARD -> { for (var p : g.getPlayers()) g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", -1, "WEATHER:BLIZZARD")); }
            case DUSK     -> {
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
        }
    }

    @Transactional
    public Game rollWeather(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (userId == null || userId.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing user id");
        if (g.getPhase() != Phase.PHASE0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in weather phase");

        var vamp = getVamp(g).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "no vampire")
        );
        if (!vamp.getId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can roll weather");

        if (g.getWeatherRoll() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "weather already rolled");

        // Sécurité : structures non-null
        if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());

        // 1) Mutations PURES (aucun save / aucun event ici)
        int roll = 1 + RND.nextInt(12);
        applyWeatherRoll(g, roll);

        // 2) Commit
        save(g);

        // 3) Events APRÈS COMMIT (aucune course avec les GET)
        afterCommit(() -> {
            live.weatherRolled(g);      // payload construit depuis g (déjà commité)
            live.raidModsUpdated(g);    // pour rafraîchir les puces météo côté UI
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

// ressources
    private int rollD100Tens() { return RND.nextInt(10) * 10; }

    private void grant(Player p, String res, int qty) {
        if (qty <= 0 || p == null) return;
        switch (res) {
            case "wood"  -> p.setWood(p.getWood() + qty);
            case "herbs" -> p.setHerbs(p.getHerbs() + qty);
            case "stone" -> p.setStone(p.getStone() + qty);
            case "iron"  -> p.setIron(p.getIron() + qty);
            case "water" -> p.setWater(p.getWater() + qty);
            case "gold"  -> p.setGold(p.getGold() + qty);
            case "souls" -> p.setSouls(p.getSouls() + qty);
            case "silver"-> p.setSilver(p.getSilver() + qty);
        }
    }

    private String resLabelFr(String res){
        return switch (res) {
            case "wood"  -> "bois";
            case "herbs" -> "herbe médicinale";
            case "stone" -> "pierre";
            case "iron"  -> "fer";
            case "water" -> "eau pure";
            case "gold"  -> "or";
            case "souls" -> "âmes déchues";
            case "silver"-> "argent";
            default -> res;
        };
    }

    /** Applique les récoltes pour les lieux SANS combat (une seule fois par raid). */
    private void applyHarvests(@NonNull Game g) {
        var vamp = getVamp(g).orElse(null);
        var groups = groupPlayersByLocation(g);

        // Participants à des duels instable → cible : ils ne récoltent pas
        java.util.Set<String> duelParticipants = new java.util.HashSet<>();
        if (g.getUnstableTargetByPlayer() != null) {
            g.getUnstableTargetByPlayer().forEach((unstableId, targetId) -> {
                if (unstableId != null) duelParticipants.add(unstableId);
                if (targetId   != null) duelParticipants.add(targetId);
            });
        }

        // besoin de ce set pour identifier les instables qui récoltent pour le vampire
        java.util.Set<String> unstableHarvesters = (g.getUnstableHarvestLocByPlayer() != null)
                ? g.getUnstableHarvestLocByPlayer().keySet()
                : java.util.Collections.emptySet();

        for (var e : groups.entrySet()) {
            String loc = e.getKey();
            var onLoc = e.getValue();

            // y a-t-il un ennemi (VAMPIRE ou SERVANT) vivant sur le lieu ?
            boolean enemyHere = onLoc.stream()
                    .anyMatch(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                              && p.getHp() > 0);
            // un chasseur vivant (non assigné à la récolte instable) sur le lieu ?
            boolean hunterCausingCombatHere = onLoc.stream()
                    .anyMatch(p -> "HUNTER".equals(p.getRole())
                              && p.getHp() > 0
                              && !unstableHarvesters.contains(p.getId()));
            boolean combatHere = enemyHere && hunterCausingCombatHere;

            for (var p : onLoc) {
                boolean harvestForVamp = g.getUnstableHarvestLocByPlayer() != null
                        && g.getUnstableHarvestLocByPlayer().containsKey(p.getId());

                // Si combat “classique”, on bloque la récolte sauf cas instable → récolte
                if (combatHere && !harvestForVamp) continue;

                // Si ce joueur est engagé dans un duel instable → cible, on bloque sa récolte
                if (duelParticipants.contains(p.getId())) continue;

                Player recipient = (harvestForVamp && vamp != null) ? vamp : p;

                java.util.List<String> gains = new java.util.ArrayList<>();
                switch (loc) {
                    case "forest" -> {
                        grant(recipient, "wood", 1);  gains.add("+1 bois");
                        grant(recipient, "herbs", 2); gains.add("+2 herbe médicinale");
                    }
                    case "quarry" -> {
                        grant(recipient, "iron", 1);  gains.add("+1 fer");
                        grant(recipient, "stone", 2); gains.add("+2 pierre");
                    }
                    case "lake" -> {
                        grant(recipient, "water", 2); gains.add("+2 eau pure");
                        grant(recipient, "herbs", 1); gains.add("+1 herbe médicinale");
                    }
                    case "manor" -> {
                        int roll = rollD100Tens();
                        if (harvestForVamp && vamp != null) {
                            // Instable qui récolte pour le vampire au Manoir → conversion en âmes
                            grant(vamp, "souls", roll);
                            gains.add("+" + roll + " âmes déchues (pour " + nameOf(g, vamp.getId()) + ")");
                        } else {
                            if ("HUNTER".equals(p.getRole())) {
                                grant(p, "gold", roll); gains.add("+" + roll + " or");
                            } else if ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())) {
                                grant(p, "souls", roll); gains.add("+" + roll + " âmes déchues");
                            }
                        }
                    }
                    default -> { /* plus tard */ }
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
        if (h.getWood()  > 0) pool.add("wood");
        if (h.getHerbs() > 0) pool.add("herbs");
        if (h.getStone() > 0) pool.add("stone");
        if (h.getIron()  > 0) pool.add("iron");
        if (h.getWater() > 0) pool.add("water");
        return pool.isEmpty() ? null : pool.get(RND.nextInt(pool.size()));
    }

    private @Nullable String vampStealOne(Game g, Player vamp, Player hunter) {
        String res = pickStealableFromHunter(hunter);
        if (res == null) {
            addHistory(g, nameOf(g, vamp.getId()) + " tente de voler, mais " + nameOf(g, hunter.getId()) + " n'a rien à prendre.");
            return null; // rien à afficher en breakdown
        }
        // retire au chasseur
        switch (res) {
            case "wood"  -> hunter.setWood(hunter.getWood() - 1);
            case "herbs" -> hunter.setHerbs(hunter.getHerbs() - 1);
            case "stone" -> hunter.setStone(hunter.getStone() - 1);
            case "iron"  -> hunter.setIron(hunter.getIron() - 1);
            case "water" -> hunter.setWater(hunter.getWater() - 1);
        }
        // donne au vampire
        grant(vamp, res, 1);

        String line = "Larcin — " + nameOf(g, vamp.getId()) + " vole 1 " + resLabelFr(res) + " à " + nameOf(g, hunter.getId()) + ".";
        addHistory(g, line);

        return line; // ← on renvoie la ligne pour la modale spectateur
    }

// actions & potions
    private RaidEffects raidFx(Game g, String playerId){
        return g.getRaidEffects().computeIfAbsent(playerId, __ -> new RaidEffects());
    }

    /**
    * tous les joueurs concernés par un combat imminent (y compris SERVANT, instable et cible)
    * peuvent utiliser leurs potions pendant la fenêtre PREPHASE3
    */
    private Set<String> participantsOfUpcomingCombat(Game g) {
        Set<String> ids = new HashSet<>();

        // Instables affectés à la RÉCOLTE => ne combattent pas
        Set<String> unstableHarvesters = (g.getUnstableHarvestLocByPlayer() != null)
                ? g.getUnstableHarvestLocByPlayer().keySet()
                : java.util.Set.of();

        var groups = groupPlayersByLocation(g);

        // (A) Combats "par défaut" : (ennemi ∈ {VAMPIRE, SERVANT}) × (HUNTER non-récolteur) au même lieu
        for (var e : groups.entrySet()) {
            var onLoc = e.getValue();

            var enemiesHere = onLoc.stream()
                    .filter(p -> ("VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole())))
                    .filter(p -> p.getHp() > 0)  // KO exclus
                    .toList();

            var huntersHere = onLoc.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> p.getHp() > 0)  // KO exclus
                    .filter(p -> !unstableHarvesters.contains(p.getId())) // récolteurs exclus
                    .toList();

            if (!enemiesHere.isEmpty() && !huntersHere.isEmpty()) {
                enemiesHere.forEach(p -> ids.add(p.getId()));
                huntersHere.forEach(p -> ids.add(p.getId()));
            }
        }

        // (B) Duels "instable -> cible" explicites
        if (g.getUnstableTargetByPlayer() != null) {
            g.getUnstableTargetByPlayer().forEach((unstableId, targetId) -> {
                var u = g.getPlayers().stream().filter(p -> p.getId().equals(unstableId)).findFirst().orElse(null);
                var t = g.getPlayers().stream().filter(p -> p.getId().equals(targetId)).findFirst().orElse(null);
                if (u != null && u.getHp() > 0) ids.add(u.getId());
                if (t != null && t.getHp() > 0) ids.add(t.getId());
            });
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

        // bloque les instables qui succombent à la corruption
        if (g.getPhase() == Phase.PREPHASE3 && hasSuccumbedToCorruption(g, playerId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu succombes à la corruption : tu ne peux pas utiliser tes potions ce raid."
            );
        }

        // --- inventaire sur Player
        Player p = g.getPlayers().stream()
                .filter(pp -> pp.getId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game"));

        List<String> inv = p.getPotions();
        if (inv == null || !inv.contains(type.name()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "potion not in inventory");

        // --- mutations + historique (PAS d'events ici)
        String feedText;

        switch (type) {
            case FORCE -> {
                if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());
                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("ATTACK", +1, "POTION:FORCE"));
                addHistory(g, nameOf(g, playerId) + " utilise une Potion de force.");
                feedText = nameOf(g, playerId) + " boit une Potion de force !";
            }
            case ENDURANCE -> {
                if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());
                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("DEFENSE", +1, "POTION:ENDURANCE"));
                addHistory(g, nameOf(g, playerId) + " utilise une Potion d’endurance.");
                feedText = nameOf(g, playerId) + " boit une Potion d’endurance !";
            }
            case VIE -> {
                int before = p.getHp();
                int max = ("VAMPIRE".equals(p.getRole()))
                        ? (20 + (int) g.getPlayers().stream()
                        .filter(x -> "HUNTER".equals(x.getRole()))
                        .count() * 10)
                        : 20;
                p.setHp(Math.min(max, p.getHp() + 10));
                int healed = p.getHp() - before;
                // (tu n’utilises pas `healed` ici, mais tu peux l’ajouter au texte si tu veux)
                addHistory(g, nameOf(g, playerId) + " utilise une Potion de vie.");
                feedText = nameOf(g, playerId) + " boit une Potion de vie !";
            }
            case FOCALISATION -> {
                var fx = raidFx(g, playerId);
                fx.setFocus(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("FOCALISATION", 0, "POTION:FOCALISATION:DSP"));
                addHistory(g, nameOf(g, playerId)
                        + " utilise une Potion de focalisation.");
                feedText = nameOf(g, playerId) + " boit une Potion de focalisation !";
            }
            case SANGSUE -> {
                var fx = raidFx(g, playerId);
                fx.setLeech(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("SANGSUE", 0, "POTION:SANGSUE:DSP"));
                addHistory(g, nameOf(g, playerId) +
                        " utilise une Potion de sangsue.");
                feedText = nameOf(g, playerId) + " boit une Potion de sangsue !";
            }
            case RAGE -> {
                var fx = raidFx(g, playerId);
                fx.setDoubleAttack(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("RAGE", 0, "POTION:RAGE:DSP"));

                addHistory(g, nameOf(g, playerId)
                        + " utilise une Potion de rage.");
                feedText = nameOf(g, playerId) + " boit une Potion de rage !";
            }
            case RESILIENCE -> {
                var fx = raidFx(g, playerId);
                fx.setDoubleDefense(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("RESILIENCE", 0, "POTION:RESILIENCE:DSP"));

                addHistory(g, nameOf(g, playerId)
                        + " utilise une Potion de résilience.");
                feedText = nameOf(g, playerId) + " boit une Potion de résilience !";
            }
            case RAPIDITE -> {
                var fx = raidFx(g, playerId);
                fx.setRapid(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("RAPIDITE", 0, "POTION:RAPIDITE:DSP"));

                addHistory(g, nameOf(g, playerId)
                        + " utilise une Potion de rapidité.");
                feedText = nameOf(g, playerId) + " boit une Potion de rapidité !";
            }
            case INVISIBILITE -> {
                var fx = raidFx(g, playerId);
                fx.setInvisible(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("INVISIBILITE", 0, "POTION:INVISIBILITE:DSP"));

                addHistory(g, nameOf(g, playerId)
                        + " utilise une Potion d’invisibilité.");
                feedText = nameOf(g, playerId) + " boit une Potion d’invisibilité !";
            }
            case INVULNERABILITE -> {
                var fx = raidFx(g, playerId);
                fx.setInvulnerable(true);

                g.getRaidMods().computeIfAbsent(playerId, __ -> new ArrayList<>())
                        .add(new StatMod("INVULNERABILITE", 0, "POTION:INVULNERABILITE:DSP"));

                addHistory(g, nameOf(g, playerId)
                        + " utilise une Potion d’invulnérabilité.");
                feedText = nameOf(g, playerId) + " boit une Potion d’invulnérabilité !";
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown potion");
        }

        // consommer l’item sur le Player
        inv.remove(type.name());

        // 4) l’envoyer en défausse
        discardPotion(g, type.name());

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

    private int maxHpFor(Game g, Player p){
        if ("VAMPIRE".equals(p.getRole())) {
            long hunters = g.getPlayers().stream()
                    .filter(x -> "HUNTER".equals(x.getRole()))
                    .count();
            return 20 + (int) hunters * 10;
        }
        return 20;
    }

    private boolean hasInvisibility(Game g, String playerId) {
        if (g.getRaidEffects() == null) return false;
        RaidEffects fx = g.getRaidEffects().get(playerId);
        return fx != null && fx.isInvisible();
    }

    private void pushLive(Game g, String msg){
        if (g.getMessages() == null) g.setMessages(new ArrayList<>());
        g.getMessages().add(msg);
        // notifie le front (déjà géré dans onLiveEvent: MESSAGE)
        live.message(g, msg);
    }

    private boolean hasSuccumbedToCorruption(Game g, String playerId) {
        var eligTargets = g.getUnstableEligibleTargets();
        var eligLocs    = g.getUnstableEligibleLocations();

        boolean markedAtk  = (eligTargets != null && eligTargets.containsKey(playerId));
        boolean markedHarv = (eligLocs    != null && eligLocs.containsKey(playerId));

        // si le jet de corruption a "foiré" pour ce joueur, on l'a mis dans l'une des deux maps
        return markedAtk || markedHarv;
    }

    @Transactional
    public Game useAction(String gameId, String playerId, Action type) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        Player p = findPlayer(g, playerId);
        if (p == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        // bloque les instables qui succombent à la corruption
        if (g.getPhase() == Phase.PREPHASE3 && hasSuccumbedToCorruption(g, playerId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Tu succombes à la corruption : tu ne peux pas utiliser tes cartes ce raid."
            );
        }

        // Inventaire sur Player
        List<String> inv = p.getActions();
        if (inv == null || !inv.contains(type.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action not in inventory");
        }

        String feedText;

        switch (type) {
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

                boolean isVamp = "VAMPIRE".equals(p.getRole());
                boolean isHunter = "HUNTER".equals(p.getRole());

                // 4) l’envoyer en défausse
                if (isHunter) discardHunterAction(g, type.name());
                if (isVamp) discardVampAction(g, type.name());

                save(g);

                final String fType     = type.name();
                final String fUserId   = playerId;

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
                            "Impossible d'utiliser pistage pendant une préparation de fumigation.");
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
                boolean advanceToP2 =
                        (g.getPhase() == Phase.PHASE1)
                                && !alreadyPlayed
                                && allHuntersSelected(g);

                // consommer l’action sur le Player
                inv.remove(type.name());

                boolean isVamp = "VAMPIRE".equals(p.getRole());
                boolean isHunter = "HUNTER".equals(p.getRole());

                // 4) l’envoyer en défausse
                if (isHunter) discardHunterAction(g, type.name());
                if (isVamp) discardVampAction(g, type.name());

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
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
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

                boolean isVamp = "VAMPIRE".equals(p.getRole());
                boolean isHunter = "HUNTER".equals(p.getRole());

                // 4) l’envoyer en défausse
                if (isHunter) discardHunterAction(g, type.name());
                if (isVamp) discardVampAction(g, type.name());

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
            case FILET -> {
                if (!"HUNTER".equals(p.getRole())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chasseurs uniquement");
                }
                if (g.getPhase() != Phase.PREPHASE3) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Filet est utilisable uniquement juste avant les combats (PREPHASE3).");
                }

                // Doit participer à un combat, sinon on pourrait lui laisser la jouer pour rien.
                String loc = locationOf(g, playerId);
                if (loc == null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "tu dois être sur un lieu pour utiliser Filet.");
                }

                var enemies = vampSideOnLocation(g, loc);
                if (enemies.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Filet n’a d’effet que s’il y a au moins un ennemi sur ton lieu.");
                }

                // Marque ce chasseur comme ayant un Filet préparé pour ce raid
                g.getNetHunters().add(playerId);

                // consommer l’action sur le Player
                inv.remove(type.name());

                boolean isVamp = "VAMPIRE".equals(p.getRole());
                boolean isHunter = "HUNTER".equals(p.getRole());

                // 4) l’envoyer en défausse
                if (isHunter) discardHunterAction(g, type.name());
                if (isVamp) discardVampAction(g, type.name());

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
                    live.actionUsed(g, fUserId, fType);   // pour refresh front
                });

                return g;
            }
            case FOSSE -> {
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
                if (enemies.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Fosse n’a d’effet que s’il y a au moins un ennemi sur ton lieu.");
                }

                // Marque ce chasseur comme ayant une Fosse préparée
                g.getPitHunters().add(playerId);

                // 🔹 Nouveau : enregistre les victimes pour ce chasseur
                if (g.getPitTargetsByHunter() == null) g.setPitTargetsByHunter(new java.util.HashMap<>());
                if (g.getPitIndexByHunter() == null)   g.setPitIndexByHunter(new java.util.HashMap<>());

                var victimIds = enemies.stream().map(Player::getId).toList();
                g.getPitTargetsByHunter().put(playerId, new java.util.ArrayList<>(victimIds));
                g.getPitIndexByHunter().put(playerId, 0);

                // consommer l’action sur le Player
                inv.remove(type.name());

                boolean isVamp = "VAMPIRE".equals(p.getRole());
                boolean isHunter = "HUNTER".equals(p.getRole());

                // 4) l’envoyer en défausse
                if (isHunter) discardHunterAction(g, type.name());
                if (isVamp) discardVampAction(g, type.name());

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

            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown action");
        }

        return g;
    }


    /**
     * Applique l'effet des chasseurs Pisteur quand le vampire pose son lieu.
     * - si le chasseur avait déjà joué un lieu (ex : fumigé) :
     *   - on lui rend ce lieu dans la main
     * - on consomme le lieu du vampire dans sa main
     * - on met sa carte au centre sur le lieu du vampire
     * - la fumigation reste active (garlicBlockedLocations inchangé)
     */
    private void applyTrackerHuntersWhenVampirePlays(Game g, String vampireLocation) {
        var trackers = g.getTrackerHunters();
        if (trackers == null || trackers.isEmpty()) return;

        // Copie pour éviter ConcurrentModificationException si on modifie le Set
        var ids = new java.util.ArrayList<>(trackers);

        for (String hunterId : ids) {
            var opt = g.getPlayers().stream()
                    .filter(pp -> pp.getId().equals(hunterId))
                    .findFirst();

            if (opt.isEmpty()) continue;
            var hp = opt.get();

            // uniquement des chasseurs vivants
            if (!"HUNTER".equals(hp.getRole()) || hp.getHp() <= 0) continue;

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
            CenterBoard follow = new CenterBoard(hunterId, vampireLocation, /*faceUp=*/false);
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
        if (g.getCampfireLocations() == null) return false;
        if (!g.getCampfireLocations().contains(location)) return false;

        WeatherStatus ws = g.getWeatherStatus();
        if (ws == null) return false;

        // Carte Feu de camp : ne sert que pour ces 3 états
        return ws == WeatherStatus.DUSK
                || ws == WeatherStatus.NIGHT_DARK
                || ws == WeatherStatus.NIGHT_CLEAR;
    }

    /**
     * Appelé à l'entrée en PHASE3 pour préparer le premier piège à résoudre.
     * Si aucun Filet/Fosse utile, currentAction reste null.
     */
    private void prepareFirstTrapAction(Game g) {
        // Par sécurité, on reset avant
        g.setCurrentAction(null);

        // 1) FILET en priorité
        if (g.getNetHunters() != null) {
            for (String hunterId : g.getNetHunters()) {
                String loc = locationOf(g, hunterId);
                if (loc == null) continue;

                var enemies = vampSideOnLocation(g, loc);
                if (enemies == null || enemies.isEmpty()) continue;

                Game.Action a = new Game.Action();
                a.setMode("NET");
                a.setOwnerId(hunterId);
                a.setLocation(loc);
                a.setTargetId(null);          // cible pas encore choisie
                a.setRoll(null);              // dé pas encore lancé
                a.setBreakdownLines(java.util.List.of());
                a.setResolvedAtMillis(null);

                g.setCurrentAction(a);
                return; // un seul piège à la fois
            }
        }

        // 2) FOSSE ensuite
        if (g.getPitHunters() != null && g.getPitTargetsByHunter() != null) {
            for (String hunterId : g.getPitHunters()) {
                String loc = locationOf(g, hunterId);
                if (loc == null) continue;

                var victims = g.getPitTargetsByHunter().get(hunterId);
                if (victims == null || victims.isEmpty()) continue;

                int idx = 0;
                if (g.getPitIndexByHunter() != null && g.getPitIndexByHunter().containsKey(hunterId)) {
                    idx = g.getPitIndexByHunter().get(hunterId);
                }

                if (idx >= victims.size()) {
                    // plus de cibles pour cette Fosse
                    continue;
                }

                String currentVictimId = victims.get(idx);

                // Juste une sécurité : vérifier que la victime est toujours sur le même lieu
                var enemies = vampSideOnLocation(g, loc);
                boolean stillHere = enemies.stream().anyMatch(e -> e.getId().equals(currentVictimId));
                if (!stillHere) {
                    // si plus là, on skip cette cible
                    g.getPitIndexByHunter().put(hunterId, idx + 1);
                    continue;
                }

                Game.Action a = new Game.Action();
                a.setMode("PIT");
                a.setOwnerId(hunterId);      // chasseur qui a posé la Fosse
                a.setLocation(loc);
                a.setTargetId(currentVictimId); // 🔹 c’est cette cible qui doit cliquer
                a.setRoll(null);
                a.setBreakdownLines(java.util.List.of());
                a.setResolvedAtMillis(null);

                g.setCurrentAction(a);
                return;
            }
        }

        // 3) Si on arrive ici : aucun piège en attente -> currentAction reste null
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

        if (!g.getNetHunters().contains(hunterId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucun Filet préparé pour ce joueur.");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "aucune action Filet en cours (currentAction null)"
            );
        }
        if (!"NET".equals(a.getMode())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "action courante n’est pas un Filet (mode=" + a.getMode() + ")"
            );
        }
        if (!hunterId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Filet en cours appartient à " + a.getOwnerId() + ", pas à " + hunterId
            );
        }

        String loc = a.getLocation();
        if (loc == null) {
            loc = locationOf(g, hunterId);
            if (loc == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "le chasseur n’est sur aucun lieu.");
            }
        }

        var enemies = vampSideOnLocation(g, loc);
        var targetOpt = enemies.stream()
                .filter(e -> e.getId().equals(targetId))
                .findFirst();

        if (targetOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cible invalide pour Filet.");
        }

        // On enregistre simplement la cible, sans lancer le dé
        a.setTargetId(targetId);
        a.setRoll(null);
        a.setBreakdownLines(java.util.List.of());
        a.setResolvedAtMillis(null);

        save(g);

        final String fLoc    = loc;
        final String fTarget = targetId;
        final String fOwner  = hunterId;

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

        if (!g.getNetHunters().contains(hunterId)) {
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

        var enemies = vampSideOnLocation(g, loc);
        var targetOpt = enemies.stream()
                .filter(e -> e.getId().equals(targetId))
                .findFirst();

        if (targetOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cible invalide pour Filet.");
        }

        Player target = targetOpt.get();

        Game.Action a = g.getCurrentAction();
        if (a != null && "NET".equals(a.getMode()) && hunterId.equals(a.getOwnerId())) {
            // Si une cible avait déjà été posée via chooseNetTarget, on vérifie la cohérence
            if (a.getTargetId() != null && !a.getTargetId().equals(targetId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "cible différente de celle choisie pour Filet.");
            }
        }

        int roll = 1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(20);

        String msg = nameOf(g, hunterId) + " jette un dé pour son Filet contre "
                + nameOf(g, target.getId()) + " (1d20 = " + roll + "). ";

        java.util.List<String> breakdown = new java.util.ArrayList<>();
        breakdown.add("Jet de Filet : 1d20 = " + roll);

        if (roll > 12) {
            if (g.getRaidMods() == null) g.setRaidMods(new java.util.HashMap<>());
            g.getRaidMods().computeIfAbsent(target.getId(), __ -> new java.util.ArrayList<>())
                    .add(new StatMod("DEFENSE", -1, "ACTION:FILET"));

            msg += "Le Filet se referme : la défense de " + nameOf(g, target.getId()) + " est réduite de 1.";
            breakdown.add("Succès : DEF -1");
        } else {
            msg += "Le Filet échoue à piéger sa cible.";
            breakdown.add("Échec : aucun malus");
        }

        addHistory(g, msg);

        // Filet consommé pour ce raid
        g.getNetHunters().remove(hunterId);

        // Remplir currentAction pour le front (tu adaptes le type exact si différent)
        Game.Action current = new Game.Action(); // ou Game.CurrentAction selon ton implémentation
        current.setMode("NET");
        current.setOwnerId(hunterId);
        current.setLocation(loc);
        current.setTargetId(target.getId());
        current.setRoll(roll);
        current.setBreakdownLines(breakdown);
        current.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(current);

        save(g);

        final String fMsg      = msg;
        final int    fRoll     = roll;
        final String fHunterId = hunterId;
        final String fTargetId = target.getId();

        afterCommit(() -> {
            pushLive(g, fMsg);
            live.raidModsUpdated(g);
            // event principal pour le front
            live.actionRolled(g, "NET", fHunterId, fTargetId, fRoll);
            // plus tard, quand tu nettoieras currentAction dans combatContinue, tu pourras appeler live.trapResolved(...)
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

        Player victim = findPlayer(g, victimId);
        if (victim == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"VAMPIRE".equals(victim.getRole()) && !"SERVANT".equals(victim.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "seuls vampire/serviteurs sont concernés par la Fosse.");
        }

        String loc = locationOf(g, victimId);
        if (loc == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "tu n’es sur aucun lieu.");
        }

        if (g.getPitHunters() == null || g.getPitHunters().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Fosse préparée.");
        }

        // Trouver le chasseur qui a posé une Fosse sur ce lieu
        String hunterId = null;
        for (String hId : g.getPitHunters()) {
            String hLoc = locationOf(g, hId);
            if (loc.equals(hLoc)) {
                hunterId = hId;
                break;
            }
        }
        if (hunterId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Fosse active sur ce lieu.");
        }

        Game.Action a = g.getCurrentAction();
        if (a == null || !"PIT".equals(a.getMode()) || !hunterId.equals(a.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "aucune Fosse en cours pour ce joueur.");
        }

        // Vérifier que c’est bien la cible attendue
        var targetsMap = g.getPitTargetsByHunter();
        var indexMap   = g.getPitIndexByHunter();
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

        String expectedVictimId = victims.get(idx);
        if (!expectedVictimId.equals(victimId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ce n’est pas encore ton tour de résoudre la Fosse.");
        }

        // Jet
        int roll = 1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(20);

        String msg = nameOf(g, victim.getId()) + " jette un dé pour éviter la Fosse (1d20 = " + roll + "). ";

        java.util.List<String> breakdown = new java.util.ArrayList<>();
        breakdown.add("Jet pour la Fosse : 1d20 = " + roll);

        if (roll < 8) {
            if (g.getRaidMods() == null) g.setRaidMods(new java.util.HashMap<>());
            g.getRaidMods().computeIfAbsent(victim.getId(), __ -> new java.util.ArrayList<>())
                    .add(new StatMod("DEFENSE", -2, "ACTION:FOSSE"));

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
        current.setTargetId(victim.getId());
        current.setRoll(roll);
        current.setBreakdownLines(breakdown);
        current.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentAction(current);

        // Avancer l’index pour ce hunter
        g.getPitIndexByHunter().put(hunterId, idx + 1);

        // Si plus de cible → on peut retirer ce hunter de pitHunters
        if (idx + 1 >= victims.size()) {
            g.getPitHunters().remove(hunterId);
        }

        save(g);

        final String fMsg      = msg;
        final int    fRoll     = roll;
        final String fHunterId = hunterId;
        final String fVictimId = victim.getId();

        afterCommit(() -> {
            pushLive(g, fMsg);
            live.raidModsUpdated(g);
            live.actionRolled(g, "PIT", fHunterId, fVictimId, fRoll);
        });

        return g;
    }


// Corruption
    /**
     * Reconstruit entièrement les mods de corruption dans raidMods à chaque début de raid (PHASE0),
     * en purgeant d’abord les anciennes entrées "CORRUPTION:*".
     * Règles:
     *  - L1: applique -1 ATK / -1 DEF (effet moteur) + 1 chip "affichage"
     *  - L2: aucune stat modifiée, 1 chip "instable"
     *  - L3: rôle=SERVANT géré lors de la morsure, aucune stat modifiée, 1 chip "serviteur"
     */
    private void rebuildCorruptionMods(@NonNull Game g) {
        if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());

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
                addRaidMod(g, p.getId(), "ATTACK",  -1, "CORRUPTION:L1:ENG");
                addRaidMod(g, p.getId(), "DEFENSE", -1, "CORRUPTION:L1:ENG");
                // Une seule puce d’affichage
                addRaidMod(g, p.getId(), "MULTIPLE",   0, "CORRUPTION:L1:DSP");
            } else if (lvl == 2) {
                // L2 : pas de debuff chiffré — seulement la puce “instable”
                addRaidMod(g, p.getId(), "INSTABLE",   0, "CORRUPTION:L2:DSP");
            } else if (lvl == 3) {
                // L3 : pas de debuff chiffré — seulement la puce “serviteur”
                addRaidMod(g, p.getId(), "SERVITEUR",   0, "CORRUPTION:L3:DSP");
            }
        }
    }

    /**
     * Redirige un chasseur instable (corruption=2, jet 1–3) vers une cible choisie par le vampire.
     * - Valide la phase (PREPHASE3) et les droits (vampire uniquement).
     * - Vérifie que la cible fait partie des éligibles calculés à la révélation.
     * - Remplace la carte posée au centre par celle de la cible ET rend l’ancienne carte à la main de l’instable.
     * - Enregistre la décision pour planifier un duel instable -> cible lors de PHASE3.
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
                        String newLoc  = targetLoc;
                        if (!java.util.Objects.equals(oldCard, newLoc)) {
                            cb.setCard(newLoc);
                            var unstable = g.getPlayers().stream()
                                    .filter(pp -> pp.getId().equals(unstableId)).findFirst().orElse(null);
                            if (unstable != null) {
                                if (unstable.getHand() == null) unstable.setHand(new java.util.ArrayList<>());
                                unstable.getHand().add(oldCard);
                                unstable.getHand().remove(newLoc);
                            }
                        }
                    });

            String targetName   = nameOf(g, targetId);
            String unstableName = nameOf(g, unstableId);
            String locLabel     = labelLieuFr(targetLoc);

            String interruptionLine = "Récolte de " + targetName + " interrompue par " + unstableName + ".";
            String combatLine       = "Combat — " + unstableName + " VS " + targetName + " à " + locLabel + ".";

            if (g.getMessages() == null) g.setMessages(new ArrayList<>());
            g.getMessages().add(combatLine);
            addHistory(g, interruptionLine);
            addHistory(g, combatLine);
        }

        // 3) Consomme toute l’éligibilité pour cet instable
        g.getUnstableEligibleTargets().remove(unstableId);
        g.getUnstableEligibleLocations().remove(unstableId);

        // 4) (RE)calcule APRÈS mutation
        boolean hasPendingAfter = !(g.getUnstableEligibleTargets().isEmpty() && g.getUnstableEligibleLocations().isEmpty());
        boolean upcomingAfter   = computeHasUpcomingCombat(g);
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
                // sinon, on laisse vivre la fenêtre (potions, autres instables…) / ou timer long déjà en place
                live.phaseChanged(g); // petit heartbeat pour rafraîchir les onglets
            }
        });

        return g;
    }

    @Transactional
    public Game assignUnstableHarvest(String gameId, String userId, String unstableId, String loc) {
        Game g = findOr404(gameId);

        log.info("[{}] assign-harvest ENTER unstableId={}", gameId, unstableId);


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
                    String newLoc  = loc;
                    if (!java.util.Objects.equals(oldCard, newLoc)) {
                        cb.setCard(newLoc);
                        var unstable = g.getPlayers().stream()
                                .filter(pp -> pp.getId().equals(unstableId)).findFirst().orElse(null);
                        if (unstable != null) {
                            if (unstable.getHand() == null) unstable.setHand(new java.util.ArrayList<>());
                            unstable.getHand().add(oldCard);
                            unstable.getHand().remove(newLoc);
                        }
                    }
                });

        String line = nameOf(g, unstableId) + " récoltera à " + labelLieuFr(loc) + " pour " + nameOf(g, vamp.getId()) + ".";
        if (g.getMessages() == null) g.setMessages(new ArrayList<>());
        g.getMessages().add(line);
        addHistory(g, line);

        // 3) Consomme l’éligibilité
        g.getUnstableEligibleTargets().remove(unstableId);
        g.getUnstableEligibleLocations().remove(unstableId);

        // 4) (RE)calcule APRÈS mutation
        boolean hasPendingAfter = !(g.getUnstableEligibleTargets().isEmpty() && g.getUnstableEligibleLocations().isEmpty());
        boolean upcomingAfter   = computeHasUpcomingCombat(g);
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

        log.info("[{}] assign-nothing ENTER unstableId={}", gameId, unstableId);

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PREPHASE3");

        var vamp = getVamp(g).orElseThrow();
        if (!vamp.getId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can assign");

        // --- Debug précis : ce qu’on a au moment T
        var eligT = g.getUnstableEligibleTargets();
        var eligL = g.getUnstableEligibleLocations();
        boolean inTargets  = eligT != null && eligT.containsKey(unstableId);
        boolean inLocations= eligL != null && eligL.containsKey(unstableId);
        log.info("[{}] assign-nothing check: inTargets={}, inLocations={}, keysT={}, keysL={}",
                g.getId(), inTargets, inLocations,
                (eligT!=null? eligT.keySet() : java.util.Set.of()),
                (eligL!=null? eligL.keySet() : java.util.Set.of()));

        boolean eligibleNow = inTargets || inLocations;

        // --- Idempotence : si déjà consommé/expiré, on répond OK et on force juste un refresh
        if (!eligibleNow) {
            log.info("[{}] assign-nothing NOOP (already decided or expired) for {}", g.getId(), unstableId);
            save(g);
            afterCommit(() -> live.phaseChanged(g));
            return g;
        }

        // --- Mutations : consomme l’option sans target/harvest
        g.getUnstableEligibleTargets().remove(unstableId);
        g.getUnstableEligibleLocations().remove(unstableId);
        g.getUnstableTargetByPlayer().remove(unstableId);       // sécurité
        g.getUnstableHarvestLocByPlayer().remove(unstableId);   // sécurité

        boolean hasPendingAfter =
                !(g.getUnstableEligibleTargets().isEmpty() && g.getUnstableEligibleLocations().isEmpty());
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
     * Tente une morsure (dé 6) si le vampire vient d’infliger des dégâts à un chasseur :
     *  - si 6: +1 niveau de corruption (max 3), passage immédiat à SERVANT si niveau=3.
     *  - sinon: échec notifié.
     * Met en place une petite fenêtre d’affichage avant d’enchaîner les duels suivants.
     */
    @Transactional
    public Game rollCorruption(String gameId, String userId) {
        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE3 || g.getCurrentBite() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no bite to resolve");

        var b = g.getCurrentBite();
        if (!userId.equals(b.getAttackerId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can roll the bite");

        if (b.getRoll() != null) return g; // déjà lancé

        int roll = 1 + RND.nextInt(6);
        b.setRoll(roll);
        addHistory(g, nameOf(g, b.getAttackerId()) + " — jet de morsure = " + roll + ".");

        var target = g.getPlayers().stream()
                .filter(p -> p.getId().equals(b.getTargetId()))
                .findFirst().orElse(null);

        boolean sendMods = false;
        if (target != null && roll > 3) {
            int before = target.getCorruption();
            int after  = Math.min(3, before + 1);
            target.setCorruption(after);
            addHistory(g, nameOf(g, target.getId()) + " se fait mordre... sa corruption passe de " + before + " à " + after + ".");
            if (after == 3) {
                discardAllActionsOf(g, target);
                target.setRole("SERVANT");
                target.setSouls(target.getSouls() + target.getGold());
                target.setGold(0);
            }
            rebuildCorruptionMods(g);
            sendMods = true;
        } else {
            addHistory(g, nameOf(g, b.getAttackerId()) + " échoue sa tentative de morsure.");
        }

        b.setResolvedAtMillis(System.currentTimeMillis());
        save(g);

        // --- figer tout ce que le lambda va capturer ---
        final boolean sendModsF       = sendMods;
        final int rollF               = roll;
        final String attF             = b.getAttackerId();
        final String tgtF             = b.getTargetId();
        final Integer newCF           = (target != null) ? target.getCorruption() : null;
        final boolean becameServantF  = (target != null && "SERVANT".equals(target.getRole()));

        afterCommit(() -> {
            if (sendModsF) {
                live.raidModsUpdated(g);
            }
            live.biteRolled(g, rollF, attF, tgtF, newCF, becameServantF);
        });

        return g;
    }

    /**
     * Défausse TOUTES les cartes d'action du joueur, puis vide sa main d'actions.
     * À appeler AVANT de changer son rôle si on veut savoir s'il était chasseur ou vampire.
     */
    private void discardAllActionsOf(Game g, Player p) {
        List<String> inv = p.getActions();
        if (inv == null || inv.isEmpty()) return;

        boolean wasHunter = "HUNTER".equals(p.getRole());
        boolean wasVamp   = "VAMPIRE".equals(p.getRole());

        // On travaille sur une copie pour éviter les soucis pendant le clear()
        var copy = new java.util.ArrayList<>(inv);

        for (String card : copy) {
            if (wasHunter) {
                discardHunterAction(g, card);
            } else if (wasVamp) {
                discardVampAction(g, card);
            }
            // on ne supprime pas ici élément par élément, on videra la liste à la fin
        }

        inv.clear(); // la main d'actions du joueur est vide
    }

    // Maintenance
    @Nullable
    private Player findPlayer(Game g, String id){
        return g.getPlayers().stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
    }

    private int poolTotal(Map<String,Integer> pool) {
        if (pool == null || pool.isEmpty()) return 0;
        return pool.values().stream()
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private String drawFromPool(Map<String,Integer> pool) {
        if (pool == null) return null;

        int total = poolTotal(pool);
        if (total <= 0) return null;

        int r = RND.nextInt(total) + 1;

        for (var e : pool.entrySet()) {
            int count = (e.getValue() != null ? e.getValue() : 0);
            if (count <= 0) continue;

            r -= count;
            if (r <= 0) {
                pool.put(e.getKey(), count - 1); // on consomme 1 carte de ce type
                return e.getKey();
            }
        }
        return null;
    }

    // POTIONS
    private String drawPotion(Game g){
        return drawFromPool(g.getPotionsPool());
    }

    // ACTIONS HUNTERS
    private String drawHunterAction(Game g){
        return drawFromPool(g.getHunterActionsPool());
    }

    // ACTIONS VAMPIRE
    private String drawVampAction(Game g){
        return drawFromPool(g.getVampActionsPool());
    }

    private void discardToPool(Map<String,Integer> discardPool, String type){
        if (type == null || discardPool == null) return;
        discardPool.merge(type, 1, Integer::sum);
    }

    private void discardPotion(Game g, String type) {
        discardToPool(g.getPotionsDiscardPool(), type);
    }

    private void discardHunterAction(Game g, String type) {
        discardToPool(g.getHunterActionsDiscardPool(), type);
    }

    private void discardVampAction(Game g, String type) {
        discardToPool(g.getVampActionsDiscardPool(), type);
    }

    private void addPotionTo(Game g, String playerId, String type) {
        var p = g.getPlayers().stream()
                .filter(pp -> pp.getId().equals(playerId))
                .findFirst()
                .orElse(null);
        if (p != null) {
            p.getPotions().add(type);
        }
    }


    @Transactional
    public Game buyPotion(String gameId, String userId) {
        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        int potionsLeft = poolTotal(g.getPotionsPool());
        if (potionsLeft <= 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no potions left");

        if (p.getWater() < 4 || p.getHerbs() < 3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");

        // paiement
        p.setWater(p.getWater() - 4);
        p.setHerbs(p.getHerbs() - 3);

        // tirage
        String type = drawPotion(g);
        if (type == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no potions left");

        addPotionTo(g, userId, type);
        addHistory(g, nameOf(g, userId) + " achète une potion.");

        // recompute pour l'event
        int remaining = poolTotal(g.getPotionsPool());

        save(g);

        final String fType = type;
        final int fRemaining = remaining;
        afterCommit(() -> live.potionBought(g, userId, fType, fRemaining));

        return g;
    }

    @Transactional
    public Game buyAction(String gameId, String userId) {
        Game g = findOr404(gameId);

        // même logique que pour buyPotion : achat pendant PHASE4
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");

        boolean isVamp = "VAMPIRE".equals(p.getRole());
        boolean isHunter = "HUNTER".equals(p.getRole());

        // on choisit le bon deck
        Map<String,Integer> pool = isVamp ? g.getVampActionsPool() : g.getHunterActionsPool();

        int actionsLeft = poolTotal(pool);
        if (actionsLeft <= 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no actions left");

        if (isVamp) {
            if (p.getSouls() < 50)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
            p.setSouls(p.getSouls() - 50);
        }
        if (isHunter) {
            if (p.getGold() < 50)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
            p.setGold(p.getGold() - 50);
        }

        // === TIRAGE ===
        String type = isVamp ? drawVampAction(g) : drawHunterAction(g);
        if (type == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no actions left");

        // Ajout dans la main d'actions
        if (p.getActions() == null) {
            p.setActions(new java.util.ArrayList<>());
        }
        p.getActions().add(type);

        addHistory(g,
                nameOf(g, userId) + " pioche une carte d'action (" +
                        (isVamp ? "camp vampire" : "camp chasseurs") + ")."
        );

        int remaining = poolTotal(pool);

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
        if (qty <= 0) qty = 1;

        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");

        int cost = 50 * qty;
        if (p.getGold() < cost)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not enough gold");

        p.setGold(p.getGold() - cost);
        p.setSilver(p.getSilver() + qty);
        addHistory(g, nameOf(g, userId) + " achète " + qty + " argent (" + cost + " or).");

        save(g);

        final int fQty  = qty;
        final int fCost = cost;

        afterCommit(() -> live.silverBought(g, userId, fQty, fCost));
        return g;
    }

    @Transactional
    public Game sellResource(String gameId, String userId, String res, int qty) {
        if (qty <= 0) qty = 1;
        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var p = findPlayer(g, userId);
        if (p == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"HUNTER".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters only");

        Set<String> allowed = Set.of("wood","herbs","stone","iron","water");
        if (!allowed.contains(res)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid resource");

        // vérifier stock
        int have = switch(res) {
            case "wood" -> p.getWood();
            case "herbs"-> p.getHerbs();
            case "stone"-> p.getStone();
            case "iron" -> p.getIron();
            case "water"-> p.getWater();
            default -> 0;
        };
        if (have < qty) throw new ResponseStatusException(HttpStatus.CONFLICT, "not enough resource");

        // débiter
        switch(res) {
            case "wood" -> p.setWood(have - qty);
            case "herbs"-> p.setHerbs(have - qty);
            case "stone"-> p.setStone(have - qty);
            case "iron" -> p.setIron(have - qty);
            case "water"-> p.setWater(have - qty);
        }

        int gain = 10 * qty;
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
        if (p == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not in game");
        if (!"VAMPIRE".equals(p.getRole()) && !"SERVANT".equals(p.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire/servants only");

        switch (recipe) {
            case "WOOD_TO_IRON" -> { // 2 bois + 1 eau → +2 fer
                if (p.getWood() < 2 || p.getWater() < 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                p.setWood(p.getWood() - 2);
                p.setWater(p.getWater() - 1);
                p.setIron(p.getIron() + 2);
                addHistory(g, nameOf(g, userId) + " transmute: 2 bois + 1 eau → +2 fer.");
            }
            case "IRON_TO_WOOD" -> { // 2 fer + 1 eau → +2 bois
                if (p.getIron() < 2 || p.getWater() < 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                p.setIron(p.getIron() - 2);
                p.setWater(p.getWater() - 1);
                p.setWood(p.getWood() + 2);
                addHistory(g, nameOf(g, userId) + " transmute: 2 fer + 1 eau → +2 bois.");
            }
            case "TRINITY_TO_SOULS" -> { // 1 bois + 1 fer + 1 eau → +20 âmes
                if (p.getWood() < 1 || p.getIron() < 1 || p.getWater() < 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "missing resources");
                p.setWood(p.getWood() - 1);
                p.setIron(p.getIron() - 1);
                p.setWater(p.getWater() - 1);
                p.setSouls(p.getSouls() + 20);
                addHistory(g, nameOf(g, userId) + " transmute: 1 bois + 1 fer + 1 eau → +20 âmes.");
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown recipe");
        }

        save(g);
        afterCommit(() -> live.transmuted(g, userId, recipe));
        return g;
    }

    private boolean sameSideCanTrade(Player a, Player b){
        if ("HUNTER".equals(a.getRole()) && "HUNTER".equals(b.getRole())) return true;
        // vamp side: vamp <-> servant uniquement
        if ("VAMPIRE".equals(a.getRole()) && "SERVANT".equals(b.getRole())) return true;
        if ("SERVANT".equals(a.getRole()) && "VAMPIRE".equals(b.getRole())) return true;
        return false;
    }

    private String sideOf(Player a, Player b){
        return "HUNTER".equals(a.getRole()) && "HUNTER".equals(b.getRole()) ? "HUNTERS" : "VAMP_SIDE";
    }

    private Game.Trade getOrCreateTrade(Game g, String aId, String bId){
        String lo = aId.compareTo(bId) <= 0 ? aId : bId;
        String hi = aId.compareTo(bId) <= 0 ? bId : aId;

        for (var t : g.getTrades()) {
            if ((t.getAId().equals(lo) && t.getBId().equals(hi))) return t;
        }
        var t = new Game.Trade();
        t.setId(java.util.UUID.randomUUID().toString());
        t.setAId(lo); t.setBId(hi);
        var pa = findPlayer(g, lo);
        var pb = findPlayer(g, hi);
        t.setSide(sideOf(pa, pb));
        g.getTrades().add(t);
        return t;
    }

    @Transactional
    public Game tradeSetMyOffer(String gameId, String userId, String targetId, Map<String,Integer> offer) {
        Game g = findOr404(gameId);
        if (g.getPhase() != Phase.PHASE4)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE4");

        var me = findPlayer(g, userId);
        var you = findPlayer(g, targetId);
        if (me == null || you == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "player not found");
        if (!sameSideCanTrade(me, you)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "pair not eligible");

        var t = getOrCreateTrade(g, userId, targetId);

        // sanitize
        Map<String,Integer> sanitized = new java.util.HashMap<>();
        if (offer != null) {
            for (var e : offer.entrySet()) {
                int q = Math.max(0, e.getValue()==null?0:e.getValue());
                if (q > 0) sanitized.put(e.getKey(), q);
            }
        }

        boolean iAmA = userId.equals(t.getAId());
        if (iAmA) t.setOfferA(sanitized); else t.setOfferB(sanitized);

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
        if (me == null || you == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "player not found");
        if (!sameSideCanTrade(me, you)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "pair not eligible");

        var t = getOrCreateTrade(g, userId, targetId);
        boolean iAmA = userId.equals(t.getAId());

        String st = switch (action) {
            case "confirm" -> "CONFIRMED";
            case "refuse"  -> "REFUSED";
            case "cancel"  -> "CANCELLED";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid action");
        };

        if (iAmA) t.setStatusA(st); else t.setStatusB(st);
        t.setUpdatedAt(System.currentTimeMillis());

        String tId = t.getId();
        String aId = t.getAId();
        String bId = t.getBId();
        Map<String,Integer> offerA = t.getOfferA()==null? java.util.Map.of() : new java.util.HashMap<>(t.getOfferA());
        Map<String,Integer> offerB = t.getOfferB()==null? java.util.Map.of() : new java.util.HashMap<>(t.getOfferB());

        boolean deleted = false;
        boolean success  = false;

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
            final java.util.Map<String,Object> extra = fSuccess
                    ? new java.util.HashMap<>(java.util.Map.of(
                    "offerA", fOfferA,
                    "offerB", fOfferB
            ))
                    : java.util.Map.of();

            afterCommit(() -> {
                live.tradeDeleted(
                        g, tId, aId, bId,
                        "FINAL",
                        fResult,
                        extra
                );
            });
        } else {
            afterCommit(() -> live.tradeSync(g, t));
        }
        return g;
    }

    private boolean isFinal(String s){ return "REFUSED".equals(s) || "CANCELLED".equals(s); }

    // Exécution de l'échange (débits puis crédits)
    private void applyTradeExchange(Game g, Game.Trade t){
        var a = findPlayer(g, t.getAId());
        var b = findPlayer(g, t.getBId());
        if (a==null || b==null) return;

        // vérif stocks côté A et B
        if (!hasAll(a, t.getOfferA()) || !hasAll(b, t.getOfferB()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "insufficient resources");

        debit(a, t.getOfferA()); debit(b, t.getOfferB());
        credit(a, t.getOfferB()); credit(b, t.getOfferA());

        addHistory(g, nameOf(g, a.getId()) + " et " + nameOf(g, b.getId()) + " concluent un échange.");
    }

    // helpers
    private boolean hasAll(Player p, Map<String,Integer> pack){
        if (pack==null) return true;
        for (var e : pack.entrySet()){
            int need = Math.max(0, e.getValue()==null?0:e.getValue());
            switch (e.getKey()) {
                case "wood"  -> { if (p.getWood()  < need) return false; }
                case "herbs" -> { if (p.getHerbs() < need) return false; }
                case "stone" -> { if (p.getStone() < need) return false; }
                case "iron"  -> { if (p.getIron()  < need) return false; }
                case "water" -> { if (p.getWater() < need) return false; }
                case "gold"  -> { if (p.getGold()  < need) return false; }          // boutique dit or permis côté hunters
                case "souls" -> { if (p.getSouls() < need) return false; }         // transmutation côté vamp
                case "silver"-> { if (p.getSilver()< need) return false; }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid resource: " + e.getKey());
            }
        }
        return true;
    }

    private void debit(Player p, Map<String,Integer> pack){
        if (pack == null) return;
        for (var e : pack.entrySet()){
            int q = Math.max(0, e.getValue()==null ? 0 : e.getValue());
            switch (e.getKey()){
                case "wood"   -> p.setWood(p.getWood() - q);
                case "herbs"  -> p.setHerbs(p.getHerbs() - q);
                case "stone"  -> p.setStone(p.getStone() - q);
                case "iron"   -> p.setIron(p.getIron() - q);
                case "water"  -> p.setWater(p.getWater() - q);
                case "gold"   -> p.setGold(p.getGold() - q);
                case "souls"  -> p.setSouls(p.getSouls() - q);
                case "silver" -> p.setSilver(p.getSilver() - q);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid resource: " + e.getKey());
            }
        }
    }

    private void credit(Player p, Map<String,Integer> pack){
        if (pack == null) return;
        for (var e : pack.entrySet()){
            int q = Math.max(0, e.getValue()==null ? 0 : e.getValue());
            switch (e.getKey()){
                case "wood"   -> p.setWood(p.getWood() + q);
                case "herbs"  -> p.setHerbs(p.getHerbs() + q);
                case "stone"  -> p.setStone(p.getStone() + q);
                case "iron"   -> p.setIron(p.getIron() + q);
                case "water"  -> p.setWater(p.getWater() + q);
                case "gold"   -> p.setGold(p.getGold() + q);
                case "souls"  -> p.setSouls(p.getSouls() + q);
                case "silver" -> p.setSilver(p.getSilver() + q);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid resource: " + e.getKey());
            }
        }
    }
}
