package org.castello.game;

import org.castello.game.domain.ActionCardService;
import org.castello.game.domain.BankService;
import org.castello.game.domain.CombatService;
import org.castello.game.domain.ConstructionService;
import org.castello.game.domain.CorruptionService;
import org.castello.game.domain.TradeService;
import org.castello.game.domain.DeckService;
import org.castello.game.domain.EquipmentService;
import org.castello.game.domain.HarvestService;
import org.castello.game.domain.HunterActionService;
import org.castello.game.domain.LocationEffectService;
import org.castello.game.domain.ShopService;
import org.castello.game.domain.VampireActionService;
import org.castello.game.domain.WeatherService;
import org.castello.game.support.Dice;
import org.castello.game.support.GameStore;
import org.castello.game.support.RaidFlow;
import org.castello.player.Player;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.castello.persistence.GameRepository;
import org.castello.persistence.PlayerRepository;
import org.castello.player.PlayerService;

import java.util.*;

import static org.castello.game.domain.EquipmentService.*;

@Service
public class GameService implements RaidFlow {

    private final TaskScheduler raidScheduler;
    private final TransactionTemplate tx;

    // ----- PERSISTENCE -----
    private final GameStore store;
    private final Dice dice;
    private final DeckService decks;
    private final WeatherService weather;
    private final HarvestService harvest;
    private final BankService bank;
    private final TradeService trades;
    private final EquipmentService equipment;
    private final ShopService shop;
    private final ConstructionService construction;
    private final CorruptionService corruption;
    private final LocationEffectService locationEffects;
    private final ActionCardService actionCards;
    private final HunterActionService hunterActions;
    private final VampireActionService vampireActions;
    private final CombatService combat;
    private final GameRepository gameRepo;
    private final PlayerRepository playerRepo;
    private final org.castello.live.LiveEvents live;
    private final PlayerService playerService;

    public GameService(GameStore store, Dice dice, DeckService decks, WeatherService weather,
            HarvestService harvest, BankService bank, TradeService trades, EquipmentService equipment,
            ShopService shop, ConstructionService construction, CorruptionService corruption,
            LocationEffectService locationEffects, ActionCardService actionCards,
            HunterActionService hunterActions, VampireActionService vampireActions,
            CombatService combat,
            GameRepository gameRepo, PlayerRepository playerRepo,
            @Qualifier("raidTaskScheduler") TaskScheduler raidScheduler, PlatformTransactionManager tm,
            org.castello.live.LiveEvents live, PlayerService playerService) {
        this.store = store;
        this.dice = dice;
        this.decks = decks;
        this.weather = weather;
        this.harvest = harvest;
        this.bank = bank;
        this.trades = trades;
        this.equipment = equipment;
        this.shop = shop;
        this.construction = construction;
        this.corruption = corruption;
        this.locationEffects = locationEffects;
        this.actionCards = actionCards;
        this.hunterActions = hunterActions;
        this.vampireActions = vampireActions;
        this.combat = combat;
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

    private int deckAvailableSize(List<String> deck, List<String> discard) {
        return decks.availableSize(deck, discard);
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

    @Override
    public boolean computeHasUpcomingCombat(Game g) {
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

    private String nameOf(Game g, String playerId) {
        return g.nameOf(playerId);
    }

    private void rebuildEquipmentMods(Game g) {
        equipment.rebuildEquipmentMods(g);
    }

    private void addRaidMod(Game g, String playerId, String stat, int amount, String source) {
        g.addRaidMod(playerId, stat, amount, source);
    }

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
        // --- Ressources de départ ---
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

        // --- Cartes action de départ ---
        for (var p : g.getPlayers()) {
            if ("HUNTER".equals(p.getRole())) {
                p.getActions().addAll(List.of("CRATE_MANOR", "CRATE_LAKE"));
            }
            if ("VAMPIRE".equals(p.getRole())) {
                p.getActions().addAll(List.of("ADVANCED_TRANSMUTATION"));
            }
        }

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

    @Override
    public void handleDeathsAndVictory(Game g) {
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

    private void initDecks(Game g) {
        decks.initDecks(g);
    }

    @Override
    public boolean allHuntersSelected(@NonNull Game g) {
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

    @Override
    public void applyPhaseEntry(@NonNull Game g, @NonNull Phase to) {
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
    @Override
    public void setupUnstableAndPrephaseTimeout(Game g) {
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

    @Override
    public void scheduleAdvance(String gameId, Phase expected, Phase target, long delayMs) {
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

    @Override
    public void schedulePrephaseTimeout(String gameId, long millis, int expectedVersion) {
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

    @Override
    public void scheduleNextLocationEffect(String gameId) {
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

    @Override
    public void refreshPrephaseRevealMessages(Game g) {
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
    private boolean isEntityAlive(Game g, String entityId) {
        return g.isEntityAlive(entityId);
    }

    // Météo
    // alimente raidMods
    private void rebuildWeatherMods(Game g) {
        weather.rebuildWeatherMods(g);
    }

    public Game rollWeather(String gameId, String userId) {
        return weather.rollWeather(gameId, userId);
    }

    private void applyWindRepairs(@NonNull Game g) {
        weather.applyWindRepairs(g);
    }

    private int rollD100Tens() {
        return harvest.rollD100Tens();
    }

    private String resLabelFr(String res) {
        return harvest.resLabelFr(res);
    }

    private void applyHarvests(@NonNull Game g) {
        harvest.applyHarvests(g);
    }

    private void grant(Player p, String res, int qty) {
        if (p == null)
            return;
        p.grant(res, qty);
    }

    private void pushLive(Game g, String msg) {
        if (g.getMessages() == null)
            g.setMessages(new ArrayList<>());
        g.getMessages().add(msg);
        // notifie le front (déjà géré dans onLiveEvent: MESSAGE)
        live.message(g, msg);
    }

    @Transactional
    // --- Cartes action : délégués vers ActionCardService ---
    public Game useAction(String gameId, String playerId, Action type) { return actionCards.useAction(gameId, playerId, type); }

    private void applyTrackerHuntersWhenVampirePlays(Game g, String vampireLocation) { actionCards.applyTrackerHuntersWhenVampirePlays(g, vampireLocation); }

    private boolean isCampfireCancellingWeather(@NonNull Game g, @NonNull String location) { return actionCards.isCampfireCancellingWeather(g, location); }

    private void prepareNextRaidAction(Game g) { actionCards.prepareNextRaidAction(g); }

    private boolean hasIncendiaireTargetsOnLocation(Game g, String loc) { return actionCards.hasIncendiaireTargetsOnLocation(g, loc); }

    private boolean hasUsableHolyWaterForPlayer(@NonNull Game g, @NonNull Player p) { return actionCards.hasUsableHolyWaterForPlayer(g, p); }

    private boolean hasUsableSecretPassageOption(Game g) { return actionCards.hasUsableSecretPassageOption(g); }

    private boolean hasTrapTargetsOnLocation(Game g, String loc) { return actionCards.hasTrapTargetsOnLocation(g, loc); }

    private boolean hasBlockingActionInProgress(Game g) { return actionCards.hasBlockingActionInProgress(g); }

    // --- Résolutions de cartes : délégués vers HunterActionService / VampireActionService ---
    public Game chooseNetTarget(String gameId, String hunterId, String targetId) { return hunterActions.chooseNetTarget(gameId, hunterId, targetId); }

    public Game resolveNet(String gameId, String hunterId, String targetId) { return hunterActions.resolveNet(gameId, hunterId, targetId); }

    public Game resolvePit(String gameId, String victimId) { return hunterActions.resolvePit(gameId, victimId); }

    public Game resolveIncendiaire(String gameId, String hunterId, String infraCode) { return hunterActions.resolveIncendiaire(gameId, hunterId, infraCode); }

    public Game resolveProvocation(String gameId, String hunterId, String enemyId) { return hunterActions.resolveProvocation(gameId, hunterId, enemyId); }

    public Game resolveAmbush(String gameId, String playerId, String targetId) { return hunterActions.resolveAmbush(gameId, playerId, targetId); }

    public Game resolveBlessedStake(String gameId, String userId) { return hunterActions.resolveBlessedStake(gameId, userId); }

    public Game rollCrate(String gameId, String userId) { return hunterActions.rollCrate(gameId, userId); }

    public Game resolveCrateAction(String gameId, String userId) { return hunterActions.resolveCrateAction(gameId, userId); }

    public Game resolveHolyWater(String gameId, String playerId, String mode) { return hunterActions.resolveHolyWater(gameId, playerId, mode); }

    public void resolveCataclysme(String gameId, String userId, WeatherStatus first, WeatherStatus second) { vampireActions.resolveCataclysme(gameId, userId, first, second); }

    public Game rollShadowClones(String gameId, String playerId) { return vampireActions.rollShadowClones(gameId, playerId); }

    public Game resolveShadowClones(String gameId, String playerId, java.util.List<String> locations, java.util.List<Boolean> biteEnabled) { return vampireActions.resolveShadowClones(gameId, playerId, locations, biteEnabled); }

    public Game resolveVoileDeBrume(String gameId, String userId, String location) { return vampireActions.resolveVoileDeBrume(gameId, userId, location); }

    public Game resolveImageMiroirSetup(String gameId, String playerId, String loc) { return vampireActions.resolveImageMiroirSetup(gameId, playerId, loc); }

    public Game resolveImageMiroirChoice(String gameId, String playerId, String loc) { return vampireActions.resolveImageMiroirChoice(gameId, playerId, loc); }

    public Game resolveDarkMark(String gameId, String playerId, String targetId) { return vampireActions.resolveDarkMark(gameId, playerId, targetId); }

    public Game resolveOccultWeakening(String gameId, String playerId, String targetId) { return vampireActions.resolveOccultWeakening(gameId, playerId, targetId); }

    public Game resolveSecretPassage(String gameId, String playerId, String destination) { return vampireActions.resolveSecretPassage(gameId, playerId, destination); }

    @Override
    public void cancelPendingCombatsForPlayer(Game g, Player player) {
        combat.cancelPendingCombatsForPlayer(g, player);
    }

    private void rebuildCorruptionMods(@NonNull Game g) {
        corruption.rebuildCorruptionMods(g);
    }

    // --- Corruption : délégués vers CorruptionService ---
    private boolean hasSuccumbedToCorruption(Game g, String playerId) { return corruption.hasSuccumbedToCorruption(g, playerId); }

    private boolean isDarkMarked(Game g, String playerId) { return corruption.isDarkMarked(g, playerId); }

    private void applyDarkMarkCorruptionOncePerRaid(Game g, Player hunter) { corruption.applyDarkMarkCorruptionOncePerRaid(g, hunter); }

    private void cleanseDarkMark(Game g, Player hunter) { corruption.cleanseDarkMark(g, hunter); }

    public Game assignUnstableTarget(String gameId, String userId, String unstableId, String targetId) { return corruption.assignUnstableTarget(gameId, userId, unstableId, targetId); }

    public Game assignUnstableHarvest(String gameId, String userId, String unstableId, String loc) { return corruption.assignUnstableHarvest(gameId, userId, unstableId, loc); }

    public Game assignUnstableNothing(String gameId, String userId, String unstableId) { return corruption.assignUnstableNothing(gameId, userId, unstableId); }

    public Game rollCorruption(String gameId, String userId) { return corruption.rollCorruption(gameId, userId); }

    private void discardAllActionsOf(Game g, Player p) {
        decks.discardAllActionsOf(g, p);
    }

    private void discardAllPotionsOf(Game g, Player p) {
        decks.discardAllPotionsOf(g, p);
    }

    // Maintenance
    @Nullable
    private Player findPlayer(Game g, String id) {
        return g.findPlayer(id);
    }

    // Pioches/défausses déléguées à DeckService.
    private String drawFromDeck(List<String> deck, List<String> discard) {
        return decks.draw(deck, discard);
    }

    private void putOnTop(List<String> deck, String cardId) {
        decks.putOnTop(deck, cardId);
    }

    private void putOnBottom(List<String> deck, String cardId) {
        decks.putOnBottom(deck, cardId);
    }

    private String drawHunterAction(Game g) {
        return decks.drawHunterAction(g);
    }

    private String drawVampAction(Game g) {
        return decks.drawVampAction(g);
    }

    private String drawPotion(Game g) {
        return decks.drawPotion(g);
    }

    private String drawElixir(Game g) {
        return decks.drawElixir(g);
    }

    private void discardHunterAction(Game g, String cardId) {
        decks.discardHunterAction(g, cardId);
    }

    private void discardVampAction(Game g, String cardId) {
        decks.discardVampAction(g, cardId);
    }

    private void discardPotion(Game g, String cardId) {
        decks.discardPotion(g, cardId);
    }

    private void discardElixir(Game g, String cardId) {
        decks.discardElixir(g, cardId);
    }

    // --- Boutique : délégués vers ShopService ---
    public Game rollMerchantItinerant(String gameId, String userId) { return shop.rollMerchantItinerant(gameId, userId); }

    public Game rollAdvancedTransmutation(String gameId, String userId) { return shop.rollAdvancedTransmutation(gameId, userId); }

    public Game buyResource(String gameId, String userId, String resourceType) { return shop.buyResource(gameId, userId, resourceType); }

    public Game startShopBonusPurchase(String gameId, String userId) { return shop.startShopBonusPurchase(gameId, userId); }

    public Game buyShopBonus(String gameId, String userId, String payment) { return shop.buyShopBonus(gameId, userId, payment); }

    public Game cancelShopBonusPurchase(String gameId, String userId) { return shop.cancelShopBonusPurchase(gameId, userId); }

    public Game buyPotion(String gameId, String userId) { return shop.buyPotion(gameId, userId); }

    public Game buyAction(String gameId, String userId) { return shop.buyAction(gameId, userId); }

    public Game buySilver(String gameId, String userId, int qty) { return shop.buySilver(gameId, userId, qty); }

    public Game buyHolyWaterAction(String gameId, String userId) { return shop.buyHolyWaterAction(gameId, userId); }

    public Game buyTrackingAction(String gameId, String userId) { return shop.buyTrackingAction(gameId, userId); }

    public Game buyUpgradeWeapon(String gameId, String userId, int expectedTier, String expectedType) { return shop.buyUpgradeWeapon(gameId, userId, expectedTier, expectedType); }

    public Game buyUpgradeArmor(String gameId, String userId) { return shop.buyUpgradeArmor(gameId, userId); }

    public Game sellResource(String gameId, String userId, String res, int qty) { return shop.sellResource(gameId, userId, res, qty); }

    public Game transmute(String gameId, String userId, String recipe) { return shop.transmute(gameId, userId, recipe); }

    public Game tradeSetMyOffer(String gameId, String userId, String targetId, Map<String, Integer> offer) {
        return trades.tradeSetMyOffer(gameId, userId, targetId, offer);
    }

    public Game tradeAction(String gameId, String userId, String targetId, String action) {
        return trades.tradeAction(gameId, userId, targetId, action);
    }

    private boolean isFinal(String s) {
        return trades.isFinal(s);
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
        return construction.hasResourcesForInfra(p, infra);
    }

    private void resolveInfraConstruction(Game g) {
        construction.resolveInfraConstruction(g);
    }

    private void destroyRaidInfrasAtEnd(Game g) {
        construction.destroyRaidInfrasAtEnd(g);
    }

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

    // --- Effets de lieux : délégués vers LocationEffectService ---
    private void maybeStartLocationEffects(Game g, String gameId) { locationEffects.maybeStartLocationEffects(g, gameId); }

    public Game chooseLocationEffect(String gameId, String playerId, LocationEffectChoice choice) { return locationEffects.chooseLocationEffect(gameId, playerId, choice); }

    public Game resolveLibraryTheft(String gameId, String playerId, String targetId, int slotIndex) { return locationEffects.resolveLibraryTheft(gameId, playerId, targetId, slotIndex); }

    public Game resolveLibraryOmen(String gameId, String playerId, java.util.List<String> placements) { return locationEffects.resolveLibraryOmen(gameId, playerId, placements); }

    public void updateLaboratoryExperimentDraft(String gameId, String userId, Game.MonsterType type, String location) { locationEffects.updateLaboratoryExperimentDraft(gameId, userId, type, location); }

    public Game resolveLaboratoryExperiment(String gameId, String userId, Game.MonsterType type, String location) { return locationEffects.resolveLaboratoryExperiment(gameId, userId, type, location); }

    public Game resolveLaboratoryExplosion(String gameId, String playerId) { return locationEffects.resolveLaboratoryExplosion(gameId, playerId); }

    public Game resolveAltarHeal(String gameId, String playerId, String targetId) { return locationEffects.resolveAltarHeal(gameId, playerId, targetId); }

    public Game resolveAltarCorrupt(String gameId, String playerId, String targetId) { return locationEffects.resolveAltarCorrupt(gameId, playerId, targetId); }

    public Game resolveForge(String gameId, String playerId, String equipCode) { return locationEffects.resolveForge(gameId, playerId, equipCode); }

    private boolean isAltarBuilt(Game g) { return locationEffects.isAltarBuilt(g); }

    private boolean isAltarCorrupted(Game g) { return locationEffects.isAltarCorrupted(g); }

    private void resetAltarRaidFlags(Game g) { locationEffects.resetAltarRaidFlags(g); }

    private void resolveAltarEndOfRaid(Game g) { locationEffects.resolveAltarEndOfRaid(g); }

    private void onAltarBite(Game g, RoundFight r, Player attacker, Player defender) { locationEffects.onAltarBite(g, r, attacker, defender); }

    private void onAltarVampireDamaged(Game g, RoundFight r, int damage) { locationEffects.onAltarVampireDamaged(g, r, damage); }

    private boolean isBallroomBloodWaltzAttack(Game g, RoundFight r, String userId, Player attackerPlayer0, Player defenderPlayer0) { return locationEffects.isBallroomBloodWaltzAttack(g, r, userId, attackerPlayer0, defenderPlayer0); }

    private String monsterNameFr(Game.MonsterType type) {
        return type.labelFr();
    }

    private String entityName(Game g, String id) {
        return g.entityName(id);
    }

    private List<Game.Monster> monstersOnLocation(Game g, String loc) {
        return g.monstersOn(loc);
    }

    private Game.Monster findMonster(Game g, String monsterId) {
        return g.findMonster(monsterId);
    }

    private boolean isEntityOnLocation(Game g, String entityId, String loc) {
        return g.isEntityOn(entityId, loc);
    }

    @Transactional
    // --- Combat : délégués vers CombatService ---
    public Game rollDice(String gameId, String userId) { return combat.rollDice(gameId, userId); }

    public Game combatContinue(String gameId, String userId) { return combat.combatContinue(gameId, userId); }

    public Game usePotion(String gameId, String playerId, Potion type) { return combat.usePotion(gameId, playerId, type); }

    private void buildCombatsQueue(Game g) { combat.buildCombatsQueue(g); }

    private void applyBleed(Game g) { combat.applyBleed(g); }

    private void purgeTransientRaidMods(Game g) { combat.purgeTransientRaidMods(g); }

    private Set<String> participantsOfUpcomingCombat(Game g) { return combat.participantsOfUpcomingCombat(g); }

    public void contributeBankStone(String gameId, String playerId) {
        bank.contributeBankStone(gameId, playerId);
    }

    private void applyBankBonusOnPhase4Entry(Game g) {
        bank.applyBankBonusOnPhase4Entry(g);
    }
}
