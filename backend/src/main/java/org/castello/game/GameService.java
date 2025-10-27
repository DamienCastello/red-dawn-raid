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
    private static final long PREPHASE3_WINDOW_MS = 20_000L; // 20 s avant PHASE3

    // ---------- utilitaires ----------
    private static final Random RND = new Random();

    private boolean computeHasUpcomingCombat(@NonNull Game g) {
        var vampOpt = getVamp(g);
        if (vampOpt.isEmpty()) return false;
        var vamp = vampOpt.get();
        var groups = groupPlayersByLocation(g);
        for (var e : groups.entrySet()) {
            var onLoc = e.getValue();
            boolean vampHere = onLoc.stream().anyMatch(p -> p.getId().equals(vamp.getId()));
            boolean hunterHere = onLoc.stream().anyMatch(p -> "HUNTER".equals(p.getRole()));
            if (vampHere && hunterHere) return true;
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

    // retourne la liste des mods VRAIMENT appliqués (bonne stat + pas supprimés par le lieu/météo)
    private List<StatMod> modsAppliedFor(Game g, String playerId, String stat){
        var all = g.getRaidMods() != null ? g.getRaidMods().get(playerId) : null;
        if (all == null) return java.util.List.of();
        return all.stream()
                .filter(m -> stat.equalsIgnoreCase(m.getStat()))
                .toList();
    }

    // somme des mods
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

            String src = "par un effet";
            if (m.getSource() != null && m.getSource().startsWith("WEATHER:")) {
                try {
                    var wsStr = m.getSource().substring("WEATHER:".length());
                    var ws = WeatherStatus.valueOf(wsStr);
                    src = "par l’effet " + weatherNameFr(ws).toLowerCase();
                } catch (Exception ignored) { /* fallback simple */ }
            } else if (m.getDescription() != null && !m.getDescription().isBlank()) {
                src = "par un effet (" + m.getDescription() + ")";
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
                g.setMessages(new ArrayList<>(java.util.List.of("Préparation : tirage météo…")));

                if (g.getRaidMods() == null) g.setRaidMods(new HashMap<>());
                rebuildWeatherMods(g);

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
                // révélation + messages + fenêtre d’actions
                g.setMessages(buildRevealMessages(g));
                for (var m : g.getMessages()) addHistory(g, m);
                g.getReadyForPhase3().clear();
                long now = System.currentTimeMillis();
                // Si combat à venir -> fenêtre normale, sinon fenêtre très courte (pas besoin du bouton)
                long window = g.isHasUpcomingCombat() ? PREPHASE3_WINDOW_MS : 4000L;
                g.setPrePhaseDeadlineMillis(now + window);
                planNextPhaseWithDelay(g, Phase.PHASE3, window);
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

    private void maybeAutoAdvance(@NonNull Game g) {
        long now = System.currentTimeMillis();

        // 1) appliquer une phase planifiée si l’heure est venue
        if (g.getPendingNextPhase() != null &&
                g.getNextAutoAdvanceAtMillis() != 0 &&
                now >= g.getNextAutoAdvanceAtMillis()) {
            applyPendingPhase(g);
            return;
        }

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
            if (bothRolled && g.getCurrentCombatNextAdvanceAtMillis() == 0L) {
                int atk = r.getAttackerRoll();
                int def = r.getDefenderRoll();

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

                // larcin en breakdownLines
                if (theftLine != null) {
                    r.getBreakdownLines().add(theftLine);
                }

                // résultat du fight
                String an = nameOf(g, r.getAttackerId());
                String dn = nameOf(g, r.getDefenderId());
                if (dmg > 0) g.getMessages().add(an + " inflige " + dmg + " dégâts à " + dn);
                else        g.getMessages().add(dn + " pare l'attaque de " + an);

                // history
                if (dmg > 0) { addHistory(g, an + " inflige " + dmg + " dégâts à " + dn); }
                else         { addHistory(g, dn + " pare l'attaque de " + an); }

                r.setResolvedAtMillis(now);
                g.setCurrentCombatNextAdvanceAtMillis(now + 5000L); // attend 4s avant d’enchaîner
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
            case PHASE2 -> { if (!"VAMPIRE".equals(p.getRole()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "vampire phase"); }
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
        var vampOpt = getVamp(g);
        Player vamp = vampOpt.orElse(null);

        // révéler toutes les cartes
        for (var cb : g.getCenter()) cb.setFaceUp(true);

        var groups = groupPlayersByLocation(g);

        if (vamp != null) {
            for (var e : groups.entrySet()) {
                String loc = e.getKey();
                List<Player> onLoc = e.getValue();
                boolean vampHere = onLoc.stream().anyMatch(p -> p.getId().equals(vamp.getId()));
                var huntersHere = onLoc.stream().filter(p -> "HUNTER".equals(p.getRole())).toList();

                if (vampHere && !huntersHere.isEmpty()) {
                    // Combat
                    String huntersNames = String.join(", ",
                            huntersHere.stream().map(p -> p.getUsername() != null && !p.getUsername().isBlank() ? p.getUsername() : p.getId()).toList()
                    );
                    String vampName = vamp.getUsername() != null && !vamp.getUsername().isBlank() ? vamp.getUsername() : vamp.getId();
                    out.add("Combat — " + vampName + " VS " + huntersNames + " à " + labelLieuFr(loc));
                } else {
                    // Récoltes indépendantes
                    if (vampHere) {
                        String vampName = vamp.getUsername() != null && !vamp.getUsername().isBlank() ? vamp.getUsername() : vamp.getId();
                        out.add("Récolte de " + labelLieuFr(loc) + " par le vampire (" + vampName + ")");
                    }
                    if (!huntersHere.isEmpty()) {
                        for (var h : huntersHere) {
                            String hn = h.getUsername() != null && !h.getUsername().isBlank() ? h.getUsername() : h.getId();
                            out.add("Récolte de " + labelLieuFr(loc) + " par " + hn);
                        }
                    }
                }
            }
        } else {
            // Pas de vampire (sécurité) → tout le monde récolte
            for (var e : groups.entrySet()) {
                String loc = e.getKey();
                for (var p : e.getValue()) {
                    String n = p.getUsername() != null && !p.getUsername().isBlank() ? p.getUsername() : p.getId();
                    out.add("Récolte de " + labelLieuFr(loc) + " par " + n);
                }
            }
        }

        if (out.isEmpty()) {
            out.add("Aucune carte jouée.");
        }
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
            // tout le monde a cliqué → on bascule vers PHASE3 immédiatement
            g.setPendingNextPhase(Phase.PHASE3);
            g.setNextAutoAdvanceAtMillis(System.currentTimeMillis());
            maybeAutoAdvance(g);
        }
        save(g);
        return g;
    }

    /** Construit la file des duels (PHASE3) :
     * pour chaque chasseur co-localisé avec le vampire, on pousse 2 rounds :
     * Hunter->Vamp puis Vamp->Hunter (chasseur commence toujours). */
    private void buildCombatsQueue(Game g) {
        g.getCombatsQueue().clear();

        var vampOpt = getVamp(g);
        if (vampOpt.isEmpty()) {
            g.setCurrentCombatIndex(null);
            g.setCurrentCombat(null);
            g.setCurrentCombatNextAdvanceAtMillis(0L);
            return;
        }
        var vamp = vampOpt.get();

        var groups = groupPlayersByLocation(g); // ta méthode existante
        for (var e : groups.entrySet()) {
            String loc = e.getKey();
            var onLoc = e.getValue();
            boolean vampHere = onLoc.stream().anyMatch(p -> p.getId().equals(vamp.getId()));
            if (!vampHere) continue;

            var huntersHere = onLoc.stream().filter(p -> "HUNTER".equals(p.getRole())).toList();
            for (var h : huntersHere) {
                // chasseur attaque d'abord
                g.getCombatsQueue().add(new RoundFight(java.util.UUID.randomUUID().toString(), loc, h.getId(), vamp.getId()));
                // puis le vampire attaque le chasseur
                g.getCombatsQueue().add(new RoundFight(java.util.UUID.randomUUID().toString(), loc, vamp.getId(), h.getId()));
            }
        }

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
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +1, "WEATHER:SUNNY", "+1 attaque (météo : jour ensoleillé)"));
                    if ("VAMPIRE".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", -1, "WEATHER:SUNNY", "−1 défense (météo : jour ensoleillé)"));
                }
            }
            case FOG -> {
                for (var p : g.getPlayers())
                    if ("HUNTER".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +1, "WEATHER:FOG", "+1 attaque (météo : brouillard)"));
            }
            case AURORA -> {
                for (var p : g.getPlayers())
                    if ("VAMPIRE".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", -1, "WEATHER:AURORA", "−1 défense (météo : aurore)"));
            }
            case WIND -> { /* effet construction pas géré -> pas de mod de combat */ }
            case CLOUDY -> { /* aucun mod */ }
            case STORM -> {
                for (var p : g.getPlayers())
                    g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", -2, "WEATHER:STORM", "−2 défense (météo : orage)"));
            }
            case RAIN -> {
                for (var p : g.getPlayers())
                    g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", -2, "WEATHER:RAIN", "−2 attaque (météo : pluie diluvienne)"));
            }
            case BLIZZARD -> {
                for (var p : g.getPlayers())
                    g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", -1, "WEATHER:BLIZZARD", "−1 attaque (météo : blizzard)"));
            }
            case DUSK -> {
                for (var p : g.getPlayers())
                    if ("VAMPIRE".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", +1, "WEATHER:DUSK", "+1 défense (météo : crépuscule)"));
            }
            case NIGHT_DARK -> {
                for (var p : g.getPlayers())
                    if ("VAMPIRE".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +1, "WEATHER:NIGHT_DARK", "+1 attaque (météo : nuit obscure)"));
            }
            case NIGHT_CLEAR -> {
                for (var p : g.getPlayers()) {
                    if ("VAMPIRE".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +1, "WEATHER:NIGHT_CLEAR", "+1 attaque (météo : nuit claire)"));
                    if ("HUNTER".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("DEFENSE", -1, "WEATHER:NIGHT_CLEAR", "−1 défense (météo : nuit claire)"));
                }
            }
            case FULL_MOON -> {
                for (var p : g.getPlayers())
                    if ("VAMPIRE".equals(p.getRole()))
                        g.getRaidMods().get(p.getId()).add(new StatMod("ATTACK", +2, "WEATHER:FULL_MOON", "+2 attaque (météo : pleine lune)"));
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

        for (var e : groups.entrySet()) {
            String loc = e.getKey();
            var onLoc = e.getValue();

            boolean vampHere   = (vamp != null) && onLoc.stream().anyMatch(p -> p.getId().equals(vamp.getId()));
            boolean hunterHere = onLoc.stream().anyMatch(p -> "HUNTER".equals(p.getRole()));
            boolean combatHere = vampHere && hunterHere;
            if (combatHere) continue; // pas de récolte sur un lieu où il y a combat

            for (var p : onLoc) {
                java.util.List<String> gains = new java.util.ArrayList<>();
                switch (loc) {
                    case "forest" -> {
                        grant(p, "wood", 1);  gains.add("+1 bois");
                        grant(p, "herbs", 2); gains.add("+2 herbe médicinale");
                    }
                    case "quarry" -> {
                        grant(p, "iron", 1);  gains.add("+1 fer");
                        grant(p, "stone", 2); gains.add("+2 pierre");
                    }
                    case "lake" -> {
                        grant(p, "water", 2); gains.add("+2 eau pure");
                        grant(p, "herbs", 1); gains.add("+1 herbe médicinale");
                    }
                    case "manor" -> {
                        int roll = rollD100Tens();
                        if ("HUNTER".equals(p.getRole())) {
                            grant(p, "gold", roll); gains.add("+" + roll + " or");
                        } else if ("VAMPIRE".equals(p.getRole())) {
                            grant(p, "souls", roll); gains.add("+" + roll + " âmes déchues");
                        }
                    }
                    default -> { /* autres cartes / infrastructures plus tard */ }
                }

                if (!gains.isEmpty()) {
                    String name = nameOf(g, p.getId());
                    String line = "Récoltes — " + name + " (" + labelLieuFr(loc) + ") : " + String.join(", ", gains);
                    if (g.getMessages() == null) g.setMessages(new java.util.ArrayList<>());
                    g.getMessages().add(line);
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
            return null; // ← rien à afficher en breakdown
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
        if (g.getMessages() == null) g.setMessages(new java.util.ArrayList<>());
        g.getMessages().add(line);
        addHistory(g, line);

        return line; // ← on renvoie la ligne pour la modale spectateur
    }
}
