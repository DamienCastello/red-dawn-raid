package org.castello.game.domain;

import org.castello.game.Game;
import org.castello.game.GameStatus;
import org.castello.game.Phase;
import org.castello.game.RaidEffects;
import org.castello.game.support.Dice;
import org.castello.game.support.GameStore;
import org.castello.live.LiveEvents;
import org.castello.persistence.GameRepository;
import org.castello.persistence.PlayerRepository;
import org.castello.player.Player;
import org.castello.player.PlayerService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Domaine Cycle de vie d'une partie.
 *
 * Lobby (création, join, présence/AFK, prêts), démarrage (compte à rebours
 * puis startReal : rôles, PV, ressources et cartes de départ, decks),
 * abandon/départ, et conditions de victoire (chasseurs si le vampire tombe,
 * vampire s'il ne reste aucun chasseur libre vivant).
 */
@Service
public class GameLifecycleService {

    private static final int MAX_PLAYERS = 7;

    private final GameStore store;
    private final Dice dice;
    private final DeckService decks;
    private final WeatherService weather;
    private final CorruptionService corruption;
    private final EquipmentService equipment;
    private final PlayerService playerService;
    private final GameRepository gameRepo;
    private final PlayerRepository playerRepo;
    private final LiveEvents live;
    private final TaskScheduler raidScheduler;
    private final TransactionTemplate tx;

    public GameLifecycleService(GameStore store, Dice dice, DeckService decks,
            WeatherService weather, CorruptionService corruption, EquipmentService equipment,
            PlayerService playerService, GameRepository gameRepo, PlayerRepository playerRepo,
            LiveEvents live, @Qualifier("raidTaskScheduler") TaskScheduler raidScheduler,
            PlatformTransactionManager tm) {
        this.store = store;
        this.dice = dice;
        this.decks = decks;
        this.weather = weather;
        this.corruption = corruption;
        this.equipment = equipment;
        this.playerService = playerService;
        this.gameRepo = gameRepo;
        this.playerRepo = playerRepo;
        this.live = live;
        this.raidScheduler = raidScheduler;
        this.tx = new TransactionTemplate(tm);
    }

    private void pushLive(Game g, String msg) {
        if (g.getMessages() == null)
            g.setMessages(new ArrayList<>());
        g.getMessages().add(msg);
        live.message(g, msg);
    }

    @Transactional
    public Game create() {
        String id = UUID.randomUUID().toString();
        Game game = new Game(id, GameStatus.CREATED, 0);
        store.save(game);

        store.afterCommit(() -> {
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

    @Transactional
    public Game addOrUpdatePlayer(String gameId, String playerId, String username) {
        if (username == null || username.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username required");

        Game g = store.loadForUpdate(gameId); // lock pessimiste => join sérialisées
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

        store.save(g);
        if (changed)
            store.afterCommit(() -> live.lobbyUpdated(g));
        return g;
    }

    @Transactional
    public void requestStart(String id) {
        Game g = store.loadForUpdate(id);

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

        // Les bots n'ont pas d'écran de chargement : prêts d'office
        for (Player p : g.getPlayers()) {
            if (p.isBot())
                g.getReadyForStart().add(p.getId());
        }

        store.save(g);
        store.afterCommit(() -> live.lobbyUpdated(g));
    }

    @Transactional
    public void presence(String gameId, String userId) {
        Game g = store.loadForUpdate(gameId);
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

        store.save(g);
        if (changed || g.getStatus() == GameStatus.STARTING) {
            store.afterCommit(() -> live.lobbyUpdated(g));
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

            // Les bots n'ont pas de client, donc pas de heartbeat : jamais fantômes
            if (p.isBot())
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
        Game g = store.loadForUpdate(gameId);
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
                store.save(g);
                store.afterCommit(() -> live.lobbyUpdated(g));
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

            store.save(g);
            store.afterCommit(() -> live.lobbyUpdated(g));
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
            store.save(g);
            store.afterCommit(() -> live.lobbyUpdated(g));
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

        // PV init (vamp = 20 + 10 * nb chasseurs) — doit rester cohérent avec
        // CombatService.maxHpFor (plafond de régénération/soin).
        int huntersCount = (int) g.getPlayers().stream().filter(p -> !"VAMPIRE".equals(p.getRole())).count();
        for (var p : g.getPlayers()) {
            p.setHp("VAMPIRE".equals(p.getRole()) ? 20 + huntersCount * 10 : 20);
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
                p.setHerbs(0);
                p.setWater(0);
                p.setStone(0);
                p.setIron(0);
            }
        }

        // --- Cartes action de départ : AUCUNE carte fixe (décision game
        // designer 2026-07-15) — uniquement la pioche initiale ci-dessous.
        // Caisses et Transmutation avancée ne s'obtiennent plus que par la
        // pioche (elles sont dans les decks).

        decks.initDecks(g);

        // --- Pioche initiale (règle du game designer, 2026-07-14) : chaque
        // chasseur pioche 1 carte Action, le vampire en pioche 1 PAR CHASSEUR. ---
        for (var p : g.getPlayers()) {
            if ("HUNTER".equals(p.getRole())) {
                String cardId = decks.drawHunterAction(g);
                if (cardId != null)
                    p.getActions().add(cardId);
            } else if ("VAMPIRE".equals(p.getRole())) {
                for (int i = 0; i < huntersCount; i++) {
                    String cardId = decks.drawVampAction(g);
                    if (cardId != null)
                        p.getActions().add(cardId);
                }
            }
        }

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
        store.save(g);

        // ====== EVENTS APRÈS COMMIT ======
        store.afterCommit(() -> {
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
        Game g = store.loadForUpdate(gameId);

        if (g.getStatus() != GameStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");
        }

        Player p = g.getPlayers().stream()
                .filter(x -> x.getId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "not in game"));

        // Y a-t-il ENCORE un autre humain qui joue ? (présent = ni bot ni parti)
        boolean otherHumanPresent = g.getPlayers().stream()
                .filter(x -> !x.getId().equals(userId))
                .anyMatch(x -> !x.isBot() && !x.isLeftGame());

        if (p.isAlive() && otherHumanPresent) {
            // ADOPTION (§10 étape 4) : un bot REPREND le personnage VIVANT au lieu de
            // le laisser mourir → la partie reste équilibrée pour les autres humains.
            // Un Player adopté est reconnu partout par le flag isBot (son id reste
            // celui du compte, mais aucun code ne suppose le préfixe « bot: »).
            p.setBot(true);
            g.addHistory(g.nameOf(p.getId())
                    + " a abandonné : un bot reprend son personnage.");
        } else {
            // Dernier humain, ou personnage déjà à terre : abandon = mort (historique).
            p.setHp(0);
            // IMPORTANT : ne pas faire p.setLeftGame(true) ici
            handleDeathsAndVictory(g); // peut terminer la game (ex : vampire seul abandonne)
        }

        // Dans les deux cas l'HUMAIN se détache : on libère son membership SQL pour
        // qu'il puisse rejoindre une autre partie (le Player du blob, lui, reste).
        playerService.leaveGame(userId, gameId);

        store.save(g);

        store.afterCommit(() -> {
            Game fresh = store.read(gameId);
            live.lobbyUpdated(fresh);
            live.phaseChanged(fresh); // pour forcer refresh snapshot chez les clients
        });
    }

    public void leave(String gameId, String userId) {
        tx.execute(status -> {
            Game g = store.loadForUpdate(gameId);

            Player p = g.getPlayers().stream()
                    .filter(x -> x.getId().equals(userId))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "not in game"));

            // Si ACTIVE: quitte QUE si déjà mort (hp<=0)
            if (g.getStatus() == GameStatus.ACTIVE && p.isAlive()) {
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
                store.save(g);
            }

            store.afterCommit(() -> {
                if (shouldDelete) {
                    live.gameDeleted(gameId);
                } else {
                    Game fresh = store.read(gameId);
                    live.lobbyUpdated(fresh);
                    live.phaseChanged(fresh);
                }
            });

            return null;
        });
    }

    public void handleDeathsAndVictory(Game g) {
        // 1) Traitement "on death" (défausser actions chasseur, etc.)
        for (Player p : g.getPlayers()) {
            if (!p.isAlive()) {
                onPlayerDeath(g, p);
            }
        }

        // 2) Vérifier si la partie doit se terminer
        checkEndGame(g);
    }

    private void onPlayerDeath(Game g, Player p) {
        // Idempotent : si la main est déjà vide, discardAllActionsOf ne cassera rien.
        if ("HUNTER".equals(p.getRole())) {
            decks.discardAllActionsOf(g, p);
            decks.discardAllPotionsOf(g, p);
        }
        if ("SERVANT".equals(p.getRole())) {
            decks.discardAllPotionsOf(g, p);
        }
    }

    private void checkEndGame(Game g) {
        // Si la partie n’est plus active, on ne touche à rien
        if (g.getStatus() != GameStatus.ACTIVE)
            return;

        boolean vampAlive = g.getPlayers().stream()
                .anyMatch(p -> "VAMPIRE".equals(p.getRole()) && p.isAlive());

        // IMPORTANT : tu passes déjà les chasseurs à role "SERVANT" quand corruption ==
        // 3
        // donc ici on veut uniquement les chasseurs encore "libres" (role HUNTER)
        boolean hunterAlive = g.getPlayers().stream()
                .anyMatch(p -> "HUNTER".equals(p.getRole()) && p.isAlive());

        if (!vampAlive) {
            g.setStatus(GameStatus.ENDED);
            g.setWinnerSide("HUNTERS");
            g.addHistory("Victoire des chasseurs: le vampire est terrassé.");
            g.setMessages(java.util.List.of("Fin de partie — Les chasseurs triomphent."));
            return;
        }

        if (!hunterAlive) {
            g.setStatus(GameStatus.ENDED);
            g.setWinnerSide("VAMPIRE");
            g.addHistory("Victoire du vampire: plus aucun chasseur n'est debout.");
            g.setMessages(java.util.List.of("Fin de partie — Le vampire règne sans partage."));
        }
    }

}
