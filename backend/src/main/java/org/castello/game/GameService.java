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

import org.castello.persistence.GameEntity;
import org.castello.persistence.GameRepository;

import java.util.*;

@Service
public class GameService {

// ----- PERSISTENCE -----
    private final GameRepository repo;
    private final ObjectMapper mapper; // Jackson fourni par Spring Boot

    public GameService(GameRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

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

// ----------------------------------------------------------

    private static final long PHASE_DELAY_MS = 5000L;   // 5 s (fenêtre “actions” quand tout le monde a joué)
    private static final long PREPHASE3_WINDOW_MS = 30_000L; // 20 s avant PHASE3

// ---------- utilitaires ----------
    private static final Random RND = new Random();

    private boolean computeHasUpcomingCombat(@NonNull Game g) {
        var vampOpt = getVamp(g);
        if (vampOpt.isEmpty()) return false;
        var vamp = vampOpt.get();
        var groups = groupPlayersByLocation(g);
        for (var e : groups.entrySet()) {
            var onLoc = e.getValue();
            boolean enemyHere = onLoc.stream().anyMatch(p -> "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()));
            boolean hunterHere = onLoc.stream().anyMatch(p -> "HUNTER".equals(p.getRole()));
            if (enemyHere && hunterHere) return true;
        }
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
        return g;
    }

    @Transactional
    public Game start(String id) {
        Game g = findOr404(id);
        if (g.getStatus() != GameStatus.CREATED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already started/ended");
        if (g.getPlayers().size() < 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "need at least 2 players");

        g.setStatus(GameStatus.ACTIVE);
        g.setRaid(1);

        // === PHASE0 : météo ===
        g.setPhase(Phase.PHASE0);
        g.setPhaseStartMillis(System.currentTimeMillis());
        g.setWeatherRoll(null);
        g.setWeatherStatus(null);
        g.setWeatherStatusNameFr(null);
        g.setWeatherDescriptionFr(null);
        g.setWeatherShowUntilMillis(0L);

        // petit timeout confort player : on laisse 3s avant d’ouvrir la modale
        g.setWeatherModalNotBeforeMillis(System.currentTimeMillis() + 5_000L);


        // PHASE1 (chasseurs)
        //Le passage en PHASE1 est planifié par applyWeatherRoll(...)

        // rôles + mains
        int vampIndex = RND.nextInt(g.getPlayers().size());
        for (int i = 0; i < g.getPlayers().size(); i++) {
            Player p = g.getPlayers().get(i);
            p.setRole(i == vampIndex ? "VAMPIRE" : "HUNTER");
            p.setHand(new ArrayList<>(List.of("forest", "quarry", "lake", "manor")));
        }

        // actions & potions // dev -> a supprimer a la fin
        // Donner 1 potion de chaque aux chasseurs pour tester
        for (var p : g.getPlayers()) {
            g.getPotionsByPlayer().computeIfAbsent(p.getId(), __ -> new ArrayList<>());
            if ("HUNTER".equals(p.getRole())) {
                g.getPotionsByPlayer().get(p.getId()).addAll(List.of("FORCE","ENDURANCE","VIE"));
            }
        }

        // init hp & dices
        int huntersCount = (int) g.getPlayers().stream().filter(p -> !"VAMPIRE".equals(p.getRole())).count();
        for (var p : g.getPlayers()) {
            // Dés de base
            p.setAttackDice("D6");
            p.setDefenseDice("D6");
            // PV
            if ("VAMPIRE".equals(p.getRole())) {
                p.setHp(20 + huntersCount * 10); // ex: 2 chasseurs -> 40 PV
            } else {
                p.setHp(20);
            }
        }

        // compteurs + centre
        g.setVampActionsLeft(20);    g.setVampActionsDiscard(0);
        g.setHunterActionsLeft(35);  g.setHunterActionsDiscard(0);
        g.setPotionsLeft(22);        g.setPotionsDiscard(0);
        g.setCenter(new ArrayList<>());

        // clear auto-advance
        g.setPendingNextPhase(null);
        g.setNextAutoAdvanceAtMillis(0);

        save(g);
        return g;
    }

    // ---------- TICK ----------
    @Transactional
    public Game tickAndGet(String gameId) {
        Game g = findOr404(gameId);

        String before = toJson(g);

        maybeAutoAdvance(g);

        String after = toJson(g);

        if (!after.equals(before)) {
            save(g);
        }
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

    private void planNextPhase(@NonNull Game g, Phase next) {
        g.setPendingNextPhase(next);
        g.setNextAutoAdvanceAtMillis(System.currentTimeMillis() + PHASE_DELAY_MS);
    }

    private void planNextPhaseWithDelay(@NonNull Game g, Phase next, long delayMs) {
        g.setPendingNextPhase(next);
        g.setNextAutoAdvanceAtMillis(System.currentTimeMillis() + delayMs);
    }

    /**
     * Applique, si nécessaire, une auto-avance de phase préprogrammée.
     *
     * Principe :
     * - Certaines transitions sont planifiées (pendingNextPhase + nextAutoAdvanceAtMillis).
     * - Si la date butoir est atteinte, on bascule dans la phase suivante en appliquant
     *   les effets d’entrée/sortie de phase (logs/historiques, timers, etc.).
     * - Cette méthode est appelée en “tick” sur la plupart des lectures/commandes afin de
     *   garantir que l’état de partie reflète les auto-avances prévues.
     *
     * In:  Game g (muté si auto-avance)
     * Out: void (mais g peut changer de phase et enrichir ses historiques)
     */
    private void applyPendingPhase(@NonNull Game g) {
        // applique le passage de phase planifié (PHASE_DELAY_MS) et réinitialise le timer de phase
        Phase to = g.getPendingNextPhase();
        g.setPhase(to);
        g.setPendingNextPhase(null);
        g.setNextAutoAdvanceAtMillis(0);
        g.setPhaseStartMillis(System.currentTimeMillis());

        switch (to) {
            case PHASE0 -> {
                g.setWeatherRoll(null);
                g.setWeatherStatus(null);
                g.setWeatherStatusNameFr(null);
                g.setWeatherDescriptionFr(null);
                g.setWeatherShowUntilMillis(0L);
                g.setPhaseStartMillis(System.currentTimeMillis());
                g.setMessages(new ArrayList<>(java.util.List.of("Tirage météo ...")));

                if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());

                // purge des effets transitoires (potions/actions) du raid précédent
                for (var list : g.getRaidMods().values()) {
                    if (list != null) {
                        list.removeIf(m -> {
                            String s = m.getSource();
                            return s != null && (s.startsWith("POTION:") || s.startsWith("ACTION:"));
                        });
                    }
                }

                rebuildCorruptionMods(g);
                rebuildWeatherMods(g);

                // raidEffects safe
                if (g.getRaidEffects() == null) g.setRaidEffects(new HashMap<>());
                g.getRaidEffects().clear();
                for (var p : g.getPlayers()) {
                    g.getRaidEffects().put(p.getId(), new RaidEffects());
                }

                // récolte reset
                g.setHarvestedRaid(null);

                // petit timeout confort player : 3s avant la modale
                g.setWeatherModalNotBeforeMillis(System.currentTimeMillis() + 3_000L);
            }
            case PHASE1 -> {
                g.setMessages(new ArrayList<>(List.of("Les chasseurs planifient un raid…")));
            }
            case PHASE2 -> {
                g.setMessages(new ArrayList<>(List.of("Le vampire s’éveille…")));
            }
            case PREPHASE3 -> {
                // 1) Révéler toutes les cartes maintenant (mais ne pas encore construire les messages)
                for (var cb : g.getCenter()) cb.setFaceUp(true);

                // 2) (Ré)initialiser les structures d’“instable”
                g.getUnstableTargetByPlayer().clear();
                g.getUnstableEligibleTargets().clear();
                g.getUnstableHarvestLocByPlayer().clear();
                g.getUnstableEligibleLocations().clear();

                // 3) Tirage instable pour chaque chasseur corruption=2
                List<String> center = new ArrayList<>();
                List<String> history = new ArrayList<>();

                for (var p : getHunters(g)) {
                    if (p.getCorruption() == 2) {
                        int roll = 1 + RND.nextInt(6);
                        addHistory(g, nameOf(g, p.getId()) + " — Corruption (instable) jet de d6 = " + roll + ".");
                        if (roll <= 3) {
                            // cibles possibles (autres chasseurs vivants)
                            var eligibleHunters = getHunters(g).stream()
                                    .filter(h -> !h.getId().equals(p.getId()))
                                    .filter(h -> h.getHp() > 0)
                                    .map(Player::getId).toList();
                            if (!eligibleHunters.isEmpty()) {
                                g.getUnstableEligibleTargets().put(p.getId(), new ArrayList<>(eligibleHunters));
                            }
                            // lieux toujours possibles
                            g.getUnstableEligibleLocations().put(p.getId(),
                                    new ArrayList<>(List.of("forest","quarry","lake","manor")));

                            String infoA = nameOf(g, p.getId()) + " succombe à la corruption.";
                            String infoB = nameOf(g, p.getId()) + " est sous contrôle du vampire ...";
                            // On n’écrit PAS encore dans l’historique ici (sinon doublons à cause du poll)
                            history.add(infoA);
                            history.add(infoB);
                            center.add(infoB);
                        } else {
                            String infoC = nameOf(g, p.getId()) + " résiste à la corruption.";
                            history.add(infoC);
                            center.add(infoC);
                        }
                    }
                }

                // 4) Maintenant que l’on sait qui est instable, on peut construire
                //    les messages de révélation (récoltes/combat), en MASQUANT la récolte
                //    des chasseurs instables (elle sera remplacée par leur redirection/choix).
                center.addAll(buildRevealMessages(g));
                g.setMessages(center);
                for (var m : history) addHistory(g, m);

                // 5) Fenêtre PREPHASE3
                g.getReadyForPhase3().clear();
                long now = System.currentTimeMillis();
                long window = g.isHasUpcomingCombat() ? PREPHASE3_WINDOW_MS : 4000L;
                g.setPrePhaseDeadlineMillis(now + window);

                // 6) Verrou : s'il reste un choix instable, on n’auto-planifie pas PHASE3
                boolean hasPendingUnstable =
                        !g.getUnstableEligibleTargets().isEmpty() || !g.getUnstableEligibleLocations().isEmpty();
                if (!hasPendingUnstable) {
                    planNextPhaseWithDelay(g, Phase.PHASE3, window);
                } else {
                    g.setPendingNextPhase(null);
                    g.setNextAutoAdvanceAtMillis(0L);
                }
            }
            case PHASE3 -> {
                // 0) Récoltes (une seule fois par raid)
                if (g.getHarvestedRaid() == null || !g.getHarvestedRaid().equals(g.getRaid())) {
                    applyHarvests(g);
                    g.setHarvestedRaid(g.getRaid());
                }

                // 1) Construire la file de combats
                buildCombatsQueue(g);

                // 2) Si aucun combat : message + passage maintenance
                if (g.getCurrentCombat() == null) {
                    if (g.getMessages() == null) g.setMessages(new ArrayList<>());
                    g.getMessages().add("Aucun combat ce raid.");
                    planNextPhaseWithDelay(g, Phase.PHASE4, 1500L);
                }
            }
            case PHASE4 -> {
                // Maintenance : on rend les cartes aux propriétaires et on vide le centre
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
                // Préparer le raid suivant : retour PHASE0 (météo) → PHASE1
                g.setRaid(g.getRaid() + 1);
                planNextPhase(g, Phase.PHASE0);
            }
            default -> { /* rien de particulier */ }
        }
    }

    /**
     * Démarre / cadence le moteur de phase :
     * - applique une phase planifiée quand l’échéance est atteinte,
     * - montre les messages météo au centre,
     * - en PHASE3, gère la succession des duels ET la fenêtre post-morsure.
     */
    private void maybeAutoAdvance(@NonNull Game g) {
        long now = System.currentTimeMillis();

        // 1) appliquer une phase planifiée si l’heure est venue
        if (g.getPendingNextPhase() != null &&
                g.getNextAutoAdvanceAtMillis() != 0 &&
                now >= g.getNextAutoAdvanceAtMillis()) {
            applyPendingPhase(g);
            return;
        }

        /*
        // PHASE0 : après la modale (5s), on affiche la météo au centre pendant 5s avant PHASE1
        if (g.getPhase() == Phase.PHASE0 && g.getWeatherRoll() != null) {
            boolean modalOver = now >= g.getWeatherShowUntilMillis();
            boolean centerEmpty = g.getMessages() == null || g.getMessages().isEmpty();

            if (modalOver && centerEmpty) {
                // On injecte le message "Météo ..." au centre (sera visible ~5s jusqu'au passage en PHASE1)
                List<String> msgs = new ArrayList<>();
                msgs.add("Météo — " + (g.getWeatherStatusNameFr() != null ? g.getWeatherStatusNameFr() : ""));
                if (g.getWeatherDescriptionFr() != null && !g.getWeatherDescriptionFr().isBlank()) {
                    msgs.add(g.getWeatherDescriptionFr());
                }
                g.setMessages(msgs);
                // NB : pas de return, on laisse tourner les autres règles; le passage PHASE1 est déjà planifié à +10s total
            }
        }
        */

        // PHASE3 — gestion de la fenêtre post-morsure
        if (g.getPhase() == Phase.PHASE3 && g.getCurrentBite() != null) {
            if (g.getCurrentBite().getRoll() != null &&
                    g.getCurrentBiteNextAdvanceAtMillis() > 0 &&
                    now >= g.getCurrentBiteNextAdvanceAtMillis()) {

                // on nettoie l’état morsure
                g.setCurrentBite(null);
                g.setCurrentBiteNextAdvanceAtMillis(0L);

                // puis on enchaîne comme si on était au "2.b" (duel suivant)
                int nextIdx = g.getCurrentCombatIndex() + 1;
                if (nextIdx < g.getCombatsQueue().size()) {
                    g.setCurrentCombatIndex(nextIdx);
                    g.setCurrentCombat(g.getCombatsQueue().get(nextIdx));
                    g.setCurrentCombatNextAdvanceAtMillis(0L);
                } else {
                    g.setCurrentCombat(null);
                    g.setCurrentCombatIndex(null);
                    g.setCurrentCombatNextAdvanceAtMillis(0L);
                    planNextPhase(g, Phase.PHASE4);
                }
                return;
            }
            // Pare-chocs: tant qu'une morsure est ouverte (avec ou sans jet),
            // on NE recalcule pas le duel courant (sinon dégâts en boucle).
            return;
        }

        // 2) Cadencer les combats (PHASE3)
        if (g.getPhase() == Phase.PHASE3) {

            // ⛳ Garde-fou : si aucun combat n'est prévu, on évite le blocage.
            if (g.getCurrentCombat() == null) {
                if (g.getPendingNextPhase() == null) {
                    if (g.getMessages() == null) g.setMessages(new ArrayList<>());
                    g.getMessages().add("Aucun combat ce raid.");
                    addHistory(g, "Aucun combat ce raid.");
                    // petit délai possible : planNextPhaseWithDelay(g, Phase.PHASE4, 1500L);
                    planNextPhase(g, Phase.PHASE4);
                }

                return; // rien à faire d'autre ce tick
            }

            var r = g.getCurrentCombat();
            boolean bothRolled = r.getAttackerRoll() != null && r.getDefenderRoll() != null;

            // 2.a) Quand les 2 ont jeté le dé → appliquer dégâts + planifier +4s vers "duel suivant"
            if (bothRolled
                    && g.getCurrentCombatNextAdvanceAtMillis() == 0L
                    && (r.getResolvedAtMillis() == null || r.getResolvedAtMillis() == 0L)) {
                int atk = r.getAttackerRoll();
                int def = r.getDefenderRoll();

                addHistory(g, nameOf(g, r.getAttackerId()) + " — jet d'attaque = " + atk + ".");
                addHistory(g, nameOf(g, r.getDefenderId()) + " — jet de défense = " + def + ".");

                // Ajoute les mods meteo (et plus tard cartes), avec exceptions lieu
                int atkMod = totalModFor(g, r.getAttackerId(), "ATTACK");
                int defMod = totalModFor(g, r.getDefenderId(), "DEFENSE");

                int dmg = Math.max(0, (atk + atkMod) - (def + defMod));

                // dégâts sur le défenseur (jamais négatif)
                var defPlayer = g.getPlayers().stream()
                        .filter(p -> p.getId().equals(r.getDefenderId()))
                        .findFirst().orElse(null);
                if (defPlayer != null && dmg > 0) {
                    defPlayer.setHp(Math.max(0, defPlayer.getHp() - dmg));
                }

                String theftLine = null;

                // VOL du vampire
                var atkPlayer = g.getPlayers().stream()
                        .filter(p -> p.getId().equals(r.getAttackerId()))
                        .findFirst().orElse(null);
                if (atkPlayer != null && "VAMPIRE".equals(atkPlayer.getRole()) &&
                        defPlayer != null && "HUNTER".equals(defPlayer.getRole()) && dmg > 0) {
                    theftLine = vampStealOne(g, atkPlayer, defPlayer);
                }

                // messages lisibles
                // breakdown poussé en history
                List<String> atkBk = buildModBreakdownLines(g, r.getAttackerId(), "ATTACK",  r.getAttackerRoll());
                List<String> defBk = buildModBreakdownLines(g, r.getDefenderId(), "DEFENSE", r.getDefenderRoll());

                for (String ln : atkBk) addHistory(g, ln);
                for (String ln : defBk) addHistory(g, ln);

                if (r.getBreakdownLines() == null) r.setBreakdownLines(new ArrayList<>());
                r.getBreakdownLines().clear();
                r.getBreakdownLines().addAll(atkBk);
                r.getBreakdownLines().addAll(defBk);

                // résultat du fight
                String an = nameOf(g, r.getAttackerId());
                String dn = nameOf(g, r.getDefenderId());

                // history
                if (dmg > 0) { addHistory(g, an + " inflige " + dmg + " dégâts à " + dn); }
                else         { addHistory(g, dn + " pare l'attaque de " + an); }

                // Si c’est un duel "instable -> cible"
                if (g.getUnstableTargetByPlayer().containsKey(r.getAttackerId())
                        && java.util.Objects.equals(g.getUnstableTargetByPlayer().get(r.getAttackerId()), r.getDefenderId())) {
                    String backLine = nameOf(g, r.getAttackerId()) + " revient à lui ...";
                    addHistory(g, backLine);
                }

                // larcin en breakdownLines
                if (theftLine != null) {
                    r.getBreakdownLines().add(theftLine);
                }

                boolean vampInflicted = false;

                if (atkPlayer != null && "VAMPIRE".equals(atkPlayer.getRole()) && defPlayer != null && "HUNTER".equals(defPlayer.getRole()) && dmg > 0) {
                    vampInflicted = true;
                }

                if (vampInflicted) {
                    Game.BiteAttempt b = new Game.BiteAttempt();
                    b.setId(UUID.randomUUID().toString());
                    b.setAttackerId(atkPlayer.getId());
                    b.setTargetId(defPlayer.getId());
                    b.setLocation(r.getLocation());
                    g.setCurrentBite(b);
                    g.setCurrentBiteNextAdvanceAtMillis(0L); // on attend le jet du vampire
                    r.setResolvedAtMillis(now);
                    return; // on sort : l’UI va afficher la modale morsure
                }

                r.setResolvedAtMillis(now);
                g.setCurrentCombatNextAdvanceAtMillis(now + 5000L); // attend 5s avant d’enchaîner
                return; // on attend un prochain tick
            }


            // 2.b) Après le délai de 4s, passer au duel suivant OU planifier PHASE4 si c’était le dernier
            if (bothRolled && g.getCurrentCombatNextAdvanceAtMillis() > 0L && now >= g.getCurrentCombatNextAdvanceAtMillis()) {
                int nextIdx = g.getCurrentCombatIndex() + 1;
                if (nextIdx < g.getCombatsQueue().size()) {
                    g.setCurrentCombatIndex(nextIdx);
                    g.setCurrentCombat(g.getCombatsQueue().get(nextIdx));
                    g.setCurrentCombatNextAdvanceAtMillis(0L);
                } else {
                    // Fin des combats → planifie PHASE4 ET nettoie l’état de combat
                    g.setCurrentCombat(null);
                    g.setCurrentCombatIndex(null);
                    g.setCurrentCombatNextAdvanceAtMillis(0L);
                    planNextPhase(g, Phase.PHASE4);
                }
                return;
            }
        }
    }

// ---------- Sélection lieu ----------
    @Transactional
    public Game selectLocation(String gameId, String playerId, String card) {
        Game g = findOr404(gameId);
        maybeAutoAdvance(g); // au cas où une bascule planifiée arrive juste maintenant

        if (g.getStatus() != GameStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game not active");

        var p = g.getPlayers().stream().filter(pp -> pp.getId().equals(playerId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "not a player of this game"));

        switch (g.getPhase()) {
            case PHASE0 -> throw new ResponseStatusException(HttpStatus.CONFLICT, "weather selection in progress");
            case PHASE1 -> { if (!"HUNTER".equals(p.getRole()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "hunters phase"); }
            case PHASE2 -> {
                String role = p.getRole();
                if (!"VAMPIRE".equals(role) && !"SERVANT".equals(role)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire/servant phase");
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, "not a selection phase");
        }

        if (hasPlayed(g, playerId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already selected this round");

        var hand = p.getHand();
        if (hand == null || !hand.remove(card))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "card not in hand");

        g.getCenter().add(new CenterBoard(playerId, card, false));

        // Fenêtre d’actions : si tout le monde a joué, on **planifie** la phase suivante dans 5s
        if (g.getPhase() == Phase.PHASE1 && allHuntersSelected(g) && g.getPendingNextPhase() == null) {
            planNextPhase(g, Phase.PHASE2);
        } else if (g.getPhase() == Phase.PHASE2 && vampireSelected(g) && g.getPendingNextPhase() == null) {
            g.setHasUpcomingCombat(computeHasUpcomingCombat(g));
            planNextPhase(g, Phase.PREPHASE3);
        }

        save(g);
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
        // ici on exige que TOUS les joueurs de la partie aient cliqué "J’ai fini".
        // plus tard possible restreindre aux joueurs concernés par un combat.
        return g.getReadyForPhase3().containsAll(
                g.getPlayers().stream().map(Player::getId).toList()
        );
    }

    @Transactional
    public Game skipAction(String gameId, String playerId) {
        Game g = findOr404(gameId);
        maybeAutoAdvance(g);

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PREPHASE3");

        var present = g.getPlayers().stream().anyMatch(p -> p.getId().equals(playerId));
        if (!present)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not a player of this game");

        g.getReadyForPhase3().add(playerId);

        if (allReadyForPhase3(g)) {
            boolean stillPending =
                    !g.getUnstableEligibleTargets().isEmpty() || !g.getUnstableEligibleLocations().isEmpty();

            if (!stillPending) {
                // tout le monde a cliqué et plus aucun choix instable -> on passe en PHASE3
                g.setPendingNextPhase(Phase.PHASE3);
                g.setNextAutoAdvanceAtMillis(System.currentTimeMillis());
                maybeAutoAdvance(g);
            } else {
                // on NE bascule PAS : on attend la décision du vampire
                if (g.getMessages() == null) g.setMessages(new java.util.ArrayList<>());
                String line = "Chasseur instable ... En attente du contrôle par le vampire.";
                g.getMessages().add(line);
                addHistory(g, line);
            }
        }
        save(g);
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

        // A) Chasseurs instables réaffectés "à l'attaque"
        var unstableAttackers = new java.util.HashSet<>(g.getUnstableTargetByPlayer().keySet());

        // B) Chasseurs instables réaffectés "à la récolte"
        var unstableHarvesters = new java.util.HashSet<>(
                g.getUnstableHarvestLocByPlayer() != null
                        ? g.getUnstableHarvestLocByPlayer().keySet()
                        : java.util.Collections.<String>emptySet()
        );

        // Union pour exclure des combats par défaut
        var unstableAssigned = new java.util.HashSet<>(g.getUnstableTargetByPlayer().keySet());
        unstableAssigned.addAll(g.getUnstableHarvestLocByPlayer().keySet());

        var groups = groupPlayersByLocation(g);

        // 1) Combats par défaut : (ennemi ∈ {VAMPIRE,SERVANT}) × (HUNTER non instable-réaffecté)
        for (var e : groups.entrySet()) {
            String loc = e.getKey();
            java.util.List<Player> onLoc = e.getValue();

            // Ennemis = vampire(s) + serviteur(s) (ils ne se battent pas entre eux ici)
            var enemies = onLoc.stream()
                    .filter(p -> "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                    .toList();

            // Chasseurs qui restent disponibles pour les combats classiques
            var huntersForDefault = onLoc.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .filter(p -> !unstableAssigned.contains(p.getId())) // exclut instable->attaque ET instable->récolte
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

        // 2) Duels "instable -> cible" (ajoutés après la boucle)
        for (var entry : g.getUnstableTargetByPlayer().entrySet()) {
            String unstableId = entry.getKey();
            String targetId   = entry.getValue();

            // Lieu courant de la cible (après redirection)
            String loc = g.getCenter().stream()
                    .filter(cb -> cb.getPlayerId().equals(targetId))
                    .map(CenterBoard::getCard)
                    .findFirst()
                    .orElse("forest"); // fallback

            g.getCombatsQueue().add(new RoundFight(
                    java.util.UUID.randomUUID().toString(), loc, unstableId, targetId
            ));

            // Message "info"
            String line = "Combat — " + nameOf(g, unstableId) + " VS " + nameOf(g, targetId) + " à " + labelLieuFr(loc);
            if (g.getMessages() == null) g.setMessages(new java.util.ArrayList<>());
            g.getMessages().add(line);
            addHistory(g, line);
        }

        // 3) Initialiser le pointeur de combat courant
        if (!g.getCombatsQueue().isEmpty()) {
            g.setCurrentCombatIndex(0);
            g.setCurrentCombat(g.getCombatsQueue().get(0));
            g.setCurrentCombatNextAdvanceAtMillis(0L);
        } else {
            g.setCurrentCombatIndex(null);
            g.setCurrentCombat(null);
            g.setCurrentCombatNextAdvanceAtMillis(0L);
        }
    }

    @Transactional
    public Game rollDice(String gameId, String userId) {
        Game g = findOr404(gameId);

        // important pour évacuer une transition de phase/duel
        maybeAutoAdvance(g);

        if (g.getStatus() != GameStatus.ACTIVE || g.getPhase() != Phase.PHASE3 || g.getCurrentCombat() == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "not in combat");
        }

        var r = g.getCurrentCombat();

        // Le joueur doit être soit l'attaquant sans jet, soit le défenseur sans jet
        if (userId.equals(r.getAttackerId()) && r.getAttackerRoll() == null) {
            var p = g.getPlayers().stream().filter(pp -> pp.getId().equals(userId)).findFirst().orElseThrow();
            int sides = diceSides(p.getAttackDice());

            r.setAttackerRoll(1 + RND.nextInt(sides));

        } else if (userId.equals(r.getDefenderId()) && r.getDefenderRoll() == null) {
            var p = g.getPlayers().stream().filter(pp -> pp.getId().equals(userId)).findFirst().orElseThrow();
            int sides = diceSides(p.getDefenseDice());
            r.setDefenderRoll(1 + RND.nextInt(sides));
        } else {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "no roll expected from you now");
        }

        save(g);
        return g;
    }

// Météo
    // alimente raidMods
    private void rebuildWeatherMods(Game g){
        // 1) retire les mods existants de type WEATHER, en gardant les autres (cartes etc.)
        for (var entry : g.getRaidMods().entrySet()) {
            var list = entry.getValue();
            if (list == null) continue;
            list.removeIf(m -> m.getSource() != null && m.getSource().startsWith("WEATHER:"));
        }

        if (g.getWeatherStatus() == null) return;

        // 2) s'assurer que chaque joueur a une liste
        for (var p : g.getPlayers()) g.getRaidMods().computeIfAbsent(p.getId(), __ -> new java.util.ArrayList<>());

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
            case WIND -> { /* effet construction pas géré -> pas de mod de combat */ }
            case CLOUDY -> { /* aucun mod */ }
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
        }
    }

    @Transactional
    public Game rollWeather(String gameId, String userId) {
        Game g = findOr404(gameId);

        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing user id");
        }
        if (g.getPhase() != Phase.PHASE0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in weather phase");
        }

        var vamp = getVamp(g).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "no vampire")
        );
        if (!vamp.getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can roll weather");
        }
        if (g.getWeatherRoll() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "weather already rolled");
        }

        // safety: structures non-null
        if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());

        int roll = 1 + RND.nextInt(12);
        applyWeatherRoll(g, roll);

        save(g);
        return g;
    }

    private void applyWeatherRoll(@NonNull Game g, int roll){
        g.setWeatherRoll(roll);
        WeatherStatus ws = mapRollToWeather(roll);
        g.setWeatherStatus(ws);
        g.setWeatherStatusNameFr(weatherNameFr(ws));
        g.setWeatherDescriptionFr(weatherDescFr(ws));

        // (re)calcule les mods météo (affichage/combat)
        rebuildWeatherMods(g);

        // add in history
        addHistory(g, "Météo — " + g.getWeatherStatusNameFr());
        if (g.getWeatherDescriptionFr() != null && !g.getWeatherDescriptionFr().isBlank())
            addHistory(g, g.getWeatherDescriptionFr());

        long now = System.currentTimeMillis();

        // 1) Pendant la modale : NE RIEN AFFICHER au centre
        g.setMessages(new ArrayList<>()); // centre vide tant que la modale est ouverte

        // 2) La modale reste visible 5s
        g.setWeatherShowUntilMillis(now + 5_000L);

        // 3) Planifie le passage en PHASE1 dans 10s total (5s modale + 5s centre)
        planNextPhaseWithDelay(g, Phase.PHASE1, 10_000L);
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

            boolean vampHere   = (vamp != null) && onLoc.stream().anyMatch(p -> p.getId().equals(vamp.getId()));
            //on EXCLUT les instables-récolteurs
            boolean hunterCausingCombatHere = onLoc.stream()
                    .anyMatch(p -> "HUNTER".equals(p.getRole()) && !unstableHarvesters.contains(p.getId()));

            boolean combatHere = vampHere && hunterCausingCombatHere;

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

        // 1) Duels par défaut : (ennemis ∈ {VAMPIRE,SERVANT}) × CHASSEURS au même lieu
        var groups = groupPlayersByLocation(g);
        for (var e : groups.entrySet()) {
            var onLoc = e.getValue();

            var enemiesHere = onLoc.stream()
                    .filter(p -> "VAMPIRE".equals(p.getRole()) || "SERVANT".equals(p.getRole()))
                    .toList();

            var huntersHere = onLoc.stream()
                    .filter(p -> "HUNTER".equals(p.getRole()))
                    .toList();

            if (!enemiesHere.isEmpty() && !huntersHere.isEmpty()) {
                enemiesHere.forEach(p -> ids.add(p.getId()));
                huntersHere.forEach(p -> ids.add(p.getId()));
            }
        }

        // 2) Duels instable → cible : ajouter explicitement les 2 protagonistes
        g.getUnstableTargetByPlayer().forEach((unstableId, targetId) -> {
            if (unstableId != null) ids.add(unstableId);
            if (targetId   != null) ids.add(targetId);
        });

        return ids;
    }

    @Transactional
    public Game usePotion(String gameId, String userId, Potion type) {
        Game g = findOr404(gameId);
        maybeAutoAdvance(g);

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

        // appliquer l’effet + message
        switch (type) {
            case FORCE -> {
                if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());
                g.getRaidMods().computeIfAbsent(userId, __ -> new ArrayList<>())
                        .add(new StatMod("ATTACK", +1, "POTION:FORCE"));
                addHistory(g, nameOf(g, userId) + " utilise une Potion de force (+1 attaque ce raid).");
                pushLive(g, nameOf(g, userId) + " boit une Potion de force !");
            }
            case ENDURANCE -> {
                if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());
                g.getRaidMods().computeIfAbsent(userId, __ -> new ArrayList<>())
                        .add(new StatMod("DEFENSE", +1, "POTION:ENDURANCE"));
                addHistory(g, nameOf(g, userId) + " utilise une Potion d’endurance (+1 défense ce raid).");
                pushLive(g, nameOf(g, userId) + " boit une Potion d’endurance !");
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
                pushLive(g, nameOf(g, userId) + " boit une Potion de vie !");
            }
        }

        // consommer et sauver
        inv.remove(type.name());
        if (inv.isEmpty()) g.getPotionsByPlayer().remove(userId);

        save(g);
        return g;
    }

    private void pushLive(Game g, String msg){
        if (g.getMessages() == null) g.setMessages(new ArrayList<>());
        g.getMessages().add(msg);
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
        maybeAutoAdvance(g);

        if (g.getPhase() != Phase.PREPHASE3)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "not in PREPHASE3");

        var vamp = getVamp(g).orElseThrow();
        if (!vamp.getId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can assign");

        // Si cet instable n'est plus éligible (déjà choisi), on refuse
        boolean eligibleNow = g.getUnstableEligibleTargets().containsKey(unstableId)
                || g.getUnstableEligibleLocations().containsKey(unstableId);
        if (!eligibleNow)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no pending unstable choice");

        var elig = g.getUnstableEligibleTargets().get(unstableId);
        if (elig == null || !elig.contains(targetId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid target");

        // 1) Enregistre la cible et invalide l’autre choix (harvest)
        g.getUnstableTargetByPlayer().put(unstableId, targetId);
        g.getUnstableHarvestLocByPlayer().remove(unstableId);

        // 2) Redirige la carte (rendre l’ancienne, retirer UNE occurrence de la nouvelle)
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

            addHistory(g, nameOf(g, unstableId) + " est redirigé vers " + labelLieuFr(targetLoc) + " par le vampire.");
        }

        // 2.b) Remplacer la récolte du chasseur ciblé par un message de combat (PREPHASE3)
        if (targetLoc != null) {
            String targetName = nameOf(g, targetId);
            String unstableName = nameOf(g, unstableId);
            String locLabel = labelLieuFr(targetLoc);

            String interruptionLine = "Récolte de " + targetName + " interrompue par " + unstableName + ".";
            String combatLine = "Combat — " + unstableName + " VS " + targetName + " à " + locLabel + ".";

            g.getMessages().add(combatLine);
            addHistory(g, interruptionLine);
            addHistory(g, combatLine);
        }

        // 3) Consomme TOUTE l’éligibilité pour CET instable (on ferme ce cas)
        g.getUnstableEligibleTargets().remove(unstableId);
        g.getUnstableEligibleLocations().remove(unstableId);

        // 4) S’il ne reste aucun instable en attente, on planifie PHASE3
        boolean anyPending = !(g.getUnstableEligibleTargets().isEmpty() && g.getUnstableEligibleLocations().isEmpty());
        if (!anyPending && g.getPendingNextPhase() == null) {
            long window = g.isHasUpcomingCombat() ? PREPHASE3_WINDOW_MS : 4000L;
            g.setPrePhaseDeadlineMillis(System.currentTimeMillis() + window);
            planNextPhaseWithDelay(g, Phase.PHASE3, window);
        }

        save(g);
        return g;
    }

    @Transactional
    public Game assignUnstableHarvest(String gameId, String userId, String unstableId, String loc) {
        Game g = findOr404(gameId);
        maybeAutoAdvance(g);

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

        // 1) Enregistre le lieu de récolte et invalide l’autre choix (target)
        g.getUnstableHarvestLocByPlayer().put(unstableId, loc);
        g.getUnstableTargetByPlayer().remove(unstableId);

        // 2) Redirige la carte (rendre l’ancienne, retirer UNE occurrence de la nouvelle)
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

        g.getMessages().add(nameOf(g, unstableId) + " récoltera à " + labelLieuFr(loc) + " pour " + nameOf(g, vamp.getId()) + ".");
        addHistory(g, nameOf(g, unstableId) + " récoltera à " + labelLieuFr(loc) + " pour " + nameOf(g, vamp.getId()) + ".");

        // 3) Consomme TOUTE l’éligibilité pour CET instable
        g.getUnstableEligibleTargets().remove(unstableId);
        g.getUnstableEligibleLocations().remove(unstableId);

        // 4) Passage vers PHASE3 s’il ne reste plus rien en attente
        boolean anyPending = !(g.getUnstableEligibleTargets().isEmpty() && g.getUnstableEligibleLocations().isEmpty());
        if (!anyPending && g.getPendingNextPhase() == null) {
            long window = g.isHasUpcomingCombat() ? PREPHASE3_WINDOW_MS : 4000L;
            g.setPrePhaseDeadlineMillis(System.currentTimeMillis() + window);
            planNextPhaseWithDelay(g, Phase.PHASE3, window);
        }

        save(g);
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
        maybeAutoAdvance(g);

        if (g.getPhase() != Phase.PHASE3 || g.getCurrentBite() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "no bite to resolve");

        var b = g.getCurrentBite();
        if (!userId.equals(b.getAttackerId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only vampire can roll the bite");

        if (b.getRoll() != null)
            return g; // déjà lancé

        int roll = 1 + RND.nextInt(6);
        b.setRoll(roll);

        var target = g.getPlayers().stream().filter(p -> p.getId().equals(b.getTargetId())).findFirst().orElse(null);
        var vamp   = g.getPlayers().stream().filter(p -> p.getId().equals(b.getAttackerId())).findFirst().orElse(null);

        if (target != null && roll > 3) {
            int before = target.getCorruption();
            int after  = Math.min(3, before + 1);
            target.setCorruption(after);

            String line = nameOf(g, target.getId()) + " se fait mordre… son niveau de corruption passe à " + after + ".";
            addHistory(g, line);

            if (after == 3) {
                target.setCorruption(after);
                // Passage SERVANT
                target.setRole("SERVANT");
                // or -> âmes
                target.setSouls(target.getSouls() + target.getGold());
                target.setGold(0);

                // défausser ses cartes actions chasseur si tu les stockes (à faire ici si présent)
                // TODO: purgeInventaireActionsChasseur(target)

                // Les mods de corruption : on retirera le chip au prochain début de raid.
            }

            // Met à jour les mods d'affichage pour CE raid
            rebuildCorruptionMods(g);
        } else {
            String line = nameOf(g, vamp != null ? vamp.getId() : "???") + " échoue sa tentative de morsure.";
            addHistory(g, line);
        }

        b.setResolvedAtMillis(System.currentTimeMillis());
        g.setCurrentBiteNextAdvanceAtMillis(System.currentTimeMillis() + 5000L); // 5s d’affichage
        save(g);
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
