package org.castello.game;

import org.castello.player.Player;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameService {
    private final Map<String, Game> store = new ConcurrentHashMap<>();

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

    // ---------- CRUD ----------
    public Game create() {
        String id = UUID.randomUUID().toString();
        Game game = new Game(id, GameStatus.CREATED, 0);
        store.put(id, game);
        return game;
    }

    public Collection<Game> list() { return store.values(); }

    public Game findOr404(String id) {
        Game g = store.get(id);
        if (g == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found");
        return g;
    }

    // ---------- LOBBY ----------
    public Game addOrUpdatePlayer(String gameId, String playerId, String nickname) {
        if (nickname == null || nickname.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nickname required");
        Game g = findOr404(gameId);
        if (g.getStatus() != GameStatus.CREATED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "game already started/ended");

        g.getPlayers().stream()
                .filter(p -> p.getId().equals(playerId))
                .findFirst()
                .ifPresentOrElse(
                        p -> p.setNickname(nickname),
                        () -> g.getPlayers().add(new Player(playerId, nickname))
                );
        return g;
    }

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

        // compteurs + centre
        g.setVampActionsLeft(20);    g.setVampActionsDiscard(0);
        g.setHunterActionsLeft(35);  g.setHunterActionsDiscard(0);
        g.setPotionsLeft(22);        g.setPotionsDiscard(0);
        g.setCenter(new ArrayList<>());

        // clear auto-advance
        g.setPendingNextPhase(null);
        g.setNextAutoAdvanceAtMillis(0);

        return g;
    }

    // ---------- TICK ----------
    public Game tickAndGet(String gameId) {
        Game g = findOr404(gameId);
        maybeAutoAdvance(g);
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
                // Pour Step 3: on n’implémente pas encore la résolution détaillée.
                // On laisse les messages déjà construits en PREPHASE3 s'afficher.
                // (Plus tard: affichage combat séquentiel, jets de dés, etc.)
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

        // 1) Si un passage de phase est planifié et l’heure atteinte → on applique
        if (g.getPendingNextPhase() != null &&
                g.getNextAutoAdvanceAtMillis() != 0 &&
                now >= g.getNextAutoAdvanceAtMillis()) {
            applyPendingPhase(g);
            return;
        }

        // 2) Sinon, si on est bloqué depuis trop longtemps → on force des choix aléatoires
        forcePicksIfTimedOut(g);
    }

    // ---------- Sélection lieu ----------
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
                            huntersHere.stream().map(p -> p.getNickname() != null && !p.getNickname().isBlank() ? p.getNickname() : p.getId()).toList()
                    );
                    String vampName = vamp.getNickname() != null && !vamp.getNickname().isBlank() ? vamp.getNickname() : vamp.getId();
                    out.add("Combat — " + vampName + " VS " + huntersNames + " à " + labelLieuFr(loc));
                } else {
                    // Récoltes indépendantes
                    if (vampHere) {
                        String vampName = vamp.getNickname() != null && !vamp.getNickname().isBlank() ? vamp.getNickname() : vamp.getId();
                        out.add("Récolte de " + labelLieuFr(loc) + " par le vampire (" + vampName + ")");
                    }
                    if (!huntersHere.isEmpty()) {
                        for (var h : huntersHere) {
                            String hn = h.getNickname() != null && !h.getNickname().isBlank() ? h.getNickname() : h.getId();
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
                    String n = p.getNickname() != null && !p.getNickname().isBlank() ? p.getNickname() : p.getId();
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
        // plus tard tu pourras restreindre aux joueurs concernés par un combat.
        return g.getReadyForPhase3().containsAll(
                g.getPlayers().stream().map(Player::getId).toList()
        );
    }

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
        return g;
    }
}