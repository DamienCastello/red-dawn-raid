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

    public GameSnapshot viewSnapshot(String gameId, String userId) {
        Game g = findOr404(gameId);

        boolean isVamp = g.getPlayers().stream()
                .anyMatch(p -> userId.equals(p.getId()) && "VAMPIRE".equals(p.getRole()));

        // Wrappers null-safe (raccourcis locaux)
        List<Player> playersSrc                      = (g.getPlayers()               != null) ? g.getPlayers()               : java.util.Collections.emptyList();
        List<CenterBoard> centerSrc                  = (g.getCenter()                != null) ? g.getCenter()                : java.util.Collections.emptyList();
        Map<String, List<StatMod>> raidModsSrc       = (g.getRaidMods()              != null) ? g.getRaidMods()              : java.util.Collections.emptyMap();
        java.util.Set<String> readySet               = (g.getReadyForPhase3()        != null) ? g.getReadyForPhase3()        : java.util.Collections.emptySet();
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
            List<String> potions = g.potionsOf(p.getId());
            if (potions == null) potions = java.util.List.of(); // null-safe

            return new GameSnapshot.PlayerView(
                    p.getId(),
                    p.getUsername(),
                    p.getRole(),
                    p.getHp(),
                    p.getCorruption(),
                    potions,
                    p.getAttackDice() != null ? p.getAttackDice() : "D6",
                    p.getDefenseDice() != null ? p.getDefenseDice() : "D6",
                    p.getWood(), p.getHerbs(), p.getStone(), p.getIron(),
                    p.getWater(), p.getGold(), p.getSouls(), p.getSilver(),
                    p.getId().equals(userId) ? (p.getHand() != null ? p.getHand() : java.util.List.of())
                            : java.util.List.of()
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

        // Decks
        var decks = new GameSnapshot.DecksView(
                new GameSnapshot.DecksView.Pile(g.getVampActionsLeft(),   g.getVampActionsDiscard()),
                new GameSnapshot.DecksView.Pile(g.getHunterActionsLeft(), g.getHunterActionsDiscard()),
                new GameSnapshot.DecksView.Pile(g.getPotionsLeft(),       g.getPotionsDiscard())
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
                    r.getResolvedAtMillis(),
                    (r.getBreakdownLines() != null ? r.getBreakdownLines() : java.util.List.of())
            );
        }

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

        return new GameSnapshot(
                g.getId(),
                (g.getStatus() != null ? g.getStatus().name() : "CREATED"),
                g.getRaid(),
                (g.getPhase()  != null ? g.getPhase().name()  : "PHASE0"),
                weather,
                players,
                center,
                raidMods,
                g.isHasUpcomingCombat(),
                readyList,
                decks,
                bite,
                combatsQueue,
                g.getCurrentCombatIndex(),
                currentCombat,
                uTargets,
                uLocs,
                uChosenTargets,
                uChosenHarvests,
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
        if (g.getUnstableEligibleTargets() == null)   g.setUnstableEligibleTargets(new java.util.HashMap<>());
        if (g.getUnstableTargetByPlayer() == null)    g.setUnstableTargetByPlayer(new java.util.HashMap<>());
        if (g.getUnstableEligibleLocations() == null) g.setUnstableEligibleLocations(new java.util.HashMap<>());
        if (g.getUnstableHarvestLocByPlayer() == null)g.setUnstableHarvestLocByPlayer(new java.util.HashMap<>());
        if (g.getCombatsQueue() == null)         g.setCombatsQueue(new java.util.ArrayList<>());
        if (g.getCenter() == null)               g.setCenter(new java.util.ArrayList<>());
        if (g.getPotionsByPlayer() == null)      g.setPotionsByPlayer(new java.util.HashMap<>());
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
        return g.getCenter().stream().anyMatch(cb -> cb.getPlayerId().equals(playerId));
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
     * Construit la liste des modificateurs “moteur” d’un joueur pour le raid courant.
     *
     * Sources possibles (exemples) :
     * - Météo active (si tirage effectué et statut appliqué)
     * - Potions consommées au début du combat (effets “ce raid”)
     * - Actions jouées (Filet, Fosse, etc., si tu les ajoutes plus tard)
     * - Corruption niveau 1 (affaibli : −1 ATK/−1 DEF) — mais pas au niveau 2 (“instable”)
     *
     * Remarques :
     * - On ne renvoie ici que les mods “numériques” utiles au calcul des dés.
     * - Les pastilles purement visuelles peuvent être gérées côté front (ex: chip “Instable”).
     */
    private List<StatMod> modsAppliedFor(Game g, String playerId, String stat){
        var all = g.getRaidMods() != null ? g.getRaidMods().get(playerId) : null;
        if (all == null) return java.util.List.of();
        return all.stream()
                .filter(m -> stat.equalsIgnoreCase(m.getStat()))
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
    private int totalModFor(Game g, String playerId, String stat){
        return modsAppliedFor(g, playerId, stat)
                .stream().mapToInt(StatMod::getAmount).sum();
    }

    /** Construit les logs liés aux mods (affichés dans la modale spectateur ET poussés dans l'historique). */
    private List<String> buildModBreakdownLines(Game g, String playerId, String stat, int baseRoll){
        List<String> out = new ArrayList<>();
        int cur = baseRoll;
        String sideLabel = "ATTACK".equalsIgnoreCase(stat) ? "L’attaque" : "La défense";
        String name = nameOf(g, playerId);

        for (var m : modsAppliedFor(g, playerId, stat)) {
            int delta = m.getAmount();
            if (delta == 0) continue;

            String verb = (delta >= 0) ? "augmente" : "diminue";
            int abs = Math.abs(delta);

            String src = "";
            String s = (m.getSource() == null) ? "" : m.getSource();
            if (m.getSource() != null && m.getSource().startsWith("WEATHER:")) {
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
        /*
        for (var p : g.getPlayers()) {
            g.getPotionsByPlayer().computeIfAbsent(p.getId(), __ -> new ArrayList<>());
            if ("HUNTER".equals(p.getRole())) {
                g.getPotionsByPlayer().get(p.getId()).addAll(List.of("FORCE", "ENDURANCE", "VIE"));
            }
        }
        */

        // --- Compteurs / centre ---
        g.setVampActionsLeft(20);    g.setVampActionsDiscard(0);
        g.setHunterActionsLeft(35);  g.setHunterActionsDiscard(0);
        g.setPotionsLeft(22);        g.setPotionsDiscard(0);
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
                g.setRaid(g.getRaid() + 1);
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
                }
                case PHASE1 -> {
                    if (to != Phase.PHASE2) throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    if (!allHuntersSelected(g)) throw new ResponseStatusException(HttpStatus.CONFLICT, "hunters not all selected");
                    applyPhaseEntry(g, Phase.PHASE2);
                }
                case PHASE2 -> {
                    if (to != Phase.PREPHASE3) throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    if (!allVampSideSelected(g)) throw new ResponseStatusException(HttpStatus.CONFLICT, "vamp/servants not all selected");
                    g.setHasUpcomingCombat(computeHasUpcomingCombat(g));
                    applyPhaseEntry(g, Phase.PREPHASE3);
                }
                case PREPHASE3 -> {
                    if (to != Phase.PHASE3) throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    boolean hasPendingUnstable =
                            !(g.getUnstableEligibleTargets().isEmpty() && g.getUnstableEligibleLocations().isEmpty());
                    if (hasPendingUnstable) throw new ResponseStatusException(HttpStatus.CONFLICT, "unstable choices pending");
                    if (!allReadyForPhase3(g)) throw new ResponseStatusException(HttpStatus.CONFLICT, "players not ready");
                    applyPhaseEntry(g, Phase.PHASE3);
                }
                case PHASE3 -> {
                    if (to != Phase.PHASE4) throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    applyPhaseEntry(g, Phase.PHASE4);
                }
                case PHASE4 -> {
                    if (to != Phase.PHASE0) throw new ResponseStatusException(HttpStatus.CONFLICT, "illegal advance");
                    applyPhaseEntry(g, Phase.PHASE0);
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

        // Phase/roles
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
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "not a selection phase");
        }

        if (hasPlayed(g, playerId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already selected this round");

        var hand = p.getHand();
        if (hand == null || !hand.remove(card))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "card not in hand");

        // Pose au centre (face cachée)
        g.getCenter().add(new CenterBoard(playerId, card, /*faceUp=*/false));

        // Flags d’auto-advance (calculés AVANT le commit)
        boolean advanceToP2   = (g.getPhase() == Phase.PHASE1) && allHuntersSelected(g);
        boolean advanceToPre3 = (g.getPhase() == Phase.PHASE2) && allVampSideSelected(g);

        if (advanceToPre3) {
            g.setHasUpcomingCombat(computeHasUpcomingCombat(g));
            scheduleAdvance(g.getId(), Phase.PHASE2, Phase.PREPHASE3, 2500);
        }

        // ----- COMMIT des changements -----
        save(g);

        // ----- EVENTS APRÈS COMMIT -----
        final String gid = g.getId();
        afterCommit(() -> {
            // 1) informer le front que la grille "center" a bougé
            live.locationSelected(g, playerId, card);
            // (pas d’autres events ici : la bascule de phase viendra du scheduler ci-dessous)
        });

        // ----- AUTO-ADVANCE planifié (séparé, transactionnel + afterCommit à l’intérieur) -----
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
        var unstableAssigned = new java.util.HashSet<>(g.getUnstableTargetByPlayer().keySet());
        unstableAssigned.addAll(g.getUnstableHarvestLocByPlayer().keySet());

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
                    // Round 1 : chasseur attaque
                    g.getCombatsQueue().add(new RoundFight(
                            java.util.UUID.randomUUID().toString(), loc, h.getId(), enemy.getId()
                    ));
                    // Round 2 : ennemi attaque
                    g.getCombatsQueue().add(new RoundFight(
                            java.util.UUID.randomUUID().toString(), loc, enemy.getId(), h.getId()
                    ));
                }
            }
        }

        // 2) Duels "instable -> cible"
        for (var entry : g.getUnstableTargetByPlayer().entrySet()) {
            String unstableId = entry.getKey();
            String targetId   = entry.getValue();

            String loc = g.getCenter().stream()
                    .filter(cb -> cb.getPlayerId().equals(targetId))
                    .map(CenterBoard::getCard)
                    .findFirst()
                    .orElse("forest");

            g.getCombatsQueue().add(new RoundFight(
                    java.util.UUID.randomUUID().toString(), loc, unstableId, targetId
            ));

            // Message lisible
            String info = "Combat — " + nameOf(g, unstableId) + " VS " + nameOf(g, targetId) + " à " + labelLieuFr(loc);
            if (g.getMessages() == null) g.setMessages(new java.util.ArrayList<>());
            g.getMessages().add(info);
            addHistory(g, info);
        }

        // 3) Pointeur sur le combat courant (plus aucun nextAdvanceAt/timer côté serveur)
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

        // ---- Payloads d’événements à émettre APRÈS commit
        final class Ev {
            boolean sendAtk, sendDef, sendResolved, startBite;
            String roundId, atkId, defId, biteAtt, biteTgt, biteLoc;
            Integer atkRoll, defRoll, dmg, defenderHp;
            java.util.List<String> breakdown = java.util.List.of();
        }
        Ev ev = new Ev();
        ev.roundId = r.getId();

        // --- Pose du jet (attacker OU defender)
        if (userId.equals(r.getAttackerId()) && r.getAttackerRoll() == null) {
            var p = g.getPlayers().stream().filter(pp -> pp.getId().equals(userId)).findFirst().orElseThrow();
            int sides = diceSides(p.getAttackDice());
            int roll = 1 + RND.nextInt(sides);
            r.setAttackerRoll(roll);
            addHistory(g, nameOf(g, r.getAttackerId()) + " — jet d'attaque = " + roll + ".");
            ev.sendAtk = true; ev.atkId = r.getAttackerId(); ev.atkRoll = roll;

        } else if (userId.equals(r.getDefenderId()) && r.getDefenderRoll() == null) {
            var p = g.getPlayers().stream().filter(pp -> pp.getId().equals(userId)).findFirst().orElseThrow();
            int sides = diceSides(p.getDefenseDice());
            int roll = 1 + RND.nextInt(sides);
            r.setDefenderRoll(roll);
            addHistory(g, nameOf(g, r.getDefenderId()) + " — jet de défense = " + roll + ".");
            ev.sendDef = true; ev.defId = r.getDefenderId(); ev.defRoll = roll;

        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
        }

        // --- Résolution si les 2 jets sont posés (et pas encore résolu)
        if (r.getAttackerRoll() != null && r.getDefenderRoll() != null && combatNotResolved(r)) {
            int atk = r.getAttackerRoll();
            int def = r.getDefenderRoll();
            int atkMod = totalModFor(g, r.getAttackerId(), "ATTACK");
            int defMod = totalModFor(g, r.getDefenderId(), "DEFENSE");
            int dmg    = Math.max(0, (atk + atkMod) - (def + defMod));

            var defPlayer = g.getPlayers().stream().filter(p -> p.getId().equals(r.getDefenderId())).findFirst().orElse(null);
            if (defPlayer != null && dmg > 0) {
                defPlayer.setHp(Math.max(0, defPlayer.getHp() - dmg));
            }

            String theftLine = null;
            var atkPlayer = g.getPlayers().stream().filter(p -> p.getId().equals(r.getAttackerId())).findFirst().orElse(null);
            if (atkPlayer != null && "VAMPIRE".equals(atkPlayer.getRole())
                    && defPlayer != null && "HUNTER".equals(defPlayer.getRole()) && dmg > 0) {
                theftLine = vampStealOne(g, atkPlayer, defPlayer);
            }

            var atkBk = buildModBreakdownLines(g, r.getAttackerId(), "ATTACK",  r.getAttackerRoll());
            var defBk = buildModBreakdownLines(g, r.getDefenderId(), "DEFENSE", r.getDefenderRoll());
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
        class Ev { boolean biteResolved; boolean advanced; String att, tgt, loc; }

        Ev ev = tx.execute(status -> {
            Game g = findOr404(gameId);
            if (g.getPhase() != Phase.PHASE3)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PHASE3");

            Ev out = new Ev();

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
                if (r == null) { save(g); return out; }
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

        // Events après commit, seulement si quelque chose a changé
        if (ev.biteResolved) {
            live.biteResolved(gAfter, ev.att, ev.tgt, ev.loc);
        }
        if (ev.advanced) {
            // heartbeat pour faire faire un GET propre côté front
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
        applyWeatherRollMut(g, roll);

        // 2) Commit
        save(g);

        // 3) Events APRÈS COMMIT (aucune course avec les GET)
        afterCommit(() -> {
            live.weatherRolled(g);      // payload construit depuis g (déjà commité)
            live.raidModsUpdated(g);    // pour rafraîchir les puces météo côté UI
        });

        return g;
    }

    private void applyWeatherRollMut(@NonNull Game g, int roll) {
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
    public Game usePotion(String gameId, String userId, Potion type) {
        Game g = findOr404(gameId);

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        // Uniquement en PREPHASE3, s'il y a un combat imminent, et si je suis concerné
        if (g.getPhase() != Phase.PREPHASE3 || !g.isHasUpcomingCombat())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "potions usable only during PREPHASE3 before combat");

        Set<String> allowed = participantsOfUpcomingCombat(g);
        if (!allowed.contains(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "you are not part of the upcoming combat");

        // inventaire
        var inv = g.getPotionsByPlayer().get(userId);
        if (inv == null || !inv.contains(type.name()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "potion not in inventory");

        // --- mutations + historique (PAS d'events ici)
        String feedText;               // message pour le flux (envoyé après commit)
        boolean touchMods = false;     // est-ce qu'on a modifié des mods (FORCE/ENDURANCE)

        switch (type) {
            case FORCE -> {
                if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());
                g.getRaidMods().computeIfAbsent(userId, __ -> new ArrayList<>())
                        .add(new StatMod("ATTACK", +1, "POTION:FORCE"));
                addHistory(g, nameOf(g, userId) + " utilise une Potion de force (+1 attaque ce raid).");
                feedText = nameOf(g, userId) + " boit une Potion de force !";
                touchMods = true;
            }
            case ENDURANCE -> {
                if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());
                g.getRaidMods().computeIfAbsent(userId, __ -> new ArrayList<>())
                        .add(new StatMod("DEFENSE", +1, "POTION:ENDURANCE"));
                addHistory(g, nameOf(g, userId) + " utilise une Potion d’endurance (+1 défense ce raid).");
                feedText = nameOf(g, userId) + " boit une Potion d’endurance !";
                touchMods = true;
            }
            case VIE -> {
                var p = g.getPlayers().stream().filter(pp -> pp.getId().equals(userId)).findFirst().orElseThrow();
                int before = p.getHp();
                int max = ("VAMPIRE".equals(p.getRole()))
                        ? (20 + (int) g.getPlayers().stream().filter(x -> "HUNTER".equals(x.getRole())).count() * 10)
                        : 20;
                p.setHp(Math.min(max, p.getHp() + 10));
                int healed = p.getHp() - before;
                addHistory(g, nameOf(g, userId) + " utilise une Potion de vie (+" + healed + " PV).");
                feedText = nameOf(g, userId) + " boit une Potion de vie !";
                // touchMods reste false, mais on forcera quand même un refresh côté front (voir events ci-dessous)
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown potion");
        }

        // consommer l’item
        inv.remove(type.name());
        if (inv.isEmpty()) g.getPotionsByPlayer().remove(userId);

        // --- commit
        save(g);

        // --- events APRÈS COMMIT (ordre maîtrisé)
        afterCommit(() -> {
            // 1) petit feed texte
            pushLive(g, feedText);

            // 2) event sémantique (si tu l’utilises côté front)
            live.potionUsed(g, userId, type.name());

            // 3) forcer un refresh complet côté UI (tu fais déjà un GET sur RAID_MODS_UPDATED)
            //    - utile pour FORCE/ENDURANCE (mods)
            //    - utile aussi pour VIE (HP) vu que ton handler resynchronise tout le snapshot
            live.raidModsUpdated(g);
        });

        return g;
    }

    private void pushLive(Game g, String msg){
        if (g.getMessages() == null) g.setMessages(new ArrayList<>());
        g.getMessages().add(msg);
        // notifie le front (déjà géré dans onLiveEvent: MESSAGE)
        live.message(g, msg);
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
}
