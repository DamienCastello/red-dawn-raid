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
    private static final long PHASE_FORCE_MS = 30000L;  // 30 s (au-delà, on force des choix aléatoires)
    private static final long PREPHASE3_WINDOW_MS = 20_000L; // 20 s avant PHASE3

    // ---------- utilitaires ----------
    private static final Random RND = new Random();

    @Nullable
    private String pickRandomCardFromHand(@NonNull Player p) {
        var hand = p.getHand();
        if (hand == null || hand.isEmpty()) return null;
        int idx = RND.nextInt(hand.size());
        return hand.get(idx);
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

        // PHASE1 (chasseurs)
        g.setPhase(Phase.PHASE1);
        g.setPhaseStartMillis(System.currentTimeMillis());

        // rôles + mains
        int vampIndex = RND.nextInt(g.getPlayers().size());
        for (int i = 0; i < g.getPlayers().size(); i++) {
            Player p = g.getPlayers().get(i);
            p.setRole(i == vampIndex ? "VAMPIRE" : "HUNTER");
            p.setHand(new ArrayList<>(List.of("foret", "carriere", "lac", "manoir")));
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
            case PREPHASE3 -> {
                // révélation + messages + fenêtre d’actions
                g.setMessages(buildRevealMessages(g));
                g.getReadyForPhase3().clear();
                g.setPrePhaseDeadlineMillis(System.currentTimeMillis() + PREPHASE3_WINDOW_MS);
                // plan préventif vers PHASE3 à la fin de la fenêtre (si pas allReady avant)
                planNextPhaseWithDelay(g, Phase.PHASE3, PREPHASE3_WINDOW_MS);
            }
            case PHASE3 -> {
                // On lance la file de combats (duels séquentiels)
                buildCombatsQueue(g);
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
                // Préparer le raid suivant : retour PHASE0 (météo) → PHASE1
                g.setRaid(g.getRaid() + 1);
                planNextPhase(g, Phase.PHASE0);
            }
            default -> { /* rien de particulier */ }
        }
    }

    private void forcePicksIfTimedOut(@NonNull Game g) {
        long now = System.currentTimeMillis();
        if (g.getPhaseStartMillis() == 0) g.setPhaseStartMillis(now);
        long elapsed = now - g.getPhaseStartMillis();

        if (elapsed < PHASE_FORCE_MS) return; // pas encore l’heure de forcer

        switch (g.getPhase()) {
            case PHASE1 -> {
                // Forcer les chasseurs qui n’ont pas joué
                for (var h : getHunters(g)) {
                    if (!hasPlayed(g, h.getId())) {
                        String card = pickRandomCardFromHand(h);
                        if (card != null) {
                            h.getHand().remove(card);
                            g.getCenter().add(new CenterBoard(h.getId(), card, false));
                        }
                    }
                }
                // Si au moins un pick a été ajouté, planifier la PHASE2 (avec petit délai)
                if (allHuntersSelected(g) && g.getPendingNextPhase() == null) {
                    planNextPhase(g, Phase.PHASE2);
                }
            }
            case PHASE2 -> {
                // Forcer le vampire s’il n’a pas joué
                var vampOpt = getVamp(g);
                if (vampOpt.isPresent() && !hasPlayed(g, vampOpt.get().getId())) {
                    var v = vampOpt.get();
                    String card = pickRandomCardFromHand(v);
                    if (card != null) {
                        v.getHand().remove(card);
                        g.getCenter().add(new CenterBoard(v.getId(), card, false));
                    }
                }
                if (vampireSelected(g) && g.getPendingNextPhase() == null) {
                    planNextPhase(g, Phase.PHASE3);
                }
            }
            default -> { /* pas de force pick sur les autres phases ici */ }
        }
    }

    private void maybeAutoAdvance(@NonNull Game g) {
        long now = System.currentTimeMillis();

        // 1) Appliquer en priorité une phase planifiée si l’heure est venue
        if (g.getPendingNextPhase() != null &&
                g.getNextAutoAdvanceAtMillis() != 0 &&
                now >= g.getNextAutoAdvanceAtMillis()) {
            applyPendingPhase(g);
            return;
        }

        // 2) Cadencer les combats (PHASE3)
        if (g.getPhase() == Phase.PHASE3 && g.getCurrentCombat() != null) {
            var r = g.getCurrentCombat();
            boolean bothRolled = r.getAttackerRoll() != null && r.getDefenderRoll() != null;

            // 2.a) Quand les 2 ont jeté le dé → appliquer dégâts + planifier +4s vers "duel suivant"
            if (bothRolled && g.getCurrentCombatNextAdvanceAtMillis() == 0L) {
                int atk = r.getAttackerRoll();
                int def = r.getDefenderRoll();
                int dmg = Math.max(0, atk - def);

                // dégâts sur le défenseur (jamais négatif)
                var defPlayer = g.getPlayers().stream()
                        .filter(p -> p.getId().equals(r.getDefenderId()))
                        .findFirst().orElse(null);
                if (defPlayer != null && dmg > 0) {
                    defPlayer.setHp(Math.max(0, defPlayer.getHp() - dmg));
                }

                // message lisible
                String an = nameOf(g, r.getAttackerId());
                String dn = nameOf(g, r.getDefenderId());
                if (dmg > 0) g.getMessages().add(an + " inflige " + dmg + " dégâts à " + dn);
                else        g.getMessages().add(dn + " pare l'attaque de " + an);

                r.setResolvedAtMillis(now);
                g.setCurrentCombatNextAdvanceAtMillis(now + 4000L); // attend 4s avant d’enchaîner
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

        // 3) Si on n’a pas de phase planifiée à appliquer et pas en combat → logique de “force pick”
        forcePicksIfTimedOut(g);
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
            case "foret" -> "Forêt";
            case "carriere" -> "Carrière";
            case "lac" -> "Lac";
            case "manoir" -> "Manoir";
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

        if (g.getPhase() != Phase.PREPHASE3) // <-- garde ta logique, juste persistance derrière
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

}
